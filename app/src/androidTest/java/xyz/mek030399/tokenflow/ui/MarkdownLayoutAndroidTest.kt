package xyz.mek030399.tokenflow.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import xyz.mek030399.tokenflow.R
import xyz.mek030399.tokenflow.ui.theme.TokenFlowTheme

class MarkdownLayoutAndroidTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun completedReplyHasEqualCellHeightsAndFitsTwoColumnsOnPhone() {
        composeRule.setContent {
            TokenFlowTheme {
                Box(Modifier.size(320.dp, 640.dp).background(MaterialTheme.colorScheme.background).testTag("markdown_fixture")) {
                    MarkdownContent(FIXTURE, Modifier.padding(16.dp).verticalScroll(rememberScrollState()))
                }
            }
        }
        assertEqualRowHeights(4)
        val table = composeRule.onNodeWithTag("markdown_table").fetchSemanticsNode().boundsInRoot
        val first = composeRule.onNodeWithTag("markdown_table_cell_0_0").fetchSemanticsNode().boundsInRoot
        val last = composeRule.onNodeWithTag("markdown_table_cell_0_1").fetchSemanticsNode().boundsInRoot
        assertEquals(table.left, first.left, 1f)
        assertEquals("Both columns should fit without cutting off the right edge", table.right, last.right, 1f)
        composeRule.onNodeWithText("几个特别突出的“明星产品”：默沙东的 Keytruda。").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.markdown_source_link_unavailable)).assertIsDisplayed()
        val screenshot = composeRule.onNodeWithTag("markdown_fixture").captureToImage().asAndroidBitmap()
        File(context.filesDir, "markdown-rendering-fixed.png").outputStream().use {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test fun changingFontScaleKeepsEachRowsBordersAligned() {
        val scale = mutableFloatStateOf(1f)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale.floatValue)) {
                TokenFlowTheme {
                    Box(Modifier.size(320.dp, 700.dp)) {
                        MarkdownContent(TABLE, Modifier.padding(16.dp).verticalScroll(rememberScrollState()))
                    }
                }
            }
        }
        assertEqualRowHeights(4)
        composeRule.runOnIdle { scale.floatValue = 1.7f }
        assertEqualRowHeights(3)
    }

    @Test fun widerTablesCanStillBeScrolledToTheLastColumn() {
        composeRule.setContent {
            TokenFlowTheme {
                Box(Modifier.size(320.dp, 400.dp)) {
                    MarkdownContent("| A | B | C | Last column |\n|---|---|---|---|\n| One | Two | Three | Four |",
                        Modifier.padding(16.dp))
                }
            }
        }
        composeRule.onNodeWithTag("markdown_table").performTouchInput { swipeLeft() }
        composeRule.onNodeWithText("Last column").assertIsDisplayed()
        composeRule.onNodeWithText("Four").assertIsDisplayed()
    }

    @Test fun longCitationUrlsUseClickableTitlesOnPhoneAtLargeFont() {
        val opened = mutableListOf<String>()
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) { opened += uri }
        }
        val explicitUrl = "https://example.com/restaurant/menu?source=reference&keep=full-target"
        val codeUrl = "https://example.com/code?keep=literal"
        val markdown = "餐厅推荐：[[${WINGON_URL}]] [[${DIANPING_URL}]]\n\n" +
            "[官网详情]($explicitUrl)\n\n`$codeUrl`"
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 1.5f),
                LocalUriHandler provides uriHandler,
            ) {
                TokenFlowTheme {
                    Box(Modifier.size(320.dp, 640.dp).background(MaterialTheme.colorScheme.background).testTag("citation_links_fixture")) {
                        MarkdownContent(markdown, Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                            webLinkTitles = mapOf(WINGON_URL to "餐厅详情", DIANPING_URL to "餐厅评价"))
                    }
                }
            }
        }

        val citations = composeRule.onNodeWithText("餐厅推荐：餐厅详情 餐厅评价").assertIsDisplayed()
        composeRule.onAllNodesWithText(WINGON_URL, substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText(DIANPING_URL, substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("[[", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("]]", substring = true).assertCountEquals(0)
        val layout = citations.textLayout()
        assertFalse("Short citation titles must fit the phone viewport", layout.hasVisualOverflow)
        citations.clickText("餐厅详情")
        citations.clickText("餐厅评价")
        composeRule.onNodeWithText("官网详情").assertIsDisplayed().clickText("官网详情")
        val code = composeRule.onNodeWithText(codeUrl).assertIsDisplayed().textLayout().layoutInput.text
        assertTrue("An inline code URL must remain literal and unlinked", code.getStringAnnotations("URL", 0, code.length).isEmpty())
        composeRule.runOnIdle { assertEquals(listOf(WINGON_URL, DIANPING_URL, explicitUrl), opened) }

        val screenshot = composeRule.onNodeWithTag("citation_links_fixture").captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "citation-links.png").outputStream().use {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test fun unknownUrlUsesDomainWhileExplicitTitleAndCodeStayIntact() {
        val opened = mutableListOf<String>()
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) { opened += uri }
        }
        val bareUrl = "https://www.example.com/a/very/long/source/path?query=keep-original&lang=zh#section"
        val explicitUrl = "https://example.com/another/path?ref=original"
        val codeUrl = "https://example.com/literal-code?value=1"
        composeRule.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                TokenFlowTheme {
                    Box(Modifier.size(320.dp, 480.dp)) {
                        MarkdownContent("$bareUrl\n\n[Read report]($explicitUrl)\n\n`$codeUrl`", Modifier.padding(16.dp))
                    }
                }
            }
        }
        composeRule.onAllNodesWithText(bareUrl, substring = true).assertCountEquals(0)
        composeRule.onNodeWithText("example.com").assertIsDisplayed().clickText("example.com")
        composeRule.onNodeWithText("Read report").assertIsDisplayed().clickText("Read report")
        val code = composeRule.onNodeWithText(codeUrl).assertIsDisplayed().textLayout().layoutInput.text
        assertEquals(codeUrl, code.text)
        assertTrue(code.getStringAnnotations("URL", 0, code.length).isEmpty())
        composeRule.runOnIdle { assertEquals(listOf(bareUrl, explicitUrl), opened) }
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
        return checkNotNull(layouts.singleOrNull()) { "Expected a single Markdown text layout" }
    }

    private fun SemanticsNodeInteraction.clickText(label: String) {
        val layout = textLayout()
        val start = layout.layoutInput.text.text.indexOf(label)
        check(start >= 0) { "Missing link label: $label" }
        val position = layout.getBoundingBox(start + label.length / 2).center
        performTouchInput { click(position) }
    }

    private fun assertEqualRowHeights(count: Int) {
        var previousBottom: Float? = null
        for (row in 0 until count) {
            val left = composeRule.onNodeWithTag("markdown_table_cell_${row}_0").fetchSemanticsNode().boundsInRoot
            val right = composeRule.onNodeWithTag("markdown_table_cell_${row}_1").fetchSemanticsNode().boundsInRoot
            assertEquals("Row $row cell tops differ", left.top, right.top, 1f)
            assertEquals("Row $row cell bottoms differ", left.bottom, right.bottom, 1f)
            assertTrue("Row $row must have a nonzero height", left.height > 0f)
            previousBottom?.let { assertEquals("Rows must not overlap or leave gaps", it, left.top, 1f) }
            previousBottom = left.bottom
        }
    }

    companion object {
        private const val WINGON_URL = "https://www.wingontravel.com/discovery/restaurant-154979511"
        private const val DIANPING_URL = "https://m.dianping.com/shop/1175084160?msource=applemaps"
        private val TABLE = """
            | 公司 | 代表产品 |
            |---|---|
            | **艾伯维 AbbVie** | Skyrizi、Rinvoq、Humira、Botox、Vraylar |
            | **强生 Johnson & Johnson** | Darzalex、Stelara |
            | **罗氏 Roche** | Ocrevus、Hemlibra、Vabysmo、Phesgo、Xolair |
        """.trimIndent()
        private val FIXTURE = TABLE + "\n\n**几个特别突出的“明星产品”：**默沙东的 Keytruda。\n\n" +
            "\uE200cite\uE202turn166120search0\uE202turn166120search1\uE201"
    }
}
