package com.enderthor.kSafe.extension.pacing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GradePaceStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun `save then load`() {
        val s = GradePaceStore(tmp.root); val m = GradePaceModel(); m.learn(BikeKind.ROAD, 2.0, 1000.0, 180.0)
        s.save(m); assertEquals(20.0, s.load()!!.speedKmh(BikeKind.ROAD, 2.0), 0.01)
    }
    @Test fun `corrupt file is discarded`() {
        java.io.File(tmp.root, GradePaceStore.FILE_NAME).writeText("{nope")
        assertNull(GradePaceStore(tmp.root).load())
    }
}
