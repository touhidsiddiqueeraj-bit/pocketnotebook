package com.pocketnotebook.app.core.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pocketnotebook.app.App
import com.pocketnotebook.app.R
import com.pocketnotebook.app.core.db.SourceStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Channels {
    const val WORK = "pocket.work"
    const val DOWNLOAD = "pocket.download"

    fun ensure(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(WORK, "Background work", NotificationManager.IMPORTANCE_LOW),
            )
            nm.createNotificationChannel(
                NotificationChannel(DOWNLOAD, "Downloads", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }
}

object WorkScheduler {

    fun enqueueIngest(context: Context, sourceId: Long): androidx.work.Operation {
        val request: OneTimeWorkRequest = OneTimeWorkRequestBuilder<IngestWorker>()
            .setInputData(workDataOf(IngestWorker.KEY_SOURCE_ID to sourceId))
            .addTag("ingest")
            .build()
        return WorkManager.getInstance(context)
            .enqueueUniqueWork("ingest-$sourceId", ExistingWorkPolicy.KEEP, request)
    }

    fun enqueueDownload(
        context: Context,
        modelId: Long,
        repoId: String,
        fileName: String,
        slot: String,
        sizeBytes: Long,
    ): androidx.work.Operation {
        val request: OneTimeWorkRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_MODEL_ID to modelId,
                    DownloadWorker.KEY_REPO to repoId,
                    DownloadWorker.KEY_FILE to fileName,
                    DownloadWorker.KEY_SLOT to slot,
                    DownloadWorker.KEY_SIZE to sizeBytes,
                ),
            )
            .addTag("download")
            .addTag("download-$modelId")
            .build()
        return WorkManager.getInstance(context)
            .enqueueUniqueWork("download-$modelId", ExistingWorkPolicy.KEEP, request)
    }

    fun enqueueBake(context: Context, artifactId: Long): androidx.work.Operation {
        val request: OneTimeWorkRequest = OneTimeWorkRequestBuilder<BakeWorker>()
            .setInputData(workDataOf(BakeWorker.KEY_ARTIFACT_ID to artifactId))
            .addTag("bake")
            .build()
        return WorkManager.getInstance(context)
            .enqueueUniqueWork("bake-$artifactId", ExistingWorkPolicy.REPLACE, request)
    }
}

abstract class BaseWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    protected fun notification(text: String, channel: String = Channels.WORK): Notification =
        NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .build()

    protected fun foregroundInfo(text: String, channel: String = Channels.WORK): ForegroundInfo {
        Channels.ensure(applicationContext)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(
                text.hashCode(),
                notification(text, channel),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(text.hashCode(), notification(text, channel))
        }
    }

    /** FGS start can be disallowed (background restrictions); degrade silently. */
    protected suspend fun safeForeground(text: String, channel: String = Channels.WORK) {
        try {
            setForeground(foregroundInfo(text, channel))
        } catch (_: Throwable) {
        }
    }
}

/** Parse → chunk → embed → store. One worker per source. */
class IngestWorker(appContext: Context, params: WorkerParameters) :
    BaseWorker(appContext, params) {

    companion object {
        const val KEY_SOURCE_ID = "source_id"
    }

    private val container get() = (applicationContext as App).container

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sourceId = inputData.getLong(KEY_SOURCE_ID, -1)
        if (sourceId <= 0) return@withContext Result.failure()

        val source = container.sourceRepo.get(sourceId)
            ?: return@withContext Result.failure()
        safeForeground("Reading \"${source.title}\"")

        try {
            container.ingestPipeline.ingestPending(sourceId)
            Result.success()
        } catch (t: Throwable) {
            container.sourceRepo.setStatus(
                sourceId, SourceStatus.FAILED, error = t.message ?: "ingest failed",
            )
            Result.success() // failure recorded on the row; no retry storms
        }
    }
}

/** Resumable model download with a progress notification. */
class DownloadWorker(appContext: Context, params: WorkerParameters) :
    BaseWorker(appContext, params) {

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_REPO = "repo"
        const val KEY_FILE = "file"
        const val KEY_SLOT = "slot"
        const val KEY_SIZE = "size"

        const val PROGRESS_BYTES = "bytes"
        const val PROGRESS_TOTAL = "total"
        const val PROGRESS_PERCENT = "percent"
    }

    private val container get() = (applicationContext as App).container

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getLong(KEY_MODEL_ID, -1)
        val repoId = inputData.getString(KEY_REPO) ?: return@withContext Result.failure()
        val fileName = inputData.getString(KEY_FILE) ?: return@withContext Result.failure()
        val slot = inputData.getString(KEY_SLOT) ?: "chat"
        val size = inputData.getLong(KEY_SIZE, -1)

        safeForeground("Downloading $fileName", Channels.DOWNLOAD)

        val url = container.hfApi.resolveUrl(repoId, fileName)
        val dir = java.io.File(container.runtime.modelsDir(), repoId.replace("/", "__"))

        try {
            val file = container.hfDownloader.download(
                url = url,
                targetDir = dir,
                fileName = fileName,
                expectedSize = size,
                onProgress = { p ->
                    setProgressAsync(
                        workDataOf(
                            PROGRESS_BYTES to p.bytes,
                            PROGRESS_TOTAL to p.total,
                            PROGRESS_PERCENT to p.percent,
                            "file" to fileName,
                        ),
                    ).get()
                },
                checkCancelled = { isStopped },
            )
            container.modelRepo.upsert(slot, repoId, fileName, file.absolutePath, file.length(), null, true)
            // First download for a slot becomes the active model.
            if (container.prefs.activeModelPath(slot) == null) {
                container.prefs.setActiveModel(slot, file.absolutePath)
            }
            Result.success(workDataOf(KEY_MODEL_ID to modelId, "path" to file.absolutePath))
        } catch (t: Throwable) {
            if (isStopped) Result.retry() else Result.failure(
                workDataOf("error" to (t.message ?: "download failed")),
            )
        }
    }
}

/** Audio overview bake: script → TTS segments. */
class BakeWorker(appContext: Context, params: WorkerParameters) :
    BaseWorker(appContext, params) {

    companion object {
        const val KEY_ARTIFACT_ID = "artifact_id"
        const val PROGRESS_DONE = "done"
        const val PROGRESS_TOTAL = "total"
    }

    private val container get() = (applicationContext as App).container

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val artifactId = inputData.getLong(KEY_ARTIFACT_ID, -1)
        if (artifactId <= 0) return@withContext Result.failure()

        safeForeground("Baking audio overview")
        return@withContext try {
            container.overviewBaker.bake(artifactId) { done, total ->
                setProgressAsync(workDataOf(PROGRESS_DONE to done, PROGRESS_TOTAL to total)).get()
            }
            Result.success(workDataOf(KEY_ARTIFACT_ID to artifactId))
        } catch (t: Throwable) {
            container.artifactRepo.update(
                artifactId,
                status = com.pocketnotebook.app.core.db.ArtifactStatus.FAILED,
                error = t.message ?: "bake failed",
            )
            Result.failure(workDataOf("error" to (t.message ?: "bake failed")))
        }
    }
}
