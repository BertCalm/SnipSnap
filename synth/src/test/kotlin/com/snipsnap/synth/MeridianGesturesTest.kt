package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MeridianGesturesTest {
    private val oldVoices = listOf(MeridianVoice.SWEEP, MeridianVoice.CLACK, MeridianVoice.CHIME, MeridianVoice.RASP)

    private fun baselineText(overridePath: String? = System.getProperty("snipsnap.meridian.baseline")): String =
        if (overridePath != null) File(overridePath).readText() else
            requireNotNull(javaClass.getResourceAsStream("/meridian-gestures/accepted-models-1-3.json")) {
                "Missing tracked MERIDIAN accepted baseline resource"
            }.bufferedReader().use { it.readText() }

    @Test fun `appended voices have explicit model four recipes without widening older defaults or missing model saves`() {
        assertEquals(listOf("SWEEP", "CLACK", "CHIME", "RASP", "DRAW", "LIFT"), MeridianVoice.entries.map { it.name })
        assertEquals(listOf(0, 1, 2, 3), oldVoices.map { it.ordinal })
        assertEquals(listOf(2, 3, 3, 3), oldVoices.map { Meridian.defaultModelFor(it) })
        for (voice in oldVoices) assertFalse(Meridian.supportsVoice(voice, 4))
        for (voice in MeridianGestureReadings.voices) {
            assertEquals(4, Meridian.defaultModelFor(voice))
            val patches = MeridianPresets.forVoice(voice)
            assertEquals(2, patches.size)
            for (patch in patches) {
                assertEquals(4, patch.model)
                assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
                for (model in 1..3) assertFalse(Meridian.supportsVoice(voice, model))
                val missing = JsonValue.Obj(LinkedHashMap(patch.toJsonValue().entries).apply { remove("model") })
                assertTrue(assertFailsWith<IllegalArgumentException> {
                    MeridianPatch("Unsupported", voice, patch.macros, model = 3)
                }.message.orEmpty().contains(voice.name))
                assertFailsWith<com.snipsnap.json.JsonException> { MeridianPatch.fromJsonValue(missing) }
            }
        }
    }

    @Test fun `all fourteen accepted recipes and eight finite held waveforms retain pristine baseline bytes`() {
        val tracked = baselineText(null)
        assertEquals(tracked, baselineText(), "explicit pristine capture differs from the tracked baseline")
        val baseline = Json.parse(tracked).obj()
        val recipes = baseline.getValue("recipes").arr()
        assertEquals(14, recipes.size)
        assertEquals(recipes.map { it.obj().getValue("json").str() }, MeridianPresets.all().take(14).map { it.toJsonText() })
        val rows = baseline.getValue("renders").arr()
        assertEquals(8, rows.size)
        for (row in rows) {
            val o = row.obj()
            val name = o.getValue("name").str()
            val patch = MeridianPresets.all().take(14).single { it.name == name }
            val held = if (Meridian.isLoop(patch.macros, patch.model)) Meridian.renderHeld(patch.voice,
                patch.macros, patch.midi, patch.velocity, patch.seedContext, patch.model) else null
            val snip = held?.snip ?: patch.render()
            assertEquals(o.getValue("frames").int(), snip.frameCount, name)
            assertEquals(o.getValue("rawSha256").str(), MeridianGestureReadings.rawHash(snip.samples), "$name accepted audio changed")
            assertEquals(o["loopStartFrame"]?.takeUnless { it == JsonValue.Null }?.int(), held?.loopStartFrame, name)
            assertEquals(o["loopEndFrameExclusive"]?.takeUnless { it == JsonValue.Null }?.int(), held?.loopEndFrameExclusive, name)
        }
    }

    @Test fun `tracked accepted baseline loads without any local capture or build output`() {
        val raw = baselineText(null)
        val expectedHash = requireNotNull(javaClass.getResourceAsStream("/meridian-gestures/accepted-models-1-3.sha256"))
            .bufferedReader().use { it.readText().trim() }
        assertEquals(expectedHash, MeridianGestureReadings.hash(raw.toByteArray(Charsets.UTF_8)))
        val o = Json.parse(raw).obj()
        assertEquals("0f7d0e26cfdf875a995e6c8a5aec8225e3fd1fe9", o.getValue("source").str())
        assertEquals(14, o.getValue("recipes").arr().size)
        assertEquals(8, o.getValue("renders").arr().size)
        assertEquals(MeridianPresets.all().take(14).map { it.toJsonText() },
            o.getValue("recipes").arr().map { it.obj().getValue("json").str() })
    }

    @Test fun `pressure storage releases on withdrawal and contracts independently of the acoustic model`() {
        val count = 20_000
        val pressure = DoubleArray(count) { if (it < count / 2) 1.0 else 0.0 }
        val carrier = DoubleArray(count) { 1.0 }
        val a = exp(-1 / (Dsp.RATE * Dsp.OVERSAMPLE * .05))
        val draw = MeridianGestures.source(MeridianVoice.DRAW, pressure, carrier, .6, 1.0, a, 0.0)
        val lift = MeridianGestures.source(MeridianVoice.LIFT, pressure, carrier, .6, 1.0, a, 0.0)
        assertTrue(draw.input.take(count / 2).all { it > 0.0 })
        assertTrue(draw.input.drop(count / 2).all { it == 0.0 })
        assertTrue(lift.input.take(count / 2).all { it == 0.0 }, "LIFT reverted to rising-pressure excitation")
        assertTrue(lift.input[count / 2] > 1e-6, "LIFT lost its loaded withdrawal answer")
        assertTrue(lift.peakPressure in 0.0..1.0 && lift.peakGate in 0.0..1.0)
        assertTrue(lift.input.last() < lift.input[count / 2] * .4, "unforced pressure did not drain")
        val initial = .1
        val other = MeridianGestures.source(MeridianVoice.LIFT, pressure, carrier, .6, 1.0, a, initial)
        assertEquals(initial * a.powInt(count), other.finalPressure - lift.finalPressure, 1e-12)
    }

    @Test fun `eight native finite and held candidates retain requested root bounded output and actual held closure`() {
        val readings = MeridianGestureReadings.all
        assertEquals(8, readings.size)
        for (r in readings) {
            val label = r.id
            assertTrue(r.snip.samples.all { it.isFinite() && abs(it) <= 1f }, label)
            assertTrue(r.snip.peak() in .01f..1f, label)
            assertTrue(abs(r.snip.samples.average()) < .005, "$label DC")
            assertTrue(Loudness.of(r.snip) >= Dsp.MELODIC_LOUDNESS_TARGET * .9 || r.snip.peak() >= .95f, "$label level")
            assertTrue(abs(r.cents) < 25, "$label root ${r.cents} cents")
            val broad = assertNotNull(r.broad, "$label broad root")
            val broadCents = 1200 * ln(broad.hz / r.expectedHz.toDouble()) / ln(2.0)
            assertTrue(broad.confidence >= .3f && abs(broadCents) < 50, "$label broad root $broadCents/confidence ${broad.confidence}")
            r.probe?.let { p ->
                assertTrue(p.diagnostics.rawPeak in 1e-6..8.0, "$label raw peak ${p.diagnostics.rawPeak}")
                assertTrue(p.diagnostics.peakEnergy < 100.0, "$label default-fixture modal energy ${p.diagnostics.peakEnergy}")
            }
            r.held?.let { h ->
                assertEquals(h.loopFrames, h.loopStartFrame)
                assertEquals(2 * h.loopFrames, h.loopEndFrameExclusive)
                assertTrue(h.stateError < 1e-5 && h.cycleError < 1e-5, label)
                assertTrue(h.seamError < Keys.MAX_SEAM_ERROR, label)
                assertEquals(h.seamError, Keys.seamError(h.snip.samples, h.loopStartFrame), 1e-12)
                assertTrue(h.rawPeak in 1e-6..8.0 && h.peakEnergy < 100.0, "$label raw state")
                val broken = h.snip.samples.copyOf()
                for (i in h.loopStartFrame - 256 until h.loopStartFrame) broken[i] = -broken[i]
                assertTrue(Keys.seamError(broken, h.loopStartFrame) > 1, "$label seam ignored a corrupted real preceding cycle")
            }
            println("MERIDIAN candidate $label nativeMs=${r.elapsedNanos / 1e6} cents=${r.cents} peak=${r.snip.peak()}")
        }
    }

    @Test fun `saved candidates replay exactly and retain finite held source behavior through actual public dispatch`() {
        for (voice in MeridianGestureReadings.voices) for (hold in listOf(false, true)) {
            val r = MeridianGestureReadings.all.first { it.patch.voice == voice && (it.held != null) == hold }
            val restored = Patches.fromJsonText(r.patch.toJsonText()) as MeridianPatch
            val start = System.nanoTime()
            assertContentEquals(r.snip.samples, restored.render().samples, "${r.id} JSON/public replay")
            println("MERIDIAN warm ${r.id} ms=${(System.nanoTime() - start) / 1e6}")
            assertEquals(if (hold) com.snipsnap.audio.DrumClass.LOOP else com.snipsnap.audio.DrumClass.TONAL,
                Meridian.drumClassFor(voice, restored.macros, restored.model))
        }
    }

    @Test fun `native velocity changes source work before leveling and zero excitation stays silent`() {
        for (voice in MeridianGestureReadings.voices) {
            val hard = MeridianGestureReadings.all.first { it.patch.voice == voice && it.probe != null }
            val patch = hard.patch
            val soft = Meridian.renderProbe(voice, patch.macros, patch.midi, .35f, patch.seedContext, normalize = true)
            assertTrue(hard.probe!!.diagnostics.perSurfaceInputEnergy.sum() > soft.diagnostics.perSurfaceInputEnergy.sum() * 4,
                "$voice velocity changed only output gain")
            assertFalse(hard.snip.samples.contentEquals(soft.samples), "$voice velocity lost its source/timbre effect")
            val silence = patch.copy(velocity = 0f).render()
            assertTrue(silence.samples.all { it == 0f }, "$voice zero velocity")
        }
    }

    @Test fun `low and high native roots survive finite and held pressure gestures`() {
        for (voice in MeridianGestureReadings.voices) for (midi in listOf(36, 72)) for (hold in listOf(false, true)) {
            val source = MeridianPresets.forVoice(voice).first()
            val patch = source.copy(midi = midi, macros = source.macros + ("HOLD" to if (hold) 1f else 0f))
            val held = if (hold) Meridian.renderHeld(voice, patch.macros, midi, patch.velocity, patch.seedContext, 4) else null
            val snip = held?.snip ?: patch.render()
            val from = held?.let { it.loopStartFrame.toFloat() / Dsp.RATE + .04f } ?: .3f
            val hz = Meridian.frequencyFor(voice, .5f, midi)
            val cents = FineTuning.cents(FineTuning.measuredHz(snip, hz, from, .5f), hz.toDouble())
            assertTrue(abs(cents) < 25 && snip.samples.all { it.isFinite() && abs(it) <= 1f }, "$voice MIDI $midi held=$hold cents=$cents")
            val broad = assertNotNull(Pitch.detect(snip, from, .5f))
            assertTrue(broad.confidence >= .3f)
            assertTrue(abs(1200 * ln(broad.hz / hz.toDouble()) / ln(2.0)) < 50, "$voice MIDI$midi octave/root drift")
            println("MERIDIAN register $voice MIDI$midi held=$hold cents=$cents")
        }
    }

    private fun Double.powInt(n: Int) = kotlin.math.exp(n * kotlin.math.ln(this))
}
