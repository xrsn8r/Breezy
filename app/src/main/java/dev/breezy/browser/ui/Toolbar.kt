package dev.breezy.browser.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
    tab: BrowserTab?,
    tabCount: Int,
    shieldsActive: Boolean,
    onShield: () -> Unit,
    onAddress: () -> Unit,
    onTabs: () -> Unit,
    onMenu: () -> Unit,
    onSearch: () -> Unit,
    onCopyLink: () -> Unit,
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
        // The gesture coroutine below outlives recompositions (it's keyed only on `travel`), so it
        // must read the *current* tab and callback, not the ones captured when it started.
        // Otherwise copying could grab the link of a tab that was switched away from or closed.
        val currentTab by rememberUpdatedState(tab)
        val currentOnCopyLink by rememberUpdatedState(onCopyLink)
        var rowBounds by remember { mutableStateOf(Rect.Zero) }
        var pillBounds by remember { mutableStateOf(Rect.Zero) }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { barHeight = it.height }
                .offset { IntOffset(0, -(shown * travel).roundToInt()) }
                .graphicsLayer { scaleX = lift; scaleY = lift }
                .onGloballyPositioned { rowBounds = it.boundsInRoot() }
                .pointerInput(travel) {
                    // Hold anywhere and drag: move the toolbar. Hold the address without moving:
                    // copy the link. Taps fall through to the buttons as usual.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        // Non-null if the finger lifts or moves before the long-press timeout.
                        val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            var done = false
                            while (!done) {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                val ch = ev.changes.firstOrNull { it.id == down.id }
                                done = ch == null || !ch.pressed ||
                                    (ch.position - down.position).getDistance() > viewConfiguration.touchSlop
                            }
                            true
                        }
                        if (released != null) return@awaitEachGesture // tap or swipe: not ours
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        val downInRoot = rowBounds.topLeft + down.position
                        val onAddressPill = pillBounds.contains(downInRoot)
                        var moved = false
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            // Consume so the button under the finger doesn't also get a click.
                            ev.changes.forEach { it.consume() }
                            if (!ch.pressed) break
                            if (!moved && (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                moved = true
                                dragging = true
                            }
                            if (moved && travel > 0f) {
                                val dy = ch.position.y - ch.previousPosition.y
                                position = (position - dy / travel).coerceIn(0f, 1f)
                            }
                        }
                        if (moved) {
                            dragging = false
                            AppSettings.updateToolbarPosition(position)
                        } else if (onAddressPill && currentTab?.url?.isNotEmpty() == true) {
                            currentOnCopyLink()
                        }
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FrostedToolbar(
                hazeState = hazeState,
                onPillPositioned = { pillBounds = it },
                expanded = expanded || dragging,
                tab = tab,
                tabCount = tabCount,
                shieldsActive = shieldsActive,
                onShield = onShield,
                onAddress = onAddress,
                onTabs = onTabs,
                onMenu = onMenu,
            )
            // VibrantFloatingActionButton applies fillMaxSize() (it expects the toolbar's FAB slot
            // to size it), so give it an explicit size now that it lives outside that slot.
            FloatingToolbarDefaults.VibrantFloatingActionButton(
                onClick = onSearch,
                modifier = Modifier.size(FloatingToolbarDefaults.ContainerSize),
            ) {
                Icon(Icons.Rounded.Search, contentDescription = "Search or type URL")
            }
        }
    }
}

@Composable
private fun FrostedToolbar(
    hazeState: HazeState,
    onPillPositioned: (Rect) -> Unit,
    expanded: Boolean,
    tab: BrowserTab?,
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
            .then(
                if (AppSettings.frostedToolbar) Modifier.hazeBlur(input = HazeInput.Backdrop(hazeState), style = glass)
                // No backdrop sampling at all: a solid, slightly translucent surface.
                else Modifier.background(tint.copy(alpha = 0.94f)),
            )
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
                    val blocked = tab?.blockedCount ?: 0
                    if (shieldsActive && blocked > 0) {
                        Badge { Text(if (blocked > 99) "99+" else blocked.toString()) }
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
                .onGloballyPositioned { onPillPositioned(it.boundsInRoot()) }
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onAddress)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val url = tab?.url.orEmpty()
            val secure = UrlInput.isSecure(url)
            if (url.isNotEmpty()) {
                Icon(
                    if (secure) Icons.Rounded.Lock else Icons.Rounded.Language,
                    contentDescription = if (secure) "Secure" else "Not secure",
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = UrlInput.display(url).ifEmpty { "Search" },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
