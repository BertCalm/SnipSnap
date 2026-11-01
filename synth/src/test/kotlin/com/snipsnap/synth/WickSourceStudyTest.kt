package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Proves that source selection changes the body while leaving RATTLE's gesture intact. */
class WickSourceStudyTest {
    @Test
    fun `source-study option cannot alter the five other voices or ordinary RATTLE defaults`() {
        for (voice in WickVoice.entries) {
            val normal = Wick.inspect(voice)
            val choice = if (voice == WickVoice.RATTLE) Wick.RattleSource.CURRENT else Wick.RattleSource.WARM_WIRE
            val explicit = Wick.inspect(voice, options = Wick.Options(rattleSource = choice))
            assertContentEquals(normal.raw, explicit.raw, "$voice raw source isolation")
            assertContentEquals(normal.samples, explicit.samples, "$voice output source isolation")
            assertEquals(normal.diagnostics, explicit.diagnostics, "$voice source isolation diagnostics")
        }
    }

    @Test
    fun `three alternate bodies retain the requested register and identical causal events`() {
        for (midi in listOf(48, 60, 72)) {
            val reference = Wick.inspect(WickVoice.RATTLE, midi = midi)
            for (source in Wick.RattleSource.entries.filter { it != Wick.RattleSource.CURRENT }) {
                val probe = Wick.inspect(WickVoice.RATTLE, midi = midi, options = Wick.Options(rattleSource = source))
                val label = "$source MIDI$midi"
                assertEquals(reference.diagnostics.events, probe.diagnostics.events, "$label gate schedule")
                assertTrue(probe.raw.all { it.isFinite() } && probe.samples.all { it.isFinite() }, "$label finite")
                assertTrue(probe.diagnostics.rawPeak in .00001..4.0, "$label raw peak")
                assertTrue(probe.samples.maxOf { abs(it) } < 1f && abs(probe.samples.average()) < .015, "$label output bounds")
                val pitch = assertNotNull(Pitch.detect(Snip(probe.samples, 1, Dsp.RATE), .05f, .25f), "$label opening pitch")
                assertTrue(abs(FineTuning.cents(pitch.hz.toDouble(), Keys.midiHz(midi).toDouble())) < 35 && pitch.confidence > .3f,
                    "$label pitch ${pitch.hz} confidence ${pitch.confidence}")
                assertTrue(TangleMeasure.spectralDistance(reference.raw, probe.raw) > .005, "$label only changes gain")
                if (midi == 60) {
                    val repeat = Wick.inspect(WickVoice.RATTLE, midi = midi, options = Wick.Options(rattleSource = source))
                    assertContentEquals(probe.raw, repeat.raw, "$label deterministic source")
                }
            }
        }
    }

    @Test
    fun `source bodies retain genuine held recurrence and an active TIMBRE control`() {
        val macros = Wick.defaults(WickVoice.RATTLE)
        for (source in Wick.RattleSource.entries) {
            val options = Wick.Options(rattleSource = source)
            val probe = Wick.inspect(WickVoice.RATTLE, macros + ("HOLD" to 1f), options = options)
            val loop = assertNotNull(probe.diagnostics.loop)
            val context = assertNotNull(probe.seamContext)
            assertTrue(loop.converged && loop.stateError < 2e-6 && loop.audioError < 1e-7, "$source converged state")
            assertTrue(context.size >= probe.samples.size + 256)
            assertTrue(Keys.seamError(context, probe.samples.size) < Keys.MAX_SEAM_ERROR, "$source genuine preceding cycle")
            if (source != Wick.RattleSource.CURRENT) {
                val low = Wick.inspect(WickVoice.RATTLE, macros + ("TIMBRE" to 0f), options = options)
                val high = Wick.inspect(WickVoice.RATTLE, macros + ("TIMBRE" to 1f), options = options)
                assertTrue(TangleMeasure.spectralDistance(low.raw, high.raw) > .005, "$source TIMBRE inactive")
                assertEquals(low.diagnostics.events, high.diagnostics.events, "$source TIMBRE changes gate movement")
            }
        }
    }

    @Test
    fun `bright high-register source studies agree with an eightfold numerical reference`() {
        val bright = Wick.defaults(WickVoice.RATTLE) + ("TIMBRE" to 1f)
        for (source in Wick.RattleSource.entries.filter { it != Wick.RattleSource.CURRENT }) {
            val normal = Wick.inspect(WickVoice.RATTLE, bright, 84, options = Wick.Options(rattleSource = source, oversample = 4))
            val reference = Wick.inspect(WickVoice.RATTLE, bright, 84, options = Wick.Options(rattleSource = source, oversample = 8))
            assertTrue(normal.raw.all { it.isFinite() } && reference.raw.all { it.isFinite() }, "$source numerical bounds")
            assertTrue(normal.diagnostics.rawPeak < 4 && reference.diagnostics.rawPeak < 4, "$source reference peak")
            val distance = TangleMeasure.spectralDistance(normal.raw, reference.raw)
            // A production/reference regression bound, not a measurement of isolated alias power.
            assertTrue(distance < .03, "$source 4x/8x normalized twelve-band disagreement $distance")
            println("WICK source study $source MIDI84 TIMBRE1 4x/8x normalized twelve-band distance $distance; numerical agreement does not isolate alias power")
        }
    }
}
