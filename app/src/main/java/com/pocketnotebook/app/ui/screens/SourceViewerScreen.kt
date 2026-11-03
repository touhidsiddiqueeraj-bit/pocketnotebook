package com.pocketnotebook.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.pocketnotebook.app.core.db.SourceStatus
import com.pocketnotebook.app.ui.rememberContainer
import com.pocketnotebook.app.ui.theme.GlassPanel
import com.pocketnotebook.app.ui.theme.LocalGlassColors

/** Source viewer: chunk flow with the cited chunk highlighted. */
@Composable
fun SourceViewerScreen(sourceId: Long, highlightChunkId: Long, nav: NavHostController) {
    val container = rememberContainer()
    val glass = LocalGlassColors.current

    var source by remember { mutableStateOf(container.sourceRepo.get(sourceId)) }
    var chunks by remember { mutableStateOf(container.sourceRepo.listChunks(sourceId)) }
    val listState = rememberLazyListState()
    val highlightIndex = chunks.indexOfFirst { it.id == highlightChunkId }

    androidx.compose.runtime.LaunchedEffect(highlightIndex) {
        if (highlightIndex >= 0) {
            listState.scrollToItem(highlightIndex.coerceAtLeast(0))
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            source?.title ?: "",
            style = MaterialTheme.typography.headlineSmall,
            color = glass.text,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
        )
        Text(
            source?.let {
                when (it.status) {
                    SourceStatus.FAILED -> "Failed: ${it.error}"
                    SourceStatus.PENDING -> "Queued for processing…"
                    SourceStatus.PROCESSING -> "Processing on-device…"
                    else -> "${it.wordCount} words · ${it.chunkCount} passages · ${it.type}"
                }
            } ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = glass.textMuted,
            modifier = Modifier.padding(horizontal = 22.dp),
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(chunks, key = { it.id }) { chunk ->
                val highlighted = chunk.id == highlightChunkId
                GlassPanel(
                    corner = 20.dp,
                    fillTop = if (highlighted) glass.accent.copy(alpha = 0.18f) else null,
                    fillBottom = if (highlighted) glass.accent2.copy(alpha = 0.08f) else null,
                ) {
                    Text(
                        chunk.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (highlighted) glass.text else glass.text.copy(alpha = 0.86f),
                    )
                }
            }
        }
    }
}
