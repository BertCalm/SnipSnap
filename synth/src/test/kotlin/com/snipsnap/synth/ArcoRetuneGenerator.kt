package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders the ARCO R1c re-listen page under testkit/arco-retune/ (gitignored): `manifest.json`, which the page builds itself from,
 * the clips, and a copy of the page from the test resources. Run via `./gradlew :synth:generateArcoRetune`, then publish the folder.
 *
 * The owner listened to the R1b engine ([ArcoAuditionGenerator]'s page) and marked three things NEARLY: BODY 1 in both voices ("not
 * enough": the box should be louder at the top of the knob), CELLO's BOW 1 ("not enough bite") and CELLO's vibrato ("too mechanical").
 * ERHU's BOW 1 and ERHU's vibrato were a YES and do not change. R1c changed those three in [Arco]; this page lets the owner hear each
 * one beside what it was. About twenty clips on three cards, each a before-and-now comparison, labelled (not blind), dry and mono, and
 * every one through [AuditionLevel.level], so one loudness for all of them as on the big page.
 *
 *  - **BODY**, for CELLO then ERHU at the voice's default note and default knobs but BODY: the default (BODY .5, which R1c leaves exactly
 *    as it was), BODY 1 BEFORE (the box rung as loud as the string, 1.00 times) and BODY 1 NOW (the box [Arco.BODY_TOP] times the string).
 *  - **BOW**, CELLO only, BOW 1 and every other knob at its default, at the default note and at the root (TUNE 0): BEFORE (the old bite),
 *    NOW (the engine's own) and STRONGER (a calibration, not in the engine: if NOW is still not enough this is the next step).
 *  - **VIBRATO**, CELLO only, at the default note and the root, HOLD for three seconds of bow: BEFORE (the old sine) and NOW (the engine's
 *    own). Beside them one ERHU clip of the default note, as a control: its vibrato was a yes and is unchanged on purpose.
 *
 * **The BEFORE clips are the old engine, re-made.** The old engine is gone from [Arco], so [oldRender] is its [Arco.render] again:
 * [oldBow] is the bow's loop as R1b had it (one bite size for both voices, relaxing in the stroke's own time, the pressure's share of the
 * bite measured against that one size, the plain sine vibrato) and [Strings.bodyRing] rings the box at the macro value itself. It is a copy,
 * and not the engine's probe overrides (`overshoot = 1.75f, biteSeconds = 0f, vibratoShape = VIBRATO_PLAIN`), because those do not reproduce
 * the old CELLO stroke: [checkOverridesCannotMakeTheOldStroke] is the proof. [checkOldEngine] holds the copy to the present engine where
 * R1c changed nothing (ERHU everywhere; CELLO wherever the bow has no bite, with the plain vibrato). That the copy is the engine the
 * owner heard is checked outside this generator, by comparing the clips it writes to the clips R1b's page wrote (not in the repository).
 *
 * Nothing here has been heard by anyone yet. The page says so.
 */
object ArcoRetuneGenerator {

    private class Clip(val id: String, val name: String, val desc: String)
    private class Group(val label: String, val clips: List<Clip>)
    private class Section(val id: String, val display: String, val body: String, val readout: List<String>, val groups: List<Group>)
    private class Block(val hd: String, val paragraphs: List<String>)

    /** What a clip is rendered as: the old engine whole, or the present engine with [overshoot] and [biteSeconds] overriding BOW's bite. */
    private class Variant(val old: Boolean = false, val overshoot: Float? = null, val biteSeconds: Float? = null)

    private val NOW = Variant()
    private val BEFORE = Variant(old = true)

    /** The calibration the BOW card offers beside NOW: a bigger and longer bite than the engine's own. */
    private const val STRONGER_OVERSHOOT = 4.0f
    private const val STRONGER_BITE_SECONDS = 0.10f

    /** R1b's BOW 1 bite, for both voices; [Arco] no longer carries it as one number (ERHU's is [Arco.OVERSHOOT_MAX_ERHU], the same). */
    private const val OLD_OVERSHOOT_MAX = 1.75f

