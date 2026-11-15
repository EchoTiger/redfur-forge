package com.fissal.forge.domain.logic

import com.fissal.forge.domain.model.DetectedLinkKind
import org.junit.Assert.assertEquals
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
}
