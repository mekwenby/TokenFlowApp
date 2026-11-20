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
        assertTrue(prompt.contains("user's selected Linux server"))
        assertTrue(prompt.contains("use the appropriate Cloud or remote MCP tools to complete that work"))
        assertTrue(prompt.contains("code only / no execution"))
        assertTrue(prompt.contains("Do not make a token connection check"))
        assertTrue(prompt.contains("at most 120 seconds"))
        assertTrue(prompt.contains("do not persist between calls"))
        assertTrue(prompt.contains("cloud_register_artifact"))
        assertFalse(prompt.contains("cloud_create_task"))
        assertFalse(prompt.contains("The attached mcp__ tools"))
        assertFalse(prompt.contains("This message explicitly requires Cloud"))
        val requiredPrompt = SystemPrompts.withCloudCapabilities(base, definitions, required = true)
        assertTrue(requiredPrompt.contains("This message explicitly requires Cloud"))
        assertTrue(requiredPrompt.contains("does not grant background-task permission"))
        assertEquals(base, SystemPrompts.withCloudCapabilities(base, emptyList(), required = false))
    }

    @Test
    fun mcpOnlyDoesNotAdvertiseLinuxExecutionAndBackgroundRequiresItsSchema() {
        val mcp = ToolDefinition("mcp__fixture", "Fixture MCP", kotlinx.serialization.json.JsonObject(emptyMap()))
        val prompt = SystemPrompts.withCloudCapabilities("BASE", listOf(mcp), required = false)
        assertTrue(prompt.contains("remote MCP services bound to the selected Infinite Cloud server"))
        assertFalse(prompt.contains("Linux server"))
        assertFalse(prompt.contains("120 seconds"))
        assertFalse(prompt.contains("cloud_register_artifact"))
        assertFalse(prompt.contains("cloud_create_task"))

        val taskOptions = ToolOptions(false, false, enableInfiniteCloud = true, cloudServerId = "server", allowCloudTaskCreation = true)
        val mixed = SystemPrompts.withCloudCapabilities("BASE", infiniteCloudToolDefinitions(taskOptions) + mcp, required = false)
        assertTrue(mixed.contains("The attached mcp__ tools"))
        assertTrue(mixed.contains("Use cloud_create_task only when the current user explicitly asks"))
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
