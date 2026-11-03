package com.pocketnotebook.app.core.llm

import com.pocketnotebook.app.core.study.Sm2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CitationRepairTest {

    @Test
    fun `valid citations are kept in order`() {
        val text = "The battery degrades faster at high C-rates [2] than at low rates [1]."
        assertEquals(listOf(2, 1), CitationRepair.usedCitations(text, 4))
    }

    @Test
    fun `out-of-range and malformed refs are dropped`() {
        val text = "A [9] and [0] and [99] and a plain bracket [ hello ] with valid [3]."
        val cleaned = CitationRepair.stripInvalid(text, 4)
        assertTrue("kept [3]", cleaned.contains("[3]"))
        assertTrue("dropped [9]", !cleaned.contains("[9]"))
        assertTrue("dropped [0]", !cleaned.contains("[0]"))
        assertTrue("dropped [99]", !cleaned.contains("[99]"))
        assertEquals(listOf(3), CitationRepair.usedCitations(cleaned, 4))
    }

    @Test
    fun `footer appended when model cited nothing`() {
        val text = "The answer with no refs."
        val out = CitationRepair.ensureFooter(text, 3, retrievalPresent = true)
        assertTrue(out.contains("Sources: [1] [2] [3]"))
        assertTrue(out.startsWith("The answer"))
    }

    @Test
    fun `no footer when citations exist or retrieval empty`() {
        assertEquals(
            "Answer [1].",
            CitationRepair.ensureFooter("Answer [1].", 3, retrievalPresent = true),
        )
        assertEquals(
            "Plain answer.",
            CitationRepair.ensureFooter("Plain answer.", 3, retrievalPresent = false),
        )
    }

    @Test
    fun `two digit refs parse`() {
        val text = "Big claim [12] here."
        assertEquals(listOf(12), CitationRepair.usedCitations(text, 12))
    }
}

class Sm2Test {

    private val now = 1_700_000_000_000L

    @Test
    fun `again resets schedule and penalizes ease`() {
        val card = Sm2.Card(ease = 2.5f, intervalDays = 6f, reps = 3)
        val out = Sm2.review(card, 0, now)
        assertEquals(0f, out.intervalDays)
        assertEquals(0, out.reps)
        assertEquals(1, out.lapses)
        assertEquals(2.3f, out.ease, 1e-4f)
    }

    @Test
    fun `first good review schedules one day out`() {
        val out = Sm2.review(Sm2.Card(), 2, now)
        assertEquals(1f, out.intervalDays)
        assertEquals(1, out.reps)
        assertEquals(2.5f, out.ease, 1e-4f)
        assertEquals(now + 86_400_000L, Sm2.dueAtMs(out, now))
    }

    @Test
    fun `easy grows faster than good`() {
        var good = Sm2.Card()
        var easy = Sm2.Card()
        repeat(4) {
            good = Sm2.review(good, 2, now)
            easy = Sm2.review(easy, 3, now)
        }
        assertTrue(easy.intervalDays > good.intervalDays)
        assertTrue(easy.ease > good.ease)
    }

    @Test
    fun `ease never drops below floor`() {
        var card = Sm2.Card(ease = 1.4f)
        repeat(5) { card = Sm2.review(card, 0, now) }
        assertTrue(card.ease >= 1.3f)
    }
}
