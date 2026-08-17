package xyz.mek030399.tokenflow.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import xyz.mek030399.tokenflow.ui.theme.AppTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ChatDisplayPreferencesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences by lazy {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    private var hadOriginalLineSpacing = false
    private var originalLineSpacing = ChatDisplayPreferences.DEFAULT_LINE_SPACING
    private var hadOriginalTheme = false
    private var originalTheme: String? = null

    @Before
    fun setUp() {
        hadOriginalLineSpacing = preferences.contains(LINE_SPACING_KEY)
        originalLineSpacing = preferences.getFloat(
            LINE_SPACING_KEY,
            ChatDisplayPreferences.DEFAULT_LINE_SPACING,
        )
        hadOriginalTheme = preferences.contains(APP_THEME_KEY)
        originalTheme = preferences.getString(APP_THEME_KEY, null)
        preferences.edit().remove(LINE_SPACING_KEY).remove(APP_THEME_KEY).commit()
    }

    @After
    fun tearDown() {
        preferences.edit().apply {
            if (hadOriginalLineSpacing) putFloat(LINE_SPACING_KEY, originalLineSpacing)
            else remove(LINE_SPACING_KEY)
            if (hadOriginalTheme) putString(APP_THEME_KEY, originalTheme)
            else remove(APP_THEME_KEY)
        }.commit()
    }

    @Test
    fun lineSpacingDefaultsToOneAndPersistsAcrossInstances() {
        assertEquals(
            ChatDisplayPreferences.DEFAULT_LINE_SPACING,
            ChatDisplayPreferences(context).readLineSpacing(),
            0f,
        )

        ChatDisplayPreferences(context).writeLineSpacing(0.6f)

        assertEquals(0.6f, ChatDisplayPreferences(context).readLineSpacing(), 0f)
    }

    @Test
    fun lineSpacingWritesAreClampedBeforePersistence() {
        val store = ChatDisplayPreferences(context)

        store.writeLineSpacing(-1f)
        assertEquals(ChatDisplayPreferences.MIN_LINE_SPACING, storedLineSpacing(), 0f)

        store.writeLineSpacing(2f)
        assertEquals(ChatDisplayPreferences.MAX_LINE_SPACING, storedLineSpacing(), 0f)
    }

    @Test
    fun lineSpacingReadsAreClamped() {
        preferences.edit().putFloat(LINE_SPACING_KEY, -1f).commit()
        assertEquals(
            ChatDisplayPreferences.MIN_LINE_SPACING,
            ChatDisplayPreferences(context).readLineSpacing(),
            0f,
        )

        preferences.edit().putFloat(LINE_SPACING_KEY, 2f).commit()
        assertEquals(
            ChatDisplayPreferences.MAX_LINE_SPACING,
            ChatDisplayPreferences(context).readLineSpacing(),
            0f,
        )
    }

    @Test
    fun themeDefaultsToDawnWhiteAndPersistsAcrossInstances() {
        assertEquals(AppTheme.DAWN_WHITE, ChatDisplayPreferences(context).readTheme())

        AppTheme.entries.forEach { theme ->
            ChatDisplayPreferences(context).writeTheme(theme)
            assertEquals(theme, ChatDisplayPreferences(context).readTheme())
        }
    }

    @Test
    fun unknownThemeFallsBackToDawnWhite() {
        preferences.edit().putString(APP_THEME_KEY, "removed_theme").commit()

        assertEquals(AppTheme.DAWN_WHITE, ChatDisplayPreferences(context).readTheme())
    }

    private fun storedLineSpacing(): Float = preferences.getFloat(LINE_SPACING_KEY, Float.NaN)

    private companion object {
        const val PREFERENCES_NAME = "tokenflow_display"
        const val LINE_SPACING_KEY = "chat_line_spacing"
        const val APP_THEME_KEY = "app_theme"
    }
}
