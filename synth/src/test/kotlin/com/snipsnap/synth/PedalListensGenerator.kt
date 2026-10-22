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
 * heard at all, rather than telling the owner it is. (5) DELAY on long notes, added after the owner's
 * first listen (2026-10-21: on the jangle and the choir "you don't hear the attack repeat like with a
 * short hit"; measured, the first repeat adds 37 dB over the snare's silence at 225 ms and 0.5 dB over
 * the jangle's still-sounding note): ROLL, the keyed treatment that strikes the hit's head again on the
 * grid, at two divisions of a stated [ROLL_BPM]; and three prototypes of an attack-fed echo
 * ([attackEcho]), ECHO's repeat line fed by the attack alone rather than the whole note, the attack as
 * SPIKE measures it, the same with the note ducked under each repeat, or a fixed head, against the
 * plain DELAY. The prototypes are test-source DSP,
 * like the WAH round-two spike: they copy ECHO's bus loop rather than call it, so nothing shipped
 * moves, and a section is written only if the owner picks one.
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

    /** The page has no kit, so ROLL's grid is stated: 1/8 is 250 ms here, close to the DELAY recipe's 225 ms. */
    const val ROLL_BPM = 120f

    /** The fixed head the second prototype feeds: the pick and a little of the body, faded over its last [HEAD_FADE_SEC]. */
    const val HEAD_SEC = 0.12f
    const val HEAD_FADE_SEC = 0.04f

    /** Which part of the note the prototype's repeat line hears. */
    enum class Feed { ATTACK, HEAD }

    /**
     * The attack-fed echo prototype: ECHO's repeat line fed by the note's attack alone, the whole
     * dry note added back untouched, so the repeats are the pick or the consonant returning under a
     * note that is still sounding. [Feed.ATTACK] weights each frame by SPIKE's own transient share
     * (the gap between a 700 Hz and an 18 Hz follower on the signal's envelope, over the fast one);
     * [Feed.HEAD] takes the first [HEAD_SEC] of the note with a raised-cosine fade over its last
     * [HEAD_FADE_SEC]. The bus is ECHO's, line for line (one delay, one low-pass in the loop, repeats
     * below minus 60 dB counted as silence, ECHO's own 1.1 s tail ceiling), then the rack's 1.0 s cap
     * from the note's end with its 10 ms fade, then a peak match. Two things differ from ECHO on
     * purpose, both forced by measurement against the plain DELAY on the jangle, whose first repeat
     * read 0.5 dB over the still-sounding note: the repeat line is levelled so its first repeat peaks
     * where the note's own attack peaked (a gain found from the bus, capped at [MAX_FEED_GAIN]; SPIKE's
     * share of a compressed pluck and the loop's low-pass had left the first repeat about 10 dB under
     * the hit, inaudible under a body at the hit's level), and [duck] (0 to 1) dips the dry note under
     * each repeat by up to [DUCK_DB] (a 1 ms attack, 150 ms release follower on the levelled repeat
     * line, scaled so its loudest repeat dips the full depth), the way a ducking delay pedal lets its repeats through a
     * held chord. A prototype in test source: it
     * repeats ECHO's loop rather than calling it, which is the one-quantity-in-two-places smell,
     * accepted here because nothing shipped may move for a listen.
     */
    /** How far the dry note dips under a repeat at duck 1, an end on purpose (measured 15 dB on the jangle 20 ms after the first repeat, a 1 ms follower on a click reaching less than its peak): the jangle's attack is only 2 dB over its body, so a repeat at the attack's level is a 4 dB bump, and the dip is what makes the repeat a rhythm. */
    const val DUCK_DB = 24f

    /** The most the repeat line may be lifted to bring its first repeat to the hit's peak: a feed that is nearly nothing is not shouted. */
    const val MAX_FEED_GAIN = 8f

    internal fun attackEcho(snip: Snip, feed: Feed, macros: Map<String, Float>, duck: Float = 0f): Snip {
        val rate = snip.sampleRate
        val frames = snip.frameCount
        val m = Echo.defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val delay = Echo.delayFrames(m.getValue("TIME"), rate)
        val feedback = Dsp.lin(m.getValue("REPEAT"), 0.15f, 0.75f)
        val toneHz = Dsp.expMap(m.getValue("TONE"), 1_200f, 9_000f)
        val taps = kotlin.math.ceil(-6.9078 / kotlin.math.ln(feedback.toDouble())).toInt().coerceAtLeast(1)
        val echoTail = (taps * delay).coerceAtMost((Echo.MAX_TAIL_SECONDS * rate).toInt())
        val capTail = (FxChain.MAX_CHAIN_TAIL_SECONDS * rate).toInt()
        val outFrames = frames + minOf(echoTail, capTail)
        val out = FloatArray(outFrames * snip.channels)
        val headFrames = (HEAD_SEC * rate).toInt()
        val headFade = (HEAD_FADE_SEC * rate).toInt()
        val inPeak = snip.peak()
        val duckFloor = Math.pow(10.0, -DUCK_DB / 20.0).toFloat()
        val duckAttack = 1f - kotlin.math.exp(-1.0 / (rate * 0.001)).toFloat()
        val duckRelease = 1f - kotlin.math.exp(-1.0 / (rate * 0.150)).toFloat()
        for (ch in 0 until snip.channels) {
            val fast = Dsp.OnePole(rate)
            val slow = Dsp.OnePole(rate)
            val lp = Dsp.OnePole(rate)
            val d = FloatArray(outFrames)
            var duckEnv = 0f
            val wetLine = FloatArray(outFrames)
            for (f in 0 until outFrames) {
                val x = if (f < frames) snip.samples[f * snip.channels + ch] else 0f
                val weight = when (feed) {
                    Feed.ATTACK -> {
                        val a = kotlin.math.abs(x)
                        val quick = fast.lp(a, Spike.FAST_HZ)
                        val slowed = slow.lp(a, Spike.SLOW_HZ)
                        ((quick - slowed).coerceAtLeast(0f) / quick.coerceAtLeast(1e-9f)).coerceIn(0f, 1f)
                    }
                    Feed.HEAD -> when {
                        f >= headFrames -> 0f
                        f < headFrames - headFade -> 1f
                        else -> (0.5 + 0.5 * kotlin.math.cos(Math.PI * (f - (headFrames - headFade)) / headFade)).toFloat()
                    }
                }
                val fed = if (f >= delay) d[f - delay] else 0f
                d[f] = lp.lp(x * weight + feedback * fed, toneHz)
                wetLine[f] = if (f >= delay) d[f - delay] else 0f
            }
            // The attack comes back at the attack's own level: the first repeat's peak is lifted to the note's peak.
            var firstPeak = 0f
            for (f in delay until minOf(outFrames, delay + (0.1f * rate).toInt())) firstPeak = maxOf(firstPeak, kotlin.math.abs(wetLine[f]))
            val feedGain = if (firstPeak > 0f) (inPeak / firstPeak).coerceAtMost(MAX_FEED_GAIN) else 1f
            // The duck follows the repeat line's envelope, scaled so the first repeat dips the note by the
            // full DUCK_DB and later, quieter repeats dip it less: a follower on a brief click reaches only a
            // fraction of the click's peak, so scaling by the note's peak left a 5 dB dip where 24 was promised.
            val envLine = FloatArray(outFrames)
            var envMax = 0f
            for (f in 0 until outFrames) {
                val a = kotlin.math.abs(wetLine[f] * feedGain)
                duckEnv += (if (a > duckEnv) duckAttack else duckRelease) * (a - duckEnv)
                envLine[f] = duckEnv
                if (duckEnv > envMax) envMax = duckEnv
            }
            for (f in 0 until outFrames) {
                val x = if (f < frames) snip.samples[f * snip.channels + ch] else 0f
                val wet = wetLine[f] * feedGain
                val dip = if (duck <= 0f || envMax <= 0f) 1f else 1f - duck * (1f - duckFloor) * (envLine[f] / envMax).coerceIn(0f, 1f)
                out[f * snip.channels + ch] = x * dip + wet
            }
        }
        // The rack's cap fades the last 10 ms, as FxChain.capTail does.
        val fade = (0.01f * rate).toInt() * snip.channels
        for (i in 0 until minOf(fade, out.size)) out[out.size - 1 - i] *= i.toFloat() / fade
        var outPeak = 0f
        for (v in out) { val a = kotlin.math.abs(v); if (a > outPeak) outPeak = a }
        if (inPeak > 0f && outPeak > 0f) {
            val k = inPeak / outPeak
            for (i in out.indices) out[i] = (out[i] * k).coerceIn(-1f, 1f)
        }
        return Snip(out, snip.channels, rate)
    }

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

        // (5) DELAY on long notes: ROLL on the grid, and the attack-fed echo two ways, beside the plain DELAY above.
        for ((name, dry) in linkedMapOf("JANGLE" to jangle, "CHOIR" to choir)) {
            for (division in listOf("1/8", "1/16")) {
                Roll.refusal(dry, ROLL_BPM, division)?.let { error("$name cannot roll at $division: $it") }
                write(name, "roll_" + division.substringAfter('/'), "$name ROLL AT $division OF ${ROLL_BPM.roundToInt()} BPM", Roll.roll(dry, ROLL_BPM, division, 1f))
            }
            write(name, "attack_echo", "$name ATTACK-FED ECHO: THE DELAY RECIPE FED BY SPIKE'S ATTACK ALONE", attackEcho(dry, Feed.ATTACK, DELAYED.echo!!))
            write(name, "attack_ducked", "$name ATTACK-FED ECHO, THE NOTE DUCKED 24 DB UNDER EACH REPEAT", attackEcho(dry, Feed.ATTACK, DELAYED.echo!!, duck = 1f))
            write(name, "head_echo", "$name HEAD-FED ECHO: THE DELAY RECIPE FED BY THE FIRST 120 MS ALONE", attackEcho(dry, Feed.HEAD, DELAYED.echo!!))
        }

        check(entries.size == 58) { "expected 58 clips (four short pads x 5, FADE IN 10, VIBRATO 6, DELAY 12, long notes 10), wrote ${entries.size}" }
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
