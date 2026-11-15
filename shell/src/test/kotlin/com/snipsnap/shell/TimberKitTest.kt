package com.snipsnap.shell

import com.snipsnap.audio.*
import com.snipsnap.kit.*
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.TimberKits
import java.io.File
import java.nio.file.Files
import kotlin.math.*
import kotlin.test.*

/** Gates use the actual chain WAV, saved selection state and exported track. */
class TimberKitTest {
    @Test fun `all three genre banks preserve every strike through saving playback and MPC3 export`() {
        val root=Files.createTempDirectory("timber-gates").toFile()
        try { for(g in TimberKits.Genre.entries) {
            val pads=TimberKits.build(g)
            val dir=File(root,g.name)
            val kit=StarterKits.ALL.single { it.id==g.id }.render(g.title,dir)
            val saved=KitStore.load(dir)
            assertEquals(kit,saved)
            for((r,pad) in saved.pads.withIndex()) assertEquals(TimberKits.level(r),pad.level)
            assertEquals(1,saved.pads[3].muteGroup)
            assertEquals(1,saved.pads[4].muteGroup)
            TriadKitProof.verify(saved,dir,pads)
            val exported=Mpc3Exporter.exportTrack(saved,dir,File(dir,"mpc"))
            val data=File(exported.directory,Mpc3TrackWriter.trackDataDirName(kit.name))
            TriadKitProof.verify(saved,data,pads)
            TriadKitProof.verifyTrack(exported.program,saved)
            for((r,pad) in saved.pads.withIndex()) {
                val rebuilt=TimberKits.replay(pad.source)
                for(z in 0..2) for(t in 0..3)
                    assertContentEquals(pads[r].velocityTakes[z][t].samples,rebuilt[z][t].samples)
                val chain=requireNotNull(pad.chain)
                val wav=WavReader.read(File(dir,pad.sampleFile))
                // Both boundaries of each MIDI velocity zone, and two full round-robin cycles.
                for(zone in chain.zones!!) for(midi in listOf(zone.velStart,zone.velEnd)) for(t in 0..7) {
                    val hit=requireNotNull(PadHit.resolve(pad,midi/127f,t,1f){wav.frameCount.toLong()})
                    val slice=zone.baseSlice+t%4
                    assertEquals(chain.boundaries[slice],hit.startFrame)
                    val native=requireNotNull(PadHit.resolve(pad,midi/127f,t){wav.frameCount.toLong()})
                    assertEquals(pad.level*(midi/127f),native.gainLeft)
                    assertEquals(native.gainLeft,native.gainRight)
                    assertEquals(chain.boundaries.getOrNull(slice+1) ?: wav.frameCount.toLong(),hit.endFrameExclusive)
                }
            }
        } } finally { root.deleteRecursively() }
    }

    @Test fun `touch changes shape takes differ and all renders stay finite and decay`() {
        var worstTouch=0.0; var worstTake=0.0; var peak=0f
        for(g in TimberKits.Genre.entries) for(r in 0..15) {
            val grid=TimberKits.velocities.map { v -> List(4) { t -> TimberKits.render(g,r,v,t).samples } }
            for(row in grid) for(x in row) {
                assertTrue(x.all { it.isFinite() && abs(it)<1f })
                peak=max(peak,x.maxOf { abs(it) })
                assertTrue(abs(x.average())<.005,"$g/$r DC")
                assertTrue(x.last()==0f)
                assertTrue(x.maxOf { abs(it) }>.001,"$g/$r audible")
                // Independent post-contact window: a sign error in modal decay would fail.
                val late=x.takeLast(4410).sumOf { it.toDouble()*it }/4410
                val attack=x.take(4410).sumOf { it.toDouble()*it }/4410
                assertTrue(late<attack*.05,"$g/$r tail energy")
            }
            for(t in 0..3) {
                assertTrue(rms(grid[0][t])<rms(grid[1][t]) && rms(grid[1][t])<rms(grid[2][t]),"$g/$r/$t velocity ordering")
                assertTrue(distanceDb(grid[0][t],grid[2][t])> -35,"$g/$r/$t timbral velocity")
            }
            for(row in grid) {
                val spread=20*log10(row.maxOf { rms(it) }/row.minOf { rms(it) })
                assertTrue(spread<1.5,"$g/$r take RMS spread $spread dB")
            }
            val soft=grid[0][0]; val hard=grid[2][0]
            assertTrue(rms(soft)<rms(hard),"$g/$r native dynamics")
            val touch=distanceDb(soft,hard)
            assertTrue(touch> -35,"$g/$r velocity is only gain: $touch dB")
            worstTouch=min(worstTouch,touch)
            // R2/R3 audition contract: subtle takes must differ without the old large level swings.
            for(row in grid) for(a in 0..3) for(b in a+1..3) {
                val diff=distanceDb(row[a],row[b])
                assertTrue(diff> -45,"$g/$r copies: $diff dB")
                worstTake=min(worstTake,diff)
            }
        }
        println("TIMBER matched-shape residual: touch minimum $worstTouch dB, take minimum $worstTake dB; peak $peak")
    }
    @Test fun `genre kits use different core instruments and land on shelf with choking rakes`() {
        for(g in TimberKits.Genre.entries) {
            val starter=StarterKits.ALL.single { it.id==g.id }
            assertEquals(g.bpm.toFloat(),starter.tempoBpm)
            assertEquals(16,TimberKits.names(g).size)
            assertEquals(TimberKits.Instrument.entries.toSet(),TimberKits.hits(g).map { it.instrument }.toSet())
        }
        for(r in 0..4) assertEquals(3,TimberKits.Genre.entries.map { TimberKits.hits(it)[r] }.toSet().size)
        assertFailsWith<IllegalArgumentException>{TimberKits.render(TimberKits.Genre.POCKET,0,Float.NaN,0)}
        assertFailsWith<IllegalArgumentException>{TimberKits.render(TimberKits.Genre.POCKET,0,1f,4)}
    }
    companion object {
        fun rms(x:FloatArray)=sqrt(x.sumOf { it.toDouble()*it }/x.size)
        /** Level-matched residual against the best scalar fit: zero for a volume-only clone. */
        fun distanceDb(a:FloatArray,b:FloatArray):Double {
            require(a.size==b.size)
            val aa=a.sumOf { it.toDouble()*it }.coerceAtLeast(1e-20)
            var ab=0.0; var bb=0.0
            for(i in a.indices) { ab+=a[i]*b[i].toDouble(); bb+=b[i]*b[i].toDouble() }
            val residual=(bb-ab*ab/aa).coerceAtLeast(1e-20)
            return 10*log10(residual/bb.coerceAtLeast(1e-20))
        }
    }
}
