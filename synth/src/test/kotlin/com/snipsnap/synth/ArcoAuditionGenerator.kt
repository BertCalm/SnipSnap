package com.snipsnap.synth

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Renders the ARCO audition (docs/superpowers/specs/2026-09-29-arco-bowed-string-engine-design.md,
 * the R1 gate) under testkit/arco-audition/ (gitignored). Clips share one loudness ([AuditionLevel]).
 * Writes `manifest.json`, which the page builds itself from, so the clip list lives here and nowhere
 * else, then copies the listening page from the test resources. Run via
 * `./gradlew :synth:generateArcoAudition`, then publish the folder as the listening artifact.
 *
 * **The pass rule, and so the first card.** The owner decided the engine passes or fails on a
 * three-second held note, bowed against a synth, not on a one-second stab: if you cannot pick the
 * bowed note out from the synth, or the bowed note itself sounds like a synth, the engine fails. So
 * the page opens with that, and the stab check is second. For each voice, at three TUNEs (the root,
 * the middle and a high step: 0, 0.5 and 0.9 of the knob), three unlabelled clips of three seconds of
 * bow each, in a different order in each of the six sets, with the answer only in the manifest (the
 * page shows it on REVEAL, after a pick and a confidence are in):
 *
 *  - **Bowed** is this engine at HOLD [Arco.holdFor] of three seconds (0.88) with every other macro
 *    at its default, so it is what a pad on the default knobs held for three seconds sounds like, vibrato
 *    and all. The file is longer than three seconds because the render keeps the string's stop (a release of up
 *    to 1.5 s at C2). The stop is 60 dB down at 0.63 to 0.89 of the release, so the last stretch of the file is
 *    silent and the audible end is earlier still ([audibleEnd] reads it): the other two clips are cut or padded to
 *    the same frame count.
 *  - **CELLO's plucked comparator is SILK's OUD**, in the CHROMATIC scale at DAMP 0 (it rings as long as it can)
 *    and COURSE 0 (one string, not a pair beating). Why it and not PLUCK: PLUCK's lowest root is A2 (110 Hz)
 *    and CELLO's TUNE starts at C2 (65.4 Hz), so no PLUCK voice reaches the root step; OUD's own root is
 *    C2 (65.41 Hz), so with CHROMATIC (twelve equal steps) every CELLO step lands on the same note to a
 *    fraction of a cent. A plucked string cannot sustain, and is not made to: it rings as long as it
 *    rings and is padded with silence to the length of the bowed clip, so the decay is the tell it should be.
 *  - **ERHU's sung comparator is VOX's CHOIR** at DECAY 1, its longest note: the vowel is held for 1.8 s
 *    (60 percent of the 3.0 s DECAY 1 gives) and then fades, so it cannot hold the full three seconds as the
 *    others do and is the one that stops early. It is folded to mono because the choir is stereo and the
 *    bowed note is not. CHOIR's TUNE stops at A4 (its root is A2, two octaves of travel) and ERHU's middle
 *    and high steps (C5, G5) are above that, so those two sets take the same note an octave down (the
 *    choir's own sopranos sing an octave above the note, so the top of the choir still reaches ERHU's
 *    note). The answer key says so.
 *  - **The synth comparator is RESIN's held pad** ([Resin.renderHeld], the renderer MAKE INSTRUMENT holds
 *    keys with): a three-oscillator saw stack through the ladder, flat for the whole three seconds, BASS for
 *    CELLO (nearly the cello's range) and LEAD for ERHU, at the exact pitch (`baseHz`). VELVET cannot be the
 *    held synth: its longest note is a 0.9 s decay.
 *
 *    The synth's onset is matched to the bowed clip's, not left at the bow's stroke. The stroke
 *    ([Arco.attackSeconds], 63 ms at the default BOW) is only how fast the bow's speed rises; the string takes
 *    longer than that to speak up, and a synth that reached full level in 63 ms would give the bowed clip away in
 *    its first half second. ArcoTest's onset table (BOW 0.5, no box, no vibrato) has the string at 90 percent of its
 *    level after about 0.12 s at ERHU's A5 and 0.55 s at CELLO's C2. So for each set this generator measures the
 *    bowed clip's time to 90 percent of its steady level ([secondsToNinety]: 25 ms windows, the steady level read
 *    from 2.0 to 2.8 s) and gives the synth an attack that reaches 90 percent at the same time: set first from
 *    the arithmetic of a straight ramp, then corrected once from the synth's own measured time, and kept inside
 *    what [Resin.Held] takes. The log prints both times for every set. The synth also lets go where the bowed clip's
 *    own tail falls out of hearing ([audibleEnd]), so the length does not tell them apart either.
 *
 *    What still differs, and the page says so: the synth has no vibrato, no chorus and no noise (plain on purpose,
 *    because those would be new machinery on this page), so the bowed note's vibrato is a tell, and the vibrato A/B
 *    card at the end of each voice is where it is judged; the synth rises in a straight line, so only its time to
 *    90 percent is matched and not the shape of the rise; the plucked comparator begins with a strike and the sung
 *    one with a vowel, each its own onset by nature; and the sung clip holds its vowel for 1.8 s and is 40 dB down
 *    by 2.6 s, where the others hold three seconds.
 *
 * The three clips of a set have exactly the same frame count, every clip goes through [AuditionLevel.level],
 * and the file names carry only the voice, the TUNE and the letter. The page takes the labels from the
 * manifest's fourth and fifth entry of each clip and the answer from the group.
 *
 * **The stab check** is second. It follows the audition spec's rules (docs/AUDITION_SPEC_2026_09.md,
 * lines 69-116: in the pattern, alternating on the bar, level-matched) as simply as the test helpers
 * allow, which is not very: nothing here mixed clips, so [place] is the smallest mixer, a sum of hits at
 * sample offsets that wraps round the end of the clip (so REPEAT loops the pattern with its last tails
 * ringing into the first bar, as a drum machine does). Four candidates play one two-bar figure (C3, C3, E-flat3,
 * G3, G3, E-flat3, C3, as eighth notes at 90 bpm) with THUMP's snare on the backbeat: CELLO's SHORT STAB at BOW 1
 * and at BOW 0.5, VELVET's BRASS STAB and RESIN's PUNCHY STAB, each at the figure's own pitches (the presets'
 * TUNE is replaced; their other macros are the presets'). Each candidate's stabs are levelled
 * on their own, by the gain [AuditionLevel.level] gives that candidate's stab-only two bars, before the
 * snare (the same hits at a fixed share of the stabs' level, the same on every clip) is added, so
 * the match is between the stabs and the snare cannot move it. The second group alternates on the bar line:
 * bar one one candidate, bar two the other. These clips are labelled; the question here is not whether you
 * can tell, but whether the cello stab earns its place beside a brass one.
 *
 * Then, in the page's order: the SURFACE stand-in (the two LOOPs held under a finger, the manifest's
 * `loops` array and its `surfaceAfter` card id), the kit as it lands ([SynthKits.arco]), and for each voice its
 * default, BOW, GRIP, BODY and HOLD at both ends with the rest at their defaults, its eight presets, its LOOP and the
 * held note with and without vibrato. ERHU's BODY is also three unlabelled clips on one note: the
 * membrane box as shipped, no box, and one placeholder resonator row (a single resonance at twice the open string
 * with a short ring, authored for this question, not sourced). Those two are rendered here with [Strings.bodyRing]
 * on [Arco.bow] and [Arco.finish], as [Arco.render] does, and the generator checks that the shipped
 * path through its own helper is [Arco.render] to the sample, so the other two are the engine with one thing changed.
 *
 * Nothing in ARCO has been heard by anyone when this is first run: it is the gate that decides whether the
 * measured engine is a bowed string. The page says so.
 */
