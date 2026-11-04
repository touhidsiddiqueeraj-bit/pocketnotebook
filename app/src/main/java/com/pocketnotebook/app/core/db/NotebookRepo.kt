package com.pocketnotebook.app.core.db

import android.content.ContentValues

class NotebookRepo(private val db: SqlDb) {

    fun create(title: String): Long {
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("title", title)
            put("createdAt", now)
            put("updatedAt", now)
        }
        return db.insert("notebooks", null, cv)
    }

    fun rename(id: Long, title: String) {
        val cv = ContentValues().apply {
            put("title", title)
            put("updatedAt", System.currentTimeMillis())
        }
        db.update("notebooks", cv, "id = ?", arrayOf(id.toString()))
    }

    fun touch(id: Long) {
        db.execSQL(
            "UPDATE notebooks SET updatedAt = ? WHERE id = ?",
            arrayOf(System.currentTimeMillis(), id),
        )
    }

    fun delete(id: Long) {
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM chunks WHERE notebookId = ?", arrayOf(id))
            db.execSQL("DELETE FROM sources WHERE notebookId = ?", arrayOf(id))
            db.execSQL("DELETE FROM messages WHERE notebookId = ?", arrayOf(id))
            db.execSQL("DELETE FROM notes WHERE notebookId = ?", arrayOf(id))
            db.execSQL("DELETE FROM artifacts WHERE notebookId = ?", arrayOf(id))
            db.execSQL("DELETE FROM notebooks WHERE id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun get(id: Long): Notebook? =
        db.rawQuery("SELECT id, title, createdAt, updatedAt FROM notebooks WHERE id = ?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst()) rowToNotebook(c) else null }

    fun listAll(): List<Notebook> =
        db.rawQuery("SELECT id, title, createdAt, updatedAt FROM notebooks ORDER BY updatedAt DESC", null)
            .use { c ->
                val out = ArrayList<Notebook>()
                while (c.moveToNext()) out += rowToNotebook(c)
                out
            }

    fun count(): Int =
        db.rawQuery("SELECT COUNT(*) FROM notebooks", null).use { c ->
            c.moveToFirst(); c.getInt(0)
        }

    private fun rowToNotebook(c: StmtCursor) = Notebook(
        id = c.getLong(0), title = c.getString(1), createdAt = c.getLong(2), updatedAt = c.getLong(3),
    )
}
