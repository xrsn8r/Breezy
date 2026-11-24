package dev.breezy.browser.browser

import java.security.SecureRandom

/**
 * Per-session random names for everything Breezy puts into web pages: the JavaScript bridge
 * objects, the page-script markers and temporary data attributes.
 *
 * With fixed names (`window.BreezyShield`, `__breezyPrivacy`...) any site could check for them
 * and know it's talking to Breezy, which makes users stand out and undercuts fingerprinting
 * protection. The scripts are written with the readable names; [js] rewrites them to this
 * session's names before they reach a page. Names change every time the app starts.
 */
object Obscure {
    private val random = SecureRandom()

    private fun name(): String {
        val letters = "abcdefghijklmnopqrstuvwxyz"
        val chars = letters + "0123456789"
        return buildString {
            append(letters[random.nextInt(letters.length)])
            repeat(7) { append(chars[random.nextInt(chars.length)]) }
        }
    }

    val shield = name()
    val page = name()
    val host = name()
    private val marker = name()
    private val attr = name()

    private val markerRegex = Regex("__breezy_?([A-Za-z]*)")

    /** Rewrite Breezy's readable names in [source] to this session's random ones. */
    fun js(source: String): String = source
        .replace("BreezyShield", shield)
        .replace("BreezyPage", page)
        .replace("BreezyHost", host)
        .replace("data-breezy-", "data-$attr-")
        .replace(markerRegex) { m -> marker + m.groupValues[1] }
}
