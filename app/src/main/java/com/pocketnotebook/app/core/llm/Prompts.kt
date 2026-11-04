package com.pocketnotebook.app.core.llm

/**
 * All prompts live here so quality can be tuned in one place.
 * Tone follows NotebookLM: grounded, concise, study-oriented.
 */
object Prompts {

    fun chatSystem(maxSnippets: Int) = """You are PocketNotebook, an offline research assistant. You answer strictly from the numbered source snippets provided in the user's message.

Rules:
- Cite snippets inline like [1] or [2] right after the sentence they support. Only cite numbers between [1] and [$maxSnippets] that actually exist.
- If the snippets do not contain the answer, say so plainly and suggest what to add to the notebook.
- Be accurate and concise. Use short paragraphs. No padding, no filler.
- Never invent facts, numbers or quotes that are not in the snippets."""

    fun chatUser(question: String, context: String, hasContext: Boolean): String =
        if (hasContext) {
            "$question\n\nSource snippets:\n$context"
        } else {
            "$question\n\n(No sources in this notebook yet — answer from general knowledge and clearly say the answer is not grounded in notebook sources.)"
        }

    fun summaryForDigest(sourceTitle: String, text: String) = """Summarize the following document for a research digest. 150-220 words, dense, keep key facts, numbers and named entities. No preamble, no headings.

Document "$sourceTitle":
${text.take(9000)}"""

    fun report(kind: String, digest: String, sourceCount: Int) =
        when (kind) {
            "study_guide" -> """Create a study guide in Markdown for the notebook summarized below. Structure:
## Core Concepts (bulleted list with 1-2 sentence explanations)
## Key Terms (term — definition pairs)
## Review Questions (8 numbered questions, no answers)
## Suggested Study Order (numbered steps)
Ground everything in the digest. Do not invent content.

Notebook digest ($sourceCount sources):
$digest"""
            "briefing" -> """Write an executive briefing document in Markdown from the notebook digest below. Structure:
## One-Paragraph Summary
## Key Takeaways (bulleted)
## Notable Details & Numbers
## Open Questions
Ground everything in the digest. Do not invent content.

Notebook digest ($sourceCount sources):
$digest"""
            "faq" -> """Write an FAQ in Markdown from the notebook digest below. 8-10 Q&A pairs. Format each as:
**Q: question text**
A: answer text (2-4 sentences).
Cover the most important things a reader would ask. Ground everything in the digest. Do not invent content.

Notebook digest ($sourceCount sources):
$digest"""
            "timeline" -> """Write a timeline in Markdown from the notebook digest below. Format:
## Timeline
- **Date or Phase label** — what happened (1-2 sentences)
If the material has no explicit dates, order events logically and use phase labels. Ground everything in the digest. Do not invent content.

Notebook digest ($sourceCount sources):
$digest"""
            else -> throw IllegalArgumentException("unknown report kind $kind")
        }

    fun mindMap(digest: String, sourceCount: Int) = """You are a mind-map builder. From the notebook digest below produce a topic tree as compact JSON.

Rules for the JSON:
- Root node has "title" and "children".
- Each child may have its own "children" (at most 3 levels total).
- Node titles: 2-6 words, no punctuation at the end, no duplicates of each other.
- 8 to 20 nodes total. Single line strings only — no line breaks inside titles.
- Output ONLY the JSON object, nothing else.

Notebook digest ($sourceCount sources):
$digest"""

    fun flashcards(digest: String, sourceCount: Int) = """Create study flashcards from the notebook digest below as compact JSON.

Rules for the JSON:
- 8 to 10 cards. Each has "front" (a short question or prompt) and "back" (the answer, 1-2 sentences).
- Fronts must be self-contained questions; backs grounded in the digest — do not invent.
- Single line strings only — no line breaks inside text.
- Be brief: 2 of your cards must come from the tail of the digest so it is fully covered.
- Output ONLY the JSON object, nothing else.

Notebook digest ($sourceCount sources):
$digest"""

    fun quiz(digest: String, sourceCount: Int) = """Create a multiple-choice quiz from the notebook digest below as compact JSON.

Rules for the JSON:
- 6 to 10 questions. Each has "question", exactly 4 "options", and "answer" — the 0-based index of the correct option.
- Exactly one correct option per question. Distractors plausible but wrong.
- Ground every question in the digest — do not invent.
- Single line strings only — no line breaks inside text.
- Output ONLY the JSON object, nothing else.

Notebook digest ($sourceCount sources):
$digest"""

    fun overviewScript(digest: String, sourceCount: Int, style: String): String {
        val hostSetup = if (style == "brief") {
            "- One host (speaker always \"A\"): a confident narrator delivering a tight 90-second audio summary."
        } else {
            "- Two hosts: A (curious interviewer) and B (knowledgeable explainer), natural back-and-forth."
        }
        return """Write an audio overview script from the notebook digest below, as compact JSON.

Rules for the JSON:
- Object with "title" (catchy, under 10 words) and "segments": an array of {\"speaker\":\"A\" or \"B\",\"text\":\"...\"}.
$hostSetup
- ${if (style == "brief") "5 to 8 segments" else "10 to 16 segments"}; each segment is 1-2 spoken sentences, single line, no special characters or emojis, no stage directions.
- Open with a hook, cover the key ideas and numbers from the digest, close with a takeaway. Do not invent facts.
- Keep language spoken-style and simple. Output ONLY the JSON object, nothing else.

Notebook digest ($sourceCount sources):
$digest"""
    }

    fun overviewTitlePrompt(digest: String) =
        "Give a short catchy title (under 8 words) for an audio overview of this material. Output only the title.\n\n$digest"
}