object ArcoAuditionGenerator {

    private class Knob(val name: String, val low: (ArcoVoice) -> String, val high: (ArcoVoice) -> String)

    /**
     * The TUNE step a voice's default note is on. The knob clips are rendered there, so GRIP's pressure is read
     * there: CELLO's floor depends on the note (0.97 at C2, 0.85 from F#2 up) and ERHU's is 0.94 at every step.
     */
    private fun defaultSemitone(voice: ArcoVoice) = Arco.semitoneFor(voice, Arco.defaults(voice).getValue("TUNE"))

    /**
     * The knobs at both ends, in the order the SYNTH screen lists them, each end's text built for the voice it is
     * shown under. TUNE is a note, not a knob to audition. HOLD's top end is a LOOP, so it has its own clip and this
     * one stops short of it.
     */
    private val KNOBS = listOf(
        Knob(
            "BOW",
            { "a slow bow: ${ms(Arco.attackSeconds(0f))} ms to full speed, no bite" },
            { voice -> "a stab with a bite: ${ms(Arco.attackSeconds(1f))} ms to full speed, the bow starts ${f2(Arco.overshootMax(voice))} times too fast, ${pressureClause(Arco.bitePressure(voice))}, and the excess relaxes with a ${ms(maxOf(Arco.attackSeconds(1f), Arco.biteSeconds(voice)))} ms time constant" },
        ),
        Knob(
            "GRIP",
            { voice -> "a light grip: the lowest bow pressure this note takes (${f2(Arco.pressureFor(voice, defaultSemitone(voice), 0f))}) and the bridge closed down, a dark, close-held string" },
            { voice -> "digging in: full bow pressure (${f2(Arco.pressureFor(voice, defaultSemitone(voice), 1f))}) and the bridge opened up, the brightest the string goes" },
        ),
        Knob("BODY", { "the string alone, no box" }, { "the box: its ring ${f2(Arco.BODY_TOP)} times as loud as the string itself (the knob is as it was up to ${f2(Arco.BODY_KNEE)}, where the box is ${f2(Arco.BODY_KNEE)} times the string)" }),
        Knob(
            "HOLD",
            { "the shortest bow: ${f1(Arco.HOLD_MIN_SECONDS)} s of note" },
            { "the longest one-shot: ${f1(Arco.holdSeconds(Arco.SCRAMBLE_HOLD_CEILING))} s of bow, with its vibrato; one step above is the LOOP, which has its own clip" },
        ),
    )

    /** What the bite's pressure share does, as the BOW line says it: ERHU's is all of it (as it was), CELLO's is [Arco.BITE_PRESSURE_CELLO] of it. */
    private fun pressureClause(share: Float) = when {
        share >= 1f -> "all of it presses the string toward the top of the window"
        abs(share - 0.5f) < 1e-4f -> "half of it presses the string toward the top of the window"
        abs(share - 0.25f) < 1e-4f -> "a quarter of it presses the string toward the top of the window"
        else -> "${(share * 100).roundToInt()} percent of it presses the string toward the top of the window"
    }

    /** The vibrato clip's caption, from the voice's own finger ([Arco.vibratoShapeFor]): CELLO's drifts and swells in, ERHU's is the constant sine. */
    private fun vibratoCaption(voice: ArcoVoice): String {
        val shape = Arco.vibratoShapeFor(voice)
        val cents = Arco.VIBRATO_MAX_CENTS.roundToInt()
        val hz = f1(Arco.VIBRATO_HZ.toFloat())
        val full = f1(Arco.VIBRATO_HOLD_FULL_SECONDS)
        return if (shape.rateWander == 0.0 && shape.depthWander == 0.0 && shape.skew == 0.0) {
            "the default: ${f1(HELD_SECONDS)} s of bow with the baked-in rock of $cents cents at $hz Hz, the same at every swing, fully in at $full s of bow"
        } else {
            "the default: ${f1(HELD_SECONDS)} s of bow with the baked-in vibrato of $cents cents at $hz Hz on average, the rate drifting by up to ${(shape.rateWander * 100).roundToInt()} percent " +
                "and the depth by up to ${(shape.depthWander * 100).roundToInt()} percent, a slight lean, a swell-in over ${f1(shape.riseSeconds.toFloat())} s, fully in at $full s of bow"
        }
    }

    private val BODIES = mapOf(
        ArcoVoice.CELLO to "a bow dragging the bottom string, into a box with an air hum and a plate hum: a low, slow-building, woody note",
        ArcoVoice.ERHU to "a bow on a fiddle's inner string, into a small skin box: a high, reedy, nasal line",
    )

    private class Clip(val id: String, val name: String, val desc: String, val trueName: String? = null, val trueDesc: String? = null)
    private class Blind(val pick: String, val answer: String?)
    private class Group(val label: String, val key: Boolean, val clips: List<Clip>, val blind: Blind? = null)
    private class Section(val id: String, val display: String, val body: String, val readout: List<String>, val groups: List<Group>)

    // ---- the held note ------------------------------------------------------

    /** The held note the pass rule is about: three seconds of bow. */
    private const val HELD_SECONDS = 3f

    /** The root, the middle and a high step of each voice's TUNE. */
    private val HELD_TUNES = listOf(0f, 0.5f, 0.9f)

