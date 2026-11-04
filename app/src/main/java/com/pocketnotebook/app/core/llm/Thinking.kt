package com.pocketnotebook.app.core.llm

/**
 * Strips residual reasoning-model artifacts from model output.
 *
 * The native engine already hides chain-of-thought (empty think block in the
 * prompt + token-level suppression — see pocket_jni.cpp). This is the second
 * line of defense for anything that still slips through as literal text:
 * think blocks, answer tags, stray marker fragments.
 *
 * NOTE: tag names are written with unicode escapes so the source bytes can
 * never be corrupted by tooling that rewrites markup-looking literals.
 */
object Thinking {

    private const val OPEN = "\u003Cthink\u003E"
    private const val CLOSE = "\u003C/think\u003E"

    private val THINK_BLOCK = Regex(Regex.escape(OPEN) + "(?s:.)*?" + Regex.escape(CLOSE))
    private val THINK_OPEN_LOOSE = Regex(Regex.escape(OPEN))
    private val ANSWER_TAG = Regex("\u003C/?answer\u003E")
    private val STRAY_GT = Regex("^\\s*\u003E+\\s*")

    /** Remove think blocks, answer tags and leading stray '>' fragments. */
    fun strip(text: String): String {
        var out = THINK_BLOCK.replace(text, "")
        // Unterminated think block (streaming cut mid-reasoning): drop the tail.
        THINK_OPEN_LOOSE.find(out)?.let { out = out.substring(0, it.range.first) }
        out = ANSWER_TAG.replace(out, "")
        out = STRAY_GT.replace(out, "")
        return out.trim()
    }
}
