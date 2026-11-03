package com.pocketnotebook.app.core.hf

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Resumable HF file downloader: Range-continues from a .part file, verifies
 * final size (+ sha256 when provided), deletes on corruption.
 * Pause = cancel the WorkManager job; resume = enqueue again.
 */
class HfDownloader(private val client: OkHttpClient) {

    class DownloadException(message: String) : IOException(message)

    data class Progress(val bytes: Long, val total: Long) {
        val percent: Int get() = if (total > 0) ((bytes * 100) / total).toInt() else 0
    }

    /**
     * Blocking download — call from Dispatchers.IO / a Worker.
     * [onProgress] may be invoked frequently; keep it cheap (WorkManager Data
     * throttled by the caller).
     * @return the final file.
     */
    fun download(
        url: String,
        targetDir: File,
        fileName: String,
        expectedSize: Long = -1,
        sha256: String? = null,
        onProgress: (Progress) -> Unit = {},
        checkCancelled: () -> Boolean = {},
    ): File {
        targetDir.mkdirs()
        val final = File(targetDir, fileName)
        val part = File(targetDir, "$fileName.part")

        // Already complete?
        if (final.exists() && final.length() > 0) {
            if (expectedSize <= 0 || final.length() == expectedSize) {
                onProgress(Progress(final.length(), final.length()))
                return final
            }
            // Wrong size on disk — restart clean.
            final.delete()
        }

        var downloaded = if (part.exists()) part.length() else 0
        val total = if (expectedSize > 0) expectedSize else -1
        var lastTick = System.currentTimeMillis()

        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "PocketNotebook/0.1")
        if (downloaded > 0) requestBuilder.header("Range", "bytes=$downloaded-")

        client.newCall(requestBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                // Range not satisfiable → server returns 416; treat as done if we have bytes.
                if (resp.code == 416 && part.exists() && (total <= 0 || part.length() == total)) {
                    return finalize(part, final, expectedSize, sha256)
                }
                throw DownloadException("HTTP ${resp.code} from Hugging Face")
            }
            val body = resp.body ?: throw DownloadException("empty body")
            val realTotal = if (total > 0) total else downloaded + body.contentLength().coerceAtLeast(0)

            onProgress(Progress(downloaded, realTotal))
            // Append mode is the whole trick: bytes already in .part are kept.
            java.io.FileOutputStream(part, true).use { fos ->
                val src = body.byteStream()
                val buf = ByteArray(64 * 1024)
                var n: Int
                while (src.read(buf).also { n = it } > 0) {
                    if (checkCancelled()) throw DownloadException("cancelled")
                    fos.write(buf, 0, n)
                    downloaded += n
                    val now = System.currentTimeMillis()
                    if (now - lastTick > 400) {
                        lastTick = now
                        onProgress(Progress(downloaded, realTotal))
                    }
                }
            }
        }

        return finalize(part, final, expectedSize, sha256)
    }

    private fun finalize(part: File, final: File, expectedSize: Long, sha256: String?): File {
        if (expectedSize > 0 && part.length() != expectedSize) {
            part.delete()
            throw DownloadException("Size mismatch: expected $expectedSize, got ${part.length()}")
        }
        if (sha256 != null) {
            val digest = sha256Of(part)
            if (!digest.equals(sha256, ignoreCase = true)) {
                part.delete()
                throw DownloadException("sha256 mismatch — download corrupted, removed")
            }
        }
        if (final.exists()) final.delete()
        if (!part.renameTo(final)) {
            part.copyTo(final, overwrite = true)
            part.delete()
        }
        return final
    }

    companion object {
        fun sha256Of(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                var n: Int
                while (ins.read(buf).also { n = it } > 0) md.update(buf, 0, n)
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
