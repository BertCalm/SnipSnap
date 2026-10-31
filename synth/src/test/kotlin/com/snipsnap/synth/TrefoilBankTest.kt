package com.snipsnap.synth

import com.snipsnap.audio.*
import com.snipsnap.json.Json
import com.snipsnap.kit.*
import com.snipsnap.mpc3.*
import com.snipsnap.xpm.WavInfo
import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.math.*
import kotlin.test.*

class TrefoilBankTest {
    @Test fun `twelve complete presets cover all five voices and retain state through editing`() {
        val entries=TrefoilPresets.entries
        assertEquals(12,entries.size);assertEquals(12,entries.map { it.patch.name }.distinct().size)
        assertEquals(TrefoilVoice.entries.toSet(),entries.map { it.patch.voice }.toSet())
        assertEquals(mapOf("Plucks" to 4,"Keys" to 4,"Motion" to 2,"Memory" to 2),entries.groupingBy { it.role }.eachCount())
        for(v in TrefoilVoice.entries) assertEquals(TrefoilPresets.forVoice(v),Presets.forVoice("TREFOIL",v.name))
        assertTrue(Presets.all().containsAll(TrefoilPresets.all()));assertTrue(Presets.forVoice("TREFOIL","UNKNOWN").isEmpty())
        for(e in entries) {
            val p=e.patch;assertEquals(2,p.model);assertEquals(0f,p.macros["HOLD"]);assertEquals(60,p.midi)
            assertEquals(p,PadRecipe.fromJsonText(PadRecipe(p).toJsonText()).patch)
            val state=p.copy(seed=Long.MIN_VALUE+7,midi=53,velocity=.41f)
            assertEquals(state.copy(name="Edited"),Patches.edited(state,"Edited",state.macros))
        }
    }
    @Test fun `new native bank is deterministic through complete recipe replay`() {
        val p=TrefoilPresets.entries.single { it.id=="broken" }.patch.copy(seed=Long.MAX_VALUE)
        assertContentEquals(p.render().samples,PadRecipe.fromJsonText(PadRecipe(p).toJsonText()).render().samples)
    }
    @Test fun `the real kit exports three native velocity zones and reimports both musical patterns`() {
        val temp=createTempDirectory("trefoil-bank").toFile()
        try {
            TrefoilPlayableBankGenerator.generate(temp)
            val folder=File(temp,"kit/${TrefoilKits.NAME}")
            val data=File(folder,"${TrefoilKits.NAME}_[TrackData]")
            val kit=KitStore.load(data)
            assertEquals(16,kit.pads.size);assertFalse(Preflight.check(kit,data).blocked())
            assertEquals(48,data.listFiles()!!.count { it.extension=="wav" })
            for((i,pad) in kit.pads.withIndex()) {
                assertEquals(TrefoilKits.patches()[i],PadRecipe.fromJsonValue(requireNotNull(pad.recipe)).patch)
                assertTrue(pad.oneShot);assertEquals(0,pad.muteGroup);assertEquals(DrumClass.TONAL,pad.drumClass)
                assertEquals(listOf(1 to 41,42 to 84,85 to 127),pad.velocityLayers.map { it.velStart to it.velEnd })
                val peaks=pad.velocityLayers.map { z ->
                    val f=File(data,z.sampleFile);val info=WavInfo.read(f)
                    assertEquals(44100,info.sampleRate);assertEquals(24,info.bitsPerSample);assertEquals(1,info.channels)
                    WavReader.read(f).peak()
                }
                assertTrue(peaks.max()-peaks.min()<2e-6f,"native layer normalization ${pad.displayName}")
            }
            val imported=Mpc3Importer.import(File(folder,"${TrefoilKits.NAME}.xtd"),File(temp,"readback"))
            assertEquals(16,imported.kit.pads.size)
            assertEquals(TrefoilPlayableBankGenerator.demos().map { it.clip },GrooveStore.load(imported.directory))
            assertTrue(imported.kit.pads.all { it.velocityLayers.size==3 && it.oneShot })
            assertTrue(File(data,"${TrefoilKits.NAME}.xpm").isFile)
            ZipFile(File(temp,"SnipSnap-Trefoil-Kit.zip")).use { z ->
                assertEquals(48,z.entries().asSequence().count { it.name.endsWith(".wav") })
                assertTrue(z.entries().asSequence().any { it.name.endsWith("kit.json") })
            }
            // Validate the sampler's real soft-zone selection, rather than just its metadata.
            val pad=kit.pads.first();val demo=TrefoilPlayableBankGenerator.Demo("soft","Soft","",96f,
                Mpc3Clip("Soft",1,listOf(Mpc3Note(Mpc3Note.noteFor(pad.slot),0L,.2f))))
            val actual=TrefoilPlayableBankGenerator.performance(kit,data,demo)
            val native=WavReader.read(File(data,pad.velocityLayers.first().sampleFile))
            val gain=.45f*sqrt(.5f)*pad.level*(.35f+.65f*.2f)
            assertEquals(native.frameCount,actual.frameCount)
            for(i in actual.samples.indices step 97) assertEquals(native.samples[i]*gain,actual.samples[i],1e-8f)
            val manifest=Json.parse(File(temp,"manifest.json").readText()).obj()
            assertEquals(12,manifest.getValue("presets").arr().size);assertEquals(2,manifest.getValue("demos").arr().size)
            for(demoRow in manifest.getValue("demos").arr()) {
                val d=demoRow.obj();val wav=WavReader.read(File(temp,d.getValue("path").str()))
                assertTrue(wav.samples.all(Float::isFinite) && wav.peak() in .001f.. .99f)
                assertTrue(wav.durationSeconds>16f*60f/d.getValue("bpm").num().toFloat(),"musical tail cut off")
            }
            assertTrue("git apply --allow-empty" in File(temp,"REPLAY.txt").readText())

            // Exercise the same missing-ref and truncated-history conditions as
            // actions/checkout, without fetching history or relaxing the audio checks.
            val repo=File(temp,"source-checkout").apply { mkdirs() }
            val hooks=File(repo,"empty-hooks").apply { mkdirs() }
            fun git(vararg args: String): String {
                val process=ProcessBuilder(listOf("git","-c","core.hooksPath=${hooks.absolutePath}")+args)
                    .directory(repo).redirectErrorStream(true).start()
                val text=process.inputStream.bufferedReader().readText().trim()
                assertEquals(0,process.waitFor(),text)
                return text
            }
            fun commit()=git("-c","user.name=Trefoil replay test","-c","user.email=replay-test@example.invalid",
                "-c","commit.gpgsign=false","commit","-qm","Source fixture")
            git("init","-q");git("config","core.autocrlf","false")
            val source=File(repo,"source.txt").apply { writeText("base\n") }
            git("add","source.txt");commit();val publicBase=git("rev-parse","HEAD")
            source.writeText("committed\n");git("add","source.txt");commit()
            val revision=git("rev-parse","HEAD")
            git("update-ref","refs/remotes/origin/main",publicBase)
            git("symbolic-ref","refs/remotes/origin/HEAD","refs/remotes/origin/main")
            assertEquals(publicBase,TrefoilPlayableBankGenerator.replayBase(repo))
            git("symbolic-ref","--delete","refs/remotes/origin/HEAD")
            File(repo,".git/shallow").writeText("$revision\n")
            assertEquals("true",git("rev-parse","--is-shallow-repository"))
            assertEquals(revision,TrefoilPlayableBankGenerator.replayBase(repo))
            assertEquals("",git("diff",revision),"clean shallow replay needs no invented source delta")
            source.writeText("local edit\n")
            val delta=File(temp,"source-delta.patch").apply { writeText(git("diff",revision)+"\n") }
            git("checkout","--","source.txt")
            git("apply","--allow-empty",delta.absolutePath)
            assertEquals("local edit\n",source.readText(),"the recorded commit plus delta restores local source")
        } finally { temp.deleteRecursively() }
    }
}
