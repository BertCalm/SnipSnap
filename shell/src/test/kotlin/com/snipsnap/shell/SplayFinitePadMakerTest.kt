package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.SplayAcceptedBank
import com.snipsnap.synth.SplayFactoryPresets
import com.snipsnap.synth.SplayVoice
import java.util.concurrent.CancellationException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SplayFinitePadMakerTest {
    private fun source() = SplayFactoryPresets.all().first().copy(
        name = "EDITED OLD TONGUE", model = 1, midi = 55, velocity = .37f, seed = Int.MIN_VALUE,
        macros = SplayFactoryPresets.all().first().macros + ("CONTACT" to .29f))

    private fun fixture(spec: SplayFinitePadMaker.Spec) = SplayFinitePadMaker.zoneMidis(spec).map { midi ->
        SplayFiniteKeygroupMaker.TOUCHES.indices.map { touch ->
            KeyNote(Snip(FloatArray(64) { i ->
                val strength = .01f * (touch + 1) + .0001f * midi
                if (i % 2 == 0) strength else -strength
            }, 1, 44_100), 0L)
        }
    }

    @Test fun `actual sheet adapter publishes complete saved cells on the directly visible shelf`() {
        val root = createTempDirectory("splay-finite-sheet").toFile()
        try {
            val spec = SplayFinitePadMaker.Spec(source())
            val before = spec.patch.toJsonText()
            val notes = fixture(spec)
            val expected = SplayFiniteKeygroupMaker.recipes(spec.keys)
            assertEquals(expected, SplayFinitePadMaker.cells(spec, notes).mapValues { it.value.recipe })
            assertEquals(spec.patch, expected.getValue(SplayFiniteKeygroupMaker.Cell(55, 2)))
            val program = SplayFinitePadMaker.export("Splay Finite", spec, notes, root)
            val (sidecar, instrument) = InstrumentStore.list(root).single()
            assertEquals(root.canonicalFile, sidecar.parentFile.canonicalFile)
            assertEquals(program, instrument.toProgram())
            assertEquals(54, instrument.sampleFiles.size)
            assertEquals(program, SplayFiniteKeygroupMaker.validate("Splay Finite", root, spec.keys).program)
            assertEquals(before, spec.patch.toJsonText())
            assertTrue(SplayFinitePadMaker.instrumentNote(spec).contains("THREE GAIN-ONLY VELOCITY LEVELS"))
            assertTrue(SplayFinitePadMaker.instrumentNote(spec).contains("54 SAMPLES"))
        } finally { root.deleteRecursively() }
    }

    @Test fun `five accepted factory choices remain one finite voice with exact saved sound fields`() {
        val factories = SplayFiniteKeygroupMaker.factorySpecs()
        assertEquals(SplayAcceptedBank.entries.map { it.patch }, factories.values.map { it.patch })
        assertEquals(5, factories.size)
        assertEquals(setOf(SplayVoice.TONGUE), factories.values.map { it.patch.voice }.toSet())
        for ((_, factory) in factories) {
            val patch = factory.patch
            val before = patch.toJsonText()
            val spec = SplayFinitePadMaker.Spec(patch)
            assertTrue(SplayFinitePadMaker.supports(patch))
            assertEquals((36..84 step 3).toList(), SplayFinitePadMaker.zoneMidis(spec))
            val cells = SplayFinitePadMaker.cells(spec, fixture(spec))
            assertEquals(patch, cells.getValue(SplayFiniteKeygroupMaker.Cell(patch.midi, 2)).recipe)
            for ((cell, note) in cells) {
                assertEquals(patch.copy(midi = cell.midi,
                    velocity = patch.velocity * SplayFiniteKeygroupMaker.TOUCHES[cell.touch]), note.recipe)
            }
            assertEquals(before, patch.toJsonText())
        }
        assertFalse(SplayFinitePadMaker.supports(source().copy(velocity = 0f)))
        assertFailsWith<IllegalArgumentException> { SplayFinitePadMaker.Spec(source().copy(velocity = 0f)) }
    }

    @Test fun `incomplete grid and loop metadata refuse before any instrument enters the shelf`() {
        val root = createTempDirectory("splay-finite-sheet-refusal").toFile()
        try {
            val spec = SplayFinitePadMaker.Spec(source())
            val notes = fixture(spec)
            val looped = notes.mapIndexed { zone, layers ->
                layers.mapIndexed { touch, note ->
                    if (zone == 0 && touch == 0) note.copy(loopStartFrame = 1L) else note
                }
            }
            val incomplete = notes.mapIndexed { zone, layers -> if (zone == 0) layers.dropLast(1) else layers }
            for (bad in listOf(notes.dropLast(1), incomplete, looped)) {
                assertFailsWith<IllegalArgumentException> { SplayFinitePadMaker.export("Refused Splay", spec, bad, root) }
                assertTrue(InstrumentStore.list(root).isEmpty())
                assertTrue(root.listFiles()!!.isEmpty())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun `cancellation refuses both sheet rendering paths before Kotlin engine work`() {
        val spec = SplayFinitePadMaker.Spec(source())
        assertFailsWith<CancellationException> { SplayFinitePadMaker.preview(spec) { true } }
        assertFailsWith<CancellationException> { SplayFinitePadMaker.renderZone(spec, spec.patch.midi) { true } }
    }

    @Test fun `real sheet zone replays all three absolute legacy excitation levels exactly`() {
        val spec = SplayFinitePadMaker.Spec(source())
        val notes = SplayFinitePadMaker.renderZone(spec, spec.patch.midi)
        assertEquals(3, notes.size)
        for ((touch, scale) in SplayFiniteKeygroupMaker.TOUCHES.withIndex()) {
            val expected = spec.patch.copy(velocity = spec.patch.velocity * scale).render()
            assertContentEquals(expected.samples, notes[touch].snip.samples)
            assertEquals(expected.sampleRate, notes[touch].snip.sampleRate)
            assertEquals(expected.channels, notes[touch].snip.channels)
            assertEquals(0L, notes[touch].loopStartFrame)
        }
    }
}
