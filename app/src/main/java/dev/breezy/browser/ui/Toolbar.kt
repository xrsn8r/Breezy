package dev.breezy.browser.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RemoveModerator
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.breezy.browser.AppSettings
import dev.breezy.browser.browser.BrowserTab
import dev.breezy.browser.browser.UrlInput
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import kotlin.math.roundToInt

/**
 * Frosted-glass floating toolbar that can be long-pressed and dragged anywhere between the bottom
 * and top of the screen (so it never sits on top of a site's own bottom navigation). The position
 * is remembered across launches.
 */
@Composable
fun MovableBrowserBar(
    hazeState: HazeState,
    expanded: Boolean,
    tab: BrowserTab,
    tabCount: Int,
    shieldsActive: Boolean,
    onShield: () -> Unit,
    onAddress: () -> Unit,
    onTabs: () -> Unit,
    onMenu: () -> Unit,
    onSearch: () -> Unit,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(vertical = 12.dp),
    ) {
        val maxPx = constraints.maxHeight.toFloat()
        var barHeight by remember { mutableIntStateOf(0) }
        val travel = (maxPx - barHeight).coerceAtLeast(0f)

        var dragging by remember { mutableStateOf(false) }
        var position by remember { mutableFloatStateOf(AppSettings.toolbarPosition) }
        LaunchedEffect(AppSettings.toolbarPosition) { if (!dragging) position = AppSettings.toolbarPosition }

        val shown by animateFloatAsState(
            targetValue = position,
            animationSpec = if (dragging) snap() else spring(dampingRatio = 0.7f, stiffness = 400f),
            label = "barPosition",
        )
        val lift by animateFloatAsState(if (dragging) 1.05f else 1f, label = "barLift")
        val haptics = LocalHapticFeedback.current

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { barHeight = it.height }
                .offset { IntOffset(0, -(shown * travel).roundToInt()) }
                .graphicsLayer { scaleX = lift; scaleY = lift }
                .pointerInput(travel) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            dragging = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDragEnd = { dragging = false; AppSettings.updateToolbarPosition(position) },
                        onDragCancel = { dragging = false; AppSettings.updateToolbarPosition(position) },
                        onDrag = { change, amount ->
                            change.consume()
                            if (travel > 0f) position = (position - amount.y / travel).coerceIn(0f, 1f)
                        },
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FrostedToolbar(
                hazeState = hazeState,
                expanded = expanded || dragging,
                tab = tab,
                tabCount = tabCount,
                shieldsActive = shieldsActive,
                onShield = onShield,
                onAddress = onAddress,
                onTabs = onTabs,
                onMenu = onMenu,
            )
            FloatingToolbarDefaults.VibrantFloatingActionButton(onClick = onSearch) {
                Icon(Icons.Rounded.Search, contentDescription = "Search or type URL")
            }
        }
    }
}

@Composable
private fun FrostedToolbar(
    hazeState: HazeState,
    expanded: Boolean,
    tab: BrowserTab,
    tabCount: Int,
    shieldsActive: Boolean,
    onShield: () -> Unit,
    onAddress: () -> Unit,
    onTabs: () -> Unit,
    onMenu: () -> Unit,
) {
    val shape = CircleShape
    val tint = MaterialTheme.colorScheme.surfaceContainerHigh
    val outline = MaterialTheme.colorScheme.outlineVariant
    val glass = remember(tint) {
        HazeBlurStyle {
            blurRadius(28.dp)
            noiseFactor(0.06f)
            colorEffects(listOf(HazeColorEffect.tint(tint.copy(alpha = 0.55f))))
            // Used when real blur isn't available (older Android): mostly opaque so text stays legible.
            fallbackColorEffect(HazeColorEffect.tint(tint.copy(alpha = 0.9f)))
        }
    }

    HorizontalFloatingToolbar(
        expanded = expanded,
        modifier = Modifier
            .clip(shape)
            .hazeBlur(input = HazeInput.Backdrop(hazeState), style = glass)
            .border(1.dp, outline.copy(alpha = 0.5f), shape),
        shape = shape,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
            toolbarContainerColor = Color.Transparent,
            toolbarContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        expandedShadowElevation = 0.dp,
        collapsedShadowElevation = 0.dp,
        leadingContent = {
            IconButton(onClick = onShield) {
                BadgedBox(badge = {
                    if (shieldsActive && tab.blockedCount > 0) {
                        Badge { Text(if (tab.blockedCount > 99) "99+" else tab.blockedCount.toString()) }
                    }
                }) {
                    Icon(
                        if (shieldsActive) Icons.Rounded.Shield else Icons.Rounded.RemoveModerator,
                        contentDescription = "Shields",
                    )
                }
            }
        },
        trailingContent = {
            IconButton(onClick = onTabs) {
                Box(
                    Modifier
                        .size(22.dp)
                        .border(2.dp, LocalContentColor.current, RoundedCornerShape(7.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (tabCount > 99) ":D" else tabCount.toString(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            IconButton(onClick = onMenu) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "Menu")
            }
        },
    ) {
        Row(
            modifier = Modifier
                .widthIn(min = 96.dp, max = 168.dp)
                .height(40.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onAddress)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val secure = UrlInput.isSecure(tab.url)
            if (tab.url.isNotEmpty()) {
                Icon(
                    if (secure) Icons.Rounded.Lock else Icons.Rounded.Language,
                    contentDescription = if (secure) "Secure" else "Not secure",
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = UrlInput.display(tab.url).ifEmpty { "Search" },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
