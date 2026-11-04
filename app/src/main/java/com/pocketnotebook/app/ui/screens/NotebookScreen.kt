package com.pocketnotebook.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.Source
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.pocketnotebook.app.ui.GlassScaffold
import com.pocketnotebook.app.ui.GlassDialog
import com.pocketnotebook.app.ui.rememberContainer
import com.pocketnotebook.app.ui.theme.GlassButton
import com.pocketnotebook.app.ui.theme.GlassIconButton
import com.pocketnotebook.app.ui.theme.GlassRow
import com.pocketnotebook.app.ui.theme.GlassTabBar
import com.pocketnotebook.app.ui.theme.GlassTextField
import com.pocketnotebook.app.ui.theme.LocalGlassColors

private data class TabSpec(val label: String, val icon: ImageVector)

@Composable
fun NotebookScreen(notebookId: Long, nav: NavHostController) {
    val container = rememberContainer()
    val glass = LocalGlassColors.current

    var notebook by remember { mutableStateOf(container.notebookRepo.get(notebookId)) }
    var tab by remember { mutableIntStateOf(0) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    val tabs = listOf(
        TabSpec("Chat", Icons.Rounded.Chat),
        TabSpec("Sources", Icons.Rounded.Source),
        TabSpec("Studio", Icons.Rounded.Dashboard),
        TabSpec("Notes", Icons.Rounded.Notes),
    )

    fun reload() { notebook = container.notebookRepo.get(notebookId) }

    GlassScaffold(
        top = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                ) { nav.popBackStack() }
                Text(
                    notebook?.title ?: "",
                    style = MaterialTheme.typography.titleLarge,
                    color = glass.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                )
                GlassIconButton(
                    Icons.Rounded.MoreVert,
                    contentDescription = "Notebook options",
                ) { showRename = true }
            }
        },
        bottom = {
            GlassTabBar(
                items = tabs.map { it.label to it.icon },
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            )
        },
    ) {
        when (tab) {
            0 -> ChatTab(notebookId = notebookId, nav = nav)
            1 -> SourcesTab(notebookId = notebookId, nav = nav)
            2 -> StudioTab(notebookId = notebookId, nav = nav)
            3 -> NotesTab(notebookId = notebookId)
        }
    }

    if (showRename) {
        var title by remember(notebook) { mutableStateOf(notebook?.title ?: "") }
        GlassDialog(onDismiss = { showRename = false; showDelete = false; reload() }, title = "Notebook") {
            GlassTextField(value = title, onValueChange = { title = it }, placeholder = "Title", singleLine = true)
            Row(Modifier.padding(top = 14.dp)) {
                GlassButton("Delete", onClick = { showDelete = true; showRename = false }, modifier = Modifier.weight(1f))
                GlassButton("Save", primary = true, onClick = {
                    if (title.isNotBlank()) {
                        container.notebookRepo.rename(notebookId, title.trim())
                    }
                    showRename = false
                    reload()
                }, modifier = Modifier.weight(1f))
            }
        }
    }

    if (showDelete) {
        GlassDialog(onDismiss = { showDelete = false; reload() }, title = "Delete this notebook?") {
            Row {
                GlassButton("Keep", onClick = { showDelete = false; reload() }, modifier = Modifier.weight(1f))
                GlassButton("Delete", onClick = {
                    container.notebookRepo.delete(notebookId)
                    showDelete = false
                    nav.popBackStack()
                }, modifier = Modifier.weight(1f))
            }
        }
    }
}
