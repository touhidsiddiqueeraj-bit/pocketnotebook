package com.pocketnotebook.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Assignment
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Toc
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.pocketnotebook.app.core.db.Artifact
import com.pocketnotebook.app.core.db.ArtifactKind
import com.pocketnotebook.app.core.db.ModelSlot
import com.pocketnotebook.app.ui.Routes
import com.pocketnotebook.app.ui.rememberContainer
import com.pocketnotebook.app.ui.theme.GlassButton
import com.pocketnotebook.app.ui.theme.GlassPanel
import com.pocketnotebook.app.ui.theme.GlassProgressBar
import com.pocketnotebook.app.ui.theme.GlassRow
import com.pocketnotebook.app.ui.theme.GlassIconButton
import com.pocketnotebook.app.ui.theme.LocalGlassColors
import com.pocketnotebook.app.ui.timeAgo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class StudioCard(
    val kind: String,
    val label: String,
    val icon: ImageVector,
)

@Composable
fun StudioTab(notebookId: Long, nav: NavHostController) {
    val container = rememberContainer()
    val glass = LocalGlassColors.current
    val scope = rememberCoroutineScope()

    var artifacts by remember { mutableStateOf(container.artifactRepo.list(notebookId)) }
    val runtimeState by container.runtime.state.collectAsState()
    val modelSet = remember { container.runtime.modelPathFor(ModelSlot.CHAT) != null }

    fun reload() { artifacts = container.artifactRepo.list(notebookId) }

    // Poll while any artifact is running so generation status updates.
    LaunchedEffect(Unit) {
        while (true) {
            if (artifacts.any { it.status == "running" || it.status == "pending" }) reload()
            delay(1200)
        }
    }

    fun generate(card: StudioCard) {
        if (runtimeState.generating || !modelSet) return
        scope.launch {
            when (card.kind) {
                ArtifactKind.MINDMAP -> container.studioService.generateMindMap(notebookId)
                ArtifactKind.FLASHCARDS -> container.studioService.generateFlashcards(notebookId)
                ArtifactKind.QUIZ -> container.studioService.generateQuiz(notebookId)
                ArtifactKind.OVERVIEW -> container.studioService.generateOverviewScript(notebookId, "deepdive")
                else -> container.studioService.generateReport(notebookId, card.kind)
            }
            reload()
        }
    }

    val cards = listOf(
        StudioCard(ArtifactKind.STUDY_GUIDE, "Study Guide", Icons.Rounded.Assignment),
        StudioCard(ArtifactKind.BRIEFING, "Briefing", Icons.Rounded.Toc),
        StudioCard(ArtifactKind.FAQ, "FAQ", Icons.Rounded.Lightbulb),
        StudioCard(ArtifactKind.TIMELINE, "Timeline", Icons.Rounded.Timeline),
        StudioCard(ArtifactKind.MINDMAP, "Mind Map", Icons.Rounded.Psychology),
        StudioCard(ArtifactKind.FLASHCARDS, "Flashcards", Icons.Rounded.Style),
        StudioCard(ArtifactKind.QUIZ, "Quiz", Icons.Rounded.Quiz),
        StudioCard(ArtifactKind.OVERVIEW, "Deep Dive", Icons.Rounded.Headphones),
    )

    Column(Modifier.fillMaxWidth()) {
        when {
            !modelSet -> GlassPanel(corner = 18.dp, modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "No chat model.",
                        style = MaterialTheme.typography.bodySmall,
                        color = glass.textMuted,
                        modifier = Modifier.weight(1f),
                    )
                    GlassButton("Get", primary = true, onClick = { nav.navigate(Routes.MODELS) })
                }
            }
            runtimeState.chatLoading -> GlassPanel(corner = 18.dp, modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp)) {
                Text("Loading model…", style = MaterialTheme.typography.bodySmall, color = glass.textMuted)
            }
            runtimeState.chatPath == null && runtimeState.chatError != null ->
                GlassPanel(corner = 18.dp, modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            (runtimeState.chatError ?: "Model failed to load.").take(200),
                            style = MaterialTheme.typography.bodySmall,
                            color = glass.accent3,
                            modifier = Modifier.weight(1f),
                        )
                        GlassButton("Retry", onClick = {
                            scope.launch { container.runtime.ensureChatLoaded() }
                        })
                    }
                }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        ) {
            items(cards, key = { it.kind }) { card ->
                val latest = artifacts.firstOrNull { it.kind == card.kind }
                val running = latest?.status == "running" || latest?.status == "pending"
                GlassPanel(
                    corner = 24.dp,
                    onClick = {
                        val ready = artifacts.firstOrNull { it.kind == card.kind && it.status == "ready" }
                        if (ready != null) nav.navigate(Routes.artifact(ready.id)) else generate(card)
                    },
                    onLongClick = { generate(card) },
                ) {
                    Icon(card.icon, null, tint = glass.accent2, modifier = Modifier.size(24.dp))
                    Text(
                        card.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = glass.text,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (running) {
                        GlassProgressBar(0.99f, Modifier.padding(top = 10.dp)) // pulsing near-full
                        Text(
                            "Generating…",
                            style = MaterialTheme.typography.labelSmall,
                            color = glass.accent,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    } else if (latest?.status == "failed") {
                        Text(
                            "Failed · tap to retry",
                            style = MaterialTheme.typography.labelSmall,
                            color = glass.accent3,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        val others = artifacts.filter { it.status == "ready" }.take(12)
        if (others.isNotEmpty()) {
            Text(
                "RECENT",
                style = MaterialTheme.typography.labelSmall,
                color = glass.textMuted,
                modifier = Modifier.padding(start = 24.dp, top = 4.dp),
            )
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listItems(others, key = { it.id }) { a ->
                    GlassRow(onClick = { nav.navigate(Routes.artifact(a.id)) }) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                a.title, style = MaterialTheme.typography.titleSmall, color = glass.text,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${a.kind.replace('_', ' ')} · ${timeAgo(a.createdAt)}",
                                style = MaterialTheme.typography.labelSmall, color = glass.textMuted,
                            )
                        }
                    }
                }
            }
        }
    }
}
