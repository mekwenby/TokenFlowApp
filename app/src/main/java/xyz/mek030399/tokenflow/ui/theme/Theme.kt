package xyz.mek030399.tokenflow.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

enum class AppTheme(val storageValue: String) {
    DAWN_WHITE("dawn_white"),
    JADE_MIST("jade_mist"),
    PEACH_BLOOM("peach_bloom"),
    VIOLET_DUSK("violet_dusk"),
    OCEAN_BLUE("ocean_blue"),
    AMOLED_BLACK("amoled_black");

    val previewColors: List<Color>
        get() = palette.previewColors

    val forcesDarkTheme: Boolean
        get() = palette.forceDarkTheme

    fun colorScheme(darkTheme: Boolean): ColorScheme =
        if (forcesDarkTheme || darkTheme) palette.dark else palette.light

    companion object {
        fun fromStorageValue(value: String?): AppTheme =
            entries.firstOrNull { it.storageValue == value } ?: DAWN_WHITE
    }
}

data class ThemePalette(
    val light: ColorScheme,
    val dark: ColorScheme,
    val previewColors: List<Color>,
    val forceDarkTheme: Boolean = false,
)

val LocalTokenFlowDarkTheme = staticCompositionLocalOf { false }

internal fun resolveDarkTheme(theme: AppTheme, systemDark: Boolean): Boolean =
    theme.forcesDarkTheme || systemDark

internal fun colorSchemeFor(theme: AppTheme, dark: Boolean): ColorScheme =
    theme.colorScheme(dark)

private data class FixedRoleColors(
    val primary: Color,
    val primaryDim: Color,
    val onPrimary: Color,
    val onPrimaryVariant: Color,
    val secondary: Color,
    val secondaryDim: Color,
    val onSecondary: Color,
    val onSecondaryVariant: Color,
    val tertiary: Color,
    val tertiaryDim: Color,
    val onTertiary: Color,
    val onTertiaryVariant: Color,
)

private val DawnWhiteFixed = FixedRoleColors(
    primary = Color(0xFFB8E9DD),
    primaryDim = Color(0xFF9DCDC1),
    onPrimary = Color(0xFF00201B),
    onPrimaryVariant = Color(0xFF1D5047),
    secondary = Color(0xFFD7E7E1),
    secondaryDim = Color(0xFFBBCBC5),
    onSecondary = Color(0xFF101F1B),
    onSecondaryVariant = Color(0xFF3C4F49),
    tertiary = Color(0xFFD0E6F0),
    tertiaryDim = Color(0xFFB4CAD4),
    onTertiary = Color(0xFF071E26),
    onTertiaryVariant = Color(0xFF354B54),
)

private val JadeMistFixed = FixedRoleColors(
    primary = Color(0xFFA2EFDE),
    primaryDim = Color(0xFF7ED3C1),
    onPrimary = Color(0xFF00201B),
    onPrimaryVariant = Color(0xFF005047),
    secondary = Color(0xFFBAEDDA),
    secondaryDim = Color(0xFF9ED1BD),
    onSecondary = Color(0xFF002019),
    onSecondaryVariant = Color(0xFF214E43),
    tertiary = Color(0xFFBCE9FA),
    tertiaryDim = Color(0xFF88CEEA),
    onTertiary = Color(0xFF001F29),
    onTertiaryVariant = Color(0xFF004D62),
)

private val PeachBloomFixed = FixedRoleColors(
    primary = Color(0xFFFFD9E3),
    primaryDim = Color(0xFFFFB0C8),
    onPrimary = Color(0xFF3D001F),
    onPrimaryVariant = Color(0xFF7E294D),
    secondary = Color(0xFFFFD9E1),
    secondaryDim = Color(0xFFE5BDC7),
    onSecondary = Color(0xFF2C151C),
    onSecondaryVariant = Color(0xFF5B3F47),
    tertiary = Color(0xFFFFDCC1),
    tertiaryDim = Color(0xFFEEBD91),
    onTertiary = Color(0xFF2E1500),
    onTertiaryVariant = Color(0xFF63401F),
)

