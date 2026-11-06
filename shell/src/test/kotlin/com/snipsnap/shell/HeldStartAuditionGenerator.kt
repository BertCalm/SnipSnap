package com.snipsnap.shell

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Arco
import com.snipsnap.synth.ArcoKeys
import com.snipsnap.synth.ArcoVoice
import com.snipsnap.synth.BoreKeys
import com.snipsnap.synth.BorePresets
import com.snipsnap.synth.BoreVoice
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How long a held key's own start lasts before the held tone takes over: the owner heard ARCO's and BORE's held keys on
 * 2026-11-06 and said "either the initial sound is too long or there is too much difference between the hit and the held
 * portion". Measured on those clips, the start differs from the held tone only in its first 0.1 to 0.6 s (CELLO's bow
 * bite is 40 to 90 % brighter for up to 0.6 s at C2, ERHU's for 0.1 to 0.2 s, FLUTE's chiff about 0.1 s, SAX's dark
 * reed onset 0.1 to 0.2 s); after that level, brightness, waver and pitch already match the loop.
 *
 * So the variants are one dial, how long the hit keeps its own character, at each voice's low, middle and high zone,
 * hard layer, through the instrument player:
 *  - **Now:** the zone as shipped.
 *  - **Medium, Short, No hit:** the zone's own audio until the hit's end, then a raised-cosine handover to the zone's own
 *    loop, phase-aligned to the note, carrying the start's level rise so the swell stays the note's. The loop marker
 *    moves to where the handover and the swell are done, and the loop after it is the same loop turned to start
 *    there, so its seam is the shipped seam.
 *
 * Nothing here has been heard. Run via `./gradlew :shell:generateHeldStartAudition -PheldStartOutput=<dir>`.
 */
object HeldStartAuditionGenerator {

    /** A variant: the hit plays as itself until [pure] seconds, and the held tone has fully taken over by [handover]. */
    class Variant(val label: String, val pure: Float, val handover: Float)

    val VARIANTS = listOf(
        Variant("medium", pure = 0.20f, handover = 0.60f),
        Variant("short", pure = 0.08f, handover = 0.30f),
        Variant("nohit", pure = 0f, handover = 0.02f),
    )

    private const val HOLD_SECONDS = 6f
    private const val RELEASE_SECONDS = 0.6f
    private const val TARGET_LOUDNESS = 0.1f
    private const val SETTLED_FRAMES = 300

    /** The zone with its start reshaped by [v], or the zone itself for null. The returned note loops as the original does. */
    fun reshape(note: KeyNote, v: Variant?): KeyNote {
        if (v == null) return note
        val x = note.snip.samples
        val sr = note.snip.sampleRate
        val ls = note.loopStartFrame.toInt()
        val lf = x.size - ls
        fun loop(i: Int) = x[ls + Math.floorMod(i, lf)]
        val a = (v.pure * sr).roundToInt()
        val h = max(a + 1, (v.handover * sr).roundToInt())

        // Align the loop to the note where the handover is half done (no earlier than 0.15 s, where the tone has a period).
        val t0 = max((a + h) / 2, (0.15f * sr).roundToInt())
        val w = (0.03f * sr).roundToInt()
        var bestD = 0
        var best = Double.NEGATIVE_INFINITY
        var d = 0
        while (d < lf) {
            var dot = 0.0; var ee = 0.0
            for (k in 0 until w) { val l = loop(d + k); dot += x[t0 + k] * l; ee += l * l }
            val c = dot / sqrt(ee + 1e-12)
            if (c > best) { best = c; bestD = d }
            d += 2
        }
        for (dd in bestD - 2..bestD + 2) {
            var dot = 0.0; var ee = 0.0
            for (k in 0 until w) { val l = loop(dd + k); dot += x[t0 + k] * l; ee += l * l }
            val c = dot / sqrt(ee + 1e-12)
            if (c > best) { best = c; bestD = dd }
        }
        fun held(t: Int) = loop(bestD + t - t0)

        // The start's level against the loop's, rising only: the held tone follows the note's own swell, then holds.
        var loopEnergy = 0.0
        for (i in ls until x.size) loopEnergy += x[i].toDouble() * x[i]
        val loopRms = sqrt(loopEnergy / lf)
        val half = (0.01f * sr).roundToInt()
        val gain = DoubleArray(ls)
        var running = 0.0
        var acc = 0.0
        for (i in 0 until min(ls, 2 * half)) acc += x[i].toDouble() * x[i]
        for (t in 0 until ls) {
            if (t + half < x.size) acc += x[t + half].toDouble() * x[t + half]
            if (t - half - 1 >= 0) acc -= x[t - half - 1].toDouble() * x[t - half - 1]
            val env = sqrt(max(acc, 0.0) / (2 * half + 1))
            running = max(running, min(1.0, env / (0.98 * loopRms)))
            gain[t] = running
        }
        var end = h
        while (end < ls && gain[end] < 1.0) end++
        // The house seam check reads the 256 frames before the marker against the loop's last 256, so the marker sits
        // after a stretch of pure held tone: what plays into the wrap is then what plays into the loop's start.
        end += SETTLED_FRAMES
        require(end < ls) { "the start never reaches the loop's level before the shipped loop start" }

        val out = FloatArray(end + lf)
        for (t in 0 until end) {
            val wgt = when {
                t < a -> 0.0
                t < h -> 0.5 - 0.5 * cos(PI * (t - a) / (h - a))
                else -> 1.0
            }
            out[t] = ((1 - wgt) * x[t] + wgt * gain[t] * held(t)).toFloat()
        }
        for (t in end until end + lf) out[t] = held(t)
        return KeyNote(Snip(out, 1, sr), end.toLong())
    }

