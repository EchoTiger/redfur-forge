import groovy.json.JsonSlurper
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

abstract class SyncLatestYtDlpTask : DefaultTask() {

    @get:Input
    abstract val checkBucket: Property<Long>

    @get:Input
    abstract val offlineMode: Property<Boolean>

    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputResDir: DirectoryProperty

    @TaskAction
    fun sync() {
        val outDir = outputResDir.get().asFile
        val rawDir = File(outDir, "raw").apply { mkdirs() }
        val valuesDir = File(outDir, "values").apply { mkdirs() }
        val outBinary = File(rawDir, "ytdlp")
        val outVersionXml = File(valuesDir, "ytdlp_bundled.xml")

        val cache = cacheDir.get().asFile.apply { mkdirs() }
        val cachedBin = File(cache, "yt-dlp")
        val metaFile = File(cache, "meta.properties")
        val metaProps = Properties()
        if (metaFile.exists()) {
            runCatching { metaFile.inputStream().use { metaProps.load(it) } }
        }

        var cachedTag = metaProps.getProperty("tag").orEmpty().trim()
        val lastCheckedMs = metaProps.getProperty("checkedAtMs")?.toLongOrNull() ?: 0L
        val nowMs = System.currentTimeMillis()
        val hasValidCachedBin = cachedBin.exists() && cachedBin.length() > MIN_YTDLP_BYTES && startsWithShebang(cachedBin)
        val cacheFresh = hasValidCachedBin && cachedTag.isNotEmpty() && (nowMs - lastCheckedMs) in 0L..CACHE_TTL_MS

        if (!cacheFresh && !offlineMode.get()) {
            try {
                val releaseJson = fetchText("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest")
                @Suppress("UNCHECKED_CAST")
                val parsed = JsonSlurper().parseText(releaseJson) as? Map<String, Any?>
                val latestTag = parsed?.get("tag_name")?.toString()?.trim().orEmpty()
                if (latestTag.isNotEmpty()) {
                    if (latestTag != cachedTag || !hasValidCachedBin) {
                        val downloadUrl = findYtDlpDownloadUrl(parsed)
                            ?: "https://github.com/yt-dlp/yt-dlp/releases/download/$latestTag/yt-dlp"
                        val tmpFile = File(cache, "yt-dlp.tmp")
                        downloadFile(downloadUrl, tmpFile)
                        if (tmpFile.length() > MIN_YTDLP_BYTES && startsWithShebang(tmpFile)) {
                            tmpFile.copyTo(cachedBin, overwrite = true)
                            tmpFile.delete()
                            cachedTag = latestTag
                            logger.lifecycle("[RedfurForge] Bundling yt-dlp $latestTag (${cachedBin.length()} bytes)")
                        } else {
                            tmpFile.delete()
                            logger.warn("[RedfurForge] Downloaded yt-dlp asset failed validation; keeping fallback.")
                        }
                    } else {
                        logger.lifecycle("[RedfurForge] Bundled yt-dlp $cachedTag is already up to date.")
                    }
                    metaProps.setProperty("tag", cachedTag)
                    metaProps.setProperty("checkedAtMs", nowMs.toString())
                    metaFile.outputStream().use { metaProps.store(it, "Redfur Forge yt-dlp cache") }
                }
            } catch (t: Throwable) {
                logger.warn("[RedfurForge] yt-dlp release sync skipped (${t.message}); using cached/AAR fallback.")
            }
        }

        val finalHasCache = cachedBin.exists() && cachedBin.length() > MIN_YTDLP_BYTES && startsWithShebang(cachedBin)
        if (finalHasCache && cachedTag.isNotEmpty()) {
            cachedBin.copyTo(outBinary, overwrite = true)
            writeBundledVersionXml(outVersionXml, cachedTag)
        } else {
            if (outBinary.exists()) outBinary.delete()
            writeBundledVersionXml(outVersionXml, "")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun findYtDlpDownloadUrl(releaseMap: Map<String, Any?>?): String? {
        val assets = releaseMap?.get("assets") as? List<Map<String, Any?>> ?: return null
        val match = assets.firstOrNull { asset ->
            asset["name"]?.toString()?.equals("yt-dlp", ignoreCase = false) == true
        }
        return match?.get("browser_download_url")?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun fetchText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 15000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "redfur-forge-gradle")
            setRequestProperty("Accept", "application/vnd.github+json")
            githubToken()?.let { token ->
                setRequestProperty("Authorization", "Bearer $token")
            }
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                error("HTTP $code from GitHub API")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadFile(url: String, dest: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "redfur-forge-gradle")
            githubToken()?.let { token ->
                setRequestProperty("Authorization", "Bearer $token")
            }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                error("HTTP $code downloading yt-dlp binary")
            }
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun githubToken(): String? {
        return System.getenv("GH_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }
            ?: System.getenv("GITHUB_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun startsWithShebang(file: File): Boolean {
        if (file.length() < 2L) return false
        return file.inputStream().use { input ->
            val b0 = input.read()
            val b1 = input.read()
            b0 == 0x23 && b1 == 0x21
        }
    }

    private fun writeBundledVersionXml(target: File, tag: String) {
        val escaped = tag
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        target.writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="bundled_ytdlp_version" translatable="false">$escaped</string>
</resources>
"""
        )
    }

    companion object {
        private const val MIN_YTDLP_BYTES = 1_000_000L
        private const val CACHE_TTL_MS = 10L * 60L * 1000L
    }
}

val syncLatestYtDlp = tasks.register<SyncLatestYtDlpTask>("syncLatestYtDlp") {
    checkBucket.set(System.currentTimeMillis() / 600_000L)
    offlineMode.set(gradle.startParameter.isOffline)
    cacheDir.set(rootProject.layout.projectDirectory.dir(".gradle/ytdlp-cache"))
    outputResDir.set(layout.buildDirectory.dir("generated/res/ytdlp"))
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.fissal.forge"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.fissal.forge"
        minSdk = 26
        targetSdk = 35
        versionCode = 20100
        versionName = "2.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (keystorePropsFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += setOf("**/libc++_shared.so")
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(
            syncLatestYtDlp,
            SyncLatestYtDlpTask::outputResDir
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.3")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")

    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:aria2c:0.18.1")
    implementation("com.github.dburckh:AndroidLibRaw:2.0.7")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
