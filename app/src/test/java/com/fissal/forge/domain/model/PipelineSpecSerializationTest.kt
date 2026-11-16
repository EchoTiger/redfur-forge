package com.fissal.forge.domain.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineSpecSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun deserializesHistoricalV1PipelineDownloadTrimAndMetadataJsonBlobs() {
        val legacyDownloadJson = """
            {
              "url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
              "playlistSingleOnly": true,
              "playlistItemIndex": 2,
              "mode": "VIDEO_MP4",
              "maxHeight": 1080,
              "audioFormat": "mp3",
              "embedMetadata": true,
              "embedThumbnail": false,
              "sponsorBlock": true,
              "useAria2c": true
            }
        """.trimIndent()

        val legacyDownload = json.decodeFromString<DownloadSpec>(legacyDownloadJson)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", legacyDownload.url)
        assertEquals(2, legacyDownload.playlistItemIndex)
        assertTrue(legacyDownload.sponsorBlock)
        assertTrue(legacyDownload.sponsorBlockCategories.isEmpty())
        assertEquals(setOf(SponsorBlockCategory.SPONSOR), legacyDownload.effectiveSponsorBlockCategories)
        assertNull(legacyDownload.siteConnectionId)
        assertFalse(legacyDownload.splitByChapters)

        val legacyTrimJson = """
            {
              "startMs": 12000,
              "endMs": 45000,
              "fastKeyframeCopy": true,
              "fadeInOut": false,
              "muteAudio": true
            }
        """.trimIndent()

        val legacyTrim = json.decodeFromString<TrimSpec>(legacyTrimJson)
        assertEquals(12_000L, legacyTrim.startMs)
        assertEquals(45_000L, legacyTrim.endMs)
        assertTrue(legacyTrim.fastKeyframeCopy)
        assertTrue(legacyTrim.muteAudio)
        assertNull(legacyTrim.silenceSkip)

        val legacyPipelineJson = """
            {
              "download": $legacyDownloadJson,
              "convert": {
                "targetExtension": "mp4",
                "videoCodec": "HEVC",
                "audioCodec": "OPUS"
              },
              "trim": $legacyTrimJson,
              "delivery": {
                "saveToDevice": true,
                "copyToClipboard": false,
                "stripMetadata": true,
                "filenameSuffix": "_v1"
              }
            }
        """.trimIndent()

        val legacyPipeline = Json.decodeFromString<PipelineSpec>(legacyPipelineJson)
        assertEquals("mp4", legacyPipeline.convert?.targetExtension)
        assertEquals(VideoCodecChoice.HEVC, legacyPipeline.convert?.videoCodec)
        assertEquals("_v1", legacyPipeline.delivery.filenameSuffix)
        assertNull(legacyPipeline.silenceSkip)
        assertNull(legacyPipeline.trim?.silenceSkip)
        assertTrue(legacyPipeline.hasTransformStage)

        val legacyResolvedMetaJson = """
            {
              "sourceUrl": "https://www.youtube.com/watch?v=abc123",
              "host": "www.youtube.com",
              "title": "Legacy Video",
              "uploader": "Echo",
              "detectedKind": "VIDEO",
              "contentMediaKind": "VIDEO",
              "durationSec": 300,
              "tracks": [
                {
                  "index": 1,
                  "trackId": "abc123",
                  "title": "Track One",
                  "selected": true
                }
              ]
            }
        """.trimIndent()

        val legacyMeta = Json.decodeFromString<ResolvedUrlMetadata>(legacyResolvedMetaJson)
        assertEquals("Legacy Video", legacyMeta.title)
        assertEquals(1, legacyMeta.tracks.size)
        assertTrue(legacyMeta.chapters.isEmpty())
    }

    @Test
    fun roundTripsWave1PipelineAndResolvedLinkModels() {
        val silence = SilenceSkipSpec(enabled = true, thresholdDb = -42, minSilenceMs = 750)
        val categories = setOf(
            SponsorBlockCategory.SPONSOR,
            SponsorBlockCategory.SELFPROMO,
            SponsorBlockCategory.MUSIC_OFFTOPIC
        )
        val spec = PipelineSpec(
            download = DownloadSpec(
                url = "https://www.youtube.com/watch?v=chapterTest",
                mode = DownloadMode.VIDEO_BEST_MKV,
                maxHeight = 1440,
                sponsorBlock = false,
                sponsorBlockCategories = categories,
                siteConnectionId = "conn_yt_premium",
                splitByChapters = true
            ),
            trim = TrimSpec(
                startMs = 5_000L,
                endMs = 95_000L,
                silenceSkip = silence
            ),
            silenceSkip = silence
        )

        val encoded = json.encodeToString(spec)
        val decoded = json.decodeFromString<PipelineSpec>(encoded)
        assertEquals(spec, decoded)
        assertEquals(categories, decoded.download?.effectiveSponsorBlockCategories)
        assertEquals("conn_yt_premium", decoded.download?.siteConnectionId)
        assertTrue(decoded.download?.splitByChapters == true)

        val customChapterSettings = LinkItemSettings(
            mode = DownloadMode.AUDIO_ONLY,
            audioFormat = "opus",
            sponsorBlock = false,
            sponsorBlockCategories = setOf(SponsorBlockCategory.INTRO, SponsorBlockCategory.OUTRO),
            siteConnectionId = "conn_sc",
            splitByChapters = false
        )
        val chapters = listOf(
            ChapterTrackItem(
                index = 1,
                title = "Opening",
                startMs = 0L,
                endMs = 225_000L,
                selected = true,
                customSettings = customChapterSettings
            ),
            ChapterTrackItem(
                index = 2,
                title = "Deep Dive",
                startMs = 3_723_000L,
                endMs = 4_520_000L,
                selected = false
            )
        )

        assertEquals(225_000L, chapters[0].durationMs)
        assertEquals("0:00 - 3:45", chapters[0].formattedRange())
        assertEquals("1:02:03 - 1:15:20", chapters[1].formattedRange())

        val chip = ResolvedLinkChipItem(
            cleanUrl = "https://www.youtube.com/watch?v=chapterTest",
            host = "www.youtube.com",
            resolveState = LinkResolveState.RESOLVED,
            title = "Chaptered Mix",
            settings = LinkItemSettings(
                sponsorBlock = true,
                splitByChapters = true,
                siteConnectionId = "conn_yt_premium"
            ),
            chapters = chapters
        )

        val chipEncoded = json.encodeToString(chip)
        val chipDecoded = json.decodeFromString<ResolvedLinkChipItem>(chipEncoded)
        assertEquals(chip, chipDecoded)
        assertEquals(1, chipDecoded.selectedChapterCount)
        assertEquals(setOf(SponsorBlockCategory.SPONSOR), chipDecoded.settings.effectiveSponsorBlockCategories)
        assertEquals(
            setOf(SponsorBlockCategory.INTRO, SponsorBlockCategory.OUTRO),
            chipDecoded.chapters.first().customSettings?.effectiveSponsorBlockCategories
        )
    }

    @Test
    fun checksEffectiveSponsorBlockCategoriesFallbackAndOverride() {
        val none = DownloadSpec(url = "https://example.com")
        assertTrue(none.effectiveSponsorBlockCategories.isEmpty())

        val legacyOnly = DownloadSpec(url = "https://example.com", sponsorBlock = true)
        assertEquals(setOf(SponsorBlockCategory.SPONSOR), legacyOnly.effectiveSponsorBlockCategories)

        val explicitWins = DownloadSpec(
            url = "https://example.com",
            sponsorBlock = true,
            sponsorBlockCategories = setOf(SponsorBlockCategory.PREVIEW, SponsorBlockCategory.INTERACTION)
        )
        assertEquals(
            setOf(SponsorBlockCategory.PREVIEW, SponsorBlockCategory.INTERACTION),
            explicitWins.effectiveSponsorBlockCategories
        )

        val linkNone = LinkItemSettings()
        assertTrue(linkNone.effectiveSponsorBlockCategories.isEmpty())

        val linkLegacy = LinkItemSettings(sponsorBlock = true)
        assertEquals(setOf(SponsorBlockCategory.SPONSOR), linkLegacy.effectiveSponsorBlockCategories)

        val linkExplicit = LinkItemSettings(
            sponsorBlock = false,
            sponsorBlockCategories = setOf(SponsorBlockCategory.MUSIC_OFFTOPIC)
        )
        assertEquals(setOf(SponsorBlockCategory.MUSIC_OFFTOPIC), linkExplicit.effectiveSponsorBlockCategories)

        assertEquals("sponsor", SponsorBlockCategory.SPONSOR.ytDlpKey)
        assertEquals("selfpromo", SponsorBlockCategory.SELFPROMO.ytDlpKey)
        assertEquals("interaction", SponsorBlockCategory.INTERACTION.ytDlpKey)
        assertEquals("intro", SponsorBlockCategory.INTRO.ytDlpKey)
        assertEquals("outro", SponsorBlockCategory.OUTRO.ytDlpKey)
        assertEquals("preview", SponsorBlockCategory.PREVIEW.ytDlpKey)
        assertEquals("music_offtopic", SponsorBlockCategory.MUSIC_OFFTOPIC.ytDlpKey)
    }

    @Test
    fun checksHasTransformStageAndSummaryLabelWithSilenceSkip() {
        val passthrough = PipelineSpec()
        assertFalse(passthrough.hasTransformStage)
        assertEquals("Passthrough", passthrough.summaryLabel())

        val disabledSilence = PipelineSpec(
            silenceSkip = SilenceSkipSpec(enabled = false),
            trim = TrimSpec(startMs = 0L, endMs = 15_000L, silenceSkip = SilenceSkipSpec(enabled = false))
        )
        assertTrue(disabledSilence.hasTransformStage)
        assertEquals("Trim", disabledSilence.summaryLabel())

        val silenceOnlyDisabled = PipelineSpec(silenceSkip = SilenceSkipSpec(enabled = false))
        assertFalse(silenceOnlyDisabled.hasTransformStage)
        assertEquals("Passthrough", silenceOnlyDisabled.summaryLabel())

        val topLevelSilence = PipelineSpec(silenceSkip = SilenceSkipSpec(enabled = true))
        assertTrue(topLevelSilence.hasTransformStage)
        assertEquals("Silence Skip", topLevelSilence.summaryLabel())

        val trimNestedSilence = PipelineSpec(
            trim = TrimSpec(startMs = 1_000L, endMs = 20_000L, silenceSkip = SilenceSkipSpec(enabled = true))
        )
        assertTrue(trimNestedSilence.hasTransformStage)
        assertEquals("Trim • Silence Skip", trimNestedSilence.summaryLabel())
    }

    @Test
    fun roundTripsWave2DeliveryMetadataLinkSilenceSkipAndAuthFields() {
        val silence = SilenceSkipSpec(enabled = true, thresholdDb = -30, minSilenceMs = 400)
        val spec = PipelineSpec(
            delivery = DeliverySpec(
                saveToDevice = true,
                copyToClipboard = false,
                stripMetadata = false,
                filenameSuffix = "_forge",
                customOutputBaseName = "01 - Track One",
                metadataTitle = "Track One",
                metadataArtist = "Echo",
                metadataAlbum = "Synth Mix",
                metadataTrack = "1/8"
            ),
            silenceSkip = silence
        )
        val encoded = json.encodeToString(spec)
        val decoded = json.decodeFromString<PipelineSpec>(encoded)
        assertEquals(spec, decoded)
        assertEquals("01 - Track One", decoded.delivery.customOutputBaseName)
        assertEquals("Track One", decoded.delivery.metadataTitle)
        assertEquals("Echo", decoded.delivery.metadataArtist)
        assertEquals("Synth Mix", decoded.delivery.metadataAlbum)
        assertEquals("1/8", decoded.delivery.metadataTrack)

        val chip = ResolvedLinkChipItem(
            cleanUrl = "https://www.patreon.com/posts/123",
            host = "patreon.com",
            resolveState = LinkResolveState.FAILED,
            settings = LinkItemSettings(
                mode = DownloadMode.AUDIO_ONLY,
                audioFormat = "opus",
                splitByChapters = true,
                silenceSkip = silence
            ),
            authRequiredReason = "Patron-only audio requires sign-in.",
            authIsPaywall = true,
            authServiceId = "patreon"
        )
        val chipEncoded = json.encodeToString(chip)
        val chipDecoded = json.decodeFromString<ResolvedLinkChipItem>(chipEncoded)
        assertEquals(chip, chipDecoded)
        assertTrue(chipDecoded.settings.compactSummary().contains("Chapters"))
        assertTrue(chipDecoded.settings.compactSummary().contains("Silence Skip"))
        assertEquals("patreon", chipDecoded.authServiceId)
        assertTrue(chipDecoded.authIsPaywall)
    }

    @Test
    fun sanitizeForPresetAndNormalizedJsonStripSourceSpecificFieldsOnly() {
        val rawSpec = PipelineSpec(
            download = DownloadSpec(url = "https://example.com/private?token=secret"),
            convert = ConvertSpec(
                targetExtension = "mp4",
                videoCodec = VideoCodecChoice.HEVC,
                audioCodec = AudioCodecChoice.AAC
            ),
            compress = CompressSpec(
                mode = CompressMode.QUALITY,
                videoCrf = 25,
                maxDimensionPx = 1080
            ),
            trim = TrimSpec(
                startMs = 12_000L,
                endMs = 44_000L,
                fadeInOut = true,
                muteAudio = true,
                silenceSkip = SilenceSkipSpec(enabled = true, thresholdDb = -35, minSilenceMs = 500)
            ),
            crop = CropSpec(
                preset = AspectPreset.RATIO_16_9,
                rect = CropRectNormalized(0.1f, 0.1f, 0.9f, 0.9f)
            ),
            delivery = DeliverySpec(
                saveToDevice = true,
                stripMetadata = true,
                customOutputBaseName = "MySecretVideo"
            )
        )

        val sanitized = rawSpec.sanitizeForPreset()
        assertNull(sanitized.download)
        assertNull(sanitized.crop)
        assertEquals(0L, sanitized.trim?.startMs)
        assertNull(sanitized.trim?.endMs)
        assertTrue(sanitized.trim?.fadeInOut == true)
        assertTrue(sanitized.trim?.muteAudio == true)
        assertTrue(sanitized.trim?.silenceSkip?.enabled == true)
        assertTrue(sanitized.delivery.stripMetadata)
        assertNull(sanitized.delivery.customOutputBaseName)

        val normalizedJson = rawSpec.toNormalizedJson()
        val roundTripped = json.decodeFromString<PipelineSpec>(normalizedJson)
        assertEquals(sanitized, roundTripped)

        // Plain range-only trim (no audio/fade/silence flags) is completely stripped from preset
        val rangeOnlySpec = PipelineSpec(
            trim = TrimSpec(startMs = 5_000L, endMs = 15_000L)
        )
        assertNull(rangeOnlySpec.sanitizeForPreset().trim)
    }
}
