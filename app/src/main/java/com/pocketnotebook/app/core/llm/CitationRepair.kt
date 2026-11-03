package com.pocketnotebook.app.core.llm

/**
 * Citation repair: model output is never trusted blindly.
 *  - [stripInvalid] drops [n] refs outside the snippet set (hallucinated cites).
 *  - [ensureFooter] appends a retrieval-derived footer when the model cited
 *    nothing even though snippets were provided.
 * Pure functions — unit-tested.
 */
object CitationRepair {

    private val CITE = Regex("\\[(\\d{1,2})]")

    /** Distinct valid snippet numbers, in order of first appearance. */
    fun usedCitations(text: String, max: Int): List<Int> {
        val seen = LinkedHashSet<Int>()
        for (m in CITE.findAll(text)) {
            val n = m.groupValues[1].toIntOrNull() ?: continue
            if (n in 1..max) seen += n
        }
        return seen.toList()
    }

    /** Drops every [n] that is not in 1..max. */
    fun stripInvalid(text: String, max: Int): String =
        CITE.replace(text) { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@replace ""
            if (n in 1..max) m.value else ""
        }

    /**
     * [retrievalPresent]: snippets were actually retrieved for this answer.
     * If the model forgot to cite, we append a sources footer instead of
     * letting the answer float without provenance.
     */
    fun ensureFooter(text: String, max: Int, retrievalPresent: Boolean): String {
        if (!retrievalPresent || max <= 0) return text
        if (usedCitations(text, max).isNotEmpty()) return text
        val refs = (1..max).joinToString(" ") { "[$it]" }
        return text.trimEnd() + "\n\nSources: " + refs
    }
}
