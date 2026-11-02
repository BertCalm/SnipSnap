package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Pitch
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CausticCompletionTest {
    @Test fun `model two adds controls while zero extensions preserve the original internal and finished samples`() {
        assertEquals(2, CausticInstrument.MODEL_VERSION)
        for (voice in CausticVoice.entries) {
            assertEquals(listOf("SOURCE", "FOCUS", "BODY", "LENGTH", "MOTION", "HOLD"),
                CausticInstrument.macrosFor(voice, 2).map { it.name })
            for (midi in listOf(36, 60, 84)) {
                val m = mapOf("LENGTH" to .2f)
                val original = CausticInstrument.probe(voice, m, midi, .63f, model = 1, seconds = .15)
                val current = CausticInstrument.probe(voice, m + mapOf("HOLD" to 0f, "MOTION" to 0f),
                    midi, .63f, model = 2, seconds = .15)
                assertContentEquals(original.internalRaw, current.internalRaw, "$voice/$midi raw")
                assertContentEquals(original.prelevel, current.prelevel, "$voice/$midi house finish")
                assertContentEquals(original.samples, current.samples, "$voice/$midi final")
            }
        }
    }

    @Test fun `saved model two is explicit and missing model keeps the strict old finite contract`() {
        val expanded = CausticPatch("Held paths", CausticVoice.RUBBED_RING,
            mapOf("MOTION" to .4f, "HOLD" to 1f), midi = 55, velocity = .43f)
        assertEquals(2, expanded.model)
        assertEquals(expanded, Patches.fromJsonText(expanded.toJsonText()))
        assertEquals(expanded.copy(macros = expanded.macros + ("FOCUS" to .3f)),
            expanded.withMacros(expanded.macros + ("FOCUS" to .3f)))
        assertFailsWith<JsonException> {
            CausticPatch.fromJsonValue(JsonValue.Obj(expanded.toJsonValue().entries - "model"))
        }
        val old = CausticPresets.all().first()
        assertEquals(1, old.model)
        assertFailsWith<IllegalArgumentException> { old.withMacros(old.macros + ("MOTION" to .4f)) }
        assertFailsWith<IllegalArgumentException> { old.withMacros(old.macros + ("HOLD" to 1f)) }
    }

    @Test fun `partial HOLD extends finite source and release while the top is an explicit loop`() {
        for (voice in CausticVoice.entries) {
            val macros = mapOf("LENGTH" to 0f, "HOLD" to .5f)
            val extended = CausticInstrument.probe(voice, macros, seconds = .5, normalize = false)
            val original = CausticInstrument.probe(voice, mapOf("LENGTH" to 0f), seconds = .5, normalize = false)
            assertEquals(2.5, extended.diagnostics.durationSeconds, 1e-6)
            assertTrue(relativeDifference(original.internalRaw, extended.internalRaw) > .02,
                "$voice partial HOLD merely appended silence")
            assertEquals(DrumClass.TONAL, CausticInstrument.drumClassFor(voice, mapOf("LENGTH" to 0f, "HOLD" to .25f)))
            assertEquals(DrumClass.LOOP, CausticInstrument.drumClassFor(voice, macros))
            assertTrue(!CausticInstrument.isLoop(voice, macros))
            assertTrue(CausticInstrument.isLoop(voice, mapOf("HOLD" to 1f)))
        }
    }

    @Test fun `held output and exported onset share genuine conditioned history and a quiet trigger phase`() {
        for (voice in CausticVoice.entries) {
            val macros = mapOf("HOLD" to 1f, "MOTION" to .5f)
            val p = CausticInstrument.heldProbe(voice, macros)
            val marker = p.note.loopStartFrame.toInt()
            assertTrue(marker >= p.loop.frameCount)
            assertContentEquals(p.loop.samples, p.note.snip.samples.copyOfRange(marker, p.note.snip.samples.size))
            assertContentEquals(p.loop.samples, CausticInstrument.renderLoop(voice, macros).samples,
                "$voice diagnostic and ordinary loop paths differ")
            assertContentEquals(p.note.snip.samples, CausticInstrument.keyNote(voice, macros).snip.samples,
                "$voice diagnostic and ordinary keygroup paths differ")
            assertTrue(p.continuationError < 1e-3, "$voice real neighbours ${p.continuationError}")
            assertTrue(p.seamError < Keys.MAX_SEAM_ERROR, "$voice exported seam ${p.seamError}")
            assertTrue(p.diagnostics.stateResidual < 1e-4)
            assertTrue(p.diagnostics.sourceMax > p.diagnostics.sourceMin)
            assertTrue(p.diagnostics.focusMax > p.diagnostics.focusMin)
            assertTrue(abs(p.loop.samples.first()) < .002f, "$voice begins with a discontinuous trigger")
            assertTrue(p.finishNanos > 0 && p.renderNanos >= p.finishNanos)
            assertTrue(p.note.snip.samples.take(128).maxOf { abs(it) } < .05f, "$voice lost its real onset")
            val pitch = assertNotNull(Pitch.detect(p.loop), "$voice held root")
            val cents = 1200.0 * log2(pitch.hz / Keys.midiHz(60))
            assertTrue(abs(cents) < 20 && pitch.confidence >= .3f, "$voice $cents cents ${pitch.confidence}")
        }
    }

    @Test fun `held zero velocity is exact silence and cancellation is checked before preparation`() {
        val macros = mapOf("HOLD" to 1f, "MOTION" to 1f)
        for (voice in CausticVoice.entries) {
            val zero = CausticInstrument.heldProbe(voice, macros, velocity = 0f)
            assertTrue(zero.note.snip.samples.all { it == 0f })
            assertTrue(zero.loop.samples.all { it == 0f })
            assertEquals(0, zero.loopPhaseOffsetFrames)
            assertFailsWith<CancellationException> {
                CausticInstrument.keyNote(voice, macros, cancelled = { true })
            }
        }
    }

    private fun relativeDifference(a: FloatArray, b: FloatArray): Double =
        sqrt(a.indices.sumOf { val d = a[it] - b[it].toDouble(); d * d } /
            a.sumOf { it.toDouble() * it }.coerceAtLeast(1e-30))
}
