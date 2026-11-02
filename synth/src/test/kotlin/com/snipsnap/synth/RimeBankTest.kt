package com.snipsnap.synth

import com.snipsnap.audio.*
import com.snipsnap.kit.*
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.*

class RimeBankTest {
    @Test fun `thirty six complete presets cover six voices and retain the accepted anchors`() {
        val entries = RimePresets.entries()
        assertEquals(36, entries.size)
        assertEquals(36, entries.map { it.id }.distinct().size)
        assertEquals(36, entries.map { it.patch.name }.distinct().size)
        for (voice in RimeVoice.entries) {
            assertEquals(6, RimePresets.forVoice(voice).size)
            assertTrue(RimePresets.all().contains(RimePresets.starter(voice)))
        }
        for ((id, role, description, p) in entries) {
            assertTrue(id.isNotBlank() && role.isNotBlank() && description.isNotBlank())
            assertTrue(p.model in listOf(4, 6))
            assertEquals(Rime.defaults(p.voice,p.model).keys, p.macros.keys)
            assertEquals(p, Patches.fromJsonText(p.toJsonText()))
        }
    }

    @Test fun `each preset pair changes spectral shape at the same note gate and velocity`() {
        val clustered = mutableListOf<String>()
        for (voice in RimeVoice.entries) {
            val presets = RimePresets.forVoice(voice)
            val notes = presets.map { it.copy(midi=Rime.rootMidi(voice),gateSeconds=.6f,velocity=.8f,macros=it.macros+("HOLD" to 0f)).render().samples }
            for (i in notes.indices) for (j in i+1 until notes.size) {
                val distance = RimeControlMeasure.distance(notes[i],notes[j],.6)
                println("RIME bank $voice ${presets[i].name}/${presets[j].name} spectral distance $distance")
                if (distance <= .10) clustered += "$voice ${presets[i].name}/${presets[j].name}: $distance"
            }
        }
        assertTrue(clustered.isEmpty(), "Recipes cluster after level matching:\n${clustered.joinToString("\n")}")
    }

    @Test fun `native bank notes retain pitch headroom and complete recipes over three registers`() {
        for (p in RimePresets.all()) for (offset in listOf(-12,0,12)) {
            val patch = p.copy(midi=p.midi+offset)
            val note = patch.render()
            assertTrue(note.samples.all { it.isFinite() })
            assertTrue(note.peak() <= .94001f)
            assertTrue(abs(note.samples.average()) < .002)
            if (patch.model == 6) {
                val actual = Classifier.classify(note).drumClass
                assertTrue(actual in setOf(DrumClass.PERC, DrumClass.TONAL, DrumClass.LOOP), "${patch.name}/${patch.midi} classified as $actual")
            }
            assertEquals(if(Rime.isLoop(patch.macros) || note.durationSeconds>1.5)DrumClass.LOOP else DrumClass.TONAL,
                Rime.drumClassFor(patch.voice,patch.macros,patch.gateSeconds,patch.model))
            val hz = Keys.midiHz(patch.midi)
            val measured = FineTuning.measuredHz(note,hz,fromSec=.02f,bodySeconds=.18f)
            assertNotNull(measured,"${patch.name}/${patch.midi} root missing")
            val cents = FineTuning.cents(measured.toDouble(),hz.toDouble())
            println("RIME bank ${patch.name}/${patch.midi}: $cents cents")
            assertTrue(abs(cents)<12,"${patch.name}/${patch.midi} root $cents cents")
        }
    }

    @Test fun `generated MPC programs keep sixteen editable pads and all native velocity layers`() {
        val root=createTempDirectory("rime-bank-kit").toFile()
        try {
            val bundle=RimeKitGenerator.generate(root)
            val kit=KitStore.load(bundle.data)
            assertEquals((1..16).toList(),kit.pads.map { it.slot })
            assertEquals(48,bundle.data.listFiles()!!.count { it.extension=="wav" })
            assertFalse(bundle.findings.blocked())
            assertTrue(File(bundle.directory,"${kit.name}.xtd").isFile)
            assertTrue(File(bundle.data,"${kit.name}.xpm").isFile)
            assertTrue(bundle.archive.isFile)
            val patches=RimeKits.patches()
            assertEquals(listOf(36,39,41,43,46,48,51,53),patches.take(8).map { it.midi })
            assertTrue(patches.map { it.name }.containsAll(RimePresets.legacyEntries().map { it.patch.name }))
            for (pad in kit.pads) {
                val saved=PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
                val p=assertIs<RimePatch>(saved.patch)
                assertEquals(patches[pad.slot-1],p)
                assertNull(saved.fx)
                assertTrue(pad.oneShot)
                assertEquals(0,pad.tuneCoarse);assertEquals(0,pad.tuneFine)
                assertEquals(listOf(1 to 41,42 to 84,85 to 127),pad.velocityLayers.map { it.velStart to it.velEnd })
                val reference=saved.render()
                for ((index,layer) in pad.velocityLayers.withIndex()) {
                    val native=if(index==2) reference else Velocity.peakMatch(reference,
                        p.copy(velocity=listOf(.3f,.6f)[index]).render())
                    val bytes=ByteArrayOutputStream().also { WavWriter.write(it,native) }.toByteArray()
                    assertContentEquals(bytes,File(bundle.data,layer.sampleFile).readBytes(),"${pad.displayName} zone $index")
                }
            }
        } finally { root.deleteRecursively() }
    }
}
