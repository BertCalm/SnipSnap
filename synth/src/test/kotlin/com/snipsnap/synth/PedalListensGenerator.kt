package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Renders the pedal board's Stage 1b listens under testkit/pedal-listens/ (gitignored): the
 * listens that cost no new section, proposed in
 * docs/superpowers/specs/2026-10-19-pedal-board-brainstorm.md and run beside the WAH gate.
 *
 * Four sheets. (1) WAH on the short pads a kit is mostly made of (a closed hat, a rim, a snare
 * trimmed to [TRIM_SEC]; no click, because no engine renders one, so the rim stands in) and on the
 * full snare, each with the shipped AUTO move and with a
 * one-cycle floor on its rate ([Wah.fitted]: the sweep completes at least [MIN_CYCLES] before the
 * pad ends), at the pad sheet's AMT 70 and at 100. (2) FADE IN's free first listen: SWELL on its own
 * and the BLOOM board (SWELL into the amp, then a spring) against the pad, on JANGLE and the choir.
 * (3) VIBRATO's: TAPE's wobble alone (DRIVE and AGE at 0) and ENSEMBLE, against the pad. (4) DELAY's:
 * the long, dark ECHO recipe the brainstorm named ([DELAYED]) beside SLAP, at 70 and at 100, and a
 * second recipe with closer repeats that fall faster ([DELAYED_SHORT]), on the snare, the choir and
 * JANGLE. The rack's one-second tail cap runs from the pad's own end, so on these sources it lands on
 * repeats already 48 to 80 dB under the hit (measured 2026-10-20); the page asks whether that cut is
 * heard at all, rather than telling the owner it is.
 *
 * JANGLE is heard as the kit has it (the dry string through its landing amp). A pedal that belongs
 * in front of the amp (SWELL) is put there, in the rig order, with the amp's own values; a pedal that
 * belongs after it (the spring, the tape, the chorus, the echo) is tapped on the baked pad, as the
 * pad sheet does it. Every clip shares one loudness ([AuditionLevel]). Writes manifest.json and
 * index.html (the page at src/test/resources/audition/pedal-listens.html with the clip table
 * written in). Run via `./gradlew :synth:generatePedalListens`. The page is published as a listening
 * artifact with root testkit/pedal-listens, the 48 clips as its files and the answer store (the db
 * capability), as the WAH listen is; the owner's answers are its verdicts/ documents.
 */
object PedalListensGenerator {

    /** The pad sheet's first tap, and full. */
    private val AMOUNTS = listOf(0.7f, 1f)

    /** The trimmed snare: a hit cut mid-body, the way a trimmed pad on the phone is. */
    const val TRIM_SEC = 0.06f

    /** The floor: one whole sweep per pad. */
    const val MIN_CYCLES = 1f

    /** The DELAY recipe the brainstorm named: repeats 225 ms apart at feedback 0.66 (the fifth about 14 dB under the first), darkening; at 70, 152 ms apart at 0.51. */
    internal val DELAYED = FxChain(echo = mapOf("TIME" to 0.75f, "REPEAT" to 0.85f, "TONE" to 0.35f, "MIX" to 0.5f))

    /**
     * A second taste of the same idea: closer repeats (158 ms) that fall faster (feedback 0.45: the
     * sixth about 35 dB under the first, the seventh 42). The rack's cap, one second past the pad's
     * end, lands on this recipe's trail about 70 dB under the snare's peak and on silence for the
     * choir and JANGLE (measured 2026-10-20), so it is heard as a taste, not as a way round the cap.
     */
    internal val DELAYED_SHORT = FxChain(echo = mapOf("TIME" to 0.55f, "REPEAT" to 0.5f, "TONE" to 0.35f, "MIX" to 0.5f))

    /** SWELL on its own, the `swelled` character's value. */
    internal val SWELL_ONLY = FxChain(swell = mapOf("RISE" to 0.6f))

