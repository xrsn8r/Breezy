package dev.breezy.browser.browser

import android.content.Context
import android.view.View
import android.webkit.WebView

/**
 * WebView that can keep reporting itself as visible while the app is in the background.
 *
 * When the window becomes invisible (screen off, another app), WebView tells Chromium the page is
 * hidden, and Chromium suspends video playback, and pages like YouTube pause on visibilitychange.
 * While [keepVisible] is set (background play or picture-in-picture), report VISIBLE instead so
 * playback continues.
 */
class BreezyWebView(context: Context) : WebView(context) {
    var keepVisible = false

    /** Everything Breezy injects goes through here, so its names are this session's random ones. */
    override fun evaluateJavascript(script: String, resultCallback: android.webkit.ValueCallback<String>?) {
        super.evaluateJavascript(Obscure.js(script), resultCallback)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(if (keepVisible && visibility != View.VISIBLE) View.VISIBLE else visibility)
    }
}
