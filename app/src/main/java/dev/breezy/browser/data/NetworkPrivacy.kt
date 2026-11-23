package dev.breezy.browser.data

import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import dev.breezy.browser.AppSettings
import java.net.ProxySelector

/** Applies the Secure DNS setting to WebView and to the app's own HTTP connections. */
object NetworkPrivacy {
    private var selectorInstalled = false

    val supported: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    fun apply() {
        if (!selectorInstalled) {
            DnsProxy.installAppProxySelector(ProxySelector.getDefault())
            selectorInstalled = true
        }
        val endpoint = AppSettings.dnsEndpoint
        SecureDns.strict = AppSettings.dnsStrict
        SecureDns.clearCache()
        if (endpoint == null || !supported) {
            SecureDns.endpoint = null
            if (supported) runCatching { ProxyController.getInstance().clearProxyOverride({ it.run() }, {}) }
            DnsProxy.stop()
            return
        }
        SecureDns.endpoint = endpoint
        val port = DnsProxy.start()
        ProxyController.getInstance().setProxyOverride(
            ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").build(),
            { it.run() },
            {},
        )
    }
}
