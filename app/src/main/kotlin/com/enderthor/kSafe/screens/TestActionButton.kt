package com.enderthor.kSafe.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * State of a one-shot test/send action.
 *
 * The status colors (red / green) are hard-coded high-contrast on purpose: this UI is read
 * outdoors on the Karoo, often after a failed test, sometimes with gloves on. Theme-derived
 * `error`/`onErrorContainer` did not survive sunlight in earlier passes.
 */
sealed class TestActionState {
    data object Idle : TestActionState()
    data object Running : TestActionState()
    data class Success(val message: String) : TestActionState()
    data class Error(val message: String) : TestActionState()
}

/**
 * A button + below-it status line for a "Test" / "Send" / "Simulate" action.
 *
 * Why this exists: there were ~8 near-identical copies across screens, each rolling its own
 * `Sending…` text and none of them disabling the button mid-flight. A gloved double-tap could
 * fire a real alert twice — see [requireConfirmation] for the dangerous-action variant.
 *
 * @param label              Button text in the idle state. Replaced with a spinner + "Sending…"
 *                           label while [TestActionState.Running].
 * @param onAction           Suspend block that performs the work and returns the human-readable
 *                           status message that will be displayed below.
 * @param isSuccess          Classifier for the message string — kept caller-controlled because
 *                           the existing extension methods return free-form text (e.g. "✓ sent",
 *                           "Ride start message sent at 12:34", "Error: …"). Defaults to a "✓"
 *                           or "sent" heuristic that matches most call sites.
 * @param requireConfirmation If non-null, a confirmation dialog is shown before [onAction] fires.
 *                           Use this for destructive / real-world side effects (Simulate Crash).
 */
@Composable
fun TestActionButton(
    label: String,
    onAction: suspend () -> String,
    modifier: Modifier = Modifier,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    runningLabel: String = "Sending…",
    isSuccess: (String) -> Boolean = { it.contains("✓") || it.contains("sent!", ignoreCase = true) },
    requireConfirmation: ConfirmConfig? = null,
) {
    val scope = rememberCoroutineScope()
    var state: TestActionState by remember { mutableStateOf(TestActionState.Idle) }
    var showConfirm by remember { mutableStateOf(false) }

    val running = state is TestActionState.Running

    fun fire() {
        state = TestActionState.Running
        scope.launch {
            val msg = onAction()
            state = if (isSuccess(msg)) TestActionState.Success(msg) else TestActionState.Error(msg)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Button(
            onClick = {
                if (running) return@Button
                if (requireConfirmation != null) showConfirm = true else fire()
            },
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
            colors = colors,
        ) {
            if (running) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current
                    )
                    Text(runningLabel)
                }
            } else {
                Text(label)
            }
        }

        when (val s = state) {
            is TestActionState.Success -> StatusLine(s.message, isError = false)
            is TestActionState.Error   -> StatusLine(s.message, isError = true)
            TestActionState.Running, TestActionState.Idle -> Unit
        }
    }

    if (showConfirm && requireConfirmation != null) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(requireConfirmation.title) },
            text  = { Text(requireConfirmation.body) },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    fire()
                }) { Text(requireConfirmation.confirmLabel) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(requireConfirmation.cancelLabel)
                }
            }
        )
    }
}

/** Wires a confirmation dialog into [TestActionButton] for destructive actions. */
data class ConfirmConfig(
    val title: String,
    val body: String,
    val confirmLabel: String,
    val cancelLabel: String,
)

@Composable
private fun StatusLine(message: String, isError: Boolean) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) Color(0xFFB71C1C) else Color(0xFF2E7D32),
        modifier = Modifier.padding(horizontal = 2.dp)
    )
}
