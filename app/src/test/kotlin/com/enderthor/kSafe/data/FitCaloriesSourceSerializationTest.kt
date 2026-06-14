package com.enderthor.kSafe.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the wire format of [FitCaloriesSource] now that it carries an explicit `@Serializable`.
 * Before the annotation it serialized via the kotlinx compiler-plugin fallback; the explicit
 * serializer must keep encoding by ENUM ENTRY NAME so existing saved configs (and exports)
 * decode unchanged. A future `@SerialName` rename or ordinal-based encoding would break this.
 */
class FitCaloriesSourceSerializationTest {

    // encodeDefaults so the default value (NONE) is also emitted and its wire name asserted.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `each value round-trips through KSafeConfig by entry name`() {
        for (source in FitCaloriesSource.entries) {
            val cfg = KSafeConfig(fitStandardCaloriesSource = source)
            val encoded = json.encodeToString(KSafeConfig.serializer(), cfg)
            assertTrue(
                "expected name-based encoding for $source",
                encoded.contains("\"fitStandardCaloriesSource\":\"${source.name}\""),
            )
            val decoded = json.decodeFromString(KSafeConfig.serializer(), encoded)
            assertEquals(source, decoded.fitStandardCaloriesSource)
        }
    }

    @Test
    fun `legacy blob without the field decodes to NONE default`() {
        val decoded = json.decodeFromString(KSafeConfig.serializer(), "{}")
        assertEquals(FitCaloriesSource.NONE, decoded.fitStandardCaloriesSource)
    }
}