private val VioletDuskFixed = FixedRoleColors(
    primary = Color(0xFFEBDDFF),
    primaryDim = Color(0xFFD4BAFF),
    onPrimary = Color(0xFF270C57),
    onPrimaryVariant = Color(0xFF533783),
    secondary = Color(0xFFEBDDF7),
    secondaryDim = Color(0xFFCFC1DA),
    onSecondary = Color(0xFF21182A),
    onSecondaryVariant = Color(0xFF4D4357),
    tertiary = Color(0xFFFFD9E2),
    tertiaryDim = Color(0xFFF1B7C6),
    onTertiary = Color(0xFF32101D),
    onTertiaryVariant = Color(0xFF653B48),
)

private val OceanBlueFixed = FixedRoleColors(
    primary = Color(0xFFD6E3FF),
    primaryDim = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF001B3D),
    onPrimaryVariant = Color(0xFF16477F),
    secondary = Color(0xFFDAE2F9),
    secondaryDim = Color(0xFFBEC6DC),
    onSecondary = Color(0xFF131C2B),
    onSecondaryVariant = Color(0xFF3E4759),
    tertiary = Color(0xFFFAD8FC),
    tertiaryDim = Color(0xFFDDBCE0),
    onTertiary = Color(0xFF29132D),
    onTertiaryVariant = Color(0xFF573E5B),
)

private val AmoledBlackFixed = FixedRoleColors(
    primary = Color(0xFFA5F2DF),
    primaryDim = Color(0xFF86D8C6),
    onPrimary = Color(0xFF00201A),
    onPrimaryVariant = Color(0xFF123F37),
    secondary = Color(0xFFD1E8E0),
    secondaryDim = Color(0xFFB5CCC5),
    onSecondary = Color(0xFF0B1F1A),
    onSecondaryVariant = Color(0xFF283F39),
    tertiary = Color(0xFFC4E7FC),
    tertiaryDim = Color(0xFFA8CBE0),
    onTertiary = Color(0xFF001F2A),
    onTertiaryVariant = Color(0xFF244555),
)

private val DawnWhiteLight = appLightColorScheme(
    fixedColors = DawnWhiteFixed,
    primary = Color(0xFF35665D),
    primaryContainer = Color(0xFFD8EDE7),
    onPrimaryContainer = Color(0xFF123A33),
    secondary = Color(0xFF536761),
    secondaryContainer = Color(0xFFDDE9E5),
    onSecondaryContainer = Color(0xFF283B36),
    tertiary = Color(0xFF46636F),
    tertiaryContainer = Color(0xFFD7E8EF),
    onTertiaryContainer = Color(0xFF203942),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFE7EEEB),
    onSurfaceVariant = Color(0xFF414946),
    surfaceDim = Color(0xFFD9DDDB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F8F7),
    surfaceContainer = Color(0xFFF0F3F1),
    surfaceContainerHigh = Color(0xFFEAEDEB),
    surfaceContainerHighest = Color(0xFFE4E8E6),
    outline = Color(0xFF717976),
    outlineVariant = Color(0xFFC1C9C5),
    inversePrimary = Color(0xFF9DCDC1),
)

private val DawnWhiteDark = appDarkColorScheme(
    fixedColors = DawnWhiteFixed,
    primary = Color(0xFF9DCDC1),
    onPrimary = Color(0xFF05372F),
    primaryContainer = Color(0xFF224E46),
    onPrimaryContainer = Color(0xFFB8E9DD),
    secondary = Color(0xFFBBCBC5),
    onSecondary = Color(0xFF263832),
    secondaryContainer = Color(0xFF3C4F49),
    onSecondaryContainer = Color(0xFFD7E7E1),
    tertiary = Color(0xFFB4CAD4),
    onTertiary = Color(0xFF1E343D),
    tertiaryContainer = Color(0xFF354B54),
    onTertiaryContainer = Color(0xFFD0E6F0),
    background = Color(0xFF121514),
    onBackground = Color(0xFFE1E3E1),
    surface = Color(0xFF121514),
    surfaceVariant = Color(0xFF414946),
    onSurfaceVariant = Color(0xFFC1C9C5),
    surfaceBright = Color(0xFF383B39),
    surfaceDim = Color(0xFF121514),
    surfaceContainerLowest = Color(0xFF0D0F0E),
    surfaceContainerLow = Color(0xFF1A1D1C),
    surfaceContainer = Color(0xFF1E2120),
    surfaceContainerHigh = Color(0xFF292C2A),
    surfaceContainerHighest = Color(0xFF343735),
    outline = Color(0xFF8B938F),
    outlineVariant = Color(0xFF414946),
    inversePrimary = Color(0xFF35665D),
)

