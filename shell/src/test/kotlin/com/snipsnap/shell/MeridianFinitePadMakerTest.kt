package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Meridian
import com.snipsnap.synth.MeridianPresets
import com.snipsnap.synth.MeridianVoice
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeridianFinitePadMakerTest {
    private fun source() = MeridianPresets.forVoice(MeridianVoice.CHIME).first().copy(
        midi = 55, velocity = .37f, seedContext = -52,
        macros = MeridianPresets.forVoice(MeridianVoice.CHIME).first().macros + ("TUNE" to .75f))

    @Test fun `actual sheet adapter retains all cell recipes on the directly visible shelf`() {
        val root = createTempDirectory("meridian-finite-sheet").toFile()
        try {
            val spec = MeridianFinitePadMaker.Spec(source())
            val before = spec.patch.toJsonText()
            val notes = MeridianFinitePadMaker.zoneMidis(spec).map { midi ->
                MeridianFiniteKeygroupMaker.TOUCHES.indices.map { touch ->
                    KeyNote(Snip(FloatArray(64) { i -> if (i % 2 == 0) .01f * (touch + 1) else -.0001f * midi }, 1, 44_100), 0L)
                }
            }
            val expected = MeridianFiniteKeygroupMaker.recipes(spec.keys)
            assertEquals(expected, MeridianFinitePadMaker.cells(spec, notes).mapValues { it.value.recipe })
            val program = MeridianFinitePadMaker.export("Meridian Finite", spec, notes, root)
            val (sidecar, instrument) = InstrumentStore.list(root).single()
            assertEquals(root.canonicalFile, sidecar.parentFile.canonicalFile)
            assertEquals(program, instrument.toProgram())
            assertEquals(66, instrument.sampleFiles.size)
            assertEquals(program, MeridianFiniteKeygroupMaker.validate("Meridian Finite", root, spec.keys).program)
            assertEquals(before, spec.patch.toJsonText())
            assertTrue(MeridianFinitePadMaker.instrumentNote(spec).startsWith("Finite notes"))
        } finally { root.deleteRecursively() }
    }

    @Test fun `finite adapter preserves legacy hold while genuine held path remains unchanged`() {
        val legacy = MeridianPresets.forVoice(MeridianVoice.SWEEP).first().copy(model = 1,
            macros = MeridianPresets.forVoice(MeridianVoice.SWEEP).first().macros + ("HOLD" to 1f))
        assertTrue(MeridianFinitePadMaker.supports(legacy))
        assertFalse(MeridianPadMaker.supports(legacy))
        assertEquals(1, MeridianFinitePadMaker.Spec(legacy).patch.model)
        val held = source().copy(macros = source().macros + ("HOLD" to 1f))
        assertTrue(MeridianPadMaker.supports(held))
        assertFalse(MeridianFinitePadMaker.supports(held))
        assertFailsWith<IllegalArgumentException> { MeridianFinitePadMaker.Spec(held) }
        assertEquals((36..72 step 3).toList(), MeridianPadMaker.zoneMidis(MeridianPadMaker.Spec(held)))
        assertEquals(listOf(.30f, .65f, 1f), MeridianPadMaker.VELOCITIES)
    }

    @Test fun `adapter cancellation and loop metadata refuse before finite notes are published`() {
        val spec = MeridianFinitePadMaker.Spec(source())
        assertFailsWith<CancellationException> { MeridianFinitePadMaker.preview(spec) { true } }
        assertFailsWith<CancellationException> { MeridianFinitePadMaker.renderZone(spec, 61) { true } }
        val notes = MeridianFinitePadMaker.zoneMidis(spec).map {
            List(3) { KeyNote(Snip(FloatArray(32), 1, 44_100), 0L) }
        }.toMutableList()
        notes[0] = notes[0].toMutableList().also { it[0] = it[0].copy(loopStartFrame = 1L) }
        assertFailsWith<IllegalArgumentException> { MeridianFinitePadMaker.cells(spec, notes) }
    }
}
