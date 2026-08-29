package xyz.mek030399.tokenflow.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ClipboardManager as ComposeClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import xyz.mek030399.tokenflow.data.ChatDataSource
import xyz.mek030399.tokenflow.data.ChatDisplayPreferences
import xyz.mek030399.tokenflow.data.ChatEvent
import xyz.mek030399.tokenflow.data.ChatMessage
import xyz.mek030399.tokenflow.data.CloudServerProfile
import xyz.mek030399.tokenflow.data.CloudArtifactDelivery
import xyz.mek030399.tokenflow.data.CloudArtifactDeliveryStatus
import xyz.mek030399.tokenflow.data.CloudArtifactSourceType
import xyz.mek030399.tokenflow.data.CloudTask
import xyz.mek030399.tokenflow.data.CloudTaskStatus
import xyz.mek030399.tokenflow.data.AssistantIdentitySnapshot
import xyz.mek030399.tokenflow.data.AssistantMetadata
import xyz.mek030399.tokenflow.data.CONTEXT_BOUNDARY_ROLE
import xyz.mek030399.tokenflow.data.BookmarkedMessage
import xyz.mek030399.tokenflow.data.ConfigArchivePayload
import xyz.mek030399.tokenflow.data.Conversation
import xyz.mek030399.tokenflow.data.ConversationDetail
import xyz.mek030399.tokenflow.data.ConversationWriteRequest
import xyz.mek030399.tokenflow.data.DEFAULT_ASSISTANT_NICKNAME
import xyz.mek030399.tokenflow.data.ImportPreview
import xyz.mek030399.tokenflow.data.KnowledgeCitation
import xyz.mek030399.tokenflow.data.KnowledgeDocument
import xyz.mek030399.tokenflow.data.KnowledgeDocumentPreview
import xyz.mek030399.tokenflow.data.KnowledgeSnippet
import xyz.mek030399.tokenflow.data.GlobalChatSettings
import xyz.mek030399.tokenflow.data.ModelProfile
import xyz.mek030399.tokenflow.data.ImportedMarkdownNote
import xyz.mek030399.tokenflow.data.Note
import xyz.mek030399.tokenflow.data.PendingAttachment
import xyz.mek030399.tokenflow.data.ProcessEvent
import xyz.mek030399.tokenflow.data.ProviderConfig
import xyz.mek030399.tokenflow.data.ProviderDraft
import xyz.mek030399.tokenflow.data.ProviderEditorData
import xyz.mek030399.tokenflow.data.ProviderProtocol
import xyz.mek030399.tokenflow.data.RemoteModel
import xyz.mek030399.tokenflow.data.SerializableUsage
import xyz.mek030399.tokenflow.data.SendMessageRequest
import xyz.mek030399.tokenflow.data.SettingMode
import xyz.mek030399.tokenflow.data.TtsAudio
import xyz.mek030399.tokenflow.data.Usage
import xyz.mek030399.tokenflow.data.WorkspaceSnapshot
import xyz.mek030399.tokenflow.data.DirectApiTransport
import xyz.mek030399.tokenflow.ui.theme.TokenFlowTheme
import xyz.mek030399.tokenflow.ui.theme.AppTheme
import xyz.mek030399.tokenflow.ui.theme.LocalTokenFlowDarkTheme
import xyz.mek030399.tokenflow.ui.theme.colorSchemeFor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class TokenFlowAppTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun infiniteCloudServerCardExpandsWithoutRiskNotice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = CloudServerProfile(
            id = "cloud-1",
            name = "Build server",
            host = "build.example.com",
            port = 2222,
            username = "runner",
            startDirectory = "/srv/build",
            hostKeyFingerprint = "SHA256:abcdefghijklmnopqrstuvwxyz",
            keyConfigured = true,
        )
        val viewModel = AppViewModel(UiFakeDataSource(withModel = true))
        composeRule.setContent {
            TokenFlowTheme {
                InfiniteCloudScreen(
                    state = AppUiState(cloudServers = listOf(server)),
                    viewModel = viewModel,
                    showBack = true,
                )
            }
        }

        composeRule.onNodeWithText("Infinite Cloud").assertIsDisplayed()
        composeRule.onNodeWithText("Build server").assertIsDisplayed()
        composeRule.onNodeWithText("runner@build.example.com:2222").assertIsDisplayed()
        composeRule.onAllNodesWithText("High risk: when enabled, the model can perform any operation allowed by the SSH account. Attachments are uploaded automatically.").assertCountEquals(0)
        composeRule.onAllNodesWithText("高风险：启用后，模型可执行 SSH 账号允许的任意操作，附件也会自动上传。").assertCountEquals(0)

        composeRule.onNodeWithContentDescription(context.getString(xyz.mek030399.tokenflow.R.string.cloud_server_details)).performClick()
        composeRule.onNodeWithText("/srv/build").assertIsDisplayed()
        composeRule.onNodeWithText("SHA256:abcdefghijklmnopqrstuvwxyz").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(xyz.mek030399.tokenflow.R.string.cloud_copy_fingerprint)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(xyz.mek030399.tokenflow.R.string.cloud_test_connection)).assertIsDisplayed()
    }

    @Test
    fun infiniteCloudTasksSupportMultiSelectAndDeleteConfirmation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val completed = CloudTask(
            id = "completed",
            serverName = "Build server",
            kind = "shell",
            summary = "Completed export",
            status = CloudTaskStatus.SUCCEEDED,
        )
        val running = CloudTask(
            id = "running",
            serverName = "Build server",
            kind = "python",
            summary = "Running worker",
            status = CloudTaskStatus.RUNNING,
        )
        val configuredServer = CloudServerProfile(
            id = "configured-server",
            name = "Configured server",
            host = "configured.example.com",
            username = "runner",
            hostKeyFingerprint = "SHA256:configured",
            keyConfigured = true,
        )
        val failedArtifact = CloudArtifactDelivery(
            id = "failed-artifact",
            messageId = "assistant-message",
            taskId = completed.id,
            sourceType = CloudArtifactSourceType.REMOTE,
            sourceIdentity = "configured-server:/srv/result.zip",
            displayName = "result.zip",
            status = CloudArtifactDeliveryStatus.FAILED,
            error = "Remote artifact is temporarily unavailable",
        )
        val viewModel = AppViewModel(UiFakeDataSource(withModel = true))
        composeRule.setContent {
            TokenFlowTheme {
                InfiniteCloudScreen(
                    state = AppUiState(
                        cloudTasks = listOf(completed, running),
                        cloudArtifactDeliveries = listOf(failedArtifact),
                        cloudServers = listOf(configuredServer),
                        cloud = CloudWorkspaceUiState(section = CloudSection.TASKS),
                    ),
                    viewModel = viewModel,
                    showBack = true,
                )
            }
        }

        composeRule.onNodeWithText(context.getString(xyz.mek030399.tokenflow.R.string.cloud_all_servers)).assertIsDisplayed()
        composeRule.onNodeWithText("Completed export").assertIsDisplayed()
        composeRule.onNodeWithText("result.zip").assertIsDisplayed()
        composeRule.onNodeWithText("Remote artifact is temporarily unavailable").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(xyz.mek030399.tokenflow.R.string.retry)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(xyz.mek030399.tokenflow.R.string.cloud_select_tasks)).performClick()
        composeRule.onNodeWithText(context.getString(xyz.mek030399.tokenflow.R.string.selected_items, 0)).assertIsDisplayed()
        composeRule.onNodeWithTag("cloud_task_completed").performClick()
        composeRule.onNodeWithText(context.getString(xyz.mek030399.tokenflow.R.string.selected_items, 1)).assertIsDisplayed()
        composeRule.onNodeWithTag("cloud_task_running").performClick()
        composeRule.onNodeWithText(context.getString(xyz.mek030399.tokenflow.R.string.selected_items, 1)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(xyz.mek030399.tokenflow.R.string.delete)).performClick()
        composeRule.onNodeWithText(context.getString(xyz.mek030399.tokenflow.R.string.cloud_delete_tasks_title)).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.resources.getQuantityString(xyz.mek030399.tokenflow.R.plurals.cloud_delete_tasks_detail, 1, 1),
        ).assertIsDisplayed()
    }

    @Test
    fun firstLaunchShowsProviderSetupWithoutLogin() {
        val fake = UiFakeDataSource(withModel = false)
        val viewModel = AppViewModel(fake)
        composeRule.setContent { TokenFlowTheme { TokenFlowApp(viewModel) } }

        composeRule.waitUntil(5_000) { fake.initialized }
        composeRule.onNodeWithTag(UiTestTags.PROVIDER_GUIDE).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTestTags.INITIAL_IMPORT).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTestTags.ADD_PROVIDER).performClick()
        composeRule.onNodeWithTag(UiTestTags.PROVIDER_NAME).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTestTags.PROVIDER_BASE_URL).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTestTags.PROVIDER_API_KEY).assertIsDisplayed()
    }

    @Test
    fun initialImportIsAvailableWithExistingProviderWithoutModels() {
        val providerOnly = UiFakeDataSource(withModel = false, withProvider = true)
        val providerOnlyViewModel = AppViewModel(providerOnly)
        composeRule.setContent { TokenFlowTheme { TokenFlowApp(providerOnlyViewModel) } }

        composeRule.waitUntil(5_000) { providerOnly.initialized }
        composeRule.onNodeWithText(providerOnly.provider.name).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTestTags.INITIAL_IMPORT).assertIsDisplayed()
    }

    @Test
    fun initialImportIsHiddenAfterModelSetup() {
        val configured = UiFakeDataSource(withModel = true)
        val configuredViewModel = AppViewModel(configured)
        composeRule.setContent { TokenFlowTheme { TokenFlowApp(configuredViewModel) } }
        composeRule.waitUntil(5_000) { configured.initialized }
        composeRule.onAllNodesWithTag(UiTestTags.INITIAL_IMPORT).assertCountEquals(0)
    }

    @Test
    fun notificationUsesSingleLineAdaptiveWidthAndCanBeDismissed() {
        val fake = UiFakeDataSource(withModel = true)
        val viewModel = AppViewModel(fake)
        lateinit var density: Density
        setAppAt(
            viewModel = viewModel,
            size = PHONE_SIZE,
            notificationAutoDismissMillis = 10_000L,
        ) { currentDensity, _ -> density = currentDensity }
        composeRule.waitUntil(5_000) { fake.initialized }

        composeRule.runOnIdle { viewModel.saveSettings(viewModel.state.value.config) }
        composeRule.waitUntil(5_000) {
            runCatching {
                composeRule.onAllNodesWithTag(UiTestTags.TOP_NOTIFICATION)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }

        val notification = composeRule.onNodeWithTag(UiTestTags.TOP_NOTIFICATION).assertIsDisplayed()
        val notificationBounds = notification.fetchSemanticsNode().boundsInRoot
        val topBarBounds = composeRule.onNodeWithTag(UiTestTags.CHAT_TOP_BAR)
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals(
            with(density) { 8.dp.toPx() },
            notificationBounds.top - topBarBounds.bottom,
            1f,
        )
        assertTrue(notificationBounds.width >= with(density) { 196.dp.toPx() } - 1f)
        assertTrue(notificationBounds.width < rootBounds.width - with(density) { 24.dp.toPx() })
        assertEquals(rootBounds.center.x, notificationBounds.center.x, 1f)
        assertTrue(notificationBounds.center.y < rootBounds.center.y)
        val messageBounds = composeRule.onNodeWithTag(UiTestTags.TOP_NOTIFICATION_MESSAGE)
            .fetchSemanticsNode()
            .boundsInRoot
        assertEquals(notificationBounds.center.x, messageBounds.center.x, 1f)
        assertEquals(
            LiveRegionMode.Polite,
            notification.fetchSemanticsNode().config[SemanticsProperties.LiveRegion],
        )
        val shortMessageLayouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithTag(UiTestTags.TOP_NOTIFICATION_MESSAGE)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                action(shortMessageLayouts)
        }
        assertEquals(1, shortMessageLayouts.single().layoutInput.maxLines)
        assertEquals(TextAlign.Center, shortMessageLayouts.single().layoutInput.style.textAlign)
        assertEquals(1, shortMessageLayouts.single().lineCount)

        composeRule.onNodeWithTag(UiTestTags.DISMISS_NOTIFICATION)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.waitUntil(5_000) {
            runCatching {
                composeRule.onAllNodesWithTag(UiTestTags.TOP_NOTIFICATION)
                    .fetchSemanticsNodes()
                    .isEmpty()
            }.getOrDefault(false)
        }
        composeRule.waitUntil(5_000) { viewModel.state.value.notice == null }

        composeRule.runOnIdle { viewModel.reportCameraCaptureFailure() }
        composeRule.waitUntil(5_000) {
            runCatching {
                composeRule.onAllNodesWithTag(UiTestTags.TOP_NOTIFICATION)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        val expandedNotification = composeRule.onNodeWithTag(UiTestTags.TOP_NOTIFICATION)
            .assertIsDisplayed()
        assertTrue(expandedNotification.fetchSemanticsNode().boundsInRoot.width > notificationBounds.width)
        val longMessageLayouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithTag(UiTestTags.TOP_NOTIFICATION_MESSAGE)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                action(longMessageLayouts)
            }
        assertEquals(1, longMessageLayouts.single().layoutInput.maxLines)
        assertEquals(1, longMessageLayouts.single().lineCount)
        composeRule.onNodeWithTag(UiTestTags.DISMISS_NOTIFICATION).performClick()
        composeRule.waitUntil(5_000) {
            runCatching {
                composeRule.onAllNodesWithTag(UiTestTags.TOP_NOTIFICATION)
                    .fetchSemanticsNodes()
                    .isEmpty()
            }.getOrDefault(false)
        }
        composeRule.waitUntil(5_000) { viewModel.state.value.notice == null }
    }

    @Test
    fun notificationAutomaticallyDismissesAfterHalfSecond() {
        val fake = UiFakeDataSource(withModel = true)
        val viewModel = AppViewModel(fake)
        composeRule.setContent { TokenFlowTheme { TokenFlowApp(viewModel) } }
        composeRule.waitUntil(5_000) { fake.initialized }

        composeRule.runOnIdle {
            viewModel.saveSettings(viewModel.state.value.config)
            assertNotNull(viewModel.state.value.notice)
        }
        composeRule.waitUntil(2_000) { viewModel.state.value.notice == null }
        composeRule.waitUntil(2_000) {
            composeRule.onAllNodesWithTag(UiTestTags.TOP_NOTIFICATION)
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    @Test
    fun codeBlockCopyUsesTopNotificationInsteadOfInlineStatus() {
        val conversation = Conversation(id = "conversation-code-copy", title = "Code copy", model = "model-1")
        val assistantMessage = ChatMessage(
            id = "assistant-code-copy",
            conversationId = conversation.id,
            role = "assistant",
            content = "```json\n{\"status\": \"ok\"}\n```",
        )
        val fake = UiFakeDataSource(withModel = true).apply {
            conversations += conversation
            seedMessages(conversation.id, listOf(assistantMessage))
        }
        val viewModel = AppViewModel(fake)
        val copied = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(xyz.mek030399.tokenflow.R.string.copied)

        setAppAt(viewModel, PHONE_SIZE)
        composeRule.waitUntil(5_000) { fake.initialized }
        composeRule.runOnIdle { viewModel.openConversation(conversation.id) }
        composeRule.waitUntil(5_000) { viewModel.state.value.activeMessages.size == 1 }

        composeRule.onNodeWithTag(UiTestTags.COPY_CODE_BLOCK).performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(UiTestTags.TOP_NOTIFICATION).fetchSemanticsNodes().isNotEmpty()
        }

        val notification = composeRule.onNodeWithTag(UiTestTags.TOP_NOTIFICATION).assertIsDisplayed()
        val copiedText = composeRule.onNodeWithText(copied).assertIsDisplayed()
        composeRule.onAllNodesWithText(copied).assertCountEquals(1)
        assertNodeWithin(copiedText, notification)
    }

    @Test
    fun globalSettingsShowsDefaultAssistantNicknameAndNormalizesSavedInput() {
        val fake = UiFakeDataSource(withModel = true)
        val viewModel = AppViewModel(fake)
        composeRule.setContent { TokenFlowTheme { TokenFlowApp(viewModel) } }
        composeRule.waitUntil(5_000) { fake.initialized }
        composeRule.runOnIdle { viewModel.openScreen(AppScreen.GLOBAL_SETTINGS) }
        composeRule.waitUntil(5_000) { viewModel.state.value.screen == AppScreen.GLOBAL_SETTINGS }

        val nickname = composeRule.onNodeWithTag(UiTestTags.ASSISTANT_NICKNAME)
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextEquals(DEFAULT_ASSISTANT_NICKNAME)
        nickname.performTextClearance()
        nickname.performTextInput("  Flow Guide  ")
        composeRule.onNodeWithText(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(
                xyz.mek030399.tokenflow.R.string.save,
            ),
        ).performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            viewModel.state.value.globalSettings.assistantNickname == "Flow Guide"
        }
        composeRule.onNodeWithTag(UiTestTags.ASSISTANT_NICKNAME)
            .performScrollTo()
            .assertTextEquals("Flow Guide")

        composeRule.onNodeWithTag(UiTestTags.ASSISTANT_NICKNAME).performTextClearance()
        composeRule.onNodeWithTag(UiTestTags.ASSISTANT_NICKNAME).performTextInput("   ")
        composeRule.onNodeWithText(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(
                xyz.mek030399.tokenflow.R.string.save,
            ),
        ).performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            viewModel.state.value.globalSettings.assistantNickname == DEFAULT_ASSISTANT_NICKNAME
        }
        composeRule.onNodeWithTag(UiTestTags.ASSISTANT_NICKNAME)
            .performScrollTo()
            .assertTextEquals(DEFAULT_ASSISTANT_NICKNAME)
    }

    @Test
    fun emptyConversationUsesBilingualCopyAndCenteredPhoneLayout() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chineseConfiguration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.SIMPLIFIED_CHINESE)
        }
        val chineseContext = context.createConfigurationContext(chineseConfiguration)
        assertEquals(
            "一念即无限",
            chineseContext.getString(xyz.mek030399.tokenflow.R.string.empty_title),
        )
        assertEquals(
            "提问、创作、推演，从这一念开始",
            chineseContext.getString(xyz.mek030399.tokenflow.R.string.empty_detail),
        )
        val englishConfiguration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        }
        val englishContext = context.createConfigurationContext(englishConfiguration)
        assertEquals(
            "One thought, infinite possibilities",
            englishContext.getString(xyz.mek030399.tokenflow.R.string.empty_title),
        )
        assertEquals(
            "Ask, create, and reason. Start with one thought.",
            englishContext.getString(xyz.mek030399.tokenflow.R.string.empty_detail),
        )
        val preferences = ChatDisplayPreferences(context)
        val originalTheme = preferences.readTheme()
        preferences.writeTheme(AppTheme.DAWN_WHITE)

        try {
            val fake = UiFakeDataSource(withModel = true)
            val viewModel = AppViewModel(fake)
            lateinit var density: Density
            setLocalizedAppAt(
                viewModel = viewModel,
                size = PHONE_SIZE,
                fontScale = 1f,
                locale = Locale.SIMPLIFIED_CHINESE,
                nightMode = Configuration.UI_MODE_NIGHT_NO,
            ) { currentDensity, _ -> density = currentDensity }
            composeRule.waitUntil(5_000) { fake.initialized }

            val emptyState = composeRule.onNodeWithTag(UiTestTags.EMPTY_STATE).assertIsDisplayed()
            val logo = composeRule.onNodeWithTag(UiTestTags.EMPTY_LOGO).assertIsDisplayed()
            val title = composeRule.onNodeWithTag(UiTestTags.EMPTY_TITLE)
                .assertIsDisplayed()
                .assertTextEquals("一念即无限")
            val detail = composeRule.onNodeWithTag(UiTestTags.EMPTY_DETAIL)
                .assertIsDisplayed()
                .assertTextEquals("提问、创作、推演，从这一念开始")
            val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
            val stateBounds = emptyState.fetchSemanticsNode().boundsInRoot
            val logoBounds = logo.fetchSemanticsNode().boundsInRoot
            val titleBounds = title.fetchSemanticsNode().boundsInRoot
            val detailBounds = detail.fetchSemanticsNode().boundsInRoot
            val horizontalSafety = with(density) { 24.dp.toPx() }
            val widthLimit = with(density) { 320.dp.toPx() }
            val expectedWidth = minOf(rootBounds.width - horizontalSafety * 2, widthLimit)
            val titleLayouts = mutableListOf<TextLayoutResult>()
            val detailLayouts = mutableListOf<TextLayoutResult>()
            title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(titleLayouts) }
            detail.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(detailLayouts) }
            val titleLayout = titleLayouts.single()
            val detailLayout = detailLayouts.single()
            val lightColors = colorSchemeFor(AppTheme.DAWN_WHITE, dark = false)

            assertEquals(expectedWidth, stateBounds.width, 1f)
            assertEquals(rootBounds.center.x, stateBounds.center.x, 1f)
            assertTrue(stateBounds.left >= rootBounds.left + horizontalSafety - 1f)
            assertTrue(stateBounds.right <= rootBounds.right - horizontalSafety + 1f)
            assertNodeWithin(logo, emptyState)
            assertNodeWithin(title, emptyState)
            assertNodeWithin(detail, emptyState)
            assertNodesDoNotOverlap(logo, title)
            assertNodesDoNotOverlap(title, detail)
            assertEquals(with(density) { 56.dp.toPx() }, logoBounds.width, 1f)
            assertEquals(with(density) { 56.dp.toPx() }, logoBounds.height, 1f)
            assertEquals(with(density) { 18.dp.toPx() }, titleBounds.top - logoBounds.bottom, 1f)
            assertEquals(with(density) { 6.dp.toPx() }, detailBounds.top - titleBounds.bottom, 1f)
            assertEquals(stateBounds.center.x, logoBounds.center.x, 1f)
            assertEquals(stateBounds.center.x, titleBounds.center.x, 1f)
            assertEquals(stateBounds.center.x, detailBounds.center.x, 1f)
            assertEquals(1, titleLayout.lineCount)
            assertEquals(1, detailLayout.lineCount)
            assertEquals(FontWeight.Medium, titleLayout.layoutInput.style.fontWeight)
            assertEquals(TextAlign.Center, titleLayout.layoutInput.style.textAlign)
            assertEquals(TextAlign.Center, detailLayout.layoutInput.style.textAlign)
            assertEquals(lightColors.onSurface, titleLayout.layoutInput.style.color)
            assertEquals(lightColors.onSurfaceVariant, detailLayout.layoutInput.style.color)
        } finally {
            preferences.writeTheme(originalTheme)
        }
    }

    @Test
    fun emptyConversationWrapsWithoutClippingAtNarrowWidthAndLargeFont() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = ChatDisplayPreferences(context)
        val originalTheme = preferences.readTheme()
        preferences.writeTheme(AppTheme.DAWN_WHITE)
        val narrowSize = DpSize(320.dp, 640.dp)

        try {
            val fake = UiFakeDataSource(withModel = true)
            val viewModel = AppViewModel(fake)
            lateinit var density: Density
            setLocalizedAppAt(
                viewModel = viewModel,
                size = narrowSize,
                fontScale = 1.5f,
                locale = Locale.ENGLISH,
                nightMode = Configuration.UI_MODE_NIGHT_NO,
            ) { currentDensity, _ -> density = currentDensity }
            composeRule.waitUntil(5_000) { fake.initialized }

            val emptyState = composeRule.onNodeWithTag(UiTestTags.EMPTY_STATE).assertIsDisplayed()
            val title = composeRule.onNodeWithTag(UiTestTags.EMPTY_TITLE)
                .assertIsDisplayed()
                .assertTextEquals("One thought, infinite possibilities")
            val detail = composeRule.onNodeWithTag(UiTestTags.EMPTY_DETAIL)
                .assertIsDisplayed()
                .assertTextEquals("Ask, create, and reason. Start with one thought.")
            val input = composeRule.onNodeWithTag(UiTestTags.MESSAGE_INPUT).assertIsDisplayed()
            val topBar = composeRule.onNodeWithTag(UiTestTags.CHAT_TOP_BAR).assertIsDisplayed()
            val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
            val stateBounds = emptyState.fetchSemanticsNode().boundsInRoot
            val horizontalSafety = with(density) { 24.dp.toPx() }
            val titleLayouts = mutableListOf<TextLayoutResult>()
            val detailLayouts = mutableListOf<TextLayoutResult>()
            title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(titleLayouts) }
            detail.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(detailLayouts) }
            val layouts = titleLayouts + detailLayouts

            assertEquals(rootBounds.width - horizontalSafety * 2, stateBounds.width, 1f)
            assertEquals(rootBounds.center.x, stateBounds.center.x, 1f)
            assertNodeWithin(title, emptyState)
            assertNodeWithin(detail, emptyState)
            assertNodesDoNotOverlap(title, detail)
            assertTrue(layouts.any { it.lineCount > 1 })
            assertTrue(layouts.all { it.layoutInput.softWrap })
            assertTrue(layouts.all { layout -> (0 until layout.lineCount).none(layout::isLineEllipsized) })
            assertTrue(stateBounds.top >= topBar.fetchSemanticsNode().boundsInRoot.bottom - 1f)
            assertTrue(stateBounds.bottom <= input.fetchSemanticsNode().boundsInRoot.top + 1f)
        } finally {
            preferences.writeTheme(originalTheme)
        }
    }

    @Test
    fun emptyConversationCapsContentWidthAndUsesDarkThemeColors() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = ChatDisplayPreferences(context)
        val originalTheme = preferences.readTheme()
        preferences.writeTheme(AppTheme.AMOLED_BLACK)

        try {
            val fake = UiFakeDataSource(withModel = true)
            val viewModel = AppViewModel(fake)
            lateinit var density: Density
            val widePhoneSize = DpSize(480.dp, 640.dp)
            setAppAt(viewModel, widePhoneSize) { currentDensity, _ -> density = currentDensity }
            composeRule.waitUntil(5_000) { fake.initialized }

            val emptyState = composeRule.onNodeWithTag(UiTestTags.EMPTY_STATE).assertIsDisplayed()
            val title = composeRule.onNodeWithTag(UiTestTags.EMPTY_TITLE).assertIsDisplayed()
            val detail = composeRule.onNodeWithTag(UiTestTags.EMPTY_DETAIL).assertIsDisplayed()
            val titleLayouts = mutableListOf<TextLayoutResult>()
            val detailLayouts = mutableListOf<TextLayoutResult>()
            title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(titleLayouts) }
            detail.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(detailLayouts) }
            val darkColors = colorSchemeFor(AppTheme.AMOLED_BLACK, dark = true)

            assertEquals(with(density) { 320.dp.toPx() }, emptyState.fetchSemanticsNode().boundsInRoot.width, 1f)
            assertEquals(darkColors.onSurface, titleLayouts.single().layoutInput.style.color)
            assertEquals(darkColors.onSurfaceVariant, detailLayouts.single().layoutInput.style.color)
            assertNodeWithin(title, emptyState)
            assertNodeWithin(detail, emptyState)
        } finally {
            preferences.writeTheme(originalTheme)
        }
    }

    @Test
    fun initialImportShowsBusyErrorsAndProtectedPreview() {
        val fake = UiFakeDataSource(withModel = false)
        val previewGate = CompletableDeferred<Unit>()
        fake.importPreviewGate = previewGate
        fake.importPreviewError = IllegalArgumentException("Wrong archive password")
        val viewModel = AppViewModel(fake)
        composeRule.setContent { TokenFlowTheme { TokenFlowApp(viewModel) } }
        composeRule.waitUntil(5_000) { fake.initialized }

        composeRule.runOnIdle { viewModel.previewImport("archive", "long-password".toCharArray()) }
        composeRule.waitUntil(5_000) { viewModel.state.value.transfer.busy }
        composeRule.onNodeWithTag(UiTestTags.INITIAL_IMPORT).assertIsNotEnabled()
        composeRule.onNodeWithTag(UiTestTags.INITIAL_IMPORT_PROGRESS).assertIsDisplayed()

        previewGate.complete(Unit)
        composeRule.waitUntil(5_000) { viewModel.state.value.transfer.error != null }
        composeRule.onNodeWithTag(UiTestTags.INITIAL_IMPORT_ERROR).assertIsDisplayed()
        composeRule.onNodeWithText("Wrong archive password").assertIsDisplayed()

        fake.importPreviewGate = null
        fake.importPreviewError = null
        composeRule.runOnIdle {
            viewModel.prepareImport()
            viewModel.previewImport("archive", "long-password".toCharArray())
        }
        composeRule.waitUntil(5_000) { viewModel.state.value.transfer.importPreview != null }
        composeRule.onNodeWithTag(UiTestTags.IMPORT_PREVIEW).assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(
            context.getString(xyz.mek030399.tokenflow.R.string.import_summary, 1, 0, 1, 0),
        ).assertIsDisplayed()

        val applyGate = CompletableDeferred<Unit>()
        fake.applyImportGate = applyGate
        fake.applyImportError = IllegalArgumentException("Import could not be applied")
        composeRule.onNodeWithTag(UiTestTags.IMPORT_CONFIRM).performClick()
        composeRule.waitUntil(5_000) { viewModel.state.value.transfer.busy }
        composeRule.onNodeWithTag(UiTestTags.IMPORT_CONFIRM).assertIsNotEnabled()
        composeRule.onAllNodesWithTag(UiTestTags.IMPORT_CANCEL).assertCountEquals(0)
        composeRule.onNodeWithTag(UiTestTags.IMPORT_PREVIEW_PROGRESS).assertIsDisplayed()
        pressBack()
        composeRule.onNodeWithTag(UiTestTags.IMPORT_PREVIEW).assertIsDisplayed()

        applyGate.complete(Unit)
        composeRule.waitUntil(5_000) {
            !viewModel.state.value.transfer.busy && viewModel.state.value.transfer.error != null
        }
        composeRule.onNodeWithTag(UiTestTags.IMPORT_PREVIEW_ERROR).assertIsDisplayed()
    }

    @Test
    fun configuredAppSendsAndCopiesProviderResponse() {
        val fake = UiFakeDataSource(withModel = true)
        val viewModel = AppViewModel(fake)
        composeRule.setContent { TokenFlowTheme { TokenFlowApp(viewModel) } }
        composeRule.waitUntil(5_000) { fake.initialized }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(UiTestTags.MESSAGE_INPUT).performTextInput("Hello locally")
        val inputBottom = composeRule.onNodeWithTag(UiTestTags.MESSAGE_INPUT).fetchSemanticsNode().boundsInRoot.bottom
        val rootBottom = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(inputBottom > rootBottom * 0.5f)
        composeRule.onNodeWithTag(UiTestTags.MESSAGE_ACTION).performClick()
        composeRule.waitUntil(5_000) { fake.sentRequest != null }
        composeRule.waitUntil(5_000) {
            viewModel.state.value.activeMessages.any { it.content == "Answer from provider" }
        }

        assertEquals("Hello locally", fake.sentRequest?.content)
        assertNotNull(fake.sentRequest?.requestId)
        composeRule.onNodeWithText("Answer from provider").assertIsDisplayed()
        composeRule.onNodeWithTag(UiTestTags.TOKEN_USAGE).assertIsDisplayed()
        composeRule.onNodeWithText("1.2K↑600↓600").assertIsDisplayed()
        val summaryBounds = composeRule.onNodeWithTag(UiTestTags.PROCESS_TOKEN_ROW).fetchSemanticsNode().boundsInRoot
        val processBounds = composeRule.onNodeWithTag(UiTestTags.PROCESS_DETAILS).fetchSemanticsNode().boundsInRoot
        val tokenBounds = composeRule.onNodeWithTag(UiTestTags.TOKEN_USAGE).fetchSemanticsNode().boundsInRoot
        val assistantMessageId = viewModel.state.value.activeMessages.single { it.role == "assistant" }.id
        val assistantHeader = composeRule.onNodeWithTag(UiTestTags.assistantMessageHeader(assistantMessageId))
            .assertIsDisplayed()
        val assistantBody = composeRule.onNodeWithTag(UiTestTags.messageBody(assistantMessageId))
            .assertIsDisplayed()
        val footerActions = composeRule.onNodeWithTag(
            UiTestTags.assistantMessageFooterActions(assistantMessageId),
        ).assertIsDisplayed()
        assertEquals(summaryBounds.left, processBounds.left, 0.5f)
        assertEquals(summaryBounds.right, footerActions.fetchSemanticsNode().boundsInRoot.right, 0.5f)
        assertTrue(processBounds.left < tokenBounds.left)
        assertTrue(processBounds.top < tokenBounds.bottom && tokenBounds.top < processBounds.bottom)
        assertNodeWithin(footerActions, composeRule.onNodeWithTag(UiTestTags.PROCESS_TOKEN_ROW))
        assertNodesDoNotOverlap(composeRule.onNodeWithTag(UiTestTags.PROCESS_DETAILS), footerActions)
        assertNodesDoNotOverlap(composeRule.onNodeWithTag(UiTestTags.TOKEN_USAGE), footerActions)
        assertNodeWithin(assistantHeader, assistantBody)
        assertNodeWithin(composeRule.onNodeWithTag(UiTestTags.COPY_ASSISTANT_MESSAGE), assistantHeader)
        assertTrue(
            composeRule.onNodeWithTag(UiTestTags.COPY_ASSISTANT_MESSAGE)
                .fetchSemanticsNode().boundsInRoot.bottom <= summaryBounds.top,
        )
        composeRule.onNodeWithTag(UiTestTags.SPEECH_ACTION).assertIsDisplayed()
        val userAvatar = composeRule.onNodeWithTag(UiTestTags.USER_MESSAGE_AVATAR).fetchSemanticsNode().boundsInRoot
        assertEquals(userAvatar.width, userAvatar.height, 0.5f)
        composeRule.onNodeWithTag(UiTestTags.COPY_ASSISTANT_MESSAGE).performClick()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        assertEquals("Answer from provider", clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString())
    }

    @Test
    fun assistantBodyStartsWithIdentityHeaderWhileUserLayoutStaysCompact() {
        val assistantNickname = "Shared assistant"
        val conversation = Conversation(id = "conversation-message-layout", title = "Message layout", model = "model-1")
        val userMessage = ChatMessage(
            id = "user-message-layout",
            conversationId = conversation.id,
            role = "user",
            content = "Short question",
        )
        val assistantMessage = ChatMessage(
            id = "assistant-message-layout",
            conversationId = conversation.id,
            role = "assistant",
            content = """
                Budget choices

                | Need | Model | Reference |
                | --- | --- | --- |
                | Value | Phone | Note |
            """.trimIndent(),
        )
        val fake = UiFakeDataSource(withModel = true, assistantNickname = assistantNickname).apply {
            conversations += conversation
            seedMessages(conversation.id, listOf(userMessage, assistantMessage))
        }
        val viewModel = AppViewModel(fake)
        lateinit var density: Density

        setAppAt(viewModel, PHONE_SIZE) { currentDensity, _ -> density = currentDensity }
        composeRule.waitUntil(5_000) { fake.initialized }
        composeRule.runOnIdle { viewModel.openConversation(conversation.id) }
        composeRule.waitUntil(5_000) { viewModel.state.value.activeMessages.size == 2 }

        val assistantBody = composeRule.onNodeWithTag(UiTestTags.messageBody(assistantMessage.id))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.waitForIdle()
        val messageListBounds = composeRule.onNodeWithTag(UiTestTags.MESSAGE_LIST).fetchSemanticsNode().boundsInRoot
        val assistantBodyBounds = assistantBody.fetchSemanticsNode().boundsInRoot
        val assistantHeader = composeRule.onNodeWithTag(UiTestTags.assistantMessageHeader(assistantMessage.id))
            .assertIsDisplayed()
        val assistantHeaderBounds = assistantHeader.fetchSemanticsNode().boundsInRoot
        val assistantAvatar = composeRule.onNodeWithTag(UiTestTags.ASSISTANT_MESSAGE_AVATAR).assertIsDisplayed()
        val assistantAvatarBounds = assistantAvatar.fetchSemanticsNode().boundsInRoot
        val assistantIdentity = composeRule.onNodeWithTag(UiTestTags.assistantMessageIdentity(assistantMessage.id))
            .assertIsDisplayed()
        val assistantName = composeRule.onNodeWithTag(UiTestTags.assistantMessageName(assistantMessage.id))
            .assertIsDisplayed()
            .assertTextEquals(assistantNickname)
        val assistantModel = composeRule.onNodeWithTag(UiTestTags.assistantMessageModel(assistantMessage.id))
            .assertIsDisplayed()
            .assertTextEquals(fake.model.remoteId)
        val assistantActions = composeRule.onNodeWithTag(UiTestTags.assistantMessageActions(assistantMessage.id))
            .assertIsDisplayed()
        val assistantBookmark = composeRule.onNodeWithTag(UiTestTags.assistantMessageBookmark(assistantMessage.id))
            .assertIsDisplayed()
        val horizontalInset = with(density) { 16.dp.toPx() }
        val bodyPadding = with(density) { 12.dp.toPx() }
        val avatarSize = with(density) { 36.dp.toPx() }
        val avatarBodyGap = with(density) { 10.dp.toPx() }

        assertEquals(avatarSize, assistantAvatarBounds.width, 0.5f)
        assertEquals(avatarSize, assistantAvatarBounds.height, 0.5f)
        assertEquals(assistantBodyBounds.left + bodyPadding, assistantHeaderBounds.left, 1f)
        assertEquals(assistantBodyBounds.right - bodyPadding, assistantHeaderBounds.right, 1f)
        assertEquals(assistantBodyBounds.left + bodyPadding, assistantAvatarBounds.left, 1f)
        assertNodeWithin(assistantHeader, assistantBody)
        assertNodeWithin(assistantAvatar, assistantHeader)
        assertNodeWithin(assistantIdentity, assistantHeader)
        assertNodeWithin(assistantName, assistantIdentity)
        assertNodeWithin(assistantModel, assistantIdentity)
        assertNodeWithin(assistantActions, assistantHeader)
        assertNodeWithin(composeRule.onNodeWithTag(UiTestTags.COPY_ASSISTANT_MESSAGE), assistantActions)
        assertNodeWithin(assistantBookmark, assistantActions)
        assertNodesDoNotOverlap(assistantAvatar, assistantIdentity)
        assertNodesDoNotOverlap(assistantIdentity, assistantActions)
        assertNodesDoNotOverlap(assistantName, assistantModel)
        assertEquals(messageListBounds.left + horizontalInset, assistantBodyBounds.left, 1f)
        assertEquals(messageListBounds.right - horizontalInset, assistantBodyBounds.right, 1f)
        assertEquals(messageListBounds.width - horizontalInset * 2, assistantBodyBounds.width, 1f)

        val paragraph = composeRule.onNodeWithText("Budget choices").assertIsDisplayed()
        val tableHeader = composeRule.onNodeWithText("Need").assertIsDisplayed()
        assertNodeWithin(paragraph, assistantBody)
        assertNodeWithin(tableHeader, assistantBody)
        assertEquals(assistantAvatarBounds.left, paragraph.fetchSemanticsNode().boundsInRoot.left, 0.5f)
        assertTrue(paragraph.fetchSemanticsNode().boundsInRoot.top >= assistantHeaderBounds.bottom)
        assertTrue(tableHeader.fetchSemanticsNode().boundsInRoot.top >= assistantHeaderBounds.bottom)
        assertNodeWithin(composeRule.onNodeWithTag(UiTestTags.PROCESS_TOKEN_ROW), assistantBody)
        composeRule.onNodeWithTag(UiTestTags.CHAT_CONVERSATION_TITLE)
            .assertTextEquals(conversation.title)
        composeRule.onNodeWithTag(UiTestTags.CHAT_MODEL_NAME)
            .assertTextEquals(fake.model.displayName)

        val userBody = composeRule.onNodeWithTag(UiTestTags.messageBody(userMessage.id))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.waitForIdle()
        val userBodyBounds = userBody.fetchSemanticsNode().boundsInRoot
        val userAvatarBounds = composeRule.onNodeWithTag(UiTestTags.USER_MESSAGE_AVATAR)
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertEquals(userBodyBounds.top, userAvatarBounds.top, 0.5f)
        assertEquals(avatarBodyGap, userAvatarBounds.left - userBodyBounds.right, 1f)
        assertEquals(messageListBounds.right - horizontalInset, userAvatarBounds.right, 1f)
        assertTrue(userBodyBounds.width < assistantBodyBounds.width)
    }

    @Test
    fun assistantIdentitySnapshotStaysFrozenAfterGlobalNicknameAndModelChange() {
        val conversation = Conversation(
            id = "conversation-frozen-assistant-identity",
            title = "Current conversation title",
            model = "model-1",
        )
        val assistantMessage = ChatMessage(
            id = "assistant-frozen-identity",
            conversationId = conversation.id,
            role = "assistant",
            content = "Historical response",
            metadata = DirectApiTransport.defaultJson.encodeToString(
                AssistantMetadata(
                    assistantIdentity = AssistantIdentitySnapshot(
                        modelId = "removed-model-id",
                        remoteModelId = "provider-model-at-generation-time",
                        nickname = "Original assistant",
                    ),
                ),
            ),
        )
        val fake = UiFakeDataSource(
            withModel = true,
            modelRemoteId = "provider-model-now",
            modelDisplayName = "Current model alias",
            assistantNickname = "Renamed assistant",
        ).apply {
            conversations += conversation
            seedMessages(conversation.id, listOf(assistantMessage))
        }
        val viewModel = AppViewModel(fake)

        setAppAt(viewModel, PHONE_SIZE)
        composeRule.waitUntil(5_000) { fake.initialized }
        composeRule.runOnIdle { viewModel.openConversation(conversation.id) }
        composeRule.waitUntil(5_000) { viewModel.state.value.activeMessages.size == 1 }

        composeRule.onNodeWithTag(UiTestTags.assistantMessageName(assistantMessage.id))
            .assertIsDisplayed()
            .assertTextEquals("Original assistant")
        composeRule.onNodeWithTag(UiTestTags.assistantMessageModel(assistantMessage.id))
            .assertIsDisplayed()
            .assertTextEquals("provider-model-at-generation-time")
        composeRule.onNodeWithTag(UiTestTags.CHAT_CONVERSATION_TITLE)
            .assertTextEquals(conversation.title)
        composeRule.onNodeWithTag(UiTestTags.CHAT_MODEL_NAME)
            .assertTextEquals(fake.model.displayName)
    }

    @Test
    fun legacyAssistantIdentityUsesDefaultNicknameAndHidesUnavailableModelId() {
        val conversation = Conversation(
            id = "conversation-missing-model-identity",
            title = "Missing model",
            model = "removed-model",
            modelMode = SettingMode.OVERRIDE,
        )
        val assistantMessage = ChatMessage(
            id = "assistant-missing-model-identity",
            conversationId = conversation.id,
            role = "assistant",
            content = "Legacy response",
        )
        val fake = UiFakeDataSource(withModel = true, assistantNickname = "   ").apply {
            conversations += conversation
            seedMessages(conversation.id, listOf(assistantMessage))
        }
        val viewModel = AppViewModel(fake)

        setAppAt(viewModel, PHONE_SIZE)
        composeRule.waitUntil(5_000) { fake.initialized }
        composeRule.runOnIdle { viewModel.openConversation(conversation.id) }
        composeRule.waitUntil(5_000) { viewModel.state.value.activeMessages.size == 1 }

        composeRule.onNodeWithTag(UiTestTags.assistantMessageName(assistantMessage.id))
            .assertIsDisplayed()
            .assertTextEquals(DEFAULT_ASSISTANT_NICKNAME)
        composeRule.onAllNodesWithTag(UiTestTags.assistantMessageModel(assistantMessage.id))
            .assertCountEquals(0)
    }

    @Test
    fun blankStreamingAssistantKeepsIdentityHeaderAndFullWidthBodyAtLargeFont() {
        val conversation = Conversation(id = "conversation-streaming-layout", title = "Streaming layout", model = "model-1")
        val assistantMessage = ChatMessage(
            id = "assistant-streaming-layout",
            conversationId = conversation.id,
            role = "assistant",
            status = "generating",
        )
        val fake = UiFakeDataSource(withModel = true).apply {
            conversations += conversation
            seedMessages(conversation.id, listOf(assistantMessage))
        }
        val viewModel = AppViewModel(fake)
        lateinit var density: Density

        setAppAt(viewModel, PHONE_SIZE, fontScale = 1.5f) { currentDensity, _ -> density = currentDensity }
        composeRule.waitUntil(5_000) { fake.initialized }
        composeRule.runOnIdle { viewModel.openConversation(conversation.id) }
        composeRule.waitUntil(5_000) { viewModel.state.value.activeMessages.size == 1 }

        val body = composeRule.onNodeWithTag(UiTestTags.messageBody(assistantMessage.id)).assertIsDisplayed()
        val bodyBounds = body.fetchSemanticsNode().boundsInRoot
        val header = composeRule.onNodeWithTag(UiTestTags.assistantMessageHeader(assistantMessage.id))
            .assertIsDisplayed()
        val headerBounds = header.fetchSemanticsNode().boundsInRoot
        val avatar = composeRule.onNodeWithTag(UiTestTags.ASSISTANT_MESSAGE_AVATAR).assertIsDisplayed()
        val identity = composeRule.onNodeWithTag(UiTestTags.assistantMessageIdentity(assistantMessage.id))
            .assertIsDisplayed()
        val name = composeRule.onNodeWithTag(UiTestTags.assistantMessageName(assistantMessage.id))
            .assertIsDisplayed()
            .assertTextEquals(DEFAULT_ASSISTANT_NICKNAME)
        val model = composeRule.onNodeWithTag(UiTestTags.assistantMessageModel(assistantMessage.id))
            .assertIsDisplayed()
            .assertTextEquals(fake.model.remoteId)
        val messageListBounds = composeRule.onNodeWithTag(UiTestTags.MESSAGE_LIST).fetchSemanticsNode().boundsInRoot
        val horizontalInset = with(density) { 16.dp.toPx() }
        val bodyPadding = with(density) { 12.dp.toPx() }
        val avatarBounds = avatar.fetchSemanticsNode().boundsInRoot
        val generationStatus = composeRule.onNodeWithTag(UiTestTags.GENERATION_STATUS).assertIsDisplayed()

        assertEquals(messageListBounds.left + horizontalInset, bodyBounds.left, 1f)
        assertEquals(messageListBounds.right - horizontalInset, bodyBounds.right, 1f)
        assertEquals(bodyBounds.left + bodyPadding, headerBounds.left, 1f)
        assertEquals(bodyBounds.right - bodyPadding, headerBounds.right, 1f)
        assertEquals(bodyBounds.left + bodyPadding, avatarBounds.left, 1f)
        assertNodeWithin(header, body)
        assertNodeWithin(avatar, header)
        assertNodeWithin(identity, header)
        assertNodeWithin(name, identity)
        assertNodeWithin(model, identity)
        assertNodesDoNotOverlap(avatar, identity)
        assertNodesDoNotOverlap(name, model)
        composeRule.onAllNodesWithTag(UiTestTags.assistantMessageActions(assistantMessage.id))
            .assertCountEquals(0)
        composeRule.onAllNodesWithTag(UiTestTags.COPY_ASSISTANT_MESSAGE).assertCountEquals(0)
        composeRule.onAllNodesWithTag(UiTestTags.assistantMessageBookmark(assistantMessage.id))
            .assertCountEquals(0)
        composeRule.onAllNodesWithTag(UiTestTags.PROCESS_TOKEN_ROW).assertCountEquals(0)
        composeRule.onAllNodesWithTag(
            UiTestTags.assistantMessageFooterActions(assistantMessage.id),
        ).assertCountEquals(0)
        assertNodeWithin(generationStatus, body)
        assertTrue(generationStatus.fetchSemanticsNode().boundsInRoot.top >= headerBounds.bottom)
        assertEquals(avatarBounds.left, generationStatus.fetchSemanticsNode().boundsInRoot.left, 0.5f)
    }

    @Test
    fun wideAssistantBodyKeepsReadingWidthWithHeaderInside() {
        val conversation = Conversation(id = "conversation-wide-message-layout", title = "Wide message layout", model = "model-1")
        val assistantMessage = ChatMessage(
            id = "assistant-wide-message-layout",
            conversationId = conversation.id,
            role = "assistant",
            content = "Wide response",
        )
        val fake = UiFakeDataSource(withModel = true).apply {
            conversations += conversation
            seedMessages(conversation.id, listOf(assistantMessage))
        }
        val viewModel = AppViewModel(fake)
        lateinit var density: Density

        setAppAt(viewModel, WIDE_CHAT_SIZE) { currentDensity, _ -> density = currentDensity }
        composeRule.waitUntil(5_000) { fake.initialized }
        composeRule.runOnIdle { viewModel.openConversation(conversation.id) }
        composeRule.waitUntil(5_000) { viewModel.state.value.activeMessages.size == 1 }

        val body = composeRule.onNodeWithTag(UiTestTags.messageBody(assistantMessage.id)).assertIsDisplayed()
        val bodyBounds = body.fetchSemanticsNode().boundsInRoot
        val header = composeRule.onNodeWithTag(UiTestTags.assistantMessageHeader(assistantMessage.id))
            .assertIsDisplayed()
        val messageListBounds = composeRule.onNodeWithTag(UiTestTags.MESSAGE_LIST).fetchSemanticsNode().boundsInRoot
        val horizontalInset = with(density) { 16.dp.toPx() }
        val readingWidthLimit = with(density) { 760.dp.toPx() }
        val expectedWidth = minOf(messageListBounds.width - horizontalInset * 2, readingWidthLimit)

        assertEquals(messageListBounds.left + horizontalInset, bodyBounds.left, 1f)
        assertEquals(expectedWidth, bodyBounds.width, 1f)
        assertTrue(bodyBounds.width <= readingWidthLimit + 1f)
        assertNodeWithin(header, body)
    }

    @Test
    fun longAssistantIdentityDoesNotOverlapHeaderActionsAtLargeFont() {
        val longTitle = "Assistant identity with an exceptionally long conversation title ".repeat(3).trim()
        val longNickname = "Assistant identity with an exceptionally long nickname ".repeat(3).trim()
        val longModelRemoteId = "provider-model-with-an-exceptionally-long-id-".repeat(4).trimEnd('-')
        val conversation = Conversation(
            id = "conversation-long-assistant-identity",
            title = longTitle,
            model = "model-1",
        )
        val assistantMessage = ChatMessage(
            id = "assistant-long-identity",
            conversationId = conversation.id,
            role = "assistant",
            content = "Compact response",
        )
        val fake = UiFakeDataSource(
            withModel = true,
            modelRemoteId = longModelRemoteId,
            assistantNickname = longNickname,
        ).apply {
            conversations += conversation
            seedMessages(conversation.id, listOf(assistantMessage))
        }
        val viewModel = AppViewModel(fake)
        lateinit var density: Density

        setAppAt(viewModel, PHONE_SIZE, fontScale = 1.5f) { currentDensity, _ -> density = currentDensity }
        composeRule.waitUntil(5_000) { fake.initialized }
        composeRule.runOnIdle { viewModel.openConversation(conversation.id) }
        composeRule.waitUntil(5_000) { viewModel.state.value.activeMessages.size == 1 }

        val header = composeRule.onNodeWithTag(UiTestTags.assistantMessageHeader(assistantMessage.id))
            .assertIsDisplayed()
        val body = composeRule.onNodeWithTag(UiTestTags.messageBody(assistantMessage.id)).assertIsDisplayed()
        val avatar = composeRule.onNodeWithTag(UiTestTags.ASSISTANT_MESSAGE_AVATAR).assertIsDisplayed()
        val identity = composeRule.onNodeWithTag(UiTestTags.assistantMessageIdentity(assistantMessage.id))
            .assertIsDisplayed()
        val name = composeRule.onNodeWithTag(UiTestTags.assistantMessageName(assistantMessage.id))
            .assertIsDisplayed()
            .assertTextEquals(longNickname)
        val model = composeRule.onNodeWithTag(UiTestTags.assistantMessageModel(assistantMessage.id))
            .assertIsDisplayed()
            .assertTextEquals(longModelRemoteId)
        val actions = composeRule.onNodeWithTag(UiTestTags.assistantMessageActions(assistantMessage.id))
            .assertIsDisplayed()
        val copy = composeRule.onNodeWithTag(UiTestTags.COPY_ASSISTANT_MESSAGE).assertIsDisplayed()
        val bookmark = composeRule.onNodeWithTag(UiTestTags.assistantMessageBookmark(assistantMessage.id))
            .assertIsDisplayed()
        val messageListBounds = composeRule.onNodeWithTag(UiTestTags.MESSAGE_LIST).fetchSemanticsNode().boundsInRoot
        val headerBounds = header.fetchSemanticsNode().boundsInRoot
        val bodyBounds = body.fetchSemanticsNode().boundsInRoot
        val avatarBounds = avatar.fetchSemanticsNode().boundsInRoot
        val horizontalInset = with(density) { 16.dp.toPx() }
        val bodyPadding = with(density) { 12.dp.toPx() }
        val response = composeRule.onNodeWithText("Compact response").assertIsDisplayed()

        assertEquals(messageListBounds.left + horizontalInset, bodyBounds.left, 1f)
        assertEquals(messageListBounds.right - horizontalInset, bodyBounds.right, 1f)
        assertEquals(bodyBounds.left + bodyPadding, headerBounds.left, 1f)
        assertEquals(bodyBounds.right - bodyPadding, headerBounds.right, 1f)
        assertEquals(bodyBounds.left + bodyPadding, avatarBounds.left, 1f)
        assertNodeWithin(header, body)
        assertNodeWithin(avatar, header)
        assertNodeWithin(identity, header)
        assertNodeWithin(name, identity)
        assertNodeWithin(model, identity)
        assertNodeWithin(actions, header)
        assertNodeWithin(copy, actions)
        assertNodeWithin(bookmark, actions)
        assertNodesDoNotOverlap(avatar, identity)
        assertNodesDoNotOverlap(identity, actions)
        assertNodesDoNotOverlap(name, model)
        assertTrue(identity.fetchSemanticsNode().boundsInRoot.width > 0f)
        assertNodeWithin(response, body)
        assertTrue(response.fetchSemanticsNode().boundsInRoot.top >= headerBounds.bottom)
        assertEquals(avatarBounds.left, response.fetchSemanticsNode().boundsInRoot.left, 0.5f)
    }

    @Test
    fun assistantFooterUsesOneCompactRowAndKeepsReadySpeechBelowIt() {
        val conversation = Conversation(
            id = "conversation-compact-footer",
            title = "Compact footer",
            model = "model-1",
        )
        val assistantMessage = ChatMessage(
            id = "assistant-compact-footer",
            conversationId = conversation.id,
            role = "assistant",
            content = "A concise completed response.",
            metadata = DirectApiTransport.defaultJson.encodeToString(
                AssistantMetadata(
                    usage = SerializableUsage(inputTokens = 2_500, outputTokens = 900),
                ),
            ),
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fake = UiFakeDataSource(withModel = true).apply {
            conversations += conversation
            speechAudio = TtsAudio(File(context.cacheDir, "compact-footer-ready.mp3"), false)
            seedMessages(conversation.id, listOf(assistantMessage))
        }
        val viewModel = AppViewModel(fake)
        lateinit var density: Density

        setAppAt(viewModel, PHONE_SIZE) { currentDensity, _ -> density = currentDensity }
        composeRule.waitUntil(5_000) { fake.initialized }
        composeRule.runOnIdle { viewModel.openConversation(conversation.id) }
        composeRule.waitUntil(5_000) { viewModel.state.value.activeMessages.size == 1 }

        val footer = composeRule.onNodeWithTag(UiTestTags.PROCESS_TOKEN_ROW)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.waitForIdle()
        val process = composeRule.onNodeWithTag(UiTestTags.PROCESS_DETAILS).assertIsDisplayed()
        val token = composeRule.onNodeWithTag(UiTestTags.TOKEN_USAGE).assertIsDisplayed()
        val actions = composeRule.onNodeWithTag(
            UiTestTags.assistantMessageFooterActions(assistantMessage.id),
        ).assertIsDisplayed()
        val branch = composeRule.onNodeWithContentDescription(
            context.getString(xyz.mek030399.tokenflow.R.string.create_branch),
        ).assertIsDisplayed()
        val speech = composeRule.onNodeWithTag(UiTestTags.SPEECH_ACTION).assertIsDisplayed()
        val note = composeRule.onNodeWithContentDescription(
            context.getString(xyz.mek030399.tokenflow.R.string.save_as_note),
        ).assertIsDisplayed()
        val regenerate = composeRule.onNodeWithContentDescription(
            context.getString(xyz.mek030399.tokenflow.R.string.regenerate),
        ).assertIsDisplayed()
        val footerBounds = footer.fetchSemanticsNode().boundsInRoot
        val expectedFooterHeight = with(density) { 32.dp.toPx() }
        val expectedFourActionWidth = with(density) { (32.dp * 4).toPx() }

        assertEquals(expectedFooterHeight, footerBounds.height, 1f)
        assertEquals(expectedFourActionWidth, actions.fetchSemanticsNode().boundsInRoot.width, 1f)
        assertNodeWithin(process, footer)
        assertNodeWithin(token, footer)
        assertNodeWithin(actions, footer)
        assertNodeWithin(branch, actions)
        assertNodeWithin(speech, actions)
        assertNodeWithin(note, actions)
        assertNodeWithin(regenerate, actions)
        assertNodesDoNotOverlap(process, token)
        assertNodesDoNotOverlap(process, actions)
        assertNodesDoNotOverlap(token, actions)
        assertEquals(footerBounds.center.y, process.fetchSemanticsNode().boundsInRoot.center.y, 1f)
        assertEquals(footerBounds.center.y, actions.fetchSemanticsNode().boundsInRoot.center.y, 1f)
        assertTrue(abs(footerBounds.center.y - token.fetchSemanticsNode().boundsInRoot.center.y) <= 1f)

        composeRule.runOnIdle { viewModel.synthesizeSpeech(assistantMessage.id) }
        composeRule.waitUntil(5_000) {
            viewModel.state.value.tts[assistantMessage.id]?.filePath == fake.speechAudio?.file?.absolutePath
        }
        composeRule.onAllNodesWithTag(UiTestTags.SPEECH_ACTION).assertCountEquals(0)
        val readyActions = composeRule.onNodeWithTag(
            UiTestTags.assistantMessageFooterActions(assistantMessage.id),
        ).assertIsDisplayed()
        val playback = composeRule.onNodeWithTag(UiTestTags.SPEECH_CONTROLS)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.waitForIdle()
        val refreshedFooter = composeRule.onNodeWithTag(UiTestTags.PROCESS_TOKEN_ROW).assertIsDisplayed()
        val expectedThreeActionWidth = with(density) { (32.dp * 3).toPx() }

        assertEquals(
            expectedFooterHeight,
            refreshedFooter.fetchSemanticsNode().boundsInRoot.height,
            1f,
        )
        assertEquals(expectedThreeActionWidth, readyActions.fetchSemanticsNode().boundsInRoot.width, 1f)
        assertNodeWithin(readyActions, refreshedFooter)
        assertTrue(
            refreshedFooter.fetchSemanticsNode().boundsInRoot.bottom <=
                playback.fetchSemanticsNode().boundsInRoot.top + 0.5f,
        )
    }

    @Test
    fun longTokenSummaryStaysSingleLineAndClearOfFooterActionsAtLargeFont() {
        val inputTokens = 9_000_000_000_000L
        val outputTokens = 8_000_000_000_000L
        val conversation = Conversation(
            id = "conversation-long-footer-usage",
            title = "Long footer usage",
            model = "model-1",
        )
        val assistantMessage = ChatMessage(
            id = "assistant-long-footer-usage",
            conversationId = conversation.id,
            role = "assistant",
            content = "Response with deliberately large usage counters.",
            metadata = DirectApiTransport.defaultJson.encodeToString(
                AssistantMetadata(
                    usage = SerializableUsage(
                        inputTokens = inputTokens,
                        outputTokens = outputTokens,
                        cacheReadTokens = 7_200_000_000_000L,
                        cacheMetricsReported = true,
                    ),
                ),
            ),
        )
        val fake = UiFakeDataSource(withModel = true).apply {
            conversations += conversation
            seedMessages(conversation.id, listOf(assistantMessage))
        }
        val viewModel = AppViewModel(fake)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        lateinit var density: Density

        setAppAt(viewModel, PHONE_SIZE, fontScale = 1.5f) { currentDensity, _ -> density = currentDensity }
        composeRule.waitUntil(5_000) { fake.initialized }
        composeRule.runOnIdle { viewModel.openConversation(conversation.id) }
        composeRule.waitUntil(5_000) { viewModel.state.value.activeMessages.size == 1 }

        val footer = composeRule.onNodeWithTag(UiTestTags.PROCESS_TOKEN_ROW)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.waitForIdle()
        val process = composeRule.onNodeWithTag(UiTestTags.PROCESS_DETAILS).assertIsDisplayed()
        val token = composeRule.onNodeWithTag(UiTestTags.TOKEN_USAGE).assertIsDisplayed()
        val actions = composeRule.onNodeWithTag(
            UiTestTags.assistantMessageFooterActions(assistantMessage.id),
        ).assertIsDisplayed()
        val footerBounds = footer.fetchSemanticsNode().boundsInRoot
        val tokenLayouts = mutableListOf<TextLayoutResult>()
        token.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(tokenLayouts) }
        val tokenLayout = tokenLayouts.single()
        val expectedDescription = context.getString(
            xyz.mek030399.tokenflow.R.string.tokens_used_with_cache_accessibility,
            formatTokenCount(inputTokens + outputTokens),
            formatTokenCount(inputTokens),
            formatTokenCount(outputTokens),
            80,
        )

        assertEquals(with(density) { 32.dp.toPx() }, footerBounds.height, 1f)
        assertTrue(process.fetchSemanticsNode().boundsInRoot.width <= with(density) { 96.dp.toPx() } + 1f)
        assertNodeWithin(process, footer)
        assertNodeWithin(token, footer)
        assertNodeWithin(actions, footer)
        assertNodesDoNotOverlap(process, token)
        assertNodesDoNotOverlap(process, actions)
        assertNodesDoNotOverlap(token, actions)
        assertEquals(1, tokenLayout.lineCount)
        assertEquals(1, tokenLayout.layoutInput.maxLines)
        assertEquals(TextOverflow.Ellipsis, tokenLayout.layoutInput.overflow)
        assertTrue(tokenLayout.isLineEllipsized(0))
        assertEquals(
            listOf(expectedDescription),
            token.fetchSemanticsNode().config[SemanticsProperties.ContentDescription],
        )
    }

    @Test
    fun assistantFooterHandlesNoTokensOlderRepliesExpandedProcessAndFailure() {
        val conversation = Conversation(
            id = "conversation-footer-states",
            title = "Footer states",
            model = "model-1",
        )
        val olderAssistant = ChatMessage(
            id = "assistant-footer-older",
            conversationId = conversation.id,
            role = "assistant",
            content = "Earlier response without usage.",
        )
        val failedAssistant = ChatMessage(
            id = "assistant-footer-failed",
            conversationId = conversation.id,
            role = "assistant",
            content = "Partial failed response.",
            status = "failed",
        )
        val fake = UiFakeDataSource(withModel = true).apply {
            conversations += conversation
            seedMessages(conversation.id, listOf(olderAssistant, failedAssistant))
        }
        val viewModel = AppViewModel(fake)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        lateinit var density: Density

        setAppAt(viewModel, PHONE_SIZE) { currentDensity, _ -> density = currentDensity }
        composeRule.waitUntil(5_000) { fake.initialized }
        composeRule.runOnIdle { viewModel.openConversation(conversation.id) }
        composeRule.waitUntil(5_000) { viewModel.state.value.activeMessages.size == 2 }

        val olderBodyTag = UiTestTags.messageBody(olderAssistant.id)
        val failedBodyTag = UiTestTags.messageBody(failedAssistant.id)
        val olderFooter = composeRule.onNode(
            hasTestTag(UiTestTags.PROCESS_TOKEN_ROW) and hasAnyAncestor(hasTestTag(olderBodyTag)),
        ).assertIsDisplayed()
        val failedFooter = composeRule.onNode(
            hasTestTag(UiTestTags.PROCESS_TOKEN_ROW) and hasAnyAncestor(hasTestTag(failedBodyTag)),
        ).assertIsDisplayed()
        val olderActions = composeRule.onNodeWithTag(
            UiTestTags.assistantMessageFooterActions(olderAssistant.id),
        ).assertIsDisplayed()
        val failedActions = composeRule.onNodeWithTag(
            UiTestTags.assistantMessageFooterActions(failedAssistant.id),
        ).assertIsDisplayed()
        val failedProcess = composeRule.onNode(
            hasTestTag(UiTestTags.PROCESS_DETAILS) and hasAnyAncestor(hasTestTag(failedBodyTag)),
        ).assertIsDisplayed()
        val regenerate = composeRule.onNodeWithContentDescription(
            context.getString(xyz.mek030399.tokenflow.R.string.regenerate),
        ).assertIsDisplayed()
        val compactHeight = with(density) { 32.dp.toPx() }

        composeRule.onAllNodesWithTag(UiTestTags.TOKEN_USAGE).assertCountEquals(0)
        assertEquals(compactHeight, olderFooter.fetchSemanticsNode().boundsInRoot.height, 1f)
        assertEquals(compactHeight, failedFooter.fetchSemanticsNode().boundsInRoot.height, 1f)
        assertEquals(with(density) { (32.dp * 3).toPx() }, olderActions.fetchSemanticsNode().boundsInRoot.width, 1f)
        assertEquals(with(density) { (32.dp * 4).toPx() }, failedActions.fetchSemanticsNode().boundsInRoot.width, 1f)
        assertNodeWithin(olderActions, olderFooter)
        assertNodeWithin(failedActions, failedFooter)
        assertNodeWithin(regenerate, failedActions)
        assertNodesDoNotOverlap(failedProcess, failedActions)

        failedProcess.performClick()
        val noDetails = composeRule.onNodeWithText(
            context.getString(xyz.mek030399.tokenflow.R.string.no_process_details),
        ).assertIsDisplayed()
        val failure = composeRule.onNodeWithText(
            context.getString(xyz.mek030399.tokenflow.R.string.assistant_failed),
        ).assertIsDisplayed()
        val refreshedFailedFooter = composeRule.onNode(
            hasTestTag(UiTestTags.PROCESS_TOKEN_ROW) and hasAnyAncestor(hasTestTag(failedBodyTag)),
        ).assertIsDisplayed()
        val refreshedFooterBounds = refreshedFailedFooter.fetchSemanticsNode().boundsInRoot

        assertEquals(compactHeight, refreshedFooterBounds.height, 1f)
        assertTrue(refreshedFooterBounds.bottom <= noDetails.fetchSemanticsNode().boundsInRoot.top + 0.5f)
        assertTrue(refreshedFooterBounds.bottom <= failure.fetchSemanticsNode().boundsInRoot.top + 0.5f)
        assertNodeWithin(noDetails, composeRule.onNodeWithTag(failedBodyTag))
        assertNodeWithin(failure, composeRule.onNodeWithTag(failedBodyTag))
    }

    @Test
    fun emptyStreamingResponseShowsToolStatusThenReturnsToCallingModel() {
        val toolTerminalGate = CompletableDeferred<Unit>()
        val finishGate = CompletableDeferred<Unit>()
        val fake = UiFakeDataSource(withModel = true).apply {
            sendMessageFlow = { conversationId, request ->
                controlledToolFlow(
                    conversationId = conversationId,
                    request = request,
                    toolName = "web_search",
                    partialContent = null,
                    toolTerminalGate = toolTerminalGate,
                    finishGate = finishGate,
                )
            }
        }
        val viewModel = AppViewModel(fake)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent { TokenFlowTheme { TokenFlowApp(viewModel) } }
        composeRule.waitUntil(5_000) { fake.initialized }

        composeRule.onNodeWithTag(UiTestTags.MESSAGE_INPUT).performTextInput("Search for this")
        composeRule.onNodeWithTag(UiTestTags.MESSAGE_ACTION).performClick()
        composeRule.waitUntil(5_000) {
            viewModel.state.value.activeGeneration?.events?.any {
                it.type == "tool_started" && it.name == "web_search"
            } == true
        }
        val status = composeRule.onNodeWithTag(UiTestTags.GENERATION_STATUS)
            .assertIsDisplayed()
            .assertTextEquals(context.getString(xyz.mek030399.tokenflow.R.string.searching_web))
        val searchingIconTag = UiTestTags.GENERATION_STATUS_ICON_PREFIX +
            GenerationActivity.SEARCHING_WEB.name.lowercase()
        composeRule.onNodeWithTag(searchingIconTag, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(
            UiTestTags.GENERATION_STATUS_ANIMATION,
            useUnmergedTree = true,
        ).assertIsDisplayed()
        assertEquals(
            LiveRegionMode.Polite,
            status.fetchSemanticsNode().config[SemanticsProperties.LiveRegion],
        )
        composeRule.mainClock.autoAdvance = false
        try {
            val boundsBeforeAnimation = status.fetchSemanticsNode().boundsInRoot
            composeRule.mainClock.advanceTimeBy(600)
            composeRule.waitForIdle()
            val boundsAfterAnimation = composeRule.onNodeWithTag(UiTestTags.GENERATION_STATUS)
                .fetchSemanticsNode().boundsInRoot
            assertEquals(boundsBeforeAnimation.left, boundsAfterAnimation.left, 0.5f)
            assertEquals(boundsBeforeAnimation.top, boundsAfterAnimation.top, 0.5f)
            assertEquals(boundsBeforeAnimation.right, boundsAfterAnimation.right, 0.5f)
            assertEquals(boundsBeforeAnimation.bottom, boundsAfterAnimation.bottom, 0.5f)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }

        toolTerminalGate.complete(Unit)
        composeRule.waitUntil(5_000) {
            viewModel.state.value.activeGeneration?.events?.any {
                it.type == "tool_completed" && it.id == "tool-call"
            } == true
        }
        composeRule.onNodeWithTag(UiTestTags.GENERATION_STATUS)
            .assertIsDisplayed()
            .assertTextEquals(context.getString(xyz.mek030399.tokenflow.R.string.calling_model))
        composeRule.onAllNodesWithTag(searchingIconTag, useUnmergedTree = true).assertCountEquals(0)
        composeRule.onNodeWithTag(
            UiTestTags.GENERATION_STATUS_ICON_PREFIX + GenerationActivity.CALLING_MODEL.name.lowercase(),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(
            UiTestTags.GENERATION_STATUS_ANIMATION,
            useUnmergedTree = true,
        ).assertIsDisplayed()

        finishGate.complete(Unit)
        composeRule.waitUntil(5_000) { viewModel.state.value.activeGeneration?.active == false }
        composeRule.onAllNodesWithTag(
            UiTestTags.GENERATION_STATUS_ANIMATION,
            useUnmergedTree = true,
        ).assertCountEquals(0)
    }

    @Test
    fun streamingResponseShowsToolStatusBelowContentOnlyWhileToolIsActive() {
        val toolStartGate = CompletableDeferred<Unit>()
        val toolTerminalGate = CompletableDeferred<Unit>()
        val finishGate = CompletableDeferred<Unit>()
        val partialContent = "Long partial answer ".repeat(160) + "Tail marker"
        val fake = UiFakeDataSource(withModel = true).apply {
            sendMessageFlow = { conversationId, request ->
                controlledToolFlow(
                    conversationId = conversationId,
                    request = request,
                    toolName = "read_url",
                    partialContent = partialContent,
                    toolStartGate = toolStartGate,
                    toolTerminalGate = toolTerminalGate,
                    finishGate = finishGate,
                )
            }
        }
        val viewModel = AppViewModel(fake)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        setAppAt(viewModel, PHONE_SIZE)
        composeRule.waitUntil(5_000) { fake.initialized }

        composeRule.onNodeWithTag(UiTestTags.MESSAGE_INPUT).performTextInput("Read this URL")
        composeRule.onNodeWithTag(UiTestTags.MESSAGE_ACTION).performClick()
        composeRule.waitUntil(5_000) {
            viewModel.state.value.activeMessages.any { it.content == partialContent }
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag(UiTestTags.GENERATION_STATUS).assertCountEquals(0)

        toolStartGate.complete(Unit)
        composeRule.waitUntil(5_000) {
            viewModel.state.value.activeGeneration?.events?.any {
                it.type == "tool_started" && it.name == "read_url"
            } == true
        }
        val content = composeRule.onNodeWithText("Tail marker", substring = true).assertIsDisplayed()
        val status = composeRule.onNodeWithTag(UiTestTags.GENERATION_STATUS)
            .assertIsDisplayed()
            .assertTextEquals(context.getString(xyz.mek030399.tokenflow.R.string.reading_url))
        val readingIconTag = UiTestTags.GENERATION_STATUS_ICON_PREFIX +
            GenerationActivity.READING_URL.name.lowercase()
        composeRule.onNodeWithTag(readingIconTag, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(
            UiTestTags.GENERATION_STATUS_ANIMATION,
            useUnmergedTree = true,
        ).assertIsDisplayed()
        assertTrue(
            content.fetchSemanticsNode().boundsInRoot.bottom <=
                status.fetchSemanticsNode().boundsInRoot.top,
        )

        toolTerminalGate.complete(Unit)
        composeRule.waitUntil(5_000) {
            viewModel.state.value.activeGeneration?.events?.any {
                it.type == "tool_completed" && it.id == "tool-call"
            } == true
        }
        composeRule.onAllNodesWithTag(UiTestTags.GENERATION_STATUS).assertCountEquals(0)
        composeRule.onAllNodesWithTag(readingIconTag, useUnmergedTree = true).assertCountEquals(0)
        composeRule.onAllNodesWithTag(
            UiTestTags.GENERATION_STATUS_ANIMATION,
            useUnmergedTree = true,
        ).assertCountEquals(0)

        finishGate.complete(Unit)
        composeRule.waitUntil(5_000) { viewModel.state.value.activeGeneration?.active == false }
    }

    @Test
    fun generationStatusUsesActivitySpecificIcons() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val labels = mapOf(
            GenerationActivity.CALLING_MODEL to xyz.mek030399.tokenflow.R.string.calling_model,
            GenerationActivity.SEARCHING_WEB to xyz.mek030399.tokenflow.R.string.searching_web,
            GenerationActivity.READING_URL to xyz.mek030399.tokenflow.R.string.reading_url,
            GenerationActivity.SEARCHING_LOCAL_KNOWLEDGE to
                xyz.mek030399.tokenflow.R.string.searching_local_knowledge,
            GenerationActivity.CALCULATING to xyz.mek030399.tokenflow.R.string.calculating,
            GenerationActivity.CONVERTING_UNITS to xyz.mek030399.tokenflow.R.string.converting_units,
            GenerationActivity.CALLING_TOOL to xyz.mek030399.tokenflow.R.string.calling_tool,
        )
        composeRule.setContent {
            TokenFlowTheme {
                Column {
                    labels.keys.forEach { activity ->
                        GenerationStatus(activity = activity, letterSpacing = 0f, animated = false)
                    }
                }
            }
        }

        labels.forEach { (activity, labelResource) ->
            composeRule.onNodeWithTag(
                UiTestTags.GENERATION_STATUS_ICON_PREFIX + activity.name.lowercase(),
                useUnmergedTree = true,
            ).assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(labelResource)).assertIsDisplayed()
        }
        composeRule.onAllNodesWithTag(
            UiTestTags.GENERATION_STATUS_ANIMATION,
            useUnmergedTree = true,
        ).assertCountEquals(0)
    }

    @Test
    fun generationStatusKeepsAnimationVisibleOnNarrowLargeTextLayout() {
        val density = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 640.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                    TokenFlowTheme {
                        Box(Modifier.size(width = 260.dp, height = 120.dp)) {
                            GenerationStatus(
                                activity = GenerationActivity.SEARCHING_LOCAL_KNOWLEDGE,
                                letterSpacing = 0f,
                                animated = true,
                            )
                        }
                    }
                }
            }
        }

        val animation = composeRule.onNodeWithTag(
            UiTestTags.GENERATION_STATUS_ANIMATION,
            useUnmergedTree = true,
        ).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(18.dp.value * density, animation.width, 0.5f)
    }

    @Test
    fun reportedCacheHitIsAppendedToTokenUsage() {
        val fake = UiFakeDataSource(withModel = true).apply {
            completionUsage = Usage(
                inputTokens = 2_500,
                outputTokens = 100,
                cacheReadTokens = 2_000,
                cacheMetricsReported = true,
            )
        }
        val viewModel = AppViewModel(fake)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent { TokenFlowTheme { TokenFlowApp(viewModel) } }
        composeRule.waitUntil(5_000) { fake.initialized }

        composeRule.onNodeWithTag(UiTestTags.MESSAGE_INPUT).performTextInput("Cache test")
        composeRule.onNodeWithTag(UiTestTags.MESSAGE_ACTION).performClick()
        composeRule.waitUntil(5_000) { fake.sentRequest != null }

        val cacheSummary = context.getString(xyz.mek030399.tokenflow.R.string.cache_hit_rate, 80)
        composeRule.onNodeWithText("2.6K↑2.5K↓100 · $cacheSummary").assertIsDisplayed()
    }

    @Test
    fun attachmentMenuShowsCameraFirstAndPendingAttachmentCanBeRemoved() {
        val fake = UiFakeDataSource(withModel = true)
        val viewModel = AppViewModel(fake)
        composeRule.setContent { TokenFlowTheme { TokenFlowApp(viewModel) } }
        composeRule.waitUntil(5_000) { fake.initialized }
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule.onNodeWithContentDescription(
            context.getString(xyz.mek030399.tokenflow.R.string.add_attachment),
        ).performClick()
        val cameraTop = composeRule.onNodeWithText(
            context.getString(xyz.mek030399.tokenflow.R.string.take_photo),
        ).fetchSemanticsNode().boundsInRoot.top
        val imagesTop = composeRule.onNodeWithText(
            context.getString(xyz.mek030399.tokenflow.R.string.choose_images),
        ).fetchSemanticsNode().boundsInRoot.top
        val filesTop = composeRule.onNodeWithText(
            context.getString(xyz.mek030399.tokenflow.R.string.choose_files),
        ).fetchSemanticsNode().boundsInRoot.top
        val noteTop = composeRule.onNodeWithText(
            context.getString(xyz.mek030399.tokenflow.R.string.import_note),
        ).fetchSemanticsNode().boundsInRoot.top
        assertTrue(cameraTop < imagesTop && imagesTop < filesTop && filesTop < noteTop)
        composeRule.onNodeWithText(context.getString(xyz.mek030399.tokenflow.R.string.cancel)).performClick()

        viewModel.addAttachments(listOf(PendingAttachment(
            uri = "content://documents/draft.txt",
            displayName = "draft.txt",
            mimeType = "text/plain",
            sizeBytes = 12,
        )))
        composeRule.onNodeWithText("draft.txt").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            context.getString(xyz.mek030399.tokenflow.R.string.remove),
        ).performClick()
        composeRule.waitUntil(5_000) { viewModel.state.value.pendingAttachments.isEmpty() }
        composeRule.onAllNodesWithText("draft.txt").assertCountEquals(0)
    }

    @Test
    fun longPressConversationEntersUuidSelectionMode() {
        val fake = UiFakeDataSource(withModel = true).apply {
            conversations += Conversation(id = "conversation-existing", title = "Existing", model = model.id)
        }
        val viewModel = AppViewModel(fake)
        setAppAt(viewModel, PHONE_SIZE)
        composeRule.waitUntil(5_000) { fake.initialized }

        composeRule.onNodeWithTag(UiTestTags.OPEN_CONVERSATIONS).performClick()
        composeRule.onNodeWithTag(UiTestTags.conversationItem("conversation-existing")).performTouchInput { longClick() }
        composeRule.onNodeWithTag(UiTestTags.RENAME_SELECTED).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTestTags.DELETE_SELECTED).assertIsDisplayed()
    }

    @Test
    fun phoneChatTopBarUsesCompactHeightTypographyAndTouchTargets() {
        val title = "这是一个用于验证手机顶部栏单行省略的超长会话标题".repeat(4)
        val modelName = "gpt-5.6-terra-with-an-extra-long-display-name-".repeat(4)
        val conversation = Conversation(id = "compact-phone-header", title = title, model = "model-1")
        val fake = UiFakeDataSource(withModel = true, modelDisplayName = modelName).apply {
            conversations += conversation
        }
        val viewModel = AppViewModel(fake)
        lateinit var density: Density
        var statusBarTop = 0

        setAppAt(viewModel, PHONE_SIZE, fontScale = 1.3f) { currentDensity, currentStatusBarTop ->
            density = currentDensity
            statusBarTop = currentStatusBarTop
        }
        composeRule.waitUntil(5_000) { fake.initialized }
        composeRule.runOnIdle { viewModel.openConversation(conversation.id) }
        composeRule.waitUntil(5_000) { viewModel.state.value.activeConversationId == conversation.id }

        assertCompactChatTopBar(density, statusBarTop, expectMenu = true)
    }

    @Test
    fun wideChatTopBarKeepsLongTitleModelAndActionsSeparated() {
        val title = "A long tablet conversation title that must stay on one line ".repeat(5)
        val modelName = "model-provider-name-that-is-far-longer-than-the-toolbar-slot-".repeat(4)
        val conversation = Conversation(id = "compact-wide-header", title = title, model = "model-1")
        val fake = UiFakeDataSource(withModel = true, modelDisplayName = modelName).apply {
            conversations += conversation
        }
        val viewModel = AppViewModel(fake)
        lateinit var density: Density
        var statusBarTop = 0

        setAppAt(viewModel, SHORT_TABLET_SIZE, fontScale = 1.3f) { currentDensity, currentStatusBarTop ->
            density = currentDensity
            statusBarTop = currentStatusBarTop
        }
        composeRule.waitUntil(5_000) { fake.initialized }
        composeRule.runOnIdle { viewModel.openConversation(conversation.id) }
        composeRule.waitUntil(5_000) { viewModel.state.value.activeConversationId == conversation.id }

        assertCompactChatTopBar(density, statusBarTop, expectMenu = false)
    }

    @Test
    fun phoneChatTopBarGrowsForLargeAccessibilityFont() {
        val title = "Accessibility title that stays on one line ".repeat(5)
        val modelName = "accessibility-model-name-that-must-ellipsize-".repeat(4)
        val conversation = Conversation(id = "accessible-phone-header", title = title, model = "model-1")
        val fake = UiFakeDataSource(withModel = true, modelDisplayName = modelName).apply {
            conversations += conversation
        }
        val viewModel = AppViewModel(fake)
        lateinit var density: Density
        var statusBarTop = 0

        setAppAt(viewModel, PHONE_SIZE, fontScale = 1.5f) { currentDensity, currentStatusBarTop ->
            density = currentDensity
            statusBarTop = currentStatusBarTop
        }
        composeRule.waitUntil(5_000) { fake.initialized }
        composeRule.runOnIdle { viewModel.openConversation(conversation.id) }
        composeRule.waitUntil(5_000) { viewModel.state.value.activeConversationId == conversation.id }

        assertCompactChatTopBar(density, statusBarTop, expectMenu = true)
        val barHeight = composeRule.onNodeWithTag(UiTestTags.CHAT_TOP_BAR)
            .fetchSemanticsNode().boundsInRoot.height - statusBarTop
        assertTrue(barHeight > with(density) { 48.dp.toPx() })
    }

    @Test
    fun globalSettingsExposeAnonymousInfoFlowAndDefaultModel() {
        val fake = UiFakeDataSource(withModel = true)
        val viewModel = AppViewModel(fake)
        setAppAt(viewModel, PHONE_SIZE)
        composeRule.waitUntil(5_000) { fake.initialized }
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule.onNodeWithTag(UiTestTags.OPEN_CONVERSATIONS).performClick()
        composeRule.onNodeWithTag(UiTestTags.EXPAND_DESTINATIONS).performClick()
        composeRule.onNodeWithText(context.getString(xyz.mek030399.tokenflow.R.string.global_settings)).performClick()
        composeRule.onNodeWithText("InfoFlow").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(xyz.mek030399.tokenflow.R.string.infoflow_api_key)).assertCountEquals(0)
        composeRule.onNodeWithText("Model A").performScrollTo().assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun globalSettingsShowSixThemesAndPersistSelectionImmediately() {
        val fake = UiFakeDataSource(withModel = true)
        val viewModel = AppViewModel(fake)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("tokenflow_display", Context.MODE_PRIVATE)
        val hadOriginalTheme = preferences.contains("app_theme")
        val originalTheme = preferences.getString("app_theme", null)
        preferences.edit().remove("app_theme").commit()
        val appInstance = mutableIntStateOf(0)

        try {
            composeRule.setContent {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(PHONE_SIZE)) {
                    key(appInstance.intValue) {
                        TokenFlowApp(viewModel)
                    }
                }
            }
            composeRule.waitUntil(5_000) { fake.initialized }

            composeRule.onNodeWithTag(UiTestTags.OPEN_CONVERSATIONS).performClick()
            composeRule.onNodeWithTag(UiTestTags.EXPAND_DESTINATIONS).performClick()
            composeRule.onNodeWithText(context.getString(xyz.mek030399.tokenflow.R.string.global_settings)).performClick()

            listOf(
                xyz.mek030399.tokenflow.R.string.theme_dawn_white,
                xyz.mek030399.tokenflow.R.string.theme_jade_mist,
                xyz.mek030399.tokenflow.R.string.theme_peach_bloom,
                xyz.mek030399.tokenflow.R.string.theme_violet_dusk,
                xyz.mek030399.tokenflow.R.string.theme_ocean_blue,
                xyz.mek030399.tokenflow.R.string.theme_amoled_black,
            ).forEach { label -> composeRule.onNodeWithText(context.getString(label)).assertIsDisplayed() }
            composeRule.onNodeWithTag(UiTestTags.themeOption(AppTheme.DAWN_WHITE)).assertIsSelected()
            val dawnBeforeSwitch = composeRule
                .onNodeWithTag(UiTestTags.themeOption(AppTheme.DAWN_WHITE))
                .sampleSurfaceColor()
            composeRule.onNodeWithTag(UiTestTags.themeOption(AppTheme.AMOLED_BLACK)).performClick().assertIsSelected()
            composeRule.waitForIdle()
            val dawnAfterSwitch = composeRule
                .onNodeWithTag(UiTestTags.themeOption(AppTheme.DAWN_WHITE))
                .sampleSurfaceColor()
            assertTrue(dawnBeforeSwitch.toArgb() != dawnAfterSwitch.toArgb())
            assertEquals(
                colorSchemeFor(AppTheme.AMOLED_BLACK, dark = true).surface.toArgb(),
                dawnAfterSwitch.toArgb(),
            )
            assertEquals(AppTheme.AMOLED_BLACK, ChatDisplayPreferences(context).readTheme())

            composeRule.runOnIdle { appInstance.intValue++ }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(UiTestTags.themeOption(AppTheme.AMOLED_BLACK)).assertIsSelected()
        } finally {
            preferences.edit().apply {
                if (hadOriginalTheme) putString("app_theme", originalTheme) else remove("app_theme")
            }.commit()
        }
    }

    @Test
    fun shortTabletSidebarScrollsToAboutAtLargeFont() {
        val fake = UiFakeDataSource(withModel = true)
        val viewModel = AppViewModel(fake)
        setAppAt(viewModel, SHORT_TABLET_SIZE, fontScale = 1.3f)
        composeRule.waitUntil(5_000) { fake.initialized }

        composeRule.onAllNodesWithTag(UiTestTags.OPEN_CONVERSATIONS).assertCountEquals(0)
        composeRule.onNodeWithTag(UiTestTags.EXPAND_DESTINATIONS).performClick()

        val destinationTag = UiTestTags.sidebarDestination(AppScreen.ABOUT)
        val destinations = composeRule.onNodeWithTag(UiTestTags.SIDEBAR_DESTINATIONS)
        destinations.performScrollToNode(hasTestTag(destinationTag))
        val about = composeRule.onNodeWithTag(destinationTag).assertIsDisplayed()
        assertNodeWithin(about, destinations)

        about.performClick()
        composeRule.waitUntil(5_000) { viewModel.state.value.screen == AppScreen.ABOUT }
        composeRule.onNodeWithTag(UiTestTags.ABOUT_SCREEN).assertIsDisplayed()
    }

    @Test
    fun collapsingTabletDestinationsRestoresConversationList() {
        val fake = UiFakeDataSource(withModel = true).apply {
            repeat(20) { index ->
                conversations += Conversation(
                    id = "tablet-conversation-$index",
                    title = "Tablet conversation $index",
                    model = model.id,
                )
            }
        }
        val viewModel = AppViewModel(fake)
        setAppAt(viewModel, SHORT_TABLET_SIZE)
        composeRule.waitUntil(5_000) { fake.initialized }

        val lastConversationTag = UiTestTags.conversationItem("tablet-conversation-19")
        composeRule.onNodeWithTag(UiTestTags.SIDEBAR_CONVERSATIONS).performScrollToNode(
            hasTestTag(lastConversationTag),
        )
        composeRule.onNodeWithTag(lastConversationTag).assertIsDisplayed()

        composeRule.onNodeWithTag(UiTestTags.EXPAND_DESTINATIONS).performClick()
        composeRule.onNodeWithTag(UiTestTags.SIDEBAR_DESTINATIONS).performScrollToNode(
            hasTestTag(UiTestTags.sidebarDestination(AppScreen.ABOUT)),
        )
        composeRule.onNodeWithTag(UiTestTags.EXPAND_DESTINATIONS).performClick()

        composeRule.onNodeWithTag(lastConversationTag).assertIsDisplayed()
    }

    @Test
    fun shortTabletWorkspaceRailScrollsToAbout() {
        val fake = UiFakeDataSource(withModel = true)
        val viewModel = AppViewModel(fake)
        setAppAt(viewModel, SHORT_TABLET_SIZE)
        composeRule.waitUntil(5_000) { fake.initialized }
        composeRule.runOnIdle { viewModel.openScreen(AppScreen.GLOBAL_SETTINGS) }
        composeRule.waitUntil(5_000) { viewModel.state.value.screen == AppScreen.GLOBAL_SETTINGS }

        val destinationTag = UiTestTags.workspaceDestination(AppScreen.ABOUT)
        val destinations = composeRule.onNodeWithTag(UiTestTags.WORKSPACE_DESTINATIONS)
        destinations.performScrollToNode(hasTestTag(destinationTag))
        val about = composeRule.onNodeWithTag(destinationTag).assertIsDisplayed()
        assertNodeWithin(about, destinations)

        about.performClick()
        composeRule.waitUntil(5_000) { viewModel.state.value.screen == AppScreen.ABOUT }
        composeRule.onNodeWithTag(UiTestTags.ABOUT_SCREEN).assertIsDisplayed()
    }

    @Test
    fun portraitTabletSidebarScrollsToAbout() {
        val fake = UiFakeDataSource(withModel = true)
        val viewModel = AppViewModel(fake)
        setAppAt(viewModel, PORTRAIT_TABLET_SIZE)
        composeRule.waitUntil(5_000) { fake.initialized }

        composeRule.onAllNodesWithTag(UiTestTags.OPEN_CONVERSATIONS).assertCountEquals(0)
        composeRule.onNodeWithTag(UiTestTags.EXPAND_DESTINATIONS).performClick()

        val destinationTag = UiTestTags.sidebarDestination(AppScreen.ABOUT)
        val destinations = composeRule.onNodeWithTag(UiTestTags.SIDEBAR_DESTINATIONS)
        destinations.performScrollToNode(hasTestTag(destinationTag))
        val about = composeRule.onNodeWithTag(destinationTag).assertIsDisplayed()
        assertNodeWithin(about, destinations)

        about.performClick()
        composeRule.waitUntil(5_000) { viewModel.state.value.screen == AppScreen.ABOUT }
        composeRule.onNodeWithTag(UiTestTags.ABOUT_SCREEN).assertIsDisplayed()
    }

    @Test
    fun phoneDrawerDestinationsScrollToAbout() {
        val fake = UiFakeDataSource(withModel = true)
        val viewModel = AppViewModel(fake)
        setAppAt(viewModel, PHONE_SIZE)
        composeRule.waitUntil(5_000) { fake.initialized }

        composeRule.onNodeWithTag(UiTestTags.OPEN_CONVERSATIONS).performClick()
        composeRule.onNodeWithTag(UiTestTags.EXPAND_DESTINATIONS).performClick()

        val destinationTag = UiTestTags.sidebarDestination(AppScreen.ABOUT)
        val destinations = composeRule.onNodeWithTag(UiTestTags.SIDEBAR_DESTINATIONS)
        destinations.performScrollToNode(hasTestTag(destinationTag))
        val about = composeRule.onNodeWithTag(destinationTag).assertIsDisplayed()
        assertNodeWithin(about, destinations)

        about.performClick()
        composeRule.waitUntil(5_000) { viewModel.state.value.screen == AppScreen.ABOUT }
        composeRule.onNodeWithTag(UiTestTags.ABOUT_SCREEN).assertIsDisplayed()
    }

    @Test
    fun amoledThemeForcesDarkRuntimeStateWhenSystemThemeIsLight() {
        var actualDark = false
        var actualBackground = Color.Unspecified

        composeRule.setContent {
            TokenFlowTheme(theme = AppTheme.AMOLED_BLACK, darkTheme = false) {
                actualDark = LocalTokenFlowDarkTheme.current
                actualBackground = MaterialTheme.colorScheme.background
                MarkdownContent("[Link](https://example.com)")
            }
        }

        composeRule.onNodeWithText("Link").assertIsDisplayed()
        composeRule.runOnIdle {
            assertTrue(actualDark)
            assertEquals(Color.Black.toArgb(), actualBackground.toArgb())
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun markdownCodeBlockTrimsOuterBlankLinesAndUsesCompactVerticalPadding() {
        val markdown = listOf(
            "```python",
            "",
            " \t",
            "def greet(name: str) -> str:",
            "    return f\"Hello, {name}!\"",
            "",
            "print(greet(\"TokenFlow\"))",
            "\t ",
            "",
            "```",
        ).joinToString("\n")
        val expectedCode =
            "def greet(name: str) -> str:\n    return f\"Hello, {name}!\"\n\nprint(greet(\"TokenFlow\"))"
        val clipboard = FakeComposeClipboardManager()
        var notification: String? = null
        lateinit var density: Density
        val copied = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(xyz.mek030399.tokenflow.R.string.copied)

        composeRule.setContent {
            CompositionLocalProvider(
                LocalClipboardManager provides clipboard,
                LocalNotificationDispatcher provides { notification = it },
            ) {
                TokenFlowTheme {
                    density = LocalDensity.current
                    MarkdownContent(markdown)
                }
            }
        }

        val block = composeRule.onNodeWithTag(UiTestTags.CODE_BLOCK).assertIsDisplayed()
        val header = composeRule.onNodeWithTag(UiTestTags.CODE_BLOCK_HEADER).assertIsDisplayed()
        val content = composeRule.onNodeWithTag(UiTestTags.CODE_BLOCK_CONTENT)
            .assertIsDisplayed()
            .assertTextEquals(expectedCode)
        val layouts = mutableListOf<TextLayoutResult>()
        content.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            action(layouts)
        }

        assertEquals(4, layouts.single().lineCount)
        val blockBounds = block.fetchSemanticsNode().boundsInRoot
        val headerBounds = header.fetchSemanticsNode().boundsInRoot
        val contentBounds = content.fetchSemanticsNode().boundsInRoot
        val expectedVerticalPadding = with(density) { 6.dp.toPx() }
        assertEquals(expectedVerticalPadding, contentBounds.top - headerBounds.bottom, 1f)
        assertEquals(expectedVerticalPadding, blockBounds.bottom - contentBounds.bottom, 1f)

        composeRule.onNodeWithTag(UiTestTags.COPY_CODE_BLOCK).performClick()
        composeRule.runOnIdle {
            assertEquals(expectedCode, clipboard.copiedText)
            assertEquals(copied, notification)
        }
        composeRule.onAllNodesWithText(copied).assertCountEquals(0)
        assertEquals(blockBounds, block.fetchSemanticsNode().boundsInRoot)
        assertEquals(contentBounds, content.fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun appLogoKeepsContrastBrandColorsAndGeometryAcrossThemesAndSizes() {
        composeRule.setContent {
            Column {
                LogoSample("logo_dawn_64", AppTheme.DAWN_WHITE, darkTheme = false, size = 64.dp)
                LogoSample("logo_dawn_34", AppTheme.DAWN_WHITE, darkTheme = false, size = 34.dp)
                LogoSample("logo_amoled_64", AppTheme.AMOLED_BLACK, darkTheme = false, size = 64.dp)
                LogoSample("logo_amoled_34", AppTheme.AMOLED_BLACK, darkTheme = false, size = 34.dp)
            }
        }

        val dawnBackground = colorSchemeFor(AppTheme.DAWN_WHITE, dark = false).background
        val amoledBackground = colorSchemeFor(AppTheme.AMOLED_BLACK, dark = true).background
        val dawn64 = composeRule.onNodeWithTag("logo_dawn_64")
            .assertLogoPixels(dawnBackground, Color(0xFF101820))
        val dawn34 = composeRule.onNodeWithTag("logo_dawn_34")
            .assertLogoPixels(dawnBackground, Color(0xFF101820))
        val amoled64 = composeRule.onNodeWithTag("logo_amoled_64")
            .assertLogoPixels(amoledBackground, Color.White)
        val amoled34 = composeRule.onNodeWithTag("logo_amoled_34")
            .assertLogoPixels(amoledBackground, Color.White)

        assertSameLogoGeometry(dawn64, amoled64)
        assertSameLogoGeometry(dawn34, amoled34)
    }

    @Test
    fun amoledAppRootPaintsPureBlackBehindTransparentChatContent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("tokenflow_display", Context.MODE_PRIVATE)
        val hadOriginalTheme = preferences.contains("app_theme")
        val originalTheme = preferences.getString("app_theme", null)
        preferences.edit().putString("app_theme", AppTheme.AMOLED_BLACK.storageValue).commit()
        val fake = UiFakeDataSource(withModel = true)

        try {
            composeRule.setContent { TokenFlowApp(AppViewModel(fake)) }
            composeRule.waitUntil(5_000) { fake.initialized }

            val pixels = composeRule.onNodeWithTag(UiTestTags.APP_BACKGROUND).captureToImage().toPixelMap()
            val exposedChatBackground = pixels[4, pixels.height / 2]
            assertEquals(Color.Black.toArgb(), exposedChatBackground.toArgb())
        } finally {
            preferences.edit().apply {
                if (hadOriginalTheme) putString("app_theme", originalTheme) else remove("app_theme")
            }.commit()
        }
    }

    @Test
    fun aboutPageShowsFeaturesKeyLinksAndOpenSourceLibraries() {
        val fake = UiFakeDataSource(withModel = true)
        val viewModel = AppViewModel(fake)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent { TokenFlowTheme { TokenFlowApp(viewModel) } }
        composeRule.waitUntil(5_000) { fake.initialized }

        viewModel.openScreen(AppScreen.ABOUT)

        val about = composeRule.onNodeWithTag(UiTestTags.ABOUT_SCREEN).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(xyz.mek030399.tokenflow.R.string.about_features)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(xyz.mek030399.tokenflow.R.string.about_feature_multi_model_title)).assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(xyz.mek030399.tokenflow.R.string.privacy_summary)).assertCountEquals(0)
        val versionName = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        composeRule.onAllNodesWithText(
            context.getString(xyz.mek030399.tokenflow.R.string.version_label, versionName),
        ).assertCountEquals(1)

        val modelToolsTitle = context.getString(xyz.mek030399.tokenflow.R.string.about_model_tools)
        about.performScrollToNode(hasText(modelToolsTitle))
        composeRule.onNodeWithText(modelToolsTitle).assertIsDisplayed()
        listOf(
            UiTestTags.ABOUT_MODEL_TOOL_WEB_SEARCH to Triple(
                xyz.mek030399.tokenflow.R.string.about_model_tool_web_search_title,
                xyz.mek030399.tokenflow.R.string.about_model_tool_web_search_body,
                "web_search",
            ),
            UiTestTags.ABOUT_MODEL_TOOL_READ_URL to Triple(
                xyz.mek030399.tokenflow.R.string.about_model_tool_read_url_title,
                xyz.mek030399.tokenflow.R.string.about_model_tool_read_url_body,
                "read_url",
            ),
            UiTestTags.ABOUT_MODEL_TOOL_SEARCH_KNOWLEDGE to Triple(
                xyz.mek030399.tokenflow.R.string.about_model_tool_search_knowledge_title,
                xyz.mek030399.tokenflow.R.string.about_model_tool_search_knowledge_body,
                "search_knowledge",
            ),
            UiTestTags.ABOUT_MODEL_TOOL_CALCULATE to Triple(
                xyz.mek030399.tokenflow.R.string.about_model_tool_calculate_title,
                xyz.mek030399.tokenflow.R.string.about_model_tool_calculate_body,
                "calculate",
            ),
            UiTestTags.ABOUT_MODEL_TOOL_CONVERT_UNITS to Triple(
                xyz.mek030399.tokenflow.R.string.about_model_tool_convert_units_title,
                xyz.mek030399.tokenflow.R.string.about_model_tool_convert_units_body,
                "convert_units",
            ),
        ).forEach { (tag, resourcesAndId) ->
            val (titleResource, descriptionResource, rawId) = resourcesAndId
            about.performScrollToNode(hasTestTag(tag))
            composeRule.onNodeWithTag(tag).assertIsDisplayed()
            val localizedTitle = context.getString(titleResource)
            about.performScrollToNode(hasText(localizedTitle))
            composeRule.onNodeWithText(localizedTitle).assertIsDisplayed()
            about.performScrollToNode(hasText(rawId))
            composeRule.onNodeWithText(rawId).assertIsDisplayed()
            val localizedDescription = context.getString(descriptionResource)
            about.performScrollToNode(hasText(localizedDescription))
            composeRule.onNodeWithText(localizedDescription).assertIsDisplayed()
        }

        about.performScrollToNode(hasText("Jetpack Compose / Material 3"))
        composeRule.onNodeWithText("Jetpack Compose / Material 3").assertIsDisplayed()
        about.performScrollToNode(hasText("commonmark-java"))
        composeRule.onNodeWithText("commonmark-java").assertIsDisplayed()
        about.performScrollToNode(hasTestTag(UiTestTags.ABOUT_THIRD_PARTY_NOTICES))
        composeRule.onNodeWithTag(UiTestTags.ABOUT_THIRD_PARTY_NOTICES).assertIsDisplayed()
        about.performScrollToNode(hasTestTag(UiTestTags.ABOUT_USER_AGREEMENT))
        composeRule.onNodeWithTag(UiTestTags.ABOUT_USER_AGREEMENT).assertIsDisplayed()
    }

    @Test
    fun aboutKeyLinksOpenOfficialPages() {
        val fake = UiFakeDataSource(withModel = true)
        val viewModel = AppViewModel(fake)
        val openedUris = mutableListOf<String>()
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                openedUris += uri
            }
        }
        composeRule.setContent {
            TokenFlowTheme {
                CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                    TokenFlowApp(viewModel)
                }
            }
        }
        composeRule.waitUntil(5_000) { fake.initialized }

        viewModel.openScreen(AppScreen.ABOUT)
        val about = composeRule.onNodeWithTag(UiTestTags.ABOUT_SCREEN)
        about.performScrollToNode(hasTestTag(UiTestTags.ABOUT_EXA_KEY_LINK))
        composeRule.onNodeWithTag(UiTestTags.ABOUT_EXA_KEY_LINK).performClick()
        about.performScrollToNode(hasTestTag(UiTestTags.ABOUT_MIMO_KEY_LINK))
        composeRule.onNodeWithTag(UiTestTags.ABOUT_MIMO_KEY_LINK).performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(EXA_API_KEY_URL, MIMO_API_KEY_URL), openedUris)
        }
    }

    @Test
    fun userAgreementOpensAndBackReturnsToAbout() {
        val fake = UiFakeDataSource(withModel = true)
        val viewModel = AppViewModel(fake)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val agreement = context.resources.openRawResource(xyz.mek030399.tokenflow.R.raw.user_agreement)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val versionText = if ("协议版本：1.1" in agreement) "协议版本：1.1" else "Agreement version: 1.1"
        val contactHeading = if ("## 14. 联系方式" in agreement) "14. 联系方式" else "14. Contact"
        composeRule.setContent { TokenFlowTheme { TokenFlowApp(viewModel) } }
        composeRule.waitUntil(5_000) { fake.initialized }

        viewModel.openScreen(AppScreen.ABOUT)
        composeRule.onNodeWithTag(UiTestTags.ABOUT_SCREEN)
            .performScrollToNode(hasTestTag(UiTestTags.ABOUT_USER_AGREEMENT))
        composeRule.onNodeWithTag(UiTestTags.ABOUT_USER_AGREEMENT).performClick()

        composeRule.onNodeWithTag(UiTestTags.USER_AGREEMENT_SCREEN).assertIsDisplayed()
        composeRule.onNodeWithText(versionText).assertIsDisplayed()
        composeRule.onNodeWithText(contactHeading).performScrollTo().assertIsDisplayed()

        pressBack()
        composeRule.onNodeWithTag(UiTestTags.ABOUT_SCREEN).assertIsDisplayed()
        composeRule.onAllNodesWithTag(UiTestTags.USER_AGREEMENT_SCREEN).assertCountEquals(0)

        pressBack()
        composeRule.waitUntil(5_000) { viewModel.state.value.screen == AppScreen.CHAT }
        composeRule.onNodeWithTag(UiTestTags.MESSAGE_INPUT).assertIsDisplayed()
    }

    @Test
    fun thirdPartyNoticesOpenOfflineAndBackReturnsToAbout() {
        val fake = UiFakeDataSource(withModel = true)
        val viewModel = AppViewModel(fake)
        composeRule.setContent { TokenFlowTheme { TokenFlowApp(viewModel) } }
        composeRule.waitUntil(5_000) { fake.initialized }

        viewModel.openScreen(AppScreen.ABOUT)
        composeRule.onNodeWithTag(UiTestTags.ABOUT_SCREEN)
            .performScrollToNode(hasTestTag(UiTestTags.ABOUT_THIRD_PARTY_NOTICES))
        composeRule.onNodeWithTag(UiTestTags.ABOUT_THIRD_PARTY_NOTICES).performClick()

        composeRule.onNodeWithTag(UiTestTags.THIRD_PARTY_NOTICES_SCREEN).assertIsDisplayed()
        composeRule.onNodeWithText("Apache License 2.0 components").performScrollTo().assertIsDisplayed()

        pressBack()
        composeRule.onNodeWithTag(UiTestTags.ABOUT_SCREEN).assertIsDisplayed()
        composeRule.onAllNodesWithTag(UiTestTags.THIRD_PARTY_NOTICES_SCREEN).assertCountEquals(0)
    }

    @Test
    fun bookmarksAndNotesSupportSelectAllAndBatchDelete() {
        val fake = UiFakeDataSource(withModel = true).apply {
            bookmarks += BookmarkedMessage(messageId = "message-1", conversationId = "conversation-1", conversationTitle = "First saved chat", content = "First saved answer")
            bookmarks += BookmarkedMessage(messageId = "message-2", conversationId = "conversation-1", conversationTitle = "Second saved chat", content = "Second saved answer")
            notes += Note(id = "note-1", title = "First note", body = "Body one")
            notes += Note(id = "note-2", title = "Second note", body = "Body two")
        }
        val viewModel = AppViewModel(fake)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent { TokenFlowTheme { TokenFlowApp(viewModel) } }
        composeRule.waitUntil(5_000) { fake.initialized }

        viewModel.openScreen(AppScreen.BOOKMARKS)
        composeRule.onNodeWithText("First saved chat").assertIsDisplayed()
        composeRule.onAllNodesWithText("First saved answer").assertCountEquals(0)
        composeRule.onNodeWithTag(UiTestTags.bookmarkItem("message-1")).performTouchInput { longClick() }
        composeRule.onNodeWithTag(UiTestTags.WORKSPACE_SELECT_ALL).performClick()
        composeRule.onNodeWithTag(UiTestTags.WORKSPACE_DELETE_SELECTED).performClick()
        composeRule.onNodeWithText(context.getString(xyz.mek030399.tokenflow.R.string.delete)).performClick()
        composeRule.waitUntil(5_000) { fake.bookmarks.isEmpty() }

        viewModel.openScreen(AppScreen.NOTES)
        composeRule.onNodeWithTag(UiTestTags.noteItem("note-1")).performTouchInput { longClick() }
        composeRule.onNodeWithTag(UiTestTags.WORKSPACE_SELECT_ALL).performClick()
        composeRule.onNodeWithTag(UiTestTags.WORKSPACE_DELETE_SELECTED).performClick()
        composeRule.onNodeWithText(context.getString(xyz.mek030399.tokenflow.R.string.delete)).performClick()
        composeRule.waitUntil(5_000) { fake.notes.isEmpty() }
    }

    @Test
    fun compactBookmarkCanBeRemovedFromItsTrailingIcon() {
        val fake = UiFakeDataSource(withModel = true).apply {
            bookmarks += BookmarkedMessage(
                messageId = "message-compact",
                conversationId = "conversation-1",
                conversationTitle = "Compact saved chat",
                content = "Hidden saved answer",
            )
        }
        val viewModel = AppViewModel(fake)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent { TokenFlowTheme { TokenFlowApp(viewModel) } }
        composeRule.waitUntil(5_000) { fake.initialized }

        viewModel.openScreen(AppScreen.BOOKMARKS)
        composeRule.onNodeWithText("Compact saved chat").assertIsDisplayed()
        composeRule.onAllNodesWithText("Hidden saved answer").assertCountEquals(0)
        composeRule.onNodeWithContentDescription(context.getString(xyz.mek030399.tokenflow.R.string.remove_bookmark)).performClick()

        composeRule.waitUntil(5_000) { fake.bookmarks.isEmpty() }
    }

    @Test
    fun existingNoteOpensRenderedReaderAndEditIsExplicit() {
        val fake = UiFakeDataSource(withModel = true).apply {
            notes += Note(
                id = "note-rich",
                title = "Rich note",
                body = "# Rendered heading\n\n<mark>Jade highlight</mark> and **strong text**",
            )
        }
        val viewModel = AppViewModel(fake)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent { TokenFlowTheme { TokenFlowApp(viewModel) } }
        composeRule.waitUntil(5_000) { fake.initialized }

        viewModel.openScreen(AppScreen.NOTES)
        composeRule.onNodeWithTag(UiTestTags.NOTE_IMPORT_MARKDOWN).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            context.getString(xyz.mek030399.tokenflow.R.string.import_markdown_note),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTestTags.noteItem("note-rich")).performClick()

        composeRule.onNodeWithTag("note_reader").assertIsDisplayed()
        composeRule.onNodeWithText("Rendered heading").assertIsDisplayed()
        composeRule.onNodeWithText("Jade highlight and strong text").assertIsDisplayed()
        composeRule.onAllNodesWithTag("note_editor").assertCountEquals(0)

        val readerTitleLayouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithTag(UiTestTags.NOTE_READER_TITLE)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(readerTitleLayouts) }
        with(readerTitleLayouts.single().layoutInput) {
            assertEquals(11.sp, style.fontSize)
            assertEquals(14.sp, style.lineHeight)
            assertEquals(1, maxLines)
            assertEquals(TextOverflow.Ellipsis, overflow)
        }

        val summarize = composeRule.onNodeWithTag("note_reader_summarize").fetchSemanticsNode().boundsInRoot
        val export = composeRule.onNodeWithTag(UiTestTags.NOTE_EXPORT_MARKDOWN).fetchSemanticsNode().boundsInRoot
        val edit = composeRule.onNodeWithTag("note_reader_edit").fetchSemanticsNode().boundsInRoot
        assertTrue(summarize.center.x < export.center.x && export.center.x < edit.center.x)
        composeRule.onNodeWithTag("note_reader_import_knowledge").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            context.getString(xyz.mek030399.tokenflow.R.string.export_markdown_note),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("note_reader_summarize").performClick()
        composeRule.onNodeWithText("Model A").assertIsDisplayed()
        pressBack()

        composeRule.onNodeWithTag("note_reader_edit").performClick()
        composeRule.onNodeWithTag("note_editor").assertIsDisplayed()
        composeRule.onAllNodesWithTag("note_reader").assertCountEquals(0)
    }

    @Test
    fun markdownNoteActionsFitPhoneToolbarAndImportBusyDisablesPicker() {
        val gate = CompletableDeferred<Unit>()
        val fake = UiFakeDataSource(withModel = true).apply {
            saveNoteGate = gate
            notes += Note(
                id = "note-toolbar",
                title = "A deliberately long Markdown note title for a narrow phone",
                body = "Toolbar body",
            )
        }
        val viewModel = AppViewModel(fake)
        setAppAt(viewModel, PHONE_SIZE)
        composeRule.waitUntil(5_000) { fake.initialized }

        viewModel.openScreen(AppScreen.NOTES)
        viewModel.importMarkdownNote(ImportedMarkdownNote("Imported", "Imported body"))
        composeRule.waitUntil(5_000) { viewModel.state.value.noteFileImporting }
        composeRule.onNodeWithTag(UiTestTags.NOTE_IMPORT_MARKDOWN)
            .assertIsDisplayed()
            .assertIsNotEnabled()

        gate.complete(Unit)
        composeRule.waitUntil(5_000) {
            !viewModel.state.value.noteFileImporting && fake.notes.any { it.title == "Imported" }
        }
        composeRule.onNodeWithTag(UiTestTags.noteItem("note-toolbar")).performClick()

        val title = composeRule.onNodeWithTag(UiTestTags.NOTE_READER_TITLE)
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val knowledge = composeRule.onNodeWithTag("note_reader_import_knowledge")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithTag("note_reader_summarize").assertIsDisplayed()
        composeRule.onNodeWithTag(UiTestTags.NOTE_EXPORT_MARKDOWN).assertIsDisplayed()
        composeRule.onNodeWithTag("note_reader_edit").assertIsDisplayed()
        assertTrue(title.right <= knowledge.left)
    }

    @Test
    fun noteKnowledgeImportRequiresConfirmationAndDisablesAfterSuccess() {
        val fake = UiFakeDataSource(withModel = true).apply {
            notes += Note(id = "note-knowledge", title = "Knowledge note", body = "Independent body")
        }
        val viewModel = AppViewModel(fake)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent { TokenFlowTheme { TokenFlowApp(viewModel) } }
        composeRule.waitUntil(5_000) { fake.initialized }

        viewModel.openScreen(AppScreen.NOTES)
        composeRule.onNodeWithTag(UiTestTags.noteItem("note-knowledge")).performClick()
        composeRule.onNodeWithTag("note_reader_import_knowledge").performClick()

        composeRule.onNodeWithTag("note_import_knowledge_dialog").assertIsDisplayed()
        assertEquals(0, fake.noteKnowledgeImportCalls)
        composeRule.onNodeWithText(context.getString(xyz.mek030399.tokenflow.R.string.cancel)).performClick()
        composeRule.onAllNodesWithTag("note_import_knowledge_dialog").assertCountEquals(0)
        assertEquals(0, fake.noteKnowledgeImportCalls)

        composeRule.onNodeWithTag("note_reader_import_knowledge").performClick()
        composeRule.onNodeWithTag("note_import_knowledge_confirm").performClick()
        composeRule.waitUntil(5_000) {
            fake.noteKnowledgeImportCalls == 1 && viewModel.state.value.knowledgeDocuments.size == 1
        }

        composeRule.onNodeWithTag("note_reader_import_knowledge").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(
            context.getString(xyz.mek030399.tokenflow.R.string.note_saved_to_knowledge),
        ).assertIsDisplayed()
        assertEquals("note-knowledge", viewModel.state.value.knowledgeDocuments.single().sourceNoteId)
    }

    @Test
    fun noteRewriteDialogUsesPromptAndKeepsCompactTitleOnOneLine() {
        val fake = UiFakeDataSource(withModel = true).apply {
            notes += Note(id = "note-rewrite", title = "Rewrite note", body = "Original body")
        }
        val viewModel = AppViewModel(fake)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent { TokenFlowTheme { TokenFlowApp(viewModel) } }
        composeRule.waitUntil(5_000) { fake.initialized }

        viewModel.openScreen(AppScreen.NOTES)
        composeRule.onNodeWithTag(UiTestTags.noteItem("note-rewrite")).performClick()
        composeRule.onNodeWithTag("note_reader_summarize").performClick()

        val titleLayouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithTag("note_rewrite_dialog_title")
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(titleLayouts) }
        assertEquals(1, titleLayouts.single().lineCount)
        assertTrue(titleLayouts.single().layoutInput.style.fontSize.value <= 20f)

        val promptField = composeRule.onNodeWithTag("note_rewrite_prompt")
        composeRule.onNodeWithContentDescription(
            context.getString(xyz.mek030399.tokenflow.R.string.note_rewrite_prompt),
        ).assertIsDisplayed()
        val placeholder = composeRule.onNodeWithTag(UiTestTags.NOTE_REWRITE_PLACEHOLDER, useUnmergedTree = true)
        val placeholderLayouts = mutableListOf<TextLayoutResult>()
        placeholder.assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(placeholderLayouts) }
        assertEquals(TextAlign.Center, placeholderLayouts.single().layoutInput.style.textAlign)
        assertTrue(abs(placeholder.fetchSemanticsNode().boundsInRoot.center.x - promptField.fetchSemanticsNode().boundsInRoot.center.x) <= 2f)

        promptField.performTextInput("Keep every source link")
        composeRule.onNodeWithContentDescription(
            context.getString(xyz.mek030399.tokenflow.R.string.note_rewrite_prompt),
        ).assertIsDisplayed()
        composeRule.onAllNodesWithTag(UiTestTags.NOTE_REWRITE_PLACEHOLDER, useUnmergedTree = true).assertCountEquals(0)
        val inputLayouts = mutableListOf<TextLayoutResult>()
        promptField.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(inputLayouts) }
        val inputLayout = inputLayouts.single()
        assertEquals(TextAlign.Center, inputLayout.layoutInput.style.textAlign)
        val firstLineCenter = (inputLayout.getLineLeft(0) + inputLayout.getLineRight(0)) / 2f
        assertTrue(abs(firstLineCenter - inputLayout.size.width / 2f) <= 2f)
        composeRule.onNodeWithText("Model A").performClick()
        composeRule.waitUntil(5_000) { fake.noteSummaryPrompt == "Keep every source link" }
        assertEquals(fake.model.id, fake.noteSummaryModelId)

        composeRule.onNodeWithTag("note_reader_summarize").performClick()
        val editableText = composeRule.onNodeWithTag("note_rewrite_prompt")
            .fetchSemanticsNode().config[SemanticsProperties.EditableText]
        assertEquals("", editableText.text)
    }

    @Test
    fun notesListShowsOnlyTitlesWhileBodyRemainsSearchableAndReadable() {
        val body = "Private preview body with body-only-key"
        val fake = UiFakeDataSource(withModel = true).apply {
            notes += Note(id = "note-title-only", title = "Visible note title", body = body)
            notes += Note(id = "note-unmatched", title = "Unmatched note", body = "Different searchable content")
        }
        val viewModel = AppViewModel(fake)
        composeRule.setContent { TokenFlowTheme { TokenFlowApp(viewModel) } }
        composeRule.waitUntil(5_000) { fake.initialized }

        viewModel.openScreen(AppScreen.NOTES)
        composeRule.onNodeWithText("Visible note title").assertIsDisplayed()
        composeRule.onAllNodes(hasText("Private preview body", substring = true)).assertCountEquals(0)

        composeRule.onNodeWithTag(UiTestTags.NOTES_SEARCH).performTextInput("body-only-key")
        composeRule.onNodeWithTag(UiTestTags.noteItem("note-title-only")).assertIsDisplayed()
        composeRule.onAllNodesWithTag(UiTestTags.noteItem("note-unmatched")).assertCountEquals(0)
        composeRule.onNodeWithText("Visible note title").assertIsDisplayed()
        composeRule.onAllNodes(hasText("Private preview body", substring = true)).assertCountEquals(0)

        composeRule.onNodeWithTag(UiTestTags.noteItem("note-title-only")).performClick()
        composeRule.onNodeWithTag("note_reader").assertIsDisplayed()
        composeRule.onNodeWithText(body).assertIsDisplayed()
    }

    @Test
    fun knowledgeReadyDocumentPreviewsFullScreenAndDeleteDoesNotOpenRows() {
        val ready = KnowledgeDocument(
            id = "knowledge-ready",
            name = "guide.md",
            mimeType = "text/markdown",
            storedPath = "knowledge/guide.md",
            sizeBytes = 5_120,
            chunkCount = 3,
        )
        val missing = ready.copy(id = "knowledge-missing", name = "missing.md")
        val indexing = ready.copy(id = "knowledge-indexing", name = "indexing.txt", status = "indexing")
        val failed = ready.copy(id = "knowledge-error", name = "failed.pdf", status = "error", error = "Broken PDF")
        val fake = UiFakeDataSource(withModel = true).apply {
            knowledgeDocuments += listOf(ready, missing, indexing, failed)
            knowledgePreviews[ready.id] = KnowledgeDocumentPreview(
                documentId = ready.id,
                documentName = ready.name,
                extension = "md",
                text = "# Preview heading\n\nUnique knowledge preview body",
                truncated = true,
            )
        }
        val viewModel = AppViewModel(fake)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent { TokenFlowTheme { TokenFlowApp(viewModel) } }
        composeRule.waitUntil(5_000) { fake.initialized }

        viewModel.openScreen(AppScreen.KNOWLEDGE)
        composeRule.onNodeWithTag(UiTestTags.knowledgeDocument(ready.id)).assertHasClickAction()
        composeRule.onNodeWithTag(UiTestTags.knowledgeDocument(missing.id)).assertHasClickAction()
        composeRule.onNodeWithTag(UiTestTags.knowledgeDocument(indexing.id)).assertHasNoClickAction()
        composeRule.onNodeWithTag(UiTestTags.knowledgeDocument(failed.id)).assertHasNoClickAction()

        composeRule.onNodeWithTag(UiTestTags.knowledgeDocument(ready.id)).performClick()
        composeRule.waitUntil(5_000) { viewModel.state.value.knowledgePreview is KnowledgePreviewState.Ready }
        composeRule.onNodeWithTag(UiTestTags.KNOWLEDGE_PREVIEW).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTestTags.KNOWLEDGE_PREVIEW_MARKDOWN).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTestTags.KNOWLEDGE_PREVIEW_TRUNCATED).assertIsDisplayed()
        composeRule.onNodeWithText("Preview heading").assertIsDisplayed()
        composeRule.onNodeWithText("Unique knowledge preview body").assertIsDisplayed()
        composeRule.onAllNodesWithTag(UiTestTags.knowledgeDocument(ready.id)).assertCountEquals(0)

        pressBack()
        composeRule.waitUntil(5_000) { viewModel.state.value.knowledgePreview is KnowledgePreviewState.Closed }
        composeRule.onNodeWithTag(UiTestTags.knowledgeDocument(ready.id)).assertIsDisplayed()

        composeRule.onNodeWithTag(UiTestTags.knowledgeDocument(missing.id)).performClick()
        composeRule.waitUntil(5_000) { viewModel.state.value.knowledgePreview is KnowledgePreviewState.Error }
        composeRule.onNodeWithTag(UiTestTags.KNOWLEDGE_PREVIEW_ERROR).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(xyz.mek030399.tokenflow.R.string.knowledge_preview_unavailable)).assertIsDisplayed()
        pressBack()
        composeRule.waitUntil(5_000) { viewModel.state.value.knowledgePreview is KnowledgePreviewState.Closed }

        val previewCallsBeforeDelete = fake.knowledgePreviewCalls
        composeRule.onNodeWithTag(UiTestTags.knowledgeDelete(ready.id)).performClick()
        composeRule.waitUntil(5_000) { ready.id in fake.deletedKnowledgeIds }
        composeRule.onAllNodesWithTag(UiTestTags.knowledgeDocument(ready.id)).assertCountEquals(0)
        assertEquals(previewCallsBeforeDelete, fake.knowledgePreviewCalls)
        assertTrue(viewModel.state.value.knowledgePreview is KnowledgePreviewState.Closed)
    }

    @Test
    fun systemBackLeavesNoteReaderBeforeReturningToChat() {
        val fake = UiFakeDataSource(withModel = true).apply {
            notes += Note(id = "note-back", title = "Back navigation", body = "Rendered body")
        }
        val viewModel = AppViewModel(fake)
        composeRule.setContent { TokenFlowTheme { TokenFlowApp(viewModel) } }
        composeRule.waitUntil(5_000) { fake.initialized }

        viewModel.openScreen(AppScreen.NOTES)
        composeRule.onNodeWithTag(UiTestTags.noteItem("note-back")).performClick()
        composeRule.onNodeWithTag("note_reader").assertIsDisplayed()

        pressBack()
        composeRule.onNodeWithTag(UiTestTags.noteItem("note-back")).assertIsDisplayed()
        assertEquals(AppScreen.NOTES, viewModel.state.value.screen)

        pressBack()
        composeRule.waitUntil(5_000) { viewModel.state.value.screen == AppScreen.CHAT }
        composeRule.onNodeWithTag(UiTestTags.MESSAGE_INPUT).assertIsDisplayed()
    }

    @Test
    fun newNoteOpensEditorDirectly() {
        val fake = UiFakeDataSource(withModel = true)
        val viewModel = AppViewModel(fake)
        composeRule.setContent { TokenFlowTheme { TokenFlowApp(viewModel) } }
        composeRule.waitUntil(5_000) { fake.initialized }

        viewModel.openScreen(AppScreen.NOTES)
        composeRule.onNodeWithTag("note_create").performClick()

        composeRule.onNodeWithTag("note_editor").assertIsDisplayed()
        composeRule.onAllNodesWithTag("note_reader").assertCountEquals(0)
    }

    @Test
    fun attachmentMenuCanAttachANoteSnapshot() {
        val noteBody = "Independent note body\n\nWith the complete Markdown snapshot."
        val fake = UiFakeDataSource(withModel = true).apply {
            notes += Note(id = "note-other", title = "Chat context", body = "Other private body")
            notes += Note(id = "note-chat", title = "Chat context", body = noteBody)
        }
        val viewModel = AppViewModel(fake)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent { TokenFlowTheme { TokenFlowApp(viewModel) } }
        composeRule.waitUntil(5_000) { fake.initialized }

        composeRule.onNodeWithContentDescription(context.getString(xyz.mek030399.tokenflow.R.string.add_attachment)).performClick()
        composeRule.onNodeWithText(context.getString(xyz.mek030399.tokenflow.R.string.import_note)).performClick()
        val otherItem = composeRule.onNodeWithTag(UiTestTags.noteImportItem("note-other"))
            .assertIsDisplayed()
        val chatItem = composeRule.onNodeWithTag(UiTestTags.noteImportItem("note-chat"))
            .assertIsDisplayed()
        assertNodeWithin(
            composeRule.onNodeWithTag(UiTestTags.noteImportTitle("note-other"))
                .assertIsDisplayed()
                .assertTextEquals("Chat context"),
            otherItem,
        )
        assertNodeWithin(
            composeRule.onNodeWithTag(UiTestTags.noteImportTitle("note-chat"))
                .assertIsDisplayed()
                .assertTextEquals("Chat context"),
            chatItem,
        )
        composeRule.onAllNodesWithText("Other private body").assertCountEquals(0)
        composeRule.onAllNodesWithText(noteBody).assertCountEquals(0)

        chatItem.performClick()

        composeRule.waitUntil(5_000) { viewModel.state.value.pendingAttachments.size == 1 }
        val attachment = viewModel.state.value.pendingAttachments.single()
        assertEquals("Chat context.md", attachment.displayName)
        assertEquals(noteBody, attachment.inlineText)
        assertEquals(noteBody.toByteArray(Charsets.UTF_8).size.toLong(), attachment.sizeBytes)
        composeRule.onNodeWithText("Chat context.md").assertIsDisplayed()
    }

    @Test
    fun noteImportPickerShowsEmptyState() {
        val fake = UiFakeDataSource(withModel = true)
        val viewModel = AppViewModel(fake)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent { TokenFlowTheme { TokenFlowApp(viewModel) } }
        composeRule.waitUntil(5_000) { fake.initialized }

        composeRule.onNodeWithContentDescription(
            context.getString(xyz.mek030399.tokenflow.R.string.add_attachment),
        ).performClick()
        composeRule.onNodeWithText(
            context.getString(xyz.mek030399.tokenflow.R.string.import_note),
        ).performClick()

        composeRule.onNodeWithText(
            context.getString(xyz.mek030399.tokenflow.R.string.no_notes_to_import),
        ).assertIsDisplayed()
        val noteImportItemTagPrefix = UiTestTags.noteImportItem("")
        composeRule.onAllNodes(
            SemanticsMatcher("has note import item tag") { node ->
                runCatching { node.config[SemanticsProperties.TestTag] }.getOrNull()
                    ?.startsWith(noteImportItemTagPrefix) == true
            },
        ).assertCountEquals(0)
    }

    @Test
    fun noteImportPickerEllipsizesLongTitleOnPhoneAtLargeFont() {
        val longTitle = "A deliberately long note title that must remain on one line in the import picker ".repeat(4).trim()
        val noteId = "note-long-import-title"
        val fake = UiFakeDataSource(withModel = true).apply {
            notes += Note(id = noteId, title = longTitle, body = "Hidden note body")
        }
        val viewModel = AppViewModel(fake)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        setAppAt(viewModel, PHONE_SIZE, fontScale = 1.5f)
        composeRule.waitUntil(5_000) { fake.initialized }

        composeRule.onNodeWithContentDescription(
            context.getString(xyz.mek030399.tokenflow.R.string.add_attachment),
        ).performClick()
        composeRule.onNodeWithText(
            context.getString(xyz.mek030399.tokenflow.R.string.import_note),
        ).performClick()

        val item = composeRule.onNodeWithTag(UiTestTags.noteImportItem(noteId)).assertIsDisplayed()
        val title = composeRule.onNodeWithTag(UiTestTags.noteImportTitle(noteId))
            .assertIsDisplayed()
            .assertTextEquals(longTitle)
        val layoutResults = mutableListOf<TextLayoutResult>()
        title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            action(layoutResults)
        }
        val layoutResult = checkNotNull(layoutResults.singleOrNull()) {
            "Expected one text layout result for the note import title"
        }

        assertNodeWithin(title, item)
        assertEquals(1, layoutResult.lineCount)
        assertTrue(layoutResult.hasVisualOverflow)
        assertTrue(layoutResult.isLineEllipsized(0))
        composeRule.onAllNodesWithText("Hidden note body").assertCountEquals(0)
    }

    @Test
    fun chatSettingsExposeTypographyControlsAndLineSpacingRange() {
        val fake = UiFakeDataSource(withModel = true)
        val viewModel = AppViewModel(fake)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(DISPLAY_PREFERENCES, Context.MODE_PRIVATE)
        val hadOriginalLineSpacing = preferences.contains(LINE_SPACING_PREFERENCE)
        val originalLineSpacing = preferences.getFloat(LINE_SPACING_PREFERENCE, 1f)
        preferences.edit().putFloat(LINE_SPACING_PREFERENCE, 1f).commit()

        try {
            composeRule.setContent { TokenFlowTheme { TokenFlowApp(viewModel) } }
            composeRule.waitUntil(5_000) { fake.initialized }

            composeRule.onNodeWithTag(UiTestTags.SETTINGS).performClick()

            composeRule.onNodeWithTag(UiTestTags.CHAT_FONT_SIZE).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag(UiTestTags.CHAT_LETTER_SPACING).performScrollTo().assertIsDisplayed()
            val lineSpacing = composeRule.onNodeWithTag(UiTestTags.CHAT_LINE_SPACING)
                .performScrollTo()
                .assertIsDisplayed()
            val rangeInfo = lineSpacing.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
            assertEquals(0.2f, rangeInfo.range.start, 0f)
            assertEquals(1f, rangeInfo.range.endInclusive, 0f)
            assertEquals(7, rangeInfo.steps)

            lineSpacing.performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                setProgress(0.2f)
            }
            composeRule.onNodeWithText("${context.getString(xyz.mek030399.tokenflow.R.string.chat_line_spacing)}: 20%")
                .assertIsDisplayed()

            lineSpacing.performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                setProgress(1f)
            }
            composeRule.onNodeWithText("${context.getString(xyz.mek030399.tokenflow.R.string.chat_line_spacing)}: 100%")
                .assertIsDisplayed()
        } finally {
            preferences.edit().apply {
                if (hadOriginalLineSpacing) putFloat(LINE_SPACING_PREFERENCE, originalLineSpacing)
                else remove(LINE_SPACING_PREFERENCE)
            }.commit()
        }
    }

    @Test
    fun clearContextKeepsHistoryAndAddsDivider() {
        val conversation = Conversation(id = "conversation-context", title = "Context", model = "model-1")
        val fake = UiFakeDataSource(withModel = true).apply {
            conversations += conversation
            seedMessages(conversation.id, listOf(ChatMessage(
                id = "old-message",
                conversationId = conversation.id,
                role = "user",
                content = "Visible history",
            )))
        }
        val viewModel = AppViewModel(fake)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent { TokenFlowTheme { TokenFlowApp(viewModel) } }
        composeRule.waitUntil(5_000) { fake.initialized }
        viewModel.openConversation(conversation.id)
        composeRule.waitUntil(5_000) { viewModel.state.value.activeMessages.isNotEmpty() }

        composeRule.onNodeWithContentDescription(context.getString(xyz.mek030399.tokenflow.R.string.more_actions)).performClick()
        composeRule.onNodeWithText(context.getString(xyz.mek030399.tokenflow.R.string.clear_context)).performClick()
        composeRule.onNodeWithText(context.getString(xyz.mek030399.tokenflow.R.string.confirm)).performClick()

        composeRule.waitUntil(5_000) { viewModel.state.value.activeMessages.any { it.role == CONTEXT_BOUNDARY_ROLE } }
        composeRule.onNodeWithText("Visible history").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(xyz.mek030399.tokenflow.R.string.context_cleared)).assertIsDisplayed()
    }

    @Test
    fun verifiedKnowledgeCitationOpensPreviewAndProcessShowsRetrieval() {
        val conversation = Conversation(id = "conversation-citation", title = "Citation", model = "model-1")
        val citation = KnowledgeCitation(
            chunkId = 42,
            documentId = "document-1",
            documentName = "pricing.md",
            position = 0,
        )
        val retrievalOnlyCitation = KnowledgeCitation(
            chunkId = 999,
            documentId = "document-not-injected",
            documentName = "not-injected.md",
            position = 1,
        )
        val process = ProcessEvent(
            type = "knowledge_retrieval",
            id = "knowledge-request-1",
            messageKey = "knowledge_retrieval_hits",
            knowledgeCitations = listOf(citation, retrievalOnlyCitation),
        )
        val assistant = ChatMessage(
            id = "assistant-citation",
            conversationId = conversation.id,
            role = "assistant",
            content = "[[KB:42]]\n\n[[KB:999]]",
            metadata = DirectApiTransport.defaultJson.encodeToString(
                AssistantMetadata(events = listOf(process), knowledgeCitations = listOf(citation)),
            ),
            status = "completed",
        )
        val fake = UiFakeDataSource(withModel = true).apply {
            conversations += conversation
            knowledgeSnippets += KnowledgeSnippet(
                chunkId = citation.chunkId,
                documentId = citation.documentId,
                documentName = citation.documentName,
                position = citation.position,
                text = "Cached input costs two yuan per million tokens.",
            )
            seedMessages(conversation.id, listOf(assistant))
        }
        val viewModel = AppViewModel(fake)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent { TokenFlowTheme { TokenFlowApp(viewModel) } }
        composeRule.waitUntil(5_000) { fake.initialized }
        viewModel.openConversation(conversation.id)
        composeRule.waitUntil(5_000) { viewModel.state.value.activeMessages.isNotEmpty() }

        val citationLabel = "pricing.md · ${context.getString(xyz.mek030399.tokenflow.R.string.knowledge_source_chunk, 1)}"
        composeRule.onNodeWithText(citationLabel).assertIsDisplayed().performClickOnText(citationLabel)
        composeRule.waitUntil(5_000) { viewModel.state.value.knowledgeSourcePreview != null }
        composeRule.waitUntil(5_000) {
            runCatching {
                composeRule.onAllNodesWithTag(UiTestTags.KNOWLEDGE_SOURCE_PREVIEW)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        composeRule.onNodeWithTag(UiTestTags.KNOWLEDGE_SOURCE_PREVIEW).assertIsDisplayed()
        composeRule.onNodeWithText("Cached input costs two yuan per million tokens.").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(xyz.mek030399.tokenflow.R.string.close)).performClick()
        composeRule.waitUntil(5_000) {
            viewModel.state.value.knowledgeSourcePreview == null &&
                runCatching {
                    composeRule.onAllNodesWithTag(UiTestTags.KNOWLEDGE_SOURCE_PREVIEW)
                        .fetchSemanticsNodes()
                        .isEmpty()
                }.getOrDefault(false)
        }

        composeRule.runOnIdle {
            fake.knowledgeSnippets.clear()
            viewModel.openKnowledgeCitation(citation.chunkId)
        }
        val unavailable = context.getString(xyz.mek030399.tokenflow.R.string.knowledge_source_unavailable)
        composeRule.waitUntil(5_000) {
            runCatching {
                composeRule.onAllNodesWithText(unavailable).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        composeRule.onNodeWithText(unavailable).assertIsDisplayed()
        assertEquals(null, viewModel.state.value.knowledgeSourcePreview)

        composeRule.onNodeWithText("[[KB:999]]").assertIsDisplayed().performClickOnText("[[KB:999]]")
        composeRule.waitForIdle()
        assertEquals(null, viewModel.state.value.knowledgeSourcePreview)

        composeRule.onNodeWithTag(UiTestTags.PROCESS_DETAILS).performClick()
        composeRule.onNodeWithText(
            context.getString(xyz.mek030399.tokenflow.R.string.knowledge_retrieval_hits, 2),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            "not-injected.md · ${context.getString(xyz.mek030399.tokenflow.R.string.knowledge_source_chunk, 2)}",
            substring = true,
        ).performScrollTo().assertIsDisplayed()
    }

    private companion object {
        const val DISPLAY_PREFERENCES = "tokenflow_display"
        const val LINE_SPACING_PREFERENCE = "chat_line_spacing"
        val PHONE_SIZE = DpSize(360.dp, 640.dp)
        val SHORT_TABLET_SIZE = DpSize(853.dp, 480.dp)
        val PORTRAIT_TABLET_SIZE = DpSize(600.dp, 960.dp)
        val WIDE_CHAT_SIZE = DpSize(1440.dp, 900.dp)
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalMaterial3Api::class)
    private fun setAppAt(
        viewModel: AppViewModel,
        size: DpSize,
        fontScale: Float = 1f,
        notificationAutoDismissMillis: Long = 10_000L,
        onLayoutEnvironment: (Density, Int) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                    val density = LocalDensity.current
                    onLayoutEnvironment(density, TopAppBarDefaults.windowInsets.getTop(density))
                    TokenFlowTheme {
                        TokenFlowApp(
                            viewModel = viewModel,
                            notificationAutoDismissMillis = notificationAutoDismissMillis,
                        )
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalMaterial3Api::class)
    private fun setLocalizedAppAt(
        viewModel: AppViewModel,
        size: DpSize,
        fontScale: Float,
        locale: Locale,
        nightMode: Int,
        onLayoutEnvironment: (Density, Int) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                    val baseContext = LocalContext.current
                    val configuration = Configuration(LocalConfiguration.current).apply {
                        setLocale(locale)
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
                    }
                    val localizedContext = baseContext.createConfigurationContext(configuration)
                    CompositionLocalProvider(
                        LocalConfiguration provides configuration,
                        LocalContext provides localizedContext,
                    ) {
                        val density = LocalDensity.current
                        onLayoutEnvironment(density, TopAppBarDefaults.windowInsets.getTop(density))
                        TokenFlowTheme { TokenFlowApp(viewModel) }
                    }
                }
            }
        }
    }

    private fun assertCompactChatTopBar(density: Density, statusBarTop: Int, expectMenu: Boolean) {
        val bar = composeRule.onNodeWithTag(UiTestTags.CHAT_TOP_BAR).assertIsDisplayed()
        val barBounds = bar.fetchSemanticsNode().boundsInRoot
        val expectedBarHeight = with(density) {
            maxOf(48.dp, 20.sp.toDp() + 14.sp.toDp() + 2.dp).toPx()
        }
        val minimumTouchTarget = with(density) { 48.dp.toPx() }
        assertEquals(expectedBarHeight, barBounds.height - statusBarTop, 1f)

        val titleLayouts = mutableListOf<TextLayoutResult>()
        val title = composeRule.onNodeWithTag(UiTestTags.CHAT_CONVERSATION_TITLE)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(titleLayouts) }
        val titleLayout = titleLayouts.single()
        with(titleLayout.layoutInput) {
            assertEquals(16.sp, style.fontSize)
            assertEquals(20.sp, style.lineHeight)
            assertEquals(0.sp, style.letterSpacing)
            assertEquals(1, maxLines)
            assertEquals(TextOverflow.Ellipsis, overflow)
        }
        assertEquals(1, titleLayout.lineCount)
        assertTrue(titleLayout.isLineEllipsized(0))

        val modelLayouts = mutableListOf<TextLayoutResult>()
        val model = composeRule.onNodeWithTag(UiTestTags.CHAT_MODEL_NAME)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(modelLayouts) }
        with(modelLayouts.single()) {
            assertEquals(14.sp, layoutInput.style.lineHeight)
            assertEquals(1, layoutInput.maxLines)
            assertEquals(TextOverflow.Ellipsis, layoutInput.overflow)
            assertEquals(1, lineCount)
            assertTrue(isLineEllipsized(0))
        }

        val titleBlock = composeRule.onNodeWithTag(UiTestTags.CHAT_TITLE_BLOCK).assertIsDisplayed()
        val tools = composeRule.onNodeWithTag(UiTestTags.CHAT_TOOLS).assertIsDisplayed().assertHasClickAction()
        val settings = composeRule.onNodeWithTag(UiTestTags.SETTINGS).assertIsDisplayed().assertHasClickAction()
        val more = composeRule.onNodeWithTag(UiTestTags.CHAT_MORE_ACTIONS).assertIsDisplayed().assertHasClickAction()
        val actionNodes = mutableListOf(tools, settings, more)

        assertNodeWithin(title, bar)
        assertNodeWithin(model, bar)
        actionNodes.forEach { action ->
            assertNodeWithin(action, bar)
            val bounds = action.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width >= minimumTouchTarget - 1f)
            assertTrue(bounds.height >= minimumTouchTarget - 1f)
        }

        if (expectMenu) {
            val menu = composeRule.onNodeWithTag(UiTestTags.OPEN_CONVERSATIONS)
                .assertIsDisplayed()
                .assertHasClickAction()
            val menuBounds = menu.fetchSemanticsNode().boundsInRoot
            assertNodeWithin(menu, bar)
            assertTrue(menuBounds.width >= minimumTouchTarget - 1f)
            assertTrue(menuBounds.height >= minimumTouchTarget - 1f)
            actionNodes.add(0, menu)
        } else {
            composeRule.onAllNodesWithTag(UiTestTags.OPEN_CONVERSATIONS).assertCountEquals(0)
        }

        val contentTop = barBounds.top + statusBarTop
        listOf(title, model, titleBlock).plus(actionNodes).forEach { node ->
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.top >= contentTop - 0.5f)
            assertTrue(bounds.bottom <= barBounds.bottom + 0.5f)
        }
        listOf(title, model, titleBlock).forEach { textNode ->
            actionNodes.forEach { actionNode -> assertNodesDoNotOverlap(textNode, actionNode) }
        }
        actionNodes.forEachIndexed { index, first ->
            actionNodes.drop(index + 1).forEach { second -> assertNodesDoNotOverlap(first, second) }
        }
    }
}

