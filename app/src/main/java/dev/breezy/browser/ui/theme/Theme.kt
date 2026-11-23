package dev.breezy.browser.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.breezy.browser.R

private val BreezyDark = darkColorScheme(
    primary = Color(0xFF9CD3FF),
    onPrimary = Color(0xFF00344F),
    primaryContainer = Color(0xFF004C71),
    onPrimaryContainer = Color(0xFFCCE6FF),
    secondary = Color(0xFFB6C9D8),
    tertiary = Color(0xFFCEC0E8),
    tertiaryContainer = Color(0xFF4C4263),
    onTertiaryContainer = Color(0xFFEADDFF),
)

// Google Sans Flex (variable: wght 100-1000, ROND 0-100), bundled in res/font.
// Body text uses the standard shapes; display/headline/title styles use the rounded (ROND=100)
// cut that Material 3 Expressive pairs with it.
private fun flex(weight: Int, round: Float) = Font(
    R.font.google_sans_flex,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.Setting("ROND", round),
    ),
)

private val weights = listOf(300, 400, 500, 600, 700, 800, 900)
val GoogleSansFlex = FontFamily(weights.map { flex(it, 0f) })
val GoogleSansFlexRounded = FontFamily(weights.map { flex(it, 100f) })

private val BaseType = Typography()
private fun TextStyle.flex(weight: FontWeight? = null) = copy(fontFamily = GoogleSansFlex, fontWeight = weight ?: fontWeight)
private fun TextStyle.rounded(weight: FontWeight? = null) = copy(fontFamily = GoogleSansFlexRounded, fontWeight = weight ?: fontWeight)

private val BreezyType = Typography(
    displayLarge = BaseType.displayLarge.rounded(FontWeight.Black),
    displayMedium = BaseType.displayMedium.rounded(FontWeight.Black),
    displaySmall = BaseType.displaySmall.rounded(FontWeight.ExtraBold),
    headlineLarge = BaseType.headlineLarge.rounded(FontWeight.Bold),
    headlineMedium = BaseType.headlineMedium.rounded(FontWeight.Bold),
    headlineSmall = BaseType.headlineSmall.rounded(FontWeight.SemiBold),
    titleLarge = BaseType.titleLarge.rounded(FontWeight.SemiBold),
    titleMedium = BaseType.titleMedium.flex(FontWeight.SemiBold),
    titleSmall = BaseType.titleSmall.flex(FontWeight.SemiBold),
    bodyLarge = BaseType.bodyLarge.flex(),
    bodyMedium = BaseType.bodyMedium.flex(),
    bodySmall = BaseType.bodySmall.flex(),
    labelLarge = BaseType.labelLarge.flex(FontWeight.SemiBold),
    labelMedium = BaseType.labelMedium.flex(FontWeight.Medium),
    labelSmall = BaseType.labelSmall.flex(FontWeight.Medium),
)

/** Material 3 Expressive theme: dynamic color on Android 12+, expressive motion everywhere. */
@Composable
fun BreezyTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> BreezyDark
        else -> expressiveLightColorScheme()
    }
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        typography = BreezyType,
        content = content,
    )
}
