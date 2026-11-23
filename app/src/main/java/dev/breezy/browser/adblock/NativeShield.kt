package dev.breezy.browser.adblock

/**
 * Raw JNI surface of `libbreezy_core.so` (rust/breezy-core/src/jni_bridge.rs), which wraps
 * Brave's adblock-rust engine. Use [Shield] instead of calling this directly.
 */
object NativeShield {
    init {
        System.loadLibrary("breezy_core")
    }

    /** Compile ABP/uBO filter lists (UTF-8) into a new engine and make it current. */
    @JvmStatic external fun compileLists(lists: Array<ByteArray>): Boolean

    /** Load an engine produced by [serialize] / the build-time compiler. */
    @JvmStatic external fun loadSerialized(bytes: ByteArray): Boolean

    @JvmStatic external fun serialize(): ByteArray

    /** Load Brave/uBO scriptlet + redirect resources (resources.json). Returns count or -1. */
    @JvmStatic external fun loadResources(json: ByteArray): Int

    @JvmStatic external fun isLoaded(): Boolean

    /**
     * Check a request. Returns `null` to allow, `""` to block, or a `data:` URL to serve instead
     * (e.g. a neutered analytics script). The resource type is inferred natively from the URL,
     * the Accept header and [isMainFrame].
     */
    @JvmStatic external fun check(
        url: String,
        sourceUrl: String?,
        accept: String?,
        isMainFrame: Boolean,
        method: String?,
    ): String?

    /** JSON: {hide_selectors, procedural_actions, exceptions, injected_script, generichide}. */
    @JvmStatic external fun cosmeticResources(url: String): String

    /** JSON array of generic hide selectors for the given class/id JSON arrays. */
    @JvmStatic external fun hiddenSelectors(classes: String, ids: String, exceptions: String): String
}
