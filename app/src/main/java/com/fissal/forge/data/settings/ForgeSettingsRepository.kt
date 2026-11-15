package com.fissal.forge.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fissal.forge.domain.model.AspectPreset
import com.fissal.forge.domain.model.DownloadMode
import com.fissal.forge.domain.model.VideoCodecChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.forgeDataStore: DataStore<Preferences> by preferencesDataStore(name = "forge_settings")

data class ForgeSettings(
    val defaultDownloadMode: DownloadMode = DownloadMode.VIDEO_MP4,
    val defaultMaxVideoHeight: Int = 1080,
    val defaultAudioFormat: String = "mp3",
    val defaultVideoExt: String = "mp4",
    val defaultVideoCodec: VideoCodecChoice = VideoCodecChoice.H264,
    val defaultVideoCrf: Int = 23,
    val defaultImageFormat: String = "jpg",
    val defaultImageQuality: Int = 88,
    val defaultTargetSizeMb: Int = 8,
    val defaultCropPreset: AspectPreset = AspectPreset.AUTO_CLOSEST,
    val rawHalfSizeFast: Boolean = false,
    val playlistSingleOnly: Boolean = true,
    val useAria2c: Boolean = true,
    val stripUrlTracking: Boolean = true,
    val embedDownloadMetadata: Boolean = true,
    val embedDownloadThumbnail: Boolean = true,
    val stripMetadataOnSave: Boolean = false,
    val stripMetadataOnClipboard: Boolean = true,
    val autoCopyOnShareEdit: Boolean = false,
    val maxConcurrentDownloads: Int = 3,
    val computeSlotsOverride: Int = 0, // 0 = auto from device RAM
    val filenameSuffix: String = "_forge",
    val autoCheckAppUpdates: Boolean = true,
    val autoDownloadAppUpdates: Boolean = false,
    val dismissedUpdateTag: String = ""
)

class ForgeSettingsRepository(private val appContext: Context) {

    val settingsFlow: Flow<ForgeSettings> = appContext.forgeDataStore.data.map { prefs ->
        ForgeSettings(
            defaultDownloadMode = runCatching {
                DownloadMode.valueOf(prefs[KEY_DOWNLOAD_MODE] ?: DownloadMode.VIDEO_MP4.name)
            }.getOrDefault(DownloadMode.VIDEO_MP4),
            defaultMaxVideoHeight = prefs[KEY_MAX_VIDEO_HEIGHT] ?: 1080,
            defaultAudioFormat = prefs[KEY_AUDIO_FORMAT] ?: "mp3",
            defaultVideoExt = prefs[KEY_VIDEO_EXT] ?: "mp4",
            defaultVideoCodec = runCatching {
                VideoCodecChoice.valueOf(prefs[KEY_VIDEO_CODEC] ?: VideoCodecChoice.H264.name)
            }.getOrDefault(VideoCodecChoice.H264),
            defaultVideoCrf = prefs[KEY_VIDEO_CRF] ?: 23,
            defaultImageFormat = prefs[KEY_IMAGE_FORMAT] ?: "jpg",
            defaultImageQuality = prefs[KEY_IMAGE_QUALITY] ?: 88,
            defaultTargetSizeMb = prefs[KEY_TARGET_SIZE_MB] ?: 8,
            defaultCropPreset = runCatching {
                AspectPreset.valueOf(prefs[KEY_CROP_PRESET] ?: AspectPreset.AUTO_CLOSEST.name)
            }.getOrDefault(AspectPreset.AUTO_CLOSEST),
            rawHalfSizeFast = prefs[KEY_RAW_HALF_FAST] ?: false,
            playlistSingleOnly = prefs[KEY_PLAYLIST_SINGLE] ?: true,
            useAria2c = prefs[KEY_USE_ARIA2C] ?: true,
            stripUrlTracking = prefs[KEY_STRIP_TRACKING] ?: true,
            embedDownloadMetadata = prefs[KEY_EMBED_META] ?: true,
            embedDownloadThumbnail = prefs[KEY_EMBED_THUMB] ?: true,
            stripMetadataOnSave = prefs[KEY_STRIP_META_SAVE] ?: false,
            stripMetadataOnClipboard = prefs[KEY_STRIP_META_CLIP] ?: true,
            autoCopyOnShareEdit = prefs[KEY_AUTO_COPY_SHARE] ?: false,
            maxConcurrentDownloads = (prefs[KEY_MAX_DOWNLOADS] ?: 3).coerceIn(1, 5),
            computeSlotsOverride = (prefs[KEY_COMPUTE_SLOTS] ?: 0).coerceIn(0, 4),
            filenameSuffix = prefs[KEY_SUFFIX] ?: "_forge",
            autoCheckAppUpdates = prefs[KEY_AUTO_CHECK_UPDATES] ?: true,
            autoDownloadAppUpdates = prefs[KEY_AUTO_DOWNLOAD_UPDATES] ?: false,
            dismissedUpdateTag = prefs[KEY_DISMISSED_UPDATE_TAG].orEmpty()
        )
    }

