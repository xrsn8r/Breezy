//! Host-side resource builder used by the Gradle build.
//!
//! `breezy-resources <ubo-src-dir> <brave-resources.json> <out.json>`
//!
//! Brave's published `resources.json` only contains Brave's own scriptlets. uBlock Origin's core
//! scriptlets (`json-prune`, `set-constant`, `trusted-replace-xhr-response`, ...), which the uBO
//! filter lists rely on (including YouTube ad removal), live in the uBO source tree as ES modules
//! that register `{ name, fn, dependencies, ... }` objects.
//!
//! This tool extracts them *statically*: a small JavaScript tokenizer locates each
//! `registeredScriptlets.push({...})` and the source text of the referenced function. No downloaded
//! code is executed. Redirect resources (`web_accessible_resources`) are added the same way.

use std::collections::{HashMap, HashSet};
use std::fs;
use std::path::{Path, PathBuf};
use std::process::ExitCode;

use base64::Engine as _;
use base64::engine::general_purpose::STANDARD as B64;
use serde_json::{Value, json};

/// Permission bit granted to uBO/Brave lists for `requiresTrust` scriptlets.
pub const TRUSTED: u8 = 1;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.len() != 3 {
        eprintln!("usage: breezy-resources <ubo-src-dir> <brave-resources.json> <out.json>");
        return ExitCode::from(2);
    }
    let (ubo, brave, out) = (Path::new(&args[0]), &args[1], &args[2]);
    match build(ubo, brave) {
        Ok((resources, stats)) => {
            let json = serde_json::to_string(&resources).expect("serialize");
            if let Err(e) = fs::write(out, json) {
                eprintln!("breezy-resources: cannot write {out}: {e}");
                return ExitCode::FAILURE;
            }
            println!("breezy-resources: {stats}");
            ExitCode::SUCCESS
        }
        Err(e) => {
            eprintln!("breezy-resources: {e}");
            ExitCode::FAILURE
        }
    }
}

fn build(ubo: &Path, brave: &str) -> Result<(Vec<Value>, String), String> {
    let brave_json = fs::read_to_string(brave).map_err(|e| format!("read {brave}: {e}"))?;
    let mut resources: Vec<Value> = serde_json::from_str(&brave_json).map_err(|e| e.to_string())?;
    let mut names: HashSet<String> = resources
        .iter()
        .filter_map(|r| r["name"].as_str().map(str::to_owned))
        .collect();
    let brave_count = resources.len();

    let scriptlets = extract_scriptlets(&ubo.join("src/js/resources"))?;
    let scriptlet_count = scriptlets.len();
    for r in scriptlets {
        let name = r["name"].as_str().unwrap_or_default().to_owned();
        if names.insert(name) {
            resources.push(r);
        }
    }

    let redirects = extract_redirects(ubo)?;
    let redirect_count = redirects.len();
    for r in redirects {
        let name = r["name"].as_str().unwrap_or_default().to_owned();
        if names.insert(name) {
            resources.push(r);
        }
    }

    if scriptlet_count < 120 {
        return Err(format!(
            "only {scriptlet_count} scriptlets found; uBO layout changed?"
        ));
    }
    let stats = format!(
        "{} resources ({brave_count} Brave, {scriptlet_count} uBO scriptlets, {redirect_count} redirects)",
        resources.len()
    );
    Ok((resources, stats))
}

// --- Scriptlets ------------------------------------------------------------------------------------

