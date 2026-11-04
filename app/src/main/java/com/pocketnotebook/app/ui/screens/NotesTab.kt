package com.pocketnotebook.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketnotebook.app.core.db.Note
import com.pocketnotebook.app.ui.GlassDialog
import com.pocketnotebook.app.ui.timeAgo
import com.pocketnotebook.app.ui.rememberContainer
import com.pocketnotebook.app.ui.theme.GlassButton
import com.pocketnotebook.app.ui.theme.GlassIconButton
import com.pocketnotebook.app.ui.theme.GlassRow
import com.pocketnotebook.app.ui.theme.GlassTextField
import com.pocketnotebook.app.ui.theme.LocalGlassColors

/** The noteboard: pinned notes first, add / pin / delete, plain text. */
@Composable
fun NotesTab(notebookId: Long) {
    val container = rememberContainer()
    val glass = LocalGlassColors.current

    var notes by remember { mutableStateOf(container.noteRepo.list(notebookId)) }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Note?>(null) }

    fun reload() { notes = container.noteRepo.list(notebookId) }

    Column(Modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                "${notes.size} notes",
                style = MaterialTheme.typography.labelSmall,
                color = glass.textMuted,
                modifier = Modifier.padding(0.dp),
            )
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            GlassIconButton(Icons.Rounded.Add, contentDescription = "Add note") { showAdd = true }
        }

        if (notes.isEmpty()) {
            com.pocketnotebook.app.ui.EmptyState(
                icon = Icons.Rounded.PushPin,
                title = "No notes",
                body = "Save thoughts and chat answers here.",
            )
        } else {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(notes, key = { it.id }) { n ->
                    GlassRow(
                        onClick = { editing = n },
                        onLongClick = {
                            if (n.pinned) container.noteRepo.setPinned(n.id, false)
                            else container.noteRepo.setPinned(n.id, true)
                            reload()
                        },
                    ) {
                        if (n.pinned) {
                            androidx.compose.material3.Icon(
                                Icons.Rounded.PushPin, null,
                                tint = glass.accent,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                n.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = glass.text,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                timeAgo(n.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = glass.textMuted,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        var text by remember { mutableStateOf("") }
        GlassDialog(onDismiss = { showAdd = false; reload() }, title = "New note") {
            GlassTextField(value = text, onValueChange = { text = it }, placeholder = "Write a thought…")
            androidx.compose.foundation.layout.Row(
                Modifier.padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GlassButton("Cancel", onClick = { showAdd = false; reload() }, modifier = Modifier.weight(1f))
                GlassButton("Save", primary = true, onClick = {
                    if (text.isNotBlank()) {
                        container.noteRepo.add(notebookId, text.trim())
                    }
                    showAdd = false
                    reload()
                }, modifier = Modifier.weight(1f))
            }
        }
    }

    editing?.let { n ->
        var text by remember(editing) { mutableStateOf(n.text) }
        GlassDialog(onDismiss = { editing = null; reload() }, title = if (n.pinned) "Pinned note" else "Note") {
            GlassTextField(value = text, onValueChange = { text = it }, placeholder = "Note")
            androidx.compose.foundation.layout.Row(
                Modifier.padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GlassButton("Delete", onClick = {
                    container.noteRepo.delete(n.id)
                    editing = null
                    reload()
                }, modifier = Modifier.weight(1f))
                GlassButton("Save", primary = true, onClick = {
                    if (text.isNotBlank()) {
                        container.noteRepo.delete(n.id)
                        container.noteRepo.add(notebookId, text.trim(), n.pinned)
                    }
                    editing = null
                    reload()
                }, modifier = Modifier.weight(1f))
            }
        }
    }
}
