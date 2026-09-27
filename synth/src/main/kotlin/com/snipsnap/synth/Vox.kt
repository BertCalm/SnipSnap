package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * VOX — formant synthesis, maximum kitsch, now with a throat.
 *
 * A buzzing source through bandpass formants is how every
 * shopping-mall-keyboard "choir" ever worked, and that is still the shelf
 * this engine aims for. The VOWEL knob morphs continuously through
 * A → E → I → O → U by interpolating the classic formant tables, one knob,
 * and the pad goes from "aah" to "ooh" under your finger.
 *
 * Round 1 of the VOX upgrade (docs/SYNTH_ROADMAP.md, "VOX, round 1"),
 * everything the audition was played, kept:
 *
 * - **A throat.** CHOIR and GHOST sing through a vocal-cord pulse
 *   ([glottal]) with breath that puffs on each pulse, into five formants
 *   of natural width, the two top ones the "singer's formant" ring near
 *   3 kHz. The lowest formant rises to meet a high note rather than
 *   letting it slip under and go thin. ROBOT keeps its square wave.
 * - **Alive.** Vibrato eases in after the attack, over a slow random
 *   wobble in pitch and level. ROBOT stays machine-steady.
 * - **A real choir.** CHOIR is seven singers in sections, two basses an
 *   octave down, three in the middle, two sopranos an octave up, each
 *   with their own throat, detune, vibrato and a start up to 60 ms late,
 *   spread across the stereo field. CHOIR renders in stereo; ROBOT and
 *   GHOST stay mono.
 * - **Held notes.** DECAY's top half holds the note before it fades.
 * - **SIZE** scales the throat, chipmunk to giant, independent of pitch.
 * - **GLIDE** moves the vowel during the note: below the middle it slides
 *   toward A, above it toward U. "Wah", "yeah", "ow".
 *
 * Round 2 ("Speak"):
 *
 * - **ONSET**, the singing voices' seventh macro, opens the note on a
 *   consonant: none, m, b, d, h, t, s, snapped. At 0 (every preset's
 *   setting) nothing changes, to the byte.
 * - **BEATBOX**, a fourth voice: vocal percussion ([VoxBeatbox]), HIT
 *   choosing a kick, three snares, three hats or a rim.
 *
 * TUNE snaps to semitones like every melodic engine here. Each note's
 * wobble, detune and onsets are seeded from its recipe, so a pad
 * regenerates to the byte.
 */
enum class VoxVoice { CHOIR, ROBOT, GHOST, BEATBOX }

object Vox {

    const val TUNE_SEMITONES = 24

    /** No note outlasts this, hold and tail included: a pad is a one-shot. TIDE's held voices stop here too. */
    const val MAX_SECONDS = 4f

    /**
     * Per-voice nudge on top of [Dsp.MELODIC_LOUDNESS_TARGET], zeroed out
     * awaiting a listening pass (task-4-report.md) - a table edit here, not
     * a refactor of [render].
     */
    private val LOUDNESS_OFFSET: Map<VoxVoice, Float> = VoxVoice.entries.associateWith { 0f }

    /** F1/F2/F3 per vowel, the classic tables: A, E, I, O, U. */
    private val VOWELS = arrayOf(
        floatArrayOf(800f, 1150f, 2900f),  // A
        floatArrayOf(400f, 1600f, 2700f),  // E
        floatArrayOf(250f, 1750f, 3800f),  // I
        floatArrayOf(400f, 800f, 2830f),   // O
        floatArrayOf(350f, 600f, 2700f),   // U
    )

    /** F4 and F5, the same for every vowel: with F3 they are the "singer's formant" cluster. */
    private val UPPER_FORMANTS = floatArrayOf(3300f, 4200f)

    /**
     * Each formant's level, F1 to F5. The top two were raised until the
     * ring carried: at 0.22 and 0.1 the whole throat measured barely
     * clear of the new render path's own noise floor against the old
     * engine (1-4.5 dB), at 1 and 0.55 it measured 6-7.5, a semitone's
     * worth of spectral change.
     */
    private val FORMANT_GAINS = floatArrayOf(1f, 0.7f, 0.45f, 1f, 0.55f)