fn extract_scriptlets(dir: &Path) -> Result<Vec<Value>, String> {
    let mut files: Vec<PathBuf> = fs::read_dir(dir)
        .map_err(|e| format!("read {}: {e}", dir.display()))?
        .filter_map(|e| e.ok().map(|e| e.path()))
        .filter(|p| p.extension().is_some_and(|x| x == "js"))
        .collect();
    files.sort();
    // Helpers some scriptlets import from outside resources/ (e.g. '../urlskip.js').
    let mut extra = Vec::new();
    for file in &files {
        let src = fs::read_to_string(file).unwrap_or_default();
        for line in src.lines() {
            if let Some(p) = line
                .split("from '../")
                .nth(1)
                .and_then(|r| r.split('\'').next())
            {
                let path = dir.join("..").join(p);
                if path.is_file() && !extra.contains(&path) {
                    extra.push(path);
                }
            }
        }
    }
    files.extend(extra);

    // Every top-level `function NAME(...) {...}` across all modules, by name.
    let mut functions: HashMap<String, String> = HashMap::new();
    // (function passed as first argument, if any; object literal)
    let mut registrations: Vec<(Option<String>, String)> = Vec::new();
    for file in &files {
        let src = fs::read_to_string(file).map_err(|e| e.to_string())?;
        let spans = Lexer::new(&src).code_spans();
        for (name, body) in find_functions(&src, &spans) {
            functions.entry(name).or_insert(body);
        }
        registrations.extend(find_registrations(&src, &spans));
    }

    // Resolve function-reference dependencies (`dependencies: [ safeSelf ]`) to registered names.
    let fn_to_name: HashMap<String, String> = registrations
        .iter()
        .filter_map(|(f, obj)| Some((f.clone()?, capture_str(obj, "name")?)))
        .collect();

    let mut out = Vec::new();
    for (fn_arg, obj) in registrations {
        let Some(name) = capture_str(&obj, "name") else {
            continue;
        };
        let Some(fn_name) = fn_arg.or_else(|| capture_ident(&obj, "fn")) else {
            continue;
        };
        let Some(body) = functions.get(&fn_name) else {
            eprintln!("breezy-resources: warning: {name}: function {fn_name} not found");
            continue;
        };
        let deps: Vec<String> = capture_list(&obj, "dependencies")
            .into_iter()
            .filter_map(|d| match d {
                ListItem::Str(s) => Some(s),
                ListItem::Ident(i) => fn_to_name.get(&i).cloned(),
            })
            .collect();
        let aliases: Vec<String> = capture_list(&obj, "aliases")
            .into_iter()
            .filter_map(|d| {
                if let ListItem::Str(s) = d {
                    Some(s)
                } else {
                    None
                }
            })
            .collect();
        let requires_trust =
            obj.contains("requiresTrust: true") || obj.contains("requiresTrust:true");
        let mut r = json!({
            "name": name,
            "aliases": aliases,
            // adblock-rust injects `application/javascript` scriptlets (invoked by function name
            // with the filter's arguments); `fn/javascript` is only for helper dependencies.
            "kind": { "mime": if name.ends_with(".fn") { "fn/javascript" } else { "application/javascript" } },
            "content": B64.encode(body.as_bytes()),
            "dependencies": deps,
        });
        if requires_trust {
            r["permission"] = json!(TRUSTED);
        }
        out.push(r);
    }
    Ok(out)
}

/// Declarations `[export] [async] function NAME(...) {...}` and `[export] class NAME {...}`,
/// at positions not inside a string/comment/regex.
fn find_functions(src: &str, spans: &[(usize, usize)]) -> Vec<(String, String)> {
    let mut out = Vec::new();
    for kw in ["function ", "class "] {
        let mut i = 0;
        while let Some(pos) = src[i..].find(kw) {
            let kw_at = i + pos;
            i = kw_at + kw.len();
            if !in_code(spans, kw_at) || (kw_at > 0 && is_ident(src.as_bytes()[kw_at - 1])) {
                continue;
            }
            // Include an `async ` prefix in the source text.
            let mut start = kw_at;
            if kw == "function " && src[..kw_at].ends_with("async ") {
                start = kw_at - 6;
            }
            // Must be a declaration: at line start, or after `export`, `;` or `}`.
            let before = src[..start].trim_end_matches([' ', '\t']);
            let before_trim = before.trim_end();
            if !(before.is_empty()
                || before.ends_with('\n')
                || before_trim.ends_with("export")
                || before_trim.ends_with(';')
                || before_trim.ends_with('}'))
            {
                continue;
            }
            let name: String = src[i..]
                .chars()
                .take_while(|c| c.is_alphanumeric() || *c == '_' || *c == '$')
                .collect();
            if name.is_empty() {
                continue;
            }
            let Some(end) = declaration_end(src, spans, i, kw == "class ") else {
                continue;
            };
            out.push((name, src[start..=end].to_owned()));
            i = end + 1;
        }
    }
    out
}