    /** The bite relaxes to nothing after this many of its time constants: [Arco]'s own private number. */
    private const val BITE_TIME_CONSTANTS = 14

    /** The three seconds of bow the vibrato card holds: HOLD 0.88. */
    private const val HELD_SECONDS = 3f
    private val HELD_HOLD = Arco.holdFor(HELD_SECONDS)

    private val CELLO = ArcoVoice.CELLO
    private val ERHU = ArcoVoice.ERHU

    // ---- the old engine -----------------------------------------------------

    /** [Arco.render] as R1b made it, for a one-shot: [oldBow], the box rung at the macro BODY itself, the finish. */
    private fun oldRender(voice: ArcoVoice, macros: Map<String, Float>): Snip {
        val m = Arco.settled(macros, voice)
        require(!Arco.isLoop(m.getValue("HOLD"))) { "oldRender is for one-shots" }
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val raw = oldBow(voice, Arco.frequencyFor(voice, m.getValue("TUNE")), m, rate)
        val ring = Strings.bodyRing(raw, Arco.bodyFor(voice), m.getValue("BODY"), rate, Arco.BODY_CEILING_SECONDS)
        val rung = if (ring.size == raw.size) ring else ring.copyOf(raw.size)
        return Snip(Arco.finish(rung, rate), channels = 1, sampleRate = Dsp.RATE)
    }

    /**
     * R1b's bow, which is [Arco.bow] (with `play` and the gate) before R1c, for a one-shot with the vibrato on: the bite is
     * [OLD_OVERSHOOT_MAX] times at BOW 1 for both voices, rising from [Arco.OVERSHOOT_FROM]; the pressure's share of it is
     * measured against that same size; both relax with the stroke's own time constant; the vibrato is [Arco.VIBRATO_PLAIN].
     */
    private fun oldBow(voice: ArcoVoice, hz: Float, m: Map<String, Float>, rate: Int): FloatArray {
        val bowKnob = m.getValue("BOW")
        val holdSec = Arco.holdSeconds(m.getValue("HOLD"))
        val semitone = Arco.semitoneFor(voice, m.getValue("TUNE"))
        val grip = m.getValue("GRIP")
        val window = Arco.gripFor(voice, semitone)
        val p = Arco.pressureFor(voice, semitone, grip)
        val corner = Arco.cornerFor(voice, semitone, grip)
        val release = Arco.releaseSeconds(hz, corner, holdSec)
        val holdN = (holdSec * rate).toInt().coerceAtLeast(1)
        val attackN = (min(Arco.attackSeconds(bowKnob), Arco.ATTACK_HOLD_FRACTION * holdSec) * rate).toInt().coerceAtLeast(1)
        val rampN = (Arco.RELEASE_RAMP_SECONDS * rate).toInt()
        val stopN = (release * rate).toInt().coerceAtLeast(1)
        val total = holdN + rampN + stopN

        val over = 1f + (OLD_OVERSHOOT_MAX - 1f) * ((bowKnob - Arco.OVERSHOOT_FROM) / (1f - Arco.OVERSHOOT_FROM)).coerceIn(0f, 1f)
        val biteShare = ((over - 1f) / (OLD_OVERSHOOT_MAX - 1f)).coerceIn(0f, 1f)
        val pBite = Dsp.lin(biteShare, p, max(p, window.pressureHigh))
        val tau = attackN.toDouble() / rate

        val bow = Strings.Bow(f = hz, beta = Arco.BETA, bridgeHz = corner, share = Arco.shareFor(voice), rate = rate)
        val out = FloatArray(total)
        val liftAt = holdN + rampN
        val bite = if (over > 1f) (over - 1f).toDouble() else 0.0
        val biteEnd = BITE_TIME_CONSTANTS * attackN
        for (i in 0 until total) {
            val t = i.toDouble() / rate
            val ramp = if (i < attackN) i.toFloat() / attackN else 1f
            val relax = if (bite > 0.0 && i < biteEnd) exp(-t / tau) else 0.0
            val release01 = when {
                i < holdN -> 1f
                i < liftAt -> 1f - (i - holdN).toFloat() / rampN
                else -> 0f
            }
            val v = Arco.V_SUSTAIN * ramp * (1f + (bite * relax).toFloat()) * release01
            val pNow = p + (pBite - p) * relax.toFloat()
            if (i == liftAt) {
                bow.lift()
                bow.gain(Arco.stopScale(hz, corner, stopN.toFloat() / rate, rate))
            }
            out[i] = bow.next(v, 5f - 4f * pNow)
        }
        return Arco.vibrato(out, Arco.vibratoCentsFor(holdN.toFloat() / rate), rate, Arco.VIBRATO_PLAIN)
    }

