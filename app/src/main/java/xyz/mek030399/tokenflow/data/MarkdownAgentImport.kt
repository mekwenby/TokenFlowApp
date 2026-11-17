package xyz.mek030399.tokenflow.data

internal const val MAX_MARKDOWN_AGENT_BYTES = 512 * 1024

/** Markdown agents reuse the bounded UTF-8 .md reader used by notes. */
internal fun ImportedMarkdownNote.toImportedAgent(modelId: String?): AgentProfile {
    // Agent rows include the prompt in Room's CursorWindow; keep UTF-16 expansion bounded.
    if (body.toByteArray(Charsets.UTF_8).size > MAX_MARKDOWN_AGENT_BYTES) {
        throw NoteMarkdownFileException(NoteMarkdownFileError.TOO_LARGE)
    }
    if (body.isBlank()) throw NoteMarkdownFileException(NoteMarkdownFileError.EMPTY)
    val name = firstMarkdownAgentHeading(body) ?: title.trim().ifBlank { "Markdown" }
    return AgentProfile(
        name = name.takeAgentNameCodePoints(120),
        modelId = modelId,
        systemPrompt = body,
    )
}

private fun firstMarkdownAgentHeading(body: String): String? {
    var fenceCharacter: Char? = null
    var fenceLength = 0
    for (line in body.lineSequence()) {
        val fence = AGENT_FENCE.matchEntire(line)
        if (fence != null) {
            val marker = fence.groupValues[1]
            if (fenceCharacter == null) {
                fenceCharacter = marker.first()
                fenceLength = marker.length
            } else if (marker.first() == fenceCharacter && marker.length >= fenceLength &&
                fence.groupValues[2].isBlank()
            ) {
                fenceCharacter = null
            }
            continue
        }
        if (fenceCharacter != null) continue
        val heading = AGENT_HEADING.matchEntire(line)?.groupValues?.get(1)?.trim()
        if (!heading.isNullOrBlank()) return heading
    }
    return null
}

private fun String.takeAgentNameCodePoints(limit: Int): String =
    if (codePointCount(0, length) <= limit) this else substring(0, offsetByCodePoints(0, limit))

private val AGENT_HEADING = Regex("^ {0,3}#[\\t ]+(.*?)(?:[\\t ]+#+[\\t ]*)?$")
private val AGENT_FENCE = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