    /** Each formant's bandwidth, Hz: roughly constant in a real vocal tract, where a fixed Q widens with frequency. */
    private val FORMANT_BANDWIDTHS = floatArrayOf(60f, 70f, 100f, 130f, 180f)

    /** The lowest formant never sits below this much above the note, so a high note keeps its vowel. */
    private const val F1_FLOOR_RATIO = 1.15f

    /** Rosenberg glottal pulse: the fold opens over [OPEN] of the cycle and snaps shut over [CLOSE]. */
    private const val OPEN = 0.4f
    private const val CLOSE = 0.16f
    private val GLOTTAL_NORM = (PI / (2 * CLOSE)).toFloat()

    /** Breath that puffs on each vocal-cord pulse, over BREATH's own. */
    private const val PUFF = 0.35f

    /** Vibrato depth, cents: 70, with GHOST's slower, wider 90. Eases in [VIBRATO_DELAY] after the strike over [VIBRATO_RISE]. */
    private const val VIBRATO_CENTS = 70f
    private const val GHOST_VIBRATO_CENTS = 90f
    private const val VIBRATO_DELAY = 0.03f
    private const val VIBRATO_RISE = 0.12f

    /** Slow random wander, [WOBBLE_HZ] points a second: this many cents of pitch, this fraction of level. */
    private const val WOBBLE_HZ = 7f
    private const val WOBBLE_CENTS = 15f
    private const val SHIMMER = 0.12f

    /** CHOIR's seven: detune in cents, octave, throat scale and pan slot, basses first. */
    private val CHOIR_DETUNE = floatArrayOf(-35f, -22f, -10f, 0f, 11f, 23f, 36f)
    private val CHOIR_OCTAVE = floatArrayOf(0.5f, 0.5f, 1f, 1f, 1f, 2f, 2f)
    private val CHOIR_THROAT = floatArrayOf(0.88f, 0.9f, 0.97f, 1f, 1.03f, 1.16f, 1.2f)
    private val PAN_SLOTS = floatArrayOf(-0.9f, -0.6f, -0.3f, 0f, 0.3f, 0.6f, 0.9f)

    /** The latest a choir singer comes in, and how long each takes to reach full voice. */
    private const val ONSET_SPREAD = 0.06f
    private const val ONSET_RISE = 0.03f

    /** SIZE's reach: the throat scales by 2^((0.5 − SIZE)·this), about ×2.1 at 0 to ×0.47 at 1. */
    private const val SIZE_OCTAVES = 2.2f

    /** Formant and pitch controls update every this many samples at the render rate. */
    private const val CONTROL_BLOCK = 16

