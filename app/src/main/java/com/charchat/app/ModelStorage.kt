package com.charchat.app

import android.content.Context
import com.arm.aichat.gguf.GgufMetadata
import java.io.File

const val FILE_EXTENSION_GGUF = ".gguf"

private const val DIRECTORY_MODELS = "models"
private const val ACTIVE_MODEL_MARKER_FILE = "active_model.txt"

/**
 * Where downloaded GGUF models live, and which one is currently loaded into the shared inference
 * engine. The engine itself doesn't remember which file it loaded, so that's tracked here via a
 * small marker file instead - the same way everything else in this app persists as plain files,
 * and it survives across process restarts so a cold launch resumes the right model rather than
 * guessing from file timestamps (which switching models without re-copying wouldn't update).
 */
object ModelStorage {
    fun modelsDirectory(context: Context): File =
        File(context.filesDir, DIRECTORY_MODELS).also {
            if (it.exists() && !it.isDirectory) it.delete()
            if (!it.exists()) it.mkdir()
        }

    /** Every downloaded .gguf model, most recently used first. */
    fun listModels(context: Context): List<File> =
        modelsDirectory(context).listFiles { f -> f.extension == "gguf" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun setActiveModel(context: Context, fileName: String) {
        runCatching { File(modelsDirectory(context), ACTIVE_MODEL_MARKER_FILE).writeText(fileName) }
    }

    fun clearActiveModel(context: Context) {
        File(modelsDirectory(context), ACTIVE_MODEL_MARKER_FILE).delete()
    }

    /**
     * The file name (not path) of whichever model was last successfully loaded, if it's still
     * actually present on disk - falls back to null (never a stale/deleted file name) so callers
     * always get either a real, currently-existing model or nothing.
     */
    fun activeModelName(context: Context): String? {
        val marker = File(modelsDirectory(context), ACTIVE_MODEL_MARKER_FILE)
        if (!marker.exists()) return null
        val name = runCatching { marker.readText().trim() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return name.takeIf { File(modelsDirectory(context), it).exists() }
    }

    fun formatSize(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        if (gb >= 0.1) return "%.1f GB".format(gb)
        val mb = bytes / (1024.0 * 1024.0)
        return "%.0f MB".format(mb)
    }
}

fun GgufMetadata.filename() = when {
    basic.name != null -> {
        basic.name?.let { name ->
            basic.sizeLabel?.let { size ->
                "$name-$size"
            } ?: name
        }
    }
    architecture?.architecture != null -> {
        architecture?.architecture?.let { arch ->
            basic.uuid?.let { uuid ->
                "$arch-$uuid"
            } ?: "$arch-${System.currentTimeMillis()}"
        }
    }
    else -> {
        "model-${System.currentTimeMillis().toHexString()}"
    }
}
