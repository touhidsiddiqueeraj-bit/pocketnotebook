package com.pocketnotebook.app.core.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

class NoteRepo(private val db: SQLiteDatabase) {

    fun add(notebookId: Long, text: String, pinned: Boolean = false): Long =
        db.insert("notes", null, ContentValues().apply {
            put("notebookId", notebookId)
            put("pinned", if (pinned) 1 else 0)
            put("text", text)
            put("createdAt", System.currentTimeMillis())
        })

    fun setPinned(id: Long, pinned: Boolean) {
        db.execSQL("UPDATE notes SET pinned = ? WHERE id = ?", arrayOf(if (pinned) 1 else 0, id))
    }

    fun delete(id: Long) {
        db.execSQL("DELETE FROM notes WHERE id = ?", arrayOf(id))
    }

    fun list(notebookId: Long): List<Note> =
        db.rawQuery(
            "SELECT id, notebookId, pinned, text, createdAt FROM notes " +
                "WHERE notebookId = ? ORDER BY pinned DESC, createdAt DESC",
            arrayOf(notebookId.toString()),
        ).use { c ->
            val out = ArrayList<Note>()
            while (c.moveToNext()) out += Note(
                id = c.getLong(0), notebookId = c.getLong(1), pinned = c.getInt(2) == 1,
                text = c.getString(3), createdAt = c.getLong(4),
            )
            out
        }
}
