package com.enderthor.kSafe.extension.util

import org.junit.Assert.assertEquals
import org.junit.Test

class CombinedFuelLogTest {
    @Test fun `carbsFromVolume scales by concentration over 500ml and rounds`() {
        assertEquals(60, carbsFromVolume(ml = 500, concentrationPer500ml = 60))
        assertEquals(30, carbsFromVolume(ml = 250, concentrationPer500ml = 60))
        assertEquals(12, carbsFromVolume(ml = 100, concentrationPer500ml = 60))   // 12.0
        assertEquals(15, carbsFromVolume(ml = 125, concentrationPer500ml = 60))   // 15.0
        assertEquals(11, carbsFromVolume(ml = 90, concentrationPer500ml = 60))    // 10.8 -> 11
        assertEquals(0, carbsFromVolume(ml = 250, concentrationPer500ml = 0))
        assertEquals(0, carbsFromVolume(ml = 0, concentrationPer500ml = 60))
    }

    @Test fun `carbsFromVolume clamps instead of throwing on overflowing inputs`() {
        // Both maxed (e.g. a corrupt import bypassing the UI's 0..1000 / 0..200 coercion):
        // ml*conc/500 far exceeds Int range — must clamp to Int.MAX_VALUE, never throw.
        assertEquals(Int.MAX_VALUE, carbsFromVolume(ml = Int.MAX_VALUE, concentrationPer500ml = Int.MAX_VALUE))
    }
}
