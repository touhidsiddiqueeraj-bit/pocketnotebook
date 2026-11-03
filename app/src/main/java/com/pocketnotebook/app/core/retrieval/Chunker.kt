package com.pocketnotebook.app.core.retrieval

/**
 * Paragraph-aware chunker (~400 tokens ≈ 1600 chars) with ~15% overlap.
 * Provenance (ordinal) is preserved for citation jumps.
 */
object Chunker {

    data class Chunk(val ordinal: Int, val text: String)

    fun chunk(text: String, target: Int = 1600, overlap: Int = 240): List<Chunk> {
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        val paragraphs = normalized.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) {
            val trimmed = normalized.trim()
            return if (trimmed.isEmpty()) emptyList() else listOf(Chunk(0, trimmed))
        }

        val out = ArrayList<Chunk>()
        var buffer = StringBuilder()
        var ordinal = 0

        for (p in paragraphs) {
            if (buffer.isNotEmpty() && buffer.length + p.length > target) {
                out += Chunk(ordinal++, buffer.toString().trim())
                val tail = if (buffer.length > overlap) {
                    buffer.substring(buffer.length - overlap)
                } else buffer.toString()
                buffer = StringBuilder(tail.trimStart())
            }
            if (buffer.isNotEmpty()) buffer.append("\n\n")
            buffer.append(p)
        }
        if (buffer.isNotBlank()) out += Chunk(ordinal++, buffer.toString().trim())

        // Hard split for pathological single paragraphs longer than the target.
        val result = ArrayList<Chunk>(out.size)
        var idx = 0
        for (c in out) {
            if (c.text.length <= target * 2) {
                result += Chunk(idx++, c.text)
            } else {
                var from = 0
                while (from < c.text.length) {
                    val to = minOf(from + target, c.text.length)
                    result += Chunk(idx++, c.text.substring(from, to).trim())
                    from = if (to == c.text.length) to else to - overlap
                }
            }
        }
        return result
    }

    fun wordCount(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
}
