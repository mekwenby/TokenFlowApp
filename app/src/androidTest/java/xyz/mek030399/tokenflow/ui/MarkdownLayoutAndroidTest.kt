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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