    /** HOLD for [HELD_SECONDS] of bow: 0.88. */
    private val HELD_HOLD = Arco.holdFor(HELD_SECONDS)

    private enum class Kind { BOWED, OTHER, SYNTH }

    /**
     * Which kind sits at the letters A, B and C in each of the six sets, in the order CELLO root, middle, high,
     * ERHU root, middle, high: six different orders, so the bowed clip sits at each letter twice and no position
     * is a hint. Fixed, not random, so the page's answer key is the same every run.
     */
    private val HELD_ORDERS = listOf(
        listOf(Kind.OTHER, Kind.BOWED, Kind.SYNTH),
        listOf(Kind.SYNTH, Kind.OTHER, Kind.BOWED),
        listOf(Kind.BOWED, Kind.SYNTH, Kind.OTHER),
        listOf(Kind.SYNTH, Kind.BOWED, Kind.OTHER),
        listOf(Kind.OTHER, Kind.SYNTH, Kind.BOWED),
        listOf(Kind.BOWED, Kind.OTHER, Kind.SYNTH),
    )

    /**
     * RESIN's held pad for each voice, as macros: a saw stack with a little of the sub-octave and the detuned
     * square, a mid cutoff, little feedback and a gentle filter envelope, so after the first fraction of a second
     * it is a steady, plain synth note. BASS for CELLO, whose notes (C2 to A-sharp3) sit in BASS's own range
     * (A1 to A3) but for the top one, a semitone over it; LEAD for ERHU (D4 to G5, LEAD's A3 to A5). Authored for a
     * fair comparison, not tuned by ear.
     */
    private val SYNTH_MACROS = mapOf(
        ArcoVoice.CELLO to mapOf("STACK" to 0.6f, "CUTOFF" to 0.5f, "CREAM" to 0.2f, "CONTOUR" to 0.25f, "DECAY" to 0.5f),
        ArcoVoice.ERHU to mapOf("STACK" to 0.35f, "CUTOFF" to 0.55f, "CREAM" to 0.25f, "CONTOUR" to 0.3f, "DECAY" to 0.5f),
    )

    private val SYNTH_VOICES = mapOf(ArcoVoice.CELLO to ResinVoice.BASS, ArcoVoice.ERHU to ResinVoice.LEAD)

    /** VOX CHOIR's root, A2 (110 Hz), and the top of its two octaves of TUNE. */
    private const val CHOIR_ROOT_MIDI = 45
    private const val CHOIR_TOP_MIDI = CHOIR_ROOT_MIDI + Vox.TUNE_SEMITONES

    /** The one placeholder resonator row for ERHU's BODY question: twice the open string, a short ring, authored here. */
    private const val PLACEHOLDER_ROW_HZ = 2f * Arco.ERHU_BODY_ANCHOR_HZ
    private const val PLACEHOLDER_ROW_T60 = 0.1f

    /** What each of a set's three clips is made of, for the answer key. */
    private class HeldSet(val clips: Map<Kind, Snip>, val trueNames: Map<Kind, String>, val trueDescs: Map<Kind, String>)

    /** The three clips of one set, all with [Arco.render]'s own frame count, before they are levelled. */
    private fun heldSet(voice: ArcoVoice, tune: Float): HeldSet {
        val bowed = Arco.render(voice, mapOf("TUNE" to tune, "HOLD" to HELD_HOLD))
        val frames = bowed.samples.size
        val midi = Arco.midiFor(voice, tune)
        val hz = Arco.frequencyFor(voice, tune)
        val note = noteName(midi)

        // The synth lets go where the bowed note falls out of hearing, not where the bow's stop ends: see [audibleEnd].
        val end = maxOf(audibleEnd(bowed.samples), HELD_SECONDS + Arco.RELEASE_RAMP_SECONDS)
        println("ARCO held ${voice.name} $note: the bowed clip is ${f2(frames.toFloat() / Dsp.RATE)} s and falls out of hearing at ${f2(end)} s")

        // The synth rises as slowly as the bowed note does, not in the bow's 63 ms stroke: see [secondsToNinety].
        val bowedRise = secondsToNinety(bowed.samples)
        check(bowedRise > 0f) { "the bowed clip of ${voice.name} $note never reaches 90 percent of its steady level" }
        fun synthWithAttack(attack: Float): FloatArray {
            val raw = Resin.renderHeld(
                SYNTH_VOICES.getValue(voice), SYNTH_MACROS.getValue(voice),
                Resin.Held(attackSeconds = attack, seconds = frames.toFloat() / Dsp.RATE, baseHz = hz.toDouble()),
            ).samples
            return fit(release(raw, HELD_SECONDS, end), frames)
        }
        fun attackRange(seconds: Float) = seconds.coerceIn(Resin.ATTACK_MIN_SECONDS, Resin.ATTACK_MAX_SECONDS)
        // A straight ramp is at 90 percent after nine tenths of its length; the ladder moves that a little, so the
        // synth's own time is measured and the attack corrected once by the ratio.
        var attack = attackRange(bowedRise / 0.9f)
        var synth = synthWithAttack(attack)
        val firstRise = secondsToNinety(synth)
        if (firstRise > 0f) {
            attack = attackRange(attack * bowedRise / firstRise)
            synth = synthWithAttack(attack)
        }
        val synthRise = secondsToNinety(synth)
        println(
            "ARCO held ${voice.name} $note: time to 90 percent of its steady level: the bowed clip ${f2(bowedRise)} s, " +
                "the synth ${f2(synthRise)} s with an attack of ${f2(attack)} s",
        )

        val other: FloatArray
        val otherName: String
        val otherDesc: String
        when (voice) {
            ArcoVoice.CELLO -> {
                val semitone = Arco.semitoneFor(voice, tune)
                val oudTune = semitone / Arco.CELLO_TUNE_SEMITONES.toFloat()
                val macros = mapOf("TUNE" to oudTune, "SCALE" to 0f, "DAMP" to 0f, "COURSE" to 0f)
                check(abs(FineTuning.cents(Silk.frequencyFor(SilkVoice.OUD, oudTune, 0f).toDouble(), hz.toDouble())) < 1.0) {
                    "the plucked comparator is not on the bowed note at TUNE $tune"
                }
                val plucked = Silk.render(SilkVoice.OUD, macros).samples
                println(
                    "ARCO held CELLO $note: the plucked note rings ${f2(plucked.size.toFloat() / Dsp.RATE)} s of its own, " +
                        "the bowed clip is ${f2(frames.toFloat() / Dsp.RATE)} s",
                )
                other = fit(plucked, frames)
                otherName = "PLUCKED"
                otherDesc = "SILK's OUD in the chromatic scale at $note, DAMP 0 so it rings as long as it can, one string; padded with silence to the bowed clip's length"
            }
            ArcoVoice.ERHU -> {
                var voxMidi = midi
                while (voxMidi > CHOIR_TOP_MIDI) voxMidi -= 12
                val voxTune = (voxMidi - CHOIR_ROOT_MIDI) / Vox.TUNE_SEMITONES.toFloat()
                val choir = Cleanup.toMono(Vox.render(VoxVoice.CHOIR, mapOf("TUNE" to voxTune, "DECAY" to 1f))).samples
                println(
                    "ARCO held ERHU $note: the choir note is ${f2(choir.size.toFloat() / Dsp.RATE)} s at ${noteName(voxMidi)}, " +
                        "the bowed clip is ${f2(frames.toFloat() / Dsp.RATE)} s",
                )
                other = fit(choir, frames)
                otherName = "SUNG"
                otherDesc = "VOX's CHOIR at DECAY 1, its longest note (the vowel held for ${f1(Vox.holdFractionFor(1f) * Vox.lengthFor(1f))} s, then faded), mono, " +
                    (if (voxMidi == midi) "at $note" else "at ${noteName(voxMidi)}, an octave under the bowed note, because the choir's TUNE tops out at A4")
            }
        }

        return HeldSet(
            clips = mapOf(
                Kind.BOWED to bowed,
                Kind.OTHER to Snip(other, channels = 1, sampleRate = Dsp.RATE),
                Kind.SYNTH to Snip(synth, channels = 1, sampleRate = Dsp.RATE),
            ),
            trueNames = mapOf(Kind.BOWED to "BOWED", Kind.OTHER to otherName, Kind.SYNTH to "SYNTH"),
            trueDescs = mapOf(
                Kind.BOWED to "this engine, $note: HOLD ${fmt(HELD_HOLD)} (${f1(HELD_SECONDS)} s of bow), every other knob at its default, the vibrato as shipped",
                Kind.OTHER to otherDesc,
                Kind.SYNTH to "RESIN's held pad (${SYNTH_VOICES.getValue(voice)}), a saw stack through the ladder at $note, rising over ${f2(attack)} s to reach 90 percent of its level when the bowed note does (${f2(bowedRise)} s), " +
                    "flat for ${f1(HELD_SECONDS)} s, then let go over ${f2(end - HELD_SECONDS)} s, where the bowed note's own tail falls away",
            ),
        )
    }

