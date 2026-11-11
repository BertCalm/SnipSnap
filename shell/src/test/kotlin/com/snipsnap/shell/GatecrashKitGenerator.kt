package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.GatecrashKits
import com.snipsnap.synth.GatecrashKits.Kit
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Builds the three GATECRASH kits, proves the grid through save, reload, playback and MPC 3 export, and
 * writes the listening pack. Every audition clip is read back from the exported WAV through the app's own
 * pad resolver ([PadHit.resolve]), so what the page plays is what a pad would play.
 */
object GatecrashKitGenerator {
    private const val RATE = 44100

    /** Pads that get a less-room and a more-room comparison: the main snare, a tom, and the fill. */
    private val TRIALS = setOf(1, 8, 14)

    /** One groove note: beat, pad slot (1..16), velocity. */
    private class Hit(val beat: Double, val slot: Int, val velocity: Float)

    private fun bar(vararg h: Hit) = h.toList()

    /** Two bars per kit, written for that kit's roles. The last half bar is always a run on the toms. */
    private fun groove(kit: Kit): List<Hit> = when (kit) {
        Kit.STONE_ROOM -> listOf(0.0, 4.0).flatMap { o -> bar(
            Hit(o + 0.0, 1, 1f), Hit(o + 1.0, 2, 1f), Hit(o + 2.0, 1, .9f), Hit(o + 2.5, 1, .7f), Hit(o + 3.0, 2, 1f),
        ) + List(8) { Hit(o + it * .5, 3, if (it % 2 == 0) .7f else .4f) } }.filterNot { it.beat >= 6.5 && it.slot in listOf(2, 3) } +
            bar(Hit(1.75, 6, .35f), Hit(3.75, 6, .4f), Hit(6.5, 7, .8f), Hit(6.75, 8, .85f), Hit(7.0, 9, .9f), Hit(7.25, 10, .92f), Hit(7.5, 11, .96f), Hit(7.75, 12, 1f))
        Kit.MACHINE_POP -> listOf(0.0, 4.0).flatMap { o -> bar(
            Hit(o + 0.0, 1, 1f), Hit(o + 1.0, 2, 1f), Hit(o + 1.5, 1, .7f), Hit(o + 2.0, 1, 1f), Hit(o + 3.0, 2, 1f), Hit(o + 3.0, 5, .8f),
            Hit(o + 2.5, 11, .6f),
        ) + List(16) { Hit(o + it * .25, 3, if (it % 4 == 0) .8f else if (it % 2 == 0) .55f else .3f) } }.filterNot { it.beat >= 7.0 && it.slot in listOf(2, 3, 5) } +
            bar(Hit(3.5, 4, .6f), Hit(7.0, 7, .85f), Hit(7.25, 8, .9f), Hit(7.5, 9, .95f), Hit(7.75, 10, 1f))
        Kit.ELECTRO_FUNK -> listOf(0.0, 4.0).flatMap { o -> bar(
            Hit(o + 0.0, 1, 1f), Hit(o + 0.75, 8, .8f), Hit(o + 1.0, 2, 1f), Hit(o + 2.5, 1, .9f), Hit(o + 3.0, 2, 1f), Hit(o + 3.0, 5, .85f),
            Hit(o + 1.5, 7, .6f), Hit(o + 2.25, 7, .45f), Hit(o + 3.75, 6, .5f),
        ) + List(16) { Hit(o + it * .25, if (it == 14) 4 else 3, if (it % 4 == 2) .75f else if (it % 2 == 0) .5f else .3f) } }.filterNot { it.beat >= 7.25 && it.slot == 3 } +
            bar(Hit(1.75, 12, .6f), Hit(7.25, 11, .85f), Hit(7.5, 10, .92f), Hit(7.75, 9, 1f))
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
        val root = File(args.firstOrNull() ?: "testkit/gatecrash-kits").absoluteFile.apply { deleteRecursively(); mkdirs() }
        val proof = StringBuilder("kit,pad,name,velocity_zones,takes_per_zone,saved_pcm,playback_cycles,mpc3_track\n")
        val metrics = StringBuilder("kit,pad,name,class,space,seconds,attacks,room_early_db,room_late_db,room_after_db,room_peak_db,build_ms\n")
        val manifest = StringBuilder("{\n  \"kitVersion\": ${GatecrashKits.VERSION},\n  \"kits\": [\n")
        for ((k, g) in Kit.entries.withIndex()) {
            val folder = File(root, g.id).apply { mkdirs() }
            val staging = File(folder, "native")
            val began = System.nanoTime()
            val pads = GatecrashKits.build(g)
            val buildMs = (System.nanoTime() - began) / 1_000_000
            val kit = KitAssembler.assembleArranged("Gatecrash ${g.title}", pads, staging, tempoBpm = g.bpm.toFloat())
            val exported = Mpc3Exporter.exportTrack(kit, staging, File(folder, "mpc"), overwrite = true)
            val data = File(folder, "mpc/${Mpc3TrackWriter.trackDataDirName(kit.name)}")
            val saved = KitStore.load(staging)
            check(saved == kit) { "${g.title}: the reloaded kit differs from the one saved" }
            // Saved PCM, then the exported PCM, each against the renders through two playback cycles a zone.
            TriadKitProof.verify(saved, staging, pads)
            TriadKitProof.verify(saved, data, pads)
            // The exported track itself: velocity ranges, base slices, cycle lengths, frame windows.
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
                val space = GatecrashKits.spaceOf(g, role)
                if (space != null) write("dry-$n.wav", GatecrashKits.dry(g, role, 1f, 0).samples)
                // Not part of the kit: the same strike with 6 dB less and 6 dB more room, for the owner to steer by.
                val trial = space != null && role in TRIALS
                if (trial) {
                    write("less-$n.wav", GatecrashKits.roomTrial(g, role, -6f).samples)
                    write("more-$n.wav", GatecrashKits.roomTrial(g, role, 6f).samples)
                    // The same room with round 2's chop and with twice the authored fade.
                    write("sharp-$n.wav", GatecrashKits.roomTrial(g, role, 0f, closeMs = 15f).samples)
                    write("long-$n.wav", GatecrashKits.roomTrial(g, role, 0f, closeMs = space!!.closeMs * 2f).samples)
                    // The same amount of room an octave darker (about where round 3 was) and 1.7 times brighter.
                    write("dark-$n.wav", GatecrashKits.roomTrial(g, role, 0f, toneScale = .55f).samples)
                    write("bright-$n.wav", GatecrashKits.roomTrial(g, role, 0f, toneScale = 1.7f).samples)
                }
                // Soft, medium, hard: four takes each, in the order the pad deals them.
                val clips = GatecrashKits.velocities.flatMap { v -> List(GatecrashKits.TAKES) { t -> play(pad.slot, v, t).first } }
                val stride = clips.maxOf { it.size } + RATE / 8
                val strip = FloatArray(stride * clips.size)
                clips.forEachIndexed { i, x -> x.copyInto(strip, i * stride) }
                if (role < GatecrashKits.HITS) write("touch-$n.wav", strip)
                val m = GatecrashKits.gesture(g, role)
                fun f(v: Double?) = if (v == null) "" else String.format(Locale.ROOT, "%.1f", v)
                proof.append("${g.id},${pad.slot},${pad.displayName},3,4,verified,2,verified\n")
                metrics.append("${g.id},${pad.slot},${pad.displayName},${pad.drumClass},${space?.mode?.name ?: "DRY"},")
                    .append(String.format(Locale.ROOT, "%.3f", wet.size / RATE.toDouble()))
                    .append(",${m?.attacks ?: ""},${f(m?.earlyDb)},${f(m?.lateDb)},${f(m?.afterDb)},${f(m?.roomPeakDb)},$buildMs\n")
                manifest.append("      {\"slot\": ${pad.slot}, \"name\": \"${pad.displayName}\", \"class\": \"${pad.drumClass}\", ")
                    .append("\"space\": \"${space?.mode?.name ?: "DRY"}\", \"seconds\": ${String.format(Locale.ROOT, "%.3f", wet.size / RATE.toDouble())}, ")
                    .append("\"dry\": ${space != null}, \"touch\": ${role < GatecrashKits.HITS}, \"trial\": $trial, \"roomDb\": ${space?.roomDb ?: "null"}, ")
                    .append("\"holdMs\": ${space?.holdMs?.roundToInt() ?: 0}, \"closeMs\": ${space?.closeMs?.roundToInt() ?: 0}, ")
                    .append("\"roomPeakDb\": ${f(m?.roomPeakDb).ifEmpty { "null" }}, \"earlyDb\": ${f(m?.earlyDb).ifEmpty { "null" }}, \"lateDb\": ${f(m?.lateDb).ifEmpty { "null" }}, ")
                    .append("\"wet\": ${envelope(wet, wet.size)}, \"dryEnv\": ${if (space != null) envelope(GatecrashKits.dry(g, role, 1f, 0).samples, wet.size) else "null"}}")
                    .append(if (pad.slot == saved.pads.size) "\n" else ",\n")
            }
            manifest.append("    ]}").append(if (k == Kit.entries.lastIndex) "\n" else ",\n")

            // The groove, dealt the way the app deals it: velocity picks the zone, each hit takes the next
            // take, the pad's level and the velocity set the gain, and a hat cuts the hat before it.
            val beat = RATE * 60.0 / g.bpm
            val events = groove(g).sortedBy { it.beat }
            val out = FloatArray((beat * 8).roundToInt() + RATE)
            val counts = IntArray(17)
            for ((i, e) in events.withIndex()) {
                val (x, gain) = play(e.slot, e.velocity, counts[e.slot]++, e.velocity)
                val start = (e.beat * beat).roundToInt()
                val hat = e.slot in 3..4
                val end = if (!hat) out.size else events.drop(i + 1).firstOrNull { it.slot in 3..4 && it.beat > e.beat }
                    ?.let { (it.beat * beat).roundToInt() } ?: out.size
                val n = min(x.size, min(end - start, out.size - start))
                for (j in 0 until n) out[start + j] += x[j] * gain * (if (hat) ((n - 1 - j) / 132f).coerceIn(0f, 1f) else 1f)
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
