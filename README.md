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