    /** The synth's own stop: flat until [onSeconds], then a straight ramp to nothing at [endSeconds], and nothing after. */
    private fun release(samples: FloatArray, onSeconds: Float, endSeconds: Float): FloatArray {
        val out = samples.copyOf()
        val on = (onSeconds * Dsp.RATE).toInt()
        val end = (endSeconds * Dsp.RATE).toInt().coerceAtLeast(on + 1)
        for (i in out.indices) if (i > on) out[i] *= (1f - (i - on).toFloat() / (end - on)).coerceAtLeast(0f)
        return out
    }

    /**
     * The second at which [samples] have fallen out of hearing: the end of the last 20 ms window within 40 dB of the
     * loudest one. The bow's stop is one constant extra loss on the string from the lift, so the tail falls at a steady
     * rate in dB and is 60 dB down at 0.63 to 0.89 of the release (ArcoTest's test of the stopped tail, which prints it
     * at CELLO C2 to C4 and ERHU D4 to A5), and the render keeps all of the release: a comparator that let go over
     * all of it would be heard to fade after the bowed note had gone, and that would tell them apart for the wrong
     * reason. The log line "falls out of hearing at" prints where this lands for each set.
     */
    private fun audibleEnd(samples: FloatArray): Float {
        val win = (0.02f * Dsp.RATE).toInt()
        val levels = ArrayList<Float>()
        var i = 0
        while (i + win <= samples.size) {
            var acc = 0.0
            for (j in i until i + win) acc += samples[j].toDouble() * samples[j]
            levels += sqrt(acc / win).toFloat()
            i += win
        }
        val loudest = levels.maxOrNull() ?: 0f
        return (levels.indexOfLast { it >= loudest * 0.01f } + 1) * win.toFloat() / Dsp.RATE
    }

    /**
     * The seconds until [samples] first hold 90 percent of their steady level: the centre of the first 25 ms window
     * (taken a window at a time) whose RMS is at least 0.9 of the RMS read from 2.0 to 2.8 s, which is inside the
     * three seconds of bow and past the slowest build of any set. It is the number a bowed note's onset is judged by,
     * and it is the same measure for the bowed clip and for the synth, so the two are compared like with like.
     * Returns -1 when no window gets there.
     */
    private fun secondsToNinety(samples: FloatArray): Float {
        val win = (0.025f * Dsp.RATE).toInt()
        val steady = rmsBetween(samples, 2.0f, 2.8f)
        var i = 0
        while (i + win <= samples.size) {
            var acc = 0.0
            for (j in i until i + win) acc += samples[j].toDouble() * samples[j]
            if (sqrt(acc / win) >= 0.9 * steady) return (i + win / 2).toFloat() / Dsp.RATE
            i += win
        }
        return -1f
    }

    /** [samples] cut or zero-padded to [frames]; a cut ends in the 4 ms fade every render ends in. */
    private fun fit(samples: FloatArray, frames: Int): FloatArray {
        val out = samples.copyOf(frames)
        if (samples.size > frames) Dsp.fadeTail(out)
        return out
    }

    /** RMS of [from] to [to] seconds of a mono [samples]. */
    private fun rmsBetween(samples: FloatArray, from: Float, to: Float): Float {
        val a = (from * Dsp.RATE).toInt().coerceIn(0, samples.size)
        val b = (to * Dsp.RATE).toInt().coerceIn(a, samples.size)
        var acc = 0.0
        for (i in a until b) acc += samples[i].toDouble() * samples[i]
        return sqrt(acc / (b - a).coerceAtLeast(1)).toFloat()
    }

    // ---- the stab check -----------------------------------------------------

    private const val BPM = 90

    /** The figure's eighth notes in a bar and the frames in one: 14,700 at 90 bpm, a whole number. */
    private const val STEPS_PER_BAR = 8
    private val EIGHTH_FRAMES = (60.0 / BPM / 2 * Dsp.RATE).roundToInt()
    private val PATTERN_FRAMES = 2 * STEPS_PER_BAR * EIGHTH_FRAMES

