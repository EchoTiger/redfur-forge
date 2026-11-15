package com.fissal.forge.domain.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlBatchParserTest {

    @Test
    fun extractsMultipleUrlsFromMessyParagraphAndDeduplicates() {
        val raw = """
            Check this clip https://www.youtube.com/watch?v=dQw4w9WgXcQ&si=abcd1234&utm_source=share,
            and this one too: https://youtu.be/dQw4w9WgXcQ?t=42!
            Duplicate: https://www.youtube.com/watch?v=dQw4w9WgXcQ&utm_medium=social
        """.trimIndent()

        val parsed = UrlBatchParser.extractUrls(raw, stripTracking = true)
        assertEquals(2, parsed.size)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", parsed[0].cleanUrl)
        assertEquals("https://youtu.be/dQw4w9WgXcQ?t=42", parsed[1].cleanUrl)
        assertFalse(parsed[0].isPlaylistLike)
    }

    @Test
    fun detectsPlaylistUrlsAndPreservesListParam() {
        val raw = "https://www.youtube.com/watch?v=abc123&list=PLxyz999&si=trackme"
        val parsed = UrlBatchParser.extractUrls(raw, stripTracking = true)
        assertEquals(1, parsed.size)
        assertTrue(parsed[0].isPlaylistLike)
        assertEquals("https://www.youtube.com/watch?v=abc123&list=PLxyz999", parsed[0].cleanUrl)
    }
}
