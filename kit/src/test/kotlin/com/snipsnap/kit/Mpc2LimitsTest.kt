package com.snipsnap.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** MPC 2's `.xpm` keeps a velocity × round-robin pad's velocity layers only, and the export says so first. */
class Mpc2LimitsTest {
    private val sixSlices = listOf(0L, 100L, 200L, 300L, 400L, 500L)

    /** Two zones of three takes: velocity and round robin, what the MPC 2 can't hold together. */
    private fun grid(slot: Int) = KitPad(
        slot, "A%02d.wav".format(java.util.Locale.ROOT, slot),
        chain = ChainInfo(sixSlices, cycle = 3, zones = listOf(ChainZone(0, 63, 0, 3), ChainZone(64, 127, 3, 3))),
    )

    /** Velocity zones with one take each: nothing for the `.xpm` to drop. */
    private fun velocityOnly(slot: Int) = KitPad(
        slot, "B%02d.wav".format(java.util.Locale.ROOT, slot),
        chain = ChainInfo(listOf(0L, 100L), cycle = 2, zones = listOf(ChainZone(0, 63, 0, 1), ChainZone(64, 127, 1, 1))),
    )

    /** A one-zone robin chain: the `.xpm` cycles it as `_RR` take files. */
    private fun robin(slot: Int) = KitPad(
        slot, "C%02d.wav".format(java.util.Locale.ROOT, slot),
        chain = ChainInfo(sixSlices, cycle = 6), robin = RobinSettings(0.6f, 6, 0, followsKit = true),
    )

    private fun plain(slot: Int) = KitPad(slot, "D%02d.wav".format(java.util.Locale.ROOT, slot))

    /** A one-zone chain of takes with no robin settings (TEMPEST ROUND ROBIN, FOLD DOUBLES): the `.xpm` windows it to take one. */
    private fun takes(slot: Int) = KitPad(
        slot, "E%02d.wav".format(java.util.Locale.ROOT, slot),
        chain = ChainInfo(listOf(0L, 100L, 200L, 300L), cycle = 4),
    )

    @Test fun `the MPC 2 formats name their grid pads, and every other format says nothing`() {
        val kit = Kit("Grid", listOf(plain(1), grid(3), robin(4), velocityOnly(5), grid(2)))
        assertEquals(listOf(2, 3), Mpc2Limits.gridPads(kit).map { it.slot })
        val xpm = setOf(ExportFormat.PROGRAM_FOLDER, ExportFormat.EXPANSION, ExportFormat.XPN)
        for (format in ExportFormat.entries) {
            val note = Mpc2Limits.note(format, kit)
            if (format in xpm) {
                assertEquals(Severity.WARN, assertNotNull(note, "$format").severity)
                assertTrue("2 pads keep their velocity layers, one take each" in note.message, note.message)
                assertTrue("MPC 3 native keeps both" in note.message, note.message)
            } else {
                assertNull(note, "$format")
            }
        }
        val one = assertNotNull(Mpc2Limits.note(ExportFormat.XPN, kit.copy(pads = listOf(grid(7)))))
        assertTrue("1 pad keeps its velocity layers" in one.message, one.message)
    }

    @Test fun `a grid kit's note is word for word what it was before take chains were counted`() {
        val kit = Kit("Grid", listOf(plain(1), grid(3), robin(4), velocityOnly(5), grid(2)))
        assertEquals(emptyList(), Mpc2Limits.firstTakePads(kit))
        assertEquals(
            "MPC 2 holds velocity layers or round robin on a pad, not both: 2 pads keep their velocity layers, one take each. " +
                "MPC 3 native keeps both.",
            assertNotNull(Mpc2Limits.note(ExportFormat.PROGRAM_FOLDER, kit)).message,
        )
    }

    @Test fun `a chain of takes with no robin settings is named as keeping its first take on MPC 2 only`() {
        val kit = Kit("Takes", listOf(plain(1), takes(4), robin(5), velocityOnly(6), takes(2)))
        assertEquals(listOf(2, 4), Mpc2Limits.firstTakePads(kit).map { it.slot })
        assertEquals(emptyList(), Mpc2Limits.gridPads(kit))
        val xpm = setOf(ExportFormat.PROGRAM_FOLDER, ExportFormat.EXPANSION, ExportFormat.XPN)
        for (format in ExportFormat.entries) {
            val note = Mpc2Limits.note(format, kit)
            if (format in xpm) {
                assertEquals(Severity.WARN, assertNotNull(note, "$format").severity)
                assertEquals(
                    "MPC 2 has no Slice Motion to deal a pad's takes: 2 pads keep their first take only. MPC 3 native carries every take.",
                    note.message,
                )
            } else {
                assertNull(note, "$format")
            }
        }
        val one = assertNotNull(Mpc2Limits.note(ExportFormat.XPN, kit.copy(pads = listOf(takes(9)))))
        assertTrue("1 pad keeps its first take only" in one.message, one.message)
        // Both kinds on one kit: the grid's sentence unchanged, then the chains'.
        val both = assertNotNull(Mpc2Limits.note(ExportFormat.EXPANSION, Kit("Both", listOf(grid(1), takes(2), takes(3)))))
        assertEquals(
            "MPC 2 holds velocity layers or round robin on a pad, not both: 1 pad keeps its velocity layers, one take each. " +
                "MPC 2 has no Slice Motion to deal a pad's takes: 2 pads keep their first take only. " +
                "MPC 3 native keeps both and carries every take.",
            both.message,
        )
    }

    @Test fun `a kit with no grid pad exports to MPC 2 with no note`() {
        val kit = Kit("Plain", listOf(plain(1), robin(2), velocityOnly(3)))
        for (format in ExportFormat.entries) assertNull(Mpc2Limits.note(format, kit), "$format")
    }
}