fn is_ident(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b == b'_' || b == b'$'
}

/// End index of a function (params then body) or class (body) declaration starting at `from`.
fn declaration_end(src: &str, spans: &[(usize, usize)], from: usize, class: bool) -> Option<usize> {
    let bytes = src.as_bytes();
    let body_search = if class {
        from
    } else {
        let paren = src[from..].find('(').map(|p| p + from)?;
        match_delim(bytes, spans, paren, b'(', b')')?
    };
    let brace = src[body_search..].find('{').map(|p| p + body_search)?;
    match_delim(bytes, spans, brace, b'{', b'}')
}

/// Scriptlet registrations: `registerScriptlet(fnIdent, {...})` (current uBO) and the older
/// `registeredScriptlets.push({...fn: fnIdent...})` / `builtinScriptlets.push({...})`.
fn find_registrations(src: &str, spans: &[(usize, usize)]) -> Vec<(Option<String>, String)> {
    let mut out = Vec::new();
    let bytes = src.as_bytes();
    for needle in [
        "registerScriptlet(",
        "registeredScriptlets.push(",
        "builtinScriptlets.push(",
    ] {
        let mut i = 0;
        while let Some(pos) = src[i..].find(needle) {
            let call = i + pos;
            let start = call + needle.len();
            i = start;
            if !in_code(spans, call) {
                continue;
            }
            // Skip the definition `const registerScriptlet = (fn, details) =>`.
            if src[..call].trim_end().ends_with("const")
                || src[..call].trim_end().ends_with("function")
            {
                continue;
            }
            let Some(brace) = src[start..].find('{').map(|p| p + start) else {
                continue;
            };
            let head = src[start..brace].trim().trim_end_matches(',').trim();
            let fn_arg = (!head.is_empty()
                && head
                    .chars()
                    .all(|c| c.is_alphanumeric() || c == '_' || c == '$'))
            .then(|| head.to_owned());
            if !head.is_empty() && fn_arg.is_none() {
                continue;
            }
            let Some(end) = match_delim(bytes, spans, brace, b'{', b'}') else {
                continue;
            };
            out.push((fn_arg, src[brace..=end].to_owned()));
            i = end;
        }
    }
    out
}

enum ListItem {
    Str(String),
    Ident(String),
}

fn capture_list(obj: &str, key: &str) -> Vec<ListItem> {
    let Some(at) = find_key(obj, key) else {
        return vec![];
    };
    let rest = obj[at..].trim_start();
    if !rest.starts_with('[') {
        return vec![];
    }
    let Some(end) = rest.find(']') else {
        return vec![];
    };
    rest[1..end]
        .split(',')
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(|s| {
            if s.starts_with('\'') || s.starts_with('"') {
                ListItem::Str(s.trim_matches(|c| c == '\'' || c == '"').to_owned())
            } else {
                ListItem::Ident(s.to_owned())
            }
        })
        .collect()
}

fn capture_str(obj: &str, key: &str) -> Option<String> {
    let at = find_key(obj, key)?;
    let rest = obj[at..].trim_start();
    let q = rest.chars().next()?;
    if q != '\'' && q != '"' {
        return None;
    }
    let end = rest[1..].find(q)?;
    Some(rest[1..1 + end].to_owned())
}

fn capture_ident(obj: &str, key: &str) -> Option<String> {
    let at = find_key(obj, key)?;
    let ident: String = obj[at..]
        .trim_start()
        .chars()
        .take_while(|c| c.is_alphanumeric() || *c == '_' || *c == '$')
        .collect();
    (!ident.is_empty()).then_some(ident)
}

