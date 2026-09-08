package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LandingTest {

    @Test
    fun `an ordinary name comes through as it is`() {
        assertEquals("break.wav", Landing.safeName("break.wav"))
        assertEquals("My Loop 01.mp3", Landing.safeName("My Loop 01.mp3"))
        // A share arrives called what the sender called it: brackets, the
        // double underscore and a leading dot all survive intact, which is
        // the whole difference between this and an MPC stem.
        assertEquals("SnipSnap Keys_[TrackData].zip", Landing.safeName("SnipSnap Keys_[TrackData].zip"))
        assertEquals("My__Loop.wav", Landing.safeName("My__Loop.wav"))
        assertEquals(".hidden", Landing.safeName(".hidden"))
    }

    @Test
    fun `a path in front of the name is dropped, not escaped`() {
        assertEquals("passwd", Landing.safeName("../../etc/passwd"))
        assertEquals("passwd", Landing.safeName("/etc/passwd"))
        assertEquals("win.ini", Landing.safeName("..\\..\\windows\\win.ini"))
        assertEquals("bar", Landing.safeName("foo/../bar"))
        // Mixed separators, because a name is a string and nobody promised
        // it came from this platform.
        assertEquals("x", Landing.safeName("a/b\\c/d\\x"))
    }

    @Test
    fun `the names that mean a folder become the fallback`() {
        assertEquals(Landing.FALLBACK, Landing.safeName("."))
        assertEquals(Landing.FALLBACK, Landing.safeName(".."))
        assertEquals(Landing.FALLBACK, Landing.safeName("..."))
        assertEquals(Landing.FALLBACK, Landing.safeName("../.."))
        assertEquals(Landing.FALLBACK, Landing.safeName(""))
        assertEquals(Landing.FALLBACK, Landing.safeName("   "))
        assertEquals(Landing.FALLBACK, Landing.safeName("///"))
    }

    @Test
    fun `no result carries a separator, whatever went in`() {
        // The property that matters, over every shape I can think to send.
        val hostile = listOf(
            "../../../../../../etc/shadow",
            "..%2f..%2fetc",
            "a\u0000b/c",
            "\\\\server\\share\\file.wav",
            "name\nwith\nnewlines/x",
            "\u202e" + "gnp.exe",  // right-to-left override
            "..",
            "....//....//x",
            "C:\\Windows\\System32\\drivers\\etc\\hosts",
        )
        for (name in hostile) {
            val safe = Landing.safeName(name)
            assertFalse(safe.contains('/'), "'$name' -> '$safe' still has a forward slash")
            assertFalse(safe.contains('\\'), "'$name' -> '$safe' still has a backslash")
            assertTrue(safe.isNotBlank(), "'$name' -> blank")
            assertFalse(safe.all { it == '.' }, "'$name' -> '$safe' is all dots")
        }
    }

    @Test
    fun `anything outside the alphabet becomes an underscore`() {
        assertEquals("a_b", Landing.safeName("a\u0000b"))
        assertEquals("caf_.wav", Landing.safeName("café.wav"))
        assertEquals("f_le_.wav", Landing.safeName("f;le\$.wav"))
        // A leading dot is a real filename, not a traversal - it stays.
        assertEquals(".hidden", Landing.safeName(".hidden"))
        assertEquals("..leading", Landing.safeName("..leading"))
    }
}
