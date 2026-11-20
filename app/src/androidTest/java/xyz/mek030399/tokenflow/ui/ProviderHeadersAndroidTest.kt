package xyz.mek030399.tokenflow.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import xyz.mek030399.tokenflow.data.ProviderHeaderTemplate
import xyz.mek030399.tokenflow.data.ProviderHeaderTemplates
import xyz.mek030399.tokenflow.data.ProviderRequestHeader
import xyz.mek030399.tokenflow.ui.theme.TokenFlowTheme

class ProviderHeadersAndroidTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun invalidJsonAndDisallowedHeadersLeaveProviderDraftUnchanged() {
        val rows = mutableStateOf(emptyList<ProviderRequestHeader>())
        composeRule.setContent {
            TokenFlowTheme { ProviderHeadersEditor(rows.value, "json-entry", true, onRowsChanged = { rows.value = it }) }
        }
        composeRule.onNodeWithTag("provider_headers_json_input").assertDoesNotExist()
        composeRule.onNodeWithTag("provider_headers_edit").performClick()
        val input = composeRule.onNodeWithTag("provider_headers_json_input")
        val apply = composeRule.onNodeWithTag("provider_headers_json_apply")
        val invalidSources = listOf(
            "[",
            """[{"name":"X-Example","value":123}]""",
            """[{"name":"X-Example","value":"static","enabled":"false"}]""",
            """[{"name":"Host","value":"example.invalid"}]""",
        )
        invalidSources.forEach { source ->
            input.performTextReplacement(source)
            apply.performClick()
            composeRule.onNodeWithTag("provider_headers_json_error", useUnmergedTree = true).assertExists()
            composeRule.runOnIdle { assertTrue(rows.value.isEmpty()) }
        }

        input.performTextReplacement("""[{"name":"X-Example","value":"static","enabled":false}]""")
        composeRule.onNodeWithTag("provider_headers_json_format").performClick()
        apply.performClick()
        input.assertDoesNotExist()
        composeRule.runOnIdle {
            val row = rows.value.single()
            assertEquals("X-Example", row.name)
            assertEquals("static", row.value)
            assertFalse(row.enabled)
        }

        composeRule.onNodeWithTag("provider_headers_edit").performClick()
        input.performTextReplacement("[]")
        composeRule.onNodeWithTag("provider_headers_json_close").performClick()
        composeRule.runOnIdle { assertEquals(1, rows.value.size) }
    }

    @Test
    fun templateSwitchUsesTheJsonEditsAndKeepsAuthWhileBusyPreventsOpening() {
        val templated = ProviderHeaderTemplates.apply(emptyList(), ProviderHeaderTemplate.CLAUDE_CODE)
        val auth = ProviderRequestHeader(id = "auth", name = "Authorization", value = "Bearer example")
        val rows = mutableStateOf(templated + auth)
        val enabled = mutableStateOf(true)
        composeRule.setContent {
            TokenFlowTheme {
                ProviderHeadersEditor(rows.value, "template-entry", enabled.value, onRowsChanged = { rows.value = it })
            }
        }

        composeRule.onNodeWithTag("provider_headers_edit").performClick()
        val source = ProviderHeaderJson.encode(templated + auth)
            .replace("\"name\": \"x-app\"", "\"name\": \"X-Manual\"")
        composeRule.onNodeWithTag("provider_headers_json_input").performTextReplacement(source)
        composeRule.onNodeWithTag("provider_header_templates").performClick()
        composeRule.onNodeWithTag("provider_header_template_pi").performClick()
        composeRule.onNodeWithTag("provider_headers_json_apply").performClick()
        composeRule.runOnIdle {
            assertEquals(setOf("Authorization", "X-Manual", "User-Agent"), rows.value.map { it.name }.toSet())
            assertEquals(auth, rows.value.single { it.id == auth.id })
            assertNull(rows.value.single { it.name == "X-Manual" }.templateId)
            assertEquals("pi", rows.value.single { it.name == "User-Agent" }.templateId)
            enabled.value = false
        }
        composeRule.onNodeWithTag("provider_headers_edit").assertIsNotEnabled()
    }
}
