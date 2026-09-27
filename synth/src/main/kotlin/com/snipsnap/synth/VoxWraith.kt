package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * WRAITH: sine-wave speech, VOX round 3's second voice
 * (docs/SYNTH_ROADMAP.md S11, "Weird").
 *
 * No voice at all: three pure tones glide along the paths a voice's first
 * three formants take through a word. The ear hears ghostly whistling
 * until it catches the word in it; heard at the audition as "slightly
 * abstract and left to interpretation", which is the point.
 *
 * - **WORD** picks the word: why, you, no, hello, wow, yeah.
 * - **DECAY** is how long the word takes: short, a hit; long, the word
 *   slowed into a pad.
 * - **TUNED** crossfades the gliding tones with the same tones stepping
 *   onto the harmonics of TUNE's note: 0 is ghostly speech, 1 sits in a
 *   key, between plays both.
 * - **ALIEN** moves the tones further than a mouth can, pushes F1 and F2
 *   across each other and gives each a beating twin.
 * - **STUTTER** grabs the word's opening up to four times first.
 * - **BREATH** turns the tones to whispered bands of noise.
 *
 * Pure tones have no harmonics to fold, so WRAITH renders at [Dsp.RATE],
 * not the 4x the buzzing throats need.
 */
internal object VoxWraith {

    /** A point on a word's path: when (seconds, spoken), F1-F3 (Hz) and how open the mouth is. */
    class Point(val t: Float, val f1: Float, val f2: Float, val f3: Float, val amp: Float = 1f)

    private fun w(t: Float) = Point(t, 300f, 610f, 2200f, 0.5f)
    private fun y(t: Float) = Point(t, 280f, 2250f, 3000f, 0.6f)
    private fun n(t: Float) = Point(t, 250f, 1000f, 2400f, 0.35f)
    private fun h(t: Float) = Point(t, 550f, 1800f, 2550f, 0f)
    private fun l(t: Float) = Point(t, 360f, 1000f, 2600f, 0.6f)
    private fun aa(t: Float) = Point(t, 750f, 1200f, 2600f)
    private fun ee(t: Float) = Point(t, 300f, 2250f, 3000f, 0.8f)
    private fun eh(t: Float) = Point(t, 550f, 1800f, 2550f)
    private fun oh(t: Float) = Point(t, 500f, 900f, 2500f)
    private fun oo(t: Float) = Point(t, 330f, 780f, 2300f, 0.8f)
    private fun end(p: Point, t: Float) = Point(t, p.f1, p.f2, p.f3, 0f)

    /** The words, WORD's order. Each path is F1-F3 through its sounds, as a speaker's formants move. */
    enum class Word(val path: List<Point>) {
        WHY(listOf(w(0f), aa(0.12f), aa(0.22f), ee(0.42f), end(ee(0f), 0.55f))),
        YOU(listOf(y(0f), y(0.05f), oo(0.25f), oo(0.4f), end(oo(0f), 0.52f))),
        NO(listOf(n(0f), n(0.06f), oh(0.16f), oh(0.3f), oo(0.45f), end(oo(0f), 0.56f))),
        HELLO(listOf(h(0f), eh(0.06f), eh(0.16f), l(0.24f), l(0.28f), oh(0.38f), oo(0.55f), end(oo(0f), 0.66f))),
        WOW(listOf(w(0f), aa(0.14f), aa(0.26f), oo(0.42f), w(0.5f), end(w(0f), 0.58f))),
        YEAH(listOf(y(0f), eh(0.12f), aa(0.3f), aa(0.4f), end(aa(0f), 0.52f))),
        ;

        val seconds: Float get() = path.last().t
    }

    fun wordFor(word: Float): Word = Word.entries[Math.round(word.coerceIn(0f, 1f) * (Word.entries.size - 1))]

    /** How far DECAY can slow a word: 7x, a half-second word held over the pad's four seconds. */
    private const val MAX_STRETCH = 7f
    private const val MIN_STRETCH = 0.45f

    /** The formants' levels, F1 to F3. */
    private val AMPS = floatArrayOf(1f, 0.55f, 0.3f)

    /** What each stutter grabs, of the word as spoken, the gap after it, and the most there are. */
    private const val STUTTER_GRAB = 0.09f
    private const val STUTTER_GAP = 0.035f
    const val MAX_STUTTERS = 4

    /** ALIEN: how far past the word's own motion, how far F1 rises and F2 falls, and the twin's beat per formant. */
    private const val ALIEN_REACH = 2.5f
    private const val ALIEN_F1_RISE = 0.9f
    private const val ALIEN_F2_FALL = 0.45f
    private const val ALIEN_BEAT_HZ = 11f

    private const val CONTROL_BLOCK = 16

    /** DECAY as how many times slower than spoken the word goes, so the word lasts about [Vox.lengthFor]. */
    fun stretchFor(word: Word, decay: Float): Float =
        (Vox.lengthFor(decay) / word.seconds).coerceIn(MIN_STRETCH, MAX_STRETCH)

