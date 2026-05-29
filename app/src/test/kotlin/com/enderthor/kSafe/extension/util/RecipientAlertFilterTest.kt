package com.enderthor.kSafe.extension.util

import com.enderthor.kSafe.data.RecipientAlertScope
import com.enderthor.kSafe.data.RecipientAlertScope.ALL
import com.enderthor.kSafe.data.RecipientAlertScope.EMERGENCY_ONLY
import com.enderthor.kSafe.data.RecipientAlertScope.INFO_ONLY
import com.enderthor.kSafe.data.SenderConfig
import com.enderthor.kSafe.extension.jsonWithUnknownKeys
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Test

class RecipientAlertFilterTest {
    @Test fun `accepts truth table`() {
        assertEquals(true,  ALL.accepts(isEmergency = true))
        assertEquals(true,  ALL.accepts(isEmergency = false))
        assertEquals(true,  EMERGENCY_ONLY.accepts(isEmergency = true))
        assertEquals(false, EMERGENCY_ONLY.accepts(isEmergency = false))
        assertEquals(false, INFO_ONLY.accepts(isEmergency = true))
        assertEquals(true,  INFO_ONLY.accepts(isEmergency = false))
    }

    @Test fun `scopeForSlot maps 0 1 2 and clamps out-of-range to slot 3`() {
        val c = SenderConfig(
            recipient1Alerts = ALL, recipient2Alerts = EMERGENCY_ONLY, recipient3Alerts = INFO_ONLY)
        assertEquals(ALL, c.scopeForSlot(0))
        assertEquals(EMERGENCY_ONLY, c.scopeForSlot(1))
        assertEquals(INFO_ONLY, c.scopeForSlot(2))
        assertEquals(INFO_ONLY, c.scopeForSlot(99))
    }

    private val scopes = listOf(EMERGENCY_ONLY, INFO_ONLY, ALL)
    private fun scopeFor(i: Int) = scopes[i]

    @Test fun `info send drops EMERGENCY_ONLY slots`() {
        assertEquals(listOf(1, 2), recipientsToSend(listOf(0, 1, 2), ::scopeFor, isEmergency = false))
    }

    @Test fun `emergency send drops INFO_ONLY slots`() {
        assertEquals(listOf(0, 2), recipientsToSend(listOf(0, 1, 2), ::scopeFor, isEmergency = true))
    }

    @Test fun `emergency fallback sends to all when no slot accepts emergencies`() {
        assertEquals(listOf(0, 1), recipientsToSend(listOf(0, 1), { INFO_ONLY }, isEmergency = true))
    }

    @Test fun `info send to all-EMERGENCY_ONLY yields empty - no fallback`() {
        assertEquals(emptyList<Int>(), recipientsToSend(listOf(0, 1), { EMERGENCY_ONLY }, isEmergency = false))
    }

    @Test fun `empty configured slots yields empty`() {
        assertEquals(emptyList<Int>(), recipientsToSend(emptyList(), { ALL }, isEmergency = true))
    }

    @Test fun `old sender JSON without scope fields decodes to ALL`() {
        val oldJson = """[{"provider":"PUSHOVER","apiKey":"t","userKey":"u"}]"""
        val list = jsonWithUnknownKeys.decodeFromString(ListSerializer(SenderConfig.serializer()), oldJson)
        assertEquals(RecipientAlertScope.ALL, list[0].recipient1Alerts)
        assertEquals(RecipientAlertScope.ALL, list[0].recipient3Alerts)
    }
}
