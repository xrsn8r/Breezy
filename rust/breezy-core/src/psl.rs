//! Compact Public Suffix List resolver.
//!
//! adblock-rust needs to know each host's registrable domain (eTLD+1) for `$third-party` and
//! `$domain` matching. Its `embedded-domain-resolver` feature does this with the `psl` crate,
//! which compiles the whole list into ~650 KiB of matching code: a third of the native library.
//! Instead we embed the list as text (~150 KiB, IDN rules pre-converted to punycode) and parse it
//! into hash sets on first use.
//! Brave does the same thing with Chromium's own registry.

use std::collections::HashSet;
use std::sync::OnceLock;

use adblock::url_parser::ResolvesDomain;

static LIST: &str = include_str!("../data/public_suffix_list.txt");

struct Rules {
    exact: HashSet<String>,
    /// `*.foo` rules, stored as `foo`.
    wildcard: HashSet<String>,
    /// `!bar.foo` exceptions, stored as `bar.foo`.
    exception: HashSet<String>,
}

fn rules() -> &'static Rules {
    static RULES: OnceLock<Rules> = OnceLock::new();
    RULES.get_or_init(|| {
        let mut r = Rules {
            exact: HashSet::with_capacity(10_500),
            wildcard: HashSet::new(),
            exception: HashSet::new(),
        };
        for line in LIST.lines() {
            let line = line.trim();
            if line.is_empty() || line.starts_with("//") {
                continue;
            }
            // The embedded list is pre-converted to punycode, matching how hosts reach us.
            if let Some(rest) = line.strip_prefix('!') {
                r.exception.insert(rest.to_owned());
            } else if let Some(rest) = line.strip_prefix("*.") {
                r.wildcard.insert(rest.to_owned());
            } else {
                r.exact.insert(line.to_owned());
            }
        }
        r
    })
}

/// Number of labels in the public suffix of `host` (standard PSL algorithm; the implicit `*`
/// rule makes an unknown TLD a suffix). `host` must be lowercase ASCII without a trailing dot.
fn suffix_labels(host: &str) -> usize {
    let r = rules();
    let labels = host.split('.').count();
    // Walk suffixes from longest to shortest: "a.b.c", "b.c", "c".
    let mut best = 1;
    let mut start = 0;
    for i in 0..labels {
        let candidate = &host[start..];
        let n = labels - i;
        if r.exception.contains(candidate) {
            // An exception rule wins outright: its suffix is one label shorter.
            return n - 1;
        }
        if r.exact.contains(candidate) {
            best = best.max(n);
        }
        // "*.foo" matches when `candidate` is "x.foo".
        if let Some(dot) = candidate.find('.')
            && r.wildcard.contains(&candidate[dot + 1..])
        {
            best = best.max(n);
        }
        match candidate.find('.') {
            Some(dot) => start += dot + 1,
            None => break,
        }
    }
    best
}

fn is_valid_domain(host: &str) -> bool {
    !host.is_empty()
        && !host.starts_with('.')
        && !host.ends_with('.')
        && !host.contains("..")
        && host.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'.' || b == b'_')
        // IPv4 addresses aren't domains.
        && !host.bytes().all(|b| b.is_ascii_digit() || b == b'.')
}

pub struct PslResolver;

impl ResolvesDomain for PslResolver {
    fn get_host_domain(&self, host: &str) -> (usize, usize) {
        if host.is_empty() {
            return (0, 0);
        }
        if !is_valid_domain(host) {
            return (0, host.len());
        }
        let lower;
        let h = if host.bytes().any(|b| b.is_ascii_uppercase()) {
            lower = host.to_ascii_lowercase();
            lower.as_str()
        } else {
            host
        };
        let suffix = suffix_labels(h);
        let labels = h.split('.').count();
        if labels <= suffix {
            // The host is itself a public suffix: the whole host.
            return (0, host.len());
        }
        // Registrable domain = suffix + one more label; find where it starts.
        let keep = suffix + 1;
        let mut dots_from_end = 0;
        for (i, b) in h.bytes().enumerate().rev() {
            if b == b'.' {
                dots_from_end += 1;
                if dots_from_end == keep {
                    return (i + 1, host.len());
                }
            }
        }
        (0, host.len())
    }

    fn is_public_suffix(&self, domain: &str) -> bool {
        if !is_valid_domain(domain) {
            return !domain.contains('.');
        }
        let d = domain.to_ascii_lowercase();
        d.split('.').count() <= suffix_labels(&d)
    }
}

/// Install the resolver (idempotent). Must run before any engine use.
pub fn install() {
    static ONCE: std::sync::Once = std::sync::Once::new();
    ONCE.call_once(|| {
        let _ = adblock::url_parser::set_domain_resolver(Box::new(PslResolver));
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    fn domain(h: &str) -> &str {
        let (s, e) = PslResolver.get_host_domain(h);
        &h[s..e]
    }

    #[test]
    fn registrable_domains() {
        assert_eq!(domain("www.example.com"), "example.com");
        assert_eq!(domain("a.b.example.co.uk"), "example.co.uk");
        assert_eq!(domain("example.co.uk"), "example.co.uk");
        assert_eq!(domain("co.uk"), "co.uk");
        assert_eq!(domain("user.github.io"), "user.github.io"); // private section
        assert_eq!(domain("x.y.unknowntld"), "y.unknowntld");
        assert_eq!(domain("192.168.1.1"), "192.168.1.1");
        assert_eq!(domain("localhost"), "localhost");
        // Wildcard + exception: "*.ck" and "!www.ck".
        assert_eq!(domain("a.b.ck"), "a.b.ck");
        assert_eq!(domain("www.ck"), "www.ck");
        assert_eq!(domain("x.www.ck"), "www.ck");
        assert!(PslResolver.is_public_suffix("co.uk"));
        assert!(!PslResolver.is_public_suffix("example.co.uk"));
    }
}
