package com.pocketnotebook.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Verified
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.pocketnotebook.app.core.db.ModelSlot
import com.pocketnotebook.app.core.work.WorkScheduler
import com.pocketnotebook.app.ui.GlassDialog
import com.pocketnotebook.app.ui.GlassScaffold
import com.pocketnotebook.app.ui.formatBytes
import com.pocketnotebook.app.ui.rememberContainer
import com.pocketnotebook.app.ui.theme.GlassButton
import com.pocketnotebook.app.ui.theme.GlassChip
import com.pocketnotebook.app.ui.theme.GlassIconButton
import com.pocketnotebook.app.ui.theme.GlassPanel
import com.pocketnotebook.app.ui.theme.GlassProgressBar
import com.pocketnotebook.app.ui.theme.GlassRow
import com.pocketnotebook.app.ui.theme.GlassTextField
import com.pocketnotebook.app.ui.theme.LocalGlassColors
import kotlinx.coroutines.launch

/** Model manager: slots, curated catalog, arbitrary HF search, downloads. */
@Composable
fun ModelsScreen(nav: NavHostController) {
    val container = rememberContainer()
    val glass = LocalGlassColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var installed by remember { mutableStateOf(container.modelRepo.listAll()) }
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<com.pocketnotebook.app.core.hf.HfApi.ModelSearchHit>>(emptyList()) }
    var pickingRepo by remember { mutableStateOf<String?>(null) }
    var pickingFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var pickingLoading by remember { mutableStateOf(false) }
    var pickSlot by remember { mutableStateOf(ModelSlot.CHAT) }

    val runtimeState by container.runtime.state.collectAsState()
    val workInfos by WorkManager.getInstance(context)
        .getWorkInfosByTagFlow("download")
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val runningInfos = workInfos.filter { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }

    fun reload() {
        installed = container.modelRepo.listAll()
    }

    // Pick up orphaned model files (e.g. downloads finished under a build
    // whose DB write crashed) so they show up as installed without a re-download.
    LaunchedEffect(Unit) {
        runCatching { com.pocketnotebook.app.core.hf.ModelScanner.scanAndRegister(container) }
        reload()
    }

    // Poll unconditionally: recomposition redraws progress, but the INSTALLED
    // list is a snapshot that must also refresh when a worker finishes.
    LaunchedEffect(Unit) {
        while (true) {
            reload()
            kotlinx.coroutines.delay(2000)
        }
    }

    fun search() {
        if (query.isBlank()) return
        scope.launch {
            searchResults = container.hfApi.search(query.trim())
        }
    }

    fun enqueue(repoId: String, fileName: String, slot: String) {
        val id = fileName.hashCode().toLong()
        scope.launch {
            // Blocking OkHttp call — must stay off the main thread.
            val size = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                container.hfApi.fileSize(repoId, fileName)
            }
            WorkScheduler.enqueueDownload(context, id, repoId, fileName, slot, size)
        }
    }

    GlassScaffold(
        top = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") { nav.popBackStack() }
                Text(
                    "Models",
                    style = MaterialTheme.typography.titleLarge,
                    color = glass.text,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                )
                Text(
                    if (container.runtime.nativeReady) "engine ready" else "engine unavailable",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (container.runtime.nativeReady) glass.accent else glass.accent3,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        },
    ) {
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- active slots ----
            item {
                Text(
                    "SLOTS",
                    style = MaterialTheme.typography.labelSmall,
                    color = glass.textMuted,
                    modifier = Modifier.padding(start = 6.dp, top = 4.dp),
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SlotRow("Chat", container.prefs.activeModelPath(ModelSlot.CHAT), runtimeState.chatLoading, runtimeState.chatPath)
                    SlotRow("Embeddings", container.prefs.activeModelPath(ModelSlot.EMBED), runtimeState.embedLoading, runtimeState.embedPath)
                    SlotRow("Speech", container.prefs.activeModelPath(ModelSlot.AUDIO), false, null)
                }
            }

            // ---- downloads in progress ----
            if (runningInfos.isNotEmpty()) {
                item {
                    Text(
                        "DOWNLOADING",
                        style = MaterialTheme.typography.labelSmall,
                        color = glass.textMuted,
                        modifier = Modifier.padding(start = 6.dp, top = 8.dp),
                    )
                }
                items(runningInfos, key = { it.id }) { info ->
                    val percent = info.progress.getInt("percent", 0)
                    val total = info.progress.getLong("total", 0)
                    val fileName = info.progress.getString("file") ?: "model file"
                    GlassPanel(corner = 18.dp, modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CloudDownload, null, tint = glass.accent, modifier = Modifier.padding(end = 8.dp))
                            Text(
                                fileName,
                                style = MaterialTheme.typography.titleSmall,
                                color = glass.text,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        GlassProgressBar(percent / 100f, Modifier.padding(top = 8.dp))
                        Text(
                            "$percent%" + if (total > 0) " · of ${formatBytes(total)}" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = glass.textMuted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            // ---- installed models ----
            if (installed.isNotEmpty()) {
                item {
                    Text(
                        "INSTALLED",
                        style = MaterialTheme.typography.labelSmall,
                        color = glass.textMuted,
                        modifier = Modifier.padding(start = 6.dp, top = 8.dp),
                    )
                }
                items(installed, key = { it.id }) { m ->
                    GlassRow(onLongClick = {
                        java.io.File(m.filePath).delete()
                        container.modelRepo.delete(m.id)
                        if (container.prefs.activeModelPath(m.slot) == m.filePath) {
                            container.prefs.setActiveModel(m.slot, null)
                        }
                        reload()
                    }) {
                        Icon(Icons.Rounded.Memory, null, tint = glass.accent2)
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp),
                        ) {
                            Text(m.fileName, style = MaterialTheme.typography.titleSmall, color = glass.text,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                formatBytes(m.sizeBytes),
                                style = MaterialTheme.typography.labelSmall, color = glass.textMuted,
                                maxLines = 1,
                            )
                        }
                        if (container.prefs.activeModelPath(m.slot) == m.filePath) {
                            Icon(Icons.Rounded.Verified, "active", tint = glass.accent)
                        } else {
                            GlassChip("Activate", onClick = {
                                container.prefs.setActiveModel(m.slot, m.filePath)
                                if (m.slot == ModelSlot.CHAT) scope.launch {
                                    container.runtime.unloadChat()
                                    container.runtime.ensureChatLoaded()
                                }
                                reload()
                            })
                        }
                    }
                }
            }

            // ---- curated catalog (only what is still missing) ----
            val slotsFilled = installed.map { it.slot }.toSet()
            val missing = container.modelCatalog.entries.filter { it.slot !in slotsFilled }
            if (missing.isNotEmpty()) {
                item {
                    Text(
                        "SUGGESTED",
                        style = MaterialTheme.typography.labelSmall,
                        color = glass.textMuted,
                        modifier = Modifier.padding(start = 6.dp, top = 8.dp),
                    )
                }
                items(missing, key = { it.id }) { entry ->
                    GlassPanel(corner = 22.dp, modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (entry.experimental) Icons.Rounded.Science else Icons.Rounded.Verified,
                                null,
                                tint = if (entry.experimental) glass.accent3 else glass.accent,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text(
                                entry.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = glass.text,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                formatBytes(entry.sizeBytes),
                                style = MaterialTheme.typography.labelSmall,
                                color = glass.textMuted,
                            )
                        }
                        GlassButton(
                            text = "Download",
                            icon = Icons.Rounded.CloudDownload,
                            primary = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                        ) {
                            enqueue(entry.repoId, entry.fileName, entry.slot)
                        }
                    }
                }
            }

            // ---- arbitrary HF search ----
            item {
                Text(
                    "SEARCH HUGGING FACE",
                    style = MaterialTheme.typography.labelSmall,
                    color = glass.textMuted,
                    modifier = Modifier.padding(start = 6.dp, top = 8.dp),
                )
                Row(
                    Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    GlassTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "repo, e.g. LiquidAI/LFM3-1.7B-GGUF",
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    GlassIconButton(Icons.Rounded.Search, contentDescription = "Search") { search() }
                }
            }
            items(searchResults, key = { it.id }) { hit ->
                GlassRow(onClick = {
                    pickingRepo = hit.id
                    pickingLoading = true
                    scope.launch {
                        val info = container.hfApi.modelInfo(hit.id)
                        pickingFiles = info?.let { container.hfApi.ggufFiles(it) } ?: emptyList()
                        pickingLoading = false
                    }
                }) {
                    Column(Modifier.weight(1f)) {
                        Text(hit.id, style = MaterialTheme.typography.titleSmall, color = glass.text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "downloads ${hit.downloads}",
                            style = MaterialTheme.typography.labelSmall, color = glass.textMuted,
                        )
                    }
                }
            }
            item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp)) }
        }
    }

    // ---- file picker dialog for a repo ----
    pickingRepo?.let { repoId ->
        GlassDialog(onDismiss = { pickingRepo = null }, title = repoId) {
            if (pickingLoading) {
                Text("Loading file list…", style = MaterialTheme.typography.bodySmall, color = glass.textMuted)
            } else {
                Text(
                    "Slot",
                    style = MaterialTheme.typography.labelMedium,
                    color = glass.textMuted,
                )
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip("Chat", selected = pickSlot == ModelSlot.CHAT, onClick = { pickSlot = ModelSlot.CHAT })
                    GlassChip("Embed", selected = pickSlot == ModelSlot.EMBED, onClick = { pickSlot = ModelSlot.EMBED })
                    GlassChip("Audio", selected = pickSlot == ModelSlot.AUDIO, onClick = { pickSlot = ModelSlot.AUDIO })
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    pickingFiles.take(12).forEach { f ->
                        GlassRow(onClick = {
                            enqueue(repoId, f, pickSlot)
                            pickingRepo = null
                        }) {
                            Text(
                                f.substringAfterLast('/'),
                                style = MaterialTheme.typography.titleSmall,
                                color = glass.text,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(Icons.Rounded.CloudDownload, null, tint = glass.accent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotRow(
    label: String,
    activePath: String?,
    loading: Boolean,
    loadedPath: String?,
) {
    val glass = LocalGlassColors.current
    GlassPanel(corner = 18.dp, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = glass.text,
                modifier = Modifier.weight(1f),
            )
            when {
                loading -> Text("loading…", style = MaterialTheme.typography.labelSmall, color = glass.accent)
                activePath != null && loadedPath == activePath ->
                    Icon(Icons.Rounded.Verified, null, tint = glass.accent)
                else -> Text("—", style = MaterialTheme.typography.labelSmall, color = glass.textMuted)
            }
        }
        val file = activePath?.let { java.io.File(it) }
        Text(
            when {
                loading -> "Loading…"
                file != null && file.exists() -> file.name + " · " + formatBytes(file.length())
                else -> "Not set"
            },
            style = MaterialTheme.typography.bodySmall,
            color = glass.textMuted,
            modifier = Modifier.padding(top = 4.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
