package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.synth.LooseCrateKits
import com.snipsnap.synth.LooseCrateKits.Kit
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The owner's velocity and round-robin gate, in software, for LOOSE CRATE: a grid through saving, reloading,
 * the app's own pad resolver and the MPC 3 exporter, read back from the files each step wrote.
 */
class LooseCrateKitTest {
    private fun pad(kit: Kit, role: Int): ArrangedPad {
        val grid = LooseCrateKits.velocities.map { v -> List(LooseCrateKits.TAKES) { t -> LooseCrateKits.render(kit, role, v, t) } }
        return ArrangedPad(grid.last().first(), LooseCrateKits.classOf(kit, role), velocityTakes = grid,
            muteGroup = LooseCrateKits.muteGroup(kit, role), source = mapOf("engine" to "LOOSE CRATE", "role" to role.toString()))
    }

    /** A multi-strike snare, a sub, a hat, a TERRA drum and the roll: one of each kind of pad, each with its whole 3 x 4 grid. */
    private fun probePads(): List<ArrangedPad> =
        listOf(pad(Kit.HUMMIN, 1), pad(Kit.HUMMIN, 4), pad(Kit.CLOTH, 2), pad(Kit.RITUAL, 7), pad(Kit.HAZE, LooseCrateKits.ROLL))

    /**
     * The expansion's new kinds of pad: BLUE's EMBER brush tap and its shaped stick ride, SPEED's sped sub (a resampled
     * strike, in the sub choke group), RAGA's tabla stroke (a TERRA drum varied per take) and its tabla roll.
     */
    private fun expansionPads(): List<ArrangedPad> =
        listOf(pad(Kit.BLUE, 1), pad(Kit.SPEED, 4), pad(Kit.BLUE, 4), pad(Kit.RAGA, 0), pad(Kit.RAGA, LooseCrateKits.ROLL))

    @Test fun `the grid survives saving reloading playback and MPC 3 export`() {
        for (pads in listOf(probePads(), expansionPads())) gridSurvives(pads)
    }

    private fun gridSurvives(pads: List<ArrangedPad>) {
        val dir = Files.createTempDirectory("loose-crate-grid").toFile()
        try {
            val kit = KitAssembler.assembleArranged("Loose Crate Probe", pads, dir, tempoBpm = 88f)
            val saved = KitStore.load(dir)
            assertEquals(kit, saved)
            TriadKitProof.verify(saved, dir, pads)
            val exported = Mpc3Exporter.exportTrack(saved, dir, File(dir, "export"))
            TriadKitProof.verifyTrack(exported.program, saved)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `playback picks the zone by velocity and walks the takes in order then repeats`() {
        playsInOrder(probePads(), sub = 1, hat = 2)
        playsInOrder(expansionPads(), sub = 1, hat = null)
    }

    private fun playsInOrder(pads: List<ArrangedPad>, sub: Int, hat: Int?) {
        val dir = Files.createTempDirectory("loose-crate-play").toFile()
        try {
            KitAssembler.assembleArranged("Loose Crate Probe", pads, dir)
            val saved = KitStore.load(dir)
            for (pad in saved.pads) {
                val wav = WavReader.read(File(dir, pad.sampleFile))
                fun start(velocity: Float, hit: Int) =
                    assertNotNull(PadHit.resolve(pad, velocity, hit) { wav.frameCount.toLong() }).startFrame
                val zones = assertNotNull(pad.chain?.zones)
                assertEquals(LooseCrateKits.velocities.size, zones.map { it.velStart }.toSet().size, "pad ${pad.slot} zones")
                for ((z, zone) in zones.withIndex()) {
                    val velocity = (zone.velStart + zone.velEnd) / 2f / 127f
                    val starts = List(LooseCrateKits.TAKES * 2) { start(velocity, it) }
                    assertEquals(LooseCrateKits.TAKES, starts.take(LooseCrateKits.TAKES).toSet().size, "pad ${pad.slot} zone $z")
                    assertEquals(starts.take(LooseCrateKits.TAKES), starts.drop(LooseCrateKits.TAKES), "pad ${pad.slot} zone $z repeat")
                }
                assertFalse(start(.1f, 0) == start(1f, 0), "pad ${pad.slot}: soft and hard play the same slice")
            }
            // The hum kicks' choke group survives the save (SPEED's sped sub too): a new sub stops the last one on the sampler.
            assertEquals(LooseCrateKits.SUB_GROUP, saved.pads[sub].muteGroup)
            if (hat != null) assertEquals(LooseCrateKits.HAT_GROUP, saved.pads[hat].muteGroup)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `the seven kits are on the shelf once each at their authored tempos`() {
        for (kit in Kit.entries) {
            val starter = assertNotNull(StarterKits.byId(kit.id), kit.id)
            assertEquals(kit.bpm.toFloat(), starter.tempoBpm)
            assertFalse(starter.seeded)
            assertEquals(1, StarterShelf.GROUPS.count { kit.id in it.ids }, kit.id)
            assertTrue(starter.displayName.startsWith("LOOSE CRATE "))
            assertEquals(DrumClass.LOOP, LooseCrateKits.classOf(kit, LooseCrateKits.GROOVE))
        }
    }
}
