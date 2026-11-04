package com.pocketnotebook.app.core.db

import android.content.ContentValues

class ModelRepo(private val db: SqlDb) {

    fun upsert(slot: String, repoId: String, fileName: String, filePath: String,
               sizeBytes: Long, sha256: String?, verified: Boolean): Long {
        val existing = findByPath(filePath)
        val cv = ContentValues().apply {
            put("slot", slot)
            put("repoId", repoId)
            put("fileName", fileName)
            put("filePath", filePath)
            put("sizeBytes", sizeBytes)
            sha256?.let { put("sha256", it) }
            put("verified", if (verified) 1 else 0)
            put("addedAt", System.currentTimeMillis())
        }
        return if (existing == null) db.insert("models", null, cv)
        else {
            db.update("models", cv, "id = ?", arrayOf(existing.id.toString()))
            existing.id
        }
    }

    fun findByPath(filePath: String): ModelRecord? =
        db.rawQuery(
            "SELECT id, slot, repoId, fileName, filePath, sizeBytes, sha256, verified, addedAt " +
                "FROM models WHERE filePath = ?", arrayOf(filePath),
        ).use { c -> if (c.moveToFirst()) rowToModel(c) else null }

    fun listAll(): List<ModelRecord> =
        db.rawQuery(
            "SELECT id, slot, repoId, fileName, filePath, sizeBytes, sha256, verified, addedAt " +
                "FROM models ORDER BY addedAt DESC", null,
        ).use { c ->
            val out = ArrayList<ModelRecord>()
            while (c.moveToNext()) out += rowToModel(c)
            out
        }

    fun delete(id: Long) {
        db.execSQL("DELETE FROM models WHERE id = ?", arrayOf(id))
    }

    private fun rowToModel(c: StmtCursor) = ModelRecord(
        id = c.getLong(0), slot = c.getString(1), repoId = c.getString(2),
        fileName = c.getString(3), filePath = c.getString(4), sizeBytes = c.getLong(5),
        sha256 = if (c.isNull(6)) null else c.getString(6), verified = c.getInt(7) == 1,
        addedAt = c.getLong(8),
    )
}
