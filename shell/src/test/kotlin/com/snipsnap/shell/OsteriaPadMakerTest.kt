package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.OneNote
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.OsteriaPatch
import com.snipsnap.synth.OsteriaPresets
import com.snipsnap.synth.OsteriaVoice
import com.snipsnap.xpm.VoiceMode
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.test.*
import kotlin.test.Test

class OsteriaPadMakerTest {
    private val source get() = OsteriaPresets.forVoice(OsteriaVoice.PLUCK).first()
    private fun fixture() = OsteriaPadMaker.zoneMidis.map {
        OsteriaPadMaker.velocities.map { velocity ->
            KeyNote(Snip(FloatArray(64) { i -> (i / 2 + 1) / 256f * velocity * if (i % 2 == 0) 1f else -.5f }, 2, 44100))
        }
    }
    private fun refuse(body: () -> Unit) { assertTrue(runCatching(body).isFailure) }

    @Test fun allSixVoicesRetainEverySavedPerformanceField() {
        for (voice in OsteriaVoice.entries) {
            val patch = OsteriaPresets.forVoice(voice).first().let {
                it.copy(midi=57, velocity=.43f, macros=it.macros+("HOLD" to .4f),
                    config=it.config.copy(seed=791, seconds=2.0, attackSeconds=.12, releaseSeconds=.31))
            }
            val spec = OsteriaPadMaker.Spec(patch)
            assertTrue(OsteriaPadMaker.supports(patch))
            val cells = OsteriaPadMaker.cells(spec, fixture())
            assertEquals(39, cells.size)
            cells.forEach { (cell, note) ->
                assertEquals(cell.midi, note.patch.midi)
                assertEquals(OsteriaPadMaker.velocities[cell.touch], note.patch.velocity)
                assertEquals(patch.toJsonText(), note.patch.copy(midi=patch.midi, velocity=patch.velocity).toJsonText())
            }
        }
    }

    @Test fun legacyPhraseCannotBeRelabelledAsFixedRootInstrument() {
        val legacy = OsteriaPresets.forVoice(OsteriaVoice.QUARTET).first().copy(
            config=com.snipsnap.synth.OsteriaConfig(model=1))
        assertFalse(OsteriaPadMaker.supports(legacy))
        refuse { OsteriaPadMaker.Spec(legacy) }
    }

    @Test fun cancellationBeforeAnyRenderDoesNotStartSynthesis() {
        val spec = OsteriaPadMaker.Spec(source)
        assertTrue(runCatching { OsteriaPadMaker.renderZone(spec, 48) { true } }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { OsteriaPadMaker.preview(spec) { true } }.exceptionOrNull() is CancellationException)
        var checks = 0
        assertTrue(runCatching { OsteriaPadMaker.renderZone(spec, 48) { ++checks >= 3 } }.exceptionOrNull() is CancellationException)
        assertEquals(3, checks) // Reaches the unchanged renderer callback, before synthesis starts.
        refuse { OsteriaPadMaker.renderZone(spec, 35) }
    }

    @Test fun partialBanksAndSyntheticLoopsAreRefused() {
        val spec = OsteriaPadMaker.Spec(source)
        val notes = fixture()
        refuse { OsteriaPadMaker.cells(spec, notes.dropLast(1)) }
        refuse { OsteriaPadMaker.cells(spec, notes.mapIndexed { index, row -> if(index==0) row.dropLast(1) else row }) }
        refuse { OsteriaPadMaker.cells(spec, notes.map { row -> row.map { it.copy(loopStartFrame=1) } }) }
    }

    @Test fun actualShelfVoiceModeEditAndReexportPreserveAllBoundSourcesAndWavs() {
        val root = Files.createTempDirectory("osteria-shelf-test").toFile()
        try {
            val name = "Original"
            val dest = File(root, "made")
            val program = OsteriaPadMaker.export(name, OsteriaPadMaker.Spec(source), fixture(), dest)
            val sidecar = InstrumentStore.sidecar(dest, name)
            assertEquals(program, InstrumentStore.read(sidecar).toProgram())
            val data = File(dest, "${name}_[TrackData]")
            val metadata = File(data, "osteria-source-recipes.json").readBytes()
            for (mode in VoiceMode.entries) {
                // Exact API used by KeysScreen.cycleVoiceMode, not a fake schema reader.
                OneNote.rewriteDescriptors(dest, name, InstrumentStore.read(sidecar).toProgram().copy(voiceMode=mode))
                assertContentEquals(metadata, File(data, "osteria-source-recipes.json").readBytes())
                val saved = OsteriaPadMaker.read(sidecar)
                assertEquals(source.toJsonText(), saved.source.toJsonText())
                assertEquals(mode, saved.bank.program.voiceMode)
                val copyName = "Copy ${mode.name}"
                val copyRoot = File(root, "copy-${mode.name}")
                val copy = OsteriaPadMaker.reexport(sidecar, copyName, copyRoot)
                val reread = OsteriaPadMaker.read(InstrumentStore.sidecar(copyRoot, copyName))
                assertEquals(copy, reread.bank.program)
                assertEquals(mode, copy.voiceMode)
                val copyData = File(copyRoot, "${copyName}_[TrackData]")
                assertContentEquals(metadata, File(copyData, "osteria-source-recipes.json").readBytes())
                for ((stem, recipe) in saved.bank.recipes) {
                    assertEquals(recipe.toJsonText(), reread.bank.recipes.getValue(stem).toJsonText())
                    assertContentEquals(File(data,"$stem.wav").readBytes(), File(copyData,"$stem.wav").readBytes())
                    val smpl = WavReader.readSmplStrict(File(copyData,"$stem.wav"))!!
                    assertEquals(recipe.midi, smpl.rootNote)
                    assertNull(smpl.loop)
                }
            }
            // A valid generic sampler edit that changes the release must not silently relabel the source.
            OneNote.rewriteDescriptors(dest, name, InstrumentStore.read(sidecar).toProgram().copy(volumeRelease=.8f))
            refuse { OsteriaPadMaker.read(sidecar) }
            refuse { OsteriaPadMaker.reexport(sidecar, "Refused", File(root,"invalid")) }
            assertFalse(File(root,"invalid").exists())
        } finally { root.deleteRecursively() }
    }
}
