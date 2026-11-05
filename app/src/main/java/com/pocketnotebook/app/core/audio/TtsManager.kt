package com.pocketnotebook.app.core.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 *
 * Every failure path records WHY in [lastFailure] (and [lastStatus]): the
 * alpha.12 bake died with a bare "TTS failed at segment 1" and no reason,
 * while the usual causes are device-side and fixable by the user — no TTS
 * engine installed, or voice data not downloaded for the language.
 */
class TtsManager(private val context: Context) {

    @Volatile private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private val utterances = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /** Human-readable state of the last init/synthesis — shown in Diagnostics. */
    @Volatile var lastStatus: String = "not initialized"
        private set

    /** Why the last synthesize failed (null = none yet). */
    @Volatile var lastFailure: String? = null
        private set

    @Volatile private var language: Locale? = null

    /**
     * First usable voice: en-US → en-GB → en-IN → system default → ANY voice
     * the engine offers. A missing locale result means the voice data is not
     * downloaded (fresh device, offline) — that must surface as a clear
     * reason, not as a silent synthesis failure at bake time.
     */
    private fun pickLanguage(e: TextToSpeech): Locale? {
        val preferred = listOf(Locale.US, Locale.UK, Locale("en", "IN"), Locale.getDefault())
        for (loc in preferred) {
            if (e.setLanguage(loc) >= 0) return loc
        }
        val any = try {
            e.availableLanguages.firstOrNull { e.setLanguage(it) >= 0 }
        } catch (_: Throwable) {
            null
        }
        return any
    }

    @Synchronized
    private fun ensure(): CompletableDeferred<Boolean>? {
        // Return the SAME deferred whether init already finished or is still
        // in flight — the old code returned hard-false for a concurrent
        // second caller while init was pending.
        ready?.let { return it }

        val d = CompletableDeferred<Boolean>()
        val initReason = arrayOf<String?>(null)
        // The init callback runs as a main-looper message, strictly AFTER
        // the constructor returns and `ref` is assigned — but it can't
        // capture the constructor's own result value, so it reads it
        // through this captured holder instead.
        var ref: TextToSpeech? = null
        val t = TextToSpeech(context.applicationContext) { status ->
            val e = ref
            var ok = false
            if (status != TextToSpeech.SUCCESS) {
                initReason[0] =
                    "no working system text-to-speech engine (init status=$status) — " +
                        "install/enable one in system Settings → Text-to-speech"
            } else if (e == null) {
                initReason[0] = "engine reference lost during init"
            } else {
                try {
                    e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            utterances.remove(utteranceId)?.complete(true)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            utterances.remove(utteranceId)?.complete(false)
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            // The engine tells us WHY — record it instead of
                            // discarding it (the deprecated overload has no code).
                            lastFailure = describe(errorCode)
                            utterances.remove(utteranceId)?.complete(false)
                        }
                    })
                    val lang = pickLanguage(e)
                    if (lang == null) {
                        initReason[0] =
                            "no installed voice (English or system language) — open system " +
                                "Settings → Text-to-speech and download the engine's voice data, " +
                                "then retry the bake"
                    } else {
                        language = lang
                        ok = true
                    }
                } catch (t2: Throwable) {
                    initReason[0] = "init: ${t2.message ?: t2.toString()}"
                }
            }
            lastStatus = if (ok) {
                "ready · engine=${e?.defaultEngine ?: "?"} · voice=$language"
            } else {
                "failed · ${initReason[0]}"
            }
            if (!ok) lastFailure = initReason[0]
            d.complete(ok)
        }
        ref = t
        tts = t
        ready = d
        return d
    }

    private suspend fun waitReady(): Boolean = ensure()?.await() ?: false

    /**
     * Initialize the engine without synthesizing — used by Diagnostics so the
     * report shows the real engine/voice state of the device.
     */
    suspend fun probe(): Boolean = withContext(Dispatchers.Main) { waitReady() }

    /** Engine error code → human reason. */
    private fun describe(code: Int): String = when (code) {
        TextToSpeech.ERROR -> "generic engine error"
        TextToSpeech.ERROR_SYNTHESIS -> "synthesis failed — voice data missing for the language"
        TextToSpeech.ERROR_SERVICE -> "TTS service died"
        TextToSpeech.ERROR_OUTPUT -> "audio output/file write failed"
        TextToSpeech.ERROR_NETWORK -> "network error — voice data download interrupted?"
        TextToSpeech.ERROR_NETWORK_TIMEOUT -> "network timeout — voice data download interrupted?"
        TextToSpeech.ERROR_INVALID_REQUEST -> "invalid request (empty or unusable text)"
        TextToSpeech.ERROR_NOT_INSTALLED_YET ->
            "voice data not downloaded yet — go online once, Settings → Text-to-speech → " +
                "Play, then retry"
        else -> "engine error code $code"
    }

    /** One synthesis attempt — must run on the main dispatcher. */
    private suspend fun attempt(text: String, out: File, pitch: Float, rate: Float): Boolean {
        val engine = tts ?: run { lastFailure = "engine not initialized"; return false }
        out.parentFile?.mkdirs()
        if (out.exists()) out.delete()

        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        utterances[id] = done

        engine.setPitch(pitch)
        engine.setSpeechRate(rate)
        val queued = try {
            engine.synthesizeToFile(text, Bundle(), out, id)
        } catch (t: Throwable) {
            lastFailure = "queue failed: ${t.message ?: t.toString()}"
            return false
        }
        if (queued == TextToSpeech.ERROR) {
            utterances.remove(id)
            lastFailure = "engine refused the utterance (${engine.defaultEngine ?: "default engine"})"
            return false
        }

        val success = try {
            withTimeout(120_000) { done.await() }
        } catch (_: Throwable) {
            false
        } finally {
            utterances.remove(id)
        }
        if (!success) {
            if (lastFailure == null) lastFailure = "synthesis timed out or failed (no engine detail)"
            return false
        }
        if (!out.exists() || out.length() == 0L) {
            lastFailure = "engine reported success but wrote no audio"
            return false
        }
        return true
    }

    /** Synthesize [text] to [out] — blocking until the engine finishes. */
    suspend fun synthesize(
        text: String,
        out: File,
        pitch: Float = 1.0f,
        rate: Float = 1.0f,
    ): Boolean = withContext(Dispatchers.Main) {
        lastFailure = null
        if (text.isBlank()) {
            lastFailure = "segment text is empty after speech cleanup"
            return@withContext false
        }
        if (!waitReady()) {
            // ensure()/init already recorded the exact reason in lastFailure.
            return@withContext false
        }
        if (attempt(text, out, pitch, rate)) return@withContext true
        // One retry: several stock engines drop the very first utterance
        // right after init — exactly the "failed at segment 1" case.
        val first = lastFailure
        delay(750)
        lastFailure = null
        if (attempt(text, out, pitch, rate)) return@withContext true
        if (lastFailure == null) lastFailure = first
        lastStatus = "failed · ${lastFailure}"
        false
    }

    @Synchronized
    fun shutdown() {
        try {
            tts?.shutdown()
        } catch (_: Throwable) {
        }
        tts = null
        ready = null
        language = null
        lastStatus = "shutdown"
    }

    companion object {
        const val PITCH_A = 1.0f
        const val PITCH_B = 1.35f
        const val RATE_A = 1.0f
        const val RATE_B = 0.96f
    }
}
