package xyz.mek030399.tokenflow.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantMetadataIdentityTest {
    private val json = DirectApiTransport.defaultJson

    @Test
    fun legacyMetadataWithoutAssistantIdentityStillDecodes() {
        val metadata = json.decodeFromString<AssistantMetadata>(
            """{"completion_status":"completed","events":[{"type":"status","message":"legacy"}]}""",
        )

        assertEquals("completed", metadata.completionStatus)
        assertEquals("legacy", metadata.events.single().message)
        assertNull(metadata.assistantIdentity)
    }

    @Test
    fun assistantIdentityRoundTripsWithTheRestOfMetadata() {
        val identity = AssistantIdentitySnapshot(
            modelId = "model-internal-id",
            remoteModelId = "provider/model-actual",
            nickname = "One Thought",
        )
        val metadata = AssistantMetadata(
            events = listOf(ProcessEvent(type = "thinking", content = "reasoning")),
            usage = Usage(inputTokens = 12, outputTokens = 4).serializable(),
            completionStatus = "completed",
            assistantIdentity = identity,
        )

        val decoded = json.decodeFromString<AssistantMetadata>(json.encodeToString(metadata))

        assertEquals(metadata, decoded)
        assertEquals(identity, decoded.assistantIdentity)
    }
}
