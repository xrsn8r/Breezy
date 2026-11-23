# Breezy

A fast Android WebView browser with **Brave's adblock-rust engine built into its core** and a
**Material 3 Expressive** UI (Jetpack Compose).

## How it fits together

```
rust/breezy-core          Rust cdylib (libbreezy_core.so)
 ├─ lib.rs                thread-safe wrapper around adblock::Engine (global Arc, hot-swappable)
 ├─ psl.rs                compact Public Suffix List resolver (replaces adblock-rust's embedded one)
 ├─ request_type.rs       infers script/image/xhr/... from URL + Accept (WebView doesn't expose it)
 ├─ jni_bridge.rs         JNI entry points for dev.breezy.browser.adblock.NativeShield
 └─ bin/compile.rs        host tool: filter lists -> serialized engine.dat (used by Gradle)

app/                      Kotlin + Compose
 ├─ adblock/Shield.kt     engine lifecycle, list updates, per-site allowlist, stats
 ├─ browser/BrowserTab.kt WebView; shouldInterceptRequest -> NativeShield.check()
 ├─ assets/adblock/cosmetic.js   document-start script: hide selectors, scriptlets,
 │                               generic class/id hiding via MutationObserver
 └─ ui/                   Expressive UI: HorizontalFloatingToolbar, LinearWavyProgressIndicator,
                          LoadingIndicator, connected ButtonGroup shapes, MaterialShapes
```

**Network blocking.** Every subresource goes through `shouldInterceptRequest` → one JNI call → the
adblock-rust matcher. Blocked requests get an empty response, and `$redirect` rules serve Brave's
neutered resources (for example a no-op `google-analytics.js`).

**Cosmetic filtering.** `WebViewCompat.addDocumentStartJavaScript` injects `cosmetic.js` into every
frame. It calls back into Rust through a JavaScript interface to get hide selectors and uBO
scriptlets, then hides elements with a constructable stylesheet, which page CSP can't block.
If CSP blocks `eval`, the scriptlets are injected with `evaluateJavascript` instead.

**Fast startup.** Gradle downloads the filter lists and compiles them **on the host** into
adblock-rust's serialized format (`assets/adblock/engine.dat`). On device the engine is
deserialized in milliseconds instead of parsing ~4 MB of filter text. "Update filter lists" in the
shield sheet recompiles on-device and caches the result.

## Features

- **Privacy**:
  - **HTTPS-only mode**: upgrades sites to HTTPS and warns before falling back to HTTP.
  - **Tracking-parameter stripping**: removes `utm_*`, `fbclid`, `gclid` and similar from links.
  - **Per-site controls in the Shields sheet**: JavaScript, cookies, and camera, microphone and
    location (Ask/Allow/Block).
  - **Forget this site** and **Clear data on exit**.
  - **Private tabs**: each gets its own WebView profile, which is deleted when the tab closes, and
    nothing is written to history.
  - **Secure DNS (DNS over HTTPS)**: Cloudflare, Quad9, Mullvad, AdGuard, Google or a custom
    endpoint, with an optional strict mode. WebView can't take a custom resolver, so Breezy runs a
    small proxy on `127.0.0.1` that resolves hostnames over DoH and tunnels connections unchanged
    (TLS stays end to end). Breezy's own downloads and list updates use it too.
- **Download manager**: downloads split into up to 8 parallel connections (HTTP range requests),
  with pause, resume (also after a restart), retry and share. When a server rate-limits (HTTP 429/503)
  every connection to it waits out the server's Retry-After, and stream downloads drop to fewer
  parallel connections, then speed back up. Files are saved to `Download/Breezy`.
- **Media detection**: the toolbar's download button shows a badge when a page plays a video or audio
  file or an HLS stream. The sheet lists sizes and qualities. HLS streams (including AES-128) are
  downloaded segment by segment in parallel and saved as one file. DRM-protected and stream-only
  players (like YouTube) can't be downloaded.
- **Shield stats**: blocked requests per day for the last 14 days, plus an estimate of data saved.
- **Undo close tab** from a snackbar.
- **Reader mode** using Mozilla Readability (Apache-2.0, `licenses/Readability-LICENSE.md`), styled
  with the Material theme and Google Sans Flex. It renders in a WebView with JavaScript disabled.
- **Text size**: set per site or as a global default. **Pull to refresh**.
- **Media**:
  - Picture-in-picture for fullscreen video.
  - Background play, with a media notification and play/pause controls.
  - Playback speed.
  - Force allow zoom.
- **History**: searchable, plus **address-bar suggestions** from history and from the search
  engine (Startpage, DuckDuckGo or Google).
- **Find in page**.

- **Block element**: Menu → Block element, then tap anything on the page. You can widen or narrow the
  selection, edit the CSS selector, and preview it before blocking. Rules are saved under
  Settings → My filters and apply instantly, without an engine rebuild.
- **Full uBlock Origin scriptlet support**: the build extracts uBO's scriptlets (`json-prune`,
  `set-constant`, `trusted-replace-xhr-response`, …) statically from the uBO source with
  `breezy-resources` and merges them with Brave's resources. Without them, uBO's rules, including
  YouTube video-ad removal, had nothing to run. Scriptlets are registered as native document-start
  scripts for each site, so they run before page scripts and aren't blocked by CSP or Trusted Types.
- **Home page shortcuts**: add with the + tile or Menu → Add to home, and long-press a tile to edit
  or remove it. The home page isn't a tab: closing the last tab goes back to it.
