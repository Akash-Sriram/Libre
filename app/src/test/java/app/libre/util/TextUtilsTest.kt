package app.libre.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextUtilsTest {

    @Test
    fun formatBitrate_nullReturnsEmpty() {
        assertEquals("", TextUtils.formatBitrate(null))
    }

    @Test
    fun formatBitrate_below1024Shows0kbps() {
        assertEquals("0kbps", TextUtils.formatBitrate(999))
    }

    @Test
    fun formatBitrate_exactly1024Shows1kbps() {
        assertEquals("1kbps", TextUtils.formatBitrate(1024))
    }

    @Test
    fun formatBitrate_128000() {
        assertEquals("125kbps", TextUtils.formatBitrate(128_000))
    }

    @Test
    fun formatBitrate_320000() {
        assertEquals("312kbps", TextUtils.formatBitrate(320_000))
    }

    @Test
    fun limitTextToLength_shorterThanLimit_unchanged() {
        assertEquals("hi", TextUtils.limitTextToLength("hi", 10))
    }

    @Test
    fun limitTextToLength_exactlyAtLimit_unchanged() {
        assertEquals("hello", TextUtils.limitTextToLength("hello", 5))
    }

    @Test
    fun limitTextToLength_overLimit_appendsEllipsis() {
        assertEquals("hello\u2026", TextUtils.limitTextToLength("hello world", 5))
    }

    @Test
    fun limitTextToLength_longTitle_truncated() {
        val title = "A Very Long Song Title That Should Be Truncated"
        val result = TextUtils.limitTextToLength(title, 20)
        assertEquals(21, result.length)
        assertTrue(result.endsWith("\u2026"))
    }

    @Test
    fun limitTextToLength_emptyString() {
        assertEquals("", TextUtils.limitTextToLength("", 5))
    }

    @Test
    fun getFileSafeTimeStampNow_matchesPattern() {
        val ts = TextUtils.getFileSafeTimeStampNow()
        val regex = Regex("""\d{4}-\d{2}-\d{2}-\d{2}_\d{2}_\d{2}""")
        assertTrue(regex.matches(ts))
    }

    @Test
    fun getFileSafeTimeStampNow_noForbiddenChars() {
        val ts = TextUtils.getFileSafeTimeStampNow()
        val forbidden = setOf(':', '/', '\\', '*', '?', '"', '<', '>', '|')
        assertTrue(ts.filter { it in forbidden }.isEmpty())
    }
}
