package xyz.mek030399.tokenflow.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.captureToImage
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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import xyz.mek030399.tokenflow.data.ChatDataSource
import xyz.mek030399.tokenflow.data.ChatDisplayPreferences
import xyz.mek030399.tokenflow.data.ChatEvent
import xyz.mek030399.tokenflow.data.ChatMessage
import xyz.mek030399.tokenflow.data.AssistantMetadata
import xyz.mek030399.tokenflow.data.CONTEXT_BOUNDARY_ROLE
import xyz.mek030399.tokenflow.data.BookmarkedMessage
import xyz.mek030399.tokenflow.data.ConfigArchivePayload
import xyz.mek030399.tokenflow.data.Conversation
import xyz.mek030399.tokenflow.data.ConversationDetail
import xyz.mek030399.tokenflow.data.ConversationWriteRequest
import xyz.mek030399.tokenflow.data.ImportPreview
import xyz.mek030399.tokenflow.data.KnowledgeCitation
import xyz.mek030399.tokenflow.data.KnowledgeDocument
import xyz.mek030399.tokenflow.data.KnowledgeSnippet
import xyz.mek030399.tokenflow.data.GlobalChatSettings
import xyz.mek030399.tokenflow.data.ModelProfile
import xyz.mek030399.tokenflow.data.Note
import xyz.mek030399.tokenflow.data.PendingAttachment
import xyz.mek030399.tokenflow.data.ProcessEvent
import xyz.mek030399.tokenflow.data.ProviderConfig
import xyz.mek030399.tokenflow.data.ProviderDraft
import xyz.mek030399.tokenflow.data.ProviderEditorData
import xyz.mek030399.tokenflow.data.ProviderProtocol
import xyz.mek030399.tokenflow.data.RemoteModel
import xyz.mek030399.tokenflow.data.SendMessageRequest
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

class TokenFlowAppTest {
    @get:Rule
    val composeRule = createComposeRule()

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
        val copyBounds = composeRule.onNodeWithTag(UiTestTags.COPY_ASSISTANT_MESSAGE).fetchSemanticsNode().boundsInRoot
        assertEquals(summaryBounds.left, processBounds.left, 0.5f)
        assertEquals(summaryBounds.right, tokenBounds.right, 0.5f)
        assertTrue(processBounds.left < tokenBounds.left)
        assertTrue(processBounds.top < tokenBounds.bottom && tokenBounds.top < processBounds.bottom)
        assertTrue(tokenBounds.bottom <= copyBounds.top)
        composeRule.onNodeWithTag(UiTestTags.SPEECH_ACTION).assertIsDisplayed()
        val userAvatar = composeRule.onNodeWithTag(UiTestTags.USER_MESSAGE_AVATAR).fetchSemanticsNode().boundsInRoot
        assertEquals(userAvatar.width, userAvatar.height, 0.5f)
        composeRule.onNodeWithTag(UiTestTags.COPY_ASSISTANT_MESSAGE).performClick()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        assertEquals("Answer from provider", clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString())
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

        about.performScrollToNode(hasText("Jetpack Compose / Material 3"))
        composeRule.onNodeWithText("Jetpack Compose / Material 3").assertIsDisplayed()
        about.performScrollToNode(hasText("commonmark-java"))
        composeRule.onNodeWithText("commonmark-java").assertIsDisplayed()
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
        composeRule.setContent { TokenFlowTheme { TokenFlowApp(viewModel) } }
        composeRule.waitUntil(5_000) { fake.initialized }

        viewModel.openScreen(AppScreen.ABOUT)
        composeRule.onNodeWithTag(UiTestTags.ABOUT_SCREEN)
            .performScrollToNode(hasTestTag(UiTestTags.ABOUT_USER_AGREEMENT))
        composeRule.onNodeWithTag(UiTestTags.ABOUT_USER_AGREEMENT).performClick()

        composeRule.onNodeWithTag(UiTestTags.USER_AGREEMENT_SCREEN).assertIsDisplayed()
        composeRule.onNodeWithText("协议版本：1.0").assertIsDisplayed()
        composeRule.onNodeWithText("15. 联系方式").performScrollTo().assertIsDisplayed()