    /** The spring the BLOOM board ends in. */
    internal val BLOOM_SPRING = mapOf("SIZE" to 0.5f, "TONE" to 0.5f, "MIX" to 0.4f)

    /** TAPE's wobble alone: the wow and flutter with no saturation and no head wear. */
    internal fun tapeWobble(depth: Float) = FxChain(tape = mapOf("WOBBLE" to depth, "DRIVE" to 0f, "AGE" to 0f))

    private fun pct(amount: Float) = (amount * 100).roundToInt()

    /** [s] cut to its first [seconds], the last 3 ms faded so the cut itself does not click. */
    internal fun trimmed(s: Snip, seconds: Float): Snip {
        val frames = minOf(s.frameCount, (seconds * s.sampleRate).toInt())
        val out = s.samples.copyOfRange(0, frames * s.channels)
        Dsp.fadeTail(out, ms = 3f, rate = s.sampleRate, channels = s.channels)
        return Snip(out, s.channels, s.sampleRate)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/pedal-listens")
        root.mkdirs()
        root.walkTopDown().filter { it.isFile && (it.extension == "wav" || it.name == "manifest.json" || it.name == "index.html") }.forEach { it.delete() }
        val entries = mutableListOf<String>()
        val table = mutableListOf<String>()
        // AuditionLevel meters a stereo clip on its mono fold and keeps both
        // channels, so the fold here is not for levelling: the listening page
        // and the phone play mono, so a stereo clip is folded (averaged, never
        // summed) to what the listener would hear.
        fun mono(s: Snip): Snip =
            if (s.channels == 1) s
            else Snip(FloatArray(s.frameCount) { f -> 0.5f * (s.samples[f * 2] + s.samples[f * 2 + 1]) }, 1, s.sampleRate)
        fun write(source: String, id: String, label: String, snip: Snip) {
            check(listOf(source, id, label).none { s -> s.any { it == '"' || it == '\\' || it < ' ' } }) { "manifest field needs escaping: $source / $id / $label" }
            val dir = File(root, source).apply { mkdirs() }
            WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(mono(snip)), WavWriter.BitDepth.PCM_16)
            val seconds = "%.3f".format(Locale.ROOT, snip.frameCount.toFloat() / snip.sampleRate)
            entries += """{"source":"$source","id":"$id","file":"$source/$id.wav","label":"$label","seconds":$seconds}"""
            table += """"$source/$id":{"u":"$source/$id.wav","s":$seconds}"""
        }

        val snare = Thump.render(ThumpVoice.SNARE)
        val choir = Vox.render(VoxVoice.CHOIR)
        val jangleDry = Magnet.render(MagnetVoice.JANGLE)
        val landing = Magnet.landingChain(MagnetVoice.JANGLE)
        val jangle = landing.process(jangleDry)

        // (1) WAH on short pads: the shipped AUTO move, and the same with the one-cycle floor.
        val shortSet = linkedMapOf(
            "HAT" to Thump.render(ThumpVoice.HAT_CLOSED),
            "RIM" to Thump.render(ThumpVoice.RIM),
            "SNARE60" to trimmed(snare, TRIM_SEC),
            "SNARE" to snare,
        )
        for ((name, dry) in shortSet) {
            write(name, "dry", "$name DRY", dry)
            for (amount in AMOUNTS) {
                val macros = Treatments.chain("wahed", amount).section("wah")!!
                write(name, "auto_${pct(amount)}", "$name WAH AUTO AT ${pct(amount)}, AS SHIPPED", Wah.process(dry, macros))
                write(name, "fit_${pct(amount)}", "$name WAH AUTO AT ${pct(amount)}, WITH THE ONE-CYCLE FLOOR", Wah.fitted(dry, macros, MIN_CYCLES))
            }
        }

