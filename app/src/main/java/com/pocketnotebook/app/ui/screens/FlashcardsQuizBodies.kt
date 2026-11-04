package com.pocketnotebook.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketnotebook.app.core.llm.StudioService
import com.pocketnotebook.app.core.study.Sm2
import com.pocketnotebook.app.ui.GlassDivider
import com.pocketnotebook.app.ui.rememberContainer
import com.pocketnotebook.app.ui.theme.GlassButton
import com.pocketnotebook.app.ui.theme.GlassChip
import com.pocketnotebook.app.ui.theme.GlassIconButton
import com.pocketnotebook.app.ui.theme.GlassPanel
import com.pocketnotebook.app.ui.theme.LocalGlassColors
import kotlinx.serialization.json.Json

private fun parseSet(artifactId: Long, payload: String, container: com.pocketnotebook.app.AppContainer): StudioService.FlashcardSet? {
    val json = Json { ignoreUnknownKeys = true }
    return runCatching { json.decodeFromString(StudioService.FlashcardSet.serializer(), payload) }
        .getOrNull()
        .also { set -> if (set == null) persistError(container, artifactId, "parse failed") }
}

private fun persistError(container: com.pocketnotebook.app.AppContainer, artifactId: Long, msg: String) {
    runCatching { container.artifactRepo.update(artifactId, error = msg) }
}

/** Flip-card study session with SM-2 scheduling. */
@Composable
fun FlashcardsBody(artifactId: Long, payload: String) {
    val container = rememberContainer()
    val glass = LocalGlassColors.current

    val initialSet = remember { parseSet(artifactId, payload, container) }
    if (initialSet == null) return
    var set by remember { mutableStateOf(initialSet) }
    var dueOnly by remember { mutableStateOf(true) }
    var queue by remember {
        val now = System.currentTimeMillis()
        mutableStateOf(set.cards.indices.filter { i -> set.cards[i].dueAt <= now })
    }
    var position by remember { mutableIntStateOf(0) }
    var flipped by remember { mutableStateOf(false) }

    fun grade(grade: Int) {
        val s = set
        if (position !in queue.indices) return
        val cardIndex = queue[position]
        val c = s.cards[cardIndex]
        val updated = Sm2.review(Sm2.Card(c.ease, c.intervalDays, c.reps, c.lapses), grade, System.currentTimeMillis())
        s.cards[cardIndex] = c.copy(
            ease = updated.ease,
            intervalDays = updated.intervalDays,
            reps = updated.reps,
            lapses = updated.lapses,
            dueAt = Sm2.dueAtMs(updated, System.currentTimeMillis()),
        )
        container.studioService.saveFlashcards(artifactId, s)
        set = s
        flipped = false
        position = (position + 1).coerceAtMost(queue.size - 1)
    }

    fun rebuildQueue(onlyDue: Boolean) {
        dueOnly = onlyDue
        val now = System.currentTimeMillis()
        queue = if (onlyDue) set.cards.indices.filter { i -> set.cards[i].dueAt <= now } else set.cards.indices.toList()
        position = 0
        flipped = false
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (queue.isEmpty()) {
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Nothing due.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = glass.text,
                )
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton("Study all", onClick = { rebuildQueue(false) })
                }
            }
            return@Column
        }

        val cardIndex = queue[position]
        val card = set.cards[cardIndex]

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${position + 1} / ${queue.size}",
                style = MaterialTheme.typography.labelMedium,
                color = glass.text,
                modifier = Modifier.weight(1f),
            )
            GlassChip("Due only", selected = dueOnly, onClick = { rebuildQueue(!dueOnly) })
            GlassIconButton(Icons.Rounded.Refresh, contentDescription = "Shuffle") {
                queue = queue.shuffled()
                position = 0
                flipped = false
            }
        }

        GlassPanel(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp),
            onClick = { flipped = !flipped },
        ) {
            Text(
                if (!flipped) "TAP TO REVEAL" else "ANSWER",
                style = MaterialTheme.typography.labelSmall,
                color = glass.textMuted,
            )
            Text(
                if (!flipped) card.front else card.back,
                style = MaterialTheme.typography.titleLarge,
                color = glass.text,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        if (flipped) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GradeButton("Again", glass.accent3) { grade(0) }
                GradeButton("Hard", glass.textMuted) { grade(1) }
                GradeButton("Good", glass.accent2) { grade(2) }
                GradeButton("Easy", glass.accent) { grade(3) }
            }
        }
    }
}

@Composable
private fun GradeButton(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    GlassChip(text = label, selected = true, accent = color, onClick = onClick)
}

// ---------------------------------------------------------------------------
// Quiz
// ---------------------------------------------------------------------------

@Composable
fun QuizBody(artifactId: Long, payload: String) {
    val container = rememberContainer()
    val glass = LocalGlassColors.current
    val json = remember { Json { ignoreUnknownKeys = true } }

    val quiz = remember {
        runCatching { json.decodeFromString(StudioService.QuizSet.serializer(), payload) }.getOrNull()
    } ?: run {
        GlassPanel(modifier = Modifier
            .fillMaxWidth()
            .padding(22.dp)) {
            Text("Could not read quiz data.", color = glass.accent3)
        }
        return
    }

    var position by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var score by remember { mutableIntStateOf(0) }
    var finished by remember { mutableStateOf(false) }

    fun persist(score: Int) {
        if (quiz.bestScore == null || score > quiz.bestScore!!) {
            quiz.bestScore = score
            container.studioService.saveQuiz(artifactId, quiz)
        }
    }

    if (finished || quiz.questions.isEmpty()) {
        val pct = if (quiz.questions.isEmpty()) 0 else (score * 100) / quiz.questions.size
        GlassPanel(modifier = Modifier
            .fillMaxWidth()
            .padding(22.dp)) {
            Text(
                if (quiz.questions.isEmpty()) "Empty quiz" else "You scored $score / ${quiz.questions.size}",
                style = MaterialTheme.typography.headlineSmall,
                color = glass.text,
            )
            Text(
                if (quiz.bestScore != null) "Best: ${quiz.bestScore} — ${pct}%" else "$pct%",
                style = MaterialTheme.typography.bodyMedium,
                color = glass.textMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        return
    }

    val q = quiz.questions[position]
    val answered = selected != null

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Question ${position + 1} / ${quiz.questions.size} · score $score",
            style = MaterialTheme.typography.labelMedium,
            color = glass.textMuted,
            modifier = Modifier.padding(start = 6.dp),
        )
        GlassPanel(modifier = Modifier.fillMaxWidth()) {
            Text(q.question, style = MaterialTheme.typography.titleLarge, color = glass.text)
            q.options.forEachIndexed { i, opt ->
                val isPicked = selected == i
                val isCorrect = answered && i == q.answer
                val isWrongPick = answered && isPicked && i != q.answer
                GlassChip(
                    text = opt,
                    selected = isPicked || isCorrect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    accent = when {
                        isCorrect -> glass.accent
                        isWrongPick -> glass.accent3
                        else -> null
                    },
                    onClick = { if (!answered) selected = i },
                )
            }
        }
        if (answered) {
            Text(
                if (selected == q.answer) "Correct" else "Answer: ${q.options[q.answer]}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected == q.answer) glass.accent else glass.accent3,
                modifier = Modifier.padding(start = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton(
                    if (position == quiz.questions.size - 1) "Finish" else "Next",
                    primary = true,
                    onClick = {
                        if (selected == q.answer) score++
                        selected = null
                        if (position == quiz.questions.size - 1) {
                            finished = true
                            persist(score)
                        } else position++
                    },
                )
            }
        }
    }
}
