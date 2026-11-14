package com.enderthor.kSafe.extension.pacing

import io.hammerhead.karooext.models.OnNavigationState.NavigationState.Climb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteClimbsTest {
    /** flat 0–2000 @100, climb 2000–5000 to 280 (6 %), flat to 8000, climb 8000–9000 to 330 (5 %), flat to 12000 */
    private val prof = ElevationProfile(
        doubleArrayOf(0.0, 2000.0, 5000.0, 8000.0, 9000.0, 12000.0),
        doubleArrayOf(100.0, 100.0, 280.0, 280.0, 330.0, 330.0),
    )

    @Test fun `own detector finds both climbs`() {
        val c = RouteClimbs.detectOwn(prof)
        assertEquals(2, c.size)
        assertEquals(2000.0, c[0].startM, 150.0); assertEquals(180.0, c[0].gainM, 10.0)
        assertEquals(8000.0, c[1].startM, 150.0); assertEquals(50.0, c[1].gainM, 10.0)
    }

    @Test fun `confirmed karoo list is primary when not reversed`() {
        val k = listOf(Climb(2000.0, 3000.0, 6.0, 180.0), Climb(8000.0, 1000.0, 5.0, 50.0))
        val r = RouteClimbs.resolve(k, prof, reversed = false)
        assertEquals(ClimbSource.KAROO, r.source); assertEquals(2, r.karooConfirmed)
    }

    @Test fun `unconfirmed karoo climb falls back to own detection`() {
        val k = listOf(Climb(500.0, 1000.0, 8.0, 80.0))     // profile is flat there
        assertEquals(ClimbSource.OWN_UNCONFIRMED, RouteClimbs.resolve(k, prof, false).source)
    }

    @Test fun `reversed route always uses own detection on the reversed profile`() {
        val r = RouteClimbs.resolve(emptyList(), prof.reversed(), reversed = true)
        assertEquals(ClimbSource.OWN_REVERSED, r.source)
        assertTrue(r.climbs.isEmpty())                        // reversed: both are descents
    }

    @Test fun `no profile and no list is UNKNOWN, never no-climbs`() {
        assertEquals(ClimbSource.UNKNOWN, RouteClimbs.resolve(emptyList(), null, false).source)
    }

    @Test fun `significance differs by bike kind`() {
        val short = ClimbSeg(0.0, 1200.0, 90.0)               // 7.5 %, 90 m
        assertFalse(RouteClimbs.isSignificant(short, BikeKind.ROAD))
        assertTrue(RouteClimbs.isSignificant(short, BikeKind.MTB))
        assertTrue(RouteClimbs.isSignificant(ClimbSeg(0.0, 3000.0, 180.0), BikeKind.ROAD))
    }

    @Test fun `climbs closer than the gap merge into one block`() {
        val a = ClimbSeg(1000.0, 2000.0, 150.0); val b = ClimbSeg(3500.0, 2000.0, 160.0); val c = ClimbSeg(20000.0, 3000.0, 200.0)
        val blocks = RouteClimbs.blocks(listOf(a, b, c), secondsBetween = { f, t -> (t - f) / 5.0 })  // 5 m/s
        assertEquals(2, blocks.size)
        assertEquals(1000.0, blocks[0].startM, 1e-9); assertEquals(5500.0, blocks[0].endM, 1e-9)
        assertEquals(310.0, blocks[0].gainM, 1e-9)
    }
}
