package dev.breezy.browser.data

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/**
 * Page translation through Google Translate (the same free endpoint Chrome's translate bar
 * uses). The page's text is sent to Google; nothing else (no cookies, no page address).
 */
object Translator {
    /** Languages offered as targets: code to display name (in that language's own terms). */
    val languages: List<Pair<String, String>> by lazy {
        listOf("en", "es", "fr", "de", "it", "pt", "nl", "sv", "da", "no", "fi", "pl", "cs", "uk", "ru", "tr",
            "el", "ro", "hu", "ar", "he", "fa", "hi", "bn", "ur", "id", "ms", "vi", "th", "tl", "ja", "ko",
            "zh-CN", "zh-TW")
            .map { it to Locale.forLanguageTag(it).let { l -> l.getDisplayName(l).replaceFirstChar { c -> c.titlecase(l) } } }
    }

    /** The user's chosen target language, or the device language. */
    fun target(setting: String): String = setting.ifBlank {
        val l = Locale.getDefault()
        when {
            l.language == "zh" -> if (l.country in setOf("TW", "HK", "MO") || l.script == "Hant") "zh-TW" else "zh-CN"
            else -> l.language.ifBlank { "en" }
        }
    }

    fun displayName(code: String): String =
        Locale.forLanguageTag(code).let { it.getDisplayLanguage(Locale.getDefault()).ifBlank { code } }

    /** True when [pageLang] (an HTML lang attribute) is a different language from [target]. */
    fun differs(pageLang: String, target: String): Boolean {
        val a = pageLang.substringBefore('-').substringBefore('_').lowercase()
        val b = target.substringBefore('-').lowercase()
        return a.length in 2..3 && a != b
    }

    data class Result(val texts: List<String>, val sourceLang: String?)

    /** Translate [texts] to [target]; the source language is detected per string. */
    fun translate(texts: List<String>, target: String): Result {
        val conn = URI("https://translate.googleapis.com/translate_a/t?client=gtx&dt=t&sl=auto&tl=" +
            URLEncoder.encode(target, "UTF-8")).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 20_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        try {
            conn.outputStream.use { out ->
                out.write(texts.joinToString("&") { "q=" + URLEncoder.encode(it, "UTF-8") }.toByteArray())
            }
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            return parse(conn.inputStream.use { it.readBytes().decodeToString() }, texts)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Response shapes: several strings give `[["text","es"], ...]`; one string gives
     * `["text"]` or `[["text","es"]]` (or `"text"`).
     */
    internal fun parse(body: String, input: List<String>): Result {
        val trimmed = body.trim()
        if (trimmed.startsWith("\"")) return Result(listOf(JSONArray("[$trimmed]").getString(0)), null)
        val arr = JSONArray(trimmed)
        val out = ArrayList<String>(input.size)
        val langs = HashMap<String, Int>()
        for (i in 0 until arr.length()) {
            when (val item = arr.get(i)) {
                is JSONArray -> {
                    out += item.optString(0)
                    item.optString(1).takeIf { it.isNotBlank() }?.let { langs.merge(it, 1, Int::plus) }
                }
                else -> out += item.toString()
            }
        }
        if (out.size != input.size) throw java.io.IOException("Unexpected translation response")
        return Result(out, langs.maxByOrNull { it.value }?.key)
    }
}
