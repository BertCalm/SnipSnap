package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The board that decides which job the dock draws when several are running:
 * the latest one begun that has not ended, and a progress update never moves
 * a job out of its place.
 */
class JobBoardTest {

    private val a = Busy("A", done = 1, total = 4)
    private val b = Busy("B")

    @Test
    fun `nothing running is null`() {
        assertNull(JobBoard().current)
    }

    @Test
    fun `the latest job shows`() {
        val board = JobBoard()
        board.begin(a)
        board.begin(b)
        assertEquals(b, board.current)
    }

    @Test
    fun `ending the latest shows the one before`() {
        val board = JobBoard()
        board.begin(a)
        val second = board.begin(b)
        board.end(second)
        assertEquals(a, board.current)
    }

    @Test
    fun `ending the last one leaves nothing running`() {
        val board = JobBoard()
        val only = board.begin(a)
        board.end(only)
        assertNull(board.current)
    }

    @Test
    fun `ending an earlier job leaves the latest showing`() {
        val board = JobBoard()
        val first = board.begin(a)
        board.begin(b)
        board.end(first)
        assertEquals(b, board.current)
    }

    @Test
    fun `an update keeps its place`() {
        val board = JobBoard()
        val first = board.begin(a)
        val second = board.begin(b)
        val moved = a.copy(done = 3)
        board.update(first, moved)
        assertEquals(b, board.current, "the older job moved on but did not jump the queue")
        board.end(second)
        assertEquals(moved, board.current)
    }

    @Test
    fun `an update to the latest job shows at once`() {
        val board = JobBoard()
        board.begin(a)
        val second = board.begin(b)
        val moved = b.copy(label = "B AGAIN")
        board.update(second, moved)
        assertEquals(moved, board.current)
    }

    @Test
    fun `ending an unknown or ended id changes nothing`() {
        val board = JobBoard()
        board.begin(a)
        val second = board.begin(b)
        board.end(second)
        board.end(second)
        board.end(9_999L)
        board.end(-1L)
        assertEquals(a, board.current)
    }

    @Test
    fun `updating an unknown or ended id changes nothing`() {
        val board = JobBoard()
        val first = board.begin(a)
        val second = board.begin(b)
        board.end(second)
        board.update(second, Busy("GHOST"))
        board.update(9_999L, Busy("GHOST"))
        assertEquals(a, board.current, "an ended job is not brought back by a late update")
        board.end(first)
        assertNull(board.current)
    }

    @Test
    fun `ids are unique`() {
        val board = JobBoard()
        val ids = mutableSetOf<Long>()
        repeat(50) {
            val id = board.begin(a)
            assertTrue(ids.add(id), "id $id was handed out twice")
            // Ended at once, so an id that was reused after its job ended would show here.
            if (it % 2 == 0) board.end(id)
        }
        assertEquals(50, ids.size)
    }
}