    fun stuttersFor(stutter: Float): Int = Math.round(stutter.coerceIn(0f, 1f) * MAX_STUTTERS)

    fun synthesize(
        noteHz: Float,
        transpose: Float,
        word: Word,
        decay: Float,
        tuned: Float,
        alien: Float,
        stutter: Float,
        breath: Float,
        rate: Int,
    ): FloatArray {
        val path = word.path
        val stretch = stretchFor(word, decay)
        val repeats = stuttersFor(stutter)
        val grab = STUTTER_GRAB * stretch
        val lead = repeats * (grab + STUTTER_GAP)
        val frames = ((lead + word.seconds * stretch + 0.05f).coerceAtMost(Vox.MAX_SECONDS) * rate).toInt()
        val out = FloatArray(frames)

        // Equal-power crossfade between the gliding tones and the stepping ones.
        val glideGain = cos(tuned.coerceIn(0f, 1f) * PI.toFloat() / 2f)
        val stepGain = sin(tuned.coerceIn(0f, 1f) * PI.toFloat() / 2f)

        val glidePhase = DoubleArray(3)
        val stepPhase = DoubleArray(3)
        val twinPhase = DoubleArray(3)
        val noise = Dsp.Noise(53)
        val bands = Array(3) { Dsp.Biquad() }
        val f = FloatArray(3)
        val step = FloatArray(3)
        val a = FloatArray(3)
        // Each formant's middle over the word: ALIEN exaggerates the motion around it.
        val centre = FloatArray(3) { k -> path.map { formant(it, k) }.average().toFloat() * transpose }

        for (i in 0 until frames) {
            val t = i.toFloat() / rate
            if (i % CONTROL_BLOCK == 0) {
                // The stutter grabs the word's opening; then the whole word plays.
                var wt: Float
                var gate: Float
                if (t < lead) {
                    val k = t % (grab + STUTTER_GAP)
                    wt = k / stretch
                    gate = if (k < grab) minOf(1f, k / 0.004f, (grab - k) / 0.006f).coerceAtLeast(0f) else 0f
                } else {
                    wt = (t - lead) / stretch
                    gate = 1f
                }
                wt = wt.coerceAtMost(word.seconds)
                for (k in 0 until 3) {
                    var hz = at(path, wt) { formant(it, k) } * transpose
                    // ALIEN: motion past what a mouth can make, F1 and F2 pushed across each other.
                    hz = centre[k] + (hz - centre[k]) * (1f + ALIEN_REACH * alien)
                    if (k == 0) hz *= 1f + ALIEN_F1_RISE * alien
                    if (k == 1) hz *= 1f - ALIEN_F2_FALL * alien
                    hz = hz.coerceIn(60f, rate * 0.4f)
                    f[k] = hz
                    step[k] = maxOf(1, Math.round(hz / noteHz)) * noteHz
                    a[k] = AMPS[k] * at(path, wt) { it.amp } * gate
                    bands[k].bandpass(hz, 8f, rate)
                }
            }
            val n = noise.next()
            var y = 0f
            for (k in 0 until 3) {
                glidePhase[k] += f[k] / rate
                stepPhase[k] += step[k] / rate
                val tone = glideGain * sin(2.0 * PI * glidePhase[k]).toFloat() + stepGain * sin(2.0 * PI * stepPhase[k]).toFloat()
                // ALIEN's twin, a few Hz off the gliding tone: it beats, metallic.
                twinPhase[k] += (f[k] + ALIEN_BEAT_HZ * (k + 1) * alien) / rate
                val twin = alien * sin(2.0 * PI * twinPhase[k]).toFloat()
                val air = breath * 3f * bands[k].process(n)
                y += a[k] * ((1f - 0.6f * breath) * (tone + twin) / (1f + alien) + air)
            }
            out[i] = y
        }
        return out
    }

    private fun formant(pt: Point, k: Int) = when (k) { 0 -> pt.f1; 1 -> pt.f2; else -> pt.f3 }

    /** A value along the path at [t] (seconds, spoken): a smoothstep between points, as a mouth moves. */
    private inline fun at(path: List<Point>, t: Float, v: (Point) -> Float): Float {
        if (t <= path.first().t) return v(path.first())
        for (k in 0 until path.size - 1) {
            val p0 = path[k]
            val p1 = path[k + 1]
            if (t <= p1.t) {
                val x = ((t - p0.t) / (p1.t - p0.t)).coerceIn(0f, 1f)
                return v(p0) + (v(p1) - v(p0)) * x * x * (3f - 2f * x)
            }
        }
        return v(path.last())
    }

    /** TUNE's semitones from the middle, as a ratio to shift the word by. */
    fun transposeFor(semitonesFromMiddle: Int): Float = 2f.pow(semitonesFromMiddle / 12f)
}
