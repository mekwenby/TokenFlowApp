package xyz.mek030399.tokenflow.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    @Test
    fun storageValuesRoundTripAndUnknownValuesFallBackToDawnWhite() {
        AppTheme.entries.forEach { theme ->
            assertEquals(theme, AppTheme.fromStorageValue(theme.storageValue))
            assertEquals(3, theme.previewColors.size)
        }

        assertEquals(AppTheme.DAWN_WHITE, AppTheme.fromStorageValue(null))
        assertEquals(AppTheme.DAWN_WHITE, AppTheme.fromStorageValue(""))
        assertEquals(AppTheme.DAWN_WHITE, AppTheme.fromStorageValue("legacy_theme"))
    }

    @Test
    fun firstFiveThemesFollowSystemAndAmoledAlwaysResolvesDark() {
        AppTheme.entries.filterNot { it == AppTheme.AMOLED_BLACK }.forEach { theme ->
            assertFalse(resolveDarkTheme(theme, systemDark = false))
            assertTrue(resolveDarkTheme(theme, systemDark = true))
        }

        assertTrue(resolveDarkTheme(AppTheme.AMOLED_BLACK, systemDark = false))
        assertTrue(resolveDarkTheme(AppTheme.AMOLED_BLACK, systemDark = true))
    }

    @Test
    fun colorSchemesUseSpecifiedLightAndDarkAnchors() {
        val anchors = mapOf(
            AppTheme.DAWN_WHITE to Anchors(
                lightBackground = Color(0xFFFFFFFF),
                lightPrimary = Color(0xFF35665D),
                darkBackground = Color(0xFF121514),
                darkPrimary = Color(0xFF9DCDC1),
            ),
            AppTheme.JADE_MIST to Anchors(
                lightBackground = Color(0xFFFAFCFA),
                lightPrimary = Color(0xFF0B7468),
                darkBackground = Color(0xFF101613),
                darkPrimary = Color(0xFF7ED3C1),
            ),
            AppTheme.PEACH_BLOOM to Anchors(
                lightBackground = Color(0xFFFFF8FA),
                lightPrimary = Color(0xFF9C4164),
                darkBackground = Color(0xFF1A1115),
                darkPrimary = Color(0xFFFFB0C8),
            ),
            AppTheme.VIOLET_DUSK to Anchors(
                lightBackground = Color(0xFFFCF9FF),
                lightPrimary = Color(0xFF6B4EA0),
                darkBackground = Color(0xFF17131C),
                darkPrimary = Color(0xFFD4BAFF),
            ),
            AppTheme.OCEAN_BLUE to Anchors(
                lightBackground = Color(0xFFF8FAFF),
                lightPrimary = Color(0xFF315F9B),
                darkBackground = Color(0xFF101620),
                darkPrimary = Color(0xFFA9C7FF),
            ),
        )

        anchors.forEach { (theme, expected) ->
            val light = colorSchemeFor(theme, dark = false)
            val dark = colorSchemeFor(theme, dark = true)
            assertEquals(expected.lightBackground, light.background)
            assertEquals(expected.lightPrimary, light.primary)
            assertEquals(expected.darkBackground, dark.background)
            assertEquals(expected.darkPrimary, dark.primary)
        }
    }

    @Test
    fun amoledSchemeIsAlwaysTheSameTrueBlackScheme() {
        val requestedLight = colorSchemeFor(AppTheme.AMOLED_BLACK, dark = false)
        val requestedDark = colorSchemeFor(AppTheme.AMOLED_BLACK, dark = true)

        assertSame(requestedLight, requestedDark)
        assertEquals(Color.Black, requestedLight.background)
        assertEquals(Color.Black, requestedLight.surface)
        assertEquals(Color.Black, requestedLight.surfaceDim)
        assertEquals(Color.Black, requestedLight.surfaceContainerLowest)
        assertEquals(Color(0xFF080808), requestedLight.surfaceContainerLow)
        assertEquals(Color(0xFF101010), requestedLight.surfaceContainerHigh)
        assertEquals(Color(0xFF86D8C6), requestedLight.primary)
    }

    @Test
    fun semanticContentRolesMeetNormalTextContrast() {
        AppTheme.entries.forEach { theme ->
            val schemes = if (theme.forcesDarkTheme) {
                listOf("dark" to colorSchemeFor(theme, dark = true))
            } else {
                listOf(
                    "light" to colorSchemeFor(theme, dark = false),
                    "dark" to colorSchemeFor(theme, dark = true),
                )
            }
            schemes.forEach { (mode, scheme) ->
                assertRoleContrast(theme, mode, "background", scheme.onBackground, scheme.background)
                assertRoleContrast(theme, mode, "surface", scheme.onSurface, scheme.surface)
                assertRoleContrast(theme, mode, "surfaceVariant", scheme.onSurfaceVariant, scheme.surfaceVariant)
                assertRoleContrast(theme, mode, "primary", scheme.onPrimary, scheme.primary)
                assertRoleContrast(theme, mode, "primaryContainer", scheme.onPrimaryContainer, scheme.primaryContainer)
                assertRoleContrast(theme, mode, "secondary", scheme.onSecondary, scheme.secondary)
                assertRoleContrast(theme, mode, "secondaryContainer", scheme.onSecondaryContainer, scheme.secondaryContainer)
                assertRoleContrast(theme, mode, "tertiary", scheme.onTertiary, scheme.tertiary)
                assertRoleContrast(theme, mode, "tertiaryContainer", scheme.onTertiaryContainer, scheme.tertiaryContainer)
                assertRoleContrast(theme, mode, "error", scheme.onError, scheme.error)
                assertRoleContrast(theme, mode, "errorContainer", scheme.onErrorContainer, scheme.errorContainer)
            }
        }
    }

    @Test
    fun fixedRolesAreThemeStableAndMeetContrastOnBothFixedSurfaces() {
        AppTheme.entries.forEach { theme ->
            val light = colorSchemeFor(theme, dark = false)
            val dark = colorSchemeFor(theme, dark = true)
            assertEquals(light.primaryFixed, dark.primaryFixed)
            assertEquals(light.primaryFixedDim, dark.primaryFixedDim)
            assertEquals(light.onPrimaryFixed, dark.onPrimaryFixed)
            assertEquals(light.onPrimaryFixedVariant, dark.onPrimaryFixedVariant)
            assertEquals(light.secondaryFixed, dark.secondaryFixed)
            assertEquals(light.secondaryFixedDim, dark.secondaryFixedDim)
            assertEquals(light.onSecondaryFixed, dark.onSecondaryFixed)
            assertEquals(light.onSecondaryFixedVariant, dark.onSecondaryFixedVariant)
            assertEquals(light.tertiaryFixed, dark.tertiaryFixed)
            assertEquals(light.tertiaryFixedDim, dark.tertiaryFixedDim)
            assertEquals(light.onTertiaryFixed, dark.onTertiaryFixed)
            assertEquals(light.onTertiaryFixedVariant, dark.onTertiaryFixedVariant)

            assertFixedContrast(theme, "primary", light.onPrimaryFixed, light.onPrimaryFixedVariant, light.primaryFixed, light.primaryFixedDim)
            assertFixedContrast(theme, "secondary", light.onSecondaryFixed, light.onSecondaryFixedVariant, light.secondaryFixed, light.secondaryFixedDim)
            assertFixedContrast(theme, "tertiary", light.onTertiaryFixed, light.onTertiaryFixedVariant, light.tertiaryFixed, light.tertiaryFixedDim)
        }
    }

    private fun assertFixedContrast(
        theme: AppTheme,
        role: String,
        onFixed: Color,
        onFixedVariant: Color,
        fixed: Color,
        fixedDim: Color,
    ) {
        assertRoleContrast(theme, "fixed", "$role/onFixed", onFixed, fixed)
        assertRoleContrast(theme, "fixedDim", "$role/onFixed", onFixed, fixedDim)
        assertRoleContrast(theme, "fixed", "$role/onFixedVariant", onFixedVariant, fixed)
        assertRoleContrast(theme, "fixedDim", "$role/onFixedVariant", onFixedVariant, fixedDim)
    }

    private fun assertRoleContrast(
        theme: AppTheme,
        mode: String,
        role: String,
        foreground: Color,
        background: Color,
    ) {
        val contrast = contrastRatio(foreground, background)
        assertTrue(
            "${theme.storageValue} $mode $role contrast was $contrast",
            contrast >= MINIMUM_NORMAL_TEXT_CONTRAST,
        )
    }

    private fun contrastRatio(first: Color, second: Color): Float {
        val lighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    private data class Anchors(
        val lightBackground: Color,
        val lightPrimary: Color,
        val darkBackground: Color,
        val darkPrimary: Color,
    )

    private companion object {
        const val MINIMUM_NORMAL_TEXT_CONTRAST = 4.5f
    }
}
