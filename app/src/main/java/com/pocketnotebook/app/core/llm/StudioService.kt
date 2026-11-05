package com.pocketnotebook.app.core.llm

import com.pocketnotebook.app.core.db.ArtifactKind
import com.pocketnotebook.app.core.db.ArtifactRepo
import com.pocketnotebook.app.core.db.ArtifactStatus
import com.pocketnotebook.app.core.db.SourceRepo
import com.pocketnotebook.app.core.engine.LlmRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * Studio generators — every structured artifact is GBNF-constrained.
 * Shared skeleton: map-reduce digest → prompt → (grammar) → parse → persist.
 *
 * Parsing goes through [StudioJson.parseLenient]: a truncation at the token/
 * context cap yields a smaller artifact instead of a failed one (alpha.9
 * failed Deep Dive and Flashcards this way — their demanded output exceeded
 * the generation budget and the half-written JSON was unparseable).
 */
class StudioService(
    private val sourceRepo: SourceRepo,
    private val artifactRepo: ArtifactRepo,
    private val runtime: LlmRuntime,
    private val scope: CoroutineScope,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Fire-and-forget UI entry point. Launches on the app scope so the
     * artifact finishes even when the user leaves the Studio tab, opens
     * another screen or backgrounds the app — running the generation on a
     * composition scope (rememberCoroutineScope) aborted it the moment the
     * tab left composition and the card failed with
     * "coroutine scope left the composition" (alpha.11).
     * The UI observes progress through the artifact repo poll — nothing
     * else is needed.
     */
    fun request(notebookId: Long, kind: String) {
        scope.launch {
            when (kind) {
                ArtifactKind.MINDMAP -> generateMindMap(notebookId)
                ArtifactKind.FLASHCARDS -> generateFlashcards(notebookId)
                ArtifactKind.QUIZ -> generateQuiz(notebookId)
                ArtifactKind.OVERVIEW -> generateOverviewScript(notebookId, "deepdive")
                else -> generateReport(notebookId, kind)
            }
        }
    }

    /** Instruction system prompt for every studio generation. */
    private val system =
        "You are a precise offline research assistant for study materials. " +
            "Follow the user's formatting instructions exactly. Ground every claim " +
            "in the provided material and never invent facts."

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
     * row) joined into one context block, then trimmed to the token budget
     * that is left after reserving [maxTokens] of generation inside the
     * loaded model's context. The old 12000-CHARACTER cap could exceed the
     * whole context on a 4096-token device and fail the prefill outright.
     */
    suspend fun digest(notebookId: Long, maxTokens: Int): Pair<String, Int> {
        // Cheap no-op when already loaded; makes token counting exact below.
        runtime.ensureChatLoaded()
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
        val budget = runtime.promptTokenBudget(maxTokens)
        val fitted = StudioJson.fitToTokens(joined, budget) { runtime.tokenCount(it) }
        return fitted to sources.size
    }

    // -------------------------------------------------------------- reports

    suspend fun generateReport(notebookId: Long, kind: String): Long {
        val artifactId = artifactRepo.create(notebookId, kind, titleFor(kind))
        artifactRepo.update(artifactId, status = ArtifactStatus.RUNNING)
        try {
            val (digestText, count) = digest(notebookId, REPORT_TOKENS)
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
                    maxTokens = REPORT_TOKENS,
                ),
            )
            if (md.isBlank()) throw IllegalStateException("model produced no output")
            artifactRepo.update(
                artifactId,
                status = ArtifactStatus.READY,
                payload = md,
            )
        } catch (c: CancellationException) {
            // Cancellation is not a failure reason — record it as such and
            // let the coroutine machinery see the cancel. This used to hit the
            // generic Throwable branch and store Compose's cancellation
            // message ("coroutine scope left the composition") as the
            // user-visible artifact error.
            artifactRepo.update(artifactId, status = ArtifactStatus.FAILED, error = "cancelled")
            throw c
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
            val (digestText, count) = digest(notebookId, MINDMAP_TOKENS)
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
                maxTokens = MINDMAP_TOKENS,
                grammar = Grammars.mindMap,
                grammarRoot = "root",
            )
            val tree = parseMindMap(raw)
            artifactRepo.update(
                artifactId, status = ArtifactStatus.READY,
                payload = json.encodeToString(MindMapNode.serializer(), tree),
            )
        } catch (c: CancellationException) {
            // Cancellation is not a failure reason — record it as such and
            // let the coroutine machinery see the cancel. This used to hit the
            // generic Throwable branch and store Compose's cancellation
            // message ("coroutine scope left the composition") as the
            // user-visible artifact error.
            artifactRepo.update(artifactId, status = ArtifactStatus.FAILED, error = "cancelled")
            throw c
        } catch (t: Throwable) {
            artifactRepo.update(artifactId, status = ArtifactStatus.FAILED, error = t.message ?: "failed")
        }
        return artifactId
    }

    internal fun parseMindMap(raw: String): MindMapNode =
        StudioJson.parseLenient(raw) { json.decodeFromString(MindMapNode.serializer(), it) }

    internal fun parseFlashcards(raw: String): FlashcardSet =
        StudioJson.parseLenient(raw) { json.decodeFromString(FlashcardSet.serializer(), it) }

    internal fun parseQuiz(raw: String): QuizSet =
        StudioJson.parseLenient(raw) { json.decodeFromString(QuizSet.serializer(), it) }

    internal fun parseOverview(raw: String): OverviewScript =
        StudioJson.parseLenient(raw) { json.decodeFromString(OverviewScript.serializer(), it) }

    // ------------------------------------------------------------- flashcards

    suspend fun generateFlashcards(notebookId: Long): Long {
        val artifactId = artifactRepo.create(notebookId, ArtifactKind.FLASHCARDS, "Flashcards")
        artifactRepo.update(artifactId, status = ArtifactStatus.RUNNING)
        try {
            val (digestText, count) = digest(notebookId, FLASHCARD_TOKENS)
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
                maxTokens = FLASHCARD_TOKENS,
                grammar = Grammars.flashcards,
                grammarRoot = "root",
            )
            val parsed = parseFlashcards(raw)
            if (parsed.cards.isEmpty()) throw IllegalStateException("no cards generated")
            val now = System.currentTimeMillis()
            parsed.cards.forEach { it.dueAt = now }
            artifactRepo.update(
                artifactId, status = ArtifactStatus.READY,
                payload = json.encodeToString(FlashcardSet.serializer(), parsed),
            )
        } catch (c: CancellationException) {
            // Cancellation is not a failure reason — record it as such and
            // let the coroutine machinery see the cancel. This used to hit the
            // generic Throwable branch and store Compose's cancellation
            // message ("coroutine scope left the composition") as the
            // user-visible artifact error.
            artifactRepo.update(artifactId, status = ArtifactStatus.FAILED, error = "cancelled")
            throw c
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
            val (digestText, count) = digest(notebookId, QUIZ_TOKENS)
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
                maxTokens = QUIZ_TOKENS,
                grammar = Grammars.quiz,
                grammarRoot = "root",
            )
            val parsed = sanitizeQuiz(parseQuiz(raw))
            if (parsed.questions.isEmpty()) throw IllegalStateException("no questions generated")
            artifactRepo.update(
                artifactId, status = ArtifactStatus.READY,
                payload = json.encodeToString(QuizSet.serializer(), parsed),
            )
        } catch (c: CancellationException) {
            // Cancellation is not a failure reason — record it as such and
            // let the coroutine machinery see the cancel. This used to hit the
            // generic Throwable branch and store Compose's cancellation
            // message ("coroutine scope left the composition") as the
            // user-visible artifact error.
            artifactRepo.update(artifactId, status = ArtifactStatus.FAILED, error = "cancelled")
            throw c
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
            val (digestText, count) = digest(notebookId, OVERVIEW_TOKENS)
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
                maxTokens = OVERVIEW_TOKENS,
                grammar = Grammars.overviewScript,
                grammarRoot = "root",
            )
            val script = parseOverview(raw)
            if (script.segments.isEmpty()) throw IllegalStateException("empty script")
            script.style = style
            artifactRepo.update(
                artifactId, status = ArtifactStatus.READY,
                payload = json.encodeToString(OverviewScript.serializer(), script),
            )
        } catch (c: CancellationException) {
            // Cancellation is not a failure reason — record it as such and
            // let the coroutine machinery see the cancel. This used to hit the
            // generic Throwable branch and store Compose's cancellation
            // message ("coroutine scope left the composition") as the
            // user-visible artifact error.
            artifactRepo.update(artifactId, status = ArtifactStatus.FAILED, error = "cancelled")
            throw c
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

        // Generation budgets (tokens). Set so the DEMANDED output fits the
        // budget: alpha.9 asked for up to 14 padded cards / 26 long segments
        // with a 1100/1600 token cap — the two cards that always failed.
        private const val REPORT_TOKENS = 1100
        private const val MINDMAP_TOKENS = 900
        private const val FLASHCARD_TOKENS = 1400
        private const val QUIZ_TOKENS = 1100
        private const val OVERVIEW_TOKENS = 2200

        /** Drop questions a truncation salvage may have left malformed. */
        internal fun sanitizeQuiz(set: QuizSet): QuizSet {
            set.questions.removeAll {
                it.options.size != 4 || it.answer !in 0..3 || it.question.isBlank()
            }
            return set
        }
    }
}
