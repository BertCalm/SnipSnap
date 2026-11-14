package com.snipsnap.shell

import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.synth.EtchKits
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The owner's velocity and round-robin gate, in software, for ETCH's kit: a pad's 3 × 4 grid through saving,
 * reloading, the app's own pad resolver and the MPC 3 exporter, read back from the files each step wrote; the zones
 * as tone, not only level; and a loop pad's three velocity zones. Phone audio and an MPC are not exercised here.
 */
class EtchKitTest {
    /** SAW's C2 (the walk's first pad) and RUBBER's note: two voices' grids, built exactly as the kit builds them. */
    private val probes by lazy { EtchKits.pads().let { all -> listOf(all[0], all.first { it.name == "RUBBER NOTE" }) } }
    private val probePads by lazy { probes.map(EtchKits::arranged) }

    @Test fun `the grid survives saving reloading playback and MPC 3 export`() {
        val dir = Files.createTempDirectory("etch-grid").toFile()
        try {
            val kit = KitAssembler.assembleArranged("Etch Probe", probePads, dir, tempoBpm = EtchKits.BPM.toFloat())
            val saved = KitStore.load(dir)
            assertEquals(kit, saved)
            TriadKitProof.verify(saved, dir, probePads)
            val exported = Mpc3Exporter.exportTrack(saved, dir, File(dir, "export"))
            TriadKitProof.verifyTrack(exported.program, saved)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `playback picks the zone by velocity and walks the takes in order then repeats`() {
        val dir = Files.createTempDirectory("etch-play").toFile()
        try {
            KitAssembler.assembleArranged("Etch Probe", probePads, dir)
            for (pad in KitStore.load(dir).pads) {
                val wav = WavReader.read(File(dir, pad.sampleFile))
                fun start(velocity: Float, hit: Int) =
                    assertNotNull(PadHit.resolve(pad, velocity, hit) { wav.frameCount.toLong() }).startFrame
                val zones = assertNotNull(pad.chain?.zones)
                assertEquals(EtchKits.VELOCITIES.size, zones.size)
                for ((z, zone) in zones.withIndex()) {
                    val velocity = (zone.velStart + zone.velEnd) / 2f / 127f
                    val starts = List(EtchKits.TAKES * 2) { start(velocity, it) }
                    assertEquals(EtchKits.TAKES, starts.take(EtchKits.TAKES).toSet().size, "${pad.displayName} zone $z")
                    assertEquals(starts.take(EtchKits.TAKES), starts.drop(EtchKits.TAKES), "${pad.displayName} zone $z repeat")
                }
                assertNotEquals(start(.1f, 0), start(1f, 0), "${pad.displayName}: soft and hard hits share a zone")
            }
        } finally { dir.deleteRecursively() }
    }

    /** Level-free brightness of a take's first 150 ms: the accent's filter opening, not its loudness. */
    private fun attackCentroid(snip: Snip): Float {
        val n = minOf(snip.frameCount, (0.15f * snip.sampleRate).toInt())
        val head = snip.samples.copyOf(n)
        val peak = head.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9f)
        return FeatureExtractor.extract(Snip(FloatArray(n) { head[it] / peak }, 1, snip.sampleRate)).centroidHz
    }

    @Test fun `a harder zone is a brighter strike and every take is a different strike`() {
        for ((probe, arranged) in probes.zip(probePads)) {
            val grid = arranged.velocityTakes
            val centroids = grid.map { row -> row.map(::attackCentroid).average() }
            assertTrue(centroids.zipWithNext().all { (a, b) -> b > a }, "${probe.name}: zone brightness $centroids")
            for ((z, row) in grid.withIndex()) {
                for (i in row.indices) for (j in i + 1 until row.size)
                    assertFalse(row[i].samples.contentEquals(row[j].samples), "${probe.name} zone $z: takes $i and $j are one strike")
            }
        }
    }

    @Test fun `a loop pad keeps three velocity zones through saving and plays the zone the velocity picks`() {
        val loop = EtchKits.pads().first { it.loop }
        val dir = Files.createTempDirectory("etch-loop").toFile()
        try {
            KitAssembler.assembleArranged("Etch Loop", listOf(EtchKits.arranged(loop)), dir, tempoBpm = EtchKits.BPM.toFloat())
            val pad = KitStore.load(dir).pads.single()
            assertEquals(EtchKits.VELOCITIES.size, pad.velocityLayers.size)
            fun file(velocity: Float) = assertNotNull(PadHit.resolve(pad, velocity, 0) { 1L }).sampleFile
            assertEquals(EtchKits.VELOCITIES.size, listOf(.1f, .5f, 1f).map(::file).toSet().size)
            assertEquals(loop.preset.voice.name, pad.source["voice"])
        } finally { dir.deleteRecursively() }
    }

    @Test fun `the kit is on the shelf once at its tempo, under BASS`() {
        val starter = assertNotNull(StarterKits.byId(EtchKits.ID))
        assertEquals(EtchKits.BPM.toFloat(), starter.tempoBpm)
        assertFalse(starter.seeded)
        assertEquals(1, StarterShelf.GROUPS.count { EtchKits.ID in it.ids })
        assertTrue(EtchKits.ID in StarterShelf.GROUPS.first { it.heading == Copy.STARTER_HEADING_BASS }.ids)
        assertEquals(EtchKits.PADS, EtchKits.pads().size)
    }
}
