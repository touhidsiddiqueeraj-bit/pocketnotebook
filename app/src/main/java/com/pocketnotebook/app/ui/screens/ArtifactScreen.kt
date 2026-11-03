package com.pocketnotebook.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.NavHostController
import com.pocketnotebook.app.core.db.ArtifactKind
import com.pocketnotebook.app.core.db.ArtifactStatus
import com.pocketnotebook.app.core.llm.StudioService
import com.pocketnotebook.app.core.work.WorkScheduler
import com.pocketnotebook.app.ui.MarkdownText
import com.pocketnotebook.app.ui.formatBytes
import com.pocketnotebook.app.ui.rememberContainer
import com.pocketnotebook.app.ui.theme.GlassButton
import com.pocketnotebook.app.ui.theme.GlassIconButton
import com.pocketnotebook.app.ui.theme.GlassPanel
import com.pocketnotebook.app.ui.theme.GlassProgressBar
import com.pocketnotebook.app.ui.theme.LocalGlassColors
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

@Composable
fun ArtifactScreen(artifactId: Long, nav: NavHostController) {
    val container = rememberContainer()
    val glass = LocalGlassColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val json = remember { Json { ignoreUnknownKeys = true } }

    var artifact by remember { mutableStateOf(container.artifactRepo.get(artifactId)) }

    LaunchedEffect(Unit) {
        while (true) {
            val a = container.artifactRepo.get(artifactId) ?: break
            if (a.status != ArtifactStatus.PENDING && a.status != ArtifactStatus.RUNNING) break
            artifact = a
            delay(1200)
        }
    }

    val kind = artifact?.kind
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    artifact?.title ?: "",
                    style = MaterialTheme.typography.headlineSmall,
                    color = glass.text,
                )
                Text(
                    (kind ?: "").replace('_', ''),
                    style = MaterialTheme.typography.labelSmall,
                    color = glass.textMuted,
                )
            }
            GlassIconButton(Icons.Rounded.IosShare, contentDescription = "Share") {
                shareText(context, artifact?.payload ?: "")
            }
            GlassIconButton(
                Icons.Rounded.Delete,
                contentDescription = "Delete artifact",
                tint = glass.accent3,
            ) {
                container.artifactRepo.delete(artifactId)
                nav.popBackStack()
            }
        }

        when (artifact?.status) {
            ArtifactStatus.PENDING, ArtifactStatus.RUNNING -> {
                GlassPanel(modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)) {
                    Text(
                        "Generating on-device…",
                        style = MaterialTheme.typography.titleMedium,
                        color = glass.text,
                    )
                    Text(
                        "Small model, big patience — this usually takes 30–120 seconds. Keep the app open.",
                        style = MaterialTheme.typography.bodySmall,
                        color = glass.textMuted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    GlassProgressBar(0.99f, Modifier.padding(top = 12.dp))
                }
            }
            ArtifactStatus.FAILED -> GlassPanel(modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp)) {
                Text(
                    "Generation failed",
                    style = MaterialTheme.typography.titleMedium,
                    color = glass.accent3,
                )
                Text(
                    artifact?.error ?: "unknown error",
                    style = MaterialTheme.typography.bodySmall,
                    color = glass.textMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            ArtifactStatus.READY -> when (kind) {
                ArtifactKind.STUDY_GUIDE, ArtifactKind.BRIEFING, ArtifactKind.FAQ, ArtifactKind.TIMELINE ->
                    ReportBody(notebookId = artifact?.notebookId ?: 0L, markdown = artifact?.payload ?: "")
                ArtifactKind.MINDMAP -> MindMapBody(payload = artifact?.payload ?: "")
                ArtifactKind.FLASHCARDS -> FlashcardsBody(artifactId, artifact?.payload ?: "")
                ArtifactKind.QUIZ -> QuizBody(artifactId, artifact?.payload ?: "")
                ArtifactKind.OVERVIEW -> OverviewBody(artifactId, artifact?.payload ?: "")
            }
            else -> {}
        }
    }
}

@Composable
private fun ReportBody(notebookId: Long, markdown: String) {
    val container = rememberContainer()
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 6.dp),
    ) {
        GlassPanel(modifier = Modifier.fillMaxWidth()) {
            MarkdownText(markdown)
        }
        Row(
            Modifier.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassButton("Save as note", onClick = {
                if (notebookId > 0) container.noteRepo.add(notebookId, markdown)
            })
        }
    }
}

