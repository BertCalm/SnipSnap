package com.snipsnap.kit

import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GrooveProgramTest {

    /** Deliberately OFF the 16th grid — an on-grid fixture makes a tight feel a no-op and every assertion below vacuous. */
    private fun offGrid(): Mpc3Clip {
        val s16 = Mpc3Clip.PULSES_PER_16TH
        return Mpc3Clip(
            "Break", 2,
            listOf(
                Mpc3Note(36, 37L, 0.9f),
                Mpc3Note(37, 4 * s16 - 31, 0.85f),
                Mpc3Note(36, 8 * s16 + 44, 0.9f),
                Mpc3Note(37, 12 * s16 - 18, 0.9f),
                Mpc3Note(38, 2 * s16 + 22, 0.5f),
            ),
        )
    }

    private val TPL = GrooveFeel.generated(11)

    private fun times(c: Mpc3Clip?) = c!!.notes.map { it.timePulses }

    @Test
    fun `PROG A at zero feel is the captured clip itself`() {
        val base = offGrid()
        assertEquals(
            base.notes,
            GrooveProgram.compute(0, base, 62, 0, TPL, null)!!.notes,
            "A must be the capture - it used to apply random jitter while calling itself AS CAPTURED",
        )
    }

    @Test
    fun `PROG A follows the feel axis`() {
        val base = offGrid()
        val tight = times(GrooveProgram.compute(0, base, 62, -100, TPL, null))
        val loose = times(GrooveProgram.compute(0, base, 62, 100, TPL, null))
        assertNotEquals(times(base), tight, "full tight must move an off-grid capture")
        assertNotEquals(times(base), loose, "full loose must too")
        assertNotEquals(tight, loose, "and the two ends are not the same place")
        tight.forEach { assertEquals(0L, it % Mpc3Clip.PULSES_PER_16TH, "full tight lands on the grid: $it") }
    }

    @Test
    fun `PROG B is unchanged by any feel`() {
        // The screen discloses this as "RIDES A . C . D". B is exempt because
        // `compute` hands swing() the UNFELT base - swing quantizes internally
        // (its own subtitle is "ON THE GRID, PUSHED LATE"), so feeding it a
        // felt clip would not reproduce this, it would shift notes that
        // crossed a rounding boundary. Pinned so the disclosure cannot rot.
        //
        // Compares full notes, not just times: applyFeel's loose half moves
        // velocity too (GrooveFeel.kt:189-195), and a pin that only watched
        // timePulses would stay green while B's exemption quietly rotted on
        // that axis. TPL itself can't exercise that: GrooveFeel.generated
        // leaves every accent null, so applyFeel's velocity path is an
        // identity against it and a `.notes` diff would have no more
        // detection power than the old `times()` did. An accented template
        // gives the loose side (feel 40, 100) real velocity movement to
        // leak, if the exemption ever stopped handing swing() the unfelt base.
        val base = offGrid()
        val accented = TPL.copy(accents = List(GrooveFeel.POSITIONS) { 1.6f })
        val plain = GrooveProgram.compute(1, base, 62, 0, accented, null)!!.notes
        for (feel in listOf(-100, -40, 40, 100)) {
            assertEquals(plain, GrooveProgram.compute(1, base, 62, feel, accented, null)!!.notes,
                "PROG B moved at feel=$feel, contradicting RIDES A . C . D")
        }
    }

    @Test
    fun `PROG C and D do follow the feel`() {
        val base = offGrid()
        for (index in listOf(2, 3)) {
            assertNotEquals(
                times(GrooveProgram.compute(index, base, 62, 0, TPL, null)),
                times(GrooveProgram.compute(index, base, 62, -100, TPL, null)),
                "program $index must inherit the axis",
            )
        }
    }

    @Test
    fun `PROG E is handed back untouched, feel or no feel`() {
        val base = offGrid()
        val e = Mpc3Clip("Break E", 2, listOf(Mpc3Note(36, 240L, 0.9f)))
        assertEquals(e, GrooveProgram.compute(4, base, 62, 0, TPL, e), "E is the user's own steps")
        assertEquals(e, GrooveProgram.compute(4, base, 62, -100, TPL, e), "and the axis does not touch them")
        assertNull(GrooveProgram.compute(4, base, 62, 0, TPL, null), "no E stored means no E program")
    }

    @Test
    fun `a feel never renames a program`() {
        val base = offGrid()
        assertTrue(
            GrooveProgram.compute(0, base, 62, -100, TPL, null)!!.name == base.name,
            "PROG A keeps the base's name - MIDI export uses these as filenames",
        )
    }
}
