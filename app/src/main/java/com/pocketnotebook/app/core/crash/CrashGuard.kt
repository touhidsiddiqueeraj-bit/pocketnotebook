package com.pocketnotebook.app.core.crash

import android.app.Application
import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash capture for a sideloaded app — there is no Play Console and often no
 * adb, so every uncaught exception is written to filesDir/crashes/ with full
 * device context. The last [KEEP] reports are retained and shown in
 * Settings → Diagnostics on the next launch.
 */
object CrashGuard {

    private const val KEEP = 5

    fun install(app: Application) {
        val dir = reportsDir(app)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                write(app, dir, thread, throwable)
            } catch (_: Throwable) {
                // Never let the crash reporter itself crash the process.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Recorded when AppContainer itself fails to build (startup path). */
    fun recordBootFailure(context: Context, throwable: Throwable) {
        try {
            write(context, reportsDir(context), Thread.currentThread(), throwable)
        } catch (_: Throwable) {
        }
    }

    fun reportsDir(context: Context): File =
        File(context.filesDir, "crashes").apply { mkdirs() }

    /** Kernel page size in bytes (4096 or 16384 on newer devices), -1 if unknown. */
    fun pageSizeBytes(): Long = try {
        Os.sysconf(OsConstants._SC_PAGE_SIZE)
    } catch (_: Throwable) {
        -1L
    }

    fun listReports(context: Context): List<File> =
        reportsDir(context).listFiles { f -> f.name.startsWith("crash-") }
            ?.sortedByDescending { it.name }
            ?.take(KEEP)
            ?: emptyList()

    fun latestReport(context: Context): String? =
        listReports(context).firstOrNull()?.let { f ->
            try {
                f.readText()
            } catch (_: Throwable) {
                null
            }
        }

    fun clear(context: Context) {
        listReports(context).forEach { it.delete() }
    }

    // ---------------------------------------------------------------------

    private fun write(context: Context, dir: File, thread: Thread, t: Throwable) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val stack = StringWriter()
        t.printStackTrace(PrintWriter(stack))

        val version: String = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.versionCode})"
        } catch (_: Throwable) {
            "?"
        }

        val body = buildString {
            appendLine("time: $stamp")
            appendLine("thread: ${thread.name}")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("kernel-page-size: ${pageSizeBytes()}")
            appendLine("supported-abis: ${Build.SUPPORTED_ABIS.joinToString(",")}")
            appendLine("app-version: $version")
            appendLine()
            appendLine(stack.toString())
        }

        File(dir, "crash-$stamp.txt").writeText(body)

        // keep only the newest reports
        dir.listFiles { f -> f.name.startsWith("crash-") }
            ?.sortedByDescending { it.name }
            ?.drop(KEEP)
            ?.forEach { it.delete() }
    }
}
