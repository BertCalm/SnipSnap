package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.CypherKits
import com.snipsnap.synth.CypherKits.Kit
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Builds the CYPHER kits, proves the grid through save, reload, playback and MPC 3 export, and writes the
 * listening pack. Every audition clip is read back from the exported WAV through the app's own pad resolver
 * ([PadHit.resolve]), so what the page plays is what a pad would play.
 */
object CypherKitGenerator {
    private const val RATE = 44100

    /** The DRIVE settings every sub pad is also played at, so the owner can hear the phone-band trade. */
    private val DRIVE_TRIALS = listOf(0f, .7f, 1f)

    /** One groove note: beat, pad slot (1..16), velocity. */
    private class Hit(val beat: Double, val slot: Int, val velocity: Float)

    private fun bar(vararg h: Hit) = h.toList()

    /** Two bars per kit, written for that kit's roles: half-time, the sub line under the kick, the hats on their grid. */
    private fun groove(kit: Kit): List<Hit> = when (kit) {
        Kit.TRAP -> listOf(0.0, 4.0).flatMap { o -> bar(
            Hit(o + 0.0, 1, 1f), Hit(o + 2.75, 1, .85f), Hit(o + 0.0, 5, 1f), Hit(o + 1.5, 7, .95f), Hit(o + 2.0, 2, 1f), Hit(o + 2.0, 9, .8f),
            Hit(o + 3.75, 4, .7f),
        ) + List(8) { Hit(o + it * .5, 3, if (it % 2 == 0) .75f else .5f) } + List(6) { Hit(o + 3.5 + it / 12.0, 3, .6f + .06f * it) } }
            .filterNot { it.beat >= 7.5 && it.slot == 3 && it.beat % .5 != 0.0 } +
            bar(Hit(4.0, 6, 1f), Hit(6.5, 8, .9f), Hit(5.5, 11, .7f), Hit(7.75, 12, .8f), Hit(7.5, 13, .5f), Hit(7.75, 14, .5f))
        Kit.DRILL -> listOf(0.0, 4.0).flatMap { o -> bar(
            Hit(o + 0.0, 1, 1f), Hit(o + 1.75, 1, .9f), Hit(o + 0.0, 5, 1f), Hit(o + 1.5, 6, .95f), Hit(o + 2.0, 8, .9f), Hit(o + 3.5, 7, .9f),
            Hit(o + 1.0, 2, 1f), Hit(o + 2.5, 2, 1f), Hit(o + 1 - 1.0 / 3, 9, .55f), Hit(o + 1 - 1.0 / 6, 9, .7f), Hit(o + 2.5 - 1.0 / 3, 9, .55f), Hit(o + 2.5 - 1.0 / 6, 9, .7f),
        ) + List(8) { Hit(o + it * .5, 3, if (it % 2 == 0) .7f else .45f) } } +
            bar(Hit(3.0, 11, .8f), Hit(7.0, 12, .85f), Hit(7.5, 10, .6f), Hit(6.75, 14, .5f))
    }

