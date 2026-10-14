package app.sbh.reader.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlNormalizerTest {
    @Test
    fun normalizesWhatTheUserTypes() {
        assertEquals("https://sbh.local.example.org", UrlNormalizer.normalize("sbh.local.example.org"))
        assertEquals("https://sbh.example.org", UrlNormalizer.normalize("  https://sbh.example.org/  "))
        assertEquals("https://sbh.example.org", UrlNormalizer.normalize("https://sbh.example.org/api/v1/"))
        assertEquals("http://100.84.182.8:8742", UrlNormalizer.normalize("http://100.84.182.8:8742"))
        assertEquals("https://example.org/sbh", UrlNormalizer.normalize("https://example.org/sbh/"))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(UrlNormalizer.normalize(""))
        assertNull(UrlNormalizer.normalize("   "))
        assertNull(UrlNormalizer.normalize("ftp://example.org"))
        assertNull(UrlNormalizer.normalize("not a url"))
    }

    @Test
    fun detectsCleartext() {
        assertTrue(UrlNormalizer.isCleartext("http://x"))
        assertFalse(UrlNormalizer.isCleartext("https://x"))
    }
}