@Composable
private fun LogoSample(tag: String, theme: AppTheme, darkTheme: Boolean, size: Dp) {
    TokenFlowTheme(theme = theme, darkTheme = darkTheme) {
        Box(
            Modifier
                .size(size)
                .background(MaterialTheme.colorScheme.background)
                .testTag(tag),
        ) {
            AppLogo(size)
        }
    }
}

private data class LogoPixelBounds(
    val canvasWidth: Int,
    val canvasHeight: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

private fun SemanticsNodeInteraction.assertLogoPixels(
    background: Color,
    expectedFrame: Color,
): LogoPixelBounds {
    val pixels = assertIsDisplayed().captureToImage().toPixelMap()
    assertEquals(pixels.width, pixels.height)

    val framePixel = pixels[
        pixels.width / 2,
        (pixels.height * 0.24f).roundToInt().coerceIn(0, pixels.height - 1),
    ]
    assertTrue(
        "Door-frame sample did not use the expected theme color",
        colorDistanceSquared(framePixel, expectedFrame) <= 0.001f,
    )
    assertTrue(
        "Door-frame contrast was ${contrastRatio(framePixel, background)}",
        contrastRatio(framePixel, background) >= 3f,
    )

    var left = pixels.width
    var top = pixels.height
    var right = -1
    var bottom = -1
    var hasCyan = false
    var hasGreen = false
    for (y in 0 until pixels.height) {
        for (x in 0 until pixels.width) {
            val pixel = pixels[x, y]
            if (colorDistanceSquared(pixel, background) > 0.01f) {
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
            }
            hasCyan = hasCyan || (
                pixel.blue > pixel.green + 0.03f && pixel.green > pixel.red + 0.20f
            )
            hasGreen = hasGreen || (
                pixel.green > pixel.blue + 0.12f && pixel.green > pixel.red + 0.20f
            )
        }
    }

    assertTrue("Logo had no visible pixels", right >= left && bottom >= top)
    assertTrue("Logo was clipped horizontally", left > 0 && right < pixels.width - 1)
    assertTrue("Logo was clipped vertically", top > 0 && bottom < pixels.height - 1)
    assertTrue("Logo lost its cyan brand color", hasCyan)
    assertTrue("Logo lost its green brand color", hasGreen)
    return LogoPixelBounds(pixels.width, pixels.height, left, top, right, bottom)
}

private fun assertSameLogoGeometry(first: LogoPixelBounds, second: LogoPixelBounds) {
    assertEquals(first.canvasWidth, second.canvasWidth)
    assertEquals(first.canvasHeight, second.canvasHeight)
    assertTrue(abs(first.left - second.left) <= 1)
    assertTrue(abs(first.top - second.top) <= 1)
    assertTrue(abs(first.right - second.right) <= 1)
    assertTrue(abs(first.bottom - second.bottom) <= 1)
}

private fun colorDistanceSquared(first: Color, second: Color): Float {
    val red = first.red - second.red
    val green = first.green - second.green
    val blue = first.blue - second.blue
    return red * red + green * green + blue * blue
}

private fun contrastRatio(first: Color, second: Color): Float {
    val lighter = maxOf(first.luminance(), second.luminance())
    val darker = minOf(first.luminance(), second.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}

private fun assertNodeWithin(node: SemanticsNodeInteraction, container: SemanticsNodeInteraction) {
    val nodeBounds = node.fetchSemanticsNode().boundsInRoot
    val containerBounds = container.fetchSemanticsNode().boundsInRoot
    assertTrue(nodeBounds.left >= containerBounds.left - 0.5f)
    assertTrue(nodeBounds.top >= containerBounds.top - 0.5f)
    assertTrue(nodeBounds.right <= containerBounds.right + 0.5f)
    assertTrue(nodeBounds.bottom <= containerBounds.bottom + 0.5f)
}

private fun assertNodesDoNotOverlap(first: SemanticsNodeInteraction, second: SemanticsNodeInteraction) {
    val firstBounds = first.fetchSemanticsNode().boundsInRoot
    val secondBounds = second.fetchSemanticsNode().boundsInRoot
    val overlapWidth = minOf(firstBounds.right, secondBounds.right) - maxOf(firstBounds.left, secondBounds.left)
    val overlapHeight = minOf(firstBounds.bottom, secondBounds.bottom) - maxOf(firstBounds.top, secondBounds.top)
    assertTrue(overlapWidth <= 0.5f || overlapHeight <= 0.5f)
}

private fun SemanticsNodeInteraction.sampleSurfaceColor(): Color {
    val pixels = captureToImage().toPixelMap()
    return pixels[(pixels.width / 10).coerceAtLeast(1), pixels.height / 2]
}

private fun SemanticsNodeInteraction.performClickOnText(text: String): SemanticsNodeInteraction {
    val layoutResults = mutableListOf<TextLayoutResult>()
    performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layoutResults) }
    val layoutResult = checkNotNull(layoutResults.singleOrNull()) { "Expected one text layout result" }
    val offset = layoutResult.layoutInput.text.text.indexOf(text)
    check(offset >= 0) { "Text '$text' is missing from the layout" }
    return performTouchInput { click(layoutResult.getBoundingBox(offset).center) }
}

