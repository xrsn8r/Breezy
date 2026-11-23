//! Breezy native core.
//!
//! Wraps Brave's [`adblock`] engine (the same one that powers Brave's shields) in a small,
//! thread-safe API and exposes it to the Android app over JNI (see [`jni_bridge`]).
//!
//! Design notes:
//! * There is exactly one live engine, held in a global `RwLock<Option<Arc<Engine>>>`.
//!   Readers clone the `Arc` and drop the lock immediately, so lookups never block each other
//!   and a filter-list update can swap the engine in without any use-after-free risk on the
//!   Kotlin side (Kotlin never holds a raw pointer).
//! * Engines are compiled once (at build time on the host, or on-device after a list update)
//!   and then loaded from the compact serialized form, which takes a few milliseconds.

pub mod request_type;
mod jni_bridge;

use std::collections::HashSet;
use std::sync::{Arc, RwLock};

use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::resources::Resource;
use adblock::Engine;

/// What the browser should do with a network request.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Decision {
    /// Let the request through untouched.
    Allow,
    /// Block it (respond with an empty body).
    Block,
    /// Respond with this `data:` URL instead (e.g. a no-op `google-analytics.js` shim).
    Redirect(String),
}

static ENGINE: RwLock<Option<Arc<Engine>>> = RwLock::new(None);

fn current() -> Option<Arc<Engine>> {
    ENGINE.read().ok().and_then(|g| g.clone())
}

fn install(engine: Engine) {
    if let Ok(mut g) = ENGINE.write() {
        *g = Some(Arc::new(engine));
    }
}

/// Compile filter lists (ABP / uBO syntax) into a fresh engine. Slow-ish (hundreds of ms on a
/// phone for EasyList + EasyPrivacy), so callers should do it off the main thread and cache the
/// [`serialize_current`] output.
pub fn compile_lists<'a>(lists: impl IntoIterator<Item = &'a str>) -> Engine {
    let mut set = FilterSet::new(false);
    for list in lists {
        set.add_filter_list(list.to_owned(), ParseOptions::default());
    }
    Engine::new_with_filter_set(set)
}

/// Compile and install an engine built from raw filter lists.
pub fn load_lists<'a>(lists: impl IntoIterator<Item = &'a str>) {
    install(compile_lists(lists));
}

/// Install an engine from bytes produced by [`Engine::serialize`]. Returns false if the data is
/// corrupt or from an incompatible version (the caller should then fall back to compiling lists).
pub fn load_serialized(bytes: &[u8]) -> bool {
    let mut engine = Engine::default();
    if engine.deserialize(bytes).is_err() {
        return false;
    }
    // Carry over scriptlet/redirect resources from the previous engine is not possible (they are
    // not part of the serialized form), so callers must call `load_resources` afterwards.
    install(engine);
    true
}

/// Like [`load_serialized`] but reads the file natively, avoiding a multi-megabyte copy through
/// the Java heap on every launch.
pub fn load_serialized_file(path: &str) -> bool {
    std::fs::read(path).map(|b| load_serialized(&b)).unwrap_or(false)
}

/// Serialize the current engine for fast reloading. Empty if no engine is loaded.
pub fn serialize_current() -> Vec<u8> {
    current().map(|e| e.serialize()).unwrap_or_default()
}

/// Load uBO/Brave scriptlet + redirect resources (Brave's `resources.json` format).
/// Resources are not part of the serialized engine and must be loaded after each engine swap.
pub fn load_resources(json: &str) -> Result<usize, String> {
    let resources: Vec<Resource> = serde_json::from_str(json).map_err(|e| e.to_string())?;
    let count = resources.len();
    let mut guard = ENGINE.write().map_err(|_| "engine lock poisoned".to_string())?;
    let Some(arc) = guard.as_mut() else {
        return Err("no engine loaded".into());
    };
    // We are the only writer; if a reader currently holds a clone, make our own copy-on-write
    // by rebuilding from the serialized form (rare: only happens during an update).
    match Arc::get_mut(arc) {
        Some(engine) => engine.use_resources(resources),
        None => {
            let mut engine = Engine::default();
            engine
                .deserialize(&arc.serialize())
                .map_err(|e| format!("{e:?}"))?;
            engine.use_resources(resources);
            *arc = Arc::new(engine);
        }
    }
    Ok(count)
}

pub fn is_loaded() -> bool {
    current().is_some()
}

