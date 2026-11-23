package dev.breezy.browser.data

import android.net.Uri

/** Removes common tracking parameters (utm_*, fbclid, gclid, ...) from URLs. */
object Tracking {
    private val exact = setOf(
        "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "twclid", "ttclid",
        "igshid", "igsh", "mc_cid", "mc_eid", "_hsenc", "_hsmi", "__hssc", "__hstc", "__hsfp", "hsctatracking",
        "mkt_tok", "vero_conv", "vero_id", "oly_anon_id", "oly_enc_id", "rb_clickid", "s_cid", "_openstat",
        "wickedid", "ml_subscriber", "ml_subscriber_hash", "srsltid", "si_source", "ref_src", "ref_url",
        "spm", "scm", "_ga", "_gl", "epik", "pk_campaign", "pk_kwd", "piwik_campaign", "piwik_kwd",
        "trk", "trkCampaign", "sc_campaign", "sc_channel", "sc_content", "sc_medium", "sc_outcome",
    )
    private val prefixes = listOf("utm_", "pk_", "mtm_", "ga_", "hsa_", "stm_")

    private fun isTracking(name: String): Boolean {
        val n = name.lowercase()
        return n in exact || prefixes.any { n.startsWith(it) }
    }

    /** The URL with tracking parameters removed, or the original if nothing changed. */
    fun clean(url: String): String {
        if (!url.startsWith("http") || !url.contains('?')) return url
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
        val names = runCatching { uri.queryParameterNames }.getOrNull() ?: return url
        if (names.none(::isTracking)) return url
        // Rebuild the raw query to keep the remaining parameters' original encoding and order.
        val kept = uri.encodedQuery.orEmpty().split('&').filter { part ->
            val name = Uri.decode(part.substringBefore('='))
            part.isNotEmpty() && !isTracking(name)
        }
        return uri.buildUpon().encodedQuery(kept.joinToString("&").ifEmpty { null }).build().toString()
    }
}
