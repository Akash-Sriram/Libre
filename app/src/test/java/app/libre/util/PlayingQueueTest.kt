package app.libre.util

import androidx.media3.common.Player
import app.libre.api.obj.StreamItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlayingQueueTest {

    private fun createStream(id: String, title: String = "Title"): StreamItem {
        return StreamItem(
            url = "/watch?v=" + id,
            title = title + " " + id,
            uploaderName = "Artist " + id,
            duration = 200L
        )
    }

    @Before
    fun setup() {
        PlayingQueue.clear()
        PlayingQueue.repeatMode = Player.REPEAT_MODE_OFF
    }

    @Test
    fun testAddAndSize() {
        val s1 = createStream("video1")
        val s2 = createStream("video2")
        PlayingQueue.add(s1, s2)

        assertEquals(2, PlayingQueue.size())
        assertEquals(s1, PlayingQueue.get(0))
        assertEquals(s2, PlayingQueue.get(1))
    }

    @Test
    fun testUpdateCurrentAndNavigation() {
        val s1 = createStream("video1")
        val s2 = createStream("video2")
        val s3 = createStream("video3")
        PlayingQueue.add(s1, s2, s3)
        PlayingQueue.updateCurrent(s2)

        assertEquals(1, PlayingQueue.currentIndex())
        assertEquals("video3", PlayingQueue.getNext())
        assertEquals("video1", PlayingQueue.getPrev())
        assertTrue(PlayingQueue.hasNext())
        assertTrue(PlayingQueue.hasPrev())
    }

    @Test
    fun testAddAsNextInsertsDirectlyAfterCurrent() {
        val s1 = createStream("video1")
        val s2 = createStream("video2")
        val s3 = createStream("video3")
        val nextStream = createStream("videoNext")

        PlayingQueue.add(s1, s2, s3)
        PlayingQueue.updateCurrent(s1)
        PlayingQueue.addAsNext(nextStream)

        assertEquals(4, PlayingQueue.size())
        assertEquals(nextStream, PlayingQueue.get(1))
        assertEquals(s2, PlayingQueue.get(2))
    }

    @Test
    fun testMoveChangesItemPositionsCorrectly() {
        val s1 = createStream("video1")
        val s2 = createStream("video2")
        val s3 = createStream("video3")
        PlayingQueue.add(s1, s2, s3)

        PlayingQueue.move(0, 2)
        assertEquals(s2, PlayingQueue.get(0))
        assertEquals(s3, PlayingQueue.get(1))
        assertEquals(s1, PlayingQueue.get(2))
    }

    @Test
    fun testRepeatModeLoopsQueue() {
        val s1 = createStream("video1")
        val s2 = createStream("video2")
        PlayingQueue.add(s1, s2)
        PlayingQueue.updateCurrent(s2)

        assertNull(PlayingQueue.getNext())

        PlayingQueue.repeatMode = Player.REPEAT_MODE_ALL
        assertEquals("video1", PlayingQueue.getNext())
    }

    @Test
    fun testClearRemovesAllItemsAndResetsCurrent() {
        val s1 = createStream("video1")
        PlayingQueue.add(s1)
        PlayingQueue.updateCurrent(s1)

        PlayingQueue.clear()
        assertTrue(PlayingQueue.isEmpty())
        assertNull(PlayingQueue.getCurrent())
    }
}