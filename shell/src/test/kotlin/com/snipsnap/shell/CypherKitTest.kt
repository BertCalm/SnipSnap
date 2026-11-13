package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.synth.CypherKits
import com.snipsnap.synth.CypherKits.Kit
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The owner's velocity and round-robin gate, in software: a CYPHER grid through saving, reloading, the
 * app's own pad resolver, and the MPC 3 exporter, read back from the files each step wrote.
 */
class CypherKitTest {
    /** A sub, a printed hit, a dry hit and a phrase: one of each kind of pad the kits hold, with the whole 3 x 4 grid. */
    private fun probePads(): List<ArrangedPad> {
        fun pad(kit: Kit, role: Int, cls: DrumClass): ArrangedPad {
            val grid = CypherKits.velocities.map { v -> List(CypherKits.TAKES) { t -> CypherKits.render(kit, role, v, t) } }
            return ArrangedPad(grid.last().first(), cls, velocityTakes = grid, muteGroup = CypherKits.muteGroup(kit, role),
                source = mapOf("engine" to "CYPHER", "role" to role.toString()))
        }
        return listOf(pad(Kit.TRAP, 6, DrumClass.KICK), pad(Kit.DRILL, 1, DrumClass.SNARE), pad(Kit.TRAP, 2, DrumClass.HAT_CLOSED), pad(Kit.TRAP, 14, DrumClass.LOOP))
    }

    @Test fun `the grid survives saving reloading playback and MPC 3 export`() {
        val dir = Files.createTempDirectory("cypher-grid").toFile()
        try {
            val pads = probePads()
            val kit = KitAssembler.assembleArranged("Cypher Probe", pads, dir, tempoBpm = 140f)
            val saved = KitStore.load(dir)
            assertEquals(kit, saved)
            TriadKitProof.verify(saved, dir, pads)
            val exported = Mpc3Exporter.exportTrack(saved, dir, File(dir, "export"))
            TriadKitProof.verifyTrack(exported.program, saved)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `playback picks the zone by velocity and walks the takes in order then repeats`() {
        val dir = Files.createTempDirectory("cypher-play").toFile()
        try {
            val pads = probePads()
            KitAssembler.assembleArranged("Cypher Probe", pads, dir)
            val pad = KitStore.load(dir).pads.first()
            val wav = WavReader.read(File(dir, pad.sampleFile))
            fun start(velocity: Float, hit: Int) =
                assertNotNull(PadHit.resolve(pad, velocity, hit) { wav.frameCount.toLong() }).startFrame
            val zones = assertNotNull(pad.chain?.zones)
            for ((z, zone) in zones.withIndex()) {
                val velocity = (zone.velStart + zone.velEnd) / 2f / 127f
                val starts = List(CypherKits.TAKES * 2) { start(velocity, it) }
                assertEquals(CypherKits.TAKES, starts.take(CypherKits.TAKES).toSet().size, "zone $z")
                assertEquals(starts.take(CypherKits.TAKES), starts.drop(CypherKits.TAKES), "zone $z repeat")
                assertEquals(starts.take(CypherKits.TAKES).sorted(), starts.take(CypherKits.TAKES), "zone $z order")
            }
            assertFalse(start(.1f, 0) == start(1f, 0))
            // The sub's choke group survives the save: a new slide stops the last one on the sampler.
            assertEquals(CypherKits.SUB_GROUP, pad.muteGroup)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `the two kits are on the shelf once each at their authored tempos`() {
        for (kit in Kit.entries) {
            val starter = assertNotNull(StarterKits.byId(kit.id), kit.id)
            assertEquals(kit.bpm.toFloat(), starter.tempoBpm)
            assertFalse(starter.seeded)
            assertEquals(1, StarterShelf.GROUPS.count { kit.id in it.ids }, kit.id)
            assertTrue(starter.displayName.startsWith("CYPHER "))
        }
    }
}
