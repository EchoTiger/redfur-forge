package com.fissal.forge

import android.app.Application
import com.fissal.forge.data.local.ForgeDatabase
import com.fissal.forge.data.settings.ForgeSettingsRepository
import com.fissal.forge.delivery.ClipboardExportManager
import com.fissal.forge.delivery.MediaStoreExporter
import com.fissal.forge.engine.FFmpegCliEngine
import com.fissal.forge.engine.ImageRawEngine
import com.fissal.forge.engine.MediaProbeEngine
import com.fissal.forge.engine.NativeBinaryManager
import com.fissal.forge.engine.YtDlpEngine
import com.fissal.forge.pipeline.PipelineOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ForgeApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var database: ForgeDatabase
        private set
    lateinit var settingsRepository: ForgeSettingsRepository
        private set
    lateinit var binaryManager: NativeBinaryManager
        private set
    lateinit var probeEngine: MediaProbeEngine
        private set
    lateinit var imageRawEngine: ImageRawEngine
        private set
    lateinit var ffmpegEngine: FFmpegCliEngine
        private set
    lateinit var ytDlpEngine: YtDlpEngine
        private set
    lateinit var mediaStoreExporter: MediaStoreExporter
        private set
    lateinit var clipboardExportManager: ClipboardExportManager
        private set
    lateinit var orchestrator: PipelineOrchestrator
        private set

    override fun onCreate() {
        super.onCreate()
        database = ForgeDatabase.getInstance(this)
        settingsRepository = ForgeSettingsRepository(this)
        binaryManager = NativeBinaryManager(this)
        probeEngine = MediaProbeEngine(this, binaryManager)
        imageRawEngine = ImageRawEngine(this)
        ffmpegEngine = FFmpegCliEngine(this, binaryManager)
        ytDlpEngine = YtDlpEngine(binaryManager)
        mediaStoreExporter = MediaStoreExporter(this)
        clipboardExportManager = ClipboardExportManager(this)

        orchestrator = PipelineOrchestrator(
            appContext = this,
            jobDao = database.jobDao(),
            settingsRepository = settingsRepository,
            probeEngine = probeEngine,
            ytDlpEngine = ytDlpEngine,
            ffmpegEngine = ffmpegEngine,
            imageRawEngine = imageRawEngine,
            mediaStoreExporter = mediaStoreExporter,
            clipboardExportManager = clipboardExportManager
        )

        // Warm up yt-dlp/python/ffmpeg extraction asynchronously on IO without blocking cold start
        appScope.launch {
            runCatching { binaryManager.ensureInitialized() }
        }
    }
}
