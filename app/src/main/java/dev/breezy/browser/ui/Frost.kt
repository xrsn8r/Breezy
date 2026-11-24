package dev.breezy.browser.ui

import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import dev.breezy.browser.AppSettings
import java.util.function.Consumer

/** True inside a FrostedSheet while its blur is on, so cards and buttons can go translucent too. */
val LocalFrosted = staticCompositionLocalOf { false }

/**
 * Background for a card or button inside a frosted sheet: a translucent, darker glass when the
 * sheet is blurred (so the frost shows through), [solid] otherwise. [tint] keeps an accent's hue.
 */
@Composable
fun frostedFill(solid: Color, tint: Color? = null): Color {
    if (!LocalFrosted.current) return solid
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when {
        tint != null -> tint.copy(alpha = if (dark) 0.42f else 0.55f)
        dark -> Color.Black.copy(alpha = 0.34f)
        else -> Color.White.copy(alpha = 0.5f)
    }
}

/**
 * A bottom sheet in its own bottom-anchored window, frosted like the toolbar.
 *
 * The system compositor blurs only the area behind this window (Window.setBackgroundBlurRadius,
 * Android 12+) and dims the rest of the screen, so the page above the sheet stays sharp. Because
 * the compositor does it after everything is drawn, it can't disturb WebView rendering (unlike
 * in-app backdrop blur). Falls back to a solid sheet when Frosted surfaces is off, before
 * Android 12, or while the system has blur turned off (battery saver, some devices).
 *
 * Closes on a tap outside or Back.
 */
@Composable
fun FrostedSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        val window = ((view as? DialogWindowProvider) ?: (view.parent as? DialogWindowProvider))?.window
        val density = LocalDensity.current
        val base = MaterialTheme.colorScheme.surfaceContainerLow
        val enabled = AppSettings.frostedSurfaces
        val blurred = remember { mutableStateOf(false) }
        val cornerPx = with(density) { 28.dp.toPx() }
        // A thin light edge (dark in light theme), brightest along the top and fading down the
        // sides, like light catching the glass.
        val edge = if (base.luminance() < 0.5f) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.12f)
        val fadePx = with(density) { 140.dp.toPx() }
        val blurPx = with(density) { 36.dp.roundToPx() }

        DisposableEffect(window, enabled, base) {
            if (window == null) return@DisposableEffect onDispose { }
            window.setGravity(Gravity.BOTTOM)
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setWindowAnimations(android.R.style.Animation_InputMethod) // slide up from the bottom
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(0.32f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.attributes = window.attributes.apply { fitInsetsTypes = 0 } // extend under the nav bar
            }

            fun apply(blur: Boolean) {
                blurred.value = blur
                // The blurred region copies this drawable's outline, which only works for a uniform
                // round rect (per-corner radii fall back to a plain rectangle and leave sharp
                // corners). So round all four corners and push the bottom two below the window.
                val shape = GradientDrawable().apply {
                    cornerRadius = cornerPx
                    setColor(base.copy(alpha = if (blur) 0.6f else 1f).toArgb())
                }
                window.setBackgroundDrawable(android.graphics.drawable.InsetDrawable(shape, 0, 0, 0, -cornerPx.toInt()))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setBackgroundBlurRadius(if (blur) blurPx else 0)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && enabled) {
                val listener = Consumer<Boolean> { allowed -> apply(allowed) }
                window.windowManager.addCrossWindowBlurEnabledListener(listener) // called now, then on changes
                onDispose { window.windowManager.removeCrossWindowBlurEnabledListener(listener) }
            } else {
                apply(false)
                onDispose { }
            }
        }

        // A Dialog has no Surface around it, so set the content colour ourselves (otherwise text
        // and icons default to black, unreadable in dark mode).
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurface,
            LocalFrosted provides blurred.value,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to edge, 1f to Color.Transparent, startY = 0f, endY = fadePx,
                        ),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    )
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.9f).dp)
                    .navigationBarsPadding(),
                content = content,
            )
        }
    }
}

/**
 * A frozen frosted backdrop for full-screen overlays (the search screen): a downscaled snapshot
 * of the window taken just before the overlay opens, blurred in-app.
 *
 * Live full-screen compositor blur flickered over pages playing video (the video layer switches
 * composition paths each frame); a still image can't flicker. Calls back on the main thread with
 * null when frosting is off, before Android 12 (no RenderEffect blur), or if the copy fails.
 */
fun captureBackdrop(activity: android.app.Activity?, onResult: (android.graphics.Bitmap?) -> Unit) {
    val window = activity?.window
    val decor = window?.decorView
    if (!AppSettings.frostedSurfaces || Build.VERSION.SDK_INT < Build.VERSION_CODES.S || decor == null || decor.width == 0) {
        onResult(null)
        return
    }
    // A quarter-size copy: cheap, and it's going to be blurred anyway.
    val bitmap = android.graphics.Bitmap.createBitmap(
        (decor.width / 4).coerceAtLeast(1), (decor.height / 4).coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888,
    )
    try {
        android.view.PixelCopy.request(window, bitmap, { result ->
            onResult(if (result == android.view.PixelCopy.SUCCESS) bitmap else null)
        }, android.os.Handler(android.os.Looper.getMainLooper()))
    } catch (e: Exception) {
        onResult(null)
    }
}

/** Draws a captured backdrop, blurred and tinted, filling the parent. */
@Composable
fun FrostedBackdrop(image: androidx.compose.ui.graphics.ImageBitmap) {
    androidx.compose.foundation.Image(
        image,
        contentDescription = null,
        contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
        modifier = Modifier.fillMaxSize().blur(36.dp),
    )
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f)),
    )
}

/** The Activity behind a Compose context (contexts can be wrapped). */
tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
