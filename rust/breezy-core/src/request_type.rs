//! Android's `WebResourceRequest` does not expose the resource type the way Chromium's
//! extension APIs do, so we infer adblock-rust's request type from the URL's extension, the
//! `Accept` header and the main-frame flag. Doing it here keeps the hot path to one JNI call.

/// Infer an adblock-rust request type (`document`, `subdocument`, `script`, `stylesheet`,
/// `image`, `font`, `media`, `xmlhttprequest`, `other`).
pub fn infer(url: &str, accept: &str, is_main_frame: bool) -> &'static str {
    if is_main_frame {
        return "document";
    }
    if let Some(t) = from_extension(url) {
        return t;
    }
    let accept = accept.trim_start();
    if accept.starts_with("text/html") || accept.starts_with("application/xhtml") {
        "subdocument"
    } else if accept.starts_with("text/css") {
        "stylesheet"
    } else if accept.starts_with("image/") {
        "image"
    } else if accept.starts_with("video/") || accept.starts_with("audio/") {
        "media"
    } else if accept.starts_with("application/json") || accept.contains("text/plain") {
        "xmlhttprequest"
    } else if accept.starts_with("*/*") || accept.is_empty() {
        // Fetch/XHR and scripts both default to */*; scripts almost always carry a .js
        // extension (handled above), so treat the remainder as XHR.
        "xmlhttprequest"
    } else {
        "other"
    }
}

fn from_extension(url: &str) -> Option<&'static str> {
    // Strip query and fragment, then take the last path segment's extension.
    let end = url.find(['?', '#']).unwrap_or(url.len());
    let path = &url[..end];
    let after_scheme = path.find("://").map(|i| i + 3).unwrap_or(0);
    let path = &path[after_scheme..];
    let slash = path.find('/')?;
    let last = path[slash..].rsplit('/').next()?;
    let dot = last.rfind('.')?;
    let ext = &last[dot + 1..];
    if ext.len() > 5 {
        return None;
    }
    let ext = ext.to_ascii_lowercase();
    Some(match ext.as_str() {
        "js" | "mjs" | "cjs" => "script",
        "css" => "stylesheet",
        "png" | "jpg" | "jpeg" | "gif" | "webp" | "avif" | "svg" | "ico" | "bmp" => "image",
        "woff" | "woff2" | "ttf" | "otf" | "eot" => "font",
        "mp4" | "webm" | "m3u8" | "mpd" | "mp3" | "ogg" | "m4a" | "m4s" | "ts" => "media",
        "html" | "htm" => "subdocument",
        "json" => "xmlhttprequest",
        _ => return None,
    })
}

#[cfg(test)]
mod tests {
    use super::infer;

    #[test]
    fn infers() {
        assert_eq!(infer("https://a.com/", "text/html", true), "document");
        assert_eq!(infer("https://a.com/x/app.min.js?v=1", "*/*", false), "script");
        assert_eq!(infer("https://a.com/s.CSS#x", "*/*", false), "stylesheet");
        assert_eq!(infer("https://a.com/pixel", "image/avif,image/webp,*/*", false), "image");
        assert_eq!(infer("https://a.com/frame", "text/html,application/xhtml+xml", false), "subdocument");
        assert_eq!(infer("https://a.com/api/v1", "*/*", false), "xmlhttprequest");
        assert_eq!(infer("https://a.com", "", false), "xmlhttprequest");
        assert_eq!(infer("https://cdn.a.com/f.woff2", "*/*", false), "font");
        assert_eq!(infer("https://a.com/v.thisisnotext", "application/x-foo", false), "other");
    }
}
