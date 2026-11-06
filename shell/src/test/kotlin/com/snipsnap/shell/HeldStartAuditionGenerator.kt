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
import com.snipsnap.synth.HeldStart
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * How long a held key's own start lasts before the held tone takes over: the owner heard ARCO's and BORE's held keys on
 * 2026-11-06 and said "either the initial sound is too long or there is too much difference between the hit and the held
 * portion". Measured on those clips, the start differs from the held tone only in its first 0.1 to 0.6 s (CELLO's bow
 * bite is 40 to 90 % brighter for up to 0.6 s at C2, ERHU's for 0.1 to 0.2 s, FLUTE's chiff about 0.1 s, SAX's dark
 * reed onset 0.1 to 0.2 s); after that level, brightness, waver and pitch already match the loop.
 *
 * So the variants are one dial, how long the hit keeps its own character, at each voice's low, middle and high zone,
 * hard layer, through the instrument player:
 *  - **Now:** the zone's whole start, as the zones were first built (`hit = null`).
 *  - **Medium, Short, No hit:** [HeldStart.shape] at three lengths: the zone's own audio until the hit's end, then a
 *    raised-cosine handover to the zone's own loop, phase-aligned to the note, carrying the start's level rise so the
 *    swell stays the note's. The loop marker moves to where the handover and the swell are done, and the loop after it
 *    is the same loop turned to start there, so its seam is the shipped seam.
 *
 * Heard (2026-11-06): the owner picked Short ("Short sound good in audition"), which every held zone now ships with
 * ([HeldStart.SHORT]). Run via `./gradlew :shell:generateHeldStartAudition -PheldStartOutput=<dir>`.
 */
object HeldStartAuditionGenerator {

    /** A variant of the start: its label and [hit] for [HeldStart.shape], or null for the zone's whole start (the first build). */
    class Variant(val label: String, val hit: HeldStart.Hit?)

    /** The round's four lengths. Short is [HeldStart.SHORT], the owner's pick, and is what every held zone now ships with. */
    val VARIANTS = listOf(
        Variant("now", null),
        Variant("medium", HeldStart.Hit(pure = 0.20f, handover = 0.60f)),
        Variant("short", HeldStart.SHORT),
        Variant("nohit", HeldStart.Hit(pure = 0f, handover = 0.02f)),
    )

    private const val HOLD_SECONDS = 6f
    private const val RELEASE_SECONDS = 0.6f
    private const val TARGET_LOUDNESS = 0.1f

    private class Zone(val engine: String, val voice: String, val note: String, val midi: Int, val zone: KeyNote, val window: IntRange)

    private fun zones(): List<Zone> {
        val list = ArrayList<Zone>()
        for (voice in ArcoVoice.entries) {
            val macros = Arco.defaults(voice)
            val roots = ArcoKeys.zoneMidis(voice)
            for (midi in listOf(roots.first(), roots[roots.size / 2], roots.last()))
                list += Zone("arco", voice.name, com.snipsnap.audio.Scales.nameOf(midi), midi,
                    ArcoKeys.pad(voice, macros, midi, ArcoKeys.Layer.HARD, hit = null), ArcoPadMaker.VELOCITY_WINDOWS.last())
        }
        for ((voice, preset) in listOf(BoreVoice.FLUTE to "STEADY LOOP", BoreVoice.SAX to "SOLO LOOP")) {
            val macros = BorePresets.forVoice(voice).single { it.name == preset }.macros
            val roots = BoreKeys.padMidis(voice)
            for (midi in listOf(roots.first(), roots[roots.size / 2], roots.last()))
                list += Zone("bore", voice.name, com.snipsnap.audio.Scales.nameOf(midi), midi,
                    BoreKeys.pad(voice, macros, midi, hit = null).last(), BoreKeys.VELOCITY_WINDOWS.last())
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
            for (v in VARIANTS) {
                val note = HeldStart.shape(z.zone, v.hit)
                val seam = Keys.seamError(note.snip.samples, note.loopStartFrame.toInt())
                // The turned loop's wrap is two consecutive frames of the shipped loop, so it must stay under the house bar.
                check(seam < max(Keys.MAX_SEAM_ERROR, shippedSeam)) { "${z.voice} ${z.note} ${v.label}: seam $seam against the shipped $shippedSeam" }
                val clip = ArcoPadMaker.play(note, z.midi, z.window, hold, RELEASE_SECONDS)
                val name = "${z.engine}-${z.voice}-${z.note}-${v.label}".lowercase(Locale.ROOT).replace("#", "s") + ".wav"
                WavWriter.write(File(out, name), level(clip), WavWriter.BitDepth.PCM_16)
                val line = "%s,%s,%s,%s,%.3f,%.3e,%.3e".format(Locale.ROOT, z.engine, z.voice, z.note, v.label,
                    note.loopStartFrame.toDouble() / sr, seam, shippedSeam)
                println(line)
                lines += line
            }
        }
        File(out, "clips.csv").writeText("engine,voice,note,variant,loopStartSeconds,seam,shippedSeam\n" + lines.joinToString("\n") + "\n")
        println("wrote ${lines.size} clips to $out")
    }
}