private val JadeMistLight = appLightColorScheme(
    fixedColors = JadeMistFixed,
    primary = Color(0xFF0B7468),
    primaryContainer = Color(0xFFBCECE1),
    onPrimaryContainer = Color(0xFF003A32),
    secondary = Color(0xFF496B63),
    secondaryContainer = Color(0xFFD0E9E1),
    onSecondaryContainer = Color(0xFF173D36),
    tertiary = Color(0xFF176B87),
    tertiaryContainer = Color(0xFFC5E7F4),
    onTertiaryContainer = Color(0xFF003544),
    background = Color(0xFFFAFCFA),
    onBackground = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDDEBE6),
    onSurfaceVariant = Color(0xFF3E4945),
    surfaceDim = Color(0xFFD8DDD9),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F7F5),
    surfaceContainer = Color(0xFFEEF2EF),
    surfaceContainerHigh = Color(0xFFE8ECE9),
    surfaceContainerHighest = Color(0xFFE2E7E4),
    outline = Color(0xFF6E7975),
    outlineVariant = Color(0xFFBDC9C4),
    inversePrimary = Color(0xFF7ED3C1),
)

private val JadeMistDark = appDarkColorScheme(
    fixedColors = JadeMistFixed,
    primary = Color(0xFF7ED3C1),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005047),
    onPrimaryContainer = Color(0xFFA2EFDE),
    secondary = Color(0xFF9ED1BD),
    onSecondary = Color(0xFF06372D),
    secondaryContainer = Color(0xFF214E43),
    onSecondaryContainer = Color(0xFFBAEDDA),
    tertiary = Color(0xFF88CEEA),
    onTertiary = Color(0xFF003544),
    tertiaryContainer = Color(0xFF004D62),
    onTertiaryContainer = Color(0xFFBCE9FA),
    background = Color(0xFF101613),
    onBackground = Color(0xFFDFE4E0),
    surface = Color(0xFF101613),
    surfaceVariant = Color(0xFF3E4945),
    onSurfaceVariant = Color(0xFFBDC9C4),
    surfaceBright = Color(0xFF353C38),
    surfaceDim = Color(0xFF101613),
    surfaceContainerLowest = Color(0xFF0A100D),
    surfaceContainerLow = Color(0xFF181E1B),
    surfaceContainer = Color(0xFF1C221F),
    surfaceContainerHigh = Color(0xFF262C29),
    surfaceContainerHighest = Color(0xFF313733),
    outline = Color(0xFF87938E),
    outlineVariant = Color(0xFF3E4945),
    inversePrimary = Color(0xFF0B7468),
)

private val PeachBloomLight = appLightColorScheme(
    fixedColors = PeachBloomFixed,
    primary = Color(0xFF9C4164),
    primaryContainer = Color(0xFFFFD9E3),
    onPrimaryContainer = Color(0xFF3D001F),
    secondary = Color(0xFF75565F),
    secondaryContainer = Color(0xFFFFD9E1),
    onSecondaryContainer = Color(0xFF2C151C),
    tertiary = Color(0xFF7D5635),
    tertiaryContainer = Color(0xFFFFDCC1),
    onTertiaryContainer = Color(0xFF2E1500),
    background = Color(0xFFFFF8FA),
    onBackground = Color(0xFF21191C),
    surfaceVariant = Color(0xFFF3DDE3),
    onSurfaceVariant = Color(0xFF514347),
    surfaceDim = Color(0xFFE5D6D9),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF0F4),
    surfaceContainer = Color(0xFFFAEAEE),
    surfaceContainerHigh = Color(0xFFF4E4E8),
    surfaceContainerHighest = Color(0xFFEEDFE2),
    outline = Color(0xFF837377),
    outlineVariant = Color(0xFFD6C2C7),
    inversePrimary = Color(0xFFFFB0C8),
)

