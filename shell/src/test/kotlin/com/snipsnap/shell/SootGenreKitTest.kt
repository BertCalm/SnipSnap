package com.snipsnap.shell

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.synth.SootGenreKits
import com.snipsnap.synth.SootGenreKits.Genre
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * SOOT's four sibling kits through the owner's velocity and round-robin gate, in software: each kit's
 * hit, roll and four bars through saving, reloading, [PadHit.resolve] and the MPC 3 exporter
 * (`:shell:generateSootGenreKits` runs all sixteen pads of every kit).
 */
class SootGenreKitTest {
    @Test fun `each kit's grids survive saving reloading playback and MPC 3 export`() {
        for (g in Genre.entries) {
            val dir = Files.createTempDirectory("soot-${g.name.lowercase()}").toFile()
            try {
                val all = SootGenreKits.build(g)
                val pads = listOf(all[1], all[SootGenreKits.HITS], all[SootGenreKits.HITS + 1])
                val kit = KitAssembler.assembleArranged("Soot ${g.title}", pads, dir, tempoBpm = g.bpm.toFloat())
                val saved = KitStore.load(dir)
                assertEquals(kit, saved, g.title)
                TriadKitProof.verify(saved, dir, pads)
                val exported = Mpc3Exporter.exportTrack(saved, dir, File(dir, "export"))
                TriadKitProof.verifyTrack(exported.program, saved)
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun `the four kits are on the shelf once each at their own tempos`() {
        for (g in Genre.entries) {
            val starter = assertNotNull(StarterKits.byId(g.id), g.id)
            assertEquals(g.bpm.toFloat(), starter.tempoBpm)
            assertFalse(starter.seeded)
            assertEquals(1, StarterShelf.GROUPS.count { g.id in it.ids }, g.id)
            assertEquals("SOOT ", starter.displayName.take(5))
        }
    }
}