fn capture_str_list(obj: &str, key: &str) -> Vec<String> {
    let Some(at) = find_key(obj, key) else {
        return vec![];
    };
    let rest = obj[at..].trim_start();
    if !rest.starts_with('[') {
        return vec![];
    }
    let Some(end) = rest.find(']') else {
        return vec![];
    };
    rest[1..end]
        .split(',')
        .map(|s| s.trim().trim_matches(|c| c == '\'' || c == '"').to_owned())
        .filter(|s| !s.is_empty())
        .collect()
}

/// Position just after `key:` where `key` is a property name (not part of a longer identifier).
fn find_key(obj: &str, key: &str) -> Option<usize> {
    let mut from = 0;
    while let Some(p) = obj[from..].find(key) {
        let at = from + p;
        let prev_ok = at == 0 || !obj.as_bytes()[at - 1].is_ascii_alphanumeric();
        let after = obj[at + key.len()..].trim_start();
        if prev_ok && after.starts_with(':') {
            let colon = obj.len() - after.len();
            return Some(colon + 1);
        }
        from = at + key.len();
    }
    None
}

// --- Redirect resources ---------------------------------------------------------------------------

fn extract_redirects(ubo: &Path) -> Result<Vec<Value>, String> {
    let map_path = ubo.join("src/js/redirect-resources.js");
    let war = ubo.join("src/web_accessible_resources");
    let src =
        fs::read_to_string(&map_path).map_err(|e| format!("read {}: {e}", map_path.display()))?;
    let spans = Lexer::new(&src).code_spans();
    let bytes = src.as_bytes();
    let mut out = Vec::new();
    // Entries look like: [ 'name.js', { alias: 'x' | [ ... ], data: 'text', params: [...] } ],
    let mut i = 0;
    while let Some(p) = src[i..].find("[ '") {
        let start = i + p;
        i = start + 3;
        if !in_code(&spans, start) {
            continue;
        }
        let Some(name_end) = src[i..].find('\'') else {
            break;
        };
        let name = &src[i..i + name_end];
        let after = &src[i + name_end + 1..];
        let Some(brace_rel) = after.find('{') else {
            continue;
        };
        if !after[..brace_rel]
            .trim()
            .trim_start_matches(',')
            .trim()
            .is_empty()
        {
            continue;
        }
        let brace = i + name_end + 1 + brace_rel;
        let Some(end) = match_delim(bytes, &spans, brace, b'{', b'}') else {
            continue;
        };
        let props = &src[brace..=end];
        i = end;
        // Templated resources (with `params`) need runtime substitution adblock-rust can't do.
        if find_key(props, "params").is_some() {
            continue;
        }
        let file = war.join(name);
        let Ok(content) = fs::read(&file) else {
            continue;
        };
        let mut aliases = capture_str_list(props, "alias");
        if aliases.is_empty()
            && let Some(a) = capture_str(props, "alias")
        {
            aliases.push(a);
        }
        out.push(json!({
            "name": name,
            "aliases": aliases,
            "kind": { "mime": mime_for(name) },
            "content": B64.encode(&content),
        }));
    }
    Ok(out)
}

fn mime_for(name: &str) -> &'static str {
    match name.rsplit('.').next().unwrap_or("") {
        "js" => "application/javascript",
        "gif" => "image/gif",
        "png" => "image/png",
        "html" => "text/html",
        "css" => "text/css",
        "json" => "application/json",
        "xml" => "text/xml",
        "mp4" => "video/mp4",
        "mp3" => "audio/mp3",
        _ => "text/plain",
    }
}

// --- Minimal JavaScript lexer ----------------------------------------------------------------------

fn in_code(spans: &[(usize, usize)], pos: usize) -> bool {
    // spans are sorted, non-overlapping [start, end) ranges of *non-code* (strings, comments, regex).
    spans
        .binary_search_by(|&(s, e)| {
            if pos < s {
                std::cmp::Ordering::Greater
            } else if pos >= e {
                std::cmp::Ordering::Less
            } else {
                std::cmp::Ordering::Equal
            }
        })
        .is_err()
}

