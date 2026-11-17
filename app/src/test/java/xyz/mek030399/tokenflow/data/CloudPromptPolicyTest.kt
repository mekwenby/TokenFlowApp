package xyz.mek030399.tokenflow.data

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudPromptPolicyTest {
    @Test
    fun unavailableCloudDoesNotAdvertiseExecutionEvenForRequiredMode() {
        val base = SystemPrompts.compose("Keep this complete role", "", "UTC")
        assertEquals(base, SystemPrompts.withCloudCapabilities(base, emptyList(), required = true))
        assertEquals(base, SystemPrompts.withCloudCapabilities(base, OfflineCalculationTools().definitions(), required = false))
    }

    @Test
    fun availableCloudGuidesRealWorkButPreservesCodeOnlyAndTaskBoundaries() {
        val base = SystemPrompts.compose("Keep this complete role", "", "UTC")
        val definitions = infiniteCloudToolDefinitions(ToolOptions(false, false, enableInfiniteCloud = true, cloudServerId = "server"))
        val prompt = SystemPrompts.withCloudCapabilities(base, definitions, required = false)
        assertTrue(prompt.startsWith(base))
        assertTrue(prompt.contains("use the appropriate Cloud or remote MCP tools to complete that work"))
        assertTrue(prompt.contains("code only / no execution"))
        assertTrue(prompt.contains("Do not make a token connection check"))
        assertTrue(prompt.contains("at most 120 seconds"))
        assertTrue(prompt.contains("do not persist between calls"))
        assertTrue(prompt.contains("cloud_register_artifact"))
        assertTrue(prompt.contains("does not grant background-task permission"))
        assertFalse(prompt.contains("This message explicitly requires Cloud"))
        assertTrue(SystemPrompts.withCloudCapabilities(base, definitions, required = true)
            .contains("This message explicitly requires Cloud"))
        assertEquals(base, SystemPrompts.withCloudCapabilities(base, emptyList(), required = false))
    }

    @Test
    fun existingMessagesAndEventsDecodeWithoutCloudFields() {
        val json = ConfigArchiveCodec.defaultJson
        val user = json.decodeFromString<UserMessageMetadata>("{\"knowledge_chunk_ids\":[7]}")
        assertEquals(CloudExecutionMode.AUTO, user.cloudExecutionMode)
        assertEquals(listOf(7L), user.knowledgeChunkIds)
        val assistant = json.decodeFromString<AssistantMetadata>("{\"completion_status\":\"completed\",\"events\":[{\"type\":\"tool_completed\",\"name\":\"calculate\"}]}")
        assertEquals(CloudExecutionMode.AUTO, assistant.cloudExecutionMode)
        assertFalse(assistant.cloudEnabled)
        assertFalse(assistant.events.single().cloudDispatched)
    }
}
