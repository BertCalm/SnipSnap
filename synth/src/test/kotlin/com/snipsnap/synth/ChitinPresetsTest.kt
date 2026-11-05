package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
import com.snipsnap.xpm.WavInfo
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.*

class ChitinPresetsTest {
    @Test fun `twelve candidate recipes remain distinct complete and reproducible`() {
        for(voice in ChitinVoice.entries) {
            val presets=ChitinPresets.forVoice(voice)
            assertEquals(6,presets.size)
            assertEquals(6,presets.map { it.name }.toSet().size)
            for(p in presets) {
                assertEquals(p.name.uppercase(),p.name);assertTrue(p.name.length<=14)
                assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(p.name))
                assertEquals(Chitin.defaults(voice).keys,p.macros.keys)
                assertEquals(p,Patches.fromJsonText(p.toJsonText()))
                assertContentEquals(p.render().samples,Patches.fromJsonText(p.toJsonText()).render().samples)
            }
            for((i,a) in presets.withIndex()) for(b in presets.drop(i+1))
                assertTrue(PresetTestSupport.rmsDistance(a.macros,b.macros)>.05f)
        }
    }
    @Test fun `research names are blocked while candidate names remain usable`() {
        for(name in listOf("BEETRONICS", "LARVA", "ARP ODYSSEY"))
            assertTrue(PresetTestSupport.trademarkBlocklist.containsMatchIn(name))
        for(p in ChitinPresets.all()) assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(p.name))
    }
    @Test fun `candidate audio has headroom honest classes and usable level`() {
        for(p in ChitinPresets.all()) {
            val r=Chitin.inspect(p.voice,p.macros)
            assertTrue(r.snip.samples.all { it.isFinite() && abs(it)<=.991f })
            assertTrue(abs(r.snip.samples.average())<.005)
            assertTrue(Loudness.of(r.snip)>=Dsp.MELODIC_LOUDNESS_TARGET*.9f || r.snip.peak()>=.95f,p.name)
            val actual=Classifier.classify(r.snip).drumClass
            assertTrue(actual in setOf(DrumClass.PERC,DrumClass.TONAL,DrumClass.LOOP),"${p.name}: $actual")
            assertEquals(actual,Chitin.drumClassFor(p.voice,p.macros))
            assertFalse(Chitin.isLoop(p.macros.getValue("HOLD")))
        }
    }
    @Test fun `sixteen pads carry dry replayable recipes and the intended bass roots`() {
        val pads=SynthKits.chitin()
        assertEquals(16,pads.size)
        val expected=listOf(24,27,29,31,34,36,39,41)
        for((i,pad) in pads.withIndex()) {
            val p=assertNotNull(pad)
            assertTrue(p.oneShot);assertEquals(0,p.tuneCoarse);assertEquals(0,p.tuneFine)
            val recipe=PadRecipe.fromJsonValue(assertNotNull(p.recipe))
            assertNull(recipe.fx)
            assertContentEquals(p.snip.samples,recipe.render().samples)
            assertEquals(Classifier.classify(p.snip).drumClass,p.drumClass)
            if(i<8) assertEquals(expected[i],p.source.getValue("rootMidi").toInt())
        }
        val late=pads.drop(8).map { it!!.drumClass==DrumClass.LOOP }
        assertEquals(late.sorted(),late,"long finite notes belong at the end")
    }
    @Test fun `MPC program points to sixteen actual WAVs and never enables looping`() {
        val root=Files.createTempDirectory("chitin-export").toFile()
        try {
            val work=root.resolve("work")
            val kit=KitAssembler.assembleArranged("SnipSnap Chitin Kit",SynthKits.chitin(),work)
            val exported=KitExporter.exportProgramFolder(kit,work,root.resolve("export"))
            assertEquals(16,exported.samples.size)
            val xml=exported.program.readText()
            assertFalse(Regex("<Loop>\\s*True\\s*</Loop>",RegexOption.IGNORE_CASE).containsMatchIn(xml))
            assertFalse(Regex("<SliceLoop>\\s*1\\s*</SliceLoop>").containsMatchIn(xml))
            for(file in exported.samples) {
                val info=WavInfo.read(file)
                assertEquals(44100,info.sampleRate);assertTrue(info.frameCount>0)
                assertTrue(xml.contains(file.nameWithoutExtension))
            }
        } finally { root.deleteRecursively() }
    }
}