    // ---- the present engine, with the bite overridable ----------------------

    /**
     * A one-shot ARCO note: [Variant.old] is [oldRender]; otherwise it is [Arco.render]'s own path (the bow, [Arco.withBody], the finish)
     * with BOW's bite replaced by [Variant.overshoot] and [Variant.biteSeconds] when given. With neither it is [Arco.render] to the sample
     * ([checkReRender]).
     */
    private fun renderWith(voice: ArcoVoice, macros: Map<String, Float>, variant: Variant): Snip {
        if (variant.old) return oldRender(voice, macros)
        val m = Arco.settled(macros, voice)
        require(!Arco.isLoop(m.getValue("HOLD"))) { "renderWith is for one-shots" }
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val raw = Arco.bow(
            voice, Arco.frequencyFor(voice, m.getValue("TUNE")), m, rate,
            overshoot = variant.overshoot, biteSeconds = variant.biteSeconds,
        )
        return Snip(Arco.finish(Arco.withBody(raw, voice, m.getValue("BODY"), rate), rate), channels = 1, sampleRate = Dsp.RATE)
    }

    // ---- the checks ---------------------------------------------------------

    private fun same(a: Snip, b: Snip) = a.samples.contentEquals(b.samples)

    private fun same(a: FloatArray, b: FloatArray) = a.contentEquals(b)

    /** The sample at which two renders first differ, or -1 when they are one. */
    private fun firstDifference(a: FloatArray, b: FloatArray): Int {
        for (i in 0 until min(a.size, b.size)) if (a[i] != b[i]) return i
        return if (a.size == b.size) -1 else min(a.size, b.size)
    }

    /**
     * This page's own render path is the engine's, to the sample, for every macro set a NOW clip uses (and ERHU's default and held notes), and
     * ERHU's BOW 1 with the old bite as overrides (`overshoot = 1.75f, biteSeconds = 0f`) is the engine's ERHU BOW 1: ERHU is unchanged.
     */
    private fun checkReRender() {
        val nowSets = listOf(
            CELLO to emptyMap<String, Float>(), ERHU to emptyMap(),
            CELLO to mapOf("BODY" to 1f), ERHU to mapOf("BODY" to 1f),
            CELLO to mapOf("BOW" to 1f), CELLO to mapOf("BOW" to 1f, "TUNE" to 0f),
            CELLO to mapOf("HOLD" to HELD_HOLD), CELLO to mapOf("HOLD" to HELD_HOLD, "TUNE" to 0f), ERHU to mapOf("HOLD" to HELD_HOLD),
        )
        for ((voice, macros) in nowSets) {
            check(same(renderWith(voice, macros, NOW), Arco.render(voice, macros))) { "this page's re-render of $voice $macros is not what Arco.render makes" }
        }
        check(same(renderWith(ERHU, mapOf("BOW" to 1f), Variant(overshoot = OLD_OVERSHOOT_MAX, biteSeconds = 0f)), Arco.render(ERHU, mapOf("BOW" to 1f)))) {
            "ERHU's BOW 1 with the old bite as overrides is not the engine's ERHU BOW 1: ERHU changed"
        }
        check(OLD_OVERSHOOT_MAX == Arco.OVERSHOOT_MAX_ERHU && Arco.BITE_SECONDS_ERHU == 0f) { "ERHU's bite is no longer R1b's" }
    }

