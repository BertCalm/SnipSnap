package com.snipsnap.synth

import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The new factory family uses existing expression and gestures without revoicing its sources. */
class OrigamiPerformancePresetsTest {
    @Test
    fun `performance family offers complementary editable dry recipes beneath the accepted fold ceiling`() {
        val performance = OrigamiPresets.performance()
        assertEquals(6, performance.size)
        assertTrue(performance.none { it in OrigamiPresets.references() })
        assertEquals(6, performance.map { Triple(it.voice, it.gesture, it.macros) }.distinct().size)
        for (voice in OrigamiVoice.entries) {
            val pair = performance.filter { it.voice == voice }
            assertEquals(listOf(OrigamiGesture.BLOOM, OrigamiGesture.UNFOLD), pair.map { it.gesture })
            assertEquals(pair[0].macros, pair[1].macros, "$voice directions must share one timbre and clock")
            val accepted = OrigamiPresets.references().first { it.voice == voice }
            for (patch in pair) {
                assertEquals(4, patch.model)
                assertEquals(OrigamiExpression.FOLD, patch.expression)
                assertEquals(accepted.seed, patch.seed)
                assertEquals(accepted.velocity, patch.velocity)
                assertNull(patch.midi, "${patch.name} must keep TUNE playable")
                for (control in listOf("TUNE", "CREASE", "TURN", "WARP")) {
                    assertEquals(accepted.macros.getValue(control), patch.macros.getValue(control),
                        "${patch.name} changes the accepted central geometry")
                }
                assertTrue(patch.macros.getValue("FOLD") in .4f..accepted.macros.getValue("FOLD"),
                    "${patch.name} must keep useful color below the accepted reference depth")
                assertTrue(OrigamiGestures.endSeconds(patch) + .4 < Origami.durationSeconds(voice),
                    "${patch.name} leaves no settled finite plateau")
                assertEquals(voice != OrigamiVoice.CRANE, OrigamiKeys.supports(patch))
                assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(patch.name), patch.name)
                assertNull(Presets.landingFor(patch.engine, patch.voiceName, patch.macros))
            }
        }
    }

    @Test
    fun `saved factory performance recipes retain gesture expression and native edits`() {
        for (patch in OrigamiPresets.performance()) {
            val restored = assertIs<OrigamiPatch>(Patches.fromJsonText(patch.toJsonText()))
            assertEquals(patch, restored)
            assertEquals(OrigamiGestures.MODEL_VERSION, restored.toJsonValue().entries.getValue("gesture").obj().getValue("model").int())
            assertEquals(OrigamiExpressions.MODEL_VERSION, restored.toJsonValue().entries.getValue("expression").obj().getValue("model").int())
            val edited = assertIs<OrigamiPatch>(Patches.edited(restored, "Edited ${patch.name}",
                restored.macros + mapOf("TUNE" to 0f, "FOLD" to .4f)))
            assertEquals(patch.copy(name = "Edited ${patch.name}",
                macros = patch.macros + mapOf("TUNE" to 0f, "FOLD" to .4f)), edited,
                "${patch.name} generic editing lost an expressive recipe field")
            assertEquals(48, Origami.midiFor(edited.voice, edited.macros.getValue("TUNE")))
            assertEquals(edited, Patches.fromJsonText(edited.toJsonText()))
        }
    }

    @Test
    fun `factory gestures travel in opposite directions and soft hits change color after matching gain`() {
        for (voice in OrigamiVoice.entries) {
            val pair = OrigamiPresets.performance().filter { it.voice == voice }
            val bloom = pair.single { it.gesture == OrigamiGesture.BLOOM }
            val unfold = pair.single { it.gesture == OrigamiGesture.UNFOLD }
            val accepted = OrigamiPresets.references().first { it.voice == voice }
            assertContentEquals(raw(accepted, OrigamiDiagnostic.SOURCE), raw(bloom, OrigamiDiagnostic.SOURCE),
                "$voice performance preset revoiced the accepted unfolded palette")

            val static = bloom.copy(gesture = OrigamiGesture.STEADY, expression = OrigamiExpression.LEVEL,
                velocity = 1f, macros = bloom.macros + ("MOTION" to 0f))
            val source = raw(static.copy(macros = static.macros + ("FOLD" to 0f)))
            val folded = raw(static)
            val opening = raw(bloom.copy(velocity = 1f))
            val closing = raw(unfold.copy(velocity = 1f))
            val start = OrigamiGestures.startSeconds(voice)
            val travel = OrigamiGestures.durationSeconds(voice, bloom.macros.getValue("MOTION"))
            val window = minOf(.08, travel * .10)
            val early = start + travel * .15
            val late = start + travel * .85
            val openingEarly = project(opening, source, folded, early, window)
            val openingLate = project(opening, source, folded, late, window)
            val closingEarly = project(closing, source, folded, early, window)
            val closingLate = project(closing, source, folded, late, window)
            println("ORIGAMI factory $voice audio journey: BLOOM $openingEarly -> $openingLate, UNFOLD $closingEarly -> $closingLate")
            assertTrue(openingEarly < .15 && openingLate > .85, "$voice factory BLOOM does not reveal a source-to-fold journey")
            assertTrue(closingEarly > .85 && closingLate < .15, "$voice factory UNFOLD does not reveal the reverse journey")

            for ((patch, hard, center) in listOf(
                Triple(bloom, opening, OrigamiGestures.endSeconds(bloom) + .06),
                Triple(unfold, closing, early),
            )) {
                val soft = raw(patch.copy(velocity = .3f))
                val difference = matchedDifference(hard, soft, center, window)
                println("ORIGAMI factory ${patch.name}: gain-matched soft/hard difference $difference")
                assertTrue(difference > .04, "${patch.name} velocity only changes level")
            }
        }
    }

    private fun raw(patch: OrigamiPatch, diagnostic: OrigamiDiagnostic = OrigamiDiagnostic.COMBINED) =
        OrigamiGestureRenderer.renderRaw(patch, diagnostic = diagnostic)

    private fun window(samples: FloatArray, center: Double, seconds: Double): IntRange {
        val frames = (seconds * Dsp.RATE).roundToInt().coerceAtLeast(1)
        val first = ((center * Dsp.RATE).roundToInt() - frames / 2).coerceIn(0, samples.size - frames)
        return first until first + frames
    }

    private fun project(samples: FloatArray, source: FloatArray, folded: FloatArray,
                        center: Double, seconds: Double): Double {
        var signal = 0.0
        var contrast = 0.0
        for (i in window(samples, center, seconds)) {
            val delta = folded[i] - source[i].toDouble()
            signal += (samples[i] - source[i]) * delta
            contrast += delta * delta
        }
        assertTrue(contrast > 1e-9, "factory has no actual source/fold contrast at $center seconds")
        return signal / contrast
    }

    private fun matchedDifference(hard: FloatArray, soft: FloatArray, center: Double, seconds: Double): Double {
        val frames = window(hard, center, seconds)
        val hardRms = sqrt(frames.sumOf { hard[it].toDouble() * hard[it] } / frames.count())
        val softRms = sqrt(frames.sumOf { soft[it].toDouble() * soft[it] } / frames.count())
        assertTrue(hardRms > 1e-5 && softRms > 1e-5, "factory contrast window is silent")
        return sqrt(frames.sumOf { val d = hard[it] / hardRms - soft[it] / softRms; d * d } / frames.count())
    }
}
