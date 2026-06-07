package com.enderthor.kSafe.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupStorageTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `resolveImportFile prefers the shared location when present`() {
        val shared = tmp.newFolder("shared")
        val legacy = tmp.newFolder("legacy")
        File(shared, BackupStorage.IMPORT_NAME).writeText("{}")
        File(legacy, BackupStorage.IMPORT_NAME).writeText("{}")
        assertEquals(
            File(shared, BackupStorage.IMPORT_NAME),
            BackupStorage.resolveImportFile(shared, legacy)
        )
    }

    @Test fun `resolveImportFile falls back to legacy when shared is absent`() {
        val shared = tmp.newFolder("shared")
        val legacy = tmp.newFolder("legacy")
        File(legacy, BackupStorage.IMPORT_NAME).writeText("{}")
        assertEquals(
            File(legacy, BackupStorage.IMPORT_NAME),
            BackupStorage.resolveImportFile(shared, legacy)
        )
    }

    @Test fun `resolveImportFile returns null when neither exists`() {
        val shared = tmp.newFolder("shared")
        assertNull(BackupStorage.resolveImportFile(shared, tmp.newFolder("legacy")))
        assertNull(BackupStorage.resolveImportFile(shared, null))
    }
}
