package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.DestinationExists
import com.snipsnap.synth.*
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.*

class HalobarKeysTest {
    @Test fun `six mallet captures survive package reload with velocity and cycling audio`() {
        val dir = Files.createTempDirectory("halobar-keys").toFile()
        try {
            for (voice in HalobarVoice.entries) {
                val spec = HalobarPadMaker.Spec(HalobarPatch("Keys", voice, mapOf("SPACE" to 0f, "HOLD" to 0f)), .1f, 17)
                assertEquals(spec, HalobarPadMaker.Spec.fromJsonText(spec.toJsonText()))
                val midi = Halobar.rootMidi(voice)
                val captures = HalobarPadMaker.renderZone(spec, midi)
                assertEquals(6, captures.size)
                assertTrue(captures.all { it.loopStartFrame == 0L })
                val name = "H${voice.ordinal}"
                // Package plumbing uses real root captures. The production generator renders each zone independently.
                val notes = HalobarPadMaker.zoneMidis(spec).map { captures }
                val program = HalobarPadMaker.export(name, spec, notes, dir)
                val inst = InstrumentStore.read(InstrumentStore.sidecar(dir, name))
                assertEquals(program, inst.toProgram())
                assertEquals(spec, HalobarPadMaker.Spec.fromJsonText(dir.resolve("$name${HalobarPadMaker.RECIPE_SUFFIX}").readText()))
                assertFailsWith<DestinationExists> { HalobarPadMaker.export(name, spec, notes, dir) }
                val cursor = KeyHit.CycleCursor()
                fun play(v: Float): Pair<String, FloatArray> {
                    val hit = KeyHit.resolveAll(inst, midi, v, cursor) { path -> WavReader.read(dir.resolve(path)).frameCount.toLong() }.single().hit
                    return hit.sampleFile to WavReader.read(dir.resolve(hit.sampleFile)).samples
                }
                val soft = List(4) { play(.4f) }
                assertEquals(3, soft.map { it.first }.distinct().size)
                assertContentEquals(soft[0].second, soft[3].second)
                assertFalse(soft[0].second.contentEquals(soft[1].second))
                val hard = play(1f)
                assertTrue(hard.second.maxOf { abs(it) } > soft[0].second.maxOf { abs(it) })
                for (chord in ChordStabs.stabs(KeySpec.parse("Cm"))) for (note in HalobarChords.notes(voice, chord))
                    assertNotNull(KeyHit.resolve(inst, note, .8f) { 256L })
                assertTrue(program.keygroups.all { it.mpcLayers.size == 2 && !it.mpcCycle })
            }
        } finally { dir.deleteRecursively() }
    }
}
