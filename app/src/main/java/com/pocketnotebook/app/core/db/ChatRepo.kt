package com.pocketnotebook.app.core.db

import android.content.ContentValues

class ChatRepo(private val db: SqlDb) {

    fun add(notebookId: Long, role: String, content: String, cites: String?): Long =
        db.insert("messages", null, ContentValues().apply {
            put("notebookId", notebookId)
            put("role", role)
            put("content", content)
            cites?.let { put("cites", it) }
            put("createdAt", System.currentTimeMillis())
        })

    fun recent(notebookId: Long, limit: Int = 200): List<ChatMessage> =
        db.rawQuery(
            "SELECT id, notebookId, role, content, cites, createdAt FROM messages " +
                "WHERE notebookId = ? ORDER BY id DESC LIMIT ?",
            arrayOf<Any?>(notebookId, limit),
        ).use { c ->
            val out = ArrayList<ChatMessage>()
            while (c.moveToNext()) {
                out += ChatMessage(
                    id = c.getLong(0), notebookId = c.getLong(1), role = c.getString(2),
                    content = c.getString(3),
                    cites = if (c.isNull(4)) null else c.getString(4),
                    createdAt = c.getLong(5),
                )
            }
            out.reversed()
        }

    fun lastTwoRoles(notebookId: Long): Boolean =
        db.rawQuery(
            "SELECT COUNT(*) FROM messages WHERE notebookId = ?", arrayOf(notebookId.toString()),
        ).use { c -> c.moveToFirst(); c.getInt(0) > 0 }

    fun clear(notebookId: Long) {
        db.execSQL("DELETE FROM messages WHERE notebookId = ?", arrayOf(notebookId))
    }
}