    fun macrosFor(voice: VoxVoice): List<MacroSpec> = when (voice) {
        VoxVoice.CHOIR -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("VOWEL", 0.1f), MacroSpec("BREATH", 0.15f),
            MacroSpec("DECAY", 0.6f), MacroSpec("SIZE", 0.5f), MacroSpec("GLIDE", 0.5f), MacroSpec("ONSET", 0f),
        )
        VoxVoice.ROBOT -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("VOWEL", 0.6f), MacroSpec("BREATH", 0.05f),
            MacroSpec("DECAY", 0.4f), MacroSpec("SIZE", 0.5f), MacroSpec("GLIDE", 0.5f), MacroSpec("ONSET", 0f),
        )
        VoxVoice.GHOST -> listOf(
            MacroSpec("TUNE", 0.45f), MacroSpec("VOWEL", 0.85f), MacroSpec("BREATH", 0.6f),
            MacroSpec("DECAY", 0.7f), MacroSpec("SIZE", 0.5f), MacroSpec("GLIDE", 0.5f), MacroSpec("ONSET", 0f),
        )
        VoxVoice.BEATBOX -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("HIT", 0f), MacroSpec("DECAY", 0.5f), MacroSpec("SIZE", 0.5f),
        )
    }

    fun defaults(voice: VoxVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    /** CHOIR sings in stereo; ROBOT, GHOST and BEATBOX are one mouth, mono. */
    fun channelsFor(voice: VoxVoice): Int = if (voice == VoxVoice.CHOIR) 2 else 1

    /**
     * What a pad holding this sound is filed as. The singing voices are
     * notes. BEATBOX is filed by its HIT, because the drum classifier does
     * not hear a mouth's drums as the drums they stand for: measured, it
     * reads the kick as PERC and the hats and the rim as SNARE.
     */
    fun drumClassFor(voice: VoxVoice, macros: Map<String, Float> = emptyMap()): DrumClass = when (voice) {
        VoxVoice.BEATBOX -> when (VoxBeatbox.hitFor(macros["HIT"] ?: defaults(voice).getValue("HIT"))) {
            VoxBeatbox.Hit.KICK -> DrumClass.KICK
            VoxBeatbox.Hit.PF, VoxBeatbox.Hit.PSH, VoxBeatbox.Hit.K -> DrumClass.SNARE
            VoxBeatbox.Hit.TS, VoxBeatbox.Hit.T -> DrumClass.HAT_CLOSED
            VoxBeatbox.Hit.TSS -> DrumClass.HAT_OPEN
            VoxBeatbox.Hit.RIM -> DrumClass.PERC
        }
        else -> DrumClass.TONAL
    }

    /** SCRAMBLE near a preset; see [Thump.scramble] (docs/SYNTH_UPGRADE.md, U2). */
    fun scramble(voice: VoxVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = when {
            near != null -> base + near.macros.filterKeys { it in base }
            temperature >= 1f -> base
            else -> base + VoxPresets.forVoice(voice).random(random).macros.filterKeys { it in base }
        }
        return Dsp.scrambleNear(seed, temperature, random)
    }

    fun frequencyFor(voice: VoxVoice, tune: Float): Float {
        val root = when (voice) {
            VoxVoice.CHOIR -> 110f
            VoxVoice.ROBOT -> 82.4f
            VoxVoice.GHOST -> 147f
            // An octave under the kick's hum: TUNE's middle lands it on VoxBeatbox.KICK_HZ.
            VoxVoice.BEATBOX -> VoxBeatbox.KICK_HZ / 2f
        }
        val semis = Math.round(tune.coerceIn(0f, 1f) * TUNE_SEMITONES)
        return root * 2f.pow(semis / 12f)
    }

    /** The morphed F1-F3 for a VOWEL position 0..1 across A→E→I→O→U. */
    internal fun formantsAt(vowel: Float): FloatArray {
        val pos = vowel.coerceIn(0f, 1f) * (VOWELS.size - 1)
        val i = pos.toInt().coerceAtMost(VOWELS.size - 2)
        val frac = pos - i
        return FloatArray(3) { f -> VOWELS[i][f] + (VOWELS[i + 1][f] - VOWELS[i][f]) * frac }
    }

    /** SIZE as a formant scale: above 1 is a smaller throat, below 1 a bigger one. */
    internal fun throatScale(size: Float): Float = 2f.pow((0.5f - size.coerceIn(0f, 1f)) * SIZE_OCTAVES)

    /** Where GLIDE takes VOWEL by the end of the glide: the middle stays put, the ends reach the far vowel. */
    internal fun glideTarget(vowel: Float, glide: Float): Float = (vowel + (glide - 0.5f) * 2f).coerceIn(0f, 1f)

    /** DECAY as seconds from the strike to −60 dB, hold included. */
    internal fun lengthFor(decay: Float): Float = Dsp.expMap(decay, 0.25f, 3f)

    /** How much of the note DECAY holds before it fades: none below the middle, up to 60% at the top. */
    internal fun holdFractionFor(decay: Float): Float = 0.6f * ((decay - 0.5f) / 0.5f).coerceIn(0f, 1f)

    /** Rosenberg glottal flow derivative: a soft opening, a sharp closure, a closed rest. Peak −1 at closure, no DC. */
    internal fun glottal(phase: Double): Float {
        val p = (phase - Math.floor(phase)).toFloat()
        return when {
            p < OPEN -> (0.5f * PI.toFloat() / OPEN * sin(PI.toFloat() * p / OPEN)) / GLOTTAL_NORM
            p < OPEN + CLOSE -> -(PI.toFloat() / (2 * CLOSE) * sin(PI.toFloat() * (p - OPEN) / (2 * CLOSE))) / GLOTTAL_NORM
            else -> 0f
        }
    }

    /** The consonant a note opens on: ONSET 0..1 snapped across these, none first. */
    internal enum class Consonant { NONE, M, B, D, H, T, S }

    /**
     * How a consonant is said, all before or around the release into the
     * vowel: how long the mouth is closed or hissing, how much voice hums
     * through while it is ([murmur]), when the voice starts after the
     * release, breath through the vowel's shape before and after it, a
     * hiss outside it, and the formants the vowel opens from ([locus]).
     *
     * No pops. The audition heard b, d and t "clucky"; an envelope
     * dropout, an instant switch of the hum's upper formants and a narrow
     * "tok" of a burst were fixed and measured smoother, but with the
     * bursts in at all, even at 30%, they still clucked. Without them, and
     * with the mouth opening over [TRANSITION] rather than 40 ms, they did
     * not: b and d are told apart by where the vowel opens from.
     */
    internal class ConsonantSpec(
        val closure: Float,
        val murmur: Float,
        val voiceDelay: Float,
        val voiceRise: Float,
        val aspirateInClosure: Float = 0f,
        val aspirateAfter: Float = 0f,
        val fricative: Boolean = false,
        val locus: FloatArray? = null,
        val murmurTilt: Float = 1f,
    )

    internal val CONSONANTS = mapOf(
        Consonant.NONE to ConsonantSpec(0f, 1f, 0f, 0f),
        Consonant.M to ConsonantSpec(0.09f, 0.55f, 0f, 0.02f, locus = floatArrayOf(250f, 1000f, 2200f), murmurTilt = 0.15f),
        // With no pops, b and d differ in where the vowel opens from and in the closure itself:
        // lips shut (b) let almost nothing but F1 through, the tongue behind the teeth (d) leaves the front of the mouth bright.
        Consonant.B to ConsonantSpec(0.04f, 0.25f, 0f, 0.02f, locus = floatArrayOf(250f, 800f, 2200f), murmurTilt = 0.08f),
        Consonant.D to ConsonantSpec(0.035f, 0.2f, 0f, 0.02f, locus = floatArrayOf(250f, 1800f, 2700f), murmurTilt = 0.6f),
        Consonant.H to ConsonantSpec(0.1f, 0f, 0f, 0.04f, aspirateInClosure = 1f, aspirateAfter = 0.04f),
        Consonant.T to ConsonantSpec(0.02f, 0f, 0.05f, 0.04f, aspirateAfter = 0.06f),
        Consonant.S to ConsonantSpec(0.16f, 0f, 0f, 0.04f, fricative = true),
    )

    internal fun consonantFor(onset: Float): Consonant =
        Consonant.entries[Math.round(onset.coerceIn(0f, 1f) * (Consonant.entries.size - 1))]

    /** An "s" hisses at this fraction of its vowel's RMS over the vowel's first 40 ms, not at a fixed level. */
    private const val FRIC_REL = 0.7f

    /** Breath through the vowel's shape, for h and after t, against the throat's own source. */
    private const val ASPIRATE_LEVEL = 0.3f

    /** How long the mouth takes to open from a consonant's locus into the vowel. */
    private const val TRANSITION = 0.09f

    /** One singer: their own throat, pitch, vibrato, wobble and entrance. */
    private class Singer(
        val detuneCents: Float,
        val octave: Float,
        val throat: Float,
        val pan: Float,
        val onset: Float,
        val vibratoHz: Float,
        val vibratoPhase: Float,
        val wobble: FloatArray,
        val shimmer: FloatArray,
        var phase: Double,
        nForm: Int,
    ) {
        val formants = Array(nForm) { Dsp.Biquad() }
        var ratio = 1f
        var level = 1f

        fun line(points: FloatArray, t: Float): Float {
            val x = t * WOBBLE_HZ
            val k = x.toInt().coerceAtMost(points.size - 2)
            val w = (1f - cos(PI.toFloat() * (x - k))) / 2f
            return points[k] * (1f - w) + points[k + 1] * w
        }
    }

    /**
     * The raw synth loop at whatever [rate] the caller wants, interleaved
     * when [channelsFor] is 2 - split out of [render] so the oversampled
     * dispatch can be tested directly against a native-rate render.
     */
    internal fun synthesize(voice: VoxVoice, macros: Map<String, Float>, rate: Int): FloatArray {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        if (voice == VoxVoice.BEATBOX) {
            return VoxBeatbox.synthesize(
                VoxBeatbox.hitFor(m.getValue("HIT")),
                pitch = frequencyFor(voice, m.getValue("TUNE")) / VoxBeatbox.KICK_HZ,
                decay = m.getValue("DECAY"),
                scale = throatScale(m.getValue("SIZE")),
                rate = rate,
            )
        }
        // ONSET is left out of the seed, so adding it (at 0 on every existing recipe) left every note's draws, and bytes, where they were.
        val random = Random(Dsp.seedFor("VOX", voice.name, m.filterKeys { it != "ONSET" }.toSortedMap().entries.joinToString(",")))

        val base = frequencyFor(voice, m.getValue("TUNE"))
        val vowelFrom = m.getValue("VOWEL")
        val vowelTo = glideTarget(vowelFrom, m.getValue("GLIDE"))
        val scale = throatScale(m.getValue("SIZE"))
        val breath = m.getValue("BREATH")
        val length = lengthFor(m.getValue("DECAY"))
        val hold = length * holdFractionFor(m.getValue("DECAY"))
        val glideSeconds = minOf(0.5f, 0.6f * length)

        val channels = channelsFor(voice)
        val consonant = consonantFor(m.getValue("ONSET"))
        val cs = CONSONANTS.getValue(consonant)
        val frames = ((cs.closure + cs.voiceDelay + length * 1.3f).coerceAtMost(MAX_SECONDS) * rate).toInt().coerceAtLeast(64)
        val out = FloatArray(frames * channels)
        val nForm = FORMANT_GAINS.size

        val choir = voice == VoxVoice.CHOIR
        val moving = voice != VoxVoice.ROBOT
        val pans = PAN_SLOTS.toMutableList().also { it.shuffle(random) }
        val wobblePoints = (frames.toFloat() / rate * WOBBLE_HZ).toInt() + 3
        val singers = List(if (choir) CHOIR_DETUNE.size else 1) { s ->
            Singer(
                detuneCents = if (choir) CHOIR_DETUNE[s] else 0f,
                octave = if (choir) CHOIR_OCTAVE[s] else 1f,
                throat = if (choir) CHOIR_THROAT[s] else 1f,
                pan = if (choir) pans[s] else 0f,
                // The middle singer is always on time, so the strike is never soft.
                onset = if (choir && s != 3) ONSET_SPREAD * random.nextFloat() else 0f,
                vibratoHz = if (voice == VoxVoice.GHOST) 4f + random.nextFloat() else 5f + 1.2f * random.nextFloat(),
                vibratoPhase = random.nextFloat(),
                wobble = FloatArray(wobblePoints) { random.nextFloat() * 2f - 1f },
                shimmer = FloatArray(wobblePoints) { random.nextFloat() * 2f - 1f },
                phase = random.nextDouble(),
                nForm = nForm,
            )
        }
        val vibratoCents = if (voice == VoxVoice.GHOST) GHOST_VIBRATO_CENTS else VIBRATO_CENTS

        fun tuneFormants(vowel: Float, open: Float = 1f) {
            val f = formantsAt(vowel)
            val locus = cs.locus
            if (locus != null && open < 1f) for (k in 0 until 3) f[k] = locus[k] + (f[k] - locus[k]) * open
            for (singer in singers) for (k in 0 until nForm) {
                var hz = (if (k < 3) f[k] else UPPER_FORMANTS[k - 3]) * scale * singer.throat
                if (k == 0) hz = maxOf(hz, base * singer.octave * F1_FLOOR_RATIO)
                hz = hz.coerceAtMost(rate * 0.45f)
                singer.formants[k].bandpass(hz, (hz / FORMANT_BANDWIDTHS[k]).coerceAtLeast(2f), rate)
            }
        }
        tuneFormants(vowelFrom, if (cs.locus != null) 0f else 1f)
        val consonantNoise = Dsp.Noise(29)
        val fricFilter = Dsp.Biquad().apply { bandpass(6000f, 1.5f, rate) }
        val fricBuf = FloatArray(if (cs.fricative) frames else 0)

        val noise = Dsp.Noise(17)
        val noiseLp = Dsp.OnePole(rate)
        // The note's own envelope starts when the vowel does; a consonant is its own shape before that.
        val vowelStart = cs.closure + cs.voiceDelay
        // After a consonant the voicing ramp is the attack: the envelope must stay at 1 across the release, or it clucks.
        val env = Dsp.Env(attackSeconds = if (vowelStart > 0f) 0f else 0.02f, decay2T60 = length - hold, holdSeconds = hold)
        val gainL = FloatArray(singers.size) { cos((singers[it].pan + 1f) * PI.toFloat() / 4f) }
        val gainR = FloatArray(singers.size) { sin((singers[it].pan + 1f) * PI.toFloat() / 4f) }

        for (i in 0 until frames) {
            val t = i.toFloat() / rate
            if (i % CONTROL_BLOCK == 0) {
                val opening = if (cs.locus != null) ((t - cs.closure) / TRANSITION).coerceIn(0f, 1f) else 1f
                if (vowelTo != vowelFrom || (cs.locus != null && t < cs.closure + TRANSITION + 0.001f)) {
                    val x = (t / glideSeconds).coerceIn(0f, 1f)
                    tuneFormants(vowelFrom + (vowelTo - vowelFrom) * x * x * (3f - 2f * x), opening)
                }
                for (singer in singers) {
                    var cents = singer.detuneCents
                    var level = if (choir) ((t - singer.onset) / ONSET_RISE).coerceIn(0f, 1f) else 1f
                    if (moving) {
                        // Vibrato waits for the vowel: a singer does not wobble through a consonant.
                        val rise = ((t - vowelStart - singer.onset - VIBRATO_DELAY) / VIBRATO_RISE).coerceIn(0f, 1f)
                        cents += rise * vibratoCents * sin(2f * PI.toFloat() * (singer.vibratoHz * t + singer.vibratoPhase))
                        cents += WOBBLE_CENTS * singer.line(singer.wobble, t)
                        level *= 1f + SHIMMER * singer.line(singer.shimmer, t)
                    }
                    singer.ratio = singer.octave * 2f.pow(cents / 1200f)
                    singer.level = level
                }
            }
            val air = noiseLp.lp(noise.next(), 3_000f) * 2f
            val e = if (t < vowelStart) 1f else env.at(t - vowelStart)
            // The consonant: voicing (a murmur while the lips are shut), breath through the vowel, a hiss outside it.
            val voicing = when {
                consonant == Consonant.NONE -> 1f
                t < cs.closure -> cs.murmur
                else -> ((t - cs.closure - cs.voiceDelay) / cs.voiceRise).coerceIn(0f, 1f)
            }
            val aspirate = when {
                t < cs.closure -> cs.aspirateInClosure
                cs.aspirateAfter > 0f && t < cs.closure + cs.aspirateAfter -> 1f - (t - cs.closure) / cs.aspirateAfter
                else -> 0f
            }
            if (cs.fricative) fricBuf[i] = fricFilter.process(consonantNoise.next()) * (if (t < cs.closure) minOf(t / 0.02f, 1f) * minOf((cs.closure - t) / 0.03f, 1f) else 0f)
            // While the mouth is closed the upper formants pass only [ConsonantSpec.murmurTilt] of themselves; they open with it.
            val murmurTilt = if (cs.locus != null) cs.murmurTilt + (1f - cs.murmurTilt) * ((t - cs.closure) / TRANSITION).coerceIn(0f, 1f) else 1f
            var left = 0f
            var right = 0f
            for ((s, singer) in singers.withIndex()) {
                singer.phase += base * singer.ratio / rate
                val buzz = if (voice == VoxVoice.ROBOT) Dsp.square(singer.phase) else glottal(singer.phase)
                // Breath puffs while the folds are open.
                val open = (singer.phase - Math.floor(singer.phase)).toFloat()
                val puff = if (moving && open < OPEN + CLOSE) PUFF * air * sin(PI.toFloat() * open / (OPEN + CLOSE)) else 0f
                val source = singer.level * ((1f - breath) * buzz) * voicing + breath * air + puff * voicing + ASPIRATE_LEVEL * aspirate * air
                var y = 0f
                for (k in 0 until nForm) y += FORMANT_GAINS[k] * (if (k == 0) 1f else murmurTilt) * singer.formants[k].process(source)
                left += y * gainL[s]
                right += y * gainR[s]
            }
            if (channels == 2) {
                out[2 * i] = left / singers.size * e
                out[2 * i + 1] = right / singers.size * e
            } else {
                // A lone singer sits in the middle, cos(π/4) each side: undo it.
                out[i] = left * Math.sqrt(2.0).toFloat() * e
            }
        }
        // The hiss, set against the vowel's own level over its first 40 ms.
        if (fricBuf.isNotEmpty()) {
            val a = ((vowelStart + 0.01f) * rate).toInt()
            val b = ((vowelStart + 0.05f) * rate).toInt().coerceAtMost(frames)
            var e2 = 0.0
            for (f in a until b) for (c in 0 until channels) e2 += out[f * channels + c].toDouble().let { it * it }
            val vowelRms = kotlin.math.sqrt(e2 / maxOf(1, (b - a) * channels)).toFloat()
            fun rmsOf(buf: FloatArray): Float {
                var e = 0.0; var n = 0
                for (v in buf) if (v != 0f) { e += v * v; n++ }
                return kotlin.math.sqrt(e / maxOf(1, n)).toFloat()
            }
            val fricGain = FRIC_REL * vowelRms / maxOf(rmsOf(fricBuf), 1e-9f)
            for (f in 0 until frames) for (c in 0 until channels) out[f * channels + c] += fricBuf[f] * fricGain
        }
        return out
    }

    fun render(voice: VoxVoice, macros: Map<String, Float> = emptyMap()): Snip {
        // U6 (docs/SYNTH_UPGRADE.md): render at 4x RATE so the vocal-cord
        // pulse's and the square's harmonics fold down above 22.05kHz
        // instead of into the audible band, then Dsp.decimate brings it
        // back to RATE. The formant bandpasses and noise lowpass are
        // threaded the render rate explicitly.
        val channels = channelsFor(voice)
        val raw = synthesize(voice, macros, RATE * Dsp.OVERSAMPLE)
        val out = Dsp.decimate(raw, RATE, channels)
        if (voice == VoxVoice.BEATBOX) {
            // A drum is levelled by its peak, like THUMP's; the snares and hats go through the close mic first.
            VoxBeatbox.finish(out, VoxBeatbox.hitFor(macros["HIT"] ?: defaults(voice).getValue("HIT")))
            return Snip(out, channels = 1, sampleRate = RATE)
        }
        // Loudness, not peak: a sine-heavy voice at equal peak reads quieter
        // (Dsp.MELODIC_LOUDNESS_TARGET's doc comment has the measurement).
        Dsp.levelTo(out, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET + LOUDNESS_OFFSET.getValue(voice), channels = channels)
        Dsp.fadeTail(out, channels = channels)
        return Snip(out, channels = channels, sampleRate = RATE)
    }
}
