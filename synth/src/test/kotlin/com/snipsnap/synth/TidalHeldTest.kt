package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TidalHeldTest {
    private fun patch(voice: TidalVoice = TidalVoice.STREAM, midi: Int = 60, model: Int = 2) =
        TidalPatch("HELD FIELD", voice, Tidal.defaults(voice, model) + ("HOLD" to 1f), midi = midi, model = model)

    @Test
    fun `both populations close through continuous state across register and source models`() {
        val cases = listOf(patch(TidalVoice.PAIR, 36), patch(TidalVoice.PAIR, 84),
            patch(TidalVoice.STREAM, 48), patch(TidalVoice.STREAM, 72), patch(TidalVoice.PAIR, 60, 1)) +
            listOf(0f, 1f).map { corner -> patch(TidalVoice.PAIR, 84).copy(velocity = 1f, seed = Long.MAX_VALUE,
                macros = Tidal.defaults(TidalVoice.PAIR).mapValues { corner } +
                    mapOf("HOLD" to 1f, "TAIL" to 1f, "BIND" to 0f)) }
        for (p in cases) {
            val probe = TidalKeys.probe(p)
            assertTrue(probe.seamError < Keys.MAX_SEAM_ERROR, "$p seam ${probe.seamError}")
            assertTrue(probe.cycleError < 1e-3, "$p actual neighboring cycles ${probe.cycleError}")
            assertTrue(probe.maxPhaseClosureCycles < 1e-6)
            assertTrue(probe.amplitudeMotion > .001, "cohort motion was flattened")
            assertTrue(abs(probe.rootCents) < .1)
            assertTrue(probe.maxFrequencyCorrectionHz < .14, "sub-Hz fit, not forced harmonic metal")
            assertTrue(probe.rawPeak in 0.0.. .96)
            assertTrue(probe.loop.all { it.isFinite() && abs(it) <= 1f })
            assertTrue(abs(probe.loop.sumOf { it.toDouble() } / probe.loop.size) < .005)
            assertTrue(probe.loop.maxOf { abs(it) } > .01f)
            val pitch = Pitch.detect(Snip(probe.loop, 1, Dsp.RATE))
                ?: error("held material lost the requested root: $p")
            val detectedCents = 1200 * ln(pitch.hz / Keys.midiHz(p.midi).toDouble()) / ln(2.0)
            assertTrue(pitch.confidence >= .3f && abs(detectedCents) < 25,
                "held material root: $detectedCents cents, confidence ${pitch.confidence}")
            val pad = TidalKeys.padLoop(probe.loop)
            assertTrue(abs(pad.first()) < 1e-4, "held pad must not start with a hard amplitude step")
            val entry = probe.loop.indices.first { probe.loop[it].toRawBits() == pad.first().toRawBits() }
            assertContentEquals(probe.loop, FloatArray(pad.size) { pad[Math.floorMod(it - entry, pad.size)] },
                "pad entry must rotate the real field, never fade or reshape its cycle")
            val note = probe.note
            assertEquals(probe.loop.size, note.snip.frameCount - note.loopStartFrame.toInt())
            assertTrue(Keys.seamError(note.snip.samples, note.loopStartFrame.toInt()) < Keys.MAX_SEAM_ERROR)
            val finite = p.copy(macros = p.macros + ("HOLD" to 0f)).render().samples
            assertContentEquals(finite.copyOfRange(0, probe.joinStartFrame),
                note.snip.samples.copyOfRange(0, probe.joinStartFrame), "the complete pre-join encounter must survive")
        }
    }

    @Test
    fun `hold is explicit versioned deterministic and cancellable without changing finite recipes`() {
        val p = patch().copy(seed = Long.MIN_VALUE, velocity = .42f)
        assertEquals(p, TidalPatch.fromJsonText(p.toJsonText()))
        assertEquals(1f, Tidal.scramble(p.voice, kotlin.random.Random(17), near = p)["HOLD"])
        assertEquals(1, p.toJsonValue().entries.getValue("heldModel").int())
        assertFailsWith<JsonException> { TidalPatch.fromJsonValue(JsonValue.Obj(p.toJsonValue().entries - "heldModel")) }
        assertFailsWith<JsonException> {
            TidalPatch.fromJsonValue(JsonValue.Obj(p.toJsonValue().entries + ("heldModel" to JsonValue.Num(2.0))))
        }
        assertFailsWith<IllegalArgumentException> { p.copy(macros = p.macros + ("HOLD" to .5f)) }
        val first = p.render().samples
        val second = TidalKeys.loop(p).samples
        assertContentEquals(first, second, "independent reconstruction must be exact")
        first.fill(0f)
        assertContentEquals(second, p.render().samples, "completed loop cache must own its samples")
        assertFailsWith<CancellationException> { TidalKeys.note(p, cancelled = { true }) }
        var checks = 0
        assertFailsWith<CancellationException> { TidalKeys.note(p, cancelled = { ++checks > 40 }) }
        assertTrue(p.copy(velocity = 0f).render().samples.all { it == 0f })
    }

    @Test
    fun `encounter and pickup controls still change the held field and moving texture survives`() {
        val p = patch()
        val baseline = TidalKeys.loop(p).samples
        for (name in listOf("APPROACH", "PASSAGE", "TILT", "BIND", "TAIL")) {
            val changed = TidalKeys.loop(p.copy(macros = p.macros + (name to .08f))).samples
            assertTrue(!baseline.contentEquals(changed), "$name was lost during reconstruction")
        }
        val a = baseline.copyOfRange(0, 4096)
        val b = baseline.copyOfRange(baseline.size / 2, baseline.size / 2 + 4096)
        assertTrue(!a.contentEquals(b), "the aftermath must retain changing controls")
    }
}