        pressBack()
        composeRule.onNodeWithTag(UiTestTags.ABOUT_SCREEN).assertIsDisplayed()
        composeRule.onAllNodesWithTag(UiTestTags.USER_AGREEMENT_SCREEN).assertCountEquals(0)

        pressBack()
        composeRule.waitUntil(5_000) { viewModel.state.value.screen == AppScreen.CHAT }
        composeRule.onNodeWithTag(UiTestTags.MESSAGE_INPUT).assertIsDisplayed()
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
        composeRule.setContent { TokenFlowTheme { TokenFlowApp(viewModel) } }
        composeRule.waitUntil(5_000) { fake.initialized }

        viewModel.openScreen(AppScreen.NOTES)
        composeRule.onNodeWithTag(UiTestTags.noteItem("note-rich")).performClick()

        composeRule.onNodeWithTag("note_reader").assertIsDisplayed()
        composeRule.onNodeWithText("Rendered heading").assertIsDisplayed()
        composeRule.onNodeWithText("Jade highlight and strong text").assertIsDisplayed()
        composeRule.onAllNodesWithTag("note_editor").assertCountEquals(0)

        val summarize = composeRule.onNodeWithTag("note_reader_summarize").fetchSemanticsNode().boundsInRoot
        val edit = composeRule.onNodeWithTag("note_reader_edit").fetchSemanticsNode().boundsInRoot
        assertTrue(summarize.center.x < edit.center.x)
        composeRule.onNodeWithTag("note_reader_import_knowledge").assertIsDisplayed()
        composeRule.onNodeWithTag("note_reader_summarize").performClick()
        composeRule.onNodeWithText("Model A").assertIsDisplayed()
        pressBack()

        composeRule.onNodeWithTag("note_reader_edit").performClick()
        composeRule.onNodeWithTag("note_editor").assertIsDisplayed()
        composeRule.onAllNodesWithTag("note_reader").assertCountEquals(0)
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

        composeRule.onNodeWithTag("note_rewrite_prompt").performTextInput("Keep every source link")
        composeRule.onNodeWithText("Model A").performClick()
        composeRule.waitUntil(5_000) { fake.noteSummaryPrompt == "Keep every source link" }
        assertEquals(fake.model.id, fake.noteSummaryModelId)

        composeRule.onNodeWithTag("note_reader_summarize").performClick()
        val editableText = composeRule.onNodeWithTag("note_rewrite_prompt")
            .fetchSemanticsNode().config[SemanticsProperties.EditableText]
        assertEquals("", editableText.text)
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
        val fake = UiFakeDataSource(withModel = true).apply {
            notes += Note(id = "note-chat", title = "Chat context", body = "Independent note body")
        }
        val viewModel = AppViewModel(fake)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent { TokenFlowTheme { TokenFlowApp(viewModel) } }
        composeRule.waitUntil(5_000) { fake.initialized }

        composeRule.onNodeWithContentDescription(context.getString(xyz.mek030399.tokenflow.R.string.add_attachment)).performClick()
        composeRule.onNodeWithText(context.getString(xyz.mek030399.tokenflow.R.string.import_note)).performClick()
        composeRule.onNodeWithText("Chat context").performClick()

        composeRule.waitUntil(5_000) { viewModel.state.value.pendingAttachments.size == 1 }
        val attachment = viewModel.state.value.pendingAttachments.single()
        assertEquals("Chat context.md", attachment.displayName)
        assertEquals("Independent note body", attachment.inlineText)
        composeRule.onNodeWithText("Chat context.md").assertIsDisplayed()
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
    }

    @OptIn(ExperimentalTestApi::class)
    private fun setAppAt(viewModel: AppViewModel, size: DpSize, fontScale: Float = 1f) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                    TokenFlowTheme { TokenFlowApp(viewModel) }
                }
            }
        }
    }
}

