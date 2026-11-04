package com.pocketnotebook.app.ui.screens

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.pocketnotebook.app.core.crash.CrashGuard
import com.pocketnotebook.app.core.db.ModelSlot
import com.pocketnotebook.app.core.native.PocketLlm
import com.pocketnotebook.app.ui.GlassScaffold
import com.pocketnotebook.app.ui.formatBytes
import com.pocketnotebook.app.ui.rememberContainer
import com.pocketnotebook.app.ui.theme.GlassButton
import com.pocketnotebook.app.ui.theme.GlassIconButton
import com.pocketnotebook.app.ui.theme.GlassPanel
import com.pocketnotebook.app.ui.theme.LocalGlassColors
import com.pocketnotebook.app.ui.theme.SectionLabel

/**
 * Sideloaded-app debugging without adb: device facts (kernel page size!),
 * native engine status + exact load error, active models, and the last crash
 * report captured by CrashGuard — all copyable as one text blob.
 */
@Composable
fun DiagnosticsScreen(nav: NavHostController) {
    val container = rememberContainer()
    val glass = LocalGlassColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = LocalClipboardManager.current

    var crashReport by remember { mutableStateOf(CrashGuard.latestReport(context)) }
    var crashCount by remember { mutableStateOf(CrashGuard.listReports(context).size) }
    var copied by remember { mutableStateOf(false) }
    // Live engine state — the load happens in the background and takes a
    // while, so read it as state, not once at composition.
    val runtimeState by container.runtime.state.collectAsState()

    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val pageSize = CrashGuard.pageSizeBytes()
    val modelsDir = container.runtime.modelsDir()
    val modelsSize = modelsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    val appVersion: String = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        "${pi.versionName} (${pi.versionCode})"
    } catch (_: Throwable) {
        "?"
    }

    val report = buildString {
        appendLine("PocketNotebook diagnostics")
        appendLine("app-version: $appVersion")
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("kernel-page-size: $pageSize")
        appendLine("supported-abis: ${Build.SUPPORTED_ABIS.joinToString(",")}")
        appendLine("ram-class: ${am?.memoryClass ?: "?"} MB")
        appendLine("native-lib-loaded: ${PocketLlm.libLoaded}")
        appendLine("native-load-error: ${PocketLlm.loadError}")
        appendLine("llama-engine-built: ${PocketLlm.fullEngine}")
        appendLine("gpu: ${PocketLlm.gpuInfo().replace("\n", " · ")}")
        appendLine("models-dir: ${modelsDir.absolutePath} (${formatBytes(modelsSize)})")
        appendLine("chat-model: ${container.prefs.activeModelPath(ModelSlot.CHAT) ?: "none"}")
        appendLine("chat-loaded-in-memory: ${container.runtime.state.value.chatPath ?: "no"}")
        appendLine("engine-error: ${container.runtime.state.value.chatError ?: PocketLlm.lastError() ?: "none"}")
        appendLine("kv-cache-quant: ${if (container.prefs.kvQuant == 0) "f16" else if (container.prefs.kvQuant == 8) "q8_0" else "q4_0"}")
        appendLine("embed-model: ${container.prefs.activeModelPath(ModelSlot.EMBED) ?: "none"}")
        appendLine("audio-model: ${container.prefs.activeModelPath(ModelSlot.AUDIO) ?: "none"}")
        appendLine()
        appendLine("=== last crash report (${crashCount} kept) ===")
        appendLine(crashReport ?: "none")
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
                    "Diagnostics",
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
            // ---------------- device ----------------
            SectionLabel("Device")
            GlassPanel(corner = 20.dp, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${Build.MANUFACTURER} ${Build.MODEL}",
                    style = MaterialTheme.typography.bodyMedium, color = glass.text,
                )
                Text(
                    "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · RAM class ${am?.memoryClass ?: "?"} MB",
                    style = MaterialTheme.typography.bodySmall, color = glass.textMuted,
                )
                Text(
                    "Kernel page size: $pageSize bytes" +
                        (if (pageSize > 4096) " — 16 KB pages: native libs MUST be 16 KB aligned" else " — 4 KB pages"),
                    style = MaterialTheme.typography.bodySmall, color = glass.textMuted,
                )
                Text(
                    "ABIs: ${Build.SUPPORTED_ABIS.joinToString(",")}",
                    style = MaterialTheme.typography.bodySmall, color = glass.textMuted,
                )
                Text(
                    "App version: $appVersion",
                    style = MaterialTheme.typography.bodySmall, color = glass.textMuted,
                )
            }

            // ---------------- native engine ----------------
            SectionLabel("Native engine")
            GlassPanel(corner = 20.dp, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "engine lib: " + when (PocketLlm.engineVariant) {
                        "opencl" -> "libpocketllm_ocl.so (OpenCL GPU build) — loaded"
                        "cpu" -> "libpocketllm.so (CPU build) — loaded"
                        else -> "none — no engine lib could be loaded"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (PocketLlm.libLoaded) glass.text else glass.accent,
                )
                if (PocketLlm.loadError != null) {
                    Text(
                        PocketLlm.loadError!!,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = glass.textMuted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text(
                    "llama.cpp engine: " + when {
                        PocketLlm.fullEngine -> "active (${PocketLlm.engineVariant} engine)"
                        PocketLlm.libLoaded -> "stub — kit build (install a release APK)"
                        else -> "not loaded (see the load error above)"
                    },
                    style = MaterialTheme.typography.bodySmall, color = glass.textMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "GPU: ${PocketLlm.gpuInfo()}",
                    style = MaterialTheme.typography.bodySmall, color = glass.textMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // ---------------- models ----------------
            SectionLabel("Models")
            GlassPanel(corner = 20.dp, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Models dir: ${formatBytes(modelsSize)}",
                    style = MaterialTheme.typography.bodyMedium, color = glass.text,
                )
                Text(
                    modelsDir.absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = glass.textMuted,
                )
                Text(
                    "chat: ${container.prefs.activeModelPath(ModelSlot.CHAT) ?: "none selected"}\n" +
                        "embed: ${container.prefs.activeModelPath(ModelSlot.EMBED) ?: "none selected"}\n" +
                        "audio: ${container.prefs.activeModelPath(ModelSlot.AUDIO) ?: "none selected"}",
                    style = MaterialTheme.typography.bodySmall, color = glass.textMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "chat loaded in memory: " +
                        if (runtimeState.chatPath != null) {
                            "yes${if (runtimeState.chatLoading) ", reloading…" else ""}"
                        } else if (runtimeState.chatLoading) "loading…" else "no",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (runtimeState.chatPath != null) glass.text else glass.textMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
                val engineError = runtimeState.chatError ?: PocketLlm.lastError()
                if (engineError != null) {
                    Text(
                        "engine error: $engineError",
                        style = MaterialTheme.typography.bodySmall, color = glass.accent3,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text(
                    "kv cache: ${if (container.prefs.kvQuant == 0) "f16" else if (container.prefs.kvQuant == 8) "q8_0" else "q4_0"}",
                    style = MaterialTheme.typography.bodySmall, color = glass.textMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // ---------------- crashes ----------------
            SectionLabel("Crash reports")
            GlassPanel(corner = 20.dp, modifier = Modifier.fillMaxWidth()) {
                if (crashReport == null) {
                    Text(
                        "No crashes recorded.",
                        style = MaterialTheme.typography.bodySmall, color = glass.textMuted,
                    )
                } else {
                    Text(
                        "$crashCount kept — newest:",
                        style = MaterialTheme.typography.bodySmall, color = glass.text,
                    )
                    Text(
                        crashReport!!,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = glass.textMuted,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                Row(
                    Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    GlassButton(if (copied) "Copied!" else "Copy diagnostics", onClick = {
                        clipboard.setText(AnnotatedString(report))
                        copied = true
                    })
                    if (crashReport != null) {
                        GlassButton("Clear reports", onClick = {
                            CrashGuard.clear(context)
                            crashReport = null
                            crashCount = 0
                        })
                    }
                }
            }
        }
    }
}