/// Index of the delimiter closing the one at `open_at`, ignoring strings/comments/regex.
fn match_delim(
    bytes: &[u8],
    spans: &[(usize, usize)],
    open_at: usize,
    open: u8,
    close: u8,
) -> Option<usize> {
    let mut depth = 0usize;
    let mut i = open_at;
    let mut span_idx = spans.partition_point(|&(_, e)| e <= open_at);
    while i < bytes.len() {
        if let Some(&(s, e)) = spans.get(span_idx)
            && i >= s
        {
            i = e;
            span_idx += 1;
            continue;
        }
        let b = bytes[i];
        if b == open {
            depth += 1;
        } else if b == close {
            depth -= 1;
            if depth == 0 {
                return Some(i);
            }
        }
        i += 1;
    }
    None
}

struct Lexer<'a> {
    s: &'a [u8],
}

impl<'a> Lexer<'a> {
    fn new(src: &'a str) -> Self {
        Self { s: src.as_bytes() }
    }

    /// Ranges of strings, template-literal text, comments and regex literals. Code inside
    /// template `${...}` is treated as code.
    fn code_spans(&self) -> Vec<(usize, usize)> {
        let s = self.s;
        let mut spans = Vec::new();
        let mut i = 0;
        let mut last_sig: u8 = b';'; // last significant code char, for regex detection
        let mut last_word = String::new();
        // Stack of brace depths at which a template literal resumes.
        let mut template_stack: Vec<usize> = Vec::new();
        let mut brace_depth = 0usize;
        while i < s.len() {
            let c = s[i];
            match c {
                b'/' if s.get(i + 1) == Some(&b'/') => {
                    let start = i;
                    while i < s.len() && s[i] != b'\n' {
                        i += 1;
                    }
                    spans.push((start, i));
                    continue;
                }
                b'/' if s.get(i + 1) == Some(&b'*') => {
                    let start = i;
                    i += 2;
                    while i + 1 < s.len() && !(s[i] == b'*' && s[i + 1] == b'/') {
                        i += 1;
                    }
                    i = (i + 2).min(s.len());
                    spans.push((start, i));
                    continue;
                }
                b'\'' | b'"' => {
                    let start = i;
                    i += 1;
                    while i < s.len() && s[i] != c {
                        if s[i] == b'\\' {
                            i += 1;
                        }
                        i += 1;
                    }
                    i = (i + 1).min(s.len());
                    spans.push((start, i));
                    last_sig = b'"';
                    last_word.clear();
                    continue;
                }
                b'`' => {
                    i = self.template(i, i + 1, &mut spans, &mut template_stack, brace_depth);
                    last_sig = b'"';
                    last_word.clear();
                    continue;
                }
                b'/' if regex_allowed(last_sig, &last_word) => {
                    let start = i;
                    i += 1;
                    let mut in_class = false;
                    while i < s.len() {
                        match s[i] {
                            b'\\' => i += 1,
                            b'[' => in_class = true,
                            b']' => in_class = false,
                            b'/' if !in_class => break,
                            b'\n' => break,
                            _ => {}
                        }
                        i += 1;
                    }
                    i += 1;
                    while i < s.len() && s[i].is_ascii_alphabetic() {
                        i += 1;
                    }
                    spans.push((start, i.min(s.len())));
                    last_sig = b'"';
                    last_word.clear();
                    continue;
                }
                b'{' => brace_depth += 1,
                b'}' => {
                    if template_stack.last() == Some(&brace_depth) {
                        // End of `${...}`: resume the template literal.
                        template_stack.pop();
                        brace_depth -= 1;
                        // The `}` itself is code (it balances the `{` of `${`); text resumes after it.
                        i = self.template(
                            i + 1,
                            i + 1,
                            &mut spans,
                            &mut template_stack,
                            brace_depth,
                        );
                        last_sig = b'"';
                        continue;
                    }
                    brace_depth = brace_depth.saturating_sub(1);
                }
                _ => {}
            }
            if c.is_ascii_alphanumeric() || c == b'_' || c == b'$' {
                if !(last_sig.is_ascii_alphanumeric() || last_sig == b'_' || last_sig == b'$') {
                    last_word.clear();
                }
                last_word.push(c as char);
                last_sig = c;
            } else if !c.is_ascii_whitespace() {
                last_sig = c;
                last_word.clear();
            }
            i += 1;
        }
        spans
    }

