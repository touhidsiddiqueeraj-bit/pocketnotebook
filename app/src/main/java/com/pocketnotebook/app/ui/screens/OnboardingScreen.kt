package com.pocketnotebook.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.GetApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.pocketnotebook.app.core.db.ModelSlot
import com.pocketnotebook.app.core.hf.ModelScanner
import com.pocketnotebook.app.core.work.WorkScheduler
import com.pocketnotebook.app.ui.GlassScaffold
import com.pocketnotebook.app.ui.rememberContainer
import com.pocketnotebook.app.ui.formatBytes
import com.pocketnotebook.app.ui.theme.GlassButton
import com.pocketnotebook.app.ui.theme.GlassPanel
import com.pocketnotebook.app.ui.theme.GlassProgressBar
import com.pocketnotebook.app.ui.theme.LocalGlassColors

/** First-run screen: privacy line + one-tap model download. */
@Composable
fun OnboardingScreen(nav: NavHostController) {
    val container = rememberContainer()
    val glass = LocalGlassColors.current
    val context = LocalContext.current

    val nativeReady = container.runtime.nativeReady
    var hasChat by remember { mutableStateOf(container.prefs.activeModelPath(ModelSlot.CHAT) != null) }
    var hasEmbed by remember { mutableStateOf(container.prefs.activeModelPath(ModelSlot.EMBED) != null) }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    // Pick up model files that are already on disk but never registered
    // (alpha.3 downloads) so the download step disappears without a re-download.
    fun refreshSlots() {
        hasChat = container.prefs.activeModelPath(ModelSlot.CHAT) != null
        hasEmbed = container.prefs.activeModelPath(ModelSlot.EMBED) != null
    }

    LaunchedEffect(Unit) {
        runCatching { ModelScanner.scanAndRegister(container) }
        refreshSlots()
    }

    // Live download progress from the download workers.
    val workInfos by WorkManager.getInstance(context)
        .getWorkInfosByTagFlow("download")
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val running = workInfos.any { it.state == WorkInfo.State.RUNNING }
    val queued = workInfos.any { it.state == WorkInfo.State.ENQUEUED }
    val succeededCount = workInfos.count { it.state == WorkInfo.State.SUCCEEDED }
    val failedCount = workInfos.count { it.state == WorkInfo.State.FAILED }
    val failed = failedCount > 0 && !running && !queued && !hasChat

    val info = workInfos.firstOrNull { it.state == WorkInfo.State.RUNNING }
    val percent = info?.progress?.getInt("percent", 0) ?: 0
    val bytes = info?.progress?.getLong("bytes", 0) ?: 0
    val total = info?.progress?.getLong("total", 0) ?: 0

    // Refresh slot state whenever downloads reach a terminal state.
    LaunchedEffect(succeededCount, failedCount) {
        if (succeededCount + failedCount > 0) {
            runCatching { ModelScanner.scanAndRegister(container) }
            refreshSlots()
        }
    }

    fun finish() {
        container.prefs.onboardDone = true
        nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
    }

    fun startDownload() {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        for (entry in listOf("lfm3-1.7b-qadq4", "bge-small-q8").mapNotNull { container.modelCatalog.byId(it) }) {
            val id = entry.fileName.hashCode().toLong()
            WorkScheduler.enqueueDownload(
                context, id, entry.repoId, entry.fileName, entry.slot, entry.sizeBytes,
            )
        }
    }

    GlassScaffold { Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            Icons.Rounded.AutoStories, contentDescription = null,
            tint = glass.accent, modifier = Modifier.size(56.dp),
        )
        Text(
            "PocketNotebook",
            style = MaterialTheme.typography.displaySmall,
            color = glass.text,
        )
        Text(
            "Your offline NotebookLM.",
            style = MaterialTheme.typography.bodyMedium,
            color = glass.textMuted,
            textAlign = TextAlign.Center,
        )
        Text(
            "100% on-device · no account · no tracking",
            style = MaterialTheme.typography.labelSmall,
            color = glass.textMuted,
        )

        if (!nativeReady) {
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Kit build — no AI engine. Install a release APK.",
                    style = MaterialTheme.typography.bodySmall,
                    color = glass.accent3,
                )
            }
        }

        if (!hasChat) {
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Download models",
                    style = MaterialTheme.typography.titleMedium,
                    color = glass.text,
                )
                Text(
                    "LFM3-1.7B (${formatBytes(1_049_615_680)}) + embeddings (${formatBytes(36_806_944)})",
                    style = MaterialTheme.typography.bodySmall,
                    color = glass.textMuted,
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                )
                when {
                    running -> {
                        GlassProgressBar((percent / 100f).coerceIn(0f, 1f))
                        Text(
                            if (total > 0) "$percent% · ${formatBytes(bytes)} / ${formatBytes(total)}"
                            else "Connecting…",
                            style = MaterialTheme.typography.labelMedium,
                            color = glass.textMuted,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    queued -> {
                        Text("Queued…", style = MaterialTheme.typography.labelMedium, color = glass.textMuted)
                    }
                    failed -> {
                        Text(
                            "Download failed",
                            style = MaterialTheme.typography.labelMedium,
                            color = glass.accent3,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        GlassButton(
                            text = "Retry",
                            primary = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                        ) { startDownload() }
                    }
                    else -> {
                        GlassButton(
                            text = "Download",
                            icon = Icons.Rounded.GetApp,
                            primary = true,
                            enabled = nativeReady,
                            modifier = Modifier.fillMaxWidth(),
                        ) { startDownload() }
                    }
                }
            }
        } else {
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.DownloadDone, null, tint = glass.accent)
                    Text(
                        if (hasEmbed) "Models ready." else "Chat model ready.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = glass.text,
                    )
                }
            }
        }

        GlassButton(
            text = if (hasChat) "Start" else "Skip",
            onClick = { finish() },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
        )
    } }
}
