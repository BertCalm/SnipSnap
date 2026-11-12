package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportOutcome
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.KitPreview
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.blocked
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.synth.BreakerKits
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Builds the BREAKER kit as the app's starter does, proves its velocity and round-robin grid through save,
 * reload, playback and MPC 3 export, and writes the listening pack. Every pad clip is cut from the exported
 * WAV through the app's own pad resolver ([PadHit.resolve]), and the groove is the app's own preview mixer
 * ([KitPreview]), so what the page plays is what a pad would play.
 *
 * Run: `./gradlew :shell:generateBreakerKit` (writes testkit/breaker-kit).
 */
object BreakerKitGenerator {
    private const val BEAT = 960L
    private const val BAR = 4 * BEAT

    /**
     * Five bars at 92 BPM: two on the hats, one on the ride with a tom run, the riser into the fifth bar's
     * crash. Velocities wander across all three zones, so the groove deals soft, middle and hard takes.
     */
    fun groove(): Mpc3Clip {
        val notes = mutableListOf<Mpc3Note>()
        fun hit(slot: Int, bar: Int, beat: Double, v: Float) { notes += Mpc3Note(Mpc3Note.noteFor(slot), bar * BAR + (beat * BEAT).toLong(), v) }
        for (bar in 0..2) {
            hit(1, bar, 0.0, 1f); hit(1, bar, 1.75, .7f); hit(1, bar, 2.5, .9f)
            hit(2, bar, 1.0, .95f); hit(2, bar, 2.25, .3f)
            // A push on the backbeat: the drip snare in bar 2, the crushed snare in bar 3.
            hit(when (bar) { 1 -> 6; 2 -> 12; else -> 2 }, bar, 3.0, .95f)
        }
        for (bar in 0..1) {
            // Closed hats on the eighths, the last one opened.
            for (e in 0..6) hit(3, bar, e * .5, if (e % 2 == 0) .8f else .45f)
            hit(4, bar, 3.5, .7f)
        }
        hit(7, 1, 2.75, .6f)
        for (e in 0..5) hit(13, 2, e * .5, if (e % 2 == 0) .65f else .4f)
        hit(8, 2, 1.5, .5f); hit(8, 2, 2.5, .8f)
        hit(11, 2, 3.0, .85f); hit(11, 2, 3.25, .7f); hit(10, 2, 3.5, .9f); hit(9, 2, 3.75, 1f)
        hit(14, 0, 0.0, .8f)
        // Bar 4: the kick on one, the riser ending on the fifth bar's one, where the crash and the crushed kick land.
        hit(1, 3, 0.0, 1f)
        val riserStart = 4 * BAR - (BreakerKits.BPM / 60.0 * 2.4 * BEAT).toLong()
        notes += Mpc3Note(Mpc3Note.noteFor(15), riserStart, .9f)
        hit(14, 4, 0.0, 1f); hit(5, 4, 0.0, 1f)
        return Mpc3Clip("Breaker Groove", 5, notes.sortedBy { it.timePulses })
    }

    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/breaker-kit").absoluteFile.apply { deleteRecursively(); mkdirs() }
        val work = File(root, "native")
        val began = System.nanoTime()
        val kit = requireNotNull(StarterKits.byId(BreakerKits.ID)).render("Breaker", work)
        val buildMs = (System.nanoTime() - began) / 1_000_000
        val saved = KitStore.load(work)
        check(saved == kit) { "the reloaded kit differs from the one saved" }

        // Velocity and round robin, measured on the saved grid.
        val grids = BreakerKitProof.measure(saved, work)
        for (g in grids) {
            check(g.closestTakes < BreakerKitProof.COPY_LIKENESS) { "${g.name}: two takes are copies" }
            check(g.leastZoneMoveDb > BreakerKitProof.LEVEL_ONLY_DB) { "${g.name}: a zone is only quieter" }
        }

        // Export: MPC 3 with the groove embedded, and the MPC 2 twin for comparison.
        val clip = groove()
        val export = Mpc3Exporter.exportTrack(saved, work, File(root, "MPC3"), overwrite = true, mpc2Twin = true, clip = clip)
        TriadKitProof.verifyTrack(export.program, saved)
        val data = export.samples.first().parentFile
        check(!ExportReadBack.verify(saved, ExportOutcome(ExportFormat.MPC3_TRACK, export.program, data, export.findings)).blocked())