    /** The clip's peak level in 96 steps across [span] frames, for the page's envelope drawing. */
    private fun envelope(x: FloatArray, span: Int): String {
        val steps = 96
        return (0 until steps).joinToString(",", "[", "]") { k ->
            var peak = 0f
            for (i in k * span / steps until min((k + 1) * span / steps, x.size)) peak = max(peak, abs(x[i]))
            String.format(Locale.ROOT, "%.2f", peak)
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/cypher-kits").absoluteFile.apply { deleteRecursively(); mkdirs() }
        val proof = StringBuilder("kit,pad,name,velocity_zones,takes_per_zone,saved_pcm,playback_cycles,mpc3_track\n")
        val metrics = StringBuilder("kit,pad,name,class,print,choke,seconds,build_ms\n")
        val manifest = StringBuilder("{\n  \"kitVersion\": ${CypherKits.VERSION},\n  \"kits\": [\n")
        for ((k, g) in Kit.entries.withIndex()) {
            val folder = File(root, g.id).apply { mkdirs() }
            val staging = File(folder, "native")
            val began = System.nanoTime()
            val pads = CypherKits.build(g)
            val buildMs = (System.nanoTime() - began) / 1_000_000
            val kit = KitAssembler.assembleArranged("Cypher ${g.title}", pads, staging, tempoBpm = g.bpm.toFloat())
            val exported = Mpc3Exporter.exportTrack(kit, staging, File(folder, "mpc"), overwrite = true)
            val data = File(folder, "mpc/${Mpc3TrackWriter.trackDataDirName(kit.name)}")
            val saved = KitStore.load(staging)
            check(saved == kit) { "${g.title}: the reloaded kit differs from the one saved" }
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
            fun write(name: String, x: FloatArray) = WavWriter.write(File(folder, name), Snip(x, 1, RATE))

            manifest.append("    {\"id\": \"${g.id}\", \"title\": \"${g.title}\", \"bpm\": ${g.bpm}, \"pads\": [\n")
            for (pad in saved.pads) {
                val role = pad.slot - 1
                val n = String.format(Locale.ROOT, "%02d", pad.slot)
                val (wet, _) = play(pad.slot, 1f, 0)
                write("pad-$n.wav", wet)
                val space = CypherKits.spaceOf(g, role)
                if (space != null) write("dry-$n.wav", CypherKits.dry(g, role, 1f, 0).samples)
                val drive = CypherKits.subDrive(g, role)
                if (drive != null) for (d in DRIVE_TRIALS) write("drive${(d * 100).roundToInt()}-$n.wav", CypherKits.subTrial(g, role, d).samples)
                val clips = CypherKits.velocities.flatMap { v -> List(CypherKits.TAKES) { t -> play(pad.slot, v, t).first } }
                val stride = clips.maxOf { it.size } + RATE / 8
                val strip = FloatArray(stride * clips.size)
                clips.forEachIndexed { i, x -> x.copyInto(strip, i * stride) }
                if (role < CypherKits.HITS) write("touch-$n.wav", strip)
                val print = space?.mode?.name ?: "DRY"
                proof.append("${g.id},${pad.slot},${pad.displayName},3,4,verified,2,verified\n")
                metrics.append("${g.id},${pad.slot},${pad.displayName},${pad.drumClass},$print,${pad.muteGroup},")
                    .append(String.format(Locale.ROOT, "%.3f", wet.size / RATE.toDouble())).append(",$buildMs\n")
                manifest.append("      {\"slot\": ${pad.slot}, \"name\": \"${pad.displayName}\", \"class\": \"${pad.drumClass}\", ")
                    .append("\"space\": \"$print\", \"choke\": ${pad.muteGroup}, \"seconds\": ${String.format(Locale.ROOT, "%.3f", wet.size / RATE.toDouble())}, ")
                    .append("\"dry\": ${space != null}, \"touch\": ${role < CypherKits.HITS}, \"sub\": ${drive != null}, \"drive\": ${drive ?: "null"}, ")
                    .append("\"roomDb\": ${space?.roomDb ?: "null"}, \"holdMs\": ${space?.holdMs?.roundToInt() ?: 0}, \"closeMs\": ${space?.closeMs?.roundToInt() ?: 0}, ")
                    .append("\"wet\": ${envelope(wet, wet.size)}, \"dryEnv\": ${if (space != null) envelope(CypherKits.dry(g, role, 1f, 0).samples, wet.size) else "null"}}")
                    .append(if (pad.slot == saved.pads.size) "\n" else ",\n")
            }
            manifest.append("    ]}").append(if (k == Kit.entries.lastIndex) "\n" else ",\n")

            // The groove, dealt the way the app deals it: velocity picks the zone, each hit takes the next
            // take, the pad's level and the velocity set the gain, and a pad in a choke group cuts the last
            // pad of that group (hats cut hats, subs cut subs).
            val beat = RATE * 60.0 / g.bpm
            val events = groove(g).sortedBy { it.beat }
            val out = FloatArray((beat * 8).roundToInt() + RATE * 2)
            val counts = IntArray(17)
            val groups = saved.pads.associate { it.slot to it.muteGroup }
            for ((i, e) in events.withIndex()) {
                val (x, gain) = play(e.slot, e.velocity, counts[e.slot]++, e.velocity)
                val start = (e.beat * beat).roundToInt()
                val group = groups.getValue(e.slot)
                val end = if (group == 0) out.size else events.drop(i + 1).firstOrNull { groups.getValue(it.slot) == group && it.beat > e.beat }
                    ?.let { (it.beat * beat).roundToInt() } ?: out.size
                val n = min(x.size, min(end - start, out.size - start))
                val fade = if (group == 0) 1 else (RATE * .005f).toInt()
                for (j in 0 until n) out[start + j] += x[j] * gain * (if (group != 0) ((n - 1 - j) / fade.toFloat()).coerceIn(0f, 1f) else 1f)
            }
            var peak = 0f
            for (v in out) peak = max(peak, abs(v))
            check(out.all { it.isFinite() })
            val trim = if (peak > .95f) .95f / peak else 1f
            for (i in out.indices) out[i] *= trim
            write("groove.wav", out)
            File(folder, "kit-source.json").writeText(File(staging, KitStore.FILE_NAME).readText())
            println("Verified ${g.title}: 16 pads, 192 slices, saved PCM + playback + MPC 3 track, built in $buildMs ms, groove bus trim $trim")
        }
        manifest.append("  ]\n}\n")
        File(root, "verification.csv").writeText(proof.toString())
        File(root, "metrics.csv").writeText(metrics.toString())
        File(root, "manifest.json").writeText(manifest.toString())
    }
}
