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
import androidx.compose.foundation.layout.widthIn
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
import com.pocketnotebook.app.core.work.WorkScheduler
import com.pocketnotebook.app.ui.GlassScaffold
import com.pocketnotebook.app.ui.rememberContainer
import com.pocketnotebook.app.ui.formatBytes
import com.pocketnotebook.app.ui.theme.GlassButton
import com.pocketnotebook.app.ui.theme.GlassPanel
import com.pocketnotebook.app.ui.theme.GlassProgressBar
import com.pocketnotebook.app.ui.theme.LocalGlassColors

/** First-run screen: privacy blurb + one-tap model download. */
@Composable
fun OnboardingScreen(nav: NavHostController) {
    val container = rememberContainer()
    val glass = LocalGlassColors.current
    val context = LocalContext.current

    val nativeReady = container.runtime.nativeReady
    var hasChat by remember { mutableStateOf(container.prefs.activeModelPath(ModelSlot.CHAT) != null) }
    var hasEmbed by remember { mutableStateOf(container.prefs.activeModelPath(ModelSlot.EMBED) != null) }
    var downloading by remember { mutableStateOf(false) }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    // Live download progress from the download workers.
    val workInfos by WorkManager.getInstance(context)
        .getWorkInfosByTagFlow("download")
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val running = workInfos.filter { it.state == WorkInfo.State.RUNNING }
    val progressBytes = running.maxOfOrNull { it.progress.getLong("bytes", 0) } ?: 0
    val progressTotal = running.maxOfOrNull { it.progress.getLong("total", 0) } ?: 0
    val progressPercent = running.maxOfOrNull { it.progress.getInt("percent", 0) } ?: 0
    val allDone = workInfos.isNotEmpty() && running.isEmpty() &&
        workInfos.any { it.state == WorkInfo.State.SUCCEEDED }

    LaunchedEffect(allDone) {
        if (allDone) {
            hasChat = container.prefs.activeModelPath(ModelSlot.CHAT) != null
            hasEmbed = container.prefs.activeModelPath(ModelSlot.EMBED) != null
        }
    }

    fun finish() {
        container.prefs.onboardDone = true
        nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
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
            "An offline research companion.\nSources in, answers with citations out — generated on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = glass.textMuted,
            textAlign = TextAlign.Center,
        )
        GlassPanel(modifier = Modifier.fillMaxWidth()) {
            Text(
                "100% on-device · no account · no telemetry.\nThe only network traffic is what you ask for: model downloads and web pages you add as sources.",
                style = MaterialTheme.typography.bodySmall,
                color = glass.textMuted,
            )
        }

        if (!nativeReady) {
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "This APK was built without the AI engine (kit build). Grab a release APK from the project's GitHub Releases to run models on-device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = glass.accent3,
                )
            }
        }

        if (!hasChat) {
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Text("Step 1 · Download the brain", style = MaterialTheme.typography.titleMedium, color = glass.text)
                Text(
                    "LFM3-1.7B QAD-Q4_0 (${formatBytes(1049615680)}) — fastest in-class on-device model — plus bge-small embeddings (35 MB) for semantic search.",
                    style = MaterialTheme.typography.bodySmall,
                    color = glass.textMuted,
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                )
                if (downloading && running.isNotEmpty()) {
                    GlassProgressBar((progressPercent / 100f).coerceIn(0f, 1f))
                    Text(
                        if (progressTotal > 0) "$progressPercent% · ${formatBytes(progressBytes)} of ${formatBytes(progressTotal)}"
                        else "Connecting to Hugging Face…",
                        style = MaterialTheme.typography.labelMedium,
                        color = glass.textMuted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else if (downloading) {
                    Text("Queued…", style = MaterialTheme.typography.labelMedium, color = glass.textMuted)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassButton(
                            text = "Download & set up",
                            icon = Icons.Rounded.GetApp,
                            primary = true,
                            enabled = nativeReady,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            downloading = true
                            for (entry in listOf("lfm3-1.7b-qadq4", "bge-small-q8").mapNotNull { container.modelCatalog.byId(it) }) {
                                val id = entry.fileName.hashCode().toLong()
                                WorkScheduler.enqueueDownload(
                                    context, id, entry.repoId, entry.fileName, entry.slot, entry.sizeBytes,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.DownloadDone, null, tint = glass.accent)
                    Text(
                        if (hasEmbed) "Brain + embeddings installed. You're set."
                        else "Brain installed. Embeddings can be added in Models.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = glass.text,
                    )
                }
            }
        }

        GlassButton(
            text = if (hasChat) "Enter PocketNotebook" else "Skip for now",
            onClick = { finish() },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
        )
        Text(
            "English-only in this version · models & voices via Hugging Face",
            style = MaterialTheme.typography.labelSmall,
            color = glass.textMuted,
        )
    } }
}
