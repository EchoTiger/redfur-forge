package com.fissal.forge

import android.app.Application
import com.fissal.forge.auth.SiteAuthVault
import com.fissal.forge.data.local.ForgeDatabase
import com.fissal.forge.data.settings.ForgeSettingsRepository
import com.fissal.forge.delivery.ClipboardExportManager
import com.fissal.forge.delivery.MediaStoreExporter
import com.fissal.forge.domain.logic.DeviceCapabilityAdvisor
import com.fissal.forge.domain.logic.DeviceCapabilityReport
import com.fissal.forge.engine.FFmpegCliEngine
import com.fissal.forge.engine.ImageRawEngine
import com.fissal.forge.engine.MediaProbeEngine
import com.fissal.forge.engine.NativeBinaryManager
import com.fissal.forge.engine.SampleFrameEngine
import com.fissal.forge.engine.YtDlpEngine
import com.fissal.forge.pipeline.PipelineOrchestrator
import com.fissal.forge.update.AppUpdateManager
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
    lateinit var siteAuthVault: SiteAuthVault
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
    lateinit var sampleFrameEngine: SampleFrameEngine
        private set
    lateinit var mediaStoreExporter: MediaStoreExporter
        private set
    lateinit var clipboardExportManager: ClipboardExportManager
        private set
    lateinit var appUpdateManager: AppUpdateManager
        private set
    lateinit var deviceCapabilities: DeviceCapabilityReport
        private set
    lateinit var orchestrator: PipelineOrchestrator
        private set

    override fun onCreate() {
        super.onCreate()
        database = ForgeDatabase.getInstance(this)
        settingsRepository = ForgeSettingsRepository(this)
        siteAuthVault = SiteAuthVault(this)
        binaryManager = NativeBinaryManager(this)
        probeEngine = MediaProbeEngine(this, binaryManager)
        imageRawEngine = ImageRawEngine(this)
        ffmpegEngine = FFmpegCliEngine(this, binaryManager)
        ytDlpEngine = YtDlpEngine(binaryManager, siteAuthVault)
        sampleFrameEngine = SampleFrameEngine(this, ffmpegEngine, imageRawEngine, probeEngine)
        mediaStoreExporter = MediaStoreExporter(this)
        clipboardExportManager = ClipboardExportManager(this)
        appUpdateManager = AppUpdateManager(this)
        deviceCapabilities = DeviceCapabilityAdvisor.profileDevice(this)

        orchestrator = PipelineOrchestrator(
            appContext = this,
            jobDao = database.jobDao(),
            settingsRepository = settingsRepository,
            probeEngine = probeEngine,
            ytDlpEngine = ytDlpEngine,
            ffmpegEngine = ffmpegEngine,
            imageRawEngine = imageRawEngine,
            mediaStoreExporter = mediaStoreExporter,
            clipboardExportManager = clipboardExportManager,
            deviceCapabilities = deviceCapabilities,
            siteAuthVault = siteAuthVault
        )

        // Sweep any leftover ephemeral cookie files on cold start (Sample Lab cache is swept under ViewModel mutex)
        appScope.launch {
            runCatching { siteAuthVault.sweepStaleTempCookieFiles() }
        }

        // Warm up yt-dlp/python/ffmpeg extraction asynchronously on IO without blocking cold start
        appScope.launch {
            runCatching { binaryManager.ensureInitialized() }
        }
    }
}
