//! Differential test: src/psl.rs must agree with the `addr`/`psl` crates that adblock-rust's
//! `embedded-domain-resolver` feature uses, on real-world hosts and on PSL edge cases.

use adblock::url_parser::ResolvesDomain;
use addr::parser::DomainName;
use addr::psl::List;
use breezy_core::psl::PslResolver;

fn reference(host: &str) -> (usize, usize) {
    if host.is_empty() {
        return (0, 0);
    }
    match List.parse_domain_name(host) {
        Err(_) => (0, host.len()),
        Ok(d) => {
            let len = d.root().unwrap_or_else(|| d.suffix()).len();
            (host.len() - len, host.len())
        }
    }
}

fn hosts() -> Vec<String> {
    let mut out: Vec<String> = [
        "example.com",
        "www.example.com",
        "a.b.c.example.co.uk",
        "co.uk",
        "uk",
        "com",
        "user.github.io",
        "github.io",
        "x.blogspot.com",
        "foo.s3.amazonaws.com",
        "a.b.ck",
        "www.ck",
        "x.www.ck",
        "city.kawasaki.jp",
        "a.city.kawasaki.jp",
        "b.kawasaki.jp",
        "x.y.unknowntld",
        "localhost",
        "192.168.0.1",
        "xn--fiqs8s",
        "a.xn--fiqs8s",
        "foo.appspot.com",
        "a.b.c.d.e.f",
    ]
    .iter()
    .map(|s| s.to_string())
    .collect();
    // Every `||host^` in the bundled filter lists, when they've been fetched by the app build.
    let dir = concat!(env!("CARGO_MANIFEST_DIR"), "/../../app/build/filterlists");
    if let Ok(entries) = std::fs::read_dir(dir) {
        for e in entries.flatten() {
            if let Ok(text) = std::fs::read_to_string(e.path()) {
                for line in text.lines() {
                    if let Some(rest) = line.strip_prefix("||") {
                        let host: String = rest
                            .chars()
                            .take_while(|c| c.is_ascii_alphanumeric() || *c == '.' || *c == '-')
                            .collect();
                        if host.contains('.') && !host.ends_with('.') {
                            out.push(host.to_ascii_lowercase());
                        }
                    }
                }
            }
        }
    }
    out.sort();
    out.dedup();
    out
}

#[test]
fn matches_reference_resolver() {
    let hosts = hosts();
    let mut mismatches = Vec::new();
    for h in &hosts {
        let ours = PslResolver.get_host_domain(h);
        let theirs = reference(h);
        if ours != theirs {
            mismatches.push(format!(
                "{h}: ours={} ref={}",
                &h[ours.0..ours.1],
                &h[theirs.0..theirs.1]
            ));
        }
    }
    eprintln!(
        "checked {} hosts, {} mismatches",
        hosts.len(),
        mismatches.len()
    );
    for m in mismatches.iter().take(20) {
        eprintln!("  {m}");
    }
    // The psl crate's snapshot and our list can differ by a few recently changed suffixes.
    assert!(
        mismatches.len() * 1000 <= hosts.len(),
        "too many mismatches: {}",
        mismatches.len()
    );
}
