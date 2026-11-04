package com.pocketnotebook.app.core.hf

import com.pocketnotebook.app.AppContainer
import com.pocketnotebook.app.core.db.ModelSlot
import java.io.File

/**
 * Self-heal for the models table: walks the models dir and registers any
 * .gguf that exists on disk but has no DB row — e.g. downloads that finished
 * under alpha.3, where the post-download upsert crashed (the ContentValues
 * bug) and the worker died right after the file landed. Also fills an empty
 * slot pref, so a model that is present is picked up without a re-download.
 *
 * Runs at app start and when the model screens are entered; idempotent.
 */
object ModelScanner {

    fun scanAndRegister(container: AppContainer) {
        val repoDirs = container.runtime.modelsDir()
            .listFiles { f: File -> f.isDirectory } ?: return
        for (repoDir in repoDirs) {
            val files = repoDir.listFiles { f: File ->
                f.isFile && f.name.endsWith(".gguf", ignoreCase = true)
            } ?: continue
            for (file in files) register(container, file, repoDir.name)
        }
    }

    private fun register(container: AppContainer, file: File, dirName: String) {
        val known = container.modelRepo.listAll()
            .firstOrNull { it.filePath == file.absolutePath }
        if (known == null) {
            val catalog = ModelCatalog.entries.firstOrNull {
                it.fileName.equals(file.name, ignoreCase = true)
            }
            val slot = catalog?.slot ?: inferSlot(file.name)
            container.modelRepo.upsert(
                slot,
                catalog?.repoId ?: dirName.replace("__", "/"),
                file.name,
                file.absolutePath,
                file.length(),
                null,
                catalog?.verified ?: false,
            )
        }
        val slot = known?.slot ?: run {
            container.modelRepo.listAll()
                .firstOrNull { it.filePath == file.absolutePath }?.slot
        } ?: return
        if (container.prefs.activeModelPath(slot) == null) {
            container.prefs.setActiveModel(slot, file.absolutePath)
        }
    }

    private fun inferSlot(fileName: String): String {
        val n = fileName.lowercase()
        return when {
            n.contains("embed") || n.contains("bge") -> ModelSlot.EMBED
            n.contains("audio") || n.contains("asr") ||
                n.contains("tts") || n.contains("whisper") -> ModelSlot.AUDIO
            else -> ModelSlot.CHAT
        }
    }
}
