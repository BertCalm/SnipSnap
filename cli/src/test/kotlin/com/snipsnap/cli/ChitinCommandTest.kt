package com.snipsnap.cli

import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.*
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ChitinCommandTest {
    @Test fun `finite CHITIN starting point renders edits and saved recipe identically`() {
        val dir=createTempDirectory("chitin-cli").toFile()
        try {
            val errors=ByteArrayOutputStream()
            assertEquals(0,Cli.run(arrayOf("synth","CHITIN","SHELL","--preset","1","--macros","BITE=.8,PHASE=.4","--out",dir.path),PrintStream(ByteArrayOutputStream()),PrintStream(errors)),errors.toString())
            val saved=dir.listFiles()!!.single { it.extension=="patch" }
            val patch=Patches.fromJsonText(saved.readText())
            assertEquals(.8f,patch.macros["BITE"])
            val expected=ByteArrayOutputStream().also { WavWriter.write(it,patch.render()) }.toByteArray()
            assertContentEquals(expected,dir.listFiles()!!.single { it.extension=="wav" }.readBytes())
        } finally { dir.deleteRecursively() }
    }
}