    /** (eighth note from the top of bar one, MIDI note): C3, C3, E-flat3 in bar one, G3, G3, E-flat3, C3 in bar two. */
    internal val STAB_FIGURE = listOf(0 to 48, 3 to 48, 6 to 51, 8 to 55, 11 to 55, 14 to 51, 15 to 48)

    /** THUMP's snare on beats two and four of both bars. */
    private val SNARE_STEPS = listOf(2, 6, 10, 14)

    /** The snare's loudness as a share of the stabs', fixed so the same mix is on every clip. */
    private const val SNARE_SHARE = 0.6f

    /** VELVET's and RESIN's BRASS roots, A2: the figure's notes are 3, 6 and 10 steps above it. */
    private const val SYNTH_BRASS_ROOT_MIDI = 45

    private class Stab(val id: String, val name: String, val desc: String, val note: (Int) -> Snip)

    private fun celloStab(bow: Float): (Int) -> Snip = { midi ->
        val preset = ArcoPresets.forVoice(ArcoVoice.CELLO).first { it.name == "SHORT STAB" }
        val tune = (midi - Arco.CELLO_ROOT_MIDI) / Arco.CELLO_TUNE_SEMITONES.toFloat()
        Arco.render(ArcoVoice.CELLO, preset.macros + mapOf("TUNE" to tune, "BOW" to bow))
    }

    private fun velvetStab(): (Int) -> Snip = { midi ->
        val preset = VelvetPresets.forVoice(VelvetVoice.BRASS).first { it.name == "BRASS STAB" }
        val tune = (midi - SYNTH_BRASS_ROOT_MIDI) / Velvet.TUNE_SEMITONES.toFloat()
        check(abs(FineTuning.cents(Velvet.frequencyFor(VelvetVoice.BRASS, tune).toDouble(), Keys.midiHz(midi).toDouble())) < 1.0) { "VELVET is off $midi" }
        Velvet.render(VelvetVoice.BRASS, preset.macros + ("TUNE" to tune))
    }

    private fun resinStab(): (Int) -> Snip = { midi ->
        val preset = ResinPresets.forVoice(ResinVoice.BRASS).first { it.name == "PUNCHY STAB" }
        val tune = (midi - SYNTH_BRASS_ROOT_MIDI) / Resin.TUNE_SEMITONES.toFloat()
        check(abs(FineTuning.cents(Resin.frequencyFor(ResinVoice.BRASS, tune).toDouble(), Keys.midiHz(midi).toDouble())) < 1.0) { "RESIN is off $midi" }
        Resin.render(ResinVoice.BRASS, preset.macros + ("TUNE" to tune))
    }

    /** Adds [hit] into [into] from sample [at], wrapping past the end: the smallest mixer, so a pattern's last tails ring into its first bar when it repeats. */
    private fun place(into: FloatArray, hit: FloatArray, at: Int, gain: Float) {
        for (i in hit.indices) into[(at + i) % into.size] += gain * hit[i]
    }

    /** One candidate's stabs at the figure, [barOf] choosing which candidate plays each bar, with no snare and no level. */
    private fun stabTrack(barOf: (Int) -> Stab): FloatArray {
        val out = FloatArray(PATTERN_FRAMES)
        for ((step, midi) in STAB_FIGURE) place(out, barOf(step / STEPS_PER_BAR).note(midi).samples, step * EIGHTH_FRAMES, 1f)
        return out
    }

    /** The gain [AuditionLevel.level] would give [samples]: asked once so the stabs can be matched first and mixed after. */
    private fun levelGain(samples: FloatArray): Float {
        val levelled = AuditionLevel.level(Snip(samples, channels = 1, sampleRate = Dsp.RATE)).samples
        var at = 0
        for (i in samples.indices) if (abs(samples[i]) > abs(samples[at])) at = i
        return levelled[at] / samples[at]
    }

    // ---- the files ----------------------------------------------------------

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/arco-audition")
        root.mkdirs()
        var count = 0
        val sections = mutableListOf<Section>()
        val loops = StringBuilder()
        fun write(dir: String, id: String, snip: Snip) {
            WavWriter.write(File(File(root, dir), "$id.wav"), snip, WavWriter.BitDepth.PCM_16)
            count++
        }

        checkReRender()

        // The held note, blind: the page's first and key card.
        val heldGroups = mutableListOf<Group>()
        var setIndex = 0
        for (voice in ArcoVoice.entries) {
            for (tune in HELD_TUNES) {
                val set = heldSet(voice, tune)
                val order = HELD_ORDERS[setIndex++]
                val frames = set.clips.getValue(Kind.BOWED).samples.size
                val tag = voice.name.lowercase() + "_t" + (tune * 100).roundToInt()
                val clips = order.mapIndexed { i, kind ->
                    val snip = set.clips.getValue(kind)
                    check(snip.samples.size == frames) { "the held set $tag is not one length: $kind is ${snip.samples.size}, the bowed clip $frames" }
                    val id = tag + "_" + ('a' + i)
                    write("HELD", id, AuditionLevel.level(snip))
                    val letter = ('A' + i).toString()
                    Clip(id, "${voice.name} ${noteName(Arco.midiFor(voice, tune))} " + DOT + " " + letter, "one held note, ${f1(HELD_SECONDS)} s of bow", set.trueNames.getValue(kind), set.trueDescs.getValue(kind))
                }
                val answer = clips[order.indexOf(Kind.BOWED)].id
                val pitch = measuredLine(order, set, voice, tune)
                println("ARCO held answer " + voice.name + " " + noteName(Arco.midiFor(voice, tune)) + ": " + order.mapIndexed { i, k -> "${'A' + i}=$k" }.joinToString(" ") + " | " + pitch)
                val label = voice.name + ", " + listOf("THE ROOT", "THE MIDDLE", "THE HIGH STEP")[HELD_TUNES.indexOf(tune)] + " (" + noteName(Arco.midiFor(voice, tune)) + ")"
                heldGroups += Group(label, key = true, clips = clips, blind = Blind("THE BOWED ONE", answer))
            }
        }
        sections += Section(
            id = "HELD", display = "THE HELD NOTE",
            body = "three seconds of bow against a synth, unlabelled: the engine passes or fails here",
            readout = listOf(
                "HOLD " + fmt(HELD_HOLD) + " " + DOT + " " + f1(HELD_SECONDS) + " S OF BOW",
                "TUNE 0, .50, .90 " + DOT + " EVERY OTHER KNOB AT ITS DEFAULT",
                "CELLO: BOWED, PLUCKED, SYNTH " + DOT + " ERHU: BOWED, SUNG, SYNTH",
            ),
            groups = heldGroups,
        )

