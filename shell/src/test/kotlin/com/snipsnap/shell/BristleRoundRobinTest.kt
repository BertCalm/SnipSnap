package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.*
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class BristleRoundRobinTest {
    @Test fun `saved velocity pools cycle independently and companions retain both takes`() {
        val root = createTempDirectory("bristle-rr").toFile()
        try {
            for (patch in BristlePresets.all()) for (held in listOf(false,true)) {
                val name = "RR ${patch.voice} $held"
                val spec = BristleKeygroupMaker.Spec(patch,36,36,held=held,takes=2)
                val notes = BristleKeygroupMaker.render(spec)
                assertEquals(6, notes.size)
                val assembly = BristleKeygroupMaker.writeNewPackage(name,spec,notes,File(root,name))
                val folder = File(root,name)
                assertEquals(assembly.program,BristleKeygroupMaker.validate(name,folder,spec).program)
                val instrument = InstrumentStore.read(InstrumentStore.sidecar(folder,name))
                val samples = instrument.sampleFiles.associateWith { WavReader.read(File(folder,it)).samples }
                val cursor = KeyHit.CycleCursor()
                repeat(4) { repeatIndex -> for ((touch,v) in BristleKeygroupMaker.TOUCHES.withIndex()) {
                    val hit = KeyHit.resolveAll(instrument,36,v,cursor) { samples[it]?.size?.toLong() }.single().hit
                    assertEquals(instrument.zones.single().layers[touch*2+repeatIndex%2].sample,hit.sampleFile)
                    assertEquals(if(held)44100L else -1L,hit.loopStartFrame)
                } }
                cursor.clear()
                assertEquals(instrument.zones.single().layers.first().sample,
                    KeyHit.resolveAll(instrument,36,.35f,cursor) { samples[it]?.size?.toLong() }.single().hit.sampleFile)
                // Playback must repeat the two waveforms, not just select different filenames.
                val player = InstrumentEngine(InstrumentEngine.Loaded(instrument,44100,samples),44100)
                val attacks = (0..3).map {
                    assertTrue(player.noteOn(36,1f))
                    val out = FloatArray(2048); player.render(out,1024)
                    player.noteOff(36); player.render(FloatArray(44100),22050)
                    out
                }
                assertContentEquals(attacks[0],attacks[2]); assertContentEquals(attacks[1],attacks[3])
                assertFalse(attacks[0].contentEquals(attacks[1]))
                val kg = assembly.program.keygroups.single()
                assertEquals(3,kg.mpcLayers.size); assertFalse(kg.mpcCycle)
                val data = File(folder,Mpc3TrackWriter.trackDataDirName(name))
                for (touchName in listOf("Soft","Medium","Hard")) {
                    val companion = File(data,"MPC $touchName")
                    val phone = InstrumentStore.read(InstrumentStore.sidecar(companion,"$name $touchName"))
                    assertTrue(phone.zones.single().cycleLayers)
                    assertEquals(2,phone.zones.single().layers.size)
                    val zone = Mpc3Project.read(File(companion,"$name $touchName.xty")).drumPrograms().single()
                        .getValue("instruments").arr().first().obj()
                    assertEquals(0,zone.getValue("zonePlayTime").int())
                    assertEquals(2,zone.getValue("layersv").arr().count { it.obj().getValue("sampleName").str().isNotEmpty() })
                }
                val bad = File(data,"MPC Soft/$name Soft.xty")
                bad.appendText("tamper")
                assertFails { BristleKeygroupMaker.validate(name,folder,spec) }
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun `sheet exposes six attacks without changing the default`() {
        val patch = BristlePresets.all().first()
        assertEquals(1,BristlePadMaker.Spec(patch).takes)
        val spec = BristlePadMaker.Spec(patch,takes=2)
        assertTrue("150 samples" in BristlePadMaker.instrumentNote(spec))
        assertEquals(6,BristlePadMaker.renderZone(spec,36).size)
        val preview = BristlePadMaker.preview(spec)
        assertEquals(2,preview.channels)
        assertTrue(preview.samples.any { it != 0f })
        assertTrue(preview.samples.takeLast(1000).all { it == 0f })
    }
    @Test fun `damaged companion cannot publish a partial shelf instrument`() {
        val root = createTempDirectory("bristle-rr-admit").toFile()
        try {
            val spec = BristleKeygroupMaker.Spec(BristlePresets.all().first(),36,36,takes=2)
            val notes = BristleKeygroupMaker.render(spec)
            assertFails {
                BristleKeygroupMaker.exportFresh("RR",spec,notes,root,
                    InstrumentPackageAdmission.Hooks(staged = { stage ->
                        val data = File(stage,"RR_[TrackData]/MPC Soft/RR Soft_[TrackData]")
                        data.listFiles()!!.first { it.extension == "wav" }.delete()
                    }))
            }
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }
}