    /// Scan template text from `i` until the closing backtick or a `${`. Returns the next index.
    fn template(
        &self,
        start: usize,
        mut i: usize,
        spans: &mut Vec<(usize, usize)>,
        stack: &mut Vec<usize>,
        depth: usize,
    ) -> usize {
        let s = self.s;
        while i < s.len() {
            match s[i] {
                b'\\' => i += 2,
                b'`' => {
                    spans.push((start, i + 1));
                    return i + 1;
                }
                b'$' if s.get(i + 1) == Some(&b'{') => {
                    spans.push((start, i));
                    // `${` opens a code region closed by the matching `}` at depth+1.
                    stack.push(depth + 1);
                    return i + 1; // the '{' is processed as code next (increments depth)
                }
                _ => i += 1,
            }
        }
        spans.push((start, s.len()));
        s.len()
    }
}

fn regex_allowed(last_sig: u8, last_word: &str) -> bool {
    if last_sig.is_ascii_alphanumeric() || last_sig == b'_' || last_sig == b'$' {
        return matches!(
            last_word,
            "return"
                | "typeof"
                | "case"
                | "in"
                | "of"
                | "new"
                | "delete"
                | "void"
                | "throw"
                | "else"
                | "do"
        );
    }
    !matches!(last_sig, b')' | b']' | b'"' | b'}')
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extracts_function_with_tricky_tokens() {
        let src = r#"
import { safeSelf } from './safe-self.js';
function demo(a = '') {
    const re = /[}{]\//g; // } in regex and comment
    const s = "}" + '{' + `x ${ a ? `}` : '{' } y`;
    if ( a / 2 > 1 ) { return { k: s }; }
    return re;
}
registeredScriptlets.push({
    name: 'demo.js',
    aliases: [ 'dm.js', 'demo2.js' ],
    fn: demo,
    dependencies: [ 'safe-self.fn' ],
    requiresTrust: true,
});
"#;
        let spans = Lexer::new(src).code_spans();
        let fns = find_functions(src, &spans);
        assert_eq!(fns.len(), 1);
        assert!(fns[0].1.starts_with("function demo("));
        assert!(fns[0].1.trim_end().ends_with("return re;\n}"));
        let regs = find_registrations(src, &spans);
        assert_eq!(regs.len(), 1);
        assert_eq!(capture_str(&regs[0].1, "name").as_deref(), Some("demo.js"));
        assert_eq!(capture_ident(&regs[0].1, "fn").as_deref(), Some("demo"));
        assert_eq!(
            capture_str_list(&regs[0].1, "aliases"),
            vec!["dm.js", "demo2.js"]
        );
        assert_eq!(
            capture_str_list(&regs[0].1, "dependencies"),
            vec!["safe-self.fn"]
        );
        let src2 = "function safeSelf() { return 1; }\nregisterScriptlet(safeSelf, { name: 'safe-self.fn' });\n\
            function jp(a) { return safeSelf(); }\nregisterScriptlet(jp, {\n name: 'json-prune.js',\n dependencies: [\n safeSelf,\n ],\n requiresTrust: true,\n});\n";
        let spans2 = Lexer::new(src2).code_spans();
        let regs2 = find_registrations(src2, &spans2);
        assert_eq!(regs2.len(), 2);
        assert_eq!(regs2[1].0.as_deref(), Some("jp"));
    }
}
