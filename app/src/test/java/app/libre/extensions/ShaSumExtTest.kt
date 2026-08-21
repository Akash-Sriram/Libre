package app.libre.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShaSumExtTest {

    @Test
    fun sha256Sum_hello_knownHash() {
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            "hello".sha256Sum()
        )
    }

    @Test
    fun sha256Sum_emptyString_knownHash() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "".sha256Sum()
        )
    }

    @Test
    fun sha256Sum_isDeterministic() {
        val input = "MyLibreTube"
        assertEquals(input.sha256Sum(), input.sha256Sum())
    }

    @Test
    fun sha256Sum_differForDifferentInputs() {
        assertNotEquals("hello".sha256Sum(), "Hello".sha256Sum())
    }

    @Test
    fun sha256Sum_alwaysReturns64Chars() {
        listOf("", "a", "hello world", "abc123!@#").forEach { input ->
            assertEquals("Expected 64 hex chars for: '$input'", 64, input.sha256Sum().length)
        }
    }

    @Test
    fun sha256Sum_outputIsLowercaseHex() {
        val result = "test".sha256Sum()
        assertTrue("Should be lowercase hex", result.all { it.isDigit() || it in 'a'..'f' })
    }
}
