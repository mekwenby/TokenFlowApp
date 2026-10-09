package xyz.mek030399.tokenflow.data

/** Retains the proposed edit separately from the original conversation's unsent composer draft. */
data class EditedQuestionSubmission(
    val message: ChatMessage,
    val sourceDraft: ComposerDraft? = null,
)

/** Includes only the history that precedes an edited question and the question itself. */
internal fun editedQuestionPrefix(messages: List<ChatMessage>, messageId: String): List<ChatMessage> {
    val index = messages.indexOfFirst { it.id == messageId }
    require(index >= 0) { "Message is not part of the conversation" }
    require(messages[index].role == "user") { "Only user questions can be edited" }
    require(messages[index].status == "completed") { "Wait for the question to be saved before editing" }
    return messages.take(index + 1)
}

/** A summary that covers the edited question would carry the old question into the new request. */
internal fun summaryBeforeEditedQuestion(summary: ContextSummary?, prefix: List<ChatMessage>): ContextSummary? =
    ContextBuilder.validSummary(summary, prefix.dropLast(1))
