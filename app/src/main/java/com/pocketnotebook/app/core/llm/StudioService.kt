package com.pocketnotebook.app.core.llm

import com.pocketnotebook.app.core.db.ArtifactKind
import com.pocketnotebook.app.core.db.ArtifactRepo
import com.pocketnotebook.app.core.db.ArtifactStatus
import com.pocketnotebook.app.core.db.SourceRepo
import com.pocketnotebook.app.core.engine.LlmRuntime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * Studio generators — every structured artifact is GBNF-constrained.
 * Shared skeleton: map-reduce digest → prompt → (grammar) → parse → persist.
 */
class StudioService(
    private val sourceRepo: SourceRepo,
    private val artifactRepo: ArtifactRepo,
    private val runtime: LlmRuntime,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Instruction system prompt for every studio generation. */
    private val system =
        "You are a precise offline research assistant for study materials. " +
            "Follow the user's formatting instructions exactly. Ground every claim " +
            "in the provided material and never invent facts."

    /**
     * Extract the outermost JSON object from raw model output. Grammar-guided
     * generation should return pure JSON, but with the chat template applied
     * the model sometimes pads a word or a stray character — alpha.7 marked
     * every artifact failed on that.
     */
    internal fun extractJson(raw: String): String {
        val text = Thinking.strip(raw)
        val first = text.indexOf('{')
        val last = text.lastIndexOf('}')
        if (first < 0 || last <= first) throw IllegalStateException("no JSON object in model output")
        return text.substring(first, last + 1)
    }

    // ------------------------------------------------------------- payloads

    @Serializable
    data class MindMapNode(var title: String, var children: List<MindMapNode> = emptyList())

    @Serializable
    data class Flashcard(
        val front: String,
        val back: String,
        var ease: Float = 2.5f,
        var intervalDays: Float = 0f,
        var reps: Int = 0,
        var lapses: Int = 0,
        var dueAt: Long = 0,
    )

    @Serializable
    data class FlashcardSet(var title: String = "Flashcards", val cards: MutableList<Flashcard> = mutableListOf())

    @Serializable
    data class QuizQuestion(
        val question: String,
        val options: List<String>,
        val answer: Int,
    )

    @Serializable
    data class QuizSet(
        var title: String = "Quiz",
        val questions: MutableList<QuizQuestion> = mutableListOf(),
        var bestScore: Int? = null,
    )

    @Serializable
    data class OverviewSegment(val speaker: String, val text: String)

    @Serializable
    data class OverviewScript(
        var title: String = "Audio Overview",
        var style: String = "deepdive",
        val segments: MutableList<OverviewSegment> = mutableListOf(),
        var baked: Boolean = false,
    )

    // --------------------------------------------------------------- digest

    /**
     * Map-reduce notebook digest: per-source summaries (cached on the source
     * row) joined into one context block, capped in characters.
     */
    suspend fun digest(notebookId: Long): Pair<String, Int> {
        val sources = sourceRepo.listForNotebook(notebookId).filter { it.status == ArtifactStatus.READY }
        if (sources.isEmpty()) return "" to 0
        val parts = ArrayList<String>(sources.size)
        for (s in sources) {
            val cached = s.summary
            val summary = if (!cached.isNullOrBlank()) {
                cached
            } else {
                val text = sourceRepo.listChunkTexts(s.id).joinToString("\n\n").take(12000)
                val gen = Thinking.strip(
                    runtime.completeChat(
                        system = system,
                        user = Prompts.summaryForDigest(s.title, text),
                        temperature = 0.3f,
                        maxTokens = 400,
                    ),
                )
                if (gen.isNotBlank()) {
                    sourceRepo.setSummary(s.id, gen)
                    gen
                } else s.title
            }
            parts += "=== ${s.title} ===\n$summary"
        }
        val joined = parts.joinToString("\n\n")
        return joined.take(12000) to sources.size
    }

    // -------------------------------------------------------------- reports

    suspend fun generateReport(notebookId: Long, kind: String): Long {
        val artifactId = artifactRepo.create(notebookId, kind, titleFor(kind))
        artifactRepo.update(artifactId, status = ArtifactStatus.RUNNING)
        try {
            val (digestText, count) = digest(notebookId)
            if (count == 0) {
                artifactRepo.update(
                    artifactId, status = ArtifactStatus.FAILED,
                    error = "Add at least one ready source first.",
                )
                return artifactId
            }
            val md = Thinking.strip(
                runtime.completeChat(
                    system = system,
                    user = Prompts.report(kind, digestText, count),
                    temperature = 0.35f,
                    maxTokens = 1100,
                ),
            )
            if (md.isBlank()) throw IllegalStateException("model produced no output")
            artifactRepo.update(
                artifactId,
                status = ArtifactStatus.READY,
                payload = md,
            )
        } catch (t: Throwable) {
            artifactRepo.update(artifactId, status = ArtifactStatus.FAILED, error = t.message ?: "failed")
        }
        return artifactId
    }

    // -------------------------------------------------------------- mind map

    suspend fun generateMindMap(notebookId: Long): Long {
        val artifactId = artifactRepo.create(notebookId, ArtifactKind.MINDMAP, "Mind map")
        artifactRepo.update(artifactId, status = ArtifactStatus.RUNNING)
        try {
            val (digestText, count) = digest(notebookId)
            if (count == 0) {
                artifactRepo.update(
                    artifactId, status = ArtifactStatus.FAILED,
                    error = "Add at least one ready source first.",
                )
                return artifactId
            }
            val raw = runtime.completeChat(
                system = system,
                user = Prompts.mindMap(digestText, count),
                temperature = 0.4f,
                maxTokens = 800,
                grammar = Grammars.mindMap,
                grammarRoot = "root",
            )
            val tree = parseMindMap(extractJson(raw))
            artifactRepo.update(
                artifactId, status = ArtifactStatus.READY,
                payload = json.encodeToString(MindMapNode.serializer(), tree),
            )
        } catch (t: Throwable) {
            artifactRepo.update(artifactId, status = ArtifactStatus.FAILED, error = t.message ?: "failed")
        }
        return artifactId
    }

    internal fun parseMindMap(raw: String): MindMapNode =
        json.decodeFromString(MindMapNode.serializer(), extractJson(raw))

    // ------------------------------------------------------------- flashcards

    suspend fun generateFlashcards(notebookId: Long): Long {
        val artifactId = artifactRepo.create(notebookId, ArtifactKind.FLASHCARDS, "Flashcards")
        artifactRepo.update(artifactId, status = ArtifactStatus.RUNNING)
        try {
            val (digestText, count) = digest(notebookId)
            if (count == 0) {
                artifactRepo.update(
                    artifactId, status = ArtifactStatus.FAILED,
                    error = "Add at least one ready source first.",
                )
                return artifactId
            }
            val raw = runtime.completeChat(
                system = system,
                user = Prompts.flashcards(digestText, count),
                temperature = 0.4f,
                maxTokens = 1100,
                grammar = Grammars.flashcards,
                grammarRoot = "root",
            )
            val parsed = json.decodeFromString(FlashcardSet.serializer(), extractJson(raw))
            if (parsed.cards.isEmpty()) throw IllegalStateException("no cards generated")
            val now = System.currentTimeMillis()
            parsed.cards.forEach { it.dueAt = now }
            artifactRepo.update(
                artifactId, status = ArtifactStatus.READY,
                payload = json.encodeToString(FlashcardSet.serializer(), parsed),
            )
        } catch (t: Throwable) {
            artifactRepo.update(artifactId, status = ArtifactStatus.FAILED, error = t.message ?: "failed")
        }
        return artifactId
    }

    // ------------------------------------------------------------------ quiz

    suspend fun generateQuiz(notebookId: Long): Long {
        val artifactId = artifactRepo.create(notebookId, ArtifactKind.QUIZ, "Quiz")
        artifactRepo.update(artifactId, status = ArtifactStatus.RUNNING)
        try {
            val (digestText, count) = digest(notebookId)
            if (count == 0) {
                artifactRepo.update(
                    artifactId, status = ArtifactStatus.FAILED,
                    error = "Add at least one ready source first.",
                )
                return artifactId
            }
            val raw = runtime.completeChat(
                system = system,
                user = Prompts.quiz(digestText, count),
                temperature = 0.4f,
                maxTokens = 1100,
                grammar = Grammars.quiz,
                grammarRoot = "root",
            )
            val parsed = json.decodeFromString(QuizSet.serializer(), extractJson(raw))
            if (parsed.questions.isEmpty()) throw IllegalStateException("no questions generated")
            artifactRepo.update(
                artifactId, status = ArtifactStatus.READY,
                payload = json.encodeToString(QuizSet.serializer(), parsed),
            )
        } catch (t: Throwable) {
            artifactRepo.update(artifactId, status = ArtifactStatus.FAILED, error = t.message ?: "failed")
        }
        return artifactId
    }

    // -------------------------------------------------------- audio overview

    suspend fun generateOverviewScript(notebookId: Long, style: String): Long {
        val artifactId = artifactRepo.create(
            notebookId, ArtifactKind.OVERVIEW,
            if (style == "brief") "Audio Brief" else "Deep Dive",
        )
        artifactRepo.update(artifactId, status = ArtifactStatus.RUNNING)
        try {
            val (digestText, count) = digest(notebookId)
            if (count == 0) {
                artifactRepo.update(
                    artifactId, status = ArtifactStatus.FAILED,
                    error = "Add at least one ready source first.",
                )
                return artifactId
            }
            val raw = runtime.completeChat(
                system = system,
                user = Prompts.overviewScript(digestText, count, style),
                temperature = 0.6f,
                maxTokens = 1600,
                grammar = Grammars.overviewScript,
                grammarRoot = "root",
            )
            val script = json.decodeFromString(OverviewScript.serializer(), extractJson(raw))
            if (script.segments.isEmpty()) throw IllegalStateException("empty script")
            script.style = style
            artifactRepo.update(
                artifactId, status = ArtifactStatus.READY,
                payload = json.encodeToString(OverviewScript.serializer(), script),
            )
        } catch (t: Throwable) {
            artifactRepo.update(artifactId, status = ArtifactStatus.FAILED, error = t.message ?: "failed")
        }
        return artifactId
    }

    // ---------------------------------------------------------------- update

    fun saveFlashcards(artifactId: Long, set: FlashcardSet) {
        artifactRepo.update(
            artifactId, status = ArtifactStatus.READY,
            payload = json.encodeToString(FlashcardSet.serializer(), set),
        )
    }

    fun saveQuiz(artifactId: Long, set: QuizSet) {
        artifactRepo.update(
            artifactId, status = ArtifactStatus.READY,
            payload = json.encodeToString(QuizSet.serializer(), set),
        )
    }

    private fun titleFor(kind: String) = when (kind) {
        ArtifactKind.STUDY_GUIDE -> "Study Guide"
        ArtifactKind.BRIEFING -> "Briefing Doc"
        ArtifactKind.FAQ -> "FAQ"
        ArtifactKind.TIMELINE -> "Timeline"
        else -> kind
    }

    companion object {
        fun estimateDurationSeconds(segments: Int, words: Int): Int =
            (words / 2.6f + segments * 0.35f).roundToInt().coerceAtLeast(15)
    }
}
