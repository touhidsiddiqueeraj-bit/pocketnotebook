package com.pocketnotebook.app.ui.screens

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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.pocketnotebook.app.core.db.ChatMessage
import com.pocketnotebook.app.core.db.Citation
import com.pocketnotebook.app.core.db.ModelSlot
import com.pocketnotebook.app.core.llm.Thinking
import com.pocketnotebook.app.ui.Routes
import com.pocketnotebook.app.ui.rememberContainer
import com.pocketnotebook.app.ui.theme.GlassButton
import com.pocketnotebook.app.ui.theme.GlassChip
import com.pocketnotebook.app.ui.theme.GlassIconButton
import com.pocketnotebook.app.ui.theme.GlassPanel
import com.pocketnotebook.app.ui.theme.GlassTextField
import com.pocketnotebook.app.ui.theme.LocalGlassColors
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

@Composable
fun ChatTab(notebookId: Long, nav: NavHostController) {
    val container = rememberContainer()
    val glass = LocalGlassColors.current
    val scope = rememberCoroutineScope()

    var messages by remember { mutableStateOf(container.chatRepo.recent(notebookId)) }
    var input by remember { mutableStateOf("") }
    var streamText by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastQuestion by remember { mutableStateOf<String?>(null) }
    val runtimeState by container.runtime.state.collectAsState()

    // A model is "set" when one is installed+active — independent of whether
    // the runtime has finished loading it into memory yet.
    val modelSet = remember { container.runtime.modelPathFor(ModelSlot.CHAT) != null }

    // Warm the engine as soon as the tab opens so the first question does not
    // pay the load cost (and so "ready" is visible).
    LaunchedEffect(Unit) {
        if (container.runtime.nativeReady && modelSet) {
            container.runtime.ensureChatLoaded()
        }
    }

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
        if (question.isBlank() || runtimeState.generating || !modelSet) return
        input = ""
        error = null
        lastQuestion = question
        streamText = ""
        scope.launch {
            messages = container.chatRepo.recent(notebookId) + ChatMessage(
                id = -1, notebookId = notebookId, role = "user",
                content = question, cites = null, createdAt = System.currentTimeMillis(),
            )
            try {
                container.chatService.ask(notebookId, question) { piece -> tokenChannel.trySend(piece) }
            } catch (t: Throwable) {
                // Show WHY it failed instead of silently swallowing — an
                // unloaded model used to look like a dead send button.
                error = (t.message ?: "generation failed").take(220)
            }
            streamText = null
            messages = container.chatRepo.recent(notebookId)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        // ---------------- status strip ----------------
        when {
            error != null -> Banner(error ?: "") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton("Retry", primary = true, onClick = {
                        lastQuestion?.let { send(it) }
                    }, modifier = Modifier.weight(1f))
                    GlassButton("Dismiss", onClick = { error = null }, modifier = Modifier.weight(1f))
                }
            }
            !container.runtime.nativeReady -> Banner("Kit build — no AI engine.") {
                GlassButton("Models", onClick = { nav.navigate(Routes.MODELS) })
            }
            !modelSet -> Banner("No chat model.") {
                GlassButton("Get model", primary = true, onClick = { nav.navigate(Routes.MODELS) })
            }
            runtimeState.chatLoading -> Banner("Loading model…")
            runtimeState.chatPath == null && !runtimeState.generating ->
                Banner((runtimeState.chatError ?: "Model not loaded.").take(240)) {
                    GlassButton("Retry", onClick = { scope.launch { container.runtime.ensureChatLoaded() } })
                }
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
                )
            }
            if (streamText != null) {
                item(key = "streaming") {
                    Column {
                        GlassPanel(corner = 22.dp, modifier = Modifier.widthIn(max = 340.dp)) {
                            Text(
                                // Hide any reasoning artifacts that slip through
                                // the native think filter while streaming.
                                Thinking.strip(streamText ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                                color = glass.text,
                            )
                        }
                    }
                }
            }
            if (messages.isEmpty() && streamText == null && modelSet && !runtimeState.chatLoading) {
                item(key = "hint") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 96.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Ask anything",
                            style = MaterialTheme.typography.titleMedium,
                            color = glass.textMuted,
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
                    placeholder = if (modelSet) "Ask" else "No model",
                    enabled = modelSet && !runtimeState.generating,
                )
            }
            if (runtimeState.generating) {
                GlassIconButton(Icons.Rounded.Stop, contentDescription = "Stop", tint = glass.accent3) {
                    container.runtime.abortGeneration()
                }
            } else {
                GlassIconButton(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send", tint = glass.accent) {
                    send(input)
                }
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
            ) {
                Text(
                    msg.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = glass.text,
                )
            }
            if (cites.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                ) {
                    cites.take(8).forEach { c ->
                        GlassChip(
                            text = "[${c.n}]",
                            onClick = { onCiteTap(c.chunkId, c.sourceId) },
                        )
                    }
                }
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
