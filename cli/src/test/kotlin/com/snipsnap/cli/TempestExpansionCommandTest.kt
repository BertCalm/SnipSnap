package com.snipsnap.cli

import com.snipsnap.synth.*
import java.io.*
import kotlin.test.*

class TempestExpansionCommandTest {
    @Test fun `all accepted model three factory presets use the normal CLI roster`() {
        val root=kotlin.io.path.createTempDirectory("tempest-factory-cli").toFile()
        try {
            val out=PrintStream(ByteArrayOutputStream())
            for (v in Tempest.EXPANSION_VOICES) {
                val dir=File(root,v.name)
                assertEquals(0,SynthCommand.run(listOf("TEMPEST",v.name,"--all","--out",dir.path),out))
                val saved=dir.listFiles()!!.filter { it.extension=="patch" }.sortedBy { it.name }.map { Patches.fromJsonText(it.readText()) }
                assertEquals(Presets.forVoice("TEMPEST",v.name),saved)
                assertEquals(saved.size,dir.listFiles()!!.count { it.extension=="wav" })
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun `all four new voices export model three with replayable DECAY edits`() {
        val root=kotlin.io.path.createTempDirectory("tempest-expansion-cli").toFile()
        try {
            val out=PrintStream(ByteArrayOutputStream())
            for (v in Tempest.EXPANSION_VOICES) {
                val dir=File(root,v.name)
                assertEquals(0,SynthCommand.run(listOf("TEMPEST",v.name,"--preset","1","--macros","DECAY=.6","--out",dir.path),out))
                val recipe=dir.listFiles()!!.single { it.extension=="patch" }
                val p=Patches.fromJsonText(recipe.readText()) as TempestPatch
                assertEquals(3,p.model);assertEquals(.6f,p.macros["DECAY"])
                val replay=File(dir,"replay")
                assertEquals(0,SynthCommand.run(listOf("TEMPEST",v.name,"--patch",recipe.path,"--out",replay.path),out))
                assertContentEquals(dir.listFiles()!!.single { it.extension=="wav" }.readBytes(),replay.listFiles()!!.single { it.extension=="wav" }.readBytes())
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun `old model override for a new voice refuses before output`() {
        val root=kotlin.io.path.createTempDirectory("tempest-model-cli").toFile()
        try {
            val dest=File(root,"not-created")
            for (model in listOf("1","2")) {
                assertEquals(2,Cli.run(arrayOf("synth","TEMPEST","SNARE","--model",model,"--out",dest.path),PrintStream(ByteArrayOutputStream()),PrintStream(ByteArrayOutputStream())))
                assertFalse(dest.exists())
            }
        } finally { root.deleteRecursively() }
    }
}
