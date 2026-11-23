package app.qrikkiyomi.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens for QRいっき読み (ART_DIRECTION v1.1, DESIGN_REVIEW 2026-11-14).
 *
 * Colour personality: deep navy / indigo as the one accent, soft blue-white
 * tints around it, neutral blue-grey surfaces. Saturated colour appears only
 * where it carries meaning (main action, detection pins, warnings, the
 * favourite star). Everything else stays quiet so the camera, the QR and the
 * one recommended action remain the loudest things on screen.
 */
object QrSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** Horizontal screen gutter — every screen uses this one value. */
    val gutter = 16.dp

    /** Vertical gap between sections on a scrolling screen. */
    val section = 24.dp
}

/** Role colours that Material does not carry (warnings, favourite star). */
object QrRoleColors {
    val warningBgLight = Color(0xFFFFF2CC)
    val warningFgLight = Color(0xFF664D00)
    val warningStrongBgLight = Color(0xFFF9E3E1)
    val warningStrongFgLight = Color(0xFF7E1B14)
    val warningBgDark = Color(0xFF4A3A0E)
    val warningFgDark = Color(0xFFF5D67B)
    val warningStrongBgDark = Color(0xFF5C2422)
    val warningStrongFgDark = Color(0xFFF6CFCB)
    val favoriteLight = Color(0xFFD18D00)
    val favoriteDark = Color(0xFFF2C14E)
}

/** Warning banner foreground/background pair for the current theme. */
@Composable
fun warningColors(strong: Boolean): Pair<Color, Color> =
    if (isSystemInDarkTheme()) {
        if (strong) {
            QrRoleColors.warningStrongBgDark to QrRoleColors.warningStrongFgDark
        } else {
            QrRoleColors.warningBgDark to QrRoleColors.warningFgDark
        }
    } else {
        if (strong) {
            QrRoleColors.warningStrongBgLight to QrRoleColors.warningStrongFgLight
        } else {
            QrRoleColors.warningBgLight to QrRoleColors.warningFgLight
        }
    }

/** The favourite star is the only place a warm accent is allowed. */
@Composable
fun favoriteColor(): Color =
    if (isSystemInDarkTheme()) QrRoleColors.favoriteDark else QrRoleColors.favoriteLight

// ---- Colour schemes -------------------------------------------------------

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B4C8C), // deep navy-blue: main action + detection
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E6F8), // soft blue-white tint
    onPrimaryContainer = Color(0xFF0A2A52),
    secondary = Color(0xFF46617E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0EAF4),
    onSecondaryContainer = Color(0xFF12293F),
    tertiary = Color(0xFF5E7A94),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCE9F4),
    onTertiaryContainer = Color(0xFF1C3448),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF11181F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF11181F),
    surfaceVariant = Color(0xFFE7EDF4),
    onSurfaceVariant = Color(0xFF44536A),
    outline = Color(0xFF75808F),
    outlineVariant = Color(0xFFC7D1DD),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7F2), // soft blue-white so it stays quiet on navy-black
    onPrimary = Color(0xFF0A2A52),
    primaryContainer = Color(0xFF1E4066),
    onPrimaryContainer = Color(0xFFD8E6F8),
    secondary = Color(0xFFB7C9DC),
    onSecondary = Color(0xFF1B2F44),
    secondaryContainer = Color(0xFF2C4358),
    onSecondaryContainer = Color(0xFFDCE9F5),
    tertiary = Color(0xFFA7C3DE),
    onTertiary = Color(0xFF14304A),
    tertiaryContainer = Color(0xFF274A66),
    onTertiaryContainer = Color(0xFFD6E8F7),
    background = Color(0xFF0B1118), // navy-black, not pure black
    onBackground = Color(0xFFE3EAF2),
    surface = Color(0xFF111A24),
    onSurface = Color(0xFFE3EAF2),
    surfaceVariant = Color(0xFF1D2A38),
    onSurfaceVariant = Color(0xFF9FB0C3),
    outline = Color(0xFF5B6B7D),
    outlineVariant = Color(0xFF2A394A),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

// ---- Typography -----------------------------------------------------------
// Three visible ranks only (art direction section 5): hero (headlineSmall /
// titleMedium semibold), body (bodyLarge / bodyMedium), notes (bodySmall /
// labelMedium muted). Headings carry their rank by weight, not by new sizes.
private val QrTypography = Typography(
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp,
    ),
)

// ---- Shapes ---------------------------------------------------------------
// One radius scale for the whole app: chips 8, buttons/fields/banners 12,
// cards and sheets 16/24. Nothing casts a shadow; sheets/dialogs only.
private val QrShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun QrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = QrTypography,
        shapes = QrShapes,
        content = content,
    )
}