private fun shareText(context: android.content.Context, text: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text.take(100_000))
    }
    try {
        context.startActivity(android.content.Intent.createChooser(intent, "Share"))
    } catch (_: Throwable) {
    }
}

// ---------------------------------------------------------------------------
// Audio overview: script + bake + Media3 playlist player
// ---------------------------------------------------------------------------

@Composable
private fun OverviewBody(artifactId: Long, payload: String) {
    val container = rememberContainer()
    val glass = LocalGlassColors.current
    val context = LocalContext.current
    val json = remember { Json { ignoreUnknownKeys = true } }

    val script = remember(payload) {
        runCatching {
            json.decodeFromString(StudioService.OverviewScript.serializer(), payload)
        }.getOrNull()
    } ?: return

    var artifact by remember { mutableStateOf(container.artifactRepo.get(artifactId)) }
    var baking by remember { mutableStateOf(false) }

    // poll while bake worker runs
    LaunchedEffect(baking) {
        while (baking) {
            artifact = container.artifactRepo.get(artifactId)
            if (artifact?.status == ArtifactStatus.READY && artifact?.audioDir != null) baking = false
            delay(1500)
        }
    }

    val bakedFiles = remember(artifact) {
        container.overviewBaker.bakedFiles(artifactId)
    }
    val isBaked = bakedFiles.isNotEmpty() && artifact?.audioDir != null

    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var segmentIndex by remember { mutableIntStateOf(0) }

    DisposableEffect(isBaked) {
        val p = if (isBaked) ExoPlayer.Builder(context).build() else null
        p?.let {
            it.addAll(
                0,
                bakedFiles.map { f -> MediaItem.fromUri(f.toUri()) },
            )
            it.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    segmentIndex = p.currentMediaItemIndex
                }
            })
        }
        player = p
        onDispose { p?.release() }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassPanel(modifier = Modifier.fillMaxWidth()) {
            Text(script.title, style = MaterialTheme.typography.titleLarge, color = glass.text)
            Text(
                "${script.segments.size} segments · ~${script.segments.sumOf { it.text.length } / 14} min · ${if (script.style == "brief") "single host" else "two hosts"}",
                style = MaterialTheme.typography.labelSmall,
                color = glass.textMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (!isBaked) {
                if (baking) {
                    GlassProgressBar(0.99f, Modifier.padding(top = 12.dp))
                    Text(
                        "Baking audio on-device (TTS per segment)…",
                        style = MaterialTheme.typography.labelMedium,
                        color = glass.accent,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    Text(
                        "Narration uses your device's TTS for now — LFM3-Audio native voices land after Phase 0.",
                        style = MaterialTheme.typography.bodySmall,
                        color = glass.textMuted,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    GlassButton("Bake audio", primary = true, onClick = {
                        baking = true
                        WorkScheduler.enqueueBake(context, artifactId)
                    })
                }
            }
        }

        if (isBaked && player != null) {
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    GlassIconButton(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = "Play/pause",
                        tint = glass.accent,
                    ) {
                        val p = player ?: return@GlassIconButton
                        if (p.isPlaying) p.pause()
                        else p.play()
                    }
                    GlassIconButton(Icons.Rounded.SkipPrevious, contentDescription = "Previous") {
                        player?.seekToPreviousMediaItem()
                    }
                    GlassIconButton(Icons.Rounded.SkipNext, contentDescription = "Next") {
                        player?.seekToNextMediaItem()
                    }
                    Column(Modifier.padding(start = 4.dp)) {
                        Text(
                            "Segment ${segmentIndex + 1} / ${bakedFiles.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = glass.text,
                        )
                        Text(
                            "${formatBytes(bakedFiles.sumOf { it.length() })} audio",
                            style = MaterialTheme.typography.labelSmall,
                            color = glass.textMuted,
                        )
                    }
                }
            }
        }

        Text(
            "SCRIPT",
            style = MaterialTheme.typography.labelSmall,
            color = glass.textMuted,
            modifier = Modifier.padding(start = 6.dp),
        )
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(script.segments.size) { i ->
                val seg = script.segments[i]
                GlassPanel(
                    corner = 18.dp,
                    fillTop = if (i == segmentIndex && playing) glass.accent.copy(alpha = 0.15f) else null,
                ) {
                    Text(
                        if (seg.speaker == "B") "Host B" else "Host A",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (seg.speaker == "B") glass.accent3 else glass.accent2,
                    )
                    Text(
                        seg.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = glass.text,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}