    /**
     * The old-engine copy agrees with the present engine wherever R1c did not touch it: ERHU at every BOW, HOLD, BODY at or under the knee
     * and TUNE tried; and CELLO where the bow has no bite (BOW .5) with the plain vibrato. It must differ where
     * R1c changed something (BODY 1 in both voices; CELLO BOW 1), or the BEFORE clips would be the NOW clips under another name.
     */
    private fun checkOldEngine() {
        val untouched = listOf(
            ERHU to emptyMap<String, Float>(), ERHU to mapOf("BOW" to 1f), ERHU to mapOf("BOW" to 0.8f, "HOLD" to HELD_HOLD, "TUNE" to 0.9f),
            ERHU to mapOf("HOLD" to HELD_HOLD), ERHU to mapOf("BODY" to Arco.BODY_KNEE, "GRIP" to 1f),
        )
        for ((voice, macros) in untouched) {
            check(same(oldRender(voice, macros), Arco.render(voice, macros))) { "the old-engine copy is not the engine for $voice $macros, which R1c did not touch" }
        }
        // CELLO with no bite and a plain vibrato: the engine with VIBRATO_PLAIN stands in for the old one (BOW .5 has no bite, so the
        // pressure's share of it does not enter).
        for (macros in listOf(emptyMap(), mapOf("HOLD" to HELD_HOLD), mapOf("HOLD" to HELD_HOLD, "TUNE" to 0f), mapOf("GRIP" to 1f))) {
            val m = Arco.settled(macros, CELLO)
            val rate = Dsp.RATE * Dsp.OVERSAMPLE
            val hz = Arco.frequencyFor(CELLO, m.getValue("TUNE"))
            check(same(oldBow(CELLO, hz, m, rate), Arco.bow(CELLO, hz, m, rate, vibratoShape = Arco.VIBRATO_PLAIN))) {
                "the old bow is not the engine's bow at CELLO $macros with a plain vibrato and no bite"
            }
        }
        for (voice in listOf(CELLO, ERHU)) {
            check(!same(oldRender(voice, mapOf("BODY" to 1f)), Arco.render(voice, mapOf("BODY" to 1f)))) { "$voice BODY 1 BEFORE is the same as NOW" }
        }
        check(!same(oldRender(CELLO, mapOf("BOW" to 1f)), Arco.render(CELLO, mapOf("BOW" to 1f)))) { "CELLO BOW 1 BEFORE is the same as NOW" }
        checkOverridesCannotMakeTheOldStroke()
        check(!same(oldRender(CELLO, mapOf("HOLD" to HELD_HOLD)), Arco.render(CELLO, mapOf("HOLD" to HELD_HOLD)))) { "CELLO's held note BEFORE is the same as NOW" }
        println("ARCO retune checks: the page's render path is Arco.render to the sample, ERHU is as it was, the old-engine copy is held to the engine where R1c changed nothing")
    }

    /**
     * Why [oldBow] is a copy and not the engine's own overrides: CELLO's BOW 1 with `overshoot = 1.75f, biteSeconds = 0f` and the plain
     * vibrato is not the old stroke, because [Arco.bow] takes the pressure's share of the bite as `(over - 1) / ([Arco.overshootMax] - 1)`
     * and CELLO's is now 3.0, so 1.75 reaches 0.375 of the way up the pressure window where the old engine went all the way. This is the
     * negative control for the copy: should the engine ever grow an override that does reproduce the old stroke, this fails and the copy can go.
     */
    private fun checkOverridesCannotMakeTheOldStroke() {
        val m = Arco.settled(mapOf("BOW" to 1f), CELLO)
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val hz = Arco.frequencyFor(CELLO, m.getValue("TUNE"))
        // The raw string, before the box and the finish: the finish levels the whole clip, so a difference anywhere moves every finished sample.
        val copy = oldBow(CELLO, hz, m, rate)
        val viaOverrides = Arco.bow(CELLO, hz, m, rate, overshoot = OLD_OVERSHOOT_MAX, biteSeconds = 0f, vibratoShape = Arco.VIBRATO_PLAIN)
        val first = firstDifference(copy, viaOverrides)
        var peak = 0f
        var worst = 0f
        for (i in copy.indices) {
            peak = max(peak, abs(copy[i]))
            worst = max(worst, abs(copy[i] - viaOverrides[i]))
        }
        println(
            "ARCO retune probe: CELLO BOW 1, the old bite as the engine's overrides against the old-engine copy, raw string: first differs at sample $first " +
                "of ${copy.size} (${f3(first.toFloat() / rate)} s), largest difference ${f3(worst / peak)} of the raw peak; the pressure's share of the bite is " +
                "${f3((OLD_OVERSHOOT_MAX - 1f) / (Arco.overshootMax(CELLO) - 1f))} by the overrides and 1.000 in the old engine",
        )
        check(first >= 0) { "the engine's overrides now make the old CELLO BOW 1 exactly: oldBow is no longer needed for it" }
    }