/// Check a single network request. `request_type` uses adblock-rust's vocabulary
/// (`script`, `image`, `stylesheet`, `xmlhttprequest`, `subdocument`, ...); see
/// [`request_type::infer`] for deriving it from what Android's WebView exposes.
pub fn check(url: &str, source_url: &str, request_type: &str, method: &str) -> Decision {
    let Some(engine) = current() else {
        return Decision::Allow;
    };
    let Ok(request) = Request::new(url, source_url, request_type, method) else {
        return Decision::Allow;
    };
    let result = engine.check_network_request(&request);
    if result.should_block() {
        match result.redirect {
            Some(data_url) => Decision::Redirect(data_url),
            None => Decision::Block,
        }
    } else {
        Decision::Allow
    }
}

/// Cosmetic resources for a page as JSON:
/// `{hide_selectors, procedural_actions, exceptions, injected_script, generichide}`.
pub fn cosmetic_resources_json(url: &str) -> String {
    let Some(engine) = current() else {
        return "{}".into();
    };
    serde_json::to_string(&engine.url_cosmetic_resources(url)).unwrap_or_else(|_| "{}".into())
}

/// Generic hide selectors matching the given classes / ids seen in the DOM (JSON arrays of
/// strings), honouring the page's `exceptions` (from [`cosmetic_resources_json`]).
/// Returns a JSON array of selectors.
pub fn hidden_class_id_selectors_json(classes: &str, ids: &str, exceptions: &str) -> String {
    let Some(engine) = current() else {
        return "[]".into();
    };
    let classes: Vec<String> = serde_json::from_str(classes).unwrap_or_default();
    let ids: Vec<String> = serde_json::from_str(ids).unwrap_or_default();
    let exceptions: HashSet<String> = serde_json::from_str(exceptions).unwrap_or_default();
    let selectors = engine.hidden_class_id_selectors(&classes, &ids, &exceptions);
    serde_json::to_string(&selectors).unwrap_or_else(|_| "[]".into())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    // The engine is global; serialize tests that touch it.
    static LOCK: Mutex<()> = Mutex::new(());

    const LIST: &str = "\
||ads.example.com^
||tracker.test^$third-party
@@||ads.example.com/allowed.js
example.org##.ad-banner
##.sponsored
";

    #[test]
    fn blocks_and_allows() {
        let _g = LOCK.lock().unwrap();
        load_lists([LIST]);
        let page = "https://news.example.net/";
        assert_eq!(check("https://ads.example.com/x.js", page, "script", "GET"), Decision::Block);
        assert_eq!(
            check("https://ads.example.com/allowed.js", page, "script", "GET"),
            Decision::Allow
        );
        assert_eq!(check("https://cdn.example.net/app.js", page, "script", "GET"), Decision::Allow);
        // $third-party: first-party load is fine.
        assert_eq!(
            check("https://tracker.test/p.gif", "https://tracker.test/", "image", "GET"),
            Decision::Allow
        );
        assert_eq!(check("https://tracker.test/p.gif", page, "image", "GET"), Decision::Block);
    }

    #[test]
    fn serialize_roundtrip() {
        let _g = LOCK.lock().unwrap();
        load_lists([LIST]);
        let bytes = serialize_current();
        assert!(!bytes.is_empty());
        assert!(load_serialized(&bytes));
        assert!(!load_serialized(b"garbage"));
        let path = std::env::temp_dir().join("breezy-test-engine.dat");
        std::fs::write(&path, &bytes).unwrap();
        assert!(load_serialized_file(path.to_str().unwrap()));
        assert!(!load_serialized_file("/nonexistent/engine.dat"));
        assert_eq!(
            check("https://ads.example.com/x.js", "https://a.test/", "script", "GET"),
            Decision::Block
        );
    }

    #[test]
    fn cosmetics() {
        let _g = LOCK.lock().unwrap();
        load_lists([LIST]);
        let json = cosmetic_resources_json("https://example.org/page");
        let v: serde_json::Value = serde_json::from_str(&json).unwrap();
        let hides: Vec<&str> = v["hide_selectors"]
            .as_array()
            .unwrap()
            .iter()
            .map(|s| s.as_str().unwrap())
            .collect();
        assert!(hides.contains(&".ad-banner"));
        let generic = hidden_class_id_selectors_json(r#"["sponsored","foo"]"#, "[]", "[]");
        assert_eq!(generic, r#"[".sponsored"]"#);
    }

    #[test]
    fn redirect_resources() {
        let _g = LOCK.lock().unwrap();
        load_lists(["||analytics.test/ga.js$script,redirect=noop.js"]);
        let resources = r#"[{"name":"noop.js","aliases":[],"kind":{"mime":"application/javascript"},"content":"KGZ1bmN0aW9uKCl7fSkoKTs="}]"#;
        assert_eq!(load_resources(resources), Ok(1));
        match check("https://analytics.test/ga.js", "https://site.test/", "script", "GET") {
            Decision::Redirect(url) => assert!(url.starts_with("data:application/javascript;base64,")),
            other => panic!("expected redirect, got {other:?}"),
        }
    }
}
