package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.SootKits
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Builds the SOOT kit, proves its grids through save, reload, playback and MPC 3 export (GATECRASH's
 * proof), and writes the round-2 listening pack. Every pad clip is read back from the exported WAV
 * through the app's own pad resolver ([PadHit.resolve]), so the page plays what a pad would play.
 */
object SootKitGenerator {
    private const val RATE = 44100

    /** Two bars from the pads, the round-0 groove's first two, dealt the way the app deals them. */
    private class Hit(val step: Int, val slot: Int, val velocity: Float)

    private fun groove(): List<Hit> = buildList {
        val kicks = listOf(listOf(0, 6, 10), listOf(0, 3, 10, 11))
        for (bar in 0..1) {
            val b0 = bar * 16
            for (s in kicks[bar]) add(Hit(b0 + s, 1, if (s == 0) 1f else .85f))
            for (s in listOf(4, 12)) add(Hit(b0 + s, 2, 1f))
            for (s in 0 until 16 step 2) add(if (bar == 1 && s == 14) Hit(b0 + s, 4, .8f) else Hit(b0 + s, 3, if (s % 4 == 0) .85f else .55f))
        }
        add(Hit(15 + 16, 2, .3f)); add(Hit(11, 7, .7f)); add(Hit(16 + 13, 8, .6f))
    }.sortedBy { it.step }

    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/soot-kit").absoluteFile.apply { deleteRecursively(); mkdirs() }
        val staging = File(root, "native")
        val began = System.nanoTime()
        val pads = SootKits.build()
        val buildMs = (System.nanoTime() - began) / 1_000_000
        val kit = KitAssembler.assembleArranged("Soot", pads, staging, tempoBpm = SootKits.BPM.toFloat())
        val exported = Mpc3Exporter.exportTrack(kit, staging, File(root, "mpc"), overwrite = true)
        val data = File(root, "mpc/${Mpc3TrackWriter.trackDataDirName(kit.name)}")
        val saved = KitStore.load(staging)
        check(saved == kit) { "the reloaded kit differs from the one saved" }
        TriadKitProof.verify(saved, staging, pads)
        TriadKitProof.verify(saved, data, pads)
        TriadKitProof.verifyTrack(exported.program, saved)

        val waves = saved.pads.associate { it.slot to WavReader.read(File(data, it.sampleFile)) }
        fun play(slot: Int, velocity: Float, index: Int, gainVelocity: Float = 1f): Pair<FloatArray, Float> {
            val pad = saved.pads.single { it.slot == slot }
            val wav = waves.getValue(slot)
            val h = requireNotNull(PadHit.resolve(pad, velocity, index, gainVelocity) { wav.frameCount.toLong() })
            return wav.samples.copyOfRange(h.startFrame.toInt(), h.endFrameExclusive.toInt()) to h.gainLeft
        }
        fun write(name: String, x: FloatArray) = WavWriter.write(File(root, "listen/$name"), Snip(x, 1, RATE), WavWriter.BitDepth.PCM_16)

        val proof = StringBuilder("pad,name,class,velocity_zones,takes_per_zone,saved_pcm,exported_pcm,playback_cycles,mpc3_track,seconds\n")
        val manifest = StringBuilder("{\n \"kitVersion\": ${SootKits.VERSION}, \"bpm\": ${SootKits.BPM}, \"build_ms\": $buildMs,\n \"clips\": [\n")
        val clips = mutableListOf<String>()
        fun clip(section: String, id: String, label: String, x: FloatArray) {
            write("$section/$id.wav", x)
            clips += "  {\"section\": \"$section\", \"id\": \"$id\", \"label\": \"$label\", \"file\": \"listen/$section/$id.wav\", \"seconds\": ${String.format(Locale.ROOT, "%.3f", x.size / RATE.toDouble())}}"
        }
        for (pad in saved.pads) {
            val n = String.format(Locale.ROOT, "%02d", pad.slot)
            val (hard, _) = play(pad.slot, 1f, 0)
            clip("pads", "a$n", "A$n ${pad.displayName}", hard)
            // Soft, medium, hard: four takes each, in the order the pad deals them.
            val strikes = SootKits.velocities.flatMap { v -> List(SootKits.TAKES) { t -> play(pad.slot, v, t).first } }
            if (pad.slot <= SootKits.HITS) {
                val stride = strikes.maxOf { it.size } + RATE / 8
                val strip = FloatArray(stride * strikes.size)
                strikes.forEachIndexed { i, x -> x.copyInto(strip, i * stride) }
                clip("touch", "a$n", "A$n ${pad.displayName}: soft, medium, hard, four takes each", strip)
            }
            proof.append("${pad.slot},${pad.displayName},${pad.drumClass},3,4,verified,verified,2,verified,")
                .append(String.format(Locale.ROOT, "%.3f", hard.size / RATE.toDouble())).append('\n')
        }
        // The roll and the four bars at each zone, first take, as the pad plays them.
        for ((z, v) in SootKits.velocities.withIndex()) {
            clip("roll", "z$z", "A15 Coil Roll at ${(v * 100).roundToInt()}%", play(SootKits.HITS + 1, v, 0).first)
            clip("phrase", "z$z", "A16 Four Bars at ${(v * 100).roundToInt()}%", play(SootKits.PADS, v, 0).first)
        }
        // Not part of the kit: the four bars with round 0's heavy dictaphone, to steer by.
        clip("phrase", "grime90", "A16 Four Bars with the dictaphone at .9 (not in the kit)", SootKits.phrase(1f, 0, grime = .9).samples)
        // The four bars played around twice, to hear the loop point.
        val bars = play(SootKits.PADS, 1f, 0).first
        clip("phrase", "looped", "A16 Four Bars, looped twice", bars + bars)
        // Two bars from the single-hit pads, dealt as the app deals them: no shared coil, for contrast.
        val step = RATE * 60.0 / SootKits.BPM / 4
        val out = FloatArray((step * 32).roundToInt() + RATE)
        val counts = IntArray(17)
        val events = groove()
        for ((i, e) in events.withIndex()) {
            val (x, gain) = play(e.slot, e.velocity, counts[e.slot]++, e.velocity)
            var t = e.step * step
            if (e.step % 2 == 1) t += .32 * step
            if (e.slot in 3..4) t += .014 * RATE
            val start = t.roundToInt()
            val hat = e.slot in 3..4
            val end = if (!hat) out.size else events.drop(i + 1).firstOrNull { it.slot in 3..4 && it.step > e.step }
                ?.let { (it.step * step + (if (it.step % 2 == 1) .32 * step else 0.0) + .014 * RATE).roundToInt() } ?: out.size
            val n = min(x.size, min(end - start, out.size - start))
            for (j in 0 until n) out[start + j] += x[j] * gain * (if (hat) ((n - 1 - j) / 132f).coerceIn(0f, 1f) else 1f)
        }
        var peak = 0f
        for (v in out) peak = max(peak, abs(v))
        if (peak > .95f) for (i in out.indices) out[i] *= .95f / peak
        clip("groove", "pads", "Two bars from the single pads (no shared coil)", out)
        manifest.append(clips.joinToString(",\n")).append("\n ]\n}\n")
        File(root, "manifest.json").writeText(manifest.toString())
        File(root, "verification.csv").writeText(proof.toString())
        println("Verified SOOT: 16 pads, 192 slices, saved PCM + exported PCM + playback + MPC 3 track; kit built in $buildMs ms")
    }
}
