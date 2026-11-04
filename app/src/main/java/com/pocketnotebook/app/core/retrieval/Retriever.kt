package com.pocketnotebook.app.core.retrieval

import com.pocketnotebook.app.core.db.SqlDb
import com.pocketnotebook.app.core.db.ChunkRow
import com.pocketnotebook.app.core.db.Citation
import com.pocketnotebook.app.core.db.SourceRepo
import com.pocketnotebook.app.core.engine.LlmRuntime
import com.pocketnotebook.app.core.retrieval.Rrf.fuse

/**
 * Hybrid retrieval: SQLite FTS5 (BM25) union vector cosine, fused with RRF.
 * ~30k chunks → sub-300 ms on a phone (brute force is fine at this scale).
 */
class Retriever(
    private val db: SqlDb,
    private val sourceRepo: SourceRepo,
    private val runtime: LlmRuntime,
) {
    data class Hit(
        val n: Int,
        val chunkId: Long,
        val sourceId: Long,
        val sourceTitle: String,
        val text: String,
    )

    suspend fun retrieve(notebookId: Long, query: String, topK: Int = 8): List<Hit> {
        if (query.isBlank()) return emptyList()

        val bm25 = bm25Rank(notebookId, query, limit = 40)
        val semantic = cosineRank(notebookId, query, limit = 40)

        val fused = fuse(listOf(bm25.map { it.first }, semantic.map { it.first }))
        val taken = fused.take(topK).map { it.first }.toHashSet()

        // Keep BM25 order as the canonical ordering when present.
        val ordered = LinkedHashSet<Long>()
        bm25.map { it.first }.forEach { if (taken.contains(it)) ordered.add(it) }
        semantic.map { it.first }.forEach { if (taken.contains(it)) ordered.add(it) }

        val sources = sourceRepo.listForNotebook(notebookId)
            .associateBy { it.id }
            .filterValues { it.status == "ready" }
            .mapValues { it.value.title }

        val out = ArrayList<Hit>(ordered.size)
        var n = 1
        for (chunkId in ordered) {
            val chunk = sourceRepo.chunkById(chunkId) ?: continue
            val title = sources[chunk.sourceId] ?: continue
            out += Hit(
                n = n++,
                chunkId = chunk.id,
                sourceId = chunk.sourceId,
                sourceTitle = title,
                text = chunk.text,
            )
        }
        return out
    }

    private fun bm25Rank(notebookId: Long, query: String, limit: Int): List<Pair<Long, Double>> {
        val sanitized = query.replace(Regex("[\"'^:]"), " ").trim()
        if (sanitized.isEmpty()) return emptyList()
        return try {
            db.rawQuery(
                """SELECT rowid, bm25(chunks_fts) FROM chunks_fts
                   WHERE chunks_fts MATCH ? AND rowid IN
                     (SELECT id FROM chunks WHERE notebookId = ?)
                   ORDER BY 2 LIMIT ?""",
                arrayOf<Any?>("\"$sanitized\"", notebookId, limit),
            ).use { c ->
                val out = ArrayList<Pair<Long, Double>>()
                while (c.moveToNext()) out += c.getLong(0) to -c.getDouble(1)
                out
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private suspend fun cosineRank(notebookId: Long, query: String, limit: Int): List<Pair<Long, Double>> {
        val qv = runtime.embed(query) ?: return emptyList()
        val scored = ArrayList<Pair<Long, Double>>()
        sourceRepo.forEachEmbChunk(notebookId) { chunk: ChunkRow, emb: FloatArray ->
            scored += chunk.id to Vectors.cosine(qv, emb).toDouble()
        }
        if (scored.isEmpty()) return emptyList()
        return scored.sortedByDescending { it.second }.take(limit)
    }

    companion object {
        /** Numbered context block fed to the model; [n] matches citation chips. */
        fun formatContext(hits: List<Hit>, maxChars: Int = 9000): String {
            val sb = StringBuilder()
            var used = 0
            for (hit in hits) {
                val block = "[${hit.n}] From \"${hit.sourceTitle}\":\n${hit.text}\n\n"
                if (used + block.length > maxChars) break
                sb.append(block)
                used += block.length
            }
            return sb.toString()
        }

        fun citations(hits: List<Hit>): List<Citation> = hits.map {
            Citation(
                n = it.n,
                chunkId = it.chunkId,
                sourceId = it.sourceId,
                sourceTitle = it.sourceTitle,
                snippet = it.text.take(220),
            )
        }
    }
}
