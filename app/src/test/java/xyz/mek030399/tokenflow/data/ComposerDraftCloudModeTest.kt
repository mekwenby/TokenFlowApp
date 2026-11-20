package xyz.mek030399.tokenflow.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerDraftCloudModeTest {
    private val json = ConfigArchiveCodec.defaultJson

    @Test
    fun restoredDraftKeepsContentAndSubmissionFromLegacyRequiredDraft() {
        val saved = json.decodeFromString<SavedShareState>(
            """{"drafts":{"one":{"text":"build","attachments":[{"uri":"content://fixture/report","displayName":"report.txt","mimeType":"text/plain"}],"submittedRequestId":"request","cloudExecutionMode":"REQUIRED"}}}""",
        )
        assertEquals(
            ComposerDraft("build", listOf(PendingAttachment("content://fixture/report", "report.txt", "text/plain")), "request"),
            saved.drafts.getValue("one"),
        )
    }
}
