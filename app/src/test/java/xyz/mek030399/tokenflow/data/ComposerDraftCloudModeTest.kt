package xyz.mek030399.tokenflow.data

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerDraftCloudModeTest {
    private val json = ConfigArchiveCodec.defaultJson

    @Test
    fun oldDraftsDefaultToAutoAndRequiredModeSurvivesSerialization() {
        assertEquals(CloudExecutionMode.AUTO, json.decodeFromString<ComposerDraft>("{\"text\":\"old draft\"}").cloudExecutionMode)
        val saved = SavedShareState(drafts = mapOf("one" to ComposerDraft("build", cloudExecutionMode = CloudExecutionMode.REQUIRED),
            "two" to ComposerDraft("chat")))
        assertEquals(saved, json.decodeFromString<SavedShareState>(json.encodeToString(saved)))
    }
}
