package com.enderthor.kSafe.extension.pacing

import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/** Schema-versioned, atomically written; unreadable or older schema → discarded (null), never partially trusted. */
class GradePaceStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    fun load(): GradePaceModel? {
        val f = File(dir, FILE_NAME); if (!f.isFile) return null
        return runCatching { json.decodeFromString<GradePaceDto>(f.readText()) }
            .onFailure { Timber.w(it, "GradePaceStore: unreadable, discarded") }.getOrNull()
            ?.takeIf { it.schemaVersion == GradePaceModel.SCHEMA }?.let(GradePaceModel::fromDto)
    }

    fun save(model: GradePaceModel) {
        val tmp = File(dir, "$FILE_NAME.tmp")
        runCatching {
            tmp.outputStream().use { out -> out.write(json.encodeToString(GradePaceDto.serializer(), model.toDto()).toByteArray()); out.fd.sync() }
            if (!tmp.renameTo(File(dir, FILE_NAME))) error("rename failed")
        }.onFailure { Timber.w(it, "GradePaceStore: save failed"); tmp.delete() }
    }

    companion object { const val FILE_NAME = "pacing_gradepace.json" }
}
