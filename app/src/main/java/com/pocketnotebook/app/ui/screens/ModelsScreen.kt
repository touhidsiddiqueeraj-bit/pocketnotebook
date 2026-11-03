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
import com.pocketnotebook.app.ui.timeAgo
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

    LaunchedEffect(Unit) {
        while (true) {
            if (runningInfos.isNotEmpty()) reload()
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
            val size = container.hfApi.fileSize(repoId, fileName)
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
                    if (container.runtime.nativeReady) "engine ready" else "kit build",
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
                    "ACTIVE SLOTS",
                    style = MaterialTheme.typography.labelSmall,
                    color = glass.textMuted,
                    modifier = Modifier.padding(start = 6.dp, top = 4.dp),
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SlotRow("Chat brain", ModelSlot.CHAT, runtimeState.chatLoading, runtimeState.chatPath, runtimeState.chatMeta?.desc)
                    SlotRow("Embeddings", ModelSlot.EMBED, runtimeState.embedLoading, runtimeState.embedPath, null)
                    SlotRow(
                        "Speech (LFM3-Audio · Phase 0)",
                        ModelSlot.AUDIO, false,
                        container.prefs.activeModelPath(ModelSlot.AUDIO),
                        "Pre-download now; voice features activate after the Phase 0 native wiring",
                    )
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
                                "${m.repoId} · ${formatBytes(m.sizeBytes)} · added ${timeAgo(m.addedAt)}",
                                style = MaterialTheme.typography.labelSmall, color = glass.textMuted,
                                maxLines = 1,
                            )
                        }
                        if (container.prefs.activeModelPath(m.slot) == m.filePath) {
                            Icon(Icons.Rounded.Verified, "active", tint = glass.accent)
                        } else {
                            GlassChip("Activate", onClick = {
                                container.prefs.setActiveModel(m.slot, m.filePath)
                                if (m.slot == ModelSlot.CHAT) scope.launch { container.runtime.unloadChat() }
                                reload()
                            })
                        }
                    }
                }
            }

            // ---- curated catalog ----
            item {
                Text(
                    "CURATED (VERIFIED)",
                    style = MaterialTheme.typography.labelSmall,
                    color = glass.textMuted,
                    modifier = Modifier.padding(start = 6.dp, top = 8.dp),
                )
            }
            items(container.modelCatalog.entries, key = { it.id }) { entry ->
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
                    Text(
                        entry.blurb,
                        style = MaterialTheme.typography.bodySmall,
                        color = glass.textMuted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        "${entry.license} · huggingface.co/${entry.repoId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = glass.textMuted.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
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

            // ---- arbitrary HF search ----
            item {
                Text(
                    "ANY HF GGUF REPO",
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
                        placeholder = "e.g. LiquidAI/LFM3-1.7B-GGUF",
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    GlassIconButton(Icons.Rounded.Search, contentDescription = "Search") { search() }
                }
                Text(
                    "Unverified repos may not load — the engine checks GGUF metadata at load time.",
                    style = MaterialTheme.typography.labelSmall,
                    color = glass.textMuted.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 6.dp),
                )
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
                            "downloads ${hit.downloads} · likes ${hit.likes}",
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
                    "Slot for the download:",
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
    slot: String,
    loading: Boolean,
    activePath: String?,
    metaLine: String?,
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
            if (loading) {
                Text("loading…", style = MaterialTheme.typography.labelSmall, color = glass.accent)
            } else if (activePath != null) {
                Icon(Icons.Rounded.Verified, null, tint = glass.accent)
            } else {
                Text("empty", style = MaterialTheme.typography.labelSmall, color = glass.textMuted)
            }
        }
        val file = activePath?.let { java.io.File(it) }
        Text(
            when {
                loading -> "Warming up the mmap…"
                file != null && file.exists() -> file.name + " · " + formatBytes(file.length())
                else -> metaLine ?: "No model installed for this slot."
            },
            style = MaterialTheme.typography.bodySmall,
            color = glass.textMuted,
            modifier = Modifier.padding(top = 4.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