- **Copy link**: long-press the address in the toolbar. Long-press and drag moves the toolbar.
- **Add page as filter list**: open a raw filter list in a tab (useful for lists behind browser
  challenges) and add it from the menu.
- **Hide YouTube Shorts** list enabled by default.
- **Settings → Filter lists**: toggle a catalog of lists (EasyList, EasyPrivacy, uBlock filters,
  privacy, cookie notices, annoyances, badware, URLhaus, AdGuard Mobile, Peter Lowe, and fix lists)
  or add any list by HTTPS URL. Lists are cached on the device, so "Apply" only downloads what's new.
  Lists auto-update weekly on Wi-Fi.
- **Dark web pages** (default on): WebViews get a dark-themed context, so sites see
  `prefers-color-scheme: dark` and anything without a dark style is darkened automatically.
- **Frosted, movable toolbar**: long-press and drag it up or down, and the position is remembered. The
  blur is real backdrop blur on Android 16+ (Haze) and a translucent tint on older versions.
- **Scroll-lock repair**: pages left locked by a consent dialog or paywall that never rendered (or
  was dismissed) get unlocked automatically. The menu also has **Unlock scrolling** and
  **Page diagnostics**.
- **Search engine**: Startpage by default, with DuckDuckGo, Brave Search and Google available.
- **In-app updates**: checks this repo's latest GitHub Release once a day (Settings → Advanced, can
  be turned off), offers **Update**, downloads the APK, checks it against `SHA256SUMS.txt` and
  opens the installer. Android only accepts it if it's signed with the same key.
- **Theme**: System, Light or Dark, plus a **pure black (AMOLED)** option for dark theme.
- **Settings in collapsible groups** (Appearance, Search & browsing, Privacy & security, Shields,
  Filter lists, Downloads & media, Advanced), each showing a summary of its current values.
- **Google Sans Flex** typeface, using a rounded cut for headings.

## Security

Web content runs in the system WebView (Chromium's sandboxed renderer, updated through the Play
Store). Breezy additionally:
- Enables Safe Browsing, blocks mixed content, and blocks third-party cookies.
- Disables file and content access from pages, and denies site permission prompts
  (camera, location) by default.
- Blocks pop-ups that weren't triggered by a user gesture.
- Accepts only http(s) links from other apps. `intent:` links open with their component and
  selector stripped.
- Excludes all app data from cloud backup and device transfer.
- Downloads filter lists over HTTPS only, with a size cap, and sanitizes download file names.
- Only allows WebView remote debugging in debug builds.
- Presents itself as mobile Chrome (no WebView `wv` token, no `X-Requested-With` header) so Google
  sign-in works and sites don't serve WebView-specific behaviour.

## Size and performance

- **Rust library: 2.35 MB (was 3.12 MB).** adblock-rust's `embedded-domain-resolver` compiled the
  Public Suffix List into about 650 KiB of code. `psl.rs` embeds the list as text, parsed into hash
  sets on first use. `tests/psl_compat.rs` checks it against the original on every host in the
  filter lists (about 100k hosts, 0 mismatches). It's also slightly faster.
- `opt-level = 3`, fat LTO, `codegen-units = 1`, `panic = "abort"`. `opt-level = "s"` would save
  only another 6% of size, at about 27% slower request checks.
- Scriptlets are fetched with a dedicated native call that returns just the script, with no JSON.
  When scriptlets are already injected natively, the page's cosmetic request leaves the script out.
- The cosmetic filtering script only examines newly added elements, not every class change, and
  batches its work into idle time.
- Only the visible tab pre-renders offscreen tiles. Library license and metadata files are left out
  of the APK. R8 strips debug logging.

## Building

Requirements: JDK 17+, Android SDK (compileSdk 37.2, NDK 28.2.13676358), Rust plus `cargo-ndk`:

```sh
rustup target add aarch64-linux-android x86_64-linux-android armv7-linux-androideabi
cargo install cargo-ndk
./gradlew assembleDebug        # or assembleRelease (debug-signed; swap in your own key)
```

Gradle runs `cargo ndk` and the list compiler automatically. Use `-PrefreshFilterLists=true` to
re-download the lists.

Rust tests: `cd rust && cargo test`

Release builds ship **arm64-v8a only**. Debug builds also include x86_64 and armeabi-v7a.
Override the ABIs with `-PbreezyAbis=arm64-v8a,x86_64`.

## Releases

Pushing a `v*` tag runs `.github/workflows/release.yml`, which builds the arm64 APK and publishes
a GitHub Release with the APK and its SHA-256 checksum. To sign with your own key, add these
repository secrets: `BREEZY_KEYSTORE_BASE64` (`base64 -w0 release.jks`),
`BREEZY_KEYSTORE_PASSWORD`, `BREEZY_KEY_ALIAS` and `BREEZY_KEY_PASSWORD`. Without them the APK is
signed with a throwaway debug key, which means each release can't install over the previous one.

## Filter lists

EasyList, EasyPrivacy, uBlock Origin filters and privacy, Brave unbreak, plus Brave's
`resources.json`. These lists have their own licenses (GPLv3 / CC BY-SA, among others). Check them
before you redistribute builds that bundle them.

Google Sans Flex is under the SIL Open Font License 1.1 (`licenses/GoogleSansFlex-OFL.txt`). It is
bundled as a Latin subset with the weight and roundness axes.
