package com.enderthor.kSafe.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Wraps a block of settings that depend on a master toggle. When [enabled] is false the
 * children render dimmed (alpha 0.45) and pointer events are intercepted at the wrapper so
 * Switches / TextFields / Buttons inside cannot be activated.
 *
 * The content stays visible on purpose — riders see what is configured underneath the
 * disabled master and understand the master is the gate. Hiding the block would make it
 * look like the settings were lost.
 *
 * No need to thread `enabled = master` through every leaf control: the pointer-event
 * sink at the parent stops touches from reaching them while the master is off.
 */
@Composable
fun DependentSection(
    enabled: Boolean,
    verticalSpacing: androidx.compose.ui.unit.Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.45f
    val gateModifier = if (!enabled) {
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    event.changes.forEach { it.consume() }
                }
            }
        }
    } else Modifier

    Column(
        modifier = Modifier
            .alpha(alpha)
            .then(gateModifier),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
    ) {
        content()
    }
}
