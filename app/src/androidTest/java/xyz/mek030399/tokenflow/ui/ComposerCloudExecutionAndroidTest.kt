package xyz.mek030399.tokenflow.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import xyz.mek030399.tokenflow.data.CloudExecutionMode
import xyz.mek030399.tokenflow.data.CloudServerProfile
import xyz.mek030399.tokenflow.data.ComposerDraft
import xyz.mek030399.tokenflow.ui.theme.TokenFlowTheme

class ComposerCloudExecutionAndroidTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun cloudRequirementChipShowsServerCancelsAndPendingSettingsBlockSend() {
        val server = CloudServerProfile(id = "cloud-ui", name = "My Linux", host = "example.com", username = "runner",
            keyConfigured = true, hostKeyFingerprint = "SHA256:test")
        val state = mutableStateOf(AppUiState(activeConversationId = "chat", cloudServers = listOf(server),
            config = ConversationConfig(enableInfiniteCloud = true, cloudServerId = server.id),
            drafts = mapOf("chat" to ComposerDraft("generate a file"))))
        composeRule.setContent {
            TokenFlowTheme {
                Composer(state.value, {}, {}, {}, {}, {}, onCloudExecutionMode = { mode ->
                    state.value = state.value.copy(drafts = mapOf("chat" to ComposerDraft("generate a file", cloudExecutionMode = mode)))
                }, onCloudConfiguration = {}, onSend = { true }, onStop = {})
            }
        }
        composeRule.onNodeWithTag("cloud_execution_mode").assertIsNotSelected().performClick()
        composeRule.onNodeWithTag("cloud_execution_mode").assertIsSelected()
        composeRule.onNodeWithText("My Linux", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("cloud_execution_mode").performClick().assertIsNotSelected()
        composeRule.runOnIdle { state.value = state.value.copy(pendingToolSettingsConversationIds = setOf("chat")) }
        composeRule.onNodeWithTag(UiTestTags.MESSAGE_ACTION).assertIsNotEnabled()
        composeRule.runOnIdle { state.value = state.value.copy(pendingToolSettingsConversationIds = setOf("other")) }
        composeRule.onNodeWithTag(UiTestTags.MESSAGE_ACTION).assertIsEnabled()
        composeRule.runOnIdle { state.value = state.value.copy(config = state.value.config.copy(enableInfiniteCloud = false)) }
        composeRule.onNodeWithTag("cloud_execution_mode").assertDoesNotExist()
        composeRule.onNodeWithTag("cloud_execution_configure").assertDoesNotExist()
        composeRule.onNodeWithTag(UiTestTags.MESSAGE_ACTION).assertIsEnabled()
    }

    @Test
    fun unavailableCloudRequirementExplainsAndOpensConfiguration() {
        var opened = 0
        composeRule.setContent {
            TokenFlowTheme {
                Composer(AppUiState(config = ConversationConfig(enableInfiniteCloud = true)),
                    {}, {}, {}, {}, {}, {}, { opened += 1 }, { true }, {})
            }
        }
        composeRule.onNodeWithTag("cloud_execution_mode").assertIsNotEnabled()
        composeRule.onNodeWithTag("cloud_execution_configure").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, opened) }
    }
}
