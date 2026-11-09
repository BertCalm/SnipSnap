package com.snipsnap.shell

import com.snipsnap.audio.*
import com.snipsnap.kit.*
import com.snipsnap.synth.TriadKits
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.*

class TriadKitTest {
    @Test fun `authored grid survives saving playback and MPC export`() {
        val dir = Files.createTempDirectory("triad-grid").toFile()
        try {
            val grid = TriadKits.velocities.map { v -> List(4) { t -> TriadKits.render(TriadKits.Genre.TECHNO, 6, v, t) } }
            val pad = ArrangedPad(grid.last().first(), DrumClass.PERC, velocityTakes = grid)
            val kit = KitAssembler.assembleArranged("Triad Probe", listOf(pad), dir)
            val saved = KitStore.load(dir)
            assertEquals(kit, saved)
            TriadKitProof.verify(saved, dir, listOf(pad))
            val exported = Mpc3Exporter.exportTrack(saved, dir, java.io.File(dir,"export"))
            TriadKitProof.verifyTrack(exported.program, saved)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `each role has repeatable distinct takes and softer touch`() {
        val g = TriadKits.Genre.TECHNO
        for (role in TriadKits.roles.indices) {
            val hard = TriadKits.render(g, role, 1f, 0).samples
            assertContentEquals(hard, TriadKits.render(g, role, 1f, 0).samples)
            val soft = TriadKits.render(g, role, .3f, 0).samples
            fun energy(x:FloatArray) = x.sumOf { it.toDouble()*it } / x.size
            assertTrue(energy(soft)<energy(hard), "role $role touch")
            for(t in 1..3) assertFalse(hard.contentEquals(TriadKits.render(g,role,1f,t).samples), "role $role take $t")
            for(x in listOf(hard,soft)) {
                assertTrue(x.all { it.isFinite() && abs(it)<1f }, "role $role bounded")
                assertTrue(abs(x.average())<.005, "role $role DC")
                assertTrue(abs(x.last())<.0001, "role $role tail")
            }
        }
    }

    @Test fun `genre starters are on shelf at their authored tempos`() {
        val ids = listOf("triad-techno", "triad-house", "triad-minimal")
        assertEquals(listOf(128f,118f,126f), ids.map { id -> StarterKits.ALL.single { it.id == id }.tempoBpm })
        for(role in 0..1) {
            val a = TriadKits.Genre.entries.map { TriadKits.render(it,role,1f,0).samples }
            assertFalse(a[0].contentEquals(a[1])); assertFalse(a[1].contentEquals(a[2]))
        }
    }
}
