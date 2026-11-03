package com.pocketnotebook.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketnotebook.app.App
import com.pocketnotebook.app.AppContainer
import com.pocketnotebook.app.ui.theme.GlassPanel
import com.pocketnotebook.app.ui.theme.LocalGlassColors
import java.util.Locale

/** One-liner access to the DI container from composables. */
@Composable
fun rememberContainer(): AppContainer =
    (androidx.compose.ui.platform.LocalContext.current.applicationContext as App).container

/**
 * Base scaffold: aurora background + top slot + weight-1 content + bottom slot,
 * with correct bar/IME insets.
 */
@Composable
fun GlassScaffold(
    modifier: Modifier = Modifier,
    top: @Composable () -> Unit = {},
    bottom: @Composable () -> Unit = {},
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        com.pocketnotebook.app.ui.theme.AuroraBackground()
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            top()
            Box(Modifier.weight(1f)) {
                Column(Modifier.fillMaxSize()) { content() }
            }
            bottom()
        }
    }
}

// ---------------------------------------------------------------------------
// Small shared pieces
// ---------------------------------------------------------------------------

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    val glass = LocalGlassColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = glass.textMuted.copy(alpha = 0.7f), modifier = Modifier.size(40.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = glass.text)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = glass.textMuted,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

@Composable
fun GlassDivider() {
    val glass = LocalGlassColors.current
    HorizontalDivider(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        thickness = 0.75.dp,
        color = glass.borderBottom.copy(alpha = 0.6f),
    )
}

/** Minimal markdown: headings, bullets, quotes, bold, rules. */
@Composable
fun MarkdownText(md: String, modifier: Modifier = Modifier) {
    val glass = LocalGlassColors.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        md.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(2.dp))
                line.startsWith("### ") -> Text(
                    line.removePrefix("### "),
                    style = MaterialTheme.typography.titleMedium,
                    color = glass.accent2,
                )
                line.startsWith("## ") -> Text(
                    line.removePrefix("## "),
                    style = MaterialTheme.typography.titleLarge,
                    color = glass.text,
                )
                line.startsWith("# ") -> Text(
                    line.removePrefix("# "),
                    style = MaterialTheme.typography.headlineSmall,
                    color = glass.text,
                )
                line.startsWith("- ") || line.startsWith("* ") -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", color = glass.accent, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        boldSpans(line.drop(2)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = glass.text,
                    )
                }
                line.startsWith("> ") -> GlassPanel(corner = 14.dp, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        boldSpans(line.drop(2)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = glass.textMuted,
                    )
                }
                line == "---" || line == "***" -> GlassDivider()
                else -> Text(
                    boldSpans(line),
                    style = MaterialTheme.typography.bodyMedium,
                    color = glass.text,
                )
            }
        }
    }
}

@Composable
private fun boldSpans(line: String): AnnotatedString = buildAnnotatedString {
    var rest = line
    while (true) {
        val start = rest.indexOf("**")
        if (start < 0) {
            append(rest)
            break
        }
        val end = rest.indexOf("**", startIndex = start + 2)
        if (end < 0) {
            append(rest)
            break
        }
        append(rest.substring(0, start))
        pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
        append(rest.substring(start + 2, end))
        pop()
        rest = rest.substring(end + 2)
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

fun timeAgo(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    val min = diff / 60000
    return when {
        diff < 0 -> "just now"
        min < 1 -> "just now"
        min < 60 -> "${min}m ago"
        min < 60 * 24 -> "${min / 60}h ago"
        min < 60 * 24 * 30 -> "${min / (60 * 24)}d ago"
        else -> "${min / (60 * 24 * 30)}mo ago"
    }
}

// ---------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------

@Composable
fun GlassDialog(
    onDismiss: () -> Unit,
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        GlassPanel(corner = 28.dp, modifier = Modifier.fillMaxWidth()) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = LocalGlassColors.current.text,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            content()
        }
    }
}
