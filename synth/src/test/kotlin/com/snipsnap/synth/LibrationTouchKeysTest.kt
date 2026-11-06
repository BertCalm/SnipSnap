package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.sqrt
import kotlin.test.*

class LibrationTouchKeysTest {
    private companion object {
        fun patch(voice: LibrationVoice) = LibrationPatch("Touch proof", voice,
            Libration.defaults(voice) + mapOf("SWARM" to .1f, "RESONANCE" to .3f), velocity = .8f)
        val captures by lazy { LibrationVoice.entries.associateWith { LibrationTouchKeys.capture(patch(it)) } }
    }

    @Test fun `both touches keep real recurrence root and distinct articulation in every voice`() {
        for ((voice, layers) in captures) {
            assertEquals(listOf(1..63, 64..127), layers.map { it.velocityWindow })
            val soft = layers.first().note.snip
            val hard = layers.last().note.snip
            for (layer in layers) {
                val note = layer.note
                assertTrue(note.loopStartFrame > 0 && note.loopStartFrame < note.snip.frameCount - 1)
                assertTrue(note.snip.samples.all { it.isFinite() })
                assertTrue(note.snip.peak() <= .941f)
                assertTrue(Keys.seamError(note.snip.samples, note.loopStartFrame.toInt()) < Keys.MAX_SEAM_ERROR)
                assertTrue(layer.diagnostics.cycleError < 1e-3 && layer.diagnostics.stateError < 1e-3)
                assertTrue(layer.diagnostics.phaseError < 1e-6 && abs(layer.diagnostics.rootCents) < .1)
                val pitch = assertNotNull(Pitch.detect(note.snip, .1f, .5f))
                assertTrue(abs(1200 * log2(pitch.hz / Keys.midiHz(60))) < 18, "$voice ${layer.strength}")
            }
            val start = (soft.sampleRate * .1).toInt()
            val end = (soft.sampleRate * .6).toInt()
            val softEnergy = (start until end).sumOf { soft.samples[it].toDouble() * soft.samples[it] }
            val hardEnergy = (start until end).sumOf { hard.samples[it].toDouble() * hard.samples[it] }
            val levelMatch = sqrt(hardEnergy / softEnergy)
            val residual = sqrt((start until end).sumOf {
                val delta = soft.samples[it] * levelMatch - hard.samples[it]
                delta * delta
            } / hardEnergy)
            println("LIBRATION touch $voice matchedResidual=$residual hardSoftRms=$levelMatch")
            assertTrue(residual > .006, "$voice touch changed level without articulation: $residual")
            assertTrue(levelMatch in .7..1.4, "$voice capture gain applied twice: $levelMatch")
        }
    }

    @Test fun `full source recipes and exact sample gains reproduce both captures without changing the source`() {
        for ((voice, layers) in captures) {
            val source = patch(voice)
            val before = source.toJsonText()
            val session = LibrationKeys.Session()
            for (layer in layers) {
                assertEquals(source.velocity * layer.strength, layer.sourcePatch.velocity)
                assertEquals(source.copy(velocity = layer.sourcePatch.velocity,
                    macros = source.macros + ("HOLD" to 1f), heldModel = 1), layer.sourcePatch)
                val replay = session.note(layer.sourcePatch)
                assertEquals(replay.loopStartFrame, layer.note.loopStartFrame)
                assertContentEquals(FloatArray(replay.snip.frameCount) {
                    (replay.snip.samples[it] * layer.sampleGain).toFloat()
                }, layer.note.snip.samples)
            }
            assertEquals(before, source.toJsonText())
        }
    }

    @Test fun `zero touch remains silent and interrupted captures do not publish a partial instrument`() {
        val source = patch(LibrationVoice.GATHER)
        val silent = LibrationTouchKeys.capture(source.copy(velocity = 0f))
        assertTrue(silent.all { it.sampleGain.isFinite() && it.note.snip.samples.all { sample -> sample == 0f } })
        var checks = 0
        val error = assertFailsWith<CancellationException> {
            LibrationTouchKeys.capture(source, cancelled = { ++checks > 10 })
        }
        assertTrue(error.message.orEmpty().contains("cancelled"))
        assertFailsWith<IllegalArgumentException> {
            LibrationTouchKeys.sourcePatch(source.copy(model = 2), 60, .35f)
        }
        assertFailsWith<IllegalArgumentException> { LibrationTouchKeys.sourcePatch(source, 60, .5f) }
    }
}
