package com.pocketnotebook.app.core.audio

import android.content.Context
import com.pocketnotebook.app.core.db.ArtifactRepo
import com.pocketnotebook.app.core.db.ArtifactStatus
import com.pocketnotebook.app.core.llm.StudioService
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Bakes an audio overview: script JSON → per-segment TTS files → playlist dir.
 * Runs inside BakeWorker with a foreground notification. Playback via Media3
 * picks the files up in order (ExoPlayer sniffs the actual container format,
 * which varies by TTS engine).
 */
class OverviewBaker(
    private val context: Context,
    private val artifactRepo: ArtifactRepo,
    private val tts: TtsManager,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun audioDir(artifactId: Long): File =
        File(File(context.filesDir, "overview_audio"), artifactId.toString())

    fun bakedFiles(artifactId: Long): List<File> {
        val dir = audioDir(artifactId)
        return dir.listFiles()?.sortedBy { it.name } ?: emptyList()
    }

    /**
     * @return number of segments synthesized.
     * Throws on failure — worker marks the artifact failed.
     */
    suspend fun bake(artifactId: Long, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val artifact = artifactRepo.get(artifactId)
            ?: throw IllegalStateException("artifact missing")
        val payload = artifact.payload
            ?: throw IllegalStateException("script payload missing")
        val script = json.decodeFromString(StudioService.OverviewScript.serializer(), payload)
        if (script.segments.isEmpty()) throw IllegalStateException("empty script")

        val speakable = script.segments.map { cleanForSpeech(it.text) }
        if (speakable.all { it.isBlank() }) {
            throw IllegalStateException("script has no speakable text")
        }

        val dir = audioDir(artifactId)
        if (!dir.exists()) dir.mkdirs()
        else dir.listFiles()?.forEach { it.delete() }

        var done = 0
        for ((index, segment) in script.segments.withIndex()) {
            val text = speakable[index]
            // Blank-after-cleanup segments (bare URLs, citation noise) would
            // make engines error out — skip them instead of failing the bake.
            if (text.isNotBlank()) {
                val out = File(dir, "seg_%04d.bin".format(index))
                val (pitch, rate) = if (segment.speaker == "B")
                    TtsManager.PITCH_B to TtsManager.RATE_B
                else TtsManager.PITCH_A to TtsManager.RATE_A

                val ok = tts.synthesize(text, out, pitch, rate)
                if (!ok) throw IllegalStateException(
                    "TTS failed at segment ${index + 1}: " +
                        (tts.lastFailure ?: "unknown engine error"),
                )
            }
            done++
            onProgress(done, script.segments.size)
        }

        // Mark baked in payload.
        script.baked = true
        artifactRepo.update(
            artifactId,
            status = ArtifactStatus.READY,
            payload = json.encodeToString(StudioService.OverviewScript.serializer(), script),
            audioDir = dir.absolutePath,
        )
        return done
    }

    /** Strip citation-like noise / long URLs the TTS engine would read aloud. */
    private fun cleanForSpeech(text: String): String =
        text.replace(Regex("https?://\\S+"), "link")
            .replace(Regex("\\[(\\d{1,2})]"), "")
            .replace(Regex("\\*+"), "")
            .replace(Regex("_+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
