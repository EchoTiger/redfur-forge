package com.fissal.forge.domain.logic

import com.fissal.forge.domain.model.DetectedLinkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpJsonParserTest {

    @Test
    fun parsesPlaylistEntriesAndNormalizesYoutubeTrackUrls() {
        val sampleJson = """
            WARNING: some yt-dlp stderr notice
            {
              "_type": "playlist",
              "title": "Fissal Synthwave Mix",
              "uploader": "Echo",
              "thumbnail": "https://i.ytimg.com/vi/abc123/hqdefault.jpg",
              "entries": [
                {"id": "trackOne11", "title": "Night Relay", "duration": 185, "uploader": "Artist A", "ie_key": "Youtube"},
                {"id": "trackTwo22", "title": "Clockwork Stars", "duration": 240, "url": "https://www.youtube.com/watch?v=trackTwo22"}
              ]
            }
        """.trimIndent()

        val result = YtDlpJsonParser.parse("https://www.youtube.com/playlist?list=PL123", sampleJson)
        assertEquals(DetectedLinkKind.PLAYLIST, result.detectedKind)
        assertEquals("Fissal Synthwave Mix", result.title)
        assertEquals(425L, result.durationSec)
        assertEquals(2, result.tracks.size)
        assertEquals("https://www.youtube.com/watch?v=trackOne11", result.tracks[0].trackUrl)
        assertEquals("https://www.youtube.com/watch?v=trackTwo22", result.tracks[1].trackUrl)
        assertTrue(result.tracks.all { it.selected })
    }

    @Test
    fun classifiesAudioOnlyStreamsAndAudioHostsAsAudio() {
        val scJson = """
            {
              "title": "Alfiq Ambient",
              "uploader": "SoundCreator",
              "vcodec": "none",
              "acodec": "opus",
              "ext": "opus",
              "duration": 320.5
            }
        """.trimIndent()

        val result = YtDlpJsonParser.parse("https://soundcloud.com/echo/alfiq-ambient", scJson)
        assertEquals(DetectedLinkKind.AUDIO, result.detectedKind)
        assertEquals(DetectedLinkKind.AUDIO, result.contentMediaKind)
        assertEquals("soundcloud.com", result.host)
        assertEquals(320L, result.durationSec)
        assertNotNull(result.title)
    }

    @Test
    fun infersContentHintsBeforeResolution() {
        assertEquals(
            DetectedLinkKind.AUDIO,
            YtDlpJsonParser.inferContentMediaHint("https://music.youtube.com/watch?v=xyz")
        )
        assertEquals(
            DetectedLinkKind.AUDIO,
            YtDlpJsonParser.inferContentMediaHint("https://cdn.example.org/tracks/song.flac")
        )
        assertEquals(
            DetectedLinkKind.IMAGE,
            YtDlpJsonParser.inferContentMediaHint("https://images.example.org/shot.webp")
        )
        assertEquals(
            DetectedLinkKind.VIDEO,
            YtDlpJsonParser.inferContentMediaHint("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        )
    }

    @Test
    fun extractsStructuredChaptersOnSingleVideoWithExactMsAndClamping() {
        val videoJson = """
            {
              "title": "Clockwork Workshop",
              "uploader": "Echo",
              "duration": 300.5,
              "chapters": [
                {"title": "  Opening Gear  ", "start_time": 0.0, "end_time": 65.25},
                {"title": "   ", "start_time": 65.25, "end_time": 180.5},
                {"title": "Invalid Zero", "start_time": 200.0, "end_time": 200.0},
                {"title": "Grand Finale", "start_time": 180.5, "end_time": 350.0}
              ]
            }
        """.trimIndent()

        val result = YtDlpJsonParser.parse("https://www.youtube.com/watch?v=abc123", videoJson)
        assertEquals(3, result.chapters.size)

        assertEquals(1, result.chapters[0].index)
        assertEquals("Opening Gear", result.chapters[0].title)
        assertEquals(0L, result.chapters[0].startMs)
        assertEquals(65_250L, result.chapters[0].endMs)
        assertTrue(result.chapters[0].selected)

        assertEquals(2, result.chapters[1].index)
        assertEquals("Chapter 2", result.chapters[1].title)
        assertEquals(65_250L, result.chapters[1].startMs)
        assertEquals(180_500L, result.chapters[1].endMs)

        assertEquals(3, result.chapters[2].index)
        assertEquals("Grand Finale", result.chapters[2].title)
        assertEquals(180_500L, result.chapters[2].startMs)
        assertEquals(300_500L, result.chapters[2].endMs)
    }

    @Test
    fun fallsBackToDescriptionTracklistWithMixedFormatsAndUrlImmunity() {
        val description = """
            Listen live at https://stream.example.com:8080/live?t=04:20 or http://mirror.local:8443/01:15
            Best jump at 01:10 and 02:20 in the chat!
            00:00 - Intro
            1. [03:45] Synth Wave
            Track Two (04:18)
            01. Opening Track - 05:30
            Boss Theme 1:12:05
            01:15:00 - 01:18:30 Finale
            01:30:00 Past Duration Should Drop
        """.trimIndent()

        val videoJson = """
            {
              "title": "Long Electronic Mix",
              "duration": 4800,
              "chapters": [
                {"title": "Only One Chapter", "start_time": 0, "end_time": 4800}
              ],
              "description": ${kotlinx.serialization.json.JsonPrimitive(description)}
            }
        """.trimIndent()

        val result = YtDlpJsonParser.parse("https://www.youtube.com/watch?v=mix999", videoJson)
        assertEquals(6, result.chapters.size)

        assertEquals("Intro", result.chapters[0].title)
        assertEquals(0L, result.chapters[0].startMs)
        assertEquals(225_000L, result.chapters[0].endMs)

        assertEquals("Synth Wave", result.chapters[1].title)
        assertEquals(225_000L, result.chapters[1].startMs)
        assertEquals(258_000L, result.chapters[1].endMs)

        assertEquals("Track Two", result.chapters[2].title)
        assertEquals(258_000L, result.chapters[2].startMs)
        assertEquals(330_000L, result.chapters[2].endMs)

        assertEquals("Opening Track", result.chapters[3].title)
        assertEquals(330_000L, result.chapters[3].startMs)
        assertEquals(4_325_000L, result.chapters[3].endMs)

        assertEquals("Boss Theme", result.chapters[4].title)
        assertEquals(4_325_000L, result.chapters[4].startMs)
        assertEquals(4_500_000L, result.chapters[4].endMs)

        assertEquals("Finale", result.chapters[5].title)
        assertEquals(4_500_000L, result.chapters[5].startMs)
        assertEquals(4_800_000L, result.chapters[5].endMs)
    }

    @Test
    fun descriptionParserHandlesUnsortedDuplicatesAndNoDurationLastChapter() {
        val desc = """
            125:30 - Late Encore
            00:00 - Warmup
            00:00 - Warmup Duplicate
            05:00 - 08:15 Middle Jam
        """.trimIndent()

        val chapters = YtDlpJsonParser.parseChaptersFromDescription(desc, durationSec = null)
        assertEquals(3, chapters.size)

        assertEquals(0L, chapters[0].startMs)
        assertEquals(300_000L, chapters[0].endMs)
        assertEquals("Warmup", chapters[0].title)

        assertEquals(300_000L, chapters[1].startMs)
        assertEquals(495_000L, chapters[1].endMs)
        assertEquals("Middle Jam", chapters[1].title)

        assertEquals(7_530_000L, chapters[2].startMs)
        assertEquals(7_530_000L + 240_000L, chapters[2].endMs)
        assertEquals("Late Encore", chapters[2].title)

        // Single valid timestamp must not emit chapters
        assertTrue(YtDlpJsonParser.parseChaptersFromDescription("00:00 Only One", 120L).isEmpty())
    }

    @Test
    fun ignoresChaptersGracefullyOnPlaylists() {
        val playlistJson = """
            {
              "_type": "playlist",
              "title": "Fissal Synthwave Mix",
              "duration": 600,
              "description": "00:00 - Part 1\n03:00 - Part 2",
              "chapters": [
                {"title": "Should Be Ignored", "start_time": 0, "end_time": 300},
                {"title": "Also Ignored", "start_time": 300, "end_time": 600}
              ],
              "entries": [
                {"id": "trackOne11", "title": "Night Relay", "duration": 300, "ie_key": "Youtube"},
                {"id": "trackTwo22", "title": "Clockwork Stars", "duration": 300, "ie_key": "Youtube"}
              ]
            }
        """.trimIndent()

        val result = YtDlpJsonParser.parse("https://www.youtube.com/playlist?list=PL123", playlistJson)
        assertEquals(DetectedLinkKind.PLAYLIST, result.detectedKind)
        assertEquals(2, result.tracks.size)
        assertTrue(result.chapters.isEmpty())
    }

    @Test
    fun descriptionParserPreservesNumericSongTitlesWithoutMisparsingRanges() {
        val desc = """
            00:00 - 21 - Adele
            03:45 - 1979
            08:05 - 08:30 Transition Skit
            10:00 100 Ways
        """.trimIndent()

        val chapters = YtDlpJsonParser.parseChaptersFromDescription(desc, durationSec = 780L)
        assertEquals(4, chapters.size)
        assertEquals("21 - Adele", chapters[0].title)
        assertEquals(0L, chapters[0].startMs)
        assertEquals(225_000L, chapters[0].endMs)

        assertEquals("1979", chapters[1].title)
        assertEquals(225_000L, chapters[1].startMs)
        assertEquals(485_000L, chapters[1].endMs)

        assertEquals("Transition Skit", chapters[2].title)
        assertEquals(485_000L, chapters[2].startMs)
        assertEquals(510_000L, chapters[2].endMs)

        assertEquals("100 Ways", chapters[3].title)
        assertEquals(600_000L, chapters[3].startMs)
        assertEquals(780_000L, chapters[3].endMs)
    }

    @Test
    fun redactsUrlCredentialsAndTokensFromFallbackTitles() {
        val secretUrl = "https://user:s3cr3tPass@cdn.example.com/v?token=tok123&sig=abc"
        val fallbackEmpty = YtDlpJsonParser.parse(secretUrl, "")
        assertFalse(fallbackEmpty.title.contains("s3cr3tPass"))
        assertFalse(fallbackEmpty.title.contains("tok123"))
        assertFalse(fallbackEmpty.title.contains("abc"))

        val titlelessJson = """{"id":"vid1","duration":12}"""
        val fallbackUntitled = YtDlpJsonParser.parse(secretUrl, titlelessJson)
        assertFalse(fallbackUntitled.title.contains("s3cr3tPass"))
        assertFalse(fallbackUntitled.title.contains("tok123"))
        assertTrue(fallbackUntitled.title.contains("[REDACTED]"))
    }
}