@Suppress("DEPRECATION")
private class FakeComposeClipboardManager : ComposeClipboardManager {
    private var value: AnnotatedString? = null

    val copiedText: String?
        get() = value?.text

    override fun setText(annotatedString: AnnotatedString) {
        value = annotatedString
    }

    override fun getText(): AnnotatedString? = value
}

private fun controlledToolFlow(
    conversationId: String,
    request: SendMessageRequest,
    toolName: String,
    partialContent: String?,
    toolStartGate: CompletableDeferred<Unit>? = null,
    toolTerminalGate: CompletableDeferred<Unit>,
    finishGate: CompletableDeferred<Unit>,
): Flow<ChatEvent> = flow {
    emit(ChatEvent.UserMessage(ChatMessage(
        id = "user",
        conversationId = conversationId,
        requestId = request.requestId,
        role = "user",
        content = request.content,
    )))
    emit(ChatEvent.AssistantMessage(ChatMessage(
        id = "assistant",
        conversationId = conversationId,
        requestId = request.requestId,
        role = "assistant",
        status = "generating",
    )))
    partialContent?.let { emit(ChatEvent.Delta(it)) }
    toolStartGate?.await()
    emit(ChatEvent.Process(ProcessEvent(
        type = "tool_started",
        id = "tool-call",
        name = toolName,
    )))
    toolTerminalGate.await()
    emit(ChatEvent.Process(ProcessEvent(
        type = "tool_completed",
        id = "tool-call",
        name = toolName,
    )))
    finishGate.await()
    emit(ChatEvent.Done(Usage(), false))
}

