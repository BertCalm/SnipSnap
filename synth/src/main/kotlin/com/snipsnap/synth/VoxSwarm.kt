package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * SWARM: a crowd, VOX round 3's third voice (docs/SYNTH_ROADMAP.md S11,
 * "Weird").
 *
 * Two to sixteen mouths, men and women, each with its own throat, pitch,
 * timing and place in the stereo field.
 *
 * - **WORD** is what the crowd says together: hey, ho, yeah, huh, ooh, aah.
 * - **CROWD** is how many: 2 to 16, most of the change at the small end,
 *   where you can pick out the people. Measured on a murmur, the envelope's
 *   lumpiness falls 0.88, 0.66, 0.54 for 2, 4, 8 mouths and stays at 0.55
 *   for 16: past eight, a crowd is a crowd.
 * - **LOOSE** takes them from one word said together (a chant, a crowd
 *   shout: full level in 40 ms) through a ragged crowd (the start smeared
 *   over most of a second) to everyone talking over each other, a
 *   murmuring room of random syllables.
 * - **EFFORT** goes hushed, talk, shout. A shout is higher, brighter and
 *   pushed. The quiet end is hushed voices: a soft voice, a third of its
 *   full strength and with fewer upper harmonics, under breath, like a
 *   library. It was a whisper, and a room of whispers was a horror film:
 *   the first, noise through the voice's own narrow resonances, moaned
 *   ("demonic"); rebuilt to hiss rather than moan, a room of it was
 *   "still scary sounding". Voice is what makes a quiet crowd people.
 * - **STUTTER** grabs the word's opening up to four times, together when
 *   the crowd is tight, scattered when it is loose.
 *
 * Stereo, like CHOIR. Each mouth's draws are seeded from the recipe, so a
 * pad regenerates to the byte.
 */
internal object VoxSwarm {

    /** A point on a mouth's path: when (s), F1-F3 (Hz), level, and how much of it is breath (an h). */
    class Seg(val t: Float, val f1: Float, val f2: Float, val f3: Float, val amp: Float, val asp: Float = 0f)

    /** The words, WORD's order: each a path of F1-F3 through its sounds. */
    enum class Word(val path: List<Seg>) {
        HEY(listOf(Seg(0f, 550f, 1800f, 2550f, 0.3f, 1f), Seg(0.07f, 550f, 1800f, 2550f, 1f), Seg(0.2f, 450f, 2000f, 2700f, 1f), Seg(0.32f, 320f, 2250f, 3000f, 0.9f))),
        HO(listOf(Seg(0f, 500f, 900f, 2500f, 0.3f, 1f), Seg(0.07f, 500f, 900f, 2500f, 1f), Seg(0.3f, 420f, 800f, 2400f, 1f), Seg(0.4f, 380f, 780f, 2350f, 0.95f))),
        YEAH(listOf(Seg(0f, 280f, 2250f, 3000f, 0.5f), Seg(0.1f, 550f, 1800f, 2550f, 1f), Seg(0.26f, 750f, 1250f, 2600f, 1f), Seg(0.36f, 720f, 1200f, 2600f, 1f))),
        HUH(listOf(Seg(0f, 600f, 1200f, 2500f, 0.3f, 1f), Seg(0.06f, 620f, 1200f, 2500f, 1f), Seg(0.16f, 600f, 1150f, 2500f, 0.9f))),
        OOH(listOf(Seg(0f, 330f, 780f, 2300f, 0.5f), Seg(0.1f, 330f, 780f, 2300f, 1f))),
        AAH(listOf(Seg(0f, 750f, 1200f, 2600f, 0.5f), Seg(0.1f, 750f, 1200f, 2600f, 1f))),
    }

    fun wordFor(word: Float): Word = Word.entries[Math.round(word.coerceIn(0f, 1f) * (Word.entries.size - 1))]

    const val MIN_MOUTHS = 2
    const val MAX_MOUTHS = 16

    /** CROWD as mouths, squared so most of the travel is at the small end, where it can be heard. */
    fun mouthsFor(crowd: Float): Int {
        val c = crowd.coerceIn(0f, 1f)
        return MIN_MOUTHS + Math.round((MAX_MOUTHS - MIN_MOUTHS) * c * c)
    }

    const val MAX_STUTTERS = 4

    fun stuttersFor(stutter: Float): Int = Math.round(stutter.coerceIn(0f, 1f) * MAX_STUTTERS)

