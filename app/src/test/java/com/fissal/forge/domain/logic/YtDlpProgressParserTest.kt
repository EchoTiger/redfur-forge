package com.fissal.forge.domain.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpProgressParserTest {

    @Test
    fun emitsIndeterminateSnapshotsDuringHandshakeBeforeBytesTransfer() {
        val tracker = YtDlpProgressTracker(expectsSeparateAudioStream = true)

        val s1 = tracker.onLine("[youtube] Extracting URL: https://www.youtube.com/watch?v=abc123")
        assertEquals(-1f, s1?.percent)
        assertEquals("Resolving media link...", s1?.statusText)

        val s2 = tracker.onLine("[youtube] abc123: Downloading webpage")
        assertEquals(-1f, s2?.percent)
        assertEquals("Negotiating video extractor...", s2?.statusText)

        val s3 = tracker.onLine("[SponsorBlock] Fetching segments")
        assertEquals(-1f, s3?.percent)
        assertEquals("Checking SponsorBlock segments...", s3?.statusText)

        val s4 = tracker.onLine("[download] Destination: /tmp/video.f137.mp4")
        assertEquals(-1f, s4?.percent)

        val s5 = tracker.onLine("[download]   0.0% of  100.00MiB at  Unknown B/s ETA Unknown")
        assertEquals(-1f, s5?.percent)
    }

    @Test
    fun mapsVideoAndAudioStreamsMonotonicallyWithoutJumpingBackward() {
        val tracker = YtDlpProgressTracker(expectsSeparateAudioStream = true)

        tracker.onLine("[download] Destination: /tmp/clip.f137.mp4")
        val p10 = tracker.onLine("[download]  10.0% of  50.00MiB at    4.50MiB/s ETA 00:09")!!
        assertTrue(p10.percent in 9f..10f)
        assertTrue(p10.statusText.contains("10%"))
        assertTrue(p10.statusText.contains("4.50MiB/s"))
        assertTrue(p10.statusText.contains("ETA 00:09"))

        val p100 = tracker.onLine("[download] 100% of 50.00MiB in 00:05 at 10.00MiB/s")!!
        assertEquals(86f, p100.percent, 0.01f)

        val nextStream = tracker.onLine("[download] Destination: /tmp/clip.f140.m4a")!!
        assertEquals(86f, nextStream.percent, 0.01f)

        val a50 = tracker.onLine("[download]  50.0% of   4.00MiB at    2.00MiB/s ETA 00:01")!!
        assertTrue(a50.percent >= p100.percent)
        assertTrue(a50.statusText.contains("audio", ignoreCase = true))

        val merge = tracker.onLine("[Merger] Merging formats into \"/tmp/clip.mp4\"")!!
        assertEquals(97f, merge.percent, 0.01f)
        assertEquals("Merging video & audio...", merge.statusText)
    }

    @Test
    fun parsesAria2cProgressLinesAndIgnoresUnknownNoise() {
        val tracker = YtDlpProgressTracker(expectsSeparateAudioStream = false)

        val aria = tracker.onLine("[#4a1b2c 12MiB/40MiB(30%) CN:8 DL:5.2MiB ETA:5s]")!!
        assertEquals(29.5f, aria.percent, 0.2f)
        assertEquals("Downloading 30% • 5.2MiB/s • ETA 5s", aria.statusText)

        assertNull(tracker.onLine("   "))
    }
}
