package xyz.mek030399.tokenflow.ui

import java.net.URI

/** Display-only labels: the URL annotation always keeps the original destination. */
internal fun compactWebLinkLabel(url: String, title: String? = null, webLinkTitles: Map<String, String> = emptyMap()): String {
    val knownTitle = webLinkTitles[url]?.let(::normalizeWebLinkTitle) ?: title?.let(::normalizeWebLinkTitle)
    if (knownTitle != null) return shortenLinkLabel(knownTitle, 80)
    val uri = URI(url)
    val host = uri.host.orEmpty().lowercase().removePrefix("www.")
    val port = uri.port.takeIf { it >= 0 && !(it == 443 && uri.scheme.equals("https", true)) &&
        !(it == 80 && uri.scheme.equals("http", true)) }
    return shortenLinkLabel(host + (port?.let { ":$it" } ?: ""), 60)
}

private fun shortenLinkLabel(value: String, maxCodePoints: Int): String =
    if (value.codePointCount(0, value.length) <= maxCodePoints) value
    else value.substring(0, value.offsetByCodePoints(0, maxCodePoints - 1)) + "…"
