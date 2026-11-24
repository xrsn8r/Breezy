package dev.breezy.browser.ui

import androidx.compose.material3.Slider
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * A stepped slider driven by an external value (the current Material 3 slider API keeps its own
 * state; this keeps it in sync when the value changes elsewhere, e.g. a reset or a restore).
 */
@Composable
internal fun StepSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    val state = rememberSliderState(value, steps, valueRange)
    LaunchedEffect(value) { if (state.value != value) state.value = value }
    Slider(state = state, onValueChange = { state.value = it; onValueChange(it) })
}