private fun assertNodeWithin(node: SemanticsNodeInteraction, container: SemanticsNodeInteraction) {
    val nodeBounds = node.fetchSemanticsNode().boundsInRoot
    val containerBounds = container.fetchSemanticsNode().boundsInRoot
    assertTrue(nodeBounds.left >= containerBounds.left - 0.5f)
    assertTrue(nodeBounds.top >= containerBounds.top - 0.5f)
    assertTrue(nodeBounds.right <= containerBounds.right + 0.5f)
    assertTrue(nodeBounds.bottom <= containerBounds.bottom + 0.5f)
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

private class UiFakeDataSource(withModel: Boolean, private val withProvider: Boolean = withModel) : ChatDataSource {
    val provider = ProviderConfig("provider-1", "Provider", "https://api.example.com/v1", ProviderProtocol.OPENAI_RESPONSES, true)
    val model = ModelProfile("model-1", provider.id, "model-a", "Model A", 4096, true)
    val conversations = mutableListOf<Conversation>()
    val bookmarks = mutableListOf<BookmarkedMessage>()
    val notes = mutableListOf<Note>()
    val knowledgeDocuments = mutableListOf<KnowledgeDocument>()
    val knowledgeSnippets = mutableListOf<KnowledgeSnippet>()
    private val messages = mutableMapOf<String, List<ChatMessage>>()
    private var models = if (withModel) listOf(model) else emptyList()
    @Volatile var initialized = false
    @Volatile var sentRequest: SendMessageRequest? = null
    @Volatile var noteKnowledgeImportCalls = 0
    @Volatile var noteSummaryModelId: String? = null
    @Volatile var noteSummaryPrompt: String? = null
    var completionUsage = Usage(600, 600)
    var importPreviewGate: CompletableDeferred<Unit>? = null
    var importPreviewError: Throwable? = null
    var applyImportGate: CompletableDeferred<Unit>? = null
    var applyImportError: Throwable? = null

    fun seedMessages(conversationId: String, items: List<ChatMessage>) {
        messages[conversationId] = items
    }

    override suspend fun initialize() { initialized = true }
    override suspend fun workspace() = WorkspaceSnapshot(
        providers = if (withProvider) listOf(provider) else emptyList(),
        models = models,
        conversations = conversations.toList(),
        exaConfigured = false,
        globalSettings = GlobalChatSettings(defaultModelId = models.firstOrNull()?.id),
        bookmarks = bookmarks.toList(),
        notes = notes.toList(),
        knowledgeDocuments = knowledgeDocuments.toList(),
    )
    override suspend fun provider(id: String) = ProviderEditorData(ProviderDraft(provider.id, provider.name, provider.baseUrl, provider.protocol, "secret"), models)
    override suspend fun fetchModels(draft: ProviderDraft) = listOf(RemoteModel("model-a"))
    override suspend fun saveProvider(draft: ProviderDraft, models: List<ModelProfile>): ProviderConfig { this.models = models; return provider }
    override suspend fun deleteProvider(id: String) { models = emptyList() }
    override suspend fun setDefaultModel(id: String) = Unit
    override fun exaConfigured() = false
    override fun saveExaKey(value: String) = Unit
    override suspend fun conversations() = conversations.toList()
    override suspend fun knowledgeSnippets(ids: List<Long>) =
        knowledgeSnippets.filter { it.chunkId in ids }
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
    override suspend fun deleteNotes(ids: Set<String>) { notes.removeAll { it.id in ids } }
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

    override fun sendMessage(id: String, request: SendMessageRequest): Flow<ChatEvent> = flow {
        sentRequest = request
        val user = ChatMessage("user", id, requestId = request.requestId, role = "user", content = request.content)
        val assistant = ChatMessage("assistant", id, requestId = request.requestId, role = "assistant", status = "generating")
        emit(ChatEvent.UserMessage(user))
        emit(ChatEvent.AssistantMessage(assistant))
        emit(ChatEvent.Process(ProcessEvent(type = "thinking", id = "thinking", content = "summary")))
        emit(ChatEvent.Delta("Answer from provider"))
        messages[id] = listOf(user, assistant.copy(content = "Answer from provider", status = "completed"))
        emit(ChatEvent.Done(completionUsage, false))
    }

    override fun regenerate(id: String, request: SendMessageRequest): Flow<ChatEvent> = flow { }
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
