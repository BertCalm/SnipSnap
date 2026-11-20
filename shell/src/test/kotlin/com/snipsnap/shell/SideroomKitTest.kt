package com.snipsnap.shell

import com.snipsnap.audio.*
import com.snipsnap.kit.*
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.SideroomKits
import java.io.File
import java.nio.file.Files
import kotlin.math.*
import kotlin.test.*

class SideroomKitTest {
    @Test fun `every kit grid survives native saving selection replay and both MPC exporters`() {
        val root=Files.createTempDirectory("sideroom-gates").toFile()
        try { for(g in SideroomKits.Kit.entries) {
            val pads=SideroomKits.build(g)
            val dir=File(root,g.slug)
            val starter=assertNotNull(StarterKits.byId(g.id))
            assertEquals(g.bpm.toFloat(),starter.tempoBpm)
            assertEquals(1,StarterShelf.GROUPS.count { g.id in it.ids })
            val kit=KitAssembler.assembleArranged("Sideroom ${g.title}",pads,dir,tempoBpm=g.bpm.toFloat())
            val saved=KitStore.load(dir);assertEquals(kit,saved)
            assertEquals(10,saved.pads.size)
            assertEquals(1,saved.pads[2].muteGroup);assertEquals(1,saved.pads[3].muteGroup)
            TriadKitProof.verify(saved,dir,pads)
            for((r,pad) in saved.pads.withIndex()) {
                val replay=SideroomKits.replay(pad.source)
                for(z in 0..2) for(t in 0..2) assertContentEquals(pads[r].velocityTakes[z][t].samples,replay[z][t].samples)
                val wav=WavReader.read(File(dir,pad.sampleFile));val chain=assertNotNull(pad.chain)
                for(zone in chain.zones!!) for(midi in listOf(max(1,zone.velStart),zone.velEnd)) for(t in 0..5) {
                    val h=assertNotNull(PadHit.resolve(pad,midi/127f,t){wav.frameCount.toLong()})
                    val slice=zone.baseSlice+t%3
                    assertEquals(chain.boundaries[slice],h.startFrame)
                    assertEquals(chain.boundaries.getOrNull(slice+1)?:wav.frameCount.toLong(),h.endFrameExclusive)
                }
                val energies=pads[r].velocityTakes.map { row -> row.map { s -> s.samples.sumOf { it.toDouble()*it } }.average() }
                assertTrue(energies[0]<energies[1] && energies[1]<energies[2],"${g.slug}/$r dynamics $energies")
                for(row in pads[r].velocityTakes) {
                    for(x in row) { assertTrue(x.samples.all { it.isFinite() && abs(it)<1f });assertEquals(0f,x.samples.last()) }
                    for(a in 0..2) for(b in a+1..2) assertTrue(distance(row[a].samples,row[b].samples)>.001,"copied takes ${g.slug}/$r")
                }
                assertTrue(distance(pads[r].velocityTakes[0][0].samples,pads[r].velocityTakes[2][0].samples)>.001,"gain-only velocity")
            }
            val exported=Mpc3Exporter.exportTrack(saved,dir,File(dir,"mpc3"))
            val data=File(exported.directory,Mpc3TrackWriter.trackDataDirName(saved.name))
            TriadKitProof.verify(saved,data,pads);TriadKitProof.verifyTrack(exported.program,saved)
            val mpc2=KitExporter.exportProgramFolder(saved,dir,File(dir,"mpc2"))
            val findings=ExportReadBack.verifyProgram(saved,mpc2.program,ExportFormat.PROGRAM_FOLDER)
            assertTrue(findings.none { it.severity==Severity.FAIL },findings.toString())
        } } finally { root.deleteRecursively() }
    }
    @Test fun `cached hits are defensive and invalid recipes are refused`() {
        val g=SideroomKits.Kit.OPEN_ROAD
        val a=SideroomKits.render(g,0,1f,0);val before=a.samples.copyOf();a.samples.fill(0f)
        assertContentEquals(before,SideroomKits.render(g,0,1f,0).samples)
        assertFailsWith<IllegalArgumentException>{SideroomKits.render(g,0,Float.NaN,0)}
        assertFailsWith<IllegalArgumentException>{SideroomKits.render(g,0,1f,3)}
        assertFailsWith<IllegalArgumentException>{SideroomKits.replay(SideroomKits.source(g,0)+("model" to "999"))}
        assertFailsWith<IllegalArgumentException>{SideroomKits.replay(SideroomKits.source(g,0)+("takes" to "1"))}
    }
    @Test fun `fill revision preserves approved roll and legacy replay`() {
        for(g in SideroomKits.Kit.entries) {
            for(v in SideroomKits.velocities) for(t in 0..2) {
                assertContentEquals(SideroomKits.render(g,8,v,t,1).samples,SideroomKits.render(g,8,v,t).samples)
            }
            val old=SideroomKits.replay(SideroomKits.source(g,9,1))
            val fresh=SideroomKits.replay(SideroomKits.source(g,9,2))
            assertEquals("fill",SideroomKits.source(g,9)["articulation"])
            assertEquals("riser",SideroomKits.source(g,9,1)["articulation"])
            for(z in 0..2) for(t in 0..2) {
                assertContentEquals(SideroomKits.render(g,9,SideroomKits.velocities[z],t,1).samples,old[z][t].samples)
                assertTrue(fresh[z][t].frameCount < old[z][t].frameCount,"fill must replace the long build")
            }
        }
    }
    private fun distance(a:FloatArray,b:FloatArray):Double {
        var aa=0.0;var bb=0.0;var ab=0.0
        for(i in 0 until max(a.size,b.size)) { val x=a.getOrElse(i){0f}.toDouble();val y=b.getOrElse(i){0f}.toDouble();aa+=x*x;bb+=y*y;ab+=x*y }
        return ((bb-ab*ab/aa.coerceAtLeast(1e-20))/bb.coerceAtLeast(1e-20)).coerceAtLeast(0.0)
    }
}