private val PeachBloomDark = appDarkColorScheme(
    fixedColors = PeachBloomFixed,
    primary = Color(0xFFFFB0C8),
    onPrimary = Color(0xFF5F1137),
    primaryContainer = Color(0xFF7E294D),
    onPrimaryContainer = Color(0xFFFFD9E3),
    secondary = Color(0xFFE5BDC7),
    onSecondary = Color(0xFF432930),
    secondaryContainer = Color(0xFF5B3F47),
    onSecondaryContainer = Color(0xFFFFD9E1),
    tertiary = Color(0xFFEEBD91),
    onTertiary = Color(0xFF48290E),
    tertiaryContainer = Color(0xFF63401F),
    onTertiaryContainer = Color(0xFFFFDCC1),
    background = Color(0xFF1A1115),
    onBackground = Color(0xFFF0DEE3),
    surface = Color(0xFF1A1115),
    surfaceVariant = Color(0xFF514347),
    onSurfaceVariant = Color(0xFFD6C2C7),
    surfaceBright = Color(0xFF44363A),
    surfaceDim = Color(0xFF1A1115),
    surfaceContainerLowest = Color(0xFF140C10),
    surfaceContainerLow = Color(0xFF23191D),
    surfaceContainer = Color(0xFF271D21),
    surfaceContainerHigh = Color(0xFF32272B),
    surfaceContainerHighest = Color(0xFF3D3236),
    outline = Color(0xFF9E8C91),
    outlineVariant = Color(0xFF514347),
    inversePrimary = Color(0xFF9C4164),
)

private val VioletDuskLight = appLightColorScheme(
    fixedColors = VioletDuskFixed,
    primary = Color(0xFF6B4EA0),
    primaryContainer = Color(0xFFEBDDFF),
    onPrimaryContainer = Color(0xFF270C57),
    secondary = Color(0xFF655A70),
    secondaryContainer = Color(0xFFEBDDF7),
    onSecondaryContainer = Color(0xFF21182A),
    tertiary = Color(0xFF80515F),
    tertiaryContainer = Color(0xFFFFD9E2),
    onTertiaryContainer = Color(0xFF32101D),
    background = Color(0xFFFCF9FF),
    onBackground = Color(0xFF1D1A20),
    surfaceVariant = Color(0xFFE9E0EB),
    onSurfaceVariant = Color(0xFF4B454D),
    surfaceDim = Color(0xFFDDD8DF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F3FA),
    surfaceContainer = Color(0xFFF1EDF4),
    surfaceContainerHigh = Color(0xFFEBE7EE),
    surfaceContainerHighest = Color(0xFFE5E1E8),
    outline = Color(0xFF7C747E),
    outlineVariant = Color(0xFFCDC4CF),
    inversePrimary = Color(0xFFD4BAFF),
)

