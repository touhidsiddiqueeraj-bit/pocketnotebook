package com.pocketnotebook.app.core.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

class ArtifactRepo(private val db: SQLiteDatabase) {

    fun create(notebookId: Long, kind: String, title: String): Long =
        db.insert("artifacts", null, ContentValues().apply {
            put("notebookId", notebookId)
            put("kind", kind)
            put("title", title)
            put("status", ArtifactStatus.PENDING)
            put("createdAt", System.currentTimeMillis())
        })

    fun update(id: Long, status: String? = null, payload: String? = null,
               audioDir: String? = null, error: String? = null, title: String? = null) {
        val cv = ContentValues().apply {
            status?.let { put("status", it) }
            payload?.let { put("payload", it) }
            audioDir?.let { put("audioDir", it) }
            error?.let { put("error", it) }
            title?.let { put("title", it) }
        }
        db.update("artifacts", cv, "id = ?", arrayOf(id.toString()))
    }

    fun get(id: Long): Artifact? =
        db.rawQuery(
            "SELECT id, notebookId, kind, title, status, payload, audioDir, error, createdAt " +
                "FROM artifacts WHERE id = ?", arrayOf(id.toString()),
        ).use { c -> if (c.moveToFirst()) rowToArtifact(c) else null }

    fun list(notebookId: Long, kind: String? = null): List<Artifact> =
        db.rawQuery(
            "SELECT id, notebookId, kind, title, status, payload, audioDir, error, createdAt " +
                "FROM artifacts WHERE notebookId = ? " +
                (if (kind != null) "AND kind = ? " else "") +
                "ORDER BY id DESC",
            if (kind != null) arrayOf(notebookId.toString(), kind) else arrayOf(notebookId.toString()),
        ).use { c ->
            val out = ArrayList<Artifact>()
            while (c.moveToNext()) out += rowToArtifact(c)
            out
        }

    fun delete(id: Long) {
        db.execSQL("DELETE FROM artifacts WHERE id = ?", arrayOf(id))
    }

    private fun rowToArtifact(c: android.database.Cursor) = Artifact(
        id = c.getLong(0), notebookId = c.getLong(1), kind = c.getString(2), title = c.getString(3),
        status = c.getString(4), payload = if (c.isNull(5)) null else c.getString(5),
        audioDir = if (c.isNull(6)) null else c.getString(6),
        error = if (c.isNull(7)) null else c.getString(7), createdAt = c.getLong(8),
    )
}
