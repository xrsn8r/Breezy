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
import androidx.compose.ui.text.font.FontWeight

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

private val BaseType = Typography()
private val BreezyType = BaseType.copy(
    displayMedium = BaseType.displayMedium.copy(fontWeight = FontWeight.Black),
    titleLarge = BaseType.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = BaseType.labelLarge.copy(fontWeight = FontWeight.SemiBold),
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