private val VioletDuskDark = appDarkColorScheme(
    fixedColors = VioletDuskFixed,
    primary = Color(0xFFD4BAFF),
    onPrimary = Color(0xFF3B246C),
    primaryContainer = Color(0xFF533783),
    onPrimaryContainer = Color(0xFFEBDDFF),
    secondary = Color(0xFFCFC1DA),
    onSecondary = Color(0xFF362D40),
    secondaryContainer = Color(0xFF4D4357),
    onSecondaryContainer = Color(0xFFEBDDF7),
    tertiary = Color(0xFFF1B7C6),
    onTertiary = Color(0xFF4A2532),
    tertiaryContainer = Color(0xFF653B48),
    onTertiaryContainer = Color(0xFFFFD9E2),
    background = Color(0xFF17131C),
    onBackground = Color(0xFFE8E0EB),
    surface = Color(0xFF17131C),
    surfaceVariant = Color(0xFF4B454D),
    onSurfaceVariant = Color(0xFFCDC4CF),
    surfaceBright = Color(0xFF3D3841),
    surfaceDim = Color(0xFF17131C),
    surfaceContainerLowest = Color(0xFF110E16),
    surfaceContainerLow = Color(0xFF1F1B24),
    surfaceContainer = Color(0xFF231F28),
    surfaceContainerHigh = Color(0xFF2E2933),
    surfaceContainerHighest = Color(0xFF39343E),
    outline = Color(0xFF968E98),
    outlineVariant = Color(0xFF4B454D),
    inversePrimary = Color(0xFF6B4EA0),
)

private val OceanBlueLight = appLightColorScheme(
    fixedColors = OceanBlueFixed,
    primary = Color(0xFF315F9B),
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B3D),
    secondary = Color(0xFF565F71),
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF131C2B),
    tertiary = Color(0xFF705573),
    tertiaryContainer = Color(0xFFFAD8FC),
    onTertiaryContainer = Color(0xFF29132D),
    background = Color(0xFFF8FAFF),
    onBackground = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44474E),
    surfaceDim = Color(0xFFD8DAE0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F4F9),
    surfaceContainer = Color(0xFFECEEF3),
    surfaceContainerHigh = Color(0xFFE6E8ED),
    surfaceContainerHighest = Color(0xFFE0E2E8),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6CF),
    inversePrimary = Color(0xFFA9C7FF),
)

private val OceanBlueDark = appDarkColorScheme(
    fixedColors = OceanBlueFixed,
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF003064),
    primaryContainer = Color(0xFF16477F),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFBEC6DC),
    onSecondary = Color(0xFF283141),
    secondaryContainer = Color(0xFF3E4759),
    onSecondaryContainer = Color(0xFFDAE2F9),
    tertiary = Color(0xFFDDBCE0),
    onTertiary = Color(0xFF3F2843),
    tertiaryContainer = Color(0xFF573E5B),
    onTertiaryContainer = Color(0xFFFAD8FC),
    background = Color(0xFF101620),
    onBackground = Color(0xFFE1E2E8),
    surface = Color(0xFF101620),
    surfaceVariant = Color(0xFF44474E),
    onSurfaceVariant = Color(0xFFC4C6CF),
    surfaceBright = Color(0xFF363941),
    surfaceDim = Color(0xFF101620),
    surfaceContainerLowest = Color(0xFF0B111A),
    surfaceContainerLow = Color(0xFF181E28),
    surfaceContainer = Color(0xFF1C222C),
    surfaceContainerHigh = Color(0xFF272D37),
    surfaceContainerHighest = Color(0xFF323842),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474E),
    inversePrimary = Color(0xFF315F9B),
)

private val AmoledBlackDark = appDarkColorScheme(
    fixedColors = AmoledBlackFixed,
    primary = Color(0xFF86D8C6),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF123F37),
    onPrimaryContainer = Color(0xFFA5F2DF),
    secondary = Color(0xFFB5CCC5),
    onSecondary = Color(0xFF203832),
    secondaryContainer = Color(0xFF283F39),
    onSecondaryContainer = Color(0xFFD1E8E0),
    tertiary = Color(0xFFA8CBE0),
    onTertiary = Color(0xFF0C3445),
    tertiaryContainer = Color(0xFF244555),
    onTertiaryContainer = Color(0xFFC4E7FC),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF000000),
    surfaceVariant = Color(0xFF101010),
    onSurfaceVariant = Color(0xFFC7C7C7),
    surfaceBright = Color(0xFF101010),
    surfaceDim = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF080808),
    surfaceContainer = Color(0xFF080808),
    surfaceContainerHigh = Color(0xFF101010),
    surfaceContainerHighest = Color(0xFF101010),
    outline = Color(0xFF8E918F),
    outlineVariant = Color(0xFF3C403E),
    inversePrimary = Color(0xFF35665D),
)

