package com.pocketnotebook.app.core.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Schema + FTS5 regression gate (JVM).
 *
 * v0.1.0-alpha.2 failed at first launch with "no such module: fts5" because
 * Android's system SQLite is compiled without FTS5 on every vendor (including
 * Android 15/16). The app now bundles its own SQLite (androidx.sqlite:sqlite-
 * bundled 2.7.1 = SQLite 3.50.1, FTS5 enabled, 16 KB-page aligned); this test
 * replays the REAL schema from [Db.DDL] against an FTS5-enabled engine on the
 * JVM, so a schema that depends on a missing module can never merge again.
 *
 * The engine here (xerial sqlite-jdbc 3.54.1) is a desktop build of the same
 * SQLite core the app bundles — same SQL dialect as the on-device engine.
 */
class DbSchemaFts5Test {

    private fun openDb(): Connection =
        DriverManager.getConnection("jdbc:sqlite::memory:").apply {
            autoCommit = true
            createStatement().use { s ->
                Db.DDL.forEach { ddl -> s.execute(ddl) }
            }
        }

    /** The failure mode of alpha.2: any of these DDL statements throwing "no such module: fts5". */
    @Test
    fun schemaRequiresFts5_andCreatesCleanly() {
        openDb().use { db ->
            createStatement().use { s ->
                s.executeQuery(
                    "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='chunks_fts'",
                ).use { rs ->
                    rs.next()
                    assertEquals("chunks_fts (FTS5) must exist after DDL", 1, rs.getInt(1))
                }
                // The fts5 module must be loaded and queryable — this is the line
                // that failed on every Android device for v0.1.0-alpha.2.
                s.executeQuery("SELECT count(*) FROM chunks_fts").use { rs ->
                    rs.next()
                    assertEquals(0, rs.getInt(1))
                }
            }
        }
    }

    /** Insert triggers must index chunks, and bm25() must rank matches — the Retriever path. */
    @Test
    fun triggersIndexChunks_andBm25RanksMatches() {
        openDb().use { db ->
            createStatement().use { s ->
                s.execute(
                    "INSERT INTO notebooks (title, createdAt, updatedAt) VALUES ('Research', 1, 1)",
                )
                s.execute(
                    "INSERT INTO sources (notebookId, type, title, status, addedAt) " +
                        "VALUES (1, 'pdf', 'Paper A', 'ready', 2)",
                )
                val texts = listOf(
                    "Liquid glass user interface design language for mobile applications",
                    "Kernel page size alignment requirements for native libraries",
                    "Retrieval augmented generation grounds answers in source material",
                    "Bananas are a tropical fruit rich in potassium",
                )
                texts.forEachIndexed { i, t ->
                    s.execute(
                        "INSERT INTO chunks (sourceId, notebookId, ordinal, text) " +
                            "VALUES (1, 1, $i, '$t')",
                    )
                }

                // Insert trigger -> indexed.
                s.executeQuery("SELECT count(*) FROM chunks_fts").use { rs ->
                    rs.next()
                    assertEquals(4, rs.getInt(1))
                }

                // The exact ranking query shape the Retriever runs.
                s.executeQuery(
                    """SELECT rowid, bm25(chunks_fts) FROM chunks_fts
                       WHERE chunks_fts MATCH ? AND rowid IN
                         (SELECT id FROM chunks WHERE notebookId = 1)
                       ORDER BY 2 LIMIT 40""",
                ).use { rs ->
                    assertTrue("bm25 MATCH must hit", rs.next())
                    assertEquals(3L, rs.getLong(1)) // the "retrieval augmented generation" chunk
                }
            }
        }
    }

    /** Update/delete triggers must keep the external-content FTS index in sync. */
    @Test
    fun updateAndDeleteTriggersKeepFtsInSync() {
        openDb().use { db ->
            createStatement().use { s ->
                s.execute(
                    "INSERT INTO chunks (sourceId, notebookId, ordinal, text) " +
                        "VALUES (1, 1, 0, 'Bananas are a tropical fruit rich in potassium')",
                )
                s.execute(
                    "INSERT INTO chunks (sourceId, notebookId, ordinal, text) " +
                        "VALUES (1, 1, 1, 'Kernel page size alignment requirements')",
                )
                // Re-index the edited row.
                s.execute("UPDATE chunks SET text = 'Bananas and page size alignment' WHERE id = 1")
                s.executeQuery(
                    "SELECT rowid, bm25(chunks_fts) FROM chunks_fts " +
                        "WHERE chunks_fts MATCH 'page size' ORDER BY 2 LIMIT 1",
                ).use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1L, rs.getLong(1))
                }
                // Delete removes it from the index.
                s.execute("DELETE FROM chunks WHERE id = 1")
                s.executeQuery(
                    "SELECT rowid FROM chunks_fts WHERE chunks_fts MATCH 'bananas'",
                ).use { rs ->
                    assertTrue("deleted chunk must leave the FTS index", !rs.next())
                }
            }
        }
    }

    /** SqlDb.insert relies on INSERT ... RETURNING id (atomic rowid, SQLite >= 3.35). */
    @Test
    fun insertReturningYieldsId() {
        openDb().use { db ->
            createStatement().use { s ->
                val rs = s.executeQuery(
                    "INSERT INTO notebooks (title, createdAt, updatedAt) " +
                        "VALUES ('Notebook', 1, 1) RETURNING id",
                )
                assertTrue(rs.next())
                assertEquals(1L, rs.getLong(1))
                assertTrue(!rs.next())
            }
        }
    }

    /** The version bootstrap the Db init path performs. */
    @Test
    fun userVersionRoundTrip() {
        openDb().use { db ->
            createStatement().use { s ->
                s.execute("PRAGMA user_version = 1")
                s.executeQuery("PRAGMA user_version").use { rs ->
                    rs.next()
                    assertEquals(1, rs.getInt(1))
                }
            }
        }
    }
}
