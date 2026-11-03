package com.pocketnotebook.app.core.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SourceRepo(private val db: SQLiteDatabase) {

    // ---- sources -----------------------------------------------------------

    fun create(notebookId: Long, type: String, title: String): Long =
        db.insert("sources", null, ContentValues().apply {
            put("notebookId", notebookId)
            put("type", type)
            put("title", title)
            put("status", SourceStatus.PENDING)
            put("addedAt", System.currentTimeMillis())
        })

    fun setStatus(id: Long, status: String, error: String? = null, charCount: Int? = null,
                 wordCount: Int? = null, chunkCount: Int? = null) {
        val cv = ContentValues().apply {
            put("status", status)
            error?.let { put("error", it) } ?: if (status == SourceStatus.READY) putNull("error")
        }
        charCount?.let { put -> cv.put("charCount", it) }
        wordCount?.let { cv.put("wordCount", it) }
        chunkCount?.let { cv.put("chunkCount", it) }
        db.update("sources", cv, "id = ?", arrayOf(id.toString()))
    }

    fun setSummary(id: Long, summary: String) {
        db.execSQL("UPDATE sources SET summary = ? WHERE id = ?", arrayOf(summary, id))
    }

    fun rename(id: Long, title: String) {
        db.execSQL("UPDATE sources SET title = ? WHERE id = ?", arrayOf(title, id))
    }

    fun countForNotebook(notebookId: Long): Int =
        db.rawQuery("SELECT COUNT(*) FROM sources WHERE notebookId = ?", arrayOf(notebookId.toString()))
            .use { c -> c.moveToFirst(); c.getInt(0) }

    fun listForNotebook(notebookId: Long): List<Source> =
        db.rawQuery(
            "SELECT id, notebookId, type, title, status, error, charCount, wordCount, chunkCount, summary, addedAt " +
                "FROM sources WHERE notebookId = ? ORDER BY addedAt DESC",
            arrayOf(notebookId.toString()),
        ).use { c ->
            val out = ArrayList<Source>()
            while (c.moveToNext()) out += Source(
                id = c.getLong(0), notebookId = c.getLong(1), type = c.getString(2),
                title = c.getString(3), status = c.getString(4),
                error = if (c.isNull(5)) null else c.getString(5),
                charCount = c.getInt(6), wordCount = c.getInt(7), chunkCount = c.getInt(8),
                summary = if (c.isNull(9)) null else c.getString(9),
                addedAt = c.getLong(10),
            )
            out
        }

    fun get(id: Long): Source? =
        db.rawQuery(
            "SELECT id, notebookId, type, title, status, error, charCount, wordCount, chunkCount, summary, addedAt " +
                "FROM sources WHERE id = ?", arrayOf(id.toString()),
        ).use { c -> if (c.moveToFirst()) Source(
            id = c.getLong(0), notebookId = c.getLong(1), type = c.getString(2),
            title = c.getString(3), status = c.getString(4),
            error = if (c.isNull(5)) null else c.getString(5),
            charCount = c.getInt(6), wordCount = c.getInt(7), chunkCount = c.getInt(8),
            summary = if (c.isNull(9)) null else c.getString(9),
            addedAt = c.getLong(10),
        ) else null }

    fun delete(id: Long) {
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM chunks WHERE sourceId = ?", arrayOf(id))
            db.execSQL("DELETE FROM sources WHERE id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---- chunks (write path used by the ingest pipeline) -------------------

    fun insertChunk(sourceId: Long, notebookId: Long, ordinal: Int, text: String, emb: FloatArray?): Long {
        val cv = ContentValues().apply {
            put("sourceId", sourceId)
            put("notebookId", notebookId)
            put("ordinal", ordinal)
            put("text", text)
            if (emb != null) put("emb", floatsToBytes(emb))
        }
        return db.insert("chunks", null, cv)
    }

    fun listChunks(sourceId: Long): List<ChunkRow> =
        db.rawQuery(
            "SELECT id, sourceId, notebookId, ordinal, text FROM chunks WHERE sourceId = ? ORDER BY ordinal",
            arrayOf(sourceId.toString()),
        ).use { c -> mapChunkRows(c) }

    fun listChunkTexts(sourceId: Long): List<String> =
        db.rawQuery(
            "SELECT text FROM chunks WHERE sourceId = ? ORDER BY ordinal", arrayOf(sourceId.toString()),
        ).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out += c.getString(0)
            out
        }

    fun chunkById(id: Long): ChunkRow? =
        db.rawQuery(
            "SELECT id, sourceId, notebookId, ordinal, text FROM chunks WHERE id = ?", arrayOf(id.toString()),
        ).use { c -> if (c.moveToFirst()) rowToChunk(c) else null }

    fun countChunks(notebookId: Long): Int =
        db.rawQuery("SELECT COUNT(*) FROM chunks WHERE notebookId = ?", arrayOf(notebookId.toString()))
            .use { c -> c.moveToFirst(); c.getInt(0) }

    /** Chunks + embeddings for vector search, streamed to the consumer to keep memory flat. */
    fun forEachEmbChunk(notebookId: Long, block: (ChunkRow, FloatArray) -> Unit) {
        db.rawQuery(
            "SELECT id, sourceId, notebookId, ordinal, text, emb FROM chunks " +
                "WHERE notebookId = ? AND emb IS NOT NULL", arrayOf(notebookId.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val row = rowToChunk(c)
                val emb = bytesToFloats(c.getBlob(5))
                if (emb.isNotEmpty()) block(row, emb)
            }
        }
    }

    // ---- ingest staging -----------------------------------------------------

    fun putPending(sourceId: Long, uri: String? = null, url: String? = null, text: String? = null) {
        db.execSQL(
            "INSERT OR REPLACE INTO pending_ingest(sourceId, uri, url, text) VALUES (?,?,?,?)",
            arrayOf(sourceId, uri, url, text),
        )
    }

    data class Pending(val uri: String?, val url: String?, val text: String?)

    fun takePending(sourceId: Long): Pending? {
        val row = db.rawQuery(
            "SELECT uri, url, text FROM pending_ingest WHERE sourceId = ?", arrayOf(sourceId.toString()),
        ).use { c ->
            if (c.moveToFirst()) Pending(
                if (c.isNull(0)) null else c.getString(0),
                if (c.isNull(1)) null else c.getString(1),
                if (c.isNull(2)) null else c.getString(2),
            ) else null
        }
        if (row != null) db.execSQL("DELETE FROM pending_ingest WHERE sourceId = ?", arrayOf(sourceId))
        return row
    }

    // ---- helpers -----------------------------------------------------------

    private fun mapChunkRows(c: android.database.Cursor): List<ChunkRow> {
        val out = ArrayList<ChunkRow>()
        while (c.moveToNext()) out += rowToChunk(c)
        return out
    }

    private fun rowToChunk(c: android.database.Cursor) = ChunkRow(
        id = c.getLong(0), sourceId = c.getLong(1), notebookId = c.getLong(2),
        ordinal = c.getInt(3), text = c.getString(4),
    )

    companion object {
        fun floatsToBytes(a: FloatArray): ByteArray {
            val bb = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.asFloatBuffer().put(a)
            return bb.array()
        }

        fun bytesToFloats(b: ByteArray): FloatArray {
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            val out = FloatArray(b.size / 4)
            bb.asFloatBuffer().get(out)
            return out
        }
    }
}
