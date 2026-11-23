package dev.breezy.browser.ui

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import dev.breezy.browser.R
import dev.breezy.browser.browser.BrowserTab
import dev.breezy.browser.browser.UrlInput

/**
 * Reader mode: the article Readability extracted, rendered in a JavaScript-disabled WebView styled
 * with the app's Material colors and Google Sans Flex.
 */
@Composable
fun ReaderScreen(article: BrowserTab.Article, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var zoom by rememberSaveable { mutableIntStateOf(110) }
    val colors = MaterialTheme.colorScheme
    val html = remember(article, colors) {
        readerHtml(article, colors.surface, colors.onSurface, colors.onSurfaceVariant, colors.primary)
    }
    Surface(color = colors.surface, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(article.siteName.ifBlank { UrlInput.display(article.url) }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium)
                },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close reader") } },
                actions = {
                    IconButton(onClick = { zoom = (zoom - 10).coerceAtLeast(70) }) { Icon(Icons.Rounded.TextDecrease, "Smaller text") }
                    IconButton(onClick = { zoom = (zoom + 10).coerceAtMost(220) }) { Icon(Icons.Rounded.TextIncrease, "Larger text") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface),
                modifier = Modifier.statusBarsPadding(),
            )
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx -> readerWebView(ctx).also { it.loadDataWithBaseURL(article.url, html, "text/html", "utf-8", null) } },
                    update = { it.settings.textZoom = zoom },
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun readerWebView(context: android.content.Context) = WebView(context).apply {
    setBackgroundColor(android.graphics.Color.TRANSPARENT)
    settings.javaScriptEnabled = false // article HTML comes from the page; never run scripts
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (request.url.toString() == FONT_URL) {
                return WebResourceResponse("font/ttf", null, view.resources.openRawResource(R.font.google_sans_flex))
            }
            return null
        }

        // Links open in the real browser tab, not inside the reader.
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
    }
}

private const val FONT_URL = "https://breezy.reader.invalid/google-sans-flex.ttf"

private fun hex(c: Color): String = String.format("#%06X", c.toArgb() and 0xFFFFFF)

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun readerHtml(a: BrowserTab.Article, bg: Color, fg: Color, muted: Color, accent: Color) = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="script-src 'none'; object-src 'none'">
<style>
@font-face { font-family: 'Google Sans Flex'; src: url('$FONT_URL'); font-weight: 100 1000; }
html { background: ${hex(bg)}; }
body { margin: 0 auto; max-width: 42em; padding: 8px 22px 96px; color: ${hex(fg)};
  font: 400 1.05rem/1.7 'Google Sans Flex', system-ui, sans-serif; word-wrap: break-word; }
h1.title { font-size: 1.9rem; line-height: 1.2; font-weight: 800; margin: 12px 0 8px; font-variation-settings: 'ROND' 100; }
.byline { color: ${hex(muted)}; font-size: .9rem; margin-bottom: 24px; }
h2, h3, h4 { line-height: 1.3; font-weight: 700; margin-top: 1.6em; }
a { color: ${hex(accent)}; }
img, video, figure, iframe { max-width: 100%; height: auto; border-radius: 16px; }
figure { margin: 1.2em 0; } figcaption { color: ${hex(muted)}; font-size: .85rem; }
blockquote { margin: 1em 0; padding: 0 1em; border-left: 4px solid ${hex(accent)}; color: ${hex(muted)}; }
pre, code { font-family: ui-monospace, monospace; font-size: .9em; }
pre { overflow-x: auto; padding: 12px; border-radius: 12px; background: rgba(127,127,127,.12); }
table { border-collapse: collapse; display: block; overflow-x: auto; }
td, th { border: 1px solid rgba(127,127,127,.3); padding: 6px 8px; }
</style></head><body>
<h1 class="title">${esc(a.title)}</h1>
<div class="byline">${esc(listOf(a.byline, a.siteName).filter { it.isNotBlank() }.joinToString(" · "))}</div>
${a.html}
</body></html>
"""
