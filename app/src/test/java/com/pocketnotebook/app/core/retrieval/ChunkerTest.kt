package com.pocketnotebook.app.core.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkerTest {

    @Test
    fun `empty text yields no chunks`() {
        assertTrue(Chunker.chunk("").isEmpty())
        assertTrue(Chunker.chunk("   \n\n  ").isEmpty())
    }

    @Test
    fun `single short paragraph becomes one chunk`() {
        val out = Chunker.chunk("Hello world. This is a single paragraph.")
        assertEquals(1, out.size)
        assertEquals(0, out[0].ordinal)
    }

    @Test
    fun `many paragraphs respect target and overlap`() {
        val paragraphs = (1..200).map { "Paragraph $it with some filler content to add length. " }
        val text = paragraphs.joinToString("\n\n")
        val out = Chunker.chunk(text, target = 800, overlap = 120)
        assertTrue("expected multiple chunks, got ${out.size}", out.size > 3)
        // ordinals are contiguous
        assertEquals(out.indices.toList(), out.map { it.ordinal })
        // overlap: second chunk shares a tail with the first
        val first = out[0].text
        val second = out[1].text
        assertTrue(second.length > 0)
        assertTrue(first.length <= 1600)
    }

    @Test
    fun `hard-split covers pathological long paragraph without loss`() {
        val one = "x".repeat(5000) + " " + "y".repeat(100)
        val out = Chunker.chunk(one, target = 400, overlap = 60)
        val total = out.joinToString("") { it.text }
        assertTrue(out.size >= 2)
        assertTrue(total.contains("yyyyy"))
    }
}

class RrfTest {

    @Test
    fun `fusion ranks ids present in both lists first`() {
        val a = listOf(10L, 20L, 30L)
        val b = listOf(30L, 40L, 10L)
        val fused = Rrf.fuse(listOf(a, b))
        assertEquals(setOf(30L, 10L, 40L, 20L), fused.map { it.first }.toSet())
        // 30 and 10 appear in both → strictly higher than 20/40
        val score = fused.toMap()
        assertTrue(score[30]!! > score[40]!!)
        assertTrue(score[10]!! > score[20]!!)
    }

    @Test
    fun `empty lists fuse to empty`() {
        assertTrue(Rrf.fuse(emptyList()).isEmpty())
        assertTrue(Rrf.fuse(listOf(emptyList(), emptyList())).isEmpty())
    }

    @Test
    fun `cosine basics`() {
        assertEquals(1f, Vectors.cosine(floatArrayOf(1f, 0f), floatArrayOf(2f, 0f)), 1e-5f)
        assertEquals(0f, Vectors.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-5f)
        assertEquals(0f, Vectors.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)), 1e-5f)
    }
}
