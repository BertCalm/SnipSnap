package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Presets
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoopPadMakerTest {

    private val dest: File = java.nio.file.Files.createTempDirectory("looppad").toFile()

    @AfterTest
    fun cleanUp() {
        dest.deleteRecursively()
    }

    private companion object {
        /** THAW BRITTLE: a qualifier whose loop is short, so nine zones render in seconds. */
        val preset = Presets.all().first { it.engine == "THAW" && it.voiceName == "BRITTLE" }
        val spec = LoopPadMaker.spec(preset, LoopPadMaker.RELEASE.fraction(.6f))
        val notes: List<KeyNote> by lazy { LoopPadMaker.zoneMidis(spec).map { LoopPadMaker.renderZone(spec, it) } }
    }

    @Test
    fun `every qualified voice is a factory voice with a root to move`() {
        val presets = Presets.all().groupBy { it.engine to it.voiceName }
        assertTrue(LoopPadMaker.QUALIFIED.isNotEmpty())
        for ((engine, voices) in LoopPadMaker.QUALIFIED) for (voice in voices) {
            val p = presets[engine to voice]?.first() ?: error("$engine $voice has no factory preset")
            assertTrue(LoopPadMaker.supports(p), "$engine $voice")
            assertTrue(LoopPadMaker.zoneMidis(LoopPadMaker.spec(p, .5f)).size >= 3, "$engine $voice has room for zones")
        }
    }

    @Test
    fun `an unqualified voice and an engine without HOLD loops are not offered`() {
        assertFalse(LoopPadMaker.offers("THUMP"))
        val thump = Presets.all().first { it.engine == "THUMP" }
        assertFalse(LoopPadMaker.supports(thump))
        val pulsing = Presets.all().first { it.engine == "REVEL" }
        assertFalse(LoopPadMaker.supports(pulsing), "REVEL's loops pulse; the scan left it out")
    }

    @Test
    fun `zones step by minor thirds an octave either side of the root, inside the range`() {
        val control = SynthRoot.of(LoopPadMaker.held(preset))!!
        val midis = LoopPadMaker.zoneMidis(spec)
        assertTrue(control.midi in midis)
        for (i in 1 until midis.size) assertEquals(3, midis[i] - midis[i - 1])
        assertTrue(midis.all { it in control.range && kotlin.math.abs(it - control.midi) <= 12 })
    }

    @Test
    fun `each zone is its loop twice over, looping at the second copy, without a click`() {
        for (note in notes) {
            val s = note.snip.samples
            val half = (note.loopStartFrame).toInt()
            assertEquals(s.size, 2 * half)
            assertContentEquals(s.copyOfRange(0, half), s.copyOfRange(half, s.size))
            assertEquals(1, note.snip.channels)
            assertTrue(LoopPadMaker.wrapRatio(s.copyOfRange(0, half)) <= LoopPadMaker.MAX_WRAP_RATIO)
        }
    }

    @Test
    fun `the zones tile as a keygroup program and survive the export and the phone's sidecar`() {
        val name = "THAW BRITTLE"
        LoopPadMaker.export(name, spec, notes, dest)
        val instrument = InstrumentStore.read(InstrumentStore.sidecar(dest, name))
        assertEquals(LoopPadMaker.zoneMidis(spec), instrument.zones.map { it.rootNote })
        assertEquals(spec.releaseSeconds, instrument.release, 1e-6f)
        val zones = instrument.zones
        for (i in 1 until zones.size) assertEquals(zones[i - 1].highNote + 1, zones[i].lowNote, "zones tile without gaps")
        zones.forEachIndexed { i, z ->
            assertEquals(notes[i].loopStartFrame, z.loopStartFrame)
            val wav = WavReader.read(File(dest, z.sample))
            assertEquals(notes[i].snip.frameCount, wav.frameCount)
        }
    }

    @Test
    fun `the wrap reading passes a whole-cycle loop and catches a cut mid-cycle`() {
        val rate = 44_100
        fun tone(frames: Int, hz: Double) = FloatArray(frames) { (0.5 * sin(2 * PI * hz * it / rate)).toFloat() }
        // 441 Hz has exactly 100 samples a cycle: 44 cycles close; 4,450 frames end mid-cycle.
        assertTrue(LoopPadMaker.wrapRatio(tone(4_400, 441.0)) <= 1.01)
        assertTrue(LoopPadMaker.wrapRatio(tone(4_450, 441.0)) > LoopPadMaker.MAX_WRAP_RATIO)
        assertEquals(Double.POSITIVE_INFINITY, LoopPadMaker.wrapRatio(tone(1_000, 441.0)))
    }
}
