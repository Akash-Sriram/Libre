package app.libre.player.parser

import org.junit.Assert.*
import org.junit.Test
import video_streaming.UmpPartId

class ParserTest {
    @Test
    fun testReadVarint() {
        val testCases = listOf(
            // 1 byte long varint
            Pair(byteArrayOf(0x01), 1u),
            Pair(byteArrayOf(0x4F), 79u),
            // 2 byte long varint
            Pair(byteArrayOf(0x96.toByte(), 0), 22u),
            Pair(byteArrayOf(0x80.toByte(), 0x01), 64u),
            Pair(byteArrayOf(0x8A.toByte(), 0x7F), 8138u),
            Pair(byteArrayOf(0xBF.toByte(), 0x7F), 8191u),
            // 3 byte long varint
            Pair(byteArrayOf(0xC0.toByte(), 0x80.toByte(), 0x01), 12288u),
            Pair(byteArrayOf(0xDF.toByte(), 0x7F, 0xFF.toByte()), 2093055u),
            // 4 byte long varint
            Pair(byteArrayOf(0xE0.toByte(), 0x80.toByte(), 0x80.toByte(), 0x01), 1574912u),
            Pair(byteArrayOf(0xEF.toByte(), 0x7F, 0xFF.toByte(), 0xFF.toByte()), 268433407u),
            // 5 byte long varint
            Pair(byteArrayOf(0xF0.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x01), 25198720u),
            Pair(byteArrayOf(0xFF.toByte(), 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), 4294967167u)
        )

        for ((data, expected) in testCases) {
            val parser = UmpParser(data)
            val result = parser.readVarint()
            assertEquals("Failed for input: ${data.joinToString { it.toString() }}", expected, result)
        }
    }

    @Test
    fun testReadPart() {
        val parser = UmpParser(byteArrayOf(20, 1, 42))
        val part = parser.readPart()

        assertNotNull(part)
        assertEquals(UmpPartId.UMPPartId.MEDIA_HEADER, part?.type)
        assertArrayEquals(byteArrayOf(42), part?.data)
        assertTrue(parser.data().isEmpty())
    }

    // ── New edge-case tests ──────────────────────────────────────────────────

    @Test
    fun readPart_emptyBuffer_returnsNull() {
        val parser = UmpParser(byteArrayOf())
        assertNull(parser.readPart())
    }

    @Test
    fun readVarint_emptyBuffer_returnsNull() {
        val parser = UmpParser(byteArrayOf())
        assertNull(parser.readVarint())
    }

    @Test
    fun readPart_truncatedDataField_returnsNull() {
        // type=20 (MEDIA_HEADER), size=5 but only 2 data bytes provided
        val parser = UmpParser(byteArrayOf(20, 5, 1, 2))
        assertNull(parser.readPart())
    }

    @Test
    fun readPart_twoSequentialParts_bothParsed() {
        // Part 1: type=20, size=1, data=[42]
        // Part 2: type=20, size=1, data=[99]
        val bytes = byteArrayOf(20, 1, 42, 20, 1, 99)
        val parser = UmpParser(bytes)

        val part1 = parser.readPart()
        val part2 = parser.readPart()

        assertNotNull(part1)
        assertNotNull(part2)
        assertArrayEquals(byteArrayOf(42), part1?.data)
        assertArrayEquals(byteArrayOf(99), part2?.data)
        assertTrue(parser.data().isEmpty())
    }

    @Test
    fun readPart_emptyDataField_returnsPartWithEmptyData() {
        // type=20, size=0 (no data bytes)
        val parser = UmpParser(byteArrayOf(20, 0))
        val part = parser.readPart()
        assertNotNull(part)
        assertEquals(0, part?.data?.size)
    }
}
