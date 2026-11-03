package com.pocketnotebook.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Notes
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.pocketnotebook.app.core.db.Source
import com.pocketnotebook.app.core.db.SourceStatus
import com.pocketnotebook.app.core.work.WorkScheduler
import com.pocketnotebook.app.ui.GlassDialog
import com.pocketnotebook.app.ui.Routes
import com.pocketnotebook.app.ui.EmptyState
import com.pocketnotebook.app.ui.timeAgo
import com.pocketnotebook.app.ui.rememberContainer
import com.pocketnotebook.app.ui.theme.GlassButton
import com.pocketnotebook.app.ui.theme.GlassPanel
import com.pocketnotebook.app.ui.theme.GlassRow
import com.pocketnotebook.app.ui.theme.GlassTextField
import com.pocketnotebook.app.ui.theme.LocalGlassColors
import com.pocketnotebook.app.ui.theme.StatusDot
import kotlinx.coroutines.delay

private const val MAX_SOURCES = 50

@Composable
fun SourcesTab(notebookId: Long, nav: NavHostController) {
    val container = rememberContainer()
    val glass = LocalGlassColors.current
    val context = LocalContext.current

    var sources by remember { mutableStateOf(container.sourceRepo.listForNotebook(notebookId)) }
    var showUrl by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun reload() { sources = container.sourceRepo.listForNotebook(notebookId) }

    // Poll while any source is pending/processing so rows update as the
    // ingest worker finishes (no event bus needed for v0.1).
    LaunchedEffect(Unit) {
        while (true) {
            if (sources.any { it.status == SourceStatus.PENDING || it.status == SourceStatus.PROCESSING }) {
                reload()
            }
            delay(1500)
        }
    }

    fun addPicked(uri: Uri, name: String) {
        if (sources.size >= MAX_SOURCES) {
            notice = "NotebookLM parity: $MAX_SOURCES sources max per notebook."
            return
        }
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: Throwable) {
        }
        val id = container.sourceRepo.create(notebookId, "file", name)
        container.sourceRepo.putPending(id, uri = uri.toString())
        WorkScheduler.enqueueIngest(context, id)
        reload()
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "Document"
            addPicked(uri, name)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassButton("File", icon = Icons.Rounded.Description, onClick = { filePicker.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f))
            GlassButton("URL", icon = Icons.Rounded.Link, onClick = { showUrl = true }, modifier = Modifier.weight(1f))
            GlassButton("Paste", icon = Icons.Rounded.Notes, onClick = { showPaste = true }, modifier = Modifier.weight(1f))
        }

        notice?.let {
            GlassPanel(
                corner = 16.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
            ) {
                Text(it, style = MaterialTheme.typography.bodySmall, color = glass.accent3)
            }
        }

        if (sources.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.Description,
                title = "No sources yet",
                body = "Add PDFs, DOCX, TXT, Markdown, web pages — or paste text/transcripts. Up to $MAX_SOURCES sources, processed entirely on-device.",
            )
        } else {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(sources, key = { it.id }) { src ->
                    GlassRow(
                        onClick = { nav.navigate(Routes.source(src.id)) },
                        onLongClick = { container.sourceRepo.delete(src.id); reload() },
                    ) {
                        Icon(sourceIcon(src.type), null, tint = glass.accent2, modifier = Modifier.size(20.dp))
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp),
                        ) {
                            Text(
                                src.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = glass.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                sourceMeta(src),
                                style = MaterialTheme.typography.labelSmall,
                                color = glass.textMuted,
                                modifier = Modifier.padding(top = 1.dp),
                                maxLines = 1,
                            )
                        }
                        when (src.status) {
                            SourceStatus.PENDING, SourceStatus.PROCESSING ->
                                StatusDot(glass.accent2.copy(alpha = 0.8f))
                            SourceStatus.FAILED -> StatusDot(glass.accent3)
                            SourceStatus.READY -> StatusDot(glass.accent)
                        }
                    }
                }
            }
        }
        Text(
            "Long-press a source to delete it.",
            style = MaterialTheme.typography.labelSmall,
            color = glass.textMuted.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
        )
    }

    // ---------------- URL dialog ----------------
    if (showUrl) {
        var url by remember { mutableStateOf("") }
        GlassDialog(onDismiss = { showUrl = false }, title = "Add web page") {
            GlassTextField(value = url, onValueChange = { url = it }, placeholder = "https://…", singleLine = true)
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("Cancel", onClick = { showUrl = false }, modifier = Modifier.weight(1f))
                GlassButton("Fetch", primary = true, onClick = {
                    if (url.isNotBlank()) {
                        val id = container.sourceRepo.create(notebookId, "url", url.take(120))
                        container.sourceRepo.putPending(id, url = url.trim())
                        WorkScheduler.enqueueIngest(context, id)
                        showUrl = false
                        reload()
                    }
                }, modifier = Modifier.weight(1f))
            }
        }
    }

    // ---------------- paste dialog ----------------
    if (showPaste) {
        var text by remember { mutableStateOf("") }
        var title by remember { mutableStateOf("") }
        GlassDialog(onDismiss = { showPaste = false }, title = "Paste text") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassTextField(value = title, onValueChange = { title = it }, placeholder = "Title (optional)", singleLine = true)
                GlassTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = "Paste text or a transcript…",
                    modifier = Modifier.heightIn(min = 170.dp),
                )
            }
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("Cancel", onClick = { showPaste = false }, modifier = Modifier.weight(1f))
                GlassButton("Add", primary = true, onClick = {
                    if (text.isNotBlank()) {
                        val id = container.sourceRepo.create(
                            notebookId, "pasted",
                            title.ifBlank { "Pasted " + timeAgo(System.currentTimeMillis()) },
                        )
                        container.sourceRepo.putPending(id, text = text)
                        WorkScheduler.enqueueIngest(context, id)
                        showPaste = false
                        reload()
                    }
                }, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun sourceIcon(type: String) = when (type) {
    "url", "html" -> Icons.Rounded.Link
    "pasted" -> Icons.Rounded.Notes
    else -> Icons.Rounded.Description
}

private fun sourceMeta(src: Source): String = when (src.status) {
    SourceStatus.FAILED -> "Failed: ${src.error ?: "unknown"}".take(90)
    SourceStatus.PENDING -> "Queued…"
    SourceStatus.PROCESSING -> "Processing on-device…"
    else -> "${src.wordCount} words · ${src.chunkCount} chunks · ${timeAgo(src.addedAt)}"
}
