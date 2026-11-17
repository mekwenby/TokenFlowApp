package xyz.mek030399.tokenflow.ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.mek030399.tokenflow.data.ConfigArchiveCodec
import xyz.mek030399.tokenflow.data.ProcessEvent

private const val MAX_WEB_TITLE_EVENTS = 80
private const val MAX_WEB_TITLE_ENTRIES = 100
private const val MAX_WEB_TITLE_JSON_BYTES = 64 * 1024
private const val MAX_WEB_TITLE_CODE_POINTS = 120
private val webAddressTitlePattern = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

/** Uses only titles already returned by successful web tools for this message. */
internal fun sourceLinkTitles(events: List<ProcessEvent>): Map<String, String> {
    val titles = linkedMapOf<String, String>()
    fun add(url: String?, title: String) {
        if (url != null && titles.size < MAX_WEB_TITLE_ENTRIES && isSafeHttpUrl(url)) {
            titles.putIfAbsent(url, title)
        }
    }
    events.asReversed().take(MAX_WEB_TITLE_EVENTS).forEach eventLoop@ { event ->
        if (titles.size >= MAX_WEB_TITLE_ENTRIES) return titles
        if (event.type != "tool_completed" || !event.ok || event.name !in setOf("web_search", "read_url")) return@eventLoop
        val result = boundedWebTitleJson(event.result) ?: return@eventLoop
        when (event.name) {
            "web_search" -> (result["results"] as? JsonArray).orEmpty().forEach sourceLoop@ { item ->
                if (titles.size >= MAX_WEB_TITLE_ENTRIES) return titles
                val source = item as? JsonObject ?: return@sourceLoop
                val title = source.webTitleString("title")?.let(::normalizeWebLinkTitle) ?: return@sourceLoop
                add(source.webTitleString("url"), title)
            }
            "read_url" -> {
                val title = result.webTitleString("title")?.let(::normalizeWebLinkTitle) ?: return@eventLoop
                add(result.webTitleString("final_url"), title)
                add(result.webTitleString("url"), title)
                add(boundedWebTitleJson(event.arguments)?.webTitleString("url"), title)
            }
        }
    }
    return titles
}

internal fun normalizeWebLinkTitle(value: String): String? {
    val normalized = buildString {
        var pendingSpace = false
        val codePoints = value.codePoints().iterator()
        while (codePoints.hasNext()) {
            val codePoint = codePoints.nextInt()
            when {
                Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) -> pendingSpace = isNotEmpty()
                Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT.toInt() ||
                    codePoint in 0xD800..0xDFFF -> Unit
                else -> {
                    if (pendingSpace) append(' ')
                    appendCodePoint(codePoint)
                    pendingSpace = false
                }
            }
        }
    }
    if (normalized.isBlank() || webAddressTitlePattern.matches(normalized)) return null
    return if (normalized.codePointCount(0, normalized.length) <= MAX_WEB_TITLE_CODE_POINTS) normalized else {
        normalized.substring(0, normalized.offsetByCodePoints(0, MAX_WEB_TITLE_CODE_POINTS)).trimEnd()
    }
}

private fun boundedWebTitleJson(value: String): JsonObject? {
    if (value.length > MAX_WEB_TITLE_JSON_BYTES || value.encodeToByteArray().size > MAX_WEB_TITLE_JSON_BYTES) return null
    return runCatching { ConfigArchiveCodec.defaultJson.parseToJsonElement(value) as? JsonObject }.getOrNull()
}

private fun JsonObject.webTitleString(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
