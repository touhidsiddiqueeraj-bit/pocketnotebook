package com.pocketnotebook.app.core.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkingTest {

    private fun open() = "\u003Cthink\u003E"
    private fun close() = "\u003C/think\u003E"

    @Test
    fun `strips a full think block and keeps the answer`() {
        val raw = open() + "I need to reason about this carefully." + close() +
            "\nParis is the capital of France."
        assertEquals("Paris is the capital of France.", Thinking.strip(raw))
    }

    @Test
    fun `strips an unterminated think block`() {
        val raw = open() + "The user asks about black holes, so I should"
        assertEquals("", Thinking.strip(raw))
    }

    @Test
    fun `keeps plain answers untouched`() {
        assertEquals("Hello world. [1]", Thinking.strip("Hello world. [1]"))
    }

    @Test
    fun `removes answer tags and stray leading gt`() {
        assertEquals("The answer.", Thinking.strip("\u003E\nThe answer.\u003C/answer\u003E"))
        assertEquals("The answer.", Thinking.strip("\u003Canswer\u003EThe answer.\u003C/answer\u003E"))
    }

    @Test
    fun `strips multiple think blocks`() {
        val raw = open() + "first" + close() + "A. " + open() + "second" + close() + "B."
        assertEquals("A. B.", Thinking.strip(raw))
    }
}
