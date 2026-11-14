package com.enderthor.kSafe.extension.pacing

import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.io.IOException

/** Schema-versioned, atomically written; older schema → discarded (null), never partially trusted.
 *  A file that cannot be read or parsed is moved to [FILE_NAME].bad first, so no later save overwrites it;
 *  if it cannot be moved, [load] throws so the caller stays unloaded and never saves over it. */
class GradePaceStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    fun load(): GradePaceModel? {
        val f = File(dir, FILE_NAME); if (!f.isFile) return null
        val dto = runCatching { json.decodeFromString<GradePaceDto>(f.readText()) }.getOrElse {
            // Move aside under a temp name first: the older .bad is only replaced once the file is safely out.
            val moving = File(dir, "$FILE_NAME.bad.tmp"); moving.delete()
            if (!f.renameTo(moving)) {
                Timber.w(it, "GradePaceStore: unreadable and could not be moved aside")
                // Not a plain null: the caller would start a fresh model and later save over the file.
                throw IOException("unreadable $FILE_NAME could not be moved aside", it)
            }
            val bad = File(dir, "$FILE_NAME.bad"); bad.delete()
            Timber.w(it, "GradePaceStore: unreadable, kept as ${if (moving.renameTo(bad)) bad.name else moving.name}")
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
