package xyz.mek030399.tokenflow.data

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ShareDraftStoreAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun delayedUiSaveCannotRestoreASettledSubmissionMarker() = runBlocking {
        val store = ShareDraftStore(context)
        val draft = ComposerDraft("retry this question", submittedRequestId = "failed-request")
        val captured = SavedShareState(mapOf("chat" to draft))
        try {
            store.save(captured)
            store.settleSubmission("failed-request", "chat", false) { it.copy(drafts = mapOf("chat" to draft.copy(submittedRequestId = null))) }
            // This UI state was captured before the application's finalization acquired the store lock.
            store.save(captured)
            assertEquals(draft.copy(submittedRequestId = null), store.load().drafts.getValue("chat"))
        } finally { store.save(SavedShareState()) }
    }

    @Test fun delayedSaveAndUpdateCannotRestoreAnAcceptedQuestion() = runBlocking {
        val store = ShareDraftStore(context)
        val captured = SavedShareState(mapOf("chat" to ComposerDraft("already sent", submittedRequestId = "accepted-request")))
        try {
            store.save(captured)
            store.settleSubmission("accepted-request", "chat", true) { SavedShareState(mapOf("chat" to ComposerDraft())) }
            store.save(captured)
            assertEquals(ComposerDraft(), store.load().drafts.getValue("chat"))
            store.update { captured }
            assertEquals(ComposerDraft(), store.load().drafts.getValue("chat"))
        } finally { store.save(SavedShareState()) }
    }

    @Test fun staleProvisionalSaveKeepsFailedQuestionInItsResolvedConversation() = runBlocking {
        val store = ShareDraftStore(context)
        val draft = ComposerDraft("keep in actual chat", submittedRequestId = "resolved-request")
        val captured = SavedShareState(mapOf("provisional" to draft))
        try {
            store.save(captured)
            store.settleSubmission("resolved-request", "actual", false) {
                SavedShareState(mapOf("actual" to draft.copy(submittedRequestId = null)))
            }
            store.save(captured)
            val restored = store.load()
            assertFalse(restored.drafts.containsKey("provisional"))
            assertEquals(draft.copy(submittedRequestId = null), restored.drafts.getValue("actual"))
            val newer = ComposerDraft("a different draft")
            store.save(SavedShareState(mapOf("actual" to newer)))
            store.save(captured)
            assertEquals(newer, store.load().drafts.getValue("actual"))
            val nextSubmission = ComposerDraft("another submitted question", submittedRequestId = "next-request")
            store.save(SavedShareState(mapOf("actual" to nextSubmission)))
            store.save(captured)
            assertEquals(nextSubmission, store.load().drafts.getValue("actual"))
            store.save(SavedShareState(mapOf("actual" to ComposerDraft())))
            store.save(captured)
            assertEquals(ComposerDraft(), store.load().drafts.getValue("actual"))
        } finally { store.save(SavedShareState()) }
    }

    private fun source(name: String, bytes: ByteArray): File = File(context.cacheDir, "camera_captures/${UUID.randomUUID()}-$name").apply {
        parentFile?.mkdirs(); writeBytes(bytes)
    }
    private fun uri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    @Test fun sourceCanDisappearAfterCaptureAndItsCopyStillImports() = runBlocking {
        val store = ShareDraftStore(context)
        val file = source("notes.txt", "shared 中文 text".toByteArray())
        val share = store.capture(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "https://example.com")
            .putExtra(Intent.EXTRA_STREAM, uri(file)))!!
        val pending = share.files.single().attachment!!
        assertEquals("https://example.com", share.text)
        assertEquals(PendingAttachmentOrigin.SHARE, pending.origin)
        file.delete()
        val database = Room.inMemoryDatabaseBuilder(context, TokenFlowDatabase::class.java).build()
        val attachments = AttachmentStore(context, database.localDao())
        try {
            val prepared = attachments.prepare("not-yet-committed", listOf(pending))
            assertEquals("shared 中文 text", prepared.single().extractedText)
            assertTrue(database.localDao().attachmentsForMessage("not-yet-committed").isEmpty())
            attachments.deleteFiles(prepared)
        } finally { store.discard(listOf(pending)); database.close() }
    }

    @Test fun multipleFilesAndStateSurviveRotationStyleReloadWithoutExternalGrants() = runBlocking {
        val store = ShareDraftStore(context)
        val first = source("first.txt", "one".toByteArray())
        val second = source("second.md", "two".toByteArray())
        try {
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/plain")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri(first), uri(second)))
            val share = store.capture(intent)!!
            val saved = SavedShareState(mapOf("chat" to ComposerDraft("draft", share.files.mapNotNull { it.attachment })), share, listOf("receipt"))
            store.save(saved)
            first.delete(); second.delete()
            val restored = ShareDraftStore(context).load()
            assertEquals(saved, restored)
            assertEquals(2, restored.incoming!!.files.size)
            assertTrue(restored.drafts.getValue("chat").attachments.all { File(it.appOwnedDraftPath!!).exists() })
            store.discard(restored.drafts.getValue("chat").attachments)
        } finally { first.delete(); second.delete(); store.save(SavedShareState()) }
    }

    @Test fun actualSizeAndUnsupportedUrisRemainRemovableFailures() = runBlocking {
        val store = ShareDraftStore(context)
        val huge = source("huge.txt", ByteArray((AttachmentStore.MAX_DOCUMENT_BYTES + 1).toInt()) { 'a'.code.toByte() })
        try {
            val result = store.capture(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri(huge)))!!
            assertNotNull(result.files.single().error)
            assertNull(result.files.single().attachment)
            val invalid = store.capture(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, android.net.Uri.fromFile(huge)))!!
            assertNotNull(invalid.files.single().error)
            assertNull(invalid.files.single().attachment)
        } finally { huge.delete() }
    }
}