private class UiFakeDataSource(
    withModel: Boolean,
    private val withProvider: Boolean = withModel,
    modelDisplayName: String = "Model A",
    modelRemoteId: String = "model-a",
    assistantNickname: String = DEFAULT_ASSISTANT_NICKNAME,
) : ChatDataSource {
    val provider = ProviderConfig("provider-1", "Provider", "https://api.example.com/v1", ProviderProtocol.OPENAI_RESPONSES, true)
    val model = ModelProfile("model-1", provider.id, modelRemoteId, modelDisplayName, 4096, true)
    val conversations = mutableListOf<Conversation>()
    val bookmarks = mutableListOf<BookmarkedMessage>()
    val notes = mutableListOf<Note>()
    val knowledgeDocuments = mutableListOf<KnowledgeDocument>()
    val knowledgePreviews = mutableMapOf<String, KnowledgeDocumentPreview>()
    val knowledgeSnippets = mutableListOf<KnowledgeSnippet>()
    val deletedKnowledgeIds = mutableListOf<String>()
    private val messages = mutableMapOf<String, List<ChatMessage>>()
    private var models = if (withModel) listOf(model) else emptyList()
    private var storedGlobalSettings = GlobalChatSettings(
        defaultModelId = models.firstOrNull()?.id,
        assistantNickname = assistantNickname,
    )
    @Volatile var initialized = false
    @Volatile var sentRequest: SendMessageRequest? = null
    @Volatile var noteKnowledgeImportCalls = 0
    @Volatile var noteSummaryModelId: String? = null
    @Volatile var noteSummaryPrompt: String? = null
    @Volatile var knowledgePreviewCalls = 0
    var completionUsage = Usage(600, 600)
    var importPreviewGate: CompletableDeferred<Unit>? = null
    var importPreviewError: Throwable? = null
    var applyImportGate: CompletableDeferred<Unit>? = null
    var applyImportError: Throwable? = null
    var saveNoteGate: CompletableDeferred<Unit>? = null
    var saveNoteError: Throwable? = null
    var speechAudio: TtsAudio? = null
    var sendMessageFlow: ((String, SendMessageRequest) -> Flow<ChatEvent>)? = null

    fun seedMessages(conversationId: String, items: List<ChatMessage>) {
        messages[conversationId] = items
    }

    override suspend fun initialize() { initialized = true }
    override suspend fun workspace() = WorkspaceSnapshot(
        providers = if (withProvider) listOf(provider) else emptyList(),
        models = models,
        conversations = conversations.toList(),
        exaConfigured = false,
        globalSettings = storedGlobalSettings,
        bookmarks = bookmarks.toList(),
        notes = notes.toList(),
        knowledgeDocuments = knowledgeDocuments.toList(),
    )
    override suspend fun provider(id: String) = ProviderEditorData(ProviderDraft(provider.id, provider.name, provider.baseUrl, provider.protocol, "secret"), models)
    override suspend fun fetchModels(draft: ProviderDraft) = listOf(RemoteModel("model-a"))
    override suspend fun saveProvider(draft: ProviderDraft, models: List<ModelProfile>): ProviderConfig { this.models = models; return provider }
    override suspend fun deleteProvider(id: String) { models = emptyList() }
    override suspend fun setDefaultModel(id: String) {
        storedGlobalSettings = storedGlobalSettings.copy(defaultModelId = id)
    }
    override fun exaConfigured() = false
    override fun saveExaKey(value: String) = Unit
    override suspend fun globalSettings() = storedGlobalSettings
    override suspend fun saveGlobalSettings(
        settings: GlobalChatSettings,
        mimoTtsKey: String?,
    ): GlobalChatSettings = settings.copy(
        assistantNickname = settings.assistantNickname.trim().ifBlank { DEFAULT_ASSISTANT_NICKNAME },
    ).also { storedGlobalSettings = it }
    override suspend fun conversations() = conversations.toList()
    override suspend fun knowledgeSnippets(ids: List<Long>) =
        knowledgeSnippets.filter { it.chunkId in ids }
    override suspend fun knowledgeDocumentPreview(documentId: String): KnowledgeDocumentPreview? {
        knowledgePreviewCalls += 1
        return knowledgePreviews[documentId]
    }
    override suspend fun conversation(id: String) = ConversationDetail(conversations.first { it.id == id }, messages[id].orEmpty())

    override suspend fun createConversation(request: ConversationWriteRequest): Conversation {
        val conversation = Conversation(id = "conversation-created", model = request.model ?: model.id)
        conversations += conversation
        return conversation
    }

    override suspend fun updateConversation(id: String, request: ConversationWriteRequest): Conversation {
        val index = conversations.indexOfFirst { it.id == id }
        val updated = conversations[index].copy(title = request.title ?: conversations[index].title)
        conversations[index] = updated
        return updated
    }

    override suspend fun deleteConversations(ids: Set<String>) { conversations.removeAll { it.id in ids } }
    override suspend fun clearContext(conversationId: String): ChatMessage {
        val boundary = ChatMessage(
            id = "context-boundary",
            conversationId = conversationId,
            role = CONTEXT_BOUNDARY_ROLE,
        )
        messages[conversationId] = messages[conversationId].orEmpty() + boundary
        return boundary
    }
    override suspend fun toggleBookmark(messageId: String): Boolean {
        val existing = bookmarks.firstOrNull { it.messageId == messageId }
        if (existing != null) bookmarks.remove(existing)
        return existing == null
    }
    override suspend fun deleteBookmarks(messageIds: Set<String>) { bookmarks.removeAll { it.messageId in messageIds } }
    override suspend fun saveNote(note: Note): Note {
        saveNoteGate?.await()
        saveNoteError?.let { throw it }
        notes.removeAll { it.id == note.id }
        notes.add(0, note)
        return note
    }
    override suspend fun deleteNotes(ids: Set<String>) { notes.removeAll { it.id in ids } }
    override suspend fun deleteKnowledge(id: String) {
        deletedKnowledgeIds += id
        knowledgeDocuments.removeAll { it.id == id }
        knowledgePreviews.remove(id)
    }
    override suspend fun importNoteToKnowledge(noteId: String): KnowledgeDocument {
        noteKnowledgeImportCalls += 1
        val note = notes.first { it.id == noteId }
        return KnowledgeDocument(
            id = "knowledge-$noteId",
            name = "${note.title}.md",
            mimeType = "text/markdown",
            storedPath = "knowledge/$noteId.md",
            sizeBytes = note.body.toByteArray().size.toLong(),
            sourceNoteId = noteId,
        ).also { document ->
            knowledgeDocuments.removeAll { it.sourceNoteId == noteId }
            knowledgeDocuments += document
        }
    }
    override suspend fun summarizeNote(noteId: String, modelId: String, rewritePrompt: String): Note {
        noteSummaryModelId = modelId
        noteSummaryPrompt = rewritePrompt
        val index = notes.indexOfFirst { it.id == noteId }
        return notes[index].copy(title = "Rewritten title", body = "Rewritten body").also { notes[index] = it }
    }
    override suspend fun generateTitle(id: String, force: Boolean) = updateConversation(id, ConversationWriteRequest(title = "Title"))

    override fun sendMessage(id: String, request: SendMessageRequest): Flow<ChatEvent> {
        sentRequest = request
        return sendMessageFlow?.invoke(id, request) ?: flow {
            val user = ChatMessage("user", id, requestId = request.requestId, role = "user", content = request.content)
            val assistant = ChatMessage("assistant", id, requestId = request.requestId, role = "assistant", status = "generating")
            emit(ChatEvent.UserMessage(user))
            emit(ChatEvent.AssistantMessage(assistant))
            emit(ChatEvent.Process(ProcessEvent(type = "thinking", id = "thinking", content = "summary")))
            emit(ChatEvent.Delta("Answer from provider"))
            messages[id] = listOf(user, assistant.copy(content = "Answer from provider", status = "completed"))
            emit(ChatEvent.Done(completionUsage, false))
        }
    }

    override fun regenerate(id: String, request: SendMessageRequest): Flow<ChatEvent> = flow { }
    override suspend fun synthesizeSpeech(messageId: String, force: Boolean) =
        requireNotNull(speechAudio) { "No speech audio was configured for this test" }
    override suspend fun exportConfiguration(password: CharArray) = "archive"
    override suspend fun previewImport(raw: String, password: CharArray): ImportPreview {
        importPreviewGate?.await()
        importPreviewError?.let { throw it }
        return ImportPreview(
            payload = ConfigArchivePayload(
                createdAt = 1L,
                providers = emptyList(),
                models = emptyList(),
            ),
            newProviders = 1,
            updatedProviders = 0,
            newModels = 1,
            updatedModels = 0,
            replacesExaKey = false,
        )
    }

    override suspend fun applyImport(preview: ImportPreview) {
        applyImportGate?.await()
        applyImportError?.let { throw it }
    }
}
