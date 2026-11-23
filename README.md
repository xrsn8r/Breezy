# Breezy

A fast Android WebView browser with **Brave's adblock-rust engine built into its core** and a
**Material 3 Expressive** UI (Jetpack Compose).

## How it fits together

```
rust/breezy-core          Rust cdylib (libbreezy_core.so)
 ├─ lib.rs                thread-safe wrapper around adblock::Engine (global Arc, hot-swappable)
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