        val proof = StringBuilder("slot,pad,zones,takes,closest_takes_db_apart,least_zone_move_db,saved_pcm,exported_pcm,playback_cycles,mpc3_track\n")
        val manifest = StringBuilder("{\n  \"bpm\": ${BreakerKits.BPM},\n  \"buildMs\": $buildMs,\n  \"pads\": [\n")
        for ((pad, g) in saved.pads.zip(grids)) {
            val mine = WavReader.read(File(work, pad.sampleFile))
            val exported = WavReader.read(File(data, pad.sampleFile))
            check(mine.frameCount == exported.frameCount && mine.samples.indices.all { abs(mine.samples[it] - exported.samples[it]) <= 3f / 8388608 }) {
                "${pad.displayName}: the exported PCM is not the saved PCM"
            }
            // Through the pad resolver on the exported WAV: each zone's takes in the order a pad deals them,
            // twice round, checked against the chain's own slices.
            val zones = requireNotNull(pad.chain?.zones)
            val strip = mutableListOf<FloatArray>()
            for ((z, zone) in zones.withIndex()) {
                val velocity = (zone.velStart + zone.velEnd) / 2f / 127f
                for (hit in 0 until zone.cycle * 2) {
                    val h = requireNotNull(PadHit.resolve(pad, velocity, hit) { exported.frameCount.toLong() })
                    val x = exported.samples.copyOfRange(h.startFrame.toInt(), h.endFrameExclusive.toInt())
                    check(x.contentEquals(BreakerKitProof.slice(exported, pad, zone.baseSlice + hit % zone.cycle))) { "${pad.displayName} zone $z hit $hit" }
                    if (hit < zone.cycle) strip += x
                }
            }
            val n = String.format(Locale.ROOT, "%02d", pad.slot)
            // The hard zone's first take: the pad's own sound.
            WavWriter.write(File(root, "pad-$n.wav"), Snip(BreakerKitProof.slice(exported, pad, zones.last().baseSlice), 1, 44100), WavWriter.BitDepth.PCM_24)
            // Soft to hard, each zone's takes in dealing order, an eighth of a second apart.
            val gap = 44100 / 8
            val total = strip.sumOf { it.size + gap }
            val out = FloatArray(total)
            var at = 0
            for (x in strip) { x.copyInto(out, at); at += x.size + gap }
            WavWriter.write(File(root, "touch-$n.wav"), Snip(out, 1, 44100), WavWriter.BitDepth.PCM_24)
            proof.append("${pad.slot},${pad.displayName},${g.zones},${g.takes},")
                .append(String.format(Locale.ROOT, "%.1f,%.1f", g.takesApartDb, g.leastZoneMoveDb))
                .append(",verified,verified,2,verified\n")
            manifest.append("    {\"slot\": ${pad.slot}, \"name\": \"${pad.displayName}\", \"class\": \"${pad.drumClass}\", \"level\": ${pad.level}, ")
                .append("\"zones\": ${g.zones}, \"takes\": ${g.takes}, ")
                .append(String.format(Locale.ROOT, "\"takesApartDb\": %.1f, \"zoneMoveDb\": %.1f}", g.takesApartDb, g.leastZoneMoveDb))
                .append(if (pad.slot == saved.pads.size) "\n" else ",\n")
        }
        manifest.append("  ]\n}\n")

        // The groove through the app's own preview mixer: velocity picks the zone, each hit deals the next
        // take, the pad's level sets the balance, and the open hat is choked by the closed one.
        val groove = KitPreview.render(saved, work, clip, BreakerKits.BPM)
        check(groove.samples.all { it.isFinite() })
        WavWriter.write(File(root, "groove.wav"), groove, WavWriter.BitDepth.PCM_24)

        File(root, "verification.csv").writeText(proof.toString())
        File(root, "manifest.json").writeText(manifest.toString())
        File(root, "README.txt").writeText(
            "BREAKER kit (renderer model 3), 16 pads at ${BreakerKits.BPM} BPM, built in $buildMs ms.\n" +
                "Each synth pad is 3 velocity zones x 3 re-struck takes, dealt by the shared robin from its saved recipe;\n" +
                "the tambourine is its 2 recorded dynamic layers x 2 recorded takes.\n" +
                "Verified here: saved PCM, regenerable recipes, playback zone selection and take cycling (PadHit.resolve,\n" +
                "KitPreview), and the MPC 3 track's velocity ranges, slice anchors, cycle lengths and exported PCM.\n" +
                "MPC 2 plays each zone's first take only and does not pass the round-robin export gate.\n" +
                "No phone or MPC has played this kit.\n")
        println("Verified BREAKER: 16 pads, ${saved.pads.sumOf { it.chain!!.boundaries.size }} slices, saved PCM + playback + MPC 3 track, built in $buildMs ms")
    }
}
