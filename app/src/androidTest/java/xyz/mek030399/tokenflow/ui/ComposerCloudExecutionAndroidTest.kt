package xyz.mek030399.tokenflow.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import xyz.mek030399.tokenflow.data.CloudServerProfile
import xyz.mek030399.tokenflow.data.ComposerDraft
import xyz.mek030399.tokenflow.ui.theme.TokenFlowTheme

class ComposerCloudExecutionAndroidTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun enablingCloudDoesNotChangeComposerBoundsAndPendingSettingsBlockSend() {
        val server = CloudServerProfile(id = "cloud-ui", name = "My Linux", host = "example.com", username = "runner",
            keyConfigured = true, hostKeyFingerprint = "SHA256:test")
        val state = mutableStateOf(AppUiState(activeConversationId = "chat", cloudServers = listOf(server),
            config = ConversationConfig(),
            drafts = mapOf("chat" to ComposerDraft("generate a file"))))
        composeRule.setContent {
            TokenFlowTheme {
                Composer(state.value, {}, {}, {}, {}, {}, onSend = { true }, onStop = {})
            }
        }
        val input = composeRule.onNodeWithTag(UiTestTags.MESSAGE_INPUT)
        val action = composeRule.onNodeWithTag(UiTestTags.MESSAGE_ACTION)
        val inputBefore = input.getUnclippedBoundsInRoot()
        val actionBefore = action.getUnclippedBoundsInRoot()
        composeRule.runOnIdle {
            state.value = state.value.copy(config = state.value.config.copy(enableInfiniteCloud = true, cloudServerId = server.id))
        }
        assertEquals(inputBefore, input.getUnclippedBoundsInRoot())
        assertEquals(actionBefore, action.getUnclippedBoundsInRoot())
        action.assertIsEnabled()
        composeRule.runOnIdle {
            state.value = state.value.copy(config = state.value.config.copy(maxToolCalls = 0, cloudServerId = null))
        }
        assertEquals(inputBefore, input.getUnclippedBoundsInRoot())
        assertEquals(actionBefore, action.getUnclippedBoundsInRoot())
        action.assertIsEnabled()
        composeRule.runOnIdle { state.value = state.value.copy(pendingToolSettingsConversationIds = setOf("chat")) }
        action.assertIsNotEnabled()
        composeRule.runOnIdle { state.value = state.value.copy(pendingToolSettingsConversationIds = setOf("other")) }
        action.assertIsEnabled()
    }
}