    private class Zone(val engine: String, val voice: String, val note: String, val midi: Int, val zone: KeyNote, val window: IntRange)

    private fun zones(): List<Zone> {
        val list = ArrayList<Zone>()
        for (voice in ArcoVoice.entries) {
            val macros = Arco.defaults(voice)
            val roots = ArcoKeys.zoneMidis(voice)
            for (midi in listOf(roots.first(), roots[roots.size / 2], roots.last()))
                list += Zone("arco", voice.name, com.snipsnap.audio.Scales.nameOf(midi), midi,
                    ArcoKeys.pad(voice, macros, midi, ArcoKeys.Layer.HARD), ArcoPadMaker.VELOCITY_WINDOWS.last())
        }
        for ((voice, preset) in listOf(BoreVoice.FLUTE to "STEADY LOOP", BoreVoice.SAX to "SOLO LOOP")) {
            val macros = BorePresets.forVoice(voice).single { it.name == preset }.macros
            val roots = BoreKeys.padMidis(voice)
            for (midi in listOf(roots.first(), roots[roots.size / 2], roots.last()))
                list += Zone("bore", voice.name, com.snipsnap.audio.Scales.nameOf(midi), midi,
                    BoreKeys.pad(voice, macros, midi).last(), BoreKeys.VELOCITY_WINDOWS.last())
        }
        return list
    }

    private fun level(s: Snip): Snip {
        val out = s.samples.copyOf()
        var g = TARGET_LOUDNESS / Loudness.of(s).coerceAtLeast(1e-9f)
        val peak = out.maxOf { abs(it) }
        if (peak * g > 0.95f) g = 0.95f / peak
        for (i in out.indices) out[i] *= g
        return Snip(out, 1, s.sampleRate)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(args.firstOrNull() ?: "../testkit/held-start-audition").canonicalFile.also { it.mkdirs() }
        val sr = WavWriter.MPC_SAMPLE_RATE
        val hold = (HOLD_SECONDS * sr).roundToInt()
        val lines = ArrayList<String>()
        for (z in zones()) {
            val shippedSeam = Keys.seamError(z.zone.snip.samples, z.zone.loopStartFrame.toInt())
            for (v in listOf<Variant?>(null) + VARIANTS) {
                val note = reshape(z.zone, v)
                val seam = Keys.seamError(note.snip.samples, note.loopStartFrame.toInt())
                // The turned loop's wrap is two consecutive frames of the shipped loop, so it must stay under the house bar.
                check(seam < max(Keys.MAX_SEAM_ERROR, shippedSeam)) { "${z.voice} ${z.note} ${v?.label}: seam $seam against the shipped $shippedSeam" }
                val clip = ArcoPadMaker.play(note, z.midi, z.window, hold, RELEASE_SECONDS)
                val name = "${z.engine}-${z.voice}-${z.note}-${v?.label ?: "now"}".lowercase(Locale.ROOT).replace("#", "s") + ".wav"
                WavWriter.write(File(out, name), level(clip), WavWriter.BitDepth.PCM_16)
                val line = "%s,%s,%s,%s,%.3f,%.3e,%.3e".format(Locale.ROOT, z.engine, z.voice, z.note, v?.label ?: "now",
                    note.loopStartFrame.toDouble() / sr, seam, shippedSeam)
                println(line)
                lines += line
            }
        }
        File(out, "clips.csv").writeText("engine,voice,note,variant,loopStartSeconds,seam,shippedSeam\n" + lines.joinToString("\n") + "\n")
        println("wrote ${lines.size} clips to $out")
    }
}
