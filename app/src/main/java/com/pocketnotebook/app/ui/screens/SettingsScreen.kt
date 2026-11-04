package com.pocketnotebook.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import java.io.File
import com.pocketnotebook.app.core.db.ModelSlot
import com.pocketnotebook.app.ui.GlassScaffold
import com.pocketnotebook.app.ui.formatBytes
import com.pocketnotebook.app.ui.rememberContainer
import com.pocketnotebook.app.ui.theme.GlassButton
import com.pocketnotebook.app.ui.theme.GlassChip
import com.pocketnotebook.app.ui.theme.GlassIconButton
import com.pocketnotebook.app.ui.theme.GlassPanel
import com.pocketnotebook.app.ui.theme.LocalGlassColors
import com.pocketnotebook.app.ui.theme.SectionLabel
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(nav: NavHostController) {
    val container = rememberContainer()
    val glass = LocalGlassColors.current
    val prefs = container.prefs

    var theme by remember { mutableStateOf(prefs.themeMode) }
    var temp by remember { mutableStateOf(prefs.temperature) }
    var ctxLen by remember { mutableStateOf(prefs.contextLength) }
    var maxTok by remember { mutableStateOf(prefs.maxTokens) }
    var storage by remember { mutableStateOf(dirSize(container.runtime.modelsDir())) }
    val runtimeState by container.runtime.state.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

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
                    "Settings",
                    style = MaterialTheme.typography.titleLarge,
                    color = glass.text,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---------------- theme ----------------
            SectionLabel("Appearance")
            GlassPanel(corner = 20.dp, modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip("System", selected = theme == "system", onClick = { theme = "system"; prefs.themeMode = "system" })
                    GlassChip("Liquid dark", selected = theme == "dark", onClick = { theme = "dark"; prefs.themeMode = "dark" })
                    GlassChip("Porcelain", selected = theme == "light", onClick = { theme = "light"; prefs.themeMode = "light" })
                }
            }

            // ---------------- generation ----------------
            SectionLabel("Generation")
            GlassPanel(corner = 20.dp, modifier = Modifier.fillMaxWidth()) {
                Text("Temperature — ${"%.2f".format(temp)}", style = MaterialTheme.typography.titleSmall, color = glass.text)
                androidx.compose.material3.Slider(
                    value = temp,
                    onValueChange = { temp = it; prefs.temperature = it },
                    valueRange = 0.1f..1.2f,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = glass.accent,
                        activeTrackColor = glass.accent,
                        inactiveTrackColor = glass.fillBottom,
                    ),
                )
                Text("Context window", style = MaterialTheme.typography.titleSmall, color = glass.text, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    GlassChip("4k", selected = ctxLen == 4096, onClick = { ctxLen = 4096; prefs.contextLength = 4096 })
                    GlassChip("8k", selected = ctxLen == 8192, onClick = { ctxLen = 8192; prefs.contextLength = 8192 })
                    GlassChip("16k", selected = ctxLen == 16384, onClick = { ctxLen = 16384; prefs.contextLength = 16384 })
                }
                Text("Answer budget — $maxTok tokens", style = MaterialTheme.typography.titleSmall, color = glass.text, modifier = Modifier.padding(top = 10.dp))
                androidx.compose.material3.Slider(
                    value = maxTok.toFloat(),
                    onValueChange = { maxTok = it.roundToInt(); prefs.maxTokens = maxTok },
                    valueRange = 256f..1280f,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = glass.accent,
                        activeTrackColor = glass.accent,
                        inactiveTrackColor = glass.fillBottom,
                    ),
                )
            }

            // ---------------- engine ----------------
            SectionLabel("Engine")
            GlassPanel(corner = 20.dp, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (runtimeState.chatPath != null) "Chat model: ${runtimeState.chatMeta?.desc ?: "loaded"}"
                    else "Chat model: not loaded",
                    style = MaterialTheme.typography.bodyMedium, color = glass.text,
                )
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton("Diagnostics", onClick = {
                        nav.navigate(com.pocketnotebook.app.ui.Routes.DIAGNOSTICS)
                    })
                }
            }

            // ---------------- storage ----------------
            SectionLabel("Storage")
            GlassPanel(corner = 20.dp, modifier = Modifier.fillMaxWidth()) {
                Text("Models on device: ${formatBytes(storage)}", style = MaterialTheme.typography.bodyMedium, color = glass.text)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton("Unload chat model", onClick = {
                        scope.launch { container.runtime.unloadChat() }
                    })
                    GlassButton("Delete all models", onClick = {
                        container.runtime.modelsDir().walk().filter { it.isFile }.forEach { it.delete() }
                        for (slot in listOf(ModelSlot.CHAT, ModelSlot.EMBED, ModelSlot.AUDIO)) {
                            container.prefs.setActiveModel(slot, null)
                        }
                        storage = dirSize(container.runtime.modelsDir())
                    })
                }
            }

            // ---------------- about ----------------
            SectionLabel("About")
            GlassPanel(corner = 20.dp, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "PocketNotebook — an offline NotebookLM clone.",
                    style = MaterialTheme.typography.bodyMedium, color = glass.text,
                )
                Text(
                    "llama.cpp (MIT) · LFM3 (LFM Open License) · bge-small (MIT)",
                    style = MaterialTheme.typography.bodySmall, color = glass.textMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

private fun dirSize(dir: File): Long =
    dir.walk().filter { it.isFile }.sumOf { it.length() }