    // ---- the cards ----------------------------------------------------------

    private fun note(voice: ArcoVoice, tune: Float) = noteName(Arco.midiFor(voice, tune))

    private fun tag(voice: ArcoVoice, tune: Float) = note(voice, tune).lowercase().replace('#', 's')

    private fun defaultTune(voice: ArcoVoice) = Arco.defaults(voice).getValue("TUNE")

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/arco-retune")
        root.mkdirs()
        var count = 0
        fun write(dir: String, id: String, snip: Snip) {
            val levelled = AuditionLevel.level(snip)
            WavWriter.write(File(File(root, dir), "$id.wav"), levelled, WavWriter.BitDepth.PCM_16)
            var at = 0
            for (i in snip.samples.indices) if (abs(snip.samples[i]) > abs(snip.samples[at])) at = i
            val gain = levelled.samples[at] / snip.samples[at]
            val peak = abs(levelled.samples[at])
            check(peak in 0.001f..0.999f) { "$dir/$id has a peak of $peak" }
            println(
                "ARCO retune clip $dir/$id: ${f2(snip.samples.size.toFloat() / Dsp.RATE)} s, loudness ${f3(Loudness.of(snip))} before levelling " +
                    "(gain ${f2(gain)}), ${f3(Loudness.of(levelled))} after, peak ${f3(peak)}",
            )
            count++
        }

        checkReRender()
        checkOldEngine()

        val sections = listOf(bodySection(::write), bowSection(::write), vibratoSection(::write))

        // The manifest is the only place the clip list lives; every clip in it must be a file on disk.
        for (section in sections) for (group in section.groups) for (clip in group.clips) {
            check(File(File(root, section.id), clip.id + ".wav").isFile) { "the manifest lists ${section.id}/${clip.id}.wav and it was not written" }
        }
        check(count == sections.sumOf { s -> s.groups.sumOf { it.clips.size } }) { "the clips written are not the clips listed" }

        val pageText = (ArcoRetuneGenerator::class.java.getResourceAsStream("/audition/arco-retune.html")
            ?: error("the re-listen page is missing from synth/src/test/resources/audition/")).use { it.readBytes() }.toString(Charsets.UTF_8)
        for ((key, value) in FACTS) {
            check(pageText.contains("data-fact=\"$key\">$value<")) { "the page's own text for $key does not say $value: the page and the engine have drifted" }
        }

