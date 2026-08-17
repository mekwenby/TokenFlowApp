package xyz.mek030399.tokenflow.data

import android.content.Context
import xyz.mek030399.tokenflow.ui.theme.AppTheme

class ChatDisplayPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun readFontScale(): Float = preferences
        .getFloat(CHAT_FONT_SCALE, DEFAULT_FONT_SCALE)
        .coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)

    fun writeFontScale(value: Float) {
        preferences.edit().putFloat(CHAT_FONT_SCALE, value.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)).apply()
    }

    fun readLetterSpacing(): Float = preferences
        .getFloat(CHAT_LETTER_SPACING, DEFAULT_LETTER_SPACING)
        .coerceIn(MIN_LETTER_SPACING, MAX_LETTER_SPACING)

    fun writeLetterSpacing(value: Float) {
        preferences.edit().putFloat(
            CHAT_LETTER_SPACING,
            value.coerceIn(MIN_LETTER_SPACING, MAX_LETTER_SPACING),
        ).apply()
    }

    fun readLineSpacing(): Float = preferences
        .getFloat(CHAT_LINE_SPACING, DEFAULT_LINE_SPACING)
        .coerceIn(MIN_LINE_SPACING, MAX_LINE_SPACING)

    fun writeLineSpacing(value: Float) {
        preferences.edit().putFloat(
            CHAT_LINE_SPACING,
            value.coerceIn(MIN_LINE_SPACING, MAX_LINE_SPACING),
        ).apply()
    }

    fun readTheme(): AppTheme = AppTheme.fromStorageValue(preferences.getString(APP_THEME, null))

    fun writeTheme(theme: AppTheme) {
        preferences.edit().putString(APP_THEME, theme.storageValue).apply()
    }

    companion object {
        const val MIN_FONT_SCALE = 0.8f
        const val MAX_FONT_SCALE = 1.4f
        const val DEFAULT_FONT_SCALE = 1f
        const val MIN_LETTER_SPACING = 0f
        const val MAX_LETTER_SPACING = 0.20f
        const val DEFAULT_LETTER_SPACING = 0f
        const val MIN_LINE_SPACING = 0.2f
        const val MAX_LINE_SPACING = 1f
        const val DEFAULT_LINE_SPACING = 1f

        private const val PREFERENCES = "tokenflow_display"
        private const val CHAT_FONT_SCALE = "chat_font_scale"
        private const val CHAT_LETTER_SPACING = "chat_letter_spacing"
        private const val CHAT_LINE_SPACING = "chat_line_spacing"
        private const val APP_THEME = "app_theme"
    }
}
