package com.snipsnap.shell

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pghi
import com.snipsnap.audio.Snip
import com.snipsnap.audio.Spectral
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Thump
import com.snipsnap.synth.ThumpVoice
import com.snipsnap.synth.Tines
import com.snipsnap.synth.TinesVoice
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Renders the A1 gate (docs/superpowers/specs/2026-09-30-become-strung-say-design.md,
 * "The A1 gate: BECOME") under testkit/become-audition/ (gitignored): ten
 * clips from the bold round's two sources (Appendix A.1), a THUMP kick at
 * 55 Hz and a TINES bell whose partials sit on its harmonics - the two
 * alone, today's MORPH at .5, SPLICE at 100 ms, BECOME 100 / 250 / 500 /
 * 1000 ms at MIX 1, 250 ms at MIX .5, and the bold round's own clip rebuilt
 * from its recipe. Every clip shares one loudness ([AuditionLevel]); since
 * BECOME's level follows the end blend, each caption carries the clip's
 * length and its loudness before levelling against the kick's, so the level
 * can still be judged. Writes manifest.json, then copies the listening page
 * from the test resources. Run via `./gradlew :shell:generateBecomeAudition`.
 */
object BecomeAuditionGenerator {

    private const val VOICE = "BECOME"
    private const val DOT = "·"

    /** The bold round's kick: TUNE .80 measured 55.00 Hz, 0.560 s long. */
    private val KICK = mapOf("TUNE" to 0.80f, "SWEEP" to 0.5f, "DECAY" to 0.6f, "HOLD" to 0.3f, "CLICK" to 0.35f, "DRIVE" to 0.3f)

    /** The CLANG that lands the bell's partner on exactly 2.0x: the synth's `around` is exponential below its pivot. */
    private val CLANG_EXACT_OCTAVE = (0.5 * ln(2.0 / 1.5) / ln(2.01 / 1.5)).toFloat()

    /** TUNE 0 is the BELL's lowest carrier, 220 Hz = 4 x 55; RATIO .67 snaps to 3.5, a 770 Hz modulator = 14 x 55. */
    private val BELL = mapOf("TUNE" to 0f, "RATIO" to 0.67f, "BRIGHT" to 0.62f, "DECAY" to 0.85f, "BITE" to 0.3f, "CLANG" to CLANG_EXACT_OCTAVE)

    private class Clip(val id: String, val name: String, val desc: String, val snip: Snip)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/become-audition")
        val dir = File(root, VOICE).apply { mkdirs() }
        val kick = Thump.render(ThumpVoice.KICK, KICK)
        val bell = Tines.render(TinesVoice.BELL, BELL)
        check(kick.channels == 1 && bell.channels == 1) { "the bold round's recipe is mono: kick ${kick.channels}, bell ${bell.channels} channels" }
        val parent = listOf(Mutate.Source("BELL", bell))
        fun morph(mix: Float, becomeMs: Int): Snip =
            Mutate.render(kick, parent, Mutate.Mode.MORPH, morphAmount = mix, becomeMs = becomeMs).snip
        val bold = boldRoundClip(kick, bell)

        val clips = listOf(
            Clip("01_kick", "1 $DOT THE KICK", "THUMP KICK, TUNE .80 (55 HZ), SWEEP .5, DECAY .6, HOLD .3, CLICK .35, DRIVE .3", kick),
            Clip("02_bell", "2 $DOT THE BELL", "TINES BELL, 220 HZ CARRIER, RATIO 3.5, BRIGHT .62, DECAY .85, BITE .3, PARTNER AT 2.0X", bell),
            Clip("03_morph", "3 $DOT MORPH .5, BECOME OFF", "TODAY'S MORPH: ONE BLEND FROM THE ATTACK TO THE TAIL", morph(0.5f, 0)),
            Clip(
                "04_splice", "4 $DOT SPLICE AT 100 MS", "TODAY'S NEAREST BY WAVEFORM: THE KICK'S ATTACK, THEN THE BELL'S BODY",
                Mutate.render(kick, parent, Mutate.Mode.SPLICE, spliceAtMs = 100).snip,
            ),
            Clip("05_become_100", "5 $DOT BECOME 100 MS, MIX 1", "THE KICK TURNS INTO THE BELL OVER 100 MS", morph(1f, 100)),
            Clip("06_become_250", "6 $DOT BECOME 250 MS, MIX 1", "THE KICK TURNS INTO THE BELL OVER 250 MS", morph(1f, 250)),
            Clip("07_become_500", "7 $DOT BECOME 500 MS, MIX 1", "THE KICK TURNS INTO THE BELL OVER 500 MS", morph(1f, 500)),
            Clip("08_become_1000", "8 $DOT BECOME 1000 MS, MIX 1", "THE KICK TURNS INTO THE BELL OVER A SECOND", morph(1f, 1000)),
            Clip("09_become_250_mix_50", "9 $DOT BECOME 250 MS, MIX .5", "THE KICK TURNS INTO THE HALF-WAY BLEND OVER 250 MS", morph(0.5f, 250)),
            Clip(
                "10_bold_round", "10 $DOT THE BOLD ROUND'S CLIP",
                "REBUILT FROM ITS RECIPE: AN S-CURVE FROM 10 TO 150 MS, THE BELL 45 MS LATE AT TWICE THE KICK'S LOUDNESS, THE KICK'S OWN HEAD FADED BACK IN",
                bold,
            ),
        )
        check(clips.size <= 10) { "a gate is at most ten clips, got ${clips.size}" }

