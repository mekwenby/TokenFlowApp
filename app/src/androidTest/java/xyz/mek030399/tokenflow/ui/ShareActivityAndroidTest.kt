package xyz.mek030399.tokenflow.ui

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import xyz.mek030399.tokenflow.MainActivity
import xyz.mek030399.tokenflow.data.SavedShareState
import xyz.mek030399.tokenflow.data.ShareDraftStore

class ShareActivityAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun coldShareSurvivesActivityRecreationAndAnIdenticalNewIntentIsANewEvent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ShareDraftStore(context)
        val previous = runBlocking { store.load() }
        runBlocking { store.save(SavedShareState()) }
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND)
            .setType("text/plain").putExtra(Intent.EXTRA_TEXT, "activity share")
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        try {
            waitForSharedText("activity share")
            scenario.recreate()
            waitForSharedText("activity share")
            scenario.onActivity { activity -> activity.startActivity(Intent(intent).setFlags(0)) }
            waitForSharedText("activity share\n\nactivity share")
            scenario.recreate()
            waitForSharedText("activity share\n\nactivity share")
            compose.waitUntil(5_000) {
                runBlocking { store.load().incoming?.text == "activity share\n\nactivity share" }
            }
        } finally {
            scenario.close()
            runBlocking { store.save(previous) }
        }
    }

    private fun waitForSharedText(text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("share_content_input").fetchSemanticsNodes().any {
                it.config.getOrElse(SemanticsProperties.EditableText) { AnnotatedString("") }.text == text
            }
        }
        compose.onNodeWithTag("share_content_input").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text)))
    }
}