        // (2) FADE IN: SWELL alone, and the BLOOM board (SWELL before the amp, a spring after it).
        write("JANGLE", "pad", "JANGLE AS A KIT PAD (THE DRY STRING THROUGH ITS LANDING AMP)", jangle)
        for (amount in AMOUNTS) {
            val swell = Treatments.fade(SWELL_ONLY, amount)
            write("JANGLE", "swell_${pct(amount)}", "JANGLE SWELL AT ${pct(amount)}, ON THE PAD", swell.process(jangle))
            val bloom = Treatments.fade(FxChain(swell = SWELL_ONLY.swell, spring = BLOOM_SPRING), amount)
            val rig = landing.withSection("swell", bloom.swell).withSection("spring", bloom.spring)
            write("JANGLE", "bloom_${pct(amount)}", "JANGLE BLOOM AT ${pct(amount)}: SWELL IN FRONT OF ITS LANDING AMP, THEN A SPRING", rig.process(jangleDry))
        }
        write("CHOIR", "dry", "CHOIR DRY", choir)
        for (amount in AMOUNTS) {
            write("CHOIR", "swell_${pct(amount)}", "CHOIR SWELL AT ${pct(amount)}", Treatments.fade(SWELL_ONLY, amount).process(choir))
            val bloom = Treatments.fade(FxChain(swell = SWELL_ONLY.swell, valve = Treatments.chain("driven").valve, spring = BLOOM_SPRING), amount)
            // The DRIVE chip fades the pad's end ahead of its amp (Treatments.apply); the board gets the same, or gain 1000 lifts the choir's residual tail.
            write("CHOIR", "bloom_${pct(amount)}", "CHOIR BLOOM AT ${pct(amount)}: SWELL INTO DRIVE, THEN A SPRING", bloom.process(Treatments.endFaded(choir, "driven", amount)))
        }

        // (3) VIBRATO: TAPE's wobble alone, and ENSEMBLE, against the pad.
        for ((name, dry) in linkedMapOf("JANGLE" to jangle, "CHOIR" to choir)) {
            write(name, "tape_80", "$name TAPE WOBBLE 80, DRIVE 0, AGE 0", tapeWobble(0.8f).process(dry))
            write(name, "tape_100", "$name TAPE WOBBLE 100, DRIVE 0, AGE 0", tapeWobble(1f).process(dry))
            write(name, "ensemble_100", "$name ENSEMBLE AT 100", Treatments.apply("ensembled", dry, 1f).snip)
        }

        // (4) DELAY: the long recipe beside SLAP, and the one that stays inside the tail cap.
        for ((name, dry) in linkedMapOf("SNARE" to snare, "CHOIR" to choir, "JANGLE" to jangle)) {
            write(name, "slap_70", "$name SLAP AT 70", Treatments.apply("slapback", dry, 0.7f).snip)
            for (amount in AMOUNTS) write(name, "delay_${pct(amount)}", "$name DELAY AT ${pct(amount)}", Treatments.fade(DELAYED, amount).process(dry))
            write(name, "delay_short_100", "$name DELAY, THE CLOSER RECIPE, AT 100", DELAYED_SHORT.process(dry))
        }

        check(entries.size == 48) { "expected 48 clips (four short pads x 5, FADE IN 10, VIBRATO 6, DELAY 12), wrote ${entries.size}" }
        File(root, "manifest.json").writeText("{\"clips\":[\n" + entries.joinToString(",\n") + "\n]}\n")
        val template = PedalListensGenerator::class.java.getResourceAsStream("/audition/pedal-listens.html")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("missing /audition/pedal-listens.html")
        val marker = "/* PEDAL_LISTENS_CLIPS */"
        check(template.contains(marker)) { "the page template has no $marker marker" }
        File(root, "index.html").writeText(template.replace(marker, "const CLIPS = {" + table.joinToString(",") + "};"))
        println("pedal listens: ${entries.size} clips + manifest.json + index.html under ${root.absolutePath}")
    }
}
