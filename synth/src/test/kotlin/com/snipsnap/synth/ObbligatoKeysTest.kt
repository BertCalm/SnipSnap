package com.snipsnap.synth

import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** OBBLIGATO held as a keys instrument ([Keys.obbligatoPad]) and the join it rides on ([HeldLoop]). */
class ObbligatoKeysTest {

    /** The default duet's five zones, rendered once for the class. */
    private val zones: List<Pair<Int, KeyNote>> by lazy {
        Keys.obbligatoPadMidis().map { it to Keys.obbligatoPad(Obbligato.defaults(), it) }
    }

    @Test
    fun `five zones every minor third across C4 to C5`() {
        assertEquals(listOf(60, 63, 66, 69, 72), Keys.obbligatoPadMidis())
    }

    @Test
    fun `every zone holds an attack and then a loop that closes, inside the held duet`() {
        for ((midi, note) in zones) {
            val s = note.snip.samples
            val start = note.loopStartFrame.toInt()
            assertTrue(start >= (Keys.OBBLIGATO_LOOP_FROM_SECONDS * RATE).toInt(), "MIDI $midi: the loop starts after the entry ($start)")
            assertTrue(s.size <= (Keys.OBBLIGATO_LOOP_UNTIL_SECONDS * RATE).toInt(), "MIDI $midi: the loop ends before the players stop (${s.size})")
            assertTrue(Keys.seamError(s, start) < Keys.MAX_SEAM_ERROR, "MIDI $midi: seam")
            assertTrue(s.all { it.isFinite() && kotlin.math.abs(it) <= 1f }, "MIDI $midi: finite and within 1")
        }
    }

    /** The key plays the duet's own render up to the join: the entry and the start of the loop are untouched. */
    @Test
    fun `the attack is the render's own`() {
        val (midi, note) = zones.first()
        val render = Obbligato.render(Obbligato.defaults() + ("HOLD" to 1f), midi).samples
        val untouched = note.snip.samples.size - HeldLoop.TAIL - (0.15f * RATE).toInt()
        assertContentEquals(render.copyOf(untouched), note.snip.samples.copyOf(untouched))
    }

    @Test
    fun `a zone renders the same twice and refuses a note outside the engine`() {
        assertContentEquals(zones[2].second.snip.samples, Keys.obbligatoPad(Obbligato.defaults(), 66).snip.samples)
        assertFailsWith<IllegalArgumentException> { Keys.obbligatoPad(Obbligato.defaults(), 59) }
        assertFailsWith<IllegalArgumentException> { Keys.obbligatoPad(Obbligato.defaults(), 73) }
    }

    @Test
    fun `every factory relationship keys on every zone`() {
        for (preset in ObbligatoPresets.all()) {
            for (midi in listOf(60, 72)) {
                val note = Keys.obbligatoPad(preset.macros, midi)
                assertTrue(note.loopStartFrame > 0, "${preset.name} MIDI $midi holds")
            }
        }
    }

    // ---------------------------------------------------------------- HeldLoop on its own

    /** Two inharmonic tones: periodic in no whole number of frames near the loop's length, steady in level. */
    private fun wavering(seconds: Float): FloatArray = FloatArray((seconds * RATE).toInt()) { i ->
        val t = i.toDouble() / RATE
        (0.5 * sin(2 * PI * 220.0 * t) + 0.15 * sin(2 * PI * 331.7 * t)).toFloat()
    }

    @Test
    fun `a join reads as the render's own continuation and keeps everything before it`() {
        val x = wavering(4f)
        val start = RATE
        val cut = HeldLoop.close(x, start, (1.4f * RATE).toInt(), (1.6f * RATE).toInt(), (0.15f * RATE).toInt())
        assertEquals(0.0, Keys.seamError(cut.audio, start), "the tail is the material before the loop start")
        val before = cut.audio.size - HeldLoop.TAIL - cut.blendFrames
        assertContentEquals(x.copyOf(before), cut.audio.copyOf(before))
        assertTrue(cut.holds, "a steady tone's join holds: ${cut.fault()}")
    }

    @Test
    fun `a loop that swells is refused, by name`() {
        val x = wavering(4f)
        for (i in x.indices) x[i] *= (0.2f + 0.8f * i / x.size)
        val cut = HeldLoop.close(x, RATE, (1.4f * RATE).toInt(), (1.6f * RATE).toInt(), (0.15f * RATE).toInt())
        assertFalse(cut.holds)
        assertTrue("level" in cut.fault()!!, cut.fault())
        assertEquals(null, HeldLoop.search(x, RATE, x.size, listOf((1.5f * RATE).toInt()), (0.05f * RATE).toInt(), (0.15f * RATE).toInt()))
    }
}
