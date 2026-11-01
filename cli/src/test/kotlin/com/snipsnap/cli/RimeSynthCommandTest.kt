package com.snipsnap.cli

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class RimeSynthCommandTest {
    @Test fun `explicit model five exports a complete saved recipe as held keys`() {
        val root = createTempDirectory("rime-cli-held").toFile()
        try {
            val out = PrintStream(ByteArrayOutputStream())
            assertEquals(0, SynthCommand.run(listOf("RIME", "LOOP", "--model", "5", "--instrument",
                "--midi", "43", "--velocity", ".6", "--seed", "2718", "--release", ".4", "--out", root.path), out))
            val patch = RimePatch.fromJsonText(root.listFiles()!!.single { it.extension == "patch" }.readText())
            assertEquals(5, patch.model); assertEquals(43, patch.midi)
            assertEquals(.6f, patch.velocity); assertEquals(2718, patch.seed)
            val instrument = com.snipsnap.kit.InstrumentStore.list(root).single().second
            assertEquals((31..55 step 3).toList(), instrument.zones.map { it.rootNote })
            assertEquals(.4f, instrument.release)
            assertTrue(instrument.zones.all { it.loopStartFrame > 0 })
        } finally { root.deleteRecursively() }
    }

    @Test fun `held pad WAV retains its native root loop and complete effective recipe`() {
        val root = createTempDirectory("rime-cli-cycle").toFile()
        try {
            assertEquals(0, SynthCommand.run(listOf("RIME", "VESSEL", "--model", "5", "--macros", "HOLD=1", "--out", root.path), PrintStream(ByteArrayOutputStream())))
            val wave = root.listFiles()!!.single { it.extension == "wav" }
            val sampler = assertNotNull(WavReader.readSmpl(wave))
            assertEquals(48, sampler.rootNote)
            assertEquals(0L, assertNotNull(sampler.loop).startFrame)
            assertEquals(WavReader.read(wave).frameCount.toLong(), sampler.loop!!.endFrameExclusive)
            val p = RimePatch.fromJsonText(File(root, wave.nameWithoutExtension + ".patch").readText())
            assertTrue(Rime.isLoop(p.macros)); assertEquals(5, p.model)
        } finally { root.deleteRecursively() }
    }

    @Test fun `all renders the three named musical presets for a RIME voice`() {
        val root=createTempDirectory("rime-cli-bank").toFile()
        try {
            assertEquals(0,SynthCommand.run(listOf("RIME","GLINT","--all","--out",root.path),PrintStream(ByteArrayOutputStream())))
            assertEquals(3,root.listFiles()!!.count { it.extension=="wav" })
        } finally { root.deleteRecursively() }
    }

    @Test fun `RIME replays an authored patch with note velocity controls duration and seed preserved`() {
        val root=createTempDirectory("rime-cli").toFile()
        try {
            val patch=RimePresets.starter(RimeVoice.GLINT).copy(gateSeconds=.25f)
            val source=File(root,"patch.json").apply{writeText(patch.toJsonText())}
            val destination=File(root,"render")
            val args=listOf("RIME","GLINT","--patch",source.path,"--midi","65","--velocity",".6","--seconds",".3","--seed","2718",
                "--macros","DETAIL=.9,EDGE=.7","--out",destination.path)
            assertEquals(0,SynthCommand.run(args,PrintStream(ByteArrayOutputStream())))
            val actual=destination.listFiles()!!.single{it.extension=="wav"}
            val expected=patch.copy(midi=65,velocity=.6f,gateSeconds=.3f,seed=2718,macros=patch.macros+mapOf("DETAIL" to .9f,"EDGE" to .7f))
            val expectedFile=File(root,"expected.wav");WavWriter.write(expectedFile,expected.render())
            assertContentEquals(expectedFile.readBytes(),actual.readBytes())
            assertEquals(44100,WavReader.read(actual).sampleRate)
            assertEquals(RimePresets.forVoice(RimeVoice.GLINT), Presets.forVoice("RIME","GLINT"))
        } finally {root.deleteRecursively()}
    }

    @Test fun `invalid notes controls and unsupported sustain fail before output creation`() {
        val root=createTempDirectory("rime-cli-invalid").toFile()
        try {
            val destination=File(root,"not-created")
            val bad=listOf(listOf("--midi","97"),listOf("--velocity","NaN"),listOf("--seed","1.2"),
                listOf("--seconds","9"),listOf("--macros","DETAIL=NaN"),listOf("--macros","BOGUS=.5"),
                listOf("--macros","HOLD=1"),listOf("--instrument"))
            for(flags in bad) {
                val errors=ByteArrayOutputStream()
                assertEquals(2,Cli.run((listOf("synth","RIME","GLINT")+flags+listOf("--out",destination.path)).toTypedArray(),
                    PrintStream(ByteArrayOutputStream()),PrintStream(errors)),errors.toString())
                assertFalse(destination.exists(),"refused flags $flags must not create output")
            }
        } finally {root.deleteRecursively()}
    }
}
