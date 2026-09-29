package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SPEAK: a talking voice that counts, one to eight.
 *
 * A small formant speech synthesizer in the classic mould (Klatt's): a
 * vocal-cord buzz and a breath run through five resonances in a chain,
 * so each vowel's formants keep their natural balance; a separate hiss
 * for s, f, th and v; short, place-shaped bursts for t and k; a nasal
 * pole and zero for n. Each word is a script of targets the mouth moves
 * between, the way a speaker's formants move through the word.
 *
 * - **WORD** picks the word: one to eight.
 * - **HUMAN** sweeps from a talking machine to a person. The machine is
 *   an 80s speech chip: a raw pulse, bright to the top, dead on the note,
 *   its mouth jumping frame to frame, its sound held at a low sample rate
 *   and a few levels. The person's pitch moves through the word, wanders
 *   and jitters cycle to cycle; the buzz is rounder and breathes. The first
 *   audition heard the ends "fairly similar" (4.3-4.7 on the 24-band
 *   difference measure, whose noise floor is 1-4.5): the chip's buzz and
 *   grit took it to 8.3-10.7, with the middle halfway.
 * - **DECAY** stretches the vowels, never the consonants: short, a
 *   spoken count; long, "fiiive" held as a chop.
 * - **SIZE** runs from a child to a giant. The giant stops at 0.7 of the
 *   formants (VOX's own 0.47 was heard "muffled rather than resonating",
 *   measured −26 dB over 2 kHz against the normal voice's −18), rings on
 *   narrower resonances and has a light chest under it; its hisses barely
 *   move, so a big mouth still says "s".
 * - **EFFORT** goes hushed, talking, shouting. Hushed keeps a voice under
 *   the breath (SWARM's whisper was heard as "demonic"); a shout opens the
 *   jaw and brightens the buzz. It carries INFLECT too: a calm word lifts
 *   and falls like a statement, a shout climbs like "five, six, seven,
 *   EIGHT!". The machine stays on the note either way, so a chop stays in key.
 * - **STUTTER** puts up to three false starts before the word, each as
 *   quick as eight's.
 *
 * Every draw (the wander, the jitter, the noise) is seeded from the whole
 * recipe, so a pad regenerates to the byte.
 */
internal object VoxSpeak {

    enum class Word { ONE, TWO, THREE, FOUR, FIVE, SIX, SEVEN, EIGHT }

    /** WORD 0..1, snapped across the eight. */
    fun wordFor(word: Float): Word = Word.entries[Math.round(word.coerceIn(0f, 1f) * (Word.entries.size - 1))]

    /**
     * One target on a word's script: at [t] seconds (spoken at natural
     * speed), the formants, how much voice ([av]), breath through the
     * formants ([ah]) and hiss ([af], centred on [fc] Hz, [fbw] wide), how
     * nasal ([nasal]) and how damped the upper formants are ([damp], 1 open,
     * higher for a closed or nasal mouth). [stretch] marks the interval
     * that starts here as vowel, which DECAY lengthens.
     */
    class Target(
        val t: Float,
        val f1: Float, val f2: Float, val f3: Float,
        val av: Float, val ah: Float, val af: Float,
        val fc: Float, val fbw: Float,
        val nasal: Float, val damp: Float,
        val stretch: Boolean,
    )

    /** A stop's release: a short decaying puff of noise, centred where the tongue lets go. */
    class Burst(val t: Float, val amp: Float, val fc: Float, val fbw: Float)

    class Script(val targets: List<Target>, val bursts: List<Burst>)

    /** Writes a script as a list of changes: anything not given carries over from the target before. */
    private class Builder {
        val targets = mutableListOf<Target>()
        val bursts = mutableListOf<Burst>()
        private var last = Target(0f, 500f, 1500f, 2500f, 0f, 0f, 0f, 5000f, 4000f, 0f, 1f, false)

        fun at(
            t: Float,
            f1: Float = last.f1, f2: Float = last.f2, f3: Float = last.f3,
            av: Float = last.av, ah: Float = last.ah, af: Float = last.af,
            fc: Float = last.fc, fbw: Float = last.fbw,
            nasal: Float = last.nasal, damp: Float = last.damp,
            stretch: Boolean = false,
        ) {
            last = Target(t, f1, f2, f3, av, ah, af, fc, fbw, nasal, damp, stretch)
            targets += last
        }

        fun burst(t: Float, amp: Float, fc: Float, fbw: Float) { bursts += Burst(t, amp, fc, fbw) }

        fun build() = Script(targets.toList(), bursts.toList())
    }

    // The hisses: s is narrow and high, f/th/v broad and weak (they are told apart mostly by the vowel's transitions).
    private const val S_FC = 5800f
    private const val S_BW = 2500f
    private const val F_FC = 4500f
    private const val F_BW = 7000f

    fun scriptFor(word: Word): Script = Builder().apply {
        when (word) {
            Word.ONE -> { // w ʌ n
                at(0f, 290f, 610f, 2150f)
                at(0.03f, av = 0.6f)
                at(0.08f, 300f, 650f, 2200f, av = 0.8f)
                at(0.18f, 620f, 1150f, 2400f, av = 1f, stretch = true)
                at(0.28f, 600f, 1250f, 2450f)
                at(0.33f, 280f, 1500f, 2500f, av = 0.55f, nasal = 1f, damp = 2.5f)
                at(0.46f, 260f, av = 0.45f)
                at(0.52f, av = 0f)
            }
            Word.TWO -> { // t uː
                burst(0f, 1f, 4000f, 3000f)
                at(0f, 450f, 1500f, 2500f, ah = 0.9f)
                at(0.06f, 380f, 1300f, 2350f, ah = 0.6f)
                at(0.08f, 350f, 1200f, 2300f, av = 0.9f, ah = 0.1f)
                at(0.16f, 320f, 950f, 2250f, av = 1f, ah = 0f, stretch = true)
                at(0.33f, 310f, 900f, 2250f)
                at(0.42f, av = 0f)
            }
            Word.THREE -> { // θ r iː
                at(0f, 400f, 1400f, 2700f, fc = F_FC, fbw = F_BW)
                at(0.02f, af = 0.3f)
                at(0.12f, af = 0.25f)
                at(0.14f, 330f, 1150f, 1700f, av = 0.7f, af = 0f)
                at(0.2f, 330f, 1250f, 1750f, av = 0.9f)
                at(0.28f, 280f, 2200f, 2900f, av = 1f, stretch = true)
                at(0.42f, 270f, 2300f, 3000f)
                at(0.5f, av = 0f)
            }
            Word.FOUR -> { // f ɔ r
                at(0f, 400f, 1000f, 2500f, fc = F_FC, fbw = F_BW)
                at(0.02f, af = 0.35f)
                at(0.12f, af = 0.3f)
                at(0.14f, 520f, 900f, 2450f, av = 0.9f, af = 0f)
                at(0.22f, 570f, 850f, 2400f, av = 1f, stretch = true)
                at(0.3f, 520f, 950f, 2200f)
                at(0.4f, 450f, 1150f, 1650f, av = 0.9f)
                at(0.5f, 430f, 1200f, 1600f, av = 0f)
            }
            Word.FIVE -> { // f aɪ v
                at(0f, 450f, 1100f, 2450f, fc = F_FC, fbw = F_BW)
                at(0.02f, af = 0.35f)
                at(0.12f, af = 0.3f)
                at(0.14f, 700f, 1100f, 2450f, av = 0.9f, af = 0f)
                at(0.2f, 730f, 1150f, 2450f, av = 1f, stretch = true)
                at(0.3f, 650f, 1400f, 2500f)
                at(0.42f, 420f, 1950f, 2550f)
                at(0.46f, 350f, 1500f, 2400f, av = 0.5f, af = 0.2f, damp = 1.5f)
                at(0.56f, av = 0.4f)
                at(0.6f, av = 0f, af = 0f)
            }
            Word.SIX -> { // s ɪ k s
                at(0f, 400f, 1800f, 2600f, fc = S_FC, fbw = S_BW)
                at(0.02f, af = 1f)
                at(0.15f)
                at(0.17f, 390f, 1900f, 2550f, av = 0.9f, af = 0f)
                at(0.2f, 390f, 1990f, 2550f, av = 1f, stretch = true)
                at(0.28f, 380f, 2050f, 2600f)
                at(0.31f, 350f, 2200f, 2800f, av = 0f)
                burst(0.37f, 0.8f, 2600f, 1200f)
                at(0.37f)
                at(0.38f, af = 0.9f)
                at(0.52f)
                at(0.55f, af = 0f)
            }
            Word.SEVEN -> { // s ɛ v ə n
                at(0f, 450f, 1800f, 2500f, fc = S_FC, fbw = S_BW)
                at(0.02f, af = 1f)
                at(0.14f)
                at(0.16f, 520f, 1800f, 2500f, av = 0.9f, af = 0f)
                at(0.19f, 540f, 1800f, 2480f, av = 1f, stretch = true)
                at(0.27f, 520f, 1700f, 2450f)
                at(0.3f, 380f, 1300f, 2300f, av = 0.5f, af = 0.2f, fc = F_FC, fbw = F_BW, damp = 1.5f)
                at(0.35f)
                at(0.37f, 500f, 1400f, 2450f, av = 0.9f, af = 0f, damp = 1f)
                at(0.42f, 480f, 1450f, 2450f)
                at(0.45f, 280f, 1600f, 2500f, av = 0.5f, nasal = 1f, damp = 2.5f)
                at(0.58f, av = 0.4f)
                at(0.63f, av = 0f)
            }
            Word.EIGHT -> { // eɪ t
                at(0f, 480f, 1800f, 2500f)
                at(0.03f, av = 0.9f)
                at(0.08f, 500f, 1800f, 2500f, av = 1f, stretch = true)
                at(0.2f, 420f, 2050f, 2650f)
                at(0.28f, 330f, 2250f, 2850f)
                at(0.31f, 320f, av = 0f)
                burst(0.37f, 0.7f, 4000f, 3000f)
                at(0.37f, 350f, 1800f, 2700f)
                at(0.371f, ah = 0.4f)
                at(0.43f, ah = 0f)
            }
        }
    }.build()

    /** Formant bandwidths, Hz, F1-F5, and F4/F5's fixed frequencies. */
    private val BANDWIDTHS = doubleArrayOf(60.0, 90.0, 150.0, 250.0, 200.0)
    private const val F4 = 3300.0
    private const val F5 = 3750.0

    /** The nasal pole sits here; its zero sits on it (cancelled) until the mouth goes nasal and it moves up. */
    private const val NASAL_POLE = 270.0
    private const val NASAL_ZERO_OPEN = 450.0

    /** Noise levels against the vowel's own RMS, per unit of [Target.af], [Target.ah] and [Burst.amp]. */
    private const val FRIC_REL = 0.5f
    private const val ASP_REL = 0.35f
    private const val BURST_REL = 1.2f

    /** A burst's length and decay. */
    private const val BURST_SECONDS = 0.012f
    private const val BURST_TAU = 0.003f

    /** HUMAN's statement fall, semitones over the word, the wander in cents, the breath in the voice. */
    private const val FALL_SEMIS = 5f
    private const val RISE_SEMIS = 1.5f
    private const val WANDER_CENTS = 40f
    private const val BREATHY = 0.5f

    /** HUMAN's cycle-to-cycle unevenness: pitch (fraction of a period) and level. A machine has none. */
    private const val JITTER = 0.015f
    private const val SHIMMER = 0.15f

    /** The machine's speech-chip frames: the mouth jumps to a new shape this often, rather than gliding. */
    private const val CHIP_FRAME_SECONDS = 0.022f

    /** The speech chip's own sample rate and word size: a low rate held step to step, and few levels. Fades out across HUMAN. */
    private const val CHIP_RATE = 10000f
    private const val CHIP_LEVELS = 48f

    /**
     * The machine's raw pulse, one sample per cycle, at this height at
     * [Dsp.RATE] and in proportion at any other: the resonators' gain falls
     * with the rate, so this keeps the pulse level with the soft one.
     */
    private const val IMPULSE = 8f

    /** The giant: how far the formants fall (VOX's own reach, 0.47, muffled it), how narrow they ring, and its chest. */
    private const val GIANT_FORMANTS = 0.7f
    private const val GIANT_BANDWIDTH = 0.5f
    private const val GIANT_CHEST = 0.35f
    private const val CHEST_HZ = 150.0
    private const val CHEST_BW = 110.0

    /**
     * EFFORT, hushed (0) through talking (0.5) to shouting (1). Hushed keeps
     * real voice under the breath, not a whisper (SWARM's whisper was heard
     * as "demonic"). A shout opens the jaw (F1 up), brightens the buzz, and
     * its pitch rises through the word like an excited count-in where a calm
     * word falls: INFLECT, folded in.
     */
    private const val HUSHED_VOICE = 0.4f
    private const val HUSHED_BREATH = 1.2f
    private const val SHOUT_JAW = 1.25f
    private const val SHOUT_RISE_SEMIS = 4f

    /**
     * STUTTER: up to this many false starts, each the word's opening and this
     * much of its vowel, then a gap. A false start is said at [STUTTER_PACE]
     * of its length, and never takes longer than [STUTTER_LONGEST]: the
     * audition heard "eight" (a vowel first) right and the hissing openings
     * of five, six and seven slow, so a long opening is squeezed to match.
     */
    private const val MAX_STUTTERS = 3
    private const val STUTTER_VOWEL = 0.04f
    private const val STUTTER_PACE = 0.6f
    private const val STUTTER_LONGEST = 0.075f
    private const val STUTTER_GAP = 0.035f

    /** How far DECAY 1 stretches the vowels. */
    private const val MAX_STRETCH = 5f

    private const val CONTROL_BLOCK = 16

    /** Klatt's two-pole resonator, unity gain at DC, in double: the 4x render rate needs the precision. */
    private class Resonator {
        private var a = 1.0; private var b = 0.0; private var c = 0.0
        private var y1 = 0.0; private var y2 = 0.0
        fun set(hz: Double, bw: Double, rate: Int) {
            c = -exp(-2 * PI * bw / rate)
            b = 2 * exp(-PI * bw / rate) * cos(2 * PI * hz / rate)
            a = 1 - b - c
        }
        fun process(x: Double): Double {
            val y = a * x + b * y1 + c * y2
            y2 = y1; y1 = y
            return y
        }
    }

    /** Its inverse: a notch, the nasal zero. */
    private class AntiResonator {
        private var a = 1.0; private var b = 0.0; private var c = 0.0
        private var x1 = 0.0; private var x2 = 0.0
        fun set(hz: Double, bw: Double, rate: Int) {
            val cc = -exp(-2 * PI * bw / rate)
            val bb = 2 * exp(-PI * bw / rate) * cos(2 * PI * hz / rate)
            val aa = 1 - bb - cc
            a = 1 / aa; b = -bb / aa; c = -cc / aa
        }
        fun process(x: Double): Double {
            val y = a * x + b * x1 + c * x2
            x2 = x1; x1 = x
            return y
        }
    }

    /** The chain the voice and breath each run through: nasal zero and pole, then F1-F5. */
    private class Tract {
        val zero = AntiResonator()
        val pole = Resonator()
        val formants = Array(5) { Resonator() }
        fun set(f: DoubleArray, damp: Double, nasal: Double, rate: Int, ring: Double = 1.0) {
            zero.set(NASAL_POLE + (NASAL_ZERO_OPEN - NASAL_POLE) * nasal, 100.0, rate)
            pole.set(NASAL_POLE, 100.0, rate)
            for (k in 0 until 5) formants[k].set(f[k], BANDWIDTHS[k] * ring * (if (k == 0) 1.0 else damp), rate)
        }
        fun process(x: Double): Double {
            var y = pole.process(zero.process(x))
            for (r in formants) y = r.process(y)
            return y
        }
    }

    /** RBJ band-pass, 0 dB at the centre, gained so white noise comes out near unit RMS whatever the width. */
    private class Hiss {
        private var b0 = 0.0; private var a1 = 0.0; private var a2 = 0.0; private var g = 1.0
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        fun set(hz: Double, bw: Double, rate: Int) {
            val w0 = 2 * PI * hz.coerceAtMost(rate * 0.45) / rate
            val alpha = sin(w0) / (2 * (hz / bw).coerceAtLeast(0.3))
            val a0 = 1 + alpha
            b0 = alpha / a0; a1 = -2 * cos(w0) / a0; a2 = (1 - alpha) / a0
            g = sqrt((rate / 2.0) / bw)
        }
        fun process(x: Double): Double {
            val y = b0 * x - b0 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y * g
        }
    }

    /** DECAY as the vowels' stretch: 1 (spoken) at 0, [MAX_STRETCH] at 1. */
    fun stretchFor(decay: Float): Float = MAX_STRETCH.pow(decay.coerceIn(0f, 1f))

    /** The script's targets retimed: each vowel interval lengthened by [stretch]. */
    private fun retime(script: Script, stretch: Float): Pair<List<Target>, List<Burst>> {
        val times = FloatArray(script.targets.size)
        var shift = 0f
        for ((i, target) in script.targets.withIndex()) {
            times[i] = target.t + shift
            if (target.stretch && i + 1 < script.targets.size) shift += (script.targets[i + 1].t - target.t) * (stretch - 1f)
        }
        fun moved(t: Float): Float {
            // A burst moves with the last target at or before it.
            var s = 0f
            for ((i, target) in script.targets.withIndex()) if (target.t <= t) s = times[i] - target.t
            return t + s
        }
        val targets = script.targets.mapIndexed { i, x ->
            Target(times[i], x.f1, x.f2, x.f3, x.av, x.ah, x.af, x.fc, x.fbw, x.nasal, x.damp, x.stretch)
        }
        return targets to script.bursts.map { Burst(moved(it.t), it.amp, it.fc, it.fbw) }
    }

    private fun lerp(a: Float, b: Float, x: Float) = a + (b - a) * x

    /** STUTTER 0..1 as false starts, 0 to [MAX_STUTTERS]. */
    fun stuttersFor(stutter: Float): Int = Math.round(stutter.coerceIn(0f, 1f) * MAX_STUTTERS)

    /**
     * The script with [count] false starts in front: each is the word up to a
     * sliver into its first vowel, closed off, then a breath's gap.
     */
    private fun stuttered(script: Script, count: Int): Script {
        if (count == 0) return script
        val vowelAt = script.targets.firstOrNull { it.stretch }?.t ?: script.targets.last().t
        val cut = vowelAt + STUTTER_VOWEL
        val part = script.targets.filter { it.t < cut }
        val closed = part.last().let {
            Target(cut, it.f1, it.f2, it.f3, 0f, 0f, 0f, it.fc, it.fbw, it.nasal, it.damp, false)
        }
        val pace = minOf(STUTTER_PACE, STUTTER_LONGEST / cut)
        val period = cut * pace + STUTTER_GAP
        val targets = mutableListOf<Target>()
        val bursts = mutableListOf<Burst>()
        fun shifted(x: Target, by: Float, stretch: Boolean, pace: Float = 1f) =
            Target(x.t * pace + by, x.f1, x.f2, x.f3, x.av, x.ah, x.af, x.fc, x.fbw, x.nasal, x.damp, stretch)
        for (n in 0 until count) {
            val by = n * period
            // A false start is never stretched: DECAY holds the real word's vowel, not the stumbles.
            part.forEach { targets += shifted(it, by, false, pace) }
            targets += shifted(closed, by, false, pace)
            targets += shifted(closed, by + STUTTER_GAP, false, pace)
            script.bursts.filter { it.t < cut }.forEach { bursts += Burst(it.t * pace + by, it.amp, it.fc, it.fbw) }
        }
        val by = count * period
        script.targets.forEach { targets += shifted(it, by, it.stretch) }
        script.bursts.forEach { bursts += Burst(it.t + by, it.amp, it.fc, it.fbw) }
        return Script(targets, bursts)
    }

    fun synthesize(
        word: Word,
        noteHz: Float,
        human: Float,
        decay: Float,
        size: Float,
        effort: Float,
        stutter: Float,
        seed: Int,
        rate: Int,
    ): FloatArray {
        val (targets, bursts) = retime(stuttered(scriptFor(word), stuttersFor(stutter)), stretchFor(decay))
        // The child side is VOX's own; the giant side stops at GIANT_FORMANTS, where speech still carries.
        val big = ((size.coerceIn(0f, 1f) - 0.5f) / 0.5f).coerceIn(0f, 1f)
        val throat = if (big > 0f) GIANT_FORMANTS.pow(big) else Vox.throatScale(size)
        val ring = lerp(1f, GIANT_BANDWIDTH, big)
        val chest = GIANT_CHEST * big
        val chestRes = Resonator().apply { set(CHEST_HZ, CHEST_BW, rate) }
        // The hisses move far less than the vowels: a big mouth still says "s".
        val hissScale = throat.pow(0.35f)
        val eff = effort.coerceIn(0f, 1f)
        // Below talking, the voice gives way to breath; above it, the jaw opens.
        val hush = ((0.5f - eff) / 0.5f).coerceIn(0f, 1f)
        val shout = ((eff - 0.5f) / 0.5f).coerceIn(0f, 1f)
        val voiceLevel = lerp(1f, HUSHED_VOICE, hush)
        val jaw = lerp(1f, SHOUT_JAW, shout)
        val length = targets.last().t
        val frames = ((length + 0.08f) * rate).toInt()
        val voiced = FloatArray(frames)
        val aspirated = FloatArray(frames)
        val hissed = FloatArray(frames)
        val voicing = FloatArray(frames)

        val voiceTract = Tract()
        val breathTract = Tract()
        val hiss = Hiss()
        val noise = Dsp.Noise(seed)
        val soften = Dsp.OnePole(rate)
        val random = java.util.Random(seed.toLong())
        val wander = FloatArray(16) { random.nextFloat() * 2f - 1f }
        val h = human.coerceIn(0f, 1f)
        // The ear hears the machine's buzz and grit long after they start to fade: the tone turns human later than the pitch does.
        val tone = h * h

        var k = 0
        var phase = 0.0
        var cur = targets[0]
        var f0 = noteHz
        var cycleStretch = 1f
        var cycleAmp = 1f
        val formants = DoubleArray(5)
        for (i in 0 until frames) {
            val t = i.toFloat() / rate
            if (i % CONTROL_BLOCK == 0) {
                // The machine updates its mouth in frames, like an 80s speech chip; the person glides.
                val frame = CHIP_FRAME_SECONDS * (1f - h) * (1f - h)
                val tq = if (frame > 0.002f) (t / frame).toInt() * frame else t
                while (k + 1 < targets.size - 1 && targets[k + 1].t <= tq) k++
                val p = targets[k]
                val q = targets[minOf(k + 1, targets.size - 1)]
                val x = if (q.t > p.t) ((tq - p.t) / (q.t - p.t)).coerceIn(0f, 1f) else 1f
                cur = Target(
                    t, lerp(p.f1, q.f1, x), lerp(p.f2, q.f2, x), lerp(p.f3, q.f3, x),
                    lerp(p.av, q.av, x), lerp(p.ah, q.ah, x), lerp(p.af, q.af, x),
                    lerp(p.fc, q.fc, x), lerp(p.fbw, q.fbw, x), lerp(p.nasal, q.nasal, x), lerp(p.damp, q.damp, x), false,
                )
                formants[0] = (cur.f1 * jaw * throat).toDouble(); formants[1] = (cur.f2 * throat).toDouble()
                formants[2] = (cur.f3 * throat).toDouble()
                formants[3] = F4 * throat; formants[4] = F5 * throat
                voiceTract.set(formants, cur.damp.toDouble(), cur.nasal.toDouble(), rate, ring.toDouble())
                breathTract.set(formants, cur.damp.toDouble(), cur.nasal.toDouble(), rate)
                hiss.set((cur.fc * hissScale).toDouble(), (cur.fbw * hissScale).toDouble(), rate)
                // HUMAN: a statement's fall through the word, a little rise first, and a slow wander.
                val frac = (t / length).coerceIn(0f, 1f)
                // A calm word lifts then falls like a statement; a shout climbs through the word like a call.
                // Both sit around the note, and the machine stays on it either way.
                val statement = RISE_SEMIS * sin(PI.toFloat() * minOf(frac * 2f, 1f)) - FALL_SEMIS * frac * frac
                val call = SHOUT_RISE_SEMIS * (1.5f * frac - 0.5f)
                var semis = h * lerp(statement, call, shout)
                val w = t * 6f
                val wi = w.toInt()
                val wx = w - wi
                semis += h * WANDER_CENTS / 100f * lerp(wander[wi % wander.size], wander[(wi + 1) % wander.size], wx)
                f0 = noteHz * 2f.pow(semis / 12f)
            }
            val prev = phase
            phase += f0 * cycleStretch / rate
            if (phase.toLong() != prev.toLong()) {
                // Each new vocal-cord cycle, a person's is never quite the last one's length or strength.
                cycleStretch = 1f + h * JITTER * (random.nextFloat() * 2f - 1f)
                cycleAmp = 1f + h * SHIMMER * (random.nextFloat() * 2f - 1f)
            }
            // The machine buzzes with a raw pulse, bright to the top, as a speech chip's did; the person's is rounder.
            val impulse = if (phase.toLong() != prev.toLong()) IMPULSE * rate / Dsp.RATE else 0f
            // A shout's buzz is brighter: the vocal folds snap shut harder.
            val pulse = soften.lp(Vox.glottal(phase), lerp(700f, 5000f, shout)) * lerp(3f, 1.2f, shout)
            val source = lerp(impulse, pulse, tone) * cycleAmp
            val n = noise.next()
            voicing[i] = cur.av
            val y = voiceTract.process((source * cur.av * voiceLevel).toDouble())
            // The giant's chest: a low resonance under the voice, a boom rather than a muffle.
            voiced[i] = (y + chest * 2.5 * chestRes.process(y)).toFloat()
            // Breath: the consonants' own, and a person's breathy voice under every vowel.
            val breath = BREATHY * h * h + HUSHED_BREATH * hush
            aspirated[i] = breathTract.process((n * (cur.ah + breath * cur.av)).toDouble()).toFloat()
            hissed[i] = hiss.process((noise.next() * cur.af).toDouble()).toFloat()
        }

        // The noises are set against the vowel's own level, not fixed.
        var e = 0.0; var count = 0
        for (i in 0 until frames) if (voicing[i] >= 0.8f) { e += voiced[i] * voiced[i]; count++ }
        // Against a talking voice's level, so a hushed voice's breath does not shrink with it.
        val vowelRms = sqrt(e / maxOf(1, count)).toFloat() / voiceLevel
        var ea = 0.0; var wa = 0.0
        val probe = Tract()
        // The breath chain's unit level: the same formants, white noise in, measured once.
        run {
            formants[0] = 500.0; formants[1] = 1500.0; formants[2] = 2500.0; formants[3] = F4; formants[4] = F5
            probe.set(formants, 1.0, 0.0, rate)
            val pn = Dsp.Noise(seed + 1)
            for (j in 0 until rate / 10) { val y = probe.process(pn.next().toDouble()); if (j > rate / 50) { ea += y * y; wa++ } }
        }
        val breathUnit = sqrt(ea / maxOf(1.0, wa)).toFloat()
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            out[i] = voiced[i] + aspirated[i] * ASP_REL * vowelRms / breathUnit + hissed[i] * FRIC_REL * vowelRms
        }
        // The bursts: a short puff of noise, band-passed where the tongue lets go.
        val burstNoise = Dsp.Noise(seed + 2)
        for (b in bursts) {
            val bh = Hiss()
            bh.set(b.fc.toDouble(), b.fbw.toDouble(), rate)
            val start = (b.t * rate).toInt()
            val n = (BURST_SECONDS * rate).toInt()
            for (j in 0 until n) {
                val idx = start + j
                if (idx !in 0 until frames) break
                val env = exp(-j / (BURST_TAU * rate))
                out[idx] += (bh.process(burstNoise.next().toDouble()) * env * b.amp * BURST_REL * vowelRms).toFloat()
            }
        }
        // The machine's speech chip: held at its own low rate, and stepped to a few levels, fading out across HUMAN.
        val chip = 1f - tone
        var peak = 0f
        for (v in out) peak = maxOf(peak, kotlin.math.abs(v))
        if (chip > 0f && peak > 0f) {
            val hold = rate / CHIP_RATE
            var held = 0f
            var next = 0f
            for (i in 0 until frames) {
                if (i >= next) {
                    held = Math.round(out[i] / peak * CHIP_LEVELS) / CHIP_LEVELS * peak
                    next += hold
                }
                out[i] = lerp(out[i], held, chip)
            }
        }
        return out
    }
}
