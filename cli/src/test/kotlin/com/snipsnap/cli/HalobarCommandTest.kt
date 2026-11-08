package com.snipsnap.cli

import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Halobar
import com.snipsnap.synth.HalobarPatch
import com.snipsnap.synth.HalobarPresets
import com.snipsnap.synth.HalobarVoice
import com.snipsnap.synth.Patches
import com.snipsnap.synth.Velocity
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.*

class HalobarCommandTest {
    @Test fun `native auditions retain note edits recipes and strike velocity across the family`() {
        val dir=kotlin.io.path.createTempDirectory("halobar-cli").toFile()
        try {
            for(voice in HalobarVoice.entries) {
                val out=dir.resolve(voice.name)
                val args=listOf("HALOBAR",voice.name,"--preset","2","--midi","65",
                    "--velocity","0.4","--macros","SPACE=.2,RETURN=.7","--out",out.path)
                assertEquals(0,SynthCommand.run(args,PrintStream(ByteArrayOutputStream())))
                val wav=out.listFiles { f -> f.extension=="wav" }!!.single()
                val recipe=out.listFiles { f -> f.extension=="patch" }!!.single()
                val saved=assertIs<HalobarPatch>(Patches.fromJsonText(recipe.readText()))
                val expected=HalobarPresets.forVoice(voice)[1].let {
                    it.copy(macros=it.macros+mapOf("TUNE" to Halobar.tuneForMidi(voice,65),"SPACE" to .2f,"RETURN" to .7f))
                }
                assertEquals(expected,saved)
                val bytes=ByteArrayOutputStream().also { WavWriter.write(it,Velocity.atVelocity(expected,.4f)) }
                assertContentEquals(bytes.toByteArray(),wav.readBytes())
                val replay=dir.resolve("replay-${voice.name}")
                assertEquals(0,SynthCommand.run(listOf("HALOBAR",voice.name,"--patch",recipe.path,
                    "--velocity","0.4","--out",replay.path),PrintStream(ByteArrayOutputStream())))
                assertContentEquals(wav.readBytes(),replay.listFiles { f -> f.extension=="wav" }!!.single().readBytes())
            }
        } finally { dir.deleteRecursively() }
    }
}