private val themePalettes = mapOf(
    AppTheme.DAWN_WHITE to ThemePalette(
        light = DawnWhiteLight,
        dark = DawnWhiteDark,
        previewColors = listOf(Color(0xFF35665D), Color(0xFFD8EDE7), Color(0xFFFFFFFF)),
    ),
    AppTheme.JADE_MIST to ThemePalette(
        light = JadeMistLight,
        dark = JadeMistDark,
        previewColors = listOf(Color(0xFF0B7468), Color(0xFFBCECE1), Color(0xFFFAFCFA)),
    ),
    AppTheme.PEACH_BLOOM to ThemePalette(
        light = PeachBloomLight,
        dark = PeachBloomDark,
        previewColors = listOf(Color(0xFF9C4164), Color(0xFFFFD9E3), Color(0xFFFFF8FA)),
    ),
    AppTheme.VIOLET_DUSK to ThemePalette(
        light = VioletDuskLight,
        dark = VioletDuskDark,
        previewColors = listOf(Color(0xFF6B4EA0), Color(0xFFEBDDFF), Color(0xFFFCF9FF)),
    ),
    AppTheme.OCEAN_BLUE to ThemePalette(
        light = OceanBlueLight,
        dark = OceanBlueDark,
        previewColors = listOf(Color(0xFF315F9B), Color(0xFFD6E3FF), Color(0xFFF8FAFF)),
    ),
    AppTheme.AMOLED_BLACK to ThemePalette(
        light = AmoledBlackDark,
        dark = AmoledBlackDark,
        previewColors = listOf(Color(0xFF86D8C6), Color(0xFF101010), Color(0xFF000000)),
        forceDarkTheme = true,
    ),
)

private val AppTheme.palette: ThemePalette
    get() = themePalettes.getValue(this)

private fun appLightColorScheme(
    fixedColors: FixedRoleColors,
    primary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
    secondary: Color,
    secondaryContainer: Color,
    onSecondaryContainer: Color,
    tertiary: Color,
    tertiaryContainer: Color,
    onTertiaryContainer: Color,
    background: Color,
    onBackground: Color,
    surfaceVariant: Color,
    onSurfaceVariant: Color,
    surfaceDim: Color,
    surfaceContainerLowest: Color,
    surfaceContainerLow: Color,
    surfaceContainer: Color,
    surfaceContainerHigh: Color,
    surfaceContainerHighest: Color,
    outline: Color,
    outlineVariant: Color,
    inversePrimary: Color,
): ColorScheme = lightColorScheme(
    primary = primary,
    onPrimary = Color.White,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    inversePrimary = inversePrimary,
    secondary = secondary,
    onSecondary = Color.White,
    secondaryContainer = secondaryContainer,
    onSecondaryContainer = onSecondaryContainer,
    tertiary = tertiary,
    onTertiary = Color.White,
    tertiaryContainer = tertiaryContainer,
    onTertiaryContainer = onTertiaryContainer,
    background = background,
    onBackground = onBackground,
    surface = background,
    onSurface = onBackground,
    surfaceVariant = surfaceVariant,
    onSurfaceVariant = onSurfaceVariant,
    surfaceTint = primary,
    inverseSurface = onBackground,
    inverseOnSurface = background,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = outline,
    outlineVariant = outlineVariant,
    scrim = Color.Black,
    surfaceBright = surfaceContainerLowest,
    surfaceDim = surfaceDim,
    surfaceContainer = surfaceContainer,
    surfaceContainerHigh = surfaceContainerHigh,
    surfaceContainerHighest = surfaceContainerHighest,
    surfaceContainerLow = surfaceContainerLow,
    surfaceContainerLowest = surfaceContainerLowest,
    primaryFixed = fixedColors.primary,
    primaryFixedDim = fixedColors.primaryDim,
    onPrimaryFixed = fixedColors.onPrimary,
    onPrimaryFixedVariant = fixedColors.onPrimaryVariant,
    secondaryFixed = fixedColors.secondary,
    secondaryFixedDim = fixedColors.secondaryDim,
    onSecondaryFixed = fixedColors.onSecondary,
    onSecondaryFixedVariant = fixedColors.onSecondaryVariant,
    tertiaryFixed = fixedColors.tertiary,
    tertiaryFixedDim = fixedColors.tertiaryDim,
    onTertiaryFixed = fixedColors.onTertiary,
    onTertiaryFixedVariant = fixedColors.onTertiaryVariant,
)

