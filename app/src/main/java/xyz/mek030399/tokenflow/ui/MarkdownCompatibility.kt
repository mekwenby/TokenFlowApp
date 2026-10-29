package xyz.mek030399.tokenflow.ui

import java.util.ArrayDeque
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Paragraph

/** Repairs CJK punctuation at strong-emphasis boundaries, using the parser's original source spans. */
internal fun repairCjkStrongEmphasis(markdown: String, document: Node): String {
    if (!markdown.contains("**")) return markdown
    val protected = BooleanArray(markdown.length)
    val paragraphs = mutableListOf<Node>()
    val pending = ArrayDeque<Node>().apply { add(document) }
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        when (node) {
            // Keep code, explicit HTML, and link syntax out of the delimiter scan.
            is Code, is FencedCodeBlock, is IndentedCodeBlock, is HtmlBlock, is HtmlInline,
            is Link, is Image -> node.sourceSpans.forEach { span ->
                val start = span.inputIndex.coerceIn(0, markdown.length)
                val end = (span.inputIndex + span.length).coerceIn(start, markdown.length)
                protected.fill(true, start, end)
            }
            is Paragraph, is Heading, is TableCell -> paragraphs += node
        }
        var child = node.firstChild
        while (child != null) { pending.add(child); child = child.next }
    }
    val replacements = mutableMapOf<Int, String>()
    for (paragraph in paragraphs) {
        val spans = paragraph.sourceSpans
        if (spans.isEmpty()) continue
        val start = spans.first().inputIndex.coerceAtLeast(0)
        val end = (spans.last().inputIndex + spans.last().length).coerceAtMost(markdown.length)
        val openers = ArrayDeque<StrongOpener>()
        var position = start
        while (position + 1 < end) {
            if (protected[position] || markdown[position] != '*' || markdown[position + 1] != '*') {
                position++
                continue
            }
            val runStart = position
            while (position < end && markdown[position] == '*') position++
            if (position - runStart != 2 || isMarkdownEscaped(markdown, runStart)) continue
            val before = if (runStart > start) markdown.codePointBefore(runStart) else -1
            val after = if (position < end) markdown.codePointAt(position) else -1
            val beforeSpace = isMarkdownSpace(before)
            val afterSpace = isMarkdownSpace(after)
            val beforePunctuation = isMarkdownPunctuation(before)
            val afterPunctuation = isMarkdownPunctuation(after)
            val normalOpen = !afterSpace && (!afterPunctuation || beforeSpace || beforePunctuation)
            val normalClose = !beforeSpace && (!beforePunctuation || afterSpace || afterPunctuation)
            val relaxedOpen = !normalOpen && afterPunctuation && Character.isLetterOrDigit(before)
            val relaxedClose = !normalClose && beforePunctuation && Character.isLetterOrDigit(after)
            if ((normalClose || relaxedClose) && openers.isNotEmpty()) {
                val opener = openers.removeLast()
                if ((opener.relaxed || relaxedClose) && containsCjk(markdown, opener.position + 2, runStart)) {
                    replacements[opener.position] = "<strong>"
                    replacements[runStart] = "</strong>"
                }
            } else if (normalOpen || relaxedOpen) {
                openers.add(StrongOpener(runStart, relaxedOpen))
            }
        }
    }
    if (replacements.isEmpty()) return markdown
    return buildString(markdown.length) {
        var position = 0
        while (position < markdown.length) {
            val replacement = replacements[position]
            if (replacement != null) { append(replacement); position += 2 }
            else append(markdown[position++])
        }
    }
}

private data class StrongOpener(val position: Int, val relaxed: Boolean)

private fun isMarkdownEscaped(value: String, position: Int): Boolean {
    var slashes = 0
    var cursor = position - 1
    while (cursor >= 0 && value[cursor--] == '\\') slashes++
    return slashes % 2 != 0
}

private fun isMarkdownSpace(codePoint: Int): Boolean =
    codePoint < 0 || Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)

private fun isMarkdownPunctuation(codePoint: Int): Boolean = codePoint >= 0 && when (Character.getType(codePoint)) {
    Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
    Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
    Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
    Character.OTHER_PUNCTUATION.toInt(), Character.MATH_SYMBOL.toInt(),
    Character.CURRENCY_SYMBOL.toInt(), Character.MODIFIER_SYMBOL.toInt(), Character.OTHER_SYMBOL.toInt() -> true
    else -> false
}

private fun containsCjk(value: String, start: Int, end: Int): Boolean {
    var position = start
    while (position < end) {
        val codePoint = value.codePointAt(position)
        when (Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> return true
            else -> position += Character.charCount(codePoint)
        }
    }
    return false
}

private val providerCitationPattern = Regex("\uE200cite\uE202turn[a-zA-Z0-9_]+(?:\uE202turn[a-zA-Z0-9_]+)*\uE201")

/** These provider-local IDs carry no public source URL; do not invent a clickable reference. */
internal fun readableProviderCitations(value: String, unavailableLabel: String): String =
    providerCitationPattern.replace(value) { unavailableLabel }
