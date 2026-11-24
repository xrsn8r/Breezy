package dev.breezy.browser.ui

import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import dev.breezy.browser.AppSettings
import java.util.function.Consumer

/**
 * Frosted sheets: the page behind a bottom sheet is blurred by the system compositor
 * (cross-window blur, Android 12+), and the sheet itself is slightly translucent.
 *
 * The compositor blurs after everything is drawn, so unlike in-app backdrop blur it can't upset
 * WebView rendering. Android turns cross-window blur off by itself (battery saver, some
 * devices); we listen for that and fall back to a solid sheet.
 */
class Frost internal constructor(internal val active: MutableState<Boolean>) {
    /** Sheet background: translucent while the blur is on, the normal surface otherwise. */
    @Composable
    fun containerColor(base: Color = MaterialTheme.colorScheme.surfaceContainerLow): Color =
        if (active.value) base.copy(alpha = 0.78f) else base

    /** A lighter scrim while blurred: the blur already separates the sheet from the page. */
    @Composable
    fun scrimColor(): Color = if (active.value) Color.Black.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.32f)
}

@Composable
fun rememberFrost(): Frost = remember { Frost(mutableStateOf(false)) }

/**
 * Call inside a ModalBottomSheet's content: turns on blur-behind for the sheet's window (animated
 * in) when Frosted surfaces is on and the system allows it.
 */
@Composable
fun FrostWindow(frost: Frost, radiusDp: Int = 36) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val view = LocalView.current
    val window = ((view as? DialogWindowProvider) ?: (view.parent as? DialogWindowProvider))?.window ?: return
    val enabled = AppSettings.frostedSurfaces
    val radiusPx = with(LocalDensity.current) { radiusDp.dp.roundToPx() }
    val blur = remember { Animatable(0f) }

    DisposableEffect(window, enabled) {
        if (!enabled) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            frost.active.value = false
            return@DisposableEffect onDispose { }
        }
        val listener = Consumer<Boolean> { allowed ->
            if (allowed) window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            frost.active.value = allowed
        }
        // Called right away with the current state, then on every change.
        window.windowManager.addCrossWindowBlurEnabledListener(listener)
        onDispose { window.windowManager.removeCrossWindowBlurEnabledListener(listener) }
    }
    LaunchedEffect(frost.active.value) {
        if (!frost.active.value) return@LaunchedEffect
        blur.snapTo(0f)
        blur.animateTo(radiusPx.toFloat(), tween(220)) {
            window.attributes = window.attributes.apply { blurBehindRadius = value.toInt() }
        }
    }
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
