package com.pocketnotebook.app.core.engine

import android.app.ActivityManager
import android.content.Context
import com.pocketnotebook.app.core.db.Db
import com.pocketnotebook.app.core.db.ModelRepo
import com.pocketnotebook.app.core.db.ModelSlot
import com.pocketnotebook.app.core.native.PocketLlm
import com.pocketnotebook.app.core.prefs.PocketPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns the loaded llama.cpp model handles. Two slots in v0.1:
 *  - CHAT   (persistent — the primary surface)
 *  - EMBED  (tiny, loaded during ingestion/retrieval)
 * AUDIO slot (LFM3-Audio-2B) lands with the Phase 0 native wiring.
 */
class LlmRuntime(
    private val context: Context,
    private val db: Db,
    private val prefs: PocketPrefs,
) {
    data class RuntimeState(
        val nativeReady: Boolean = false,
        val chatPath: String? = null,
        val chatMeta: PocketLlm.Meta? = null,
        val chatLoading: Boolean = false,
        val embedPath: String? = null,
        val embedLoading: Boolean = false,
        val generating: Boolean = false,
    )

    private val _state = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = _state

    @Volatile private var chatHandle: Long = 0
    @Volatile private var embedHandle: Long = 0
    private val chatLoadMutex = Mutex()
    private val embedLoadMutex = Mutex()
    private val genMutex = Mutex()

    private val threads: Int
        get() {
            val pref = prefs.threadCount
            if (pref > 0) return pref
            val cores = Runtime.getRuntime().availableProcessors()
            return cores.coerceIn(2, 8)
        }

    /** Low-RAM devices get a smaller default context. */
    private fun autoContext(): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memClass = am?.memoryClass ?: 128
        return if (memClass <= 128) 4096 else prefs.contextLength
    }

    val nativeReady: Boolean get() = PocketLlm.fullEngine

    // ------------------------------------------------------------------ chat

    suspend fun ensureChatLoaded(): Boolean {
        if (chatHandle != 0L) return true
        if (!PocketLlm.fullEngine) return false
        val path = activeModelPath(ModelSlot.CHAT) ?: return false
        return chatLoadMutex.withLock {
            if (chatHandle != 0L) return true
            val file = File(path)
            if (!file.exists()) {
                prefs.setActiveModel(ModelSlot.CHAT, null)
                return false
            }
            _state.value = _state.value.copy(chatLoading = true)
            val ok = withContext(Dispatchers.IO) {
                PocketLlm.nativeBackendInit()
                val h = PocketLlm.nativeLoadModel(path, autoContext(), threads, false)
                chatHandle = h
                h != 0L
            }
            _state.value = _state.value.copy(
                chatLoading = false,
                chatPath = if (ok) path else null,
                chatMeta = if (ok) PocketLlm.meta(chatHandle) else null,
            )
            ok
        }
    }

    suspend fun unloadChat() {
        chatLoadMutex.withLock {
            if (chatHandle != 0L) {
                withContext(Dispatchers.IO) { PocketLlm.nativeUnload(chatHandle) }
                chatHandle = 0
            }
            _state.value = _state.value.copy(chatPath = null, chatMeta = null)
        }
    }

    // ---------------------------------------------------------------- embed

    suspend fun ensureEmbedLoaded(): Boolean {
        if (embedHandle != 0L) return true
        if (!PocketLlm.fullEngine) return false
        val path = activeModelPath(ModelSlot.EMBED) ?: return false
        return embedLoadMutex.withLock {
            if (embedHandle != 0L) return true
            if (!File(path).exists()) {
                prefs.setActiveModel(ModelSlot.EMBED, null)
                return false
            }
            _state.value = _state.value.copy(embedLoading = true)
            val ok = withContext(Dispatchers.IO) {
                PocketLlm.nativeBackendInit()
                val h = PocketLlm.nativeLoadModel(path, 1024, threads, true)
                embedHandle = h
                h != 0L
            }
            _state.value = _state.value.copy(
                embedLoading = false,
                embedPath = if (ok) path else null,
            )
            ok
        }
    }

    suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.IO) {
        if (!ensureEmbedLoaded() || embedHandle == 0L) return@withContext null
        try {
            val v = PocketLlm.nativeEmbed(embedHandle, text)
            if (v.isEmpty()) null else v
        } catch (_: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------- generation

    suspend fun chatTemplate(roles: List<String>, contents: List<String>, addAssist: Boolean = true): String {
        val h = chatHandle
        if (h == 0L) return ""
        return withContext(Dispatchers.IO) {
            PocketLlm.nativeApplyTemplate(h, roles.toTypedArray(), contents.toTypedArray(), addAssist)
        }
    }

    suspend fun tokenCount(text: String): Int {
        val h = chatHandle
        if (h == 0L) return text.length / 4
        return withContext(Dispatchers.IO) { PocketLlm.nativeTokenCount(h, text) }
    }

    /**
     * One completion at a time per process (mobile RAM/KV-cache reality).
     * [onToken] runs on an IO thread — hop for UI updates.
     */
    suspend fun complete(
        prompt: String,
        grammar: String? = null,
        grammarRoot: String? = null,
        temperature: Float = prefs.temperature,
        maxTokens: Int = prefs.maxTokens,
        topK: Int = 40,
        topP: Float = 0.95f,
        seed: Int = -1,
        stop: String = "",
        onToken: (String) -> Unit = {},
    ): String = genMutex.withLock {
        if (!ensureChatLoaded()) return ""
        _state.value = _state.value.copy(generating = true)
        try {
            withContext(Dispatchers.IO) {
                PocketLlm.completeBlocking(
                    chatHandle, prompt, grammar, grammarRoot,
                    temperature, topK, topP, seed, maxTokens, stop, onToken,
                )
            }
        } finally {
            _state.value = _state.value.copy(generating = false)
        }
    }

    fun abortGeneration() {
        if (chatHandle != 0L) PocketLlm.nativeAbort(chatHandle)
    }

    // ---------------------------------------------------------------- models

    /** Public: does this slot have a model set (prefs, DB fallback)? */
    fun modelPathFor(slot: String): String? = activeModelPath(slot)

    private fun activeModelPath(slot: String): String? {
        prefs.activeModelPath(slot)?.let { return it }
        val repo = ModelRepo(db.writableDatabase)
        val any = repo.listAll().firstOrNull { it.slot == slot } ?: return null
        return any.filePath
    }

    fun modelsDir(): File = File(context.filesDir, "models").apply { mkdirs() }
}
