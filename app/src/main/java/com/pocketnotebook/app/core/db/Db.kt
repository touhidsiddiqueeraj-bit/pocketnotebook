package com.pocketnotebook.app.core.db

import android.content.ContentValues
import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_FULLMUTEX
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import java.io.Closeable

/**
 * Database bootstrap on the bundled SQLite engine (androidx.sqlite:sqlite-bundled,
 * SQLite 3.50.1 compiled with FTS5).
 *
 * Why bundled: Android's system SQLite — on every vendor, including Android 15/16 —
 * ships WITHOUT the FTS5 module, so creating the full-text index failed at first
 * launch with "no such module: fts5" (the v0.1.0-alpha.2 startup error). Bundling
 * the engine gives identical SQL behavior on every device and a 16 KB-page-aligned
 * native library for Android 15/16.
 */
class Db(context: Context) : Closeable {

    val conn: SQLiteConnection
    val writableDatabase: SqlDb
    val readableDatabase: SqlDb get() = writableDatabase

    init {
        val file = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        // The bundled engine is compiled in multi-thread mode, so the single shared
        // connection opts into serialized mode (FULLMUTEX) — same threading
        // contract the old framework SQLiteDatabase had.
        conn = BundledSQLiteDriver().open(
            file.absolutePath,
            SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE or SQLITE_OPEN_FULLMUTEX,
        )
        migrate(conn)
        writableDatabase = SqlDb(conn)
    }

    override fun close() {
        conn.close()
    }

    companion object {
        private const val DB_NAME = "pocketnotebook.db"
        private const val SCHEMA_VERSION = 1

        /**
         * The real DDL, public so the JVM unit test can replay the exact schema
         * against an FTS5-enabled engine (regression gate for the alpha.2 crash).
         */
        val DDL: List<String> = listOf(
            """CREATE TABLE notebooks(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL)""",
            """CREATE TABLE sources(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                notebookId INTEGER NOT NULL,
                type TEXT NOT NULL,
                title TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'pending',
                error TEXT,
                charCount INTEGER NOT NULL DEFAULT 0,
                wordCount INTEGER NOT NULL DEFAULT 0,
                chunkCount INTEGER NOT NULL DEFAULT 0,
                summary TEXT,
                addedAt INTEGER NOT NULL)""",
            """CREATE TABLE chunks(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sourceId INTEGER NOT NULL,
                notebookId INTEGER NOT NULL,
                ordinal INTEGER NOT NULL,
                text TEXT NOT NULL,
                emb BLOB)""",
            """CREATE VIRTUAL TABLE chunks_fts USING fts5(
                text, content='chunks', content_rowid='id')""",
            """CREATE TRIGGER chunks_ai AFTER INSERT ON chunks BEGIN
                INSERT INTO chunks_fts(rowid, text) VALUES (new.id, new.text);
                END""",
            """CREATE TRIGGER chunks_ad AFTER DELETE ON chunks BEGIN
                INSERT INTO chunks_fts(chunks_fts, rowid, text) VALUES('delete', old.id, old.text);
                END""",
            """CREATE TRIGGER chunks_au AFTER UPDATE ON chunks BEGIN
                INSERT INTO chunks_fts(chunks_fts, rowid, text) VALUES('delete', old.id, old.text);
                INSERT INTO chunks_fts(rowid, text) VALUES (new.id, new.text);
                END""",
            """CREATE TABLE messages(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                notebookId INTEGER NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                cites TEXT,
                createdAt INTEGER NOT NULL)""",
            """CREATE TABLE notes(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                notebookId INTEGER NOT NULL,
                pinned INTEGER NOT NULL DEFAULT 0,
                text TEXT NOT NULL,
                createdAt INTEGER NOT NULL)""",
            """CREATE TABLE artifacts(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                notebookId INTEGER NOT NULL,
                kind TEXT NOT NULL,
                title TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'pending',
                payload TEXT,
                audioDir TEXT,
                error TEXT,
                createdAt INTEGER NOT NULL)""",
            """CREATE TABLE models(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                slot TEXT NOT NULL,
                repoId TEXT NOT NULL,
                fileName TEXT NOT NULL,
                filePath TEXT NOT NULL,
                sizeBytes INTEGER NOT NULL DEFAULT 0,
                sha256 TEXT,
                verified INTEGER NOT NULL DEFAULT 0,
                addedAt INTEGER NOT NULL)""",
            // Ingest staging: WorkManager Data is capped at 10 KB, so large
            // payloads (pasted text, file uris) wait here until the worker runs.
            """CREATE TABLE pending_ingest(
                sourceId INTEGER PRIMARY KEY,
                uri TEXT,
                url TEXT,
                text TEXT)""",
            "CREATE INDEX idx_chunks_source ON chunks(sourceId)",
            "CREATE INDEX idx_chunks_notebook ON chunks(notebookId)",
            "CREATE INDEX idx_messages_notebook ON messages(notebookId)",
            "CREATE INDEX idx_artifacts_notebook ON artifacts(notebookId, kind)",
            "CREATE INDEX idx_notes_notebook ON notes(notebookId)",
        )

        fun createSchema(conn: SQLiteConnection) {
            conn.execSQL("BEGIN IMMEDIATE")
            try {
                DDL.forEach { conn.execSQL(it) }
                conn.execSQL("COMMIT")
            } catch (t: Throwable) {
                conn.execSQL("ROLLBACK")
                throw t
            }
        }

        private fun migrate(conn: SQLiteConnection) {
            val version = longQuery(conn, "PRAGMA user_version").toInt()
            when {
                version == 0 -> {
                    // Fresh file — or a pre-release install whose schema create
                    // failed inside the old transaction and rolled back without
                    // ever setting user_version. Both rebuild cleanly from here.
                    if (!tableExists(conn, "notebooks")) createSchema(conn)
                    conn.execSQL("PRAGMA user_version = $SCHEMA_VERSION")
                }
                version < SCHEMA_VERSION -> error("schema downgrade is not supported")
                // version == SCHEMA_VERSION — nothing to do.
            }
        }

        private fun tableExists(conn: SQLiteConnection, name: String): Boolean =
            longQuery(
                conn,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
                name,
            ) > 0

        internal fun longQuery(conn: SQLiteConnection, sql: String, vararg args: Any?): Long =
            conn.prepare(sql).use { s ->
                args.forEachIndexed { i, a -> bind(s, i + 1, a) }
                check(s.step()) { "query returned no row: $sql" }
                s.getLong(0)
            }

        /** Type-dispatched parameter binding for the loosely typed repo call sites. */
        internal fun bind(s: SQLiteStatement, index: Int, value: Any?) {
            when (value) {
                null -> s.bindNull(index)
                is Long -> s.bindLong(index, value)
                is Int -> s.bindLong(index, value.toLong())
                is Boolean -> s.bindLong(index, if (value) 1L else 0L)
                is String -> s.bindText(index, value)
                is ByteArray -> s.bindBlob(index, value)
                is Double -> s.bindDouble(index, value)
                is Float -> s.bindDouble(index, value.toDouble())
                else -> throw IllegalArgumentException("unsupported bind type: ${value::class.java}")
            }
        }
    }
}