        val kickLoudness = Loudness.of(kick)
        for (c in clips) WavWriter.write(File(dir, "${c.id}.wav"), AuditionLevel.level(c.snip), WavWriter.BitDepth.PCM_16)
        fun caption(c: Clip): String {
            val seconds = c.snip.frameCount.toFloat() / c.snip.sampleRate
            val db = 20f * log10(Loudness.of(c.snip).coerceAtLeast(1e-9f) / kickLoudness)
            return "${c.desc} $DOT ${"%.2f".format(Locale.ROOT, seconds)} S $DOT ${"%+.1f".format(Locale.ROOT, db)} DB AGAINST THE KICK, BEFORE LEVELLING"
        }
        val groups = listOf(
            Triple("THE TWO SOURCES", false, listOf(0, 1)),
            Triple("WHAT THE APP MAKES TODAY", false, listOf(2, 3)),
            Triple("BECOME AT MIX 1", true, listOf(4, 5, 6, 7)),
            Triple("BECOME AT MIX .5", false, listOf(8)),
            Triple("THE BOLD ROUND'S CLIP, REBUILT", false, listOf(9)),
        )
        val g = groups.joinToString(",") { (label, key, ix) ->
            val c = ix.joinToString(",") { i -> "[${q(clips[i].id)},${q(clips[i].name)},${q(caption(clips[i]))}]" }
            "{\"label\":${q(label)},\"key\":$key,\"clips\":[$c]}"
        }
        val readout = listOf(
            "THE BOLD ROUND'S TWO SOURCES",
            "KICK 55 HZ $DOT BELL 220 HZ, EVERY PARTIAL ON THE KICK'S SERIES",
            "BECOME IS LINEAR IN AMOUNT, ONE STEP PER 5.8 MS HOP, READ THROUGH A 23 MS WINDOW",
        ).joinToString(",") { q(it) }
        val body = "MORPH blends a pad and a parent into one sound between them. BECOME starts the hit as the pad and turns it into that blend over the milliseconds given, so the first beat is the kick and what rings is the bell."
        val voice = "{\"id\":${q(VOICE)},\"display\":${q("A KICK BECOMES A BELL")},\"body\":${q(body)},\"readout\":[$readout],\"groups\":[$g]}"
        File(root, "manifest.json").writeText("{\"voices\": [\n$voice\n]}\n")
        val page = BecomeAuditionGenerator::class.java.getResourceAsStream("/audition/become-audition.html")
            ?: error("the listening page is missing from shell/src/test/resources/audition/")
        File(root, "index.html").outputStream().use { out -> page.use { it.copyTo(out) } }

        // Appendix A.1's numbers for the bold round's clip: if these drift, clip 10 is not what the owner heard.
        println(
            "bold-round rebuild: kick %.3f s (A.1: 0.560), clip 10 %.3f s (A.1: 1.190), raw peak %.2f (A.1: about 2.09)".format(
                Locale.ROOT, kick.frameCount.toFloat() / kick.sampleRate, bold.frameCount.toFloat() / bold.sampleRate, bold.peak(),
            ),
        )
        println("wrote ${clips.size} clips + manifest.json + index.html under ${root.absolutePath}")
    }

    /**
     * The bold round's clip 2, rebuilt from its recipe (spec Appendix A.1):
     * the bell delayed 45 ms and scaled to 2.0x the kick's [Loudness.of],
     * each frame mixed at a smoothstep of its centre time from 10 to 150 ms,
     * PGHI phases, the kick's own first 15 ms crossfaded back over the head
     * with a raised cosine, a 12 Hz one-pole DC blocker and a 4 ms tail fade.
     * What the owner heard when the family was kept, beside BECOME's
     * default (Decision 1): none of these extras is BECOME's.
     */
    private fun boldRoundClip(kick: Snip, bell: Snip): Snip {
        val rate = kick.sampleRate
        val k = kick.samples
        val b = bell.samples
        val g = 2.0f * Loudness.of(kick) / Loudness.of(bell)
        val delay = (45f / 1000f * rate).roundToInt()
        val delayed = FloatArray(delay + b.size).also { System.arraycopy(b, 0, it, delay, b.size) }
        val frames = maxOf(k.size, delayed.size)
        val mk = mags(k.copyOf(frames), rate)
        val mb = mags(delayed.copyOf(frames), rate)
        val mixed = ArrayList<FloatArray>(mk.size)
        for (f in mk.indices) {
            val t = (f * Spectral.HOP - Spectral.FRAME / 2f) / rate
            val x = ((t * 1000f - 10f) / (150f - 10f)).coerceIn(0f, 1f)
            val a = x * x * (3f - 2f * x)
            mixed.add(FloatArray(Spectral.BINS) { i -> (1f - a) * mk[f][i] + a * g * mb[f][i] })
        }
        val out = Pghi.invert(mixed, frames, rate).samples
        val n = (15f / 1000f * rate).roundToInt()
        for (i in 0 until minOf(n, frames)) {
            val w = 0.5f * (1f + cos(PI.toFloat() * i / n))
            out[i] = w * k.getOrElse(i) { 0f } + (1f - w) * out[i]
        }
        // The 12 Hz one-pole DC blocker the clip used (a whole-file mean left a step in the tail).
        val pole = exp(-2.0 * PI * 12.0 / rate).toFloat()
        var xi = 0f
        var yo = 0f
        for (i in out.indices) {
            val x0 = out[i]
            yo = x0 - xi + pole * yo
            xi = x0
            out[i] = yo
        }
        // The synth's 4 ms tail fade, restated: its Dsp object is internal to :synth, out of this module's reach.
        val fade = minOf(out.size, (4f / 1000f * rate).toInt())
        for (i in 0 until fade) out[out.size - 1 - i] *= i.toFloat() / fade
        return Snip(out, 1, rate)
    }

    private fun mags(x: FloatArray, rate: Int): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        Spectral.forEachFrame(Snip(x, 1, rate)) { _, _, m -> out.add(m.copyOf()) }
        return out
    }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
