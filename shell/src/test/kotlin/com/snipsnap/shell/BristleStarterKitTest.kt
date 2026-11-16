package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.kit.*
import com.snipsnap.synth.*
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.*

class BristleStarterKitTest {
    @Test fun `phone grid and explicit MPC projections retain their actual playback windows`() {
        val root=createTempDirectory("bristle-starter").toFile()
        try {
            val dir=File(root,"source")
            val kit=requireNotNull(StarterKits.byId("bristle")).render("Bristle Starter",dir)
            assertEquals(kit,KitStore.load(dir));assertEquals(16,kit.pads.size)
            assertFalse(Preflight.check(kit,dir).blocked())
            val exported=BristleStarterExport.write(File(root,"export"))
            val imported=Mpc3Importer.import(exported.native.program,File(root,"import"))
            for ((index,pad) in kit.pads.withIndex()) {
                val source=BristleKits.patches()[index]
                val grid=BristleKits.grid(source)
                assertEquals(source,PadRecipe.fromJsonValue(requireNotNull(pad.recipe)).patch)
                val pcm=WavReader.read(File(dir,pad.sampleFile))
                val restored=imported.kit.pads[index]
                assertNull(restored.chain);assertEquals(3,restored.velocityLayers.size)
                for ((z,v) in BristleKits.velocities.withIndex()) {
                    repeat(4) { hitIndex ->
                        val hit=assertNotNull(PadHit.resolve(pad,v,hitIndex) { pcm.frameCount.toLong() })
                        assertEquals(((z*2+hitIndex%2)*44100).toLong(),hit.startFrame)
                        val expected=grid[z][hitIndex%2].samples
                        assertTrue(expected.indices.maxOf { abs(pcm.samples[hit.startFrame.toInt()+it]-expected[it]) } < .000001f)
                    }
                    val samples=restored.velocityLayers.associate { it.sampleFile to WavReader.read(File(imported.directory,it.sampleFile)) }
                    val hit=assertNotNull(PadHit.resolve(restored,v,0) { samples[it]?.frameCount?.toLong() })
                    assertEquals(0L,hit.startFrame)
                    assertTrue(grid[z][0].samples.indices.maxOf { abs(samples.getValue(hit.sampleFile).samples[it]-grid[z][0].samples[it]) }<.000001f)
                    val companion=exported.companions[z]
                    for(take in 0..1) {
                        val file=File(companion.directory,"A${(index+1).toString().padStart(2,'0')}_RR${take+1}.wav")
                        val actual=WavReader.read(file).samples
                        assertTrue(actual.indices.maxOf { abs(actual[it]-grid[z][take].samples[it]*v) }<.000001f)
                    }
                }
            }
            for(companion in exported.companions) {
                val doc=javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(companion.program)
                val zones=doc.getElementsByTagName("ZonePlay")
                assertTrue((0 until 16).all { zones.item(it).textContent=="0" })
                assertEquals(32,companion.samples.size)
            }
        } finally { root.deleteRecursively() }
    }
}
