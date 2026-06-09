package com.enderthor.kSafe.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the v17 → v18 backward-compat contract for [CarbFuelingState].
 *
 * v18 renamed the integrator accumulator from `cumTargetG` to `cumBurnedG`
 * because the integrator semantics changed (target-based plan → physiological
 * burn estimate). The Kotlin field was renamed, but the JSON key was preserved
 * via `@SerialName("cumTargetG")` so DataStore snapshots written by v17 still
 * deserialise without losing the rider's accumulator.
 *
 * If this test fails the rider mid-ride during an upgrade would silently lose
 * their session-total carb burn (data loss).
 */
class CarbFuelingStateSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun `v17 JSON with cumTargetG deserialises into v18 cumBurnedG field`() {
        // Synthetic v17 snapshot — the field is the legacy name `cumTargetG`.
        // `lastAlertMs` was a v17 field; it's now silently dropped by
        // ignoreUnknownKeys=true on the deserialise side, which is the
        // backward-compat contract being verified here.
        val v17Json = """{
            "cumTargetG": 87.5,
            "cumLoggedG": 60,
            "sessionStartMs": 1234567890,
            "lastLogMs": 1234567900,
            "lastAlertMs": 1234567910,
            "lastRealLogMs": 1234567900
        }"""
        val restored = json.decodeFromString(CarbFuelingState.serializer(), v17Json)
        // The rider's accumulator must land in the new field.
        assertEquals(87.5f, restored.cumBurnedG, 0.001f)
        // Other fields preserved.
        assertEquals(60, restored.cumLoggedG)
        assertEquals(1234567890L, restored.sessionStartMs)
        assertEquals(1234567900L, restored.lastLogMs)
        assertEquals(1234567900L, restored.lastRealLogMs)
        // v18-only fields default to 0 when absent from v17 JSON.
        assertEquals(0L, restored.lastTimeAlertFireMs)
        assertEquals(0L, restored.lastDeficitAlertFireMs)
        assertEquals(0L, restored.activeIntegrationMs)
    }

    @Test
    fun `v18 JSON round-trip preserves cumBurnedG via cumTargetG key`() {
        // A v18 state encoded → JSON contains the legacy `cumTargetG` key
        // (because @SerialName overrides the field name). Decoding it back
        // returns the same Kotlin object.
        val original = CarbFuelingState(
            cumBurnedG = 120f,
            cumLoggedG = 90,
            sessionStartMs = 1000,
            lastLogMs = 2000,
            lastRealLogMs = 2000,
            lastTimeAlertFireMs = 1500,
            lastDeficitAlertFireMs = 1700,
            activeIntegrationMs = 600_000L,
        )
        val encoded = json.encodeToString(CarbFuelingState.serializer(), original)
        // The serialized form MUST use the legacy `cumTargetG` key — that's the
        // whole point of @SerialName. Any future change that breaks this
        // invariant means existing v17 snapshots stop loading.
        assertTrue(
            "encoded JSON must contain the legacy `cumTargetG` key (got: $encoded)",
            encoded.contains("\"cumTargetG\""),
        )
        // The current Kotlin field name must NOT appear in the JSON.
        assertFalse(
            "encoded JSON must NOT use the new `cumBurnedG` key (got: $encoded)",
            encoded.contains("\"cumBurnedG\""),
        )
        val roundtripped = json.decodeFromString(CarbFuelingState.serializer(), encoded)
        assertEquals(original, roundtripped)
    }

    @Test
    fun `empty v17-shape JSON yields all defaults`() {
        // Defensive: a stripped JSON `{}` (no fields written yet) must
        // deserialise to all-defaults without throwing.
        val empty = "{}"
        val restored = json.decodeFromString(CarbFuelingState.serializer(), empty)
        assertEquals(CarbFuelingState(), restored)
    }

    @Test
    fun `old JSON without cumKcal defaults to zero and new field round-trips`() {
        // Backward compat: a snapshot written before the calorie feature has no
        // cumKcal key → must default to 0f, never crash.
        val oldJson = """{ "cumTargetG": 50.0, "cumLoggedG": 30 }"""
        val restored = json.decodeFromString(CarbFuelingState.serializer(), oldJson)
        assertEquals(0f, restored.cumKcal, 0.001f)

        // Round-trip a populated value.
        val encoded = json.encodeToString(CarbFuelingState.serializer(), CarbFuelingState(cumKcal = 742.5f))
        val back = json.decodeFromString(CarbFuelingState.serializer(), encoded)
        assertEquals(742.5f, back.cumKcal, 0.001f)
    }

    private fun assertTrue(message: String, condition: Boolean) {
        org.junit.Assert.assertTrue(message, condition)
    }
    private fun assertFalse(message: String, condition: Boolean) {
        org.junit.Assert.assertFalse(message, condition)
    }
}