        File(root, "manifest.json").writeText(
            "{\"surfaceAfter\":null,\"voices\": [\n" + sections.joinToString(",\n") { sectionJson(it) } + "\n],\"loops\": []," +
                "\"intro\":[" + introBlocks().joinToString(",") { blockJson(it) } + "]," +
                "\"facts\":{" + FACTS.entries.joinToString(",") { "${q(it.key)}:${q(it.value)}" } + "}}\n",
        )
        File(root, "index.html").writeText(pageText)
        println("wrote $count clips + manifest.json + index.html under ${root.absolutePath}")
    }

    /** The numbers the page's own question text carries, which the page fills from the manifest and which are checked against the page's fallback above. */
    private val FACTS: Map<String, String> = mapOf("bodyTop" to f2(Arco.BODY_TOP))

    private fun bodySection(write: (String, String, Snip) -> Unit): Section {
        val groups = listOf(CELLO, ERHU).map { voice ->
            val tune = defaultTune(voice)
            val where = note(voice, tune)
            val t = voice.name.lowercase()
            val default = Arco.render(voice)
            val before = renderWith(voice, mapOf("BODY" to 1f), BEFORE)
            val now = Arco.render(voice, mapOf("BODY" to 1f))
            write("BODY", t + "_default", default)
            write("BODY", t + "_before", before)
            write("BODY", t + "_now", now)
            Group(
                "${voice.name}, $where (THE DEFAULT NOTE)",
                listOf(
                    Clip(t + "_default", "BODY .5 $DOT THE DEFAULT", "the default knobs: the box rings ${f2(Arco.boxAmountFor(Arco.DEFAULT_BODY))} times as loud as the string, as it always did"),
                    Clip(t + "_before", "BODY 1 $DOT BEFORE", "what you heard: the box as loud as the string, 1.00 times"),
                    Clip(t + "_now", "BODY 1 $DOT NOW", "the box ${f2(Arco.boxAmountFor(1f))} times the string, ${f1(20f * log10(Arco.boxAmountFor(1f)))} dB more box than BEFORE"),
                ),
            )
        }
        return Section(
            id = "BODY", display = "BODY", body = "both voices: the box, at the top of the knob",
            readout = listOf(
                "CELLO ${note(CELLO, defaultTune(CELLO))} $DOT ERHU ${note(ERHU, defaultTune(ERHU))} $DOT EVERY OTHER KNOB AT ITS DEFAULT",
                "BODY .5 $DOT BODY 1 BEFORE $DOT BODY 1 NOW",
            ),
            groups = groups,
        )
    }

    private fun bowSection(write: (String, String, Snip) -> Unit): Section {
        val groups = listOf(defaultTune(CELLO), 0f).map { tune ->
            val where = note(CELLO, tune)
            val macros = mapOf("BOW" to 1f, "TUNE" to tune)
            val t = "${tag(CELLO, tune)}"
            write("BOW", t + "_before", renderWith(CELLO, macros, BEFORE))
            write("BOW", t + "_now", Arco.render(CELLO, macros))
            write("BOW", t + "_stronger", renderWith(CELLO, macros, Variant(overshoot = STRONGER_OVERSHOOT, biteSeconds = STRONGER_BITE_SECONDS)))
            val label = if (tune == 0f) "CELLO, $where (THE ROOT)" else "CELLO, $where (THE DEFAULT NOTE)"
            Group(
                label,
                listOf(
                    Clip(
                        t + "_before", "BOW 1 $DOT BEFORE",
                        "what you heard: the bow starts ${f2(OLD_OVERSHOOT_MAX)} times too fast and presses at the top of the window, and the bite is gone in ${ms(Arco.attackSeconds(1f))} ms (the stroke's own time)",
                    ),
                    Clip(
                        t + "_now", "BOW 1 $DOT NOW",
                        "the engine now: ${f2(Arco.OVERSHOOT_MAX_CELLO)} times too fast, the same pressure, and the bite takes at least ${ms(Arco.BITE_SECONDS_CELLO)} ms to settle",
                    ),
                    Clip(
                        t + "_stronger", "BOW 1 $DOT STRONGER",
                        "not in the engine, to calibrate: ${f2(STRONGER_OVERSHOOT)} times too fast, at least ${ms(STRONGER_BITE_SECONDS)} ms to settle. If NOW is still not enough, this is the next step",
                    ),
                ),
            )
        }
        return Section(
            id = "BOW", display = "BOW", body = "CELLO only: BOW 1, the stab with a bite",
            readout = listOf(
                "BOW 1 $DOT EVERY OTHER KNOB AT ITS DEFAULT",
                "${note(CELLO, defaultTune(CELLO))} THE DEFAULT NOTE $DOT ${note(CELLO, 0f)} THE ROOT",
            ),
            groups = groups,
        )
    }

    private fun vibratoSection(write: (String, String, Snip) -> Unit): Section {
        val held = HELD_HOLD
        val groups = listOf(defaultTune(CELLO), 0f).map { tune ->
            val where = note(CELLO, tune)
            val macros = mapOf("HOLD" to held, "TUNE" to tune)
            val t = tag(CELLO, tune)
            write("VIBRATO", t + "_before", renderWith(CELLO, macros, BEFORE))
            write("VIBRATO", t + "_now", Arco.render(CELLO, macros))
            val h = Arco.VIBRATO_HUMAN
            Group(
                if (tune == 0f) "CELLO, $where (THE ROOT)" else "CELLO, $where (THE DEFAULT NOTE)",
                listOf(
                    Clip(
                        t + "_before", "VIBRATO $DOT BEFORE",
                        "what you heard: a constant ${Arco.VIBRATO_MAX_CENTS.roundToInt()} cents at ${f1(Arco.VIBRATO_HZ.toFloat())} Hz, every swing the same, fully in after ${f1(Arco.VIBRATO_RISE_SECONDS.toFloat())} s",
                    ),
                    Clip(
                        t + "_now", "VIBRATO $DOT NOW",
                        "the rate drifts by up to ${pct(h.rateWander)}, the depth by up to ${pct(h.depthWander)}, a slight lean, a swell-in of ${f1(h.riseSeconds.toFloat())} s; ${Arco.VIBRATO_MAX_CENTS.roundToInt()} cents at ${f1(Arco.VIBRATO_HZ.toFloat())} Hz on average",
                    ),
                ),
            )
        }
        val erhuTune = defaultTune(ERHU)
        write("VIBRATO", "erhu_unchanged", Arco.render(ERHU, mapOf("HOLD" to held)))
        val control = Group(
            "ERHU, ${note(ERHU, erhuTune)} (A CONTROL)",
            listOf(
                Clip(
                    "erhu_unchanged", "ERHU $DOT UNCHANGED",
                    "the same held note on ERHU, as it was and on purpose: you said its vibrato was a yes, and it is the plain ${Arco.VIBRATO_MAX_CENTS.roundToInt()} cents at ${f1(Arco.VIBRATO_HZ.toFloat())} Hz still",
                ),
            ),
        )
        return Section(
            id = "VIBRATO", display = "VIBRATO", body = "CELLO only: a player, not a machine",
            readout = listOf(
                "HOLD ${fmt(held)} $DOT ${f1(HELD_SECONDS)} S OF BOW $DOT EVERY OTHER KNOB AT ITS DEFAULT",
                "${note(CELLO, defaultTune(CELLO))} $DOT ${note(CELLO, 0f)} $DOT ERHU: THE CONTROL",
            ),
            groups = groups + control,
        )
    }

    /** The page's two guide cards that carry numbers: what changed and what did not, from the constants so the text cannot drift. */
    private fun introBlocks(): List<Block> {
        val h = Arco.VIBRATO_HUMAN
        val defaultHold = Arco.defaults(CELLO).getValue("HOLD")
        val defaultCents = Arco.vibratoCentsFor(Arco.holdSeconds(defaultHold))
        val lo = Arco.VIBRATO_MAX_CENTS * (1f - h.depthWander.toFloat())
        val hi = Arco.VIBRATO_MAX_CENTS * (1f + h.depthWander.toFloat())
        val oneSecondBite = ms(Arco.attackSeconds(1f))
        return listOf(
            Block(
                "WHAT CHANGED",
                listOf(
                    "BODY is as it was up to ${fmt(Arco.BODY_KNEE)}, where the box rings ${f2(Arco.BODY_KNEE)} times as loud as the string. Above that it climbs to ${f2(Arco.BODY_TOP)} times the string at BODY 1, in both voices. It was 1.00 times, so ${f1(20f * log10(Arco.BODY_TOP))} dB more box.",
                    "CELLO's BOW 1: the bite is ${f2(Arco.OVERSHOOT_MAX_CELLO)} times the sustain speed (it was ${f2(OLD_OVERSHOOT_MAX)}) and takes at least ${ms(Arco.BITE_SECONDS_CELLO)} ms to settle (it was the stroke's own $oneSecondBite ms). A bass string needs many milliseconds to build a period, and a bite over in $oneSecondBite ms was gone before it did. At BOW ${fmt(Arco.OVERSHOOT_FROM)} and under there is still no bite.",
                    "CELLO's vibrato was a plain sine, ${Arco.VIBRATO_MAX_CENTS.roundToInt()} cents at ${f1(Arco.VIBRATO_HZ.toFloat())} Hz, every swing the same, in over ${f1(Arco.VIBRATO_RISE_SECONDS.toFloat())} s. Now the rate drifts by up to ${pct(h.rateWander)} and the depth by up to ${pct(h.depthWander)} (swings of about ${lo.roundToInt()} to ${hi.roundToInt()} cents), the swing leans a little (a second harmonic of ${f2(h.skew.toFloat())}), and it swells in over ${f1(h.riseSeconds.toFloat())} s. The drift depends on time alone, so a note renders the same every time.",
                ),
            ),
            Block(
                "WHAT DID NOT",
                listOf(
                    "ERHU's bite (${f2(Arco.OVERSHOOT_MAX_ERHU)} times, in the stroke's own time), ERHU's vibrato (the plain sine) and ERHU's box shape. Every knob's default. The box at or under BODY ${fmt(Arco.BODY_KNEE)}, so every preset at or under it keeps its box to the bit.",
                    "Two things to know. CELLO's default note is ${f2(Arco.holdSeconds(defaultHold))} s of bow and carries ${f1(defaultCents)} cents of vibrato, so the CELLO default clip has the new drift in it too, far too faint to matter. CELLO presets with a BOW above ${fmt(Arco.OVERSHOOT_FROM)} get the new bite, and those held over ${f1(Arco.VIBRATO_HOLD_FROM_SECONDS)} s the new vibrato; this page does not play them.",
                    "The BEFORE clips are the old engine made again from its own numbers, with the old vibrato.",
                ),
            ),
        )
    }

    // ---- words --------------------------------------------------------------

    private const val DOT = "·"

    private fun f1(v: Float) = "%.1f".format(Locale.ROOT, v)
    private fun f2(v: Float) = "%.2f".format(Locale.ROOT, v)
    private fun f3(v: Float) = "%.3f".format(Locale.ROOT, v)
    private fun ms(seconds: Float) = (seconds * 1000f).roundToInt()
    private fun pct(share: Double) = "${(share * 100).roundToInt()} percent"

    private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** MIDI note to its name, middle C (60) being C4. */
    private fun noteName(midi: Int) = NOTE_NAMES[midi % 12] + (midi / 12 - 1)

    /** A macro value the way the app's sliders read it: `0`, `.35`, `1`. */
    private fun fmt(v: Float) = when {
        v <= 0f -> "0"
        v >= 1f -> "1"
        else -> "." + (v * 100).roundToInt().toString().padStart(2, '0')
    }

    private fun sectionJson(s: Section): String {
        val g = s.groups.joinToString(",") { grp ->
            val c = grp.clips.joinToString(",") { clip -> "[${q(clip.id)},${q(clip.name)},${q(clip.desc)}]" }
            "{\"label\":${q(grp.label)},\"key\":true,\"clips\":[$c]}"
        }
        val r = s.readout.joinToString(",") { q(it) }
        return "{\"id\":${q(s.id)},\"display\":${q(s.display)},\"body\":${q(s.body)},\"readout\":[$r],\"groups\":[$g]}"
    }

    private fun blockJson(b: Block) = "{\"hd\":${q(b.hd)},\"p\":[" + b.paragraphs.joinToString(",") { q(it) } + "]}"

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