/**
 * Minimal façade over the androidx.sqlite driver that keeps the repo layer close
 * to the old framework API (insert / update / execSQL / rawQuery / transactions),
 * so the whole layer is auditable in one place.
 */
class SqlDb(private val conn: SQLiteConnection) {

    fun execSQL(sql: String, bindArgs: Array<out Any?>? = null) {
        conn.prepare(sql).use { s ->
            bindArgs?.forEachIndexed { i, a -> Db.bind(s, i + 1, a) }
            s.step()
        }
    }

    /** Streams the prepared statement — close the cursor when done (use { }). */
    fun rawQuery(sql: String, args: Array<out Any?>? = null): StmtCursor {
        val stmt = conn.prepare(sql)
        try {
            args?.forEachIndexed { i, a -> Db.bind(stmt, i + 1, a) }
            return StmtCursor(stmt)
        } catch (t: Throwable) {
            stmt.close()
            throw t
        }
    }

    /**
     * INSERT with `RETURNING id` so the new row id is read atomically in the
     * same statement — no last_insert_rowid() race on the shared connection.
     */
    fun insert(table: String, nullColumnHack: String?, values: ContentValues): Long {
        val entries = values.valueSet().toList()
        val cols = entries.joinToString(", ") { "\"${it.key}\"" }
        val marks = entries.joinToString(", ") { "?" }
        val sql = if (entries.isEmpty()) {
            // Matches the framework contract: an empty values map needs the
            // nullColumnHack to form a valid statement; DEFAULT VALUES suffices.
            "INSERT INTO \"$table\" DEFAULT VALUES"
        } else {
            "INSERT INTO \"$table\" ($cols) VALUES ($marks) RETURNING id"
        }
        conn.prepare(sql).use { s ->
            entries.forEachIndexed { i, e -> Db.bind(s, i + 1, e.value) }
            check(s.step()) { "INSERT produced no row" }
            return s.getLong(0)
        }
    }

    fun update(
        table: String,
        values: ContentValues,
        whereClause: String,
        whereArgs: Array<out Any?>?,
    ): Int {
        val entries = values.valueSet().toList()
        if (entries.isEmpty()) return 0
        val sets = entries.joinToString(", ") { "\"${it.key}\" = ?" }
        conn.prepare("UPDATE \"$table\" SET $sets WHERE $whereClause").use { s ->
            entries.forEachIndexed { i, e -> Db.bind(s, i + 1, e.value) }
            whereArgs?.forEachIndexed { i, a -> Db.bind(s, entries.size + i + 1, a) }
            s.step()
        }
        return Db.longQuery(conn, "SELECT changes()").toInt()
    }

    // Transactions over the single shared (serialized) connection. All call
    // sites are top-level multi-statement deletes — nesting would be a bug.
    private var txSuccess = false

    fun beginTransaction() {
        conn.execSQL("BEGIN IMMEDIATE")
        txSuccess = false
    }

    fun setTransactionSuccessful() {
        txSuccess = true
    }

    fun endTransaction() {
        conn.execSQL(if (txSuccess) "COMMIT" else "ROLLBACK")
        txSuccess = false
    }
}

/**
 * Cursor-style wrapper over a stepping [SQLiteStatement] (no row buffering —
 * memory stays flat for the 30k-chunk embedding scan).
 */
class StmtCursor(private val stmt: SQLiteStatement) : AutoCloseable {

    private var atRow = false

    fun moveToFirst(): Boolean {
        atRow = stmt.step()
        return atRow
    }

    fun moveToNext(): Boolean {
        atRow = stmt.step()
        return atRow
    }

    fun getLong(index: Int): Long = stmt.getLong(index)
    fun getInt(index: Int): Int = stmt.getLong(index).toInt()
    fun getDouble(index: Int): Double = stmt.getDouble(index)
    fun getFloat(index: Int): Float = stmt.getDouble(index).toFloat()
    fun getString(index: Int): String = stmt.getText(index)
    fun getBlob(index: Int): ByteArray = stmt.getBlob(index)
    fun isNull(index: Int): Boolean = stmt.isNull(index)

    override fun close() {
        stmt.close()
    }
}