        // The stab check, in the pattern.
        sections += stabSection(::write)

        // The kit, as it lands: SynthKits.arco() is the one list of what is on which pad,
        // and each pad's own recipe names its patch.
        val kitClips = SynthKits.arco().mapIndexed { i, pad ->
            val arranged = requireNotNull(pad) { "the arco kit has an empty pad at ${i + 1}" }
            val recipe = PadRecipe.fromJsonValue(requireNotNull(arranged.recipe) { "kit pad ${i + 1} carries no recipe" })
            val patch = requireNotNull(recipe.patch) as ArcoPatch
            val tag = "A%02d".format(Locale.ROOT, i + 1)
            val id = tag.lowercase() + "_" + patch.name.lowercase().replace(' ', '_')
            write("KIT", id, AuditionLevel.level(arranged.snip))
            val loop = Arco.isLoop(patch.macros["HOLD"] ?: Arco.defaults(patch.voice).getValue("HOLD"))
            Clip(id, "$tag ${patch.name.uppercase()}", patch.voice.name + (if (loop) ", a LOOP, dry" else ", dry") + " " + DOT + " " + macroLine(patch.voice, patch.macros))
        }
        val kitSection = Section(
            id = "KIT", display = "THE ARCO KIT", body = "sixteen pads as the MPC gets them",
            readout = listOf("A01-A08 CELLO WALKING THE PENTATONIC, AS STABS", "A09-A14 SIX ERHU PRESETS", "A15 A16 ONE LOOP EACH"),
            groups = listOf(
                Group("CELLO, THE PENTATONIC FROM C2", key = true, clips = kitClips.subList(0, 8)),
                Group("ERHU PRESETS", key = true, clips = kitClips.subList(8, 14)),
                Group("THE LOOPS", key = true, clips = kitClips.subList(14, 16)),
            ),
        )
        sections += kitSection

        for (voice in ArcoVoice.entries) {
            val dir = voice.name
            val defaults = Arco.defaults(voice)
            fun render(id: String, macros: Map<String, Float>) = write(dir, id, AuditionLevel.level(Arco.render(voice, macros)))

            render("default", emptyMap())
            val groups = mutableListOf(
                Group("THE DEFAULT", key = true, clips = listOf(Clip("default", "DEFAULT", macroLine(voice, emptyMap())))),
            )
            for (knob in KNOBS) {
                val lo = knob.name.lowercase() + "_0"
                val hi = knob.name.lowercase() + "_1"
                render(lo, mapOf(knob.name to 0f))
                // HOLD 1 is the LOOP step; the top of the one-shot travel is just under it.
                render(hi, mapOf(knob.name to if (knob.name == "HOLD") Arco.SCRAMBLE_HOLD_CEILING else 1f))
                groups += Group(
                    knob.name + " " + DOT + " DEFAULT " + fmt(defaults.getValue(knob.name)), key = false,
                    clips = listOf(Clip(lo, "${knob.name} 0", knob.low(voice)), Clip(hi, "${knob.name} 1", knob.high(voice))),
                )
            }

            if (voice == ArcoVoice.ERHU) groups += erhuBodyGroup(::write)

            val presets = ArcoPresets.forVoice(voice).map { preset ->
                val id = "preset_" + preset.name.lowercase().replace(' ', '_')
                render(id, preset.macros)
                Clip(id, preset.name, macroLine(voice, preset.macros))
            }
            groups += Group("${voice.name}'S OWN PRESETS", key = true, clips = presets)

            // The LOOP step, alone: a seamless two seconds, played once here (hold it on the surface above, or turn REPEAT on).
            render("loop", mapOf("HOLD" to 1f))
            groups += Group(
                "THE LOOP STEP", key = false,
                clips = listOf(Clip("loop", "HOLD 1 (LOOP)", "a seamless whole-period loop: dry, no vibrato, a steady bow; turn the page's REPEAT on to hear the wrap, or hold it on the surface")),
            )
            val loopMidi = Arco.midiFor(voice, defaults.getValue("TUNE"))
            if (loops.isNotEmpty()) loops.append(",")
            loops.append("{\"id\":${q(voice.name)},\"name\":${q(voice.name)},\"sub\":${q(noteName(loopMidi))},\"file\":${q(voice.name + "/loop.wav")}}")

            // The vibrato A/B: the three-second held note as it ships, and the same note with a plain string.
            val held = mapOf("HOLD" to HELD_HOLD)
            write(dir, "vibrato_on", AuditionLevel.level(Arco.render(voice, held)))
            write(dir, "vibrato_off", AuditionLevel.level(renderWith(voice, held, vibrato = false, table = null)))
            groups += Group(
                "VIBRATO: THE HELD NOTE, WITH AND WITHOUT", key = true,
                clips = listOf(
                    Clip("vibrato_on", "VIBRATO ON", vibratoCaption(voice)),
                    Clip("vibrato_off", "VIBRATO OFF", "the same note on a plain string: the same bow, the same box, no pitch movement"),
                ),
            )

            sections += Section(
                id = voice.name, display = voice.name, body = BODIES.getValue(voice),
                readout = listOf(
                    "ROOT " + noteName(Arco.rootMidi(voice)) + " " + DOT + " " + Arco.tuneSemitones(voice) + " SEMITONES OF TRAVEL, TO " + noteName(Arco.rootMidi(voice) + Arco.tuneSemitones(voice)),
                    "BOW " + fmt(defaults.getValue("BOW")) + DOT + "GRIP " + fmt(defaults.getValue("GRIP")) + DOT + "BODY " + fmt(defaults.getValue("BODY")) +
                        DOT + "HOLD " + fmt(defaults.getValue("HOLD")) + " (" + f2(Arco.holdSeconds(defaults.getValue("HOLD"))) + " S OF BOW)",
                ),
                groups = groups,
            )
        }

        // The manifest is the only place the clip list lives; every clip in it must be a file on disk.
        for (section in sections) for (group in section.groups) for (clip in group.clips) {
            check(File(File(root, section.id), clip.id + ".wav").isFile) { "the manifest lists ${section.id}/${clip.id}.wav and it was not written" }
        }
        for (voice in ArcoVoice.entries) check(File(File(root, voice.name), "loop.wav").isFile) { "the surface's ${voice.name} loop was not written" }

