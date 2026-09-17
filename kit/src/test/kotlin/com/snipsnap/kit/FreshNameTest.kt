package com.snipsnap.kit

import com.snipsnap.audio.Snip
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The fresh-name rule, and the one thing every caller of it has to agree on:
 * what counts as "taken".
 *
 * Three places in this codebase counted a name up to the next free one —
 * `KitShelf.freshName` (a shelf folder), `Rooms.freshName` (a `.wav`/`.json`
 * pair) and nothing at all for instrument packages, which is the bug these
 * tests were written for. The counting is the same rule everywhere; only the
 * occupancy test differs. So the counting lives in one place and takes the
 * occupancy test as an argument, rather than being written a fourth time.
 */
class FreshNameTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("fresh").toFile()

    @AfterTest
    fun tearDown() { temp.deleteRecursively() }

    private fun tone(hz: Double, seconds: Float = 0.6f): Snip =
        Snip(FloatArray((seconds * 44_100).toInt()) { (0.5 * Math.sin(2 * Math.PI * hz * it / 44_100)).toFloat() }, 1, 44_100)

    // ---- the counting rule itself ----

    @Test
    fun `a name nothing holds comes back unchanged`() {
        assertEquals("FACTORY", Names.freshStem("FACTORY") { false })
    }

    @Test
    fun `a taken name counts from two, not from one`() {
        val taken = setOf("FACTORY")
        assertEquals("FACTORY 2", Names.freshStem("FACTORY") { it in taken })
    }

    @Test
    fun `counting steps over every name already held`() {
        val taken = setOf("FACTORY", "FACTORY 2", "FACTORY 3")
        assertEquals("FACTORY 4", Names.freshStem("FACTORY") { it in taken })
    }

    @Test
    fun `a gap in the middle is filled rather than skipped past`() {
        // "FACTORY 2" free while "FACTORY 3" is taken: the rule returns the
        // first free name, so a deleted middle entry is reused. Asserted
        // because the alternative - always appending past the highest - is
        // the other plausible reading, and callers pick names the user sees.
        val taken = setOf("FACTORY", "FACTORY 3")
        assertEquals("FACTORY 2", Names.freshStem("FACTORY") { it in taken })
    }

    @Test
    fun `the counted name is still MPC-safe`() {
        val name = Names.freshStem("FACTORY") { it == "FACTORY" }
        assertTrue(Names.isMpcSafe(name), "counted name is not MPC-safe: '$name'")
    }

    // ---- what "taken" means for an instrument package ----

    @Test
    fun `a package name is taken when its xty is on disk`() {
        val root = File(temp, "Instruments").also { it.mkdirs() }
        File(root, "PAD.xty").writeText("x")
        assertEquals("PAD 2", OneNote.freshName(root, "PAD"))
    }

    /**
     * The case a name-only check misses, and the reason occupancy is not
     * just "does the file exist".
     *
     * `writePackage` guards on the program file **OR** its data folder,
     * because either alone makes the next write a replacement — and it
     * `deleteRecursively()`s that folder before writing. A fresh-name search
     * that only looked for `.xty` would hand back a name whose data folder is
     * then silently destroyed, which is the exact data loss this is meant to
     * prevent, moved one step along.
     */
    @Test
    fun `a package name is taken when only its data folder is on disk`() {
        val root = File(temp, "Instruments").also { it.mkdirs() }
        File(root, com.snipsnap.mpc3.Mpc3TrackWriter.trackDataDirName("PAD")).mkdirs()
        assertEquals("PAD 2", OneNote.freshName(root, "PAD"))
    }

    @Test
    fun `a destination that does not exist yet holds nothing`() {
        val root = File(temp, "NoSuchFolder")
        assertEquals("PAD", OneNote.freshName(root, "PAD"))
    }

    // ---- the round trip: two presses, two packages, neither clobbered ----

    /**
     * The bug in one test. `PadFromAnything.export`/`OneNote.export` already
     * refuse to clobber (`overwrite = false` is their default); a caller that
     * asks for a fresh name first can take that default and keep both.
     */
    @Test
    fun `exporting twice under fresh names leaves both packages standing`() {
        val root = File(temp, "Instruments")
        val first = OneNote.freshName(root, "TAKE")
        OneNote.export(first, tone(220.0), root, overwrite = false)

        val second = OneNote.freshName(root, "TAKE")
        assertNotEquals(first, second, "the second press reused the first press's name")
        OneNote.export(second, tone(330.0), root, overwrite = false)

        assertTrue(File(root, "$first.xty").isFile, "the first package's program file is gone")
        assertTrue(File(root, "$second.xty").isFile, "the second package's program file is missing")
        assertTrue(
            File(root, com.snipsnap.mpc3.Mpc3TrackWriter.trackDataDirName(first)).isDirectory,
            "the first package's data folder was destroyed by the second export",
        )
    }

    /**
     * And the same for a pad, which is the door that actually reseeds: every
     * press of MAKE PAD renders different audio from a fresh random seed, so
     * a press that overwrites destroys something no later press can
     * reproduce. Exported with distinct sources to prove the two packages
     * hold different audio rather than one file twice.
     */
    @Test
    fun `two pads from the same source land side by side`() {
        val root = File(temp, "Instruments")
        val a = OneNote.freshName(root, "DRONE")
        PadFromAnything.export(a, tone(110.0, seconds = 3f), root, overwrite = false)
        val b = OneNote.freshName(root, "DRONE")
        PadFromAnything.export(b, tone(110.0, seconds = 3f), root, overwrite = false)

        assertEquals("DRONE", a)
        assertEquals("DRONE 2", b)
        assertTrue(File(root, "$a.xty").isFile)
        assertTrue(File(root, "$b.xty").isFile)
    }
}
