package com.pocketnotebook.app.core.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Platform TTS (v0.1 narration engine — always present, zero extra download).
 * Speaker A/B differ via pitch/rate so 2-host overviews feel like a dialogue.
 * LFM3-Audio-2B native voices replace this once Phase 0 wiring lands.
 */
class TtsManager(private val context: Context) {

    @Volatile private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private val utterances = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private fun ensure(): Boolean {
        if (tts != null && ready?.isCompleted == true) return true
        if (tts != null) return false
        val d = CompletableDeferred<Boolean>()
        ready = d
        var ok = false
        val t = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                try {
                    tts?.language = Locale.US
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            utterances.remove(utteranceId)?.complete(true)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            utterances.remove(utteranceId)?.complete(false)
                        }
                    })
                    ok = true
                } catch (_: Throwable) {
                    ok = false
                }
            }
            d.complete(ok)
        }
        tts = t
        return true // async; callers must call waitReady()
    }

    private suspend fun waitReady(): Boolean {
        if (!ensure()) return false
        return ready?.await() ?: false
    }

    /** Synthesize [text] to [out] — blocking until the engine finishes. */
    suspend fun synthesize(
        text: String,
        out: File,
        pitch: Float = 1.0f,
        rate: Float = 1.0f,
    ): Boolean = withContext(Dispatchers.Main) {
        if (!waitReady()) return@withContext false
        val engine = tts ?: return@withContext false
        out.parentFile?.mkdirs()
        if (out.exists()) out.delete()

        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        utterances[id] = done

        engine.setPitch(pitch)
        engine.setSpeechRate(rate)
        val params = Bundle()
        engine.synthesizeToFile(text, params, out, id)

        val success = try {
            withTimeout(120_000) { done.await() }
        } catch (_: Throwable) {
            false
        } finally {
            utterances.remove(id)
        }
        success && out.exists() && out.length() > 0
    }

    fun shutdown() {
        try {
            tts?.shutdown()
        } catch (_: Throwable) {
        }
        tts = null
        ready = null
    }

    companion object {
        const val PITCH_A = 1.0f
        const val PITCH_B = 1.35f
        const val RATE_A = 1.0f
        const val RATE_B = 0.96f
    }
}