        File(root, "manifest.json").writeText(
            "{\"surfaceAfter\":\"STAB\",\"voices\": [\n" + sections.joinToString(",\n") { sectionJson(it) } + "\n],\"loops\": [$loops]}\n",
        )
        val page = ArcoAuditionGenerator::class.java.getResourceAsStream("/audition/arco-audition.html")
            ?: error("the listening page is missing from synth/src/test/resources/audition/")
        File(root, "index.html").outputStream().use { out -> page.use { it.copyTo(out) } }
        println("wrote $count clips + manifest.json + index.html under ${root.absolutePath}")
    }

    /**
     * The stab section: four candidates one at a time, then the pairs alternating on the bar line. Written
     * through [write] (a directory and an id), already levelled, so it is not levelled again.
     */
    private fun stabSection(write: (String, String, Snip) -> Unit): Section {
        val bow1 = Stab("bow_1", "CELLO SHORT STAB, BOW 1", "the preset at BOW 1: ${f1(Arco.attackSeconds(1f) * 1000f)} ms stroke, the bite", celloStab(1f))
        val bowHalf = Stab("bow_half", "CELLO SHORT STAB, BOW .50", "the same stab at BOW .50: ${f1(Arco.attackSeconds(0.5f) * 1000f)} ms stroke, no bite", celloStab(0.5f))
        val velvet = Stab("velvet", "VELVET BRASS STAB", "the synth brass stab, at the figure's own notes", velvetStab())
        val resin = Stab("resin", "RESIN PUNCHY STAB", "the other synth stab: a saw stack through the ladder", resinStab())

        // Each candidate's loudness, matched on its own stab-only two bars.
        val gains = listOf(bow1, bowHalf, velvet, resin).associateWith { stab -> levelGain(stabTrack { stab }) }
        val snareHit = Thump.render(ThumpVoice.SNARE).samples
        val snareTrack = FloatArray(PATTERN_FRAMES)
        for (step in SNARE_STEPS) place(snareTrack, snareHit, step * EIGHTH_FRAMES, 1f)
        val snareGain = levelGain(snareTrack) * SNARE_SHARE

        /** The mix: each bar's candidate at its own matched gain, the snare under it. */
        fun mix(barOf: (Int) -> Stab): FloatArray {
            val out = FloatArray(PATTERN_FRAMES)
            for ((step, midi) in STAB_FIGURE) {
                val stab = barOf(step / STEPS_PER_BAR)
                place(out, stab.note(midi).samples, step * EIGHTH_FRAMES, gains.getValue(stab))
            }
            for (step in SNARE_STEPS) place(out, snareHit, step * EIGHTH_FRAMES, snareGain)
            return out
        }

        val solo = listOf(bow1, bowHalf, velvet, resin).map { stab -> stab to mix { stab } }
        val pairs = listOf(
            Triple("bow_1_then_bow_half", bow1, bowHalf),
            Triple("bow_1_then_velvet", bow1, velvet),
            Triple("bow_1_then_resin", bow1, resin),
        ).map { (id, a, b) -> Triple(id, a to b, mix { bar -> if (bar == 0) a else b }) }

        // One guard for all seven clips, so a peak that needs it moves every clip by the same amount and the match holds.
        var peak = 0f
        for ((_, samples) in solo) for (v in samples) peak = maxOf(peak, abs(v))
        for ((_, _, samples) in pairs) for (v in samples) peak = maxOf(peak, abs(v))
        val guard = if (peak > 0.99f) 0.99f / peak else 1f
        fun out(samples: FloatArray) = Snip(FloatArray(samples.size) { samples[it] * guard }, channels = 1, sampleRate = Dsp.RATE)

        val soloClips = solo.map { (stab, samples) ->
            write("STAB", stab.id, out(samples))
            println("ARCO stab ${stab.id}: gain ${f2(gains.getValue(stab))}, loudness ${f3(Loudness.of(out(samples)))}")
            Clip(stab.id, stab.name, stab.desc)
        }
        val pairClips = pairs.map { (id, ab, samples) ->
            write("STAB", id, out(samples))
            // Bar one is the same in all three pairs, so the name leads with what changes: the page cuts a long name short at the right.
            val first = ab.first.name.replace("CELLO SHORT STAB, ", "CELLO ")
            val second = ab.second.name.replace("CELLO SHORT STAB, ", "CELLO ")
            Clip(
                id,
                "$second ON BAR 2 $DOT $first ON BAR 1",
                "bar 1 is ${ab.first.desc}; bar 2 is ${ab.second.desc}. The candidate changes on the bar line; REPEAT keeps it alternating",
            )
        }
        println("ARCO stab pattern: ${PATTERN_FRAMES} frames (${f2(PATTERN_FRAMES.toFloat() / Dsp.RATE)} s) at $BPM bpm, snare share ${f2(SNARE_SHARE)}, guard ${f2(guard)}")
        return Section(
            id = "STAB", display = "THE ONE-SECOND STAB CHECK",
            body = "the cello stab in a two-bar pattern at $BPM bpm, beside two synth brass stabs and a snare",
            readout = listOf(
                "C3 C3 E-FLAT3 G3 G3 E-FLAT3 C3 " + DOT + " $BPM BPM " + DOT + " TWO BARS",
                "THE SNARE IS THE SAME ON EVERY CLIP " + DOT + " EACH STAB MATCHED BEFORE THE SNARE",
            ),
            groups = listOf(
                Group("TWO BARS, ONE STAB AT A TIME", key = true, clips = soloClips),
                Group("ALTERNATING ON THE BAR LINE", key = true, clips = pairClips),
            ),
        )
    }

    /**
     * ERHU's BODY as three unlabelled clips on the default note: the membrane box as shipped, no box, and one
     * placeholder resonator row, all at BODY's default amount. The first is [Arco.render] to the sample (checked
     * in [checkReRender]); the other two replace only the table [Strings.bodyRing] rings.
     */
    private fun erhuBodyGroup(write: (String, String, Snip) -> Unit): Group {
        val voice = ArcoVoice.ERHU
        val shipped = renderWith(voice, emptyMap(), vibrato = true, table = null)
        val none = renderWith(voice, emptyMap(), vibrato = true, table = emptyList())
        val row = renderWith(voice, emptyMap(), vibrato = true, table = listOf(Modes.fixed(PLACEHOLDER_ROW_HZ, 1f, PLACEHOLDER_ROW_T60)))
        // A fixed order, like the held sets: row, shipped, none.
        val order = listOf(
            Triple(row, "THE ROW", "one placeholder resonator row: a single resonance at ${f1(PLACEHOLDER_ROW_HZ)} Hz (twice the open string), ${f1(PLACEHOLDER_ROW_T60 * 1000f)} ms ring; authored for this question, not sourced"),
            Triple(shipped, "THE MEMBRANE BOX", "the box as shipped: the house membrane's five modes on the open string, rung down to a quarter of the table's own decay"),
            Triple(none, "NO BOX", "the bowed string with no box at all, as at BODY 0"),
        )
        val clips = order.mapIndexed { i, (snip, name, desc) ->
            val id = "erhu_body_" + ('a' + i)
            write("ERHU", id, AuditionLevel.level(snip))
            Clip(id, "ERHU BODY " + DOT + " " + ('A' + i), "the default note, the default knobs", name, desc)
        }
        return Group("THE BOX, THREE WAYS: WHICH IS MOST LIKE AN ERHU?", key = true, clips = clips, blind = Blind("MOST LIKE AN ERHU", null))
    }

    /**
     * A one-shot ARCO note the way [Arco.render] makes it, with [vibrato] and the box's [table] as the two things
     * this page may change: null is [Arco.bodyFor]'s own table through [Arco.withBody]; otherwise [Strings.bodyRing] rings
     * [table] (an empty one is no box) and is cut where the stopped string ends, as [Arco.withBody] cuts it. The macros
     * must not reach a LOOP, which [Arco.render] makes another way.
     */
    private fun renderWith(voice: ArcoVoice, macros: Map<String, Float>, vibrato: Boolean, table: List<Modes.Mode>?): Snip {
        val m = Arco.settled(macros, voice)
        require(!Arco.isLoop(m.getValue("HOLD"))) { "renderWith is for one-shots" }
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val raw = Arco.bow(voice, Arco.frequencyFor(voice, m.getValue("TUNE")), m, rate, vibrato = vibrato)
        val rung = if (table == null) {
            Arco.withBody(raw, voice, m.getValue("BODY"), rate)
        } else {
            val ring = Strings.bodyRing(raw, table, m.getValue("BODY"), rate, Arco.BODY_CEILING_SECONDS)
            if (ring.size == raw.size) ring else ring.copyOf(raw.size)
        }
        return Snip(Arco.finish(rung, rate), channels = 1, sampleRate = Dsp.RATE)
    }

    /** This page's own render path must be the engine's, to the sample, for the default and for the held note, in both voices. */
    private fun checkReRender() {
        for (voice in ArcoVoice.entries) {
            for (macros in listOf(emptyMap(), mapOf("HOLD" to HELD_HOLD))) {
                check(renderWith(voice, macros, vibrato = true, table = null).samples.contentEquals(Arco.render(voice, macros).samples)) {
                    "the audition's re-render of $voice $macros is not what Arco.render makes"
                }
            }
        }
    }

    /** The measured pitch of each clip of a held set against the bowed note's, for the log: cents sharp (+) or flat (-) and the dB the note falls from its loudest 200 ms to the middle of its hold. */
    private fun measuredLine(order: List<Kind>, set: HeldSet, voice: ArcoVoice, tune: Float): String {
        val want = Arco.frequencyFor(voice, tune)
        return order.joinToString(" ") { kind ->
            val snip = set.clips.getValue(kind)
            val loud = rmsBetween(snip.samples, 0f, 4f).coerceAtLeast(1e-9f)
            var best = 1e-9f
            var from = 0f
            while (from + 0.2f <= snip.samples.size.toFloat() / Dsp.RATE) {
                best = maxOf(best, rmsBetween(snip.samples, from, from + 0.2f))
                from += 0.05f
            }
            val mid = 20f * log10(rmsBetween(snip.samples, 1.4f, 1.9f).coerceAtLeast(1e-9f) / best)
            val hz = if (kind == Kind.OTHER && voice == ArcoVoice.ERHU) 0.0 else FineTuning.measuredHz(snip, want, fromSec = 0.4f, bodySeconds = 0.5f)
            val cents = if (hz > 0.0) f1(FineTuning.cents(hz, want.toDouble()).toFloat()) + "c" else "n/a"
            "$kind[${f2(snip.samples.size.toFloat() / Dsp.RATE)}s $cents mid ${f1(mid)}dB rms ${f3(loud)}]"
        }
    }

    // ---- words --------------------------------------------------------------

    private const val DOT = "·"

    private fun f1(v: Float) = "%.1f".format(Locale.ROOT, v)
    private fun f2(v: Float) = "%.2f".format(Locale.ROOT, v)
    private fun f3(v: Float) = "%.3f".format(Locale.ROOT, v)
    private fun ms(seconds: Float) = (seconds * 1000f).roundToInt()

    private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** MIDI note to its name, middle C (60) being C4. */
    private fun noteName(midi: Int) = NOTE_NAMES[midi % 12] + (midi / 12 - 1)

    /** The knobs that differ from the voice's defaults, the way the app's sliders read them. */
    private fun macroLine(voice: ArcoVoice, macros: Map<String, Float>): String {
        val d = Arco.defaults(voice)
        val moved = Arco.macrosFor(voice).map { it.name }.filter { (macros[it] ?: d.getValue(it)) != d.getValue(it) }
        return if (moved.isEmpty()) "every knob at its default" else moved.joinToString(" ") { "$it ${fmt(macros.getValue(it))}" }
    }

    /** A macro value the way the app's sliders read it: `0`, `.35`, `1`. */
    private fun fmt(v: Float) = when {
        v <= 0f -> "0"
        v >= 1f -> "1"
        else -> "." + (v * 100).roundToInt().toString().padStart(2, '0')
    }

    private fun sectionJson(s: Section): String {
        val g = s.groups.joinToString(",") { grp ->
            val c = grp.clips.joinToString(",") { clip ->
                val answer = if (clip.trueName != null) ",${q(clip.trueName)},${q(clip.trueDesc ?: "")}" else ""
                "[${q(clip.id)},${q(clip.name)},${q(clip.desc)}$answer]"
            }
            val blind = grp.blind?.let { b ->
                "\"blind\":{\"pick\":${q(b.pick)}" + (b.answer?.let { ",\"answer\":${q(it)}" } ?: "") + "},"
            } ?: ""
            "{\"label\":${q(grp.label)},\"key\":${grp.key},$blind\"clips\":[$c]}"
        }
        val r = s.readout.joinToString(",") { q(it) }
        return "{\"id\":${q(s.id)},\"display\":${q(s.display)},\"body\":${q(s.body)},\"readout\":[$r],\"groups\":[$g]}"
    }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