    // Babble: random syllables, a consonant's locus then a vowel.
    private val VOWELS = listOf(
        floatArrayOf(750f, 1200f, 2600f), floatArrayOf(550f, 1800f, 2550f), floatArrayOf(300f, 2250f, 3000f),
        floatArrayOf(500f, 900f, 2500f), floatArrayOf(330f, 780f, 2300f), floatArrayOf(600f, 1200f, 2500f),
    )

    /** F1, F2, F3, level while it is said, breath. */
    private val CONSONANTS = listOf(
        floatArrayOf(250f, 1000f, 2400f, 0.35f, 0f), // m, n
        floatArrayOf(250f, 800f, 2200f, 0.15f, 0f), // b
        floatArrayOf(250f, 1800f, 2700f, 0.15f, 0f), // d
        floatArrayOf(360f, 1000f, 2600f, 0.5f, 0f), // l
        floatArrayOf(300f, 610f, 2200f, 0.4f, 0f), // w
        floatArrayOf(280f, 2250f, 3000f, 0.45f, 0f), // y
        floatArrayOf(500f, 1500f, 2500f, 0.25f, 1f), // h
    )

    /**
     * One mouth's talk: words of two to four random syllables, 0.12-0.26 s each, with pauses between. The rest
     * come in up to a quarter second late; the first mouth, [startNow], talks from the strike.
     */
    private fun babble(random: Random, total: Float, startNow: Boolean): List<Seg> {
        val out = ArrayList<Seg>()
        out.add(Seg(0f, 500f, 1500f, 2500f, 0f))
        var t = if (startNow) 0f else random.nextFloat() * 0.25f
        out.add(Seg(t, 500f, 1500f, 2500f, 0f))
        while (t < total) {
            repeat(2 + random.nextInt(3)) {
                val c = CONSONANTS[random.nextInt(CONSONANTS.size)]
                val v = VOWELS[random.nextInt(VOWELS.size)]
                val dur = 0.12f + 0.14f * random.nextFloat()
                out.add(Seg(t + 0.012f, c[0], c[1], c[2], c[3], c[4]))
                out.add(Seg(t + 0.055f, v[0], v[1], v[2], 1f))
                out.add(Seg(t + dur * 0.85f, v[0], v[1], v[2], 0.8f))
                t += dur
            }
            val last = out.last()
            out.add(Seg(t + 0.03f, last.f1, last.f2, last.f3, 0f))
            t += 0.06f + 0.3f * random.nextFloat()
            out.add(Seg(t, last.f1, last.f2, last.f3, 0f))
        }
        return out
    }

    private class Mouth(
        val high: Boolean,
        val detuneCents: Float,
        val throat: Float,
        pan: Float,
        val onset: Float,
        val babble: List<Seg>?,
        val wander: FloatArray,
        val wanderSemis: Float,
        val grab: Float,
        val gap: Float,
        seed: Int,
        rate: Int,
    ) {
        var phase = 0.0
        val formants = Array(4) { Dsp.Biquad() }
        val noise = Dsp.Noise(seed)
        val airLow = Dsp.OnePole(rate)
        val airHigh = Dsp.OnePole(rate)
        val soft = Dsp.OnePole(rate)
        var f0 = 0f
        var amp = 0f
        var asp = 0f
        var env = 0f

        /** Where on its path the mouth last was: time only runs forward between stutters, so the search does too. */
        var cursor = 0
        val at = FloatArray(5)
        val gainL = cos((pan + 1f) * PI.toFloat() / 4f)
        val gainR = sin((pan + 1f) * PI.toFloat() / 4f)
    }

    private const val CONTROL_BLOCK = 16

    /**
     * The breath hiss over a hushed voice, 2-6.5 kHz, at full hush. Set when the quiet end was a whisper:
     * at 0 it centred at 3.1 kHz, at this 0.06 at 3.9-4.7, at 0.2 at 5.4-7; 0.315 above 3.5 kHz was static.
     */
    private const val WHISPER_AIR = 0.06f

    /** How much of a voice a hushed mouth keeps (the rest is breath), and how much of the whisper's treatment. */
    private const val HUSHED_VOICE = 0.35f
    private const val HUSHED_BREATH = 0.5f
    private const val HUSHED_WHISPER = 0.35f

    /** What each stutter grabs of the word's opening, and the gap after it. */
    private const val GRAB = 0.09f
    private const val GAP = 0.035f

    /** F1-F3 and a fixed F4: bandwidths and levels. */
    private val BW = floatArrayOf(80f, 100f, 130f, 170f)
    private val GAIN = floatArrayOf(1f, 0.7f, 0.45f, 0.25f)
    private const val F4_HZ = 3300f

