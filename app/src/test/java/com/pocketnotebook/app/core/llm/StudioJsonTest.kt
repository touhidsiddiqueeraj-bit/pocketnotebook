package com.pocketnotebook.app.core.llm

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * StudioJson.parseLenient — the salvage parser that turns truncated (token/
 * context-capped) grammar output into a smaller artifact instead of a
 * "Failed · tap to retry" card. Regression suite for the alpha.9 Deep Dive +
 * Flashcards failures.
 */
class StudioJsonTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun flashcards(count: Int): String {
        val sb = StringBuilder("{\"cards\":[")
        for (i in 0 until count) {
            if (i > 0) sb.append(',')
            sb.append("{\"front\":\"Question number $i?\",\"back\":\"Answer number $i, briefly.\"}")
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun overview(segments: Int): String {
        val sb = StringBuilder("{\"title\":\"The Paper\",\"segments\":[")
        for (i in 0 until segments) {
            if (i > 0) sb.append(',')
            sb.append("{\"speaker\":\"")
            sb.append(if (i % 2 == 0) "A" else "B")
            sb.append("\",\"text\":\"Segment $i says something interesting.\"}")
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun parseFlashcards(s: String) =
        json.decodeFromString(StudioService.FlashcardSet.serializer(), s)

    private fun parseOverview(s: String) =
        json.decodeFromString(StudioService.OverviewScript.serializer(), s)

    @Test
    fun `complete flashcards parse untouched`() {
        val set = StudioJson.parseLenient(flashcards(9)) { parseFlashcards(it) }
        assertEquals(9, set.cards.size)
    }

    @Test
    fun `flashcards truncated mid-card salvage the complete cards`() {
        // A 14-card generation cut at the token cap mid card #5: the five
        // complete cards (indices 0-4) must survive, the half-written fifth
        // (index 5) must be dropped.
        val full = flashcards(14)
        val raw = full.substring(0, full.indexOf("{\"front\":\"Question number 5") + 24)
        val set = StudioJson.parseLenient(raw) { parseFlashcards(it) }
        assertEquals(5, set.cards.size)
        assertEquals("Question number 4?", set.cards[4].front)
    }

    @Test
    fun `deep dive truncated mid-string salvages the complete segments`() {
        val full = overview(26)
        val cutAt = full.indexOf("Segment 20") + 10
        val raw = full.substring(0, cutAt)
        val script = StudioJson.parseLenient(raw) { parseOverview(it) }
        // Segments 0..19 survive (20 complete segments), the partial 21st drops.
        assertEquals(20, script.segments.size)
        assertEquals("The Paper", script.title)
    }

    @Test
    fun `trailing comma after the last complete element is handled`() {
        val raw = flashcards(3).dropLast(2) + "," // {"cards":[{c1},{c2},{c3},
        val set = StudioJson.parseLenient(raw) { parseFlashcards(it) }
        assertEquals(3, set.cards.size)
    }

    @Test
    fun `padded non-json text around the object is ignored`() {
        val raw = "Here are the flashcards:\n" + flashcards(2) + "\nDone."
        val set = StudioJson.parseLenient(raw) { parseFlashcards(it) }
        assertEquals(2, set.cards.size)
    }

    @Test
    fun `think-block-prefixed output still parses`() {
        val open = "\u003Cthink\u003E"
        val close = "\u003C/think\u003E"
        val raw = open + "reasoning here" + close + flashcards(2)
        val set = StudioJson.parseLenient(raw) { parseFlashcards(it) }
        assertEquals(2, set.cards.size)
    }

    @Test
    fun `output with no json at all throws a clear error`() {
        try {
            StudioJson.parseLenient("I could not answer that.") { parseFlashcards(it) }
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("no JSON object"))
        }
    }

    @Test
    fun `truncation before any complete element throws`() {
        val raw = "{\"cards\":[{\"front\":\"only a partial"
        try {
            StudioJson.parseLenient(raw) { parseFlashcards(it) }
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("truncated"))
        }
    }

    @Test
    fun `quiz sanitize drops malformed questions and keeps the good ones`() {
        val set = StudioService.QuizSet().apply {
            questions.add(StudioService.QuizQuestion("Good q", listOf("a", "b", "c", "d"), 2))
            questions.add(StudioService.QuizQuestion("Bad options", listOf("a", "b"), 0))
            questions.add(StudioService.QuizQuestion("Bad answer idx", listOf("a", "b", "c", "d"), 7))
            questions.add(StudioService.QuizQuestion("", listOf("a", "b", "c", "d"), 0))
        }
        val clean = StudioService.sanitizeQuiz(set)
        assertEquals(1, clean.questions.size)
        assertEquals("Good q", clean.questions[0].question)
    }

    @Test
    fun `fitToTokens keeps text that already fits`() = runBlocking {
        val text = "four score and seven years ago"
        assertEquals(text, StudioJson.fitToTokens(text, 100) { it.length / 4 })
    }

    @Test
    fun `fitToTokens trims over-budget text down`() = runBlocking {
        val text = buildString { repeat(100) { append("token$it ") } } // 800 tokens at 4 chars/token
        val out = StudioJson.fitToTokens(text, 100) { it.length / 4 }
        assertTrue("trimmed: ${out.length} chars", out.length < text.length)
        assertTrue("should keep ~100 tokens = ~400 chars, got ${out.length}", out.length <= 460)
        assertTrue("must keep the head of the text", out.startsWith("token0 "))
    }
}
