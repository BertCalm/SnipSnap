package com.snipsnap.shell

import com.snipsnap.audio.*
import com.snipsnap.kit.*
import com.snipsnap.synth.SporeMembrane
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class SporeMembranePlaybackTest {
    @Test fun `prototype grids preserve physical strikes through saving playback and SFZ`() {
        val root=createTempDirectory("spore-membrane").toFile()
        try {
            val grids=SporeMembrane.Voice.entries.map { voice -> listOf(.3f,.6f,1f).map { v -> (0..2).map { SporeMembrane.render(voice,velocity=v,take=it) } } }
            val pads=grids.mapIndexed { i,grid -> ArrangedPad(grid[2][0],if(i==0)DrumClass.KICK else DrumClass.SNARE,level=.8f,velocityTakes=grid) }
            val dir=root.resolve("Native");val kit=KitAssembler.assembleArranged("Prototype",pads,dir)
            assertEquals(kit,KitStore.load(dir))
            for((i,pad)in kit.pads.withIndex()) {
                val wav=WavReader.read(dir.resolve(pad.sampleFile))
                for((z,v)in listOf(.3f,.6f,1f).withIndex())for(t in 0..3) {
                    val hit=assertNotNull(PadHit.resolve(pad,v,t) { wav.frameCount.toLong() })
                    val expected=grids[i][z][t%3]
                    assertEquals(pad.level*v,hit.gainLeft,1e-6f)
                    assertEquals(expected.frameCount.toLong(),hit.endFrameExclusive-hit.startFrame)
                    for(f in expected.samples.indices)assertTrue(kotlin.math.abs(wav.samples[hit.startFrame.toInt()+f]-expected.samples[f])<=3f/8388608)
                }
            }
            val sfz=SfzWriter.write(kit,dir,root.resolve("export"))
            val imported=SfzImporter.import(sfz,root.resolve("import"))
            assertTrue(imported.skipped.isEmpty());assertEquals(2,imported.kit.pads.size)
            for((a,b)in kit.pads.zip(imported.kit.pads)) {
                assertEquals(a.chain,b.chain)
                assertContentEquals(WavReader.read(dir.resolve(a.sampleFile)).samples,WavReader.read(imported.directory.resolve(b.sampleFile)).samples)
            }
        } finally { root.deleteRecursively() }
    }
}
