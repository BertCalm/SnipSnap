package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.MirrorballKits
import com.snipsnap.synth.MirrorballKits.Kit
import com.snipsnap.synth.MirrorballKits.Trial
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Builds the three MIRRORBALL kits, proves the velocity and round-robin grid through save, reload, playback and
 * MPC 3 export, and writes the listening pack. Every pad clip is read back from the exported WAV through the
 * app's own pad resolver ([PadHit.resolve]), so what the page plays is what a pad would play. The comparison
 * clips (dry, a darker speaker, the rack's order, ...) are not part of any kit and are rendered directly.
 */
object MirrorballKitGenerator {
    private const val RATE = 44100

    /** Pads that get the full set of comparisons: the kick, the main snare, and the tambourine, a conga or a synth tom. */
    private fun trialRoles(kit: Kit): List<Int> = when (kit) {
        Kit.PHILLY -> listOf(0, 1, 4)
        Kit.LOFT -> listOf(0, 1, 6)
        Kit.MUNICH -> listOf(0, 1, 5)
    }

    /** One groove note: beat, pad slot (1..16), velocity. */
    private class Hit(val beat: Double, val slot: Int, val velocity: Float)

    /** Hats on the eighths: closed on the beat, open on the "and" (the disco "tss"). */
    private fun hats(o: Double, closed: Float = .55f, open: Float = .8f) =
        List(8) { if (it % 2 == 0) Hit(o + it * .5, 3, closed) else Hit(o + it * .5, 4, open) }

    /** Two bars per kit, written for that kit's roles. Bar two ends in a fill on the kit's own drums. */
    private fun groove(kit: Kit): List<Hit> = when (kit) {
        Kit.PHILLY -> listOf(0.0, 4.0).flatMap { o ->
            List(4) { Hit(o + it, 1, 1f) } + listOf(Hit(o + 1, 2, 1f), Hit(o + 3, 2, 1f)) + hats(o) +
                List(16) { Hit(o + it * .25, 5, if (it % 4 == 2) .75f else .35f) } +
                listOf(Hit(o + 1.75, 14, .35f), Hit(o + 2.75, 14, .3f), Hit(o + 0.5, 7, .7f), Hit(o + 1.5, 8, .8f), Hit(o + 2.5, 7, .6f), Hit(o + 2.75, 7, .5f))
        }.filterNot { it.beat >= 7.0 && it.slot in listOf(2, 3, 4, 5, 7, 8, 14) } +
            listOf(Hit(7.0, 9, .9f), Hit(7.25, 9, .8f), Hit(7.5, 10, .95f), Hit(7.75, 10, 1f))
        Kit.LOFT -> listOf(0.0, 4.0).flatMap { o ->
            List(4) { Hit(o + it, 1, 1f) } + listOf(Hit(o + 1, 2, 1f), Hit(o + 3, 2, 1f), Hit(o + 3, 5, .8f)) + hats(o, .5f, .75f) +
                listOf(Hit(o + 0.5, 11, .6f), Hit(o + 1.5, 11, .6f), Hit(o + 2.5, 11, .6f), Hit(o + 3.5, 11, .7f)) +
                listOf(Hit(o + 0.75, 7, .6f), Hit(o + 1.0, 6, .8f), Hit(o + 1.5, 7, .7f), Hit(o + 2.5, 8, .9f), Hit(o + 3.0, 8, .85f), Hit(o + 3.5, 14, .6f)) +
                List(8) { Hit(o + it * .5 + .25, 13, .3f) }
        }.filterNot { it.beat >= 7.0 && it.slot in listOf(2, 3, 4, 5, 6, 7, 8, 11, 13, 14) } +
            listOf(Hit(7.0, 9, .85f), Hit(7.25, 10, .8f), Hit(7.5, 9, .9f), Hit(7.75, 10, 1f))
        Kit.MUNICH -> listOf(0.0, 4.0).flatMap { o ->
            List(4) { Hit(o + it, 1, 1f) } + listOf(Hit(o + 1, 2, 1f), Hit(o + 3, 2, 1f), Hit(o + 3, 5, .75f)) +
                List(16) { Hit(o + it * .25, if (it % 4 == 2) 4 else 3, if (it % 4 == 2) .75f else if (it % 2 == 0) .55f else .3f) } +
                listOf(Hit(o + 0.0, 10, .7f), Hit(o + 0.75, 10, .5f), Hit(o + 1.5, 10, .6f), Hit(o + 2.25, 10, .5f))
        }.filterNot { it.beat >= 7.0 && it.slot in listOf(2, 3, 4, 5, 10) } +
            listOf(Hit(7.0, 6, .85f), Hit(7.25, 7, .9f), Hit(7.5, 8, .95f), Hit(7.75, 9, 1f))
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

    /** Spectral brightness, a share of energy above 2 kHz by a one-pole split, as the tests measure it. */
    private fun brightness(x: FloatArray): Double {
        // A one-pole low-pass at 2 kHz (Dsp's own is internal to :synth): what it lets through is the low part.
        val a = 1.0 - kotlin.math.exp(-2.0 * Math.PI * 2_000.0 / RATE)
        var lp = 0.0
        var high = 0.0
        var all = 0.0
        for (v in x) { lp += a * (v - lp); val h = v - lp; high += h * h; all += v.toDouble() * v }
        return high / max(all, 1e-18)
    }

    private fun mix(events: List<Hit>, bpm: Int, play: (Hit, Int) -> Pair<FloatArray, Float>): FloatArray {
        val beat = RATE * 60.0 / bpm
        val sorted = events.sortedBy { it.beat }
        val out = FloatArray((beat * 8).roundToInt() + (1.5 * RATE).toInt())
        val counts = IntArray(17)
        for ((i, e) in sorted.withIndex()) {
            val (x, gain) = play(e, counts[e.slot]++)
            val start = (e.beat * beat).roundToInt()
            // Hats choke each other (mute group 1), as the app and the MPC do.
            val hat = e.slot in 3..4
            val end = if (!hat) out.size else sorted.drop(i + 1).firstOrNull { it.slot in 3..4 && it.beat > e.beat }
                ?.let { (it.beat * beat).roundToInt() } ?: out.size
            val n = min(x.size, min(end - start, out.size - start))
            for (j in 0 until n) out[start + j] += x[j] * gain * (if (hat) ((n - 1 - j) / 132f).coerceIn(0f, 1f) else 1f)
        }
        check(out.all { it.isFinite() })
        return out
    }

    private fun trim(x: FloatArray, peakTo: Float = .95f): Float {
        var peak = 0f
        for (v in x) peak = max(peak, abs(v))
        val k = if (peak > peakTo) peakTo / peak else 1f
        for (i in x.indices) x[i] *= k
        return k
    }

    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/mirrorball-kits").absoluteFile.apply { deleteRecursively(); mkdirs() }
        val proof = StringBuilder("kit,pad,name,velocity_zones,takes_per_zone,saved_pcm,playback_cycles,mpc3_track\n")
        val metrics = StringBuilder("kit,pad,name,class,heads,seconds,brightness_hard,brightness_soft,build_ms\n")
        val manifest = StringBuilder("{\n  \"kitVersion\": ${MirrorballKits.VERSION},\n  \"stageVersion\": ${com.snipsnap.synth.Mirrorball.VERSION},\n  \"kits\": [\n")
        for ((k, g) in Kit.entries.withIndex()) {
            val folder = File(root, g.id).apply { mkdirs() }
            val staging = File(folder, "native")
            val began = System.nanoTime()
            val pads = MirrorballKits.build(g)
            val buildMs = (System.nanoTime() - began) / 1_000_000
            val kit = KitAssembler.assembleArranged("Mirrorball ${g.title}", pads, staging, tempoBpm = g.bpm.toFloat())
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

            val trials = trialRoles(g)
            manifest.append("    {\"id\": \"${g.id}\", \"title\": \"${g.title}\", \"bpm\": ${g.bpm}, \"trialPads\": ${trials.map { it + 1 }}, \"pads\": [\n")
            for (pad in saved.pads) {
                val role = pad.slot - 1
                val n = String.format(Locale.ROOT, "%02d", pad.slot)
                val (wet, _) = play(pad.slot, 1f, 0)
                write("pad-$n.wav", wet)
                val dry = MirrorballKits.trial(g, role, Trial.DRY).samples
                write("dry-$n.wav", dry)
                if (role in trials) for (t in Trial.entries) if (t != Trial.DRY && t != Trial.RECORDED_TAMBOURINE)
                    write("${t.name.lowercase()}-$n.wav", MirrorballKits.trial(g, role, t).samples)
                val tambourine = pad.displayName == "Tambourine"
                if (tambourine) write("recorded_tambourine-$n.wav", MirrorballKits.trial(g, role, Trial.RECORDED_TAMBOURINE).samples)
                // Soft, medium, hard: four takes each, in the order the pad deals them, read from the export.
                val clips = MirrorballKits.velocities.flatMap { v -> List(MirrorballKits.TAKES) { t -> play(pad.slot, v, t).first } }
                val stride = clips.maxOf { it.size } + RATE / 8
                val strip = FloatArray(stride * clips.size)
                clips.forEachIndexed { i, x -> x.copyInto(strip, i * stride) }
                write("touch-$n.wav", strip)
                val chain = MirrorballKits.settingOf(g, role)
                val heads = chain.reel.heads.joinToString("+").ifEmpty { "none" }
                val soft = play(pad.slot, MirrorballKits.velocities.first(), 0).first
                proof.append("${g.id},${pad.slot},${pad.displayName},3,4,verified,2,verified\n")
                metrics.append("${g.id},${pad.slot},${pad.displayName},${pad.drumClass},$heads,")
                    .append(String.format(Locale.ROOT, "%.3f,%.3f,%.3f,%d\n", wet.size / RATE.toDouble(), brightness(wet), brightness(soft), buildMs))
                manifest.append("      {\"slot\": ${pad.slot}, \"name\": \"${pad.displayName}\", \"class\": \"${pad.drumClass}\", ")
                    .append("\"heads\": \"$heads\", \"send\": ${chain.reel.send}, \"repeats\": ${chain.reel.repeats}, ")
                    .append("\"inputDb\": ${chain.press.inputDb}, \"ratio\": \"${chain.press.ratio}\", \"cabHz\": ${chain.combo.cabHz.roundToInt()}, ")
                    .append("\"seconds\": ${String.format(Locale.ROOT, "%.3f", wet.size / RATE.toDouble())}, \"trial\": ${role in trials}, \"tambourine\": $tambourine, \"phrase\": ${role >= MirrorballKits.HITS}, ")
                    .append("\"wet\": ${envelope(wet, max(wet.size, dry.size))}, \"dryEnv\": ${envelope(dry, max(wet.size, dry.size))}}")
                    .append(if (pad.slot == saved.pads.size) "\n" else ",\n")
            }
            manifest.append("    ]}").append(if (k == Kit.entries.lastIndex) "\n" else ",\n")

            // The groove, dealt the way the app deals it: velocity picks the zone, each hit takes the next take,
            // the velocity sets the gain, and a hat cuts the hat before it.
            val events = groove(g)
            val out = mix(events, g.bpm) { e, i -> play(e.slot, e.velocity, i, e.velocity) }
            val trimmed = trim(out)
            write("groove.wav", out)
            // The same groove from the dry strikes (the zone the velocity would pick, the same take order), at the
            // same trim, so the page can play the chain against no chain at all.
            val zones = requireNotNull(saved.pads.first().chain?.zones) { "a kit pad without velocity zones" }
            val dryOut = mix(events, g.bpm) { e, i ->
                val z = zones.indexOfFirst { e.velocity * 127f <= it.velEnd + .5f }.coerceAtLeast(0)
                MirrorballKits.dry(g, e.slot - 1, MirrorballKits.velocities[z], i % MirrorballKits.TAKES).samples to e.velocity
            }
            trim(dryOut)
            write("groove-dry.wav", dryOut)
            // Speaker candidates, after the owner heard round 1b as "a bit mid heavy, like listening through dollar store
            // headphones": the same groove and every pad's hard first take with the speaker opened, re-amped in
            // parallel, or both. Rendered directly, not read from a kit: comparisons only.
            for (sp in MirrorballKits.Speaker.entries) if (sp != MirrorballKits.SPEAKER) {
                val name = sp.name.lowercase()
                val cache = HashMap<Triple<Int, Int, Int>, FloatArray>()
                val candidate = mix(events, g.bpm) { e, i ->
                    val z = zones.indexOfFirst { e.velocity * 127f <= it.velEnd + .5f }.coerceAtLeast(0)
                    val t = i % MirrorballKits.TAKES
                    cache.getOrPut(Triple(e.slot, z, t)) {
                        MirrorballKits.renderWith(g, e.slot - 1, MirrorballKits.velocities[z], t, sp).samples
                    } to e.velocity
                }
                trim(candidate)
                write("groove-$name.wav", candidate)
                for (pad in saved.pads) write("speaker_$name-${String.format(Locale.ROOT, "%02d", pad.slot)}.wav",
                    MirrorballKits.renderWith(g, pad.slot - 1, 1f, 0, sp).samples)
            }
            File(folder, "kit-source.json").writeText(File(staging, KitStore.FILE_NAME).readText())
            println("Verified ${g.title}: 16 pads, 192 slices, saved PCM + playback + MPC 3 track, built in $buildMs ms, groove trim $trimmed")
        }
        manifest.append("  ]\n}\n")
        File(root, "verification.csv").writeText(proof.toString())
        File(root, "metrics.csv").writeText(metrics.toString())
        File(root, "manifest.json").writeText(manifest.toString())
    }
}