    suspend fun update(transform: (ForgeSettings) -> ForgeSettings) {
        appContext.forgeDataStore.edit { prefs ->
            val current = ForgeSettings(
                defaultDownloadMode = runCatching {
                    DownloadMode.valueOf(prefs[KEY_DOWNLOAD_MODE] ?: DownloadMode.VIDEO_MP4.name)
                }.getOrDefault(DownloadMode.VIDEO_MP4),
                defaultMaxVideoHeight = prefs[KEY_MAX_VIDEO_HEIGHT] ?: 1080,
                defaultAudioFormat = prefs[KEY_AUDIO_FORMAT] ?: "mp3",
                defaultVideoExt = prefs[KEY_VIDEO_EXT] ?: "mp4",
                defaultVideoCodec = runCatching {
                    VideoCodecChoice.valueOf(prefs[KEY_VIDEO_CODEC] ?: VideoCodecChoice.H264.name)
                }.getOrDefault(VideoCodecChoice.H264),
                defaultVideoCrf = prefs[KEY_VIDEO_CRF] ?: 23,
                defaultImageFormat = prefs[KEY_IMAGE_FORMAT] ?: "jpg",
                defaultImageQuality = prefs[KEY_IMAGE_QUALITY] ?: 88,
                defaultTargetSizeMb = prefs[KEY_TARGET_SIZE_MB] ?: 8,
                defaultCropPreset = runCatching {
                    AspectPreset.valueOf(prefs[KEY_CROP_PRESET] ?: AspectPreset.AUTO_CLOSEST.name)
                }.getOrDefault(AspectPreset.AUTO_CLOSEST),
                rawHalfSizeFast = prefs[KEY_RAW_HALF_FAST] ?: false,
                playlistSingleOnly = prefs[KEY_PLAYLIST_SINGLE] ?: true,
                useAria2c = prefs[KEY_USE_ARIA2C] ?: true,
                stripUrlTracking = prefs[KEY_STRIP_TRACKING] ?: true,
                embedDownloadMetadata = prefs[KEY_EMBED_META] ?: true,
                embedDownloadThumbnail = prefs[KEY_EMBED_THUMB] ?: true,
                stripMetadataOnSave = prefs[KEY_STRIP_META_SAVE] ?: false,
                stripMetadataOnClipboard = prefs[KEY_STRIP_META_CLIP] ?: true,
                autoCopyOnShareEdit = prefs[KEY_AUTO_COPY_SHARE] ?: false,
                maxConcurrentDownloads = (prefs[KEY_MAX_DOWNLOADS] ?: 3).coerceIn(1, 5),
                computeSlotsOverride = (prefs[KEY_COMPUTE_SLOTS] ?: 0).coerceIn(0, 4),
                filenameSuffix = prefs[KEY_SUFFIX] ?: "_forge",
                autoCheckAppUpdates = prefs[KEY_AUTO_CHECK_UPDATES] ?: true,
                autoDownloadAppUpdates = prefs[KEY_AUTO_DOWNLOAD_UPDATES] ?: false,
                dismissedUpdateTag = prefs[KEY_DISMISSED_UPDATE_TAG].orEmpty()
            )
            val next = transform(current)
            prefs[KEY_DOWNLOAD_MODE] = next.defaultDownloadMode.name
            prefs[KEY_MAX_VIDEO_HEIGHT] = next.defaultMaxVideoHeight
            prefs[KEY_AUDIO_FORMAT] = next.defaultAudioFormat
            prefs[KEY_VIDEO_EXT] = next.defaultVideoExt
            prefs[KEY_VIDEO_CODEC] = next.defaultVideoCodec.name
            prefs[KEY_VIDEO_CRF] = next.defaultVideoCrf
            prefs[KEY_IMAGE_FORMAT] = next.defaultImageFormat
            prefs[KEY_IMAGE_QUALITY] = next.defaultImageQuality
            prefs[KEY_TARGET_SIZE_MB] = next.defaultTargetSizeMb
            prefs[KEY_CROP_PRESET] = next.defaultCropPreset.name
            prefs[KEY_RAW_HALF_FAST] = next.rawHalfSizeFast
            prefs[KEY_PLAYLIST_SINGLE] = next.playlistSingleOnly
            prefs[KEY_USE_ARIA2C] = next.useAria2c
            prefs[KEY_STRIP_TRACKING] = next.stripUrlTracking
            prefs[KEY_EMBED_META] = next.embedDownloadMetadata
            prefs[KEY_EMBED_THUMB] = next.embedDownloadThumbnail
            prefs[KEY_STRIP_META_SAVE] = next.stripMetadataOnSave
            prefs[KEY_STRIP_META_CLIP] = next.stripMetadataOnClipboard
            prefs[KEY_AUTO_COPY_SHARE] = next.autoCopyOnShareEdit
            prefs[KEY_MAX_DOWNLOADS] = next.maxConcurrentDownloads.coerceIn(1, 5)
            prefs[KEY_COMPUTE_SLOTS] = next.computeSlotsOverride.coerceIn(0, 4)
            prefs[KEY_SUFFIX] = next.filenameSuffix
            prefs[KEY_AUTO_CHECK_UPDATES] = next.autoCheckAppUpdates
            prefs[KEY_AUTO_DOWNLOAD_UPDATES] = next.autoDownloadAppUpdates
            prefs[KEY_DISMISSED_UPDATE_TAG] = next.dismissedUpdateTag
        }
    }

