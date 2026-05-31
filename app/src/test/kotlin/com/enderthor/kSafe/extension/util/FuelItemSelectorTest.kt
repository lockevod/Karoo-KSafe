package com.enderthor.kSafe.extension.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuelItemSelectorTest {
    private val slots = listOf(
        FuelSlot(1, "Gel", 25),
        FuelSlot(2, "Bar", 30),
        FuelSlot(3, "Fruit", 20),
    )

    @Test fun `picks slot whose size is closest to deficit`() {
        assertEquals(2, pickFuelItem(31, slots)?.slot)
        assertEquals(3, pickFuelItem(18, slots)?.slot)
    }

    @Test fun `tiebreak goes to lowest slot index`() {
        val tie = listOf(FuelSlot(1, "A", 20), FuelSlot(2, "B", 20))
        assertEquals(1, pickFuelItem(50, tie)?.slot)
    }

    @Test fun `null deficit returns first slot`() {
        assertEquals(1, pickFuelItem(null, slots)?.slot)
    }

    @Test fun `no usable slots returns null`() {
        assertNull(pickFuelItem(40, emptyList()))
        assertNull(pickFuelItem(40, listOf(FuelSlot(1, "X", 0))))
    }
}
