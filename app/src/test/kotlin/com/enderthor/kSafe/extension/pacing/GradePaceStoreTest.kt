package com.enderthor.kSafe.extension.pacing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    @Test fun `an unreadable file is moved aside to bad instead of being overwritten later`() {
        java.io.File(tmp.root, "${GradePaceStore.FILE_NAME}.bad").writeText("older")
        java.io.File(tmp.root, GradePaceStore.FILE_NAME).writeText("{nope")
        assertNull(GradePaceStore(tmp.root).load())
        assertEquals("{nope", java.io.File(tmp.root, "${GradePaceStore.FILE_NAME}.bad").readText())
        assertFalse(java.io.File(tmp.root, GradePaceStore.FILE_NAME).exists())
    }
    @Test fun `an unreadable file that cannot be moved aside fails the load and keeps both files`() {
        val bad = java.io.File(tmp.root, "${GradePaceStore.FILE_NAME}.bad").apply { writeText("older") }
        val f = java.io.File(tmp.root, GradePaceStore.FILE_NAME).apply { writeText("{nope") }
        tmp.root.setWritable(false)
        try { assertTrue(runCatching { GradePaceStore(tmp.root).load() }.isFailure) } finally { tmp.root.setWritable(true) }
        assertEquals("{nope", f.readText()); assertEquals("older", bad.readText())
    }
}
