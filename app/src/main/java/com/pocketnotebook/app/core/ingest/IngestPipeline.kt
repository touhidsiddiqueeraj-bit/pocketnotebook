package com.pocketnotebook.app.core.ingest

import android.content.Context
import android.net.Uri
import com.pocketnotebook.app.core.db.SourceRepo
import com.pocketnotebook.app.core.db.SourceStatus
import com.pocketnotebook.app.core.engine.LlmRuntime
import com.pocketnotebook.app.core.retrieval.Chunker

/**
 * Ingestion pipeline: bytes/text → extract → chunk → (embed) → SQLite+FTS5.
 * Runs inside [com.pocketnotebook.app.core.work.IngestWorker] with a
 * foreground notification; failures land in sources.status/error.
 */
class IngestPipeline(
    private val context: Context,
    private val sourceRepo: SourceRepo,
    private val runtime: LlmRuntime,
) {
    data class Result(val charCount: Int, val wordCount: Int, val chunkCount: Int)

    /** Ingest from a SAF content Uri (picked file). */
    suspend fun ingestUri(sourceId: Long, uri: Uri, displayName: String?): Result {
        val bytes = readUri(uri)
        val type = Extractors.sniffType(displayName, bytes)
        val text = when (type) {
            "pdf" -> Extractors.fromPdf(context, bytes)
            "docx" -> Extractors.fromDocx(bytes)
            "html" -> Extractors.fromHtml(String(bytes, Charsets.UTF_8))
            else -> String(bytes, Charsets.UTF_8)
        }
        return store(sourceId, text)
    }

    /** Ingest a URL source. Title comes from the page itself. */
    suspend fun ingestUrl(sourceId: Long, url: String, onTitle: (String) -> Unit = {}): Result {
        val (title, text) = Extractors.fromUrl(url)
        onTitle(title)
        return store(sourceId, text)
    }

    /** Ingest pasted text / manual transcript. */
    suspend fun ingestText(sourceId: Long, text: String): Result = store(sourceId, text)

    /** Dispatch by the staged payload type (called by IngestWorker). */
    suspend fun ingestPending(sourceId: Long): Result {
        val pending = sourceRepo.takePending(sourceId)
            ?: throw Extractors.ExtractException("nothing staged for this source")
        return when {
            pending.uri != null -> ingestUri(sourceId, android.net.Uri.parse(pending.uri), null)
            pending.url != null -> {
                var newTitle: String? = null
                val r = ingestUrl(sourceId, pending.url) { newTitle = it }
                newTitle?.let { t -> sourceRepo.rename(sourceId, t.take(120)) }
                r
            }
            pending.text != null -> ingestText(sourceId, pending.text)
            else -> throw Extractors.ExtractException("empty staged payload")
        }
    }

    private suspend fun store(sourceId: Long, rawText: String): Result {
        val source = sourceRepo.get(sourceId) ?: throw IllegalStateException("source gone")
        val text = rawText.replace(Regex("\\u00A0"), " ").trim()
        if (text.length < 40) {
            throw Extractors.ExtractException("Nothing readable found (too little text)")
        }
        val chunks = Chunker.chunk(text)
        if (chunks.isEmpty()) throw Extractors.ExtractException("Nothing readable found")

        sourceRepo.setStatus(sourceId, SourceStatus.PROCESSING)

        // Embed while we store. If the embedding model isn't installed yet,
        // chunks land un-embedded: retrieval still works via BM25, and a later
        // "backfill" pass can add vectors (models manager).
        val wantEmb = runtime.embed("warmup") != null
        var stored = 0
        for ((index, chunk) in chunks.withIndex()) {
            val emb = if (wantEmb) runtime.embed(chunk.text) else null
            val id = sourceRepo.insertChunk(sourceId, source.notebookId, index, chunk.text, emb)
            stored++
            if (id == -1L) throw IllegalStateException("chunk insert failed")
        }

        val words = Chunker.wordCount(text)
        sourceRepo.setStatus(
            sourceId, SourceStatus.READY,
            charCount = text.length, wordCount = words, chunkCount = stored,
        )
        return Result(text.length, words, stored)
    }

    private fun readUri(uri: Uri): ByteArray = context.contentResolver.openInputStream(uri)?.use {
        it.readBytes()
    } ?: throw Extractors.ExtractException("Could not read the selected file")
}
