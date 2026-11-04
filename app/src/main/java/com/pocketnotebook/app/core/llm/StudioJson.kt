package com.pocketnotebook.app.core.llm

/**
 * Lenient JSON handling for grammar-guided Studio output.
 *
 * GBNF-constrained decoding produces well-formed JSON *as it goes*, but when
 * generation stops on the token/context limit the text ends mid-JSON — and a
 * 1.7B model asked for "14 cards" or "26 segments" pads its way straight past
 * the cap. alpha.9 turned every such truncation into "Failed · tap to retry"
 * (most visibly Deep Dive and Flashcards: exactly the two cards whose
 * demanded output exceeded their max_tokens budget).
 *
 * Salvage: walk the text with a string/escape-aware scanner, remember the
 * position after every COMPLETE value together with the closers still open
 * there, then try those cuts newest-first until one parses. A truncated
 * 26-segment script becomes a 20-segment artifact instead of nothing.
 */
object StudioJson {

    /** Max number of candidate cuts kept (newest cuts matter most). */
    private const val MAX_CUTS = 256

    /**
     * Parse the outermost JSON object in [raw], tolerating truncation.
     * [parse] must THROW on invalid input (kotlinx decoders do).
     */
    fun <T> parseLenient(raw: String, parse: (String) -> T): T {
        val text = Thinking.strip(raw)
        val first = text.indexOf('{')
        if (first < 0) throw IllegalStateException("no JSON object in model output")

        // Fast path: the output is complete.
        val last = text.lastIndexOf('}')
        if (last > first) {
            val body = text.substring(first, last + 1)
            try {
                return parse(body)
            } catch (_: Throwable) {
                // Not well-formed after all — fall through to salvage.
            }
        }

        // Salvage path: cut back to each complete value, newest first.
        var truncated = false
        for (cand in cuts(text, first).asReversed()) {
            truncated = true
            try {
                return parse(cand)
            } catch (_: Throwable) {
                // try an earlier cut
            }
        }
        throw IllegalStateException(
            if (last <= first || !truncated)
                "model output truncated before any complete JSON element"
            else
                "model output truncated and no salvage cut parsed"
        )
    }

    /**
     * All candidate prefixes after a complete value, each closed with the
     * brackets still open at that point (trailing comma dropped). Scanned
     * only outside strings; string escapes handled.
     */
    private fun cuts(text: String, first: Int): List<String> {
        val out = ArrayList<String>(64)
        val stack = ArrayList<Char>() // expected closers, innermost last
        var inString = false
        var escape = false
        var i = first
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> {
                        inString = false
                        if (stack.isNotEmpty() && out.size < MAX_CUTS)
                            out.add(closeAt(text, first, i + 1, stack))
                    }
                }
            } else when (c) {
                '"' -> inString = true
                '{' -> stack.add('}')
                '[' -> stack.add(']')
                '}', ']' -> {
                    if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    if (stack.isNotEmpty() && out.size < MAX_CUTS)
                        out.add(closeAt(text, first, i + 1, stack))
                }
            }
            i++
        }
        return out
    }

    /** text[first..end) minus a trailing comma, plus the closers still open. */
    private fun closeAt(text: String, first: Int, end: Int, stack: List<Char>): String {
        var s = text.substring(first, end).trimEnd()
        if (s.endsWith(',')) s = s.dropLast(1)
        val sb = StringBuilder(s)
        for (j in stack.indices.reversed()) sb.append(stack[j])
        return sb.toString()
    }

    /**
     * Trim [text] down to [budget] tokens (counted by [counter], e.g. the
     * loaded model's tokenizer). The Studio digest is capped in CHARACTERS
     * today (12000) — on a 4096-token context that alone can exceed the
     * whole context once the artifact budget is reserved, which fails the
     * prefill outright. Estimating by token density and then shaving until
     * it fits keeps prompt + generation inside n_ctx.
     */
    suspend fun fitToTokens(text: String, budget: Int, counter: suspend (String) -> Int): String {
        if (budget <= 0 || text.isEmpty()) return text
        val n = counter(text)
        if (n <= 0 || n <= budget) return text
        // First cut: estimate by measured token density.
        var out = text.take(((text.length * budget) / n).coerceAtLeast(64))
        var guard = 8
        while (guard-- > 0) {
            val t = counter(out)
            if (t <= 0 || t <= budget) break
            val drop = (out.length / 8 + 64).coerceAtMost(out.length)
            if (drop >= out.length) return ""
            out = out.dropLast(drop)
        }
        return out
    }
}