    companion object {
        private val KEY_DOWNLOAD_MODE = stringPreferencesKey("download_mode")
        private val KEY_MAX_VIDEO_HEIGHT = intPreferencesKey("max_video_height")
        private val KEY_AUDIO_FORMAT = stringPreferencesKey("audio_format")
        private val KEY_VIDEO_EXT = stringPreferencesKey("video_ext")
        private val KEY_VIDEO_CODEC = stringPreferencesKey("video_codec")
        private val KEY_VIDEO_CRF = intPreferencesKey("video_crf")
        private val KEY_IMAGE_FORMAT = stringPreferencesKey("image_format")
        private val KEY_IMAGE_QUALITY = intPreferencesKey("image_quality")
        private val KEY_TARGET_SIZE_MB = intPreferencesKey("target_size_mb")
        private val KEY_CROP_PRESET = stringPreferencesKey("crop_preset")
        private val KEY_RAW_HALF_FAST = booleanPreferencesKey("raw_half_fast")
        private val KEY_PLAYLIST_SINGLE = booleanPreferencesKey("playlist_single")
        private val KEY_USE_ARIA2C = booleanPreferencesKey("use_aria2c")
        private val KEY_STRIP_TRACKING = booleanPreferencesKey("strip_tracking")
        private val KEY_EMBED_META = booleanPreferencesKey("embed_meta")
        private val KEY_EMBED_THUMB = booleanPreferencesKey("embed_thumb")
        private val KEY_STRIP_META_SAVE = booleanPreferencesKey("strip_meta_save")
        private val KEY_STRIP_META_CLIP = booleanPreferencesKey("strip_meta_clip")
        private val KEY_AUTO_COPY_SHARE = booleanPreferencesKey("auto_copy_share")
        private val KEY_MAX_DOWNLOADS = intPreferencesKey("max_downloads")
        private val KEY_COMPUTE_SLOTS = intPreferencesKey("compute_slots")
        private val KEY_SUFFIX = stringPreferencesKey("filename_suffix")
        private val KEY_AUTO_CHECK_UPDATES = booleanPreferencesKey("auto_check_app_updates")
        private val KEY_AUTO_DOWNLOAD_UPDATES = booleanPreferencesKey("auto_download_app_updates")
        private val KEY_DISMISSED_UPDATE_TAG = stringPreferencesKey("dismissed_update_tag")
    }
}
