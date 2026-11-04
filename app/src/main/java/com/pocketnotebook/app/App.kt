package com.pocketnotebook.app

import android.app.Application
import com.pocketnotebook.app.core.audio.OverviewBaker
import com.pocketnotebook.app.core.audio.TtsManager
import com.pocketnotebook.app.core.db.ArtifactRepo
import com.pocketnotebook.app.core.db.ChatRepo
import com.pocketnotebook.app.core.db.Db
import com.pocketnotebook.app.core.db.ModelRepo
import com.pocketnotebook.app.core.db.NoteRepo
import com.pocketnotebook.app.core.db.NotebookRepo
import com.pocketnotebook.app.core.db.SourceRepo
import com.pocketnotebook.app.core.engine.LlmRuntime
import com.pocketnotebook.app.core.hf.HfApi
import com.pocketnotebook.app.core.hf.HfDownloader
import com.pocketnotebook.app.core.ingest.IngestPipeline
import com.pocketnotebook.app.core.llm.ChatService
import com.pocketnotebook.app.core.llm.StudioService
import com.pocketnotebook.app.core.crash.CrashGuard
import com.pocketnotebook.app.core.prefs.PocketPrefs
import com.pocketnotebook.app.core.retrieval.Retriever
import com.pocketnotebook.app.core.work.Channels

class App : Application() {

    lateinit var container: AppContainer
        private set

    /**
     * Non-null when the DI container failed to build on this launch.
     * MainActivity shows a minimal error screen (with copyable text) instead of
     * crash-looping, so the failure reason can still reach us without adb.
     */
    @Volatile
    var bootError: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        // First thing, before anything can throw: capture future crashes to disk.
        CrashGuard.install(this)
        try {
            Channels.ensure(this)
            container = AppContainer(this)
            // Self-heal: register model files that landed on disk but never
            // made it into the DB (alpha.3 downloads whose post-download
            // write crashed) so they are detected without a re-download.
            runCatching { core.hf.ModelScanner.scanAndRegister(container) }
        } catch (t: Throwable) {
            bootError = t.message ?: t.toString()
            CrashGuard.recordBootFailure(this, t)
        }
    }
}

/** Manual DI — one instance per process, everything wired explicitly. */
class AppContainer(private val app: App) {

    // data
    val db = Db(app)
    private val sq = db.writableDatabase
    val notebookRepo = NotebookRepo(sq)
    val sourceRepo = SourceRepo(sq)
    val chatRepo = ChatRepo(sq)
    val noteRepo = NoteRepo(sq)
    val artifactRepo = ArtifactRepo(sq)
    val modelRepo = ModelRepo(sq)

    // prefs + engine
    val prefs = PocketPrefs(app)
    val runtime = LlmRuntime(app, db, prefs)

    // services
    val retriever = Retriever(sq, sourceRepo, runtime)
    val ingestPipeline = IngestPipeline(app, sourceRepo, runtime)
    val chatService = ChatService(retriever, chatRepo, notebookRepo, runtime)
    val studioService = StudioService(sourceRepo, artifactRepo, runtime)

    // hf
    private val okHttp = HfApi.defaultClient()
    val hfApi = HfApi(okHttp)
    val hfDownloader = HfDownloader(okHttp)
    val modelCatalog = com.pocketnotebook.app.core.hf.ModelCatalog

    // audio
    val ttsManager = TtsManager(app)
    val overviewBaker = OverviewBaker(app, artifactRepo, ttsManager)
}
