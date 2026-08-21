package app.libre.extensions

import org.junit.Assert.assertEquals
import org.junit.Test

class MoveExtTest {

    @Test
    fun moveForward_shiftsRight() {
        val list = mutableListOf("A", "B", "C", "D")
        list.move(0, 2)
        assertEquals(listOf("B", "C", "A", "D"), list)
    }

    @Test
    fun moveBackward_shiftsLeft() {
        val list = mutableListOf("A", "B", "C", "D")
        list.move(3, 1)
        assertEquals(listOf("A", "D", "B", "C"), list)
    }

    @Test
    fun moveSamePosition_noChange() {
        val list = mutableListOf("A", "B", "C")
        list.move(1, 1)
        assertEquals(listOf("A", "B", "C"), list)
    }

    @Test
    fun moveFirstToLast() {
        val list = mutableListOf("A", "B", "C", "D")
        list.move(0, 3)
        assertEquals(listOf("B", "C", "D", "A"), list)
    }

    @Test
    fun moveLastToFirst() {
        val list = mutableListOf("A", "B", "C", "D")
        list.move(3, 0)
        assertEquals(listOf("D", "A", "B", "C"), list)
    }

    @Test
    fun move_preservesListSize() {
        val list = mutableListOf(1, 2, 3, 4, 5)
        list.move(1, 3)
        assertEquals(5, list.size)
    }

    @Test
    fun move_singleElement_noOp() {
        val list = mutableListOf("A")
        list.move(0, 0)
        assertEquals(listOf("A"), list)
    }
}
