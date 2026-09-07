package com.snipsnap.kit

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SafePathTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("safepath").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    @Test
    fun `plain child names resolve inside the folder`() {
        val f = SafePath.child(temp, "A01_Kick_01.wav")
        assertEquals(temp.canonicalFile, f.canonicalFile.parentFile)
        // Interior spaces are legal MPC names.
        assertTrue(SafePath.isSafe("Session Kit.wav"))
        assertTrue(SafePath.isSafe("808 & Friends.wav"))
    }

    @Test
    fun `every escape shape is refused`() {
        val escapes = listOf(
            "../evil.wav",
            "../../evil.wav",
            "a/b.wav",
            "a\\b.wav",
            "/etc/passwd",
            "..",
            ".",
            "C:evil.wav",
            "  ",
            "",
            " leading.wav",
            "trailing.wav ",
        )
        for (name in escapes) {
            assertTrue(!SafePath.isSafe(name), "isSafe should reject '$name'")
            assertFailsWith<BadPathException>("child should reject '$name'") {
                SafePath.child(temp, name)
            }
        }
    }

    @Test
    fun `a symlinked-out name cannot resolve past the folder`() {
        // Even a name that canonicalises elsewhere is caught by the
        // parent-equals-dir check, not just the string rules.
        assertFailsWith<BadPathException> { SafePath.child(temp, "sub/../../out.wav") }
    }

    @Test
    fun `basename flattens any path to a safe last segment`() {
        // The format is loose - both a legit shared-pool path and a hostile
        // traversal collapse to the same safe basename; child then confirms
        // it stays inside the folder.
        assertEquals("Kick.wav", SafePath.basename("../Samples/Kick.wav"))
        assertEquals("Kick.wav", SafePath.basename("Samples\\Deep\\Kick.wav"))
        assertEquals("pwned.wav", SafePath.basename("../../../../pwned.wav"))
        assertEquals("Kick.wav", SafePath.basename("Kick.wav"))
        val flat = SafePath.basename("../../evil.wav")
        assertEquals(temp.canonicalFile, SafePath.child(temp, flat).canonicalFile.parentFile)
    }

    @Test
    fun `basename refuses only genuinely broken names`() {
        assertFailsWith<BadPathException> { SafePath.basename("") }
        assertFailsWith<BadPathException> { SafePath.basename("../../") }   // no file part
        assertFailsWith<BadPathException> { SafePath.basename("C:evil.wav") }
    }
}
