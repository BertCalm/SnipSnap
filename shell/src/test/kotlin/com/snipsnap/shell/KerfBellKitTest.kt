package com.snipsnap.shell

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.synth.KerfBellKit
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/** KERF BELLS through saving, reloading, the pad resolver and MPC 3 export: a strike, the roll and the riser. */
class KerfBellKitTest {
    @Test fun `a strike, the roll and the riser survive saving reloading playback and MPC 3 export`() {
        val dir = Files.createTempDirectory("kerf-bells").toFile()
        try {
            val pads = KerfBellKit.build(listOf(0, KerfBellKit.HITS, KerfBellKit.HITS + 1))
            val kit = KitAssembler.assembleArranged("Kerf Bells", pads, dir, tempoBpm = KerfBellKit.bpm().toFloat())
            val saved = KitStore.load(dir)
            assertEquals(kit, saved)
            TriadKitProof.verify(saved, dir, pads)
            TriadKitProof.verifyTrack(Mpc3Exporter.exportTrack(saved, dir, File(dir, "export")).program, saved)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `the kit is on the shelf once at its tempo`() {
        val starter = assertNotNull(StarterKits.byId(KerfBellKit.ID))
        assertEquals(KerfBellKit.bpm().toFloat(), starter.tempoBpm)
        assertFalse(starter.seeded)
        assertEquals(1, StarterShelf.GROUPS.count { KerfBellKit.ID in it.ids })
        assertEquals("KERF BELLS", starter.displayName)
    }
}