private fun appDarkColorScheme(
    fixedColors: FixedRoleColors,
    primary: Color,
    onPrimary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
    secondary: Color,
    onSecondary: Color,
    secondaryContainer: Color,
    onSecondaryContainer: Color,
    tertiary: Color,
    onTertiary: Color,
    tertiaryContainer: Color,
    onTertiaryContainer: Color,
    background: Color,
    onBackground: Color,
    surface: Color,
    surfaceVariant: Color,
    onSurfaceVariant: Color,
    surfaceBright: Color,
    surfaceDim: Color,
    surfaceContainerLowest: Color,
    surfaceContainerLow: Color,
    surfaceContainer: Color,
    surfaceContainerHigh: Color,
    surfaceContainerHighest: Color,
    outline: Color,
    outlineVariant: Color,
    inversePrimary: Color,
): ColorScheme = darkColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    inversePrimary = inversePrimary,
    secondary = secondary,
    onSecondary = onSecondary,
    secondaryContainer = secondaryContainer,
    onSecondaryContainer = onSecondaryContainer,
    tertiary = tertiary,
    onTertiary = onTertiary,
    tertiaryContainer = tertiaryContainer,
    onTertiaryContainer = onTertiaryContainer,
    background = background,
    onBackground = onBackground,
    surface = surface,
    onSurface = onBackground,
    surfaceVariant = surfaceVariant,
    onSurfaceVariant = onSurfaceVariant,
    surfaceTint = primary,
    inverseSurface = onBackground,
    inverseOnSurface = background,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = outline,
    outlineVariant = outlineVariant,
    scrim = Color.Black,
    surfaceBright = surfaceBright,
    surfaceDim = surfaceDim,
    surfaceContainer = surfaceContainer,
    surfaceContainerHigh = surfaceContainerHigh,
    surfaceContainerHighest = surfaceContainerHighest,
    surfaceContainerLow = surfaceContainerLow,
    surfaceContainerLowest = surfaceContainerLowest,
    primaryFixed = fixedColors.primary,
    primaryFixedDim = fixedColors.primaryDim,
    onPrimaryFixed = fixedColors.onPrimary,
    onPrimaryFixedVariant = fixedColors.onPrimaryVariant,
    secondaryFixed = fixedColors.secondary,
    secondaryFixedDim = fixedColors.secondaryDim,
    onSecondaryFixed = fixedColors.onSecondary,
    onSecondaryFixedVariant = fixedColors.onSecondaryVariant,
    tertiaryFixed = fixedColors.tertiary,
    tertiaryFixedDim = fixedColors.tertiaryDim,
    onTertiaryFixed = fixedColors.onTertiary,
    onTertiaryFixedVariant = fixedColors.onTertiaryVariant,
)

private val TokenFlowShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

@Composable
fun TokenFlowTheme(
    theme: AppTheme = AppTheme.DAWN_WHITE,
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val actualDarkTheme = resolveDarkTheme(theme, darkTheme ?: isSystemInDarkTheme())
    val colors = colorSchemeFor(theme, actualDarkTheme)
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            view.context.findActivity()?.window?.let { window ->
                window.statusBarColor = colors.background.toArgb()
                window.navigationBarColor = colors.background.toArgb()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !actualDarkTheme
                    isAppearanceLightNavigationBars = !actualDarkTheme
                }
            }
        }
    }

    CompositionLocalProvider(LocalTokenFlowDarkTheme provides actualDarkTheme) {
        MaterialTheme(colorScheme = colors, shapes = TokenFlowShapes, content = content)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
