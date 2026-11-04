package com.pocketnotebook.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.pocketnotebook.app.core.db.Notebook
import com.pocketnotebook.app.ui.Routes
import com.pocketnotebook.app.ui.GlassScaffold
import com.pocketnotebook.app.ui.GlassDialog
import com.pocketnotebook.app.ui.rememberContainer
import com.pocketnotebook.app.ui.timeAgo
import com.pocketnotebook.app.ui.theme.GlassButton
import com.pocketnotebook.app.ui.theme.GlassIconButton
import com.pocketnotebook.app.ui.theme.GlassPanel
import com.pocketnotebook.app.ui.theme.GlassRow
import com.pocketnotebook.app.ui.theme.GlassTextField
import com.pocketnotebook.app.ui.theme.LocalGlassColors

@Composable
fun HomeScreen(nav: NavHostController) {
    val container = rememberContainer()
    val glass = LocalGlassColors.current

    var notebooks by remember { mutableStateOf(container.notebookRepo.listAll()) }
    var showCreate by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf<Notebook?>(null) }

    fun reload() { notebooks = container.notebookRepo.listAll() }

    GlassScaffold(
        top = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "PocketNotebook",
                    style = MaterialTheme.typography.headlineMedium,
                    color = glass.text,
                    modifier = Modifier.weight(1f),
                )
                GlassIconButton(Icons.Rounded.Memory, contentDescription = "Models") { nav.navigate("models") }
            }
        },
        bottom = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                GlassIconButton(Icons.Rounded.Memory, contentDescription = "Models") { nav.navigate("models") }
                GlassIconButton(Icons.Rounded.Add, contentDescription = "New notebook") { showCreate = true }
                GlassIconButton(Icons.Rounded.Settings, contentDescription = "Settings") { nav.navigate("settings") }
            }
        },
    ) {
        if (notebooks.isEmpty()) {
            com.pocketnotebook.app.ui.EmptyState(
                icon = Icons.Rounded.Add,
                title = "No notebooks",
                body = "Tap + to create one.",
            )
        } else {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(notebooks, key = { it.id }) { nb ->
                    GlassRow(
                        onClick = { nav.navigate(Routes.notebook(nb.id)) },
                        onLongClick = { showDelete = nb },
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                nb.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = glass.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${container.sourceRepo.countForNotebook(nb.id)} sources · ${timeAgo(nb.updatedAt)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = glass.textMuted,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = glass.textMuted)
                    }
                }
            }
        }
    }

    if (showCreate) {
        var title by remember { mutableStateOf("") }
        GlassDialog(onDismiss = { showCreate = false }, title = "New notebook") {
            GlassTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = "Notebook title",
                singleLine = true,
            )
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("Cancel", onClick = { showCreate = false }, modifier = Modifier.weight(1f))
                GlassButton("Create", primary = true, onClick = {
                    if (title.isNotBlank()) {
                        val id = container.notebookRepo.create(title.trim())
                        showCreate = false
                        reload()
                        nav.navigate(Routes.notebook(id))
                    }
                }, modifier = Modifier.weight(1f))
            }
        }
    }

    showDelete?.let { nb ->
        GlassDialog(onDismiss = { showDelete = null }, title = "Delete \"${nb.title}\"?") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("Keep", onClick = { showDelete = null }, modifier = Modifier.weight(1f))
                GlassButton("Delete", onClick = {
                    container.notebookRepo.delete(nb.id)
                    showDelete = null
                    reload()
                }, modifier = Modifier.weight(1f))
            }
        }
    }
}
