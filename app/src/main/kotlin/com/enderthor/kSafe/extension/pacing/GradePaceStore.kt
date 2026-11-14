package com.enderthor.kSafe.extension.pacing

import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/** Schema-versioned, atomically written; older schema → discarded (null), never partially trusted.
 *  A file that cannot be read or parsed is moved to [FILE_NAME].bad first, so no later save overwrites it. */
class GradePaceStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    fun load(): GradePaceModel? {
        val f = File(dir, FILE_NAME); if (!f.isFile) return null
        val dto = runCatching { json.decodeFromString<GradePaceDto>(f.readText()) }.getOrElse {
            val bad = File(dir, "$FILE_NAME.bad")
            bad.delete()
            val kept = f.renameTo(bad)
            Timber.w(it, "GradePaceStore: unreadable, ${if (kept) "kept as ${bad.name}" else "could not be moved aside"}")
            return null
        }
        return dto.takeIf { it.schemaVersion == GradePaceModel.SCHEMA }?.let(GradePaceModel::fromDto)
    }

    fun save(model: GradePaceModel) = save(model.toDto())

    fun save(dto: GradePaceDto) {
        val tmp = File(dir, "$FILE_NAME.tmp")
        runCatching {
            dir.mkdirs()
            tmp.outputStream().use { out -> out.write(json.encodeToString(GradePaceDto.serializer(), dto).toByteArray()); out.fd.sync() }
            if (!tmp.renameTo(File(dir, FILE_NAME))) error("rename failed")
        }.onFailure { Timber.w(it, "GradePaceStore: save failed"); tmp.delete() }
    }

    companion object { const val FILE_NAME = "pacing_gradepace.json" }
}
