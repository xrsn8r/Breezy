package dev.breezy.browser.ui

import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
        val cornerPx = with(density) { 28.dp.toPx() }
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
                // The blurred region follows this drawable's shape: rounded top corners.
                window.setBackgroundDrawable(GradientDrawable().apply {
                    cornerRadii = floatArrayOf(cornerPx, cornerPx, cornerPx, cornerPx, 0f, 0f, 0f, 0f)
                    setColor(base.copy(alpha = if (blur) 0.6f else 1f).toArgb())
                })
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

        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.9f).dp)
                .navigationBarsPadding(),
            content = content,
        )
    }
}