    fun synthesize(
        noteHz: Float,
        word: Word,
        crowd: Float,
        loose: Float,
        effort: Float,
        decay: Float,
        stutter: Float,
        seed: Int,
        rate: Int,
    ): FloatArray {
        val random = Random(seed)
        val mouthCount = mouthsFor(crowd)
        val width = 0.4f + 0.6f * crowd.coerceIn(0f, 1f)
        val stutters = stuttersFor(stutter)
        val length = Vox.lengthFor(decay)
        val hold = length * Vox.holdFractionFor(decay)
        // LOOSE's lower half loosens the chant; its upper half turns mouths, one by one, to talking.
        val chantLoose = (loose / 0.5f).coerceIn(0f, 1f)
        val babbleShare = ((loose - 0.5f) / 0.5f).coerceIn(0f, 1f)
        val shout = ((effort - 0.5f) / 0.5f).coerceIn(0f, 1f)
        // How hushed: 1 at EFFORT 0, gone by 0.4.
        val hushed = 1f - ((effort - 0.1f) / 0.3f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        val voicing = 1f - (1f - HUSHED_VOICE) * hushed
        val breath = 0.12f + HUSHED_BREATH * hushed
        val maxOnset = 0.004f + 0.15f * chantLoose
        val lead = stutters * (GRAB + GAP)
        val total = (maxOnset + lead * 1.3f + length * 1.3f).coerceAtMost(Vox.MAX_SECONDS)
        val frames = (total * rate).toInt().coerceAtLeast(64)
        val out = FloatArray(frames * 2)

        val pans = List(mouthCount) { -1f + 2f * (it + 0.5f) / mouthCount }.shuffled(random)
        val mouths = List(mouthCount) { i ->
            val high = i % 2 == 1
            val babbles = babbleShare > 0f && random.nextFloat() < babbleShare
            Mouth(
                high = high,
                detuneCents = (random.nextFloat() * 2f - 1f) * (10f + 50f * chantLoose),
                throat = if (high) 1.12f + 0.1f * random.nextFloat() else 0.95f + 0.1f * random.nextFloat(),
                pan = pans[i] * width,
                // The first mouth is always on time, so the strike is never silent: a pad that starts late
                // sounds late, and the classifier, reading the first 93 ms, heard two or three late talkers as nothing.
                onset = if (i == 0) 0f else random.nextFloat() * maxOnset,
                babble = if (babbles) babble(random, total, startNow = i == 0) else null,
                wander = FloatArray((total * 4f).toInt() + 3) { random.nextFloat() * 2f - 1f },
                wanderSemis = if (babbles) 3f + 3f * random.nextFloat() else 0.15f + 0.6f * chantLoose,
                grab = GRAB * (1f + (random.nextFloat() - 0.5f) * 0.6f * loose),
                gap = GAP * (1f + (random.nextFloat() - 0.5f) * 0.8f * loose),
                seed = 100 + i,
                rate = rate,
            )
        }
        val env = Dsp.Env(attackSeconds = 0.03f - 0.022f * shout, decay2T60 = length - hold, holdSeconds = hold)
        val scale = 1f / sqrt(mouthCount.toFloat())
        // Some of a whisper's treatment: resonances a little wider and weaker, the low end a little thinner,
        // breath hissing above them. At full strength, with no voice under it, it was a haunted room.
        val hush = HUSHED_WHISPER * hushed
        val bwScale = 1f + 1.2f * hush
        val f1Gain = 1f - 0.5f * hush
        val airLevel = WHISPER_AIR * hush
        val hz = FloatArray(4)

        for (i in 0 until frames) {
            val t = i.toFloat() / rate
            if (i % CONTROL_BLOCK == 0) {
                for (m in mouths) {
                    val tv = t - m.onset
                    val leadV = stutters * (m.grab + m.gap)
                    var pt: Float
                    var gate: Float
                    when {
                        tv < 0f -> { pt = 0f; gate = 0f }
                        tv < leadV -> {
                            val k = tv % (m.grab + m.gap)
                            pt = k
                            gate = if (k < m.grab) minOf(1f, k / 0.004f, (m.grab - k) / 0.006f).coerceAtLeast(0f) else 0f
                        }
                        else -> { pt = tv - leadV; gate = 1f }
                    }
                    sample(m.babble ?: word.path, pt, m)
                    hz[0] = m.at[0] * (1f + 0.2f * shout + 0.15f * hush)
                    hz[1] = m.at[1]
                    hz[2] = m.at[2]
                    hz[3] = F4_HZ
                    for (k in 0 until 4) {
                        val fk = (hz[k] * m.throat).coerceAtMost(rate * 0.45f)
                        m.formants[k].bandpass(fk, (fk / (BW[k] * bwScale)).coerceAtLeast(1.2f), rate)
                    }
                    // Pitch: the note (the women an octave up), each mouth's detune and wander,
                    // and a shout's rise and fall on the chanted word.
                    val x = t * 4f
                    val w = x.toInt().coerceAtMost(m.wander.size - 2)
                    val s = (1f - cos(PI.toFloat() * (x - w))) / 2f
                    var semis = m.wanderSemis * (m.wander[w] * (1f - s) + m.wander[w + 1] * s)
                    if (m.babble == null) semis += shout * (1.5f * minOf(pt / 0.1f, 1f) - 3f * ((pt - 0.1f) / 0.4f).coerceIn(0f, 1f))
                    m.f0 = noteHz * (if (m.high) 2f else 1f) * 2f.pow((m.detuneCents / 100f + semis + 4f * shout) / 12f)
                    m.amp = m.at[3] * gate
                    m.asp = m.at[4]
                    m.env = if (tv < leadV) 1f else env.at((tv - leadV).coerceAtLeast(0f))
                }
            }
            var left = 0f
            var right = 0f
            for (m in mouths) {
                m.phase += m.f0 / rate
                val pulse = Vox.glottal(m.phase)
                // A hushed voice is soft: its pulse loses its upper harmonics.
                val g = if (hushed > 0f) pulse + hushed * (m.soft.lp(pulse, 700f) * 2.5f - pulse) else pulse
                val n = m.noise.next()
                val src = voicing * g * (1f - m.asp) + (breath + 0.8f * m.asp) * 0.5f * n
                var y = 0f
                for (k in 0 until 4) y += (if (k == 0) f1Gain else 1f) * GAIN[k] * m.formants[k].process(src)
                // The hiss of breath, above the resonances, following the syllables.
                if (airLevel > 0f) y += airLevel * m.airHigh.lp(n - m.airLow.lp(n, 2000f), 6500f)
                y *= m.amp * m.env
                left += y * m.gainL
                right += y * m.gainR
            }
            out[2 * i] = left * scale
            out[2 * i + 1] = right * scale
        }
        // A hushed crowd loses a little of its low end.
        if (hush > 0f) {
            val lowL = Dsp.Biquad().apply { lowShelf(400f, -8f * hush, rate) }
            val lowR = Dsp.Biquad().apply { lowShelf(400f, -8f * hush, rate) }
            for (f in 0 until frames) {
                out[2 * f] = lowL.process(out[2 * f])
                out[2 * f + 1] = lowR.process(out[2 * f + 1])
            }
        }
        // A shout is brighter and pushed: a high shelf and a little saturation.
        if (shout > 0f) {
            val shelfL = Dsp.Biquad().apply { highShelf(1800f, 10f * shout, rate) }
            val shelfR = Dsp.Biquad().apply { highShelf(1800f, 10f * shout, rate) }
            for (f in 0 until frames) {
                out[2 * f] = shelfL.process(out[2 * f])
                out[2 * f + 1] = shelfR.process(out[2 * f + 1])
            }
            Dsp.normalize(out, 1f)
            val drive = 1f + 2f * shout
            val norm = tanh(drive)
            for (k in out.indices) out[k] = tanh(out[k] * drive) / norm
        }
        return out
    }

    /**
     * The mouth's F1-F3, level and breath at [t] (s) on [path], into [Mouth.at]: a smoothstep between points,
     * as a mouth moves. Searches on from where it last was, back to the start only when a stutter restarts it.
     */
    private fun sample(path: List<Seg>, t: Float, m: Mouth) {
        var k = m.cursor
        if (k > path.size - 2 || t < path[k].t) k = 0
        while (k < path.size - 2 && t > path[k + 1].t) k++
        m.cursor = k
        val p0 = path[k]
        val p1 = path[k + 1]
        val x = when {
            t <= p0.t -> 0f
            t >= p1.t -> 1f
            else -> ((t - p0.t) / (p1.t - p0.t)).let { it * it * (3f - 2f * it) }
        }
        m.at[0] = p0.f1 + (p1.f1 - p0.f1) * x
        m.at[1] = p0.f2 + (p1.f2 - p0.f2) * x
        m.at[2] = p0.f3 + (p1.f3 - p0.f3) * x
        m.at[3] = p0.amp + (p1.amp - p0.amp) * x
        m.at[4] = p0.asp + (p1.asp - p0.asp) * x
    }
}
