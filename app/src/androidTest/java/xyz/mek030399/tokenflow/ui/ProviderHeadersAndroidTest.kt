package xyz.mek030399.tokenflow.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import xyz.mek030399.tokenflow.data.ProviderHeaderTemplate
import xyz.mek030399.tokenflow.data.ProviderHeaderTemplates
import xyz.mek030399.tokenflow.data.ProviderRequestHeader
import xyz.mek030399.tokenflow.ui.theme.TokenFlowTheme

class ProviderHeadersAndroidTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun emptyHeadersStayCollapsedAndRowsSupportEditingDisablingRevealingAndDeleting() {
        val rows = mutableStateOf(emptyList<ProviderRequestHeader>())
        composeRule.setContent {
            TokenFlowTheme {
                Box(Modifier.size(360.dp, 760.dp).verticalScroll(rememberScrollState())) {
                    ProviderHeadersEditor(rows.value, "header-entry", true,
                        onRowsChanged = { rows.value = it },
                        onAdd = { rows.value += ProviderRequestHeader(id = "manual") },
                        onApplyTemplate = { rows.value = ProviderHeaderTemplates.apply(rows.value, it) })
                }
            }
        }
        composeRule.onNodeWithTag("provider_header_add").assertDoesNotExist()
        composeRule.onNodeWithTag("provider_headers_expand").performClick()
        composeRule.onNodeWithTag("provider_header_add").performClick()
        composeRule.onNodeWithTag("provider_header_name_manual").performScrollTo().performTextInput("X-Example")
        composeRule.onNodeWithTag("provider_header_value_manual").performScrollTo().performTextInput("example-value")
        composeRule.onNodeWithTag("provider_header_value_manual")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        composeRule.onNodeWithTag("provider_header_visibility_manual").performClick()
        composeRule.onNodeWithTag("provider_header_enabled_manual").performScrollTo().assertIsOn().performClick().assertIsOff()
        composeRule.runOnIdle {
            assertEquals("X-Example", rows.value.single().name)
            assertEquals("example-value", rows.value.single().value)
            assertFalse(rows.value.single().enabled)
        }
        composeRule.onNodeWithTag("provider_header_delete_manual").performClick()
        composeRule.onNodeWithTag("provider_header_row_manual").assertDoesNotExist()
        composeRule.runOnIdle { assertTrue(rows.value.isEmpty()) }
    }

    @Test
    fun editingTemplateRowsMakesThemManualAndBusyStateDisablesHeaderMutations() {
        val templateRows = ProviderHeaderTemplates.apply(emptyList(), ProviderHeaderTemplate.CLAUDE_CODE)
        val editable = templateRows.first { it.name.equals("User-Agent", ignoreCase = true) }
        val rows = mutableStateOf(listOf(editable,
            ProviderRequestHeader(id = "auth", name = "Authorization", value = "Bearer example", templateId = "claude_code")))
        val enabled = mutableStateOf(true)
        composeRule.setContent {
            TokenFlowTheme {
                Box(Modifier.size(360.dp, 760.dp).verticalScroll(rememberScrollState())) {
                    ProviderHeadersEditor(rows.value, "template-entry", enabled.value,
                        onRowsChanged = { rows.value = it }, onAdd = {},
                        onApplyTemplate = { rows.value = ProviderHeaderTemplates.apply(rows.value, it) })
                }
            }
        }
        composeRule.onNodeWithTag("provider_header_name_${editable.id}").performScrollTo().performTextReplacement("X-Manual")
        composeRule.runOnIdle { assertEquals(null, rows.value.first().templateId) }
        composeRule.onNodeWithTag("provider_header_templates").performScrollTo().performClick()
        composeRule.onNodeWithTag("provider_header_template_pi").performClick()
        composeRule.runOnIdle {
            assertTrue(rows.value.any { it.id == editable.id && it.name == "X-Manual" })
            assertTrue(rows.value.any { it.id == "auth" && it.value == "Bearer example" })
            enabled.value = false
        }
        composeRule.onNodeWithTag("provider_header_name_${editable.id}").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("provider_header_value_${editable.id}").assertIsNotEnabled()
        composeRule.onNodeWithTag("provider_header_enabled_${editable.id}").assertIsNotEnabled()
        composeRule.onNodeWithTag("provider_header_delete_${editable.id}").assertIsNotEnabled()
        composeRule.onNodeWithTag("provider_header_add").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("provider_header_templates").assertIsNotEnabled()
    }
}
