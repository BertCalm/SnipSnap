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
    @Test fun `RIME replays an authored patch with note velocity controls duration and seed preserved`() {
        val root=createTempDirectory("rime-cli").toFile()
        try {
            val patch=RimePresets.forVoice(RimeVoice.GLINT).single().copy(gateSeconds=.25f)
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
            assertTrue(Presets.forVoice("RIME","GLINT").isEmpty(),"provisional recipes stay outside the phone roster")
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
