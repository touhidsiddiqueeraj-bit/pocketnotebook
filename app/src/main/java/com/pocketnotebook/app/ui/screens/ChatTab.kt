package com.pocketnotebook.app.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import com.pocketnotebook.app.core.db.ChatMessage
import com.pocketnotebook.app.core.db.Citation
import com.pocketnotebook.app.core.db.ModelSlot
import com.pocketnotebook.app.ui.Routes
import com.pocketnotebook.app.ui.timeAgo
import com.pocketnotebook.app.ui.rememberContainer
import com.pocketnotebook.app.ui.theme.GlassButton
import com.pocketnotebook.app.ui.theme.GlassChip
import com.pocketnotebook.app.ui.theme.GlassIconButton
import com.pocketnotebook.app.ui.theme.GlassPanel
import com.pocketnotebook.app.ui.theme.GlassTextField
import com.pocketnotebook.app.ui.theme.LocalGlassColors
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

@Composable
fun ChatTab(notebookId: Long, nav: NavHostController) {
    val container = rememberContainer()
    val glass = LocalGlassColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val json = remember { Json { ignoreUnknownKeys = true } }

    var messages by remember { mutableStateOf(container.chatRepo.recent(notebookId)) }
    var input by remember { mutableStateOf("") }
    var streamText by remember { mutableStateOf<String?>(null) }
    val runtimeState by container.runtime.state.collectAsState()

    val tokenChannel = remember { Channel<String>(Channel.UNLIMITED) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        for (piece in tokenChannel) {
            streamText = (streamText ?: "") + piece
        }
    }
    LaunchedEffect(messages.size, streamText) {
        val last = messages.size + (if (streamText != null) 1 else 0) - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    fun send(question: String) {
        if (question.isBlank() || runtimeState.generating) return
        input = ""
        streamText = ""
        scope.launch {
            messages = container.chatRepo.recent(notebookId) + ChatMessage(
                id = -1, notebookId = notebookId, role = "user",
                content = question, cites = null, createdAt = System.currentTimeMillis(),
            )
            try {
                container.chatService.ask(notebookId, question) { piece -> tokenChannel.trySend(piece) }
            } catch (_: Throwable) {
            }
            streamText = null
            messages = container.chatRepo.recent(notebookId)
        }
    }

    // --- voice input (uses the device speech service; may be cloud) ----------
    val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra("android.speech.extra.RESULTS")
            ?.firstOrNull()
        if (!text.isNullOrBlank()) input = (input + " " + text).trim()
    }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) speechLauncher.launch(speechIntent) }

    fun startMic() {
        val activity = context as? Activity
        if (activity != null &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            try { speechLauncher.launch(speechIntent) } catch (_: Throwable) { }
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        // ---------------- model availability banners ----------------
        val chatReady = runtimeState.chatPath != null
        if (!container.runtime.nativeReady) {
            Banner("Kit build — no AI engine. Install a release APK.") {
                GlassButton("Models", onClick = { nav.navigate(Routes.MODELS) })
            }
        } else if (!chatReady && !runtimeState.chatLoading) {
            Banner("No chat model yet.") {
                GlassButton("Get model", primary = true, onClick = { nav.navigate(Routes.MODELS) })
            }
        } else if (runtimeState.chatLoading) {
            Banner("Loading model…")
        }

        // ---------------- conversation ----------------
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 18.dp, end = 18.dp, top = 8.dp, bottom = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(messages, key = { m -> if (m.id == -1L) -m.createdAt else m.id }) { msg ->
                MessageBubble(
                    msg, glass.text, glass.textMuted,
                    onCiteTap = { chunkId, sourceId ->
                        nav.navigate(Routes.source(sourceId, chunkId))
                    },
                    onSaveAsNote = { content ->
                        container.noteRepo.add(notebookId, content)
                    },
                )
            }
            if (streamText != null) {
                item(key = "streaming") {
                    Column {
                        GlassPanel(corner = 22.dp, modifier = Modifier.widthIn(max = 340.dp)) {
                            Text(
                                streamText ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = glass.text,
                            )
                        }
                        Text(
                            "…",
                            style = MaterialTheme.typography.labelSmall,
                            color = glass.accent,
                            modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                        )
                    }
                }
            }
        }

        // ---------------- input bar ----------------
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.weight(1f)) {
                GlassTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = if (chatReady) "Ask…" else "No model",
                    enabled = chatReady && !runtimeState.generating,
                )
            }
            if (runtimeState.generating) {
                GlassIconButton(Icons.Rounded.Stop, contentDescription = "Stop", tint = glass.accent3) {
                    container.runtime.abortGeneration()
                }
            } else {
                GlassIconButton(Icons.Rounded.Mic, contentDescription = "Voice input") { startMic() }
                GlassIconButton(
                    Icons.AutoMirrored.Rounded.Send,
                    contentDescription = "Send",
                    tint = glass.accent,
                ) { send(input) }
            }
        }
    }
}

@Composable
private fun Banner(text: String, action: (@Composable () -> Unit)? = null) {
    GlassPanel(corner = 20.dp, modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 18.dp, vertical = 8.dp)) {
        if (action != null) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text, style = MaterialTheme.typography.bodySmall, color = LocalGlassColors.current.textMuted)
                action()
            }
        } else {
            Text(text, style = MaterialTheme.typography.bodySmall, color = LocalGlassColors.current.textMuted)
        }
    }
}

@Composable
private fun MessageBubble(
    msg: ChatMessage,
    text: androidx.compose.ui.graphics.Color,
    textMuted: androidx.compose.ui.graphics.Color,
    onCiteTap: (chunkId: Long, sourceId: Long) -> Unit,
    onSaveAsNote: (String) -> Unit,
) {
    val glass = LocalGlassColors.current
    val isUser = msg.role == "user"
    val cites: List<Citation> = remember(msg.cites) {
        msg.cites?.let {
            runCatching { jsonCites(it) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            GlassPanel(
                corner = 22.dp,
                fillTop = if (isUser) glass.accent.copy(alpha = 0.20f) else null,
                fillBottom = if (isUser) glass.accent2.copy(alpha = 0.10f) else null,
                modifier = Modifier.widthIn(max = 330.dp),
                onLongClick = { if (!isUser) onSaveAsNote(msg.content) },
            ) {
                Text(
                    msg.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isUser) glass.text else glass.text,
                )
            }
            if (cites.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                ) {
                    cites.take(8).forEach { c ->
                        GlassChip(
                            text = "[${c.n}] ${c.sourceTitle.take(14)}${if (c.sourceTitle.length > 14) "…" else ""}",
                            onClick = { onCiteTap(c.chunkId, c.sourceId) },
                        )
                    }
                }
            } else if (!isUser) {
                Text(
                    timeAgo(msg.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = textMuted,
                    modifier = Modifier.padding(start = 10.dp, top = 2.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun jsonCites(raw: String): List<Citation> =
    kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        .decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(Citation.serializer()),
            raw,
        )
