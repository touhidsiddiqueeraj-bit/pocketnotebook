package com.pocketnotebook.app.core.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Hand-rolled SQLite instead of Room: we need FTS5 (Room only wraps FTS4) with
 * external-content sync triggers and raw bm25() ranking queries.
 */
class Db(context: Context) : SQLiteOpenHelper(context, "pocketnotebook.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) = createSchema(db)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 — future migrations go here.
    }

    companion object {
        private val DDL = listOf(
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

        fun createSchema(db: SQLiteDatabase) {
            db.beginTransaction()
            try {
                DDL.forEach { db.execSQL(it) }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }
}
