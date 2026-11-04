package com.pocketnotebook.app.core.native

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * JNI bridge to the native llama.cpp engine (libpocketllm.so).
 *
 * Two build flavors share this file:
 *  - FULL build: llama.cpp is checked out under native/llama.cpp; the .so
 *    contains the real engine ([fullEngine] == true).
 *  - KIT build (-PskipNative, fast CI): the .so is a stub returning benign
 *    failures; [fullEngine] == false and the app shows a setup hint.
 */
object PocketLlm {

    private const val MAGIC = 0x504C4D31 // must match pocket_jni.cpp kMagic
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Engine libs, tried in order (see app/src/main/cpp/CMakeLists.txt):
     *  1. "pocketllm_ocl" — OpenCL/Adreno GPU engine; shipped only in the
     *     experimental -gpu APK. It hard-links the device's vendor
     *     libOpenCL.so, and ROMs that do not expose that lib to apps reject
     *     the whole .so — so it must never be the only engine on board.
     *  2. "pocketllm" — CPU engine; shipped in every APK and must always
     *     load. A missing GPU lib is normal (standard APK), not an error.
     */
    private val gpuLoadError: String? = try {
        System.loadLibrary("pocketllm_ocl")
        null
    } catch (t: Throwable) {
        t.message ?: t.toString()
    }

    private val cpuLoadError: String? = if (gpuLoadError == null) null else try {
        System.loadLibrary("pocketllm")
        null
    } catch (t: Throwable) {
        t.message ?: t.toString()
    }

    /** Which engine lib is mapped: "opencl", "cpu", or "" when none loaded. */
    val engineVariant: String = when {
        gpuLoadError == null -> "opencl"
        cpuLoadError == null -> "cpu"
        else -> ""
    }

    /**
     * The fatal System.loadLibrary failure when NO engine lib could be
     * mapped (null when one loaded) — e.g. a 16 KB-page device rejecting a
     * 4 KB-aligned lib, or the -gpu APK on a ROM without a public
     * libOpenCL.so. Surfaced in Settings → Diagnostics.
     */
    val loadError: String? = if (engineVariant.isNotEmpty()) null else cpuLoadError

    /** Last engine error (model load / context init / decode), or null. */
    fun lastError(): String? {
        if (!libLoaded) return loadError
        return try {
            nativeLastError().ifBlank { null }
        } catch (_: Throwable) {
            null
        }
    }

    /** True when any native engine lib was mapped into the process. */
    val libLoaded: Boolean get() = loadError == null

    /**
     * Banner text for the "engine unavailable" state, or null when the full
     * engine is ready. Splits the two failure classes alpha.9 conflated: a
     * real kit build (stub .so, no engine compiled in) vs a LOAD failure —
     * back then any load failure printed "Kit build", so a release APK
     * whose OpenCL-linked engine was rejected by the ROM looked like a kit
     * build on perfectly good hardware.
     */
    val engineIssue: String? get() = when {
        fullEngine -> null
        loadError != null ->
            if (gpuLoadError != null && gpuLoadError.contains("libOpenCL"))
                "GPU engine failed to load — this ROM does not expose OpenCL to apps " +
                    "(experimental -gpu APK). Install the standard arm64 APK (CPU engine) instead."
            else
                "AI engine failed to load on this device. See Settings → Diagnostics for the reason."
        else -> "Kit build — no AI engine compiled in. Install a release APK."
    }

    /** True when the real llama.cpp engine is compiled into the app. */
    val fullEngine: Boolean by lazy {
        if (!libLoaded) return@lazy false
        try {
            nativeBackendInit()
            nativePing() == MAGIC
        } catch (_: Throwable) {
            false
        }
    }

    // ---------------------------------------------------------------------
    // Streaming token bridge — native calls emitToken() during completion.
    // ---------------------------------------------------------------------

    private val tokenListeners = ConcurrentHashMap<Long, (String) -> Unit>()
    private val nextCallbackId = AtomicLong(1)

    @JvmStatic
    fun emitToken(id: Long, piece: String) {
        tokenListeners[id]?.invoke(piece)
    }

    @JvmStatic
    fun onDone(id: Long) {
        tokenListeners.remove(id)
    }

    // ---------------------------------------------------------------------
    // Native surface (see pocket_jni.cpp for contracts)
    // ---------------------------------------------------------------------

    external fun nativePing(): Int
    external fun nativeBackendInit()
    external fun nativeLastError(): String
    external fun nativeLoadModel(
        path: String, nCtx: Int, nThreads: Int, embedding: Boolean, kvQuant: Int,
    ): Long
    external fun nativeUnload(id: Long)
    external fun nativeMeta(id: Long): String
    external fun nativeTokenCount(id: Long, text: String): Int
    external fun nativeApplyTemplate(
        id: Long, roles: Array<String>, contents: Array<String>, addAssist: Boolean,
    ): String
    external fun nativeComplete(
        id: Long, prompt: String, grammar: String?, grammarRoot: String?,
        temp: Float, topK: Int, topP: Float, seed: Int, maxTokens: Int,
        stop: String, callbackId: Long,
    ): String
    external fun nativeAbort(id: Long)
    external fun nativeEmbed(id: Long, text: String): FloatArray

    /** GPU backend status from the engine log (device + offload result). */
    fun gpuInfo(): String = try {
        if (!libLoaded) "native lib not loaded" else nativeGpuInfo()
    } catch (_: Throwable) {
        "unavailable"
    }

    private external fun nativeGpuInfo(): String

    @Serializable
    data class Meta(
        val desc: String = "",
        val name: String = "",
        val arch: String = "",
        val nCtxTrain: Int = 0,
        val nParams: Long = 0,
        val sizeBytes: Long = 0,
        val nEmbd: Int = 0,
        val hasTemplate: Boolean = false,
        val embedding: Boolean = false,
    )

    fun meta(id: Long): Meta? = if (id == 0L) null else try {
        json.decodeFromString(nativeMeta(id))
    } catch (_: Throwable) {
        null
    }

    /**
     * Blocking completion on the caller's thread (call from Dispatchers.IO).
     * [onToken] fires on this same thread per generated piece — keep it cheap
     * (e.g. Channel.trySend) and hop to another dispatcher for UI work.
     */
    fun completeBlocking(
        id: Long,
        prompt: String,
        grammar: String? = null,
        grammarRoot: String? = null,
        temp: Float = 0.7f,
        topK: Int = 40,
        topP: Float = 0.95f,
        seed: Int = -1,
        maxTokens: Int = 512,
        stop: String = "",
        onToken: (String) -> Unit = {},
    ): String {
        val cb = nextCallbackId.incrementAndGet()
        tokenListeners[cb] = onToken
        return try {
            nativeComplete(
                id, prompt, grammar, grammarRoot,
                temp, topK, topP, seed, maxTokens, stop, cb,
            )
        } finally {
            tokenListeners.remove(cb)
        }
    }
}
