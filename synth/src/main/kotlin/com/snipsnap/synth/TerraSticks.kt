package com.snipsnap.synth

import com.snipsnap.synth.TerraSticksTables.Stroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * TERRA's stick-struck and shaken voices, the Japanese festival set: ODAIKO (two big drums), NAGADO
 * (the mid barrel drum), SHIME (the small rope-tensioned drum), KA (the stick on a drum's rim), KANE
 * (the atarigane hand gong), CHAPPA (hand cymbals), BINZASARA (a string of wooden slats), COWBELL (a
 * clapper bell, shaken) and WATER_DRUM. Each is resynthesised from the owner's reference recordings
 * (2026-11-19 and -20: loops and single hits, measured only, never shipped), the way [TerraFitted]'s
 * hand drums were, then picked by ear over six listening rounds (docs/TERRA_STICKS.md).
 *
 * The characteristic gesture: **the stick, the clapper or the slats land, once or as a train → the
 * object's own notes ring, each from the level and phase that landing gave it → band noise fills
 * what the notes leave out of each landing's first tens of milliseconds → silence.**
 *
 * Why it is built this way:
 * - **Notes, measured.** A stroke's strongest spectral peaks, each with its own ring time. Each
 *   landing gives each note a sine and a cosine level, solved by least squares against the
 *   reference's waveform, so a note starts where the reference's did.
 * - **A landing is not always one.** The binzasara is 21 slat clicks over 185 ms and the cowbell is
 *   three clapper strikes and a rebound before it rings. Fitted as one strike they read as "a
 *   single clack" and "a regular bell" (the owner's rounds 1 to 3); so a stroke is a list of
 *   [TerraSticksTables.Hit]s, and the cowbell's notes get their own levels at every strike.
 * - **The residual, fitted.** As [TerraFitted]: per third-octave band, noise with its own level and
 *   decay, solved so the band's energy matches the reference in an attack and a body window.
 * - **Velocity is the strike.** A softer landing drives the upper notes less than the lowest
 *   (`v^(1 + 0.45 log2(f / f0))`, capped at v^3), the noise bands by [TerraFitted]'s exponents and
 *   lands a longer contact, so a soft hit is darker, not only quieter.
 * - **A take is another landing.** A later take re-draws the noise and moves the contact, each
 *   note's level and each later landing's time a little; take 0 is the stroke as picked.
 *
 * Literal: the note tables, the landing times and the residual bands (measured). Proposed: the macro
 * ranges, the velocity exponents and the take spreads. Not simulated: the stick, the room, and the
 * second player of the references' ensemble hits.
 */
internal object TerraSticks {

    val VOICES = setOf(TerraVoice.ODAIKO, TerraVoice.NAGADO, TerraVoice.SHIME, TerraVoice.KA, TerraVoice.KANE,
        TerraVoice.CHAPPA, TerraVoice.BINZASARA, TerraVoice.COWBELL, TerraVoice.WATER_DRUM, TerraVoice.FAN)

    /** Under the classifier's 1.5 s, past which a render is filed as a phrase ([TerraFitted.SECONDS]). */
    private fun seconds(voice: TerraVoice): Double = when (voice) {
        TerraVoice.ODAIKO, TerraVoice.CHAPPA, TerraVoice.COWBELL -> 1.45
        TerraVoice.NAGADO -> 1.2
        TerraVoice.KANE -> 1.0
        TerraVoice.SHIME, TerraVoice.WATER_DRUM -> 0.8
        TerraVoice.KA, TerraVoice.BINZASARA, TerraVoice.FAN -> 0.5
        else -> error("$voice is not a TERRA stick voice")
    }

    fun frames(voice: TerraVoice, rate: Int): Int = (seconds(voice) * rate).toInt()

    /** The longest a RING is printed: past it even the odaiko's longest note at DECAY's top is under -60 dB. Shape. */
    const val MAX_RING_SECONDS = 6.0

    /**
     * How long [voice] at [m] rings when left to ([TerraArticulation.RING]): its longest note's T60 at DECAY, plus
     * 0.3 s for the fade, at least half a second past the hit's own length and at most [MAX_RING_SECONDS]. The hit is
     * cut under the classifier's 1.5 s; a ring is a phrase pad, so it keeps the tail the reference has (the first
     * odaiko's notes ring 2 to 3 s).
     */
    fun ringSeconds(voice: TerraVoice, m: Map<String, Float>): Double {
        val longest = played(voice, m).maxOf { pl -> pl.stroke.t60.max() * pl.ringScale } * decayScale(m)
        return (longest + 0.3).coerceIn(seconds(voice) + 0.5, MAX_RING_SECONDS)
    }

    // ---------------------------------------------------------------- macros

    /**
     * TUNE: +-5 semitones around the stroke as heard. DECAY: every ring and the residual's, 0.5x to
     * 2x. FORCE: the contact, 2x longer (softer) to half (harder); on the cowbell, which has no
     * contact pulse, the clapper's hardness (its upper notes and strike noise, down or up). ATTACK: the landing's own noise,
     * +-9 dB. Then one or two of the voice's own.
     */
    fun macros(voice: TerraVoice): List<MacroSpec> {
        val shared = listOf(MacroSpec("TUNE", 0.5f), MacroSpec("DECAY", 0.5f), MacroSpec("FORCE", 0.5f), MacroSpec("ATTACK", 0.5f))
        return shared + when (voice) {
            // DRUM: 0 the longer-ringing drum (notes at 66, 91 and 113 Hz), 1 the lower, tighter one
            // (61 and 85 Hz); between, both struck together.
            TerraVoice.ODAIKO -> listOf(MacroSpec("DRUM", 0f))
            // STROKE: 0 and 1 are two strokes on the same drum, from two references.
            TerraVoice.SHIME -> listOf(MacroSpec("STROKE", 0f))
            // EDGE: 0 the open centre note (708 Hz), 1 the bright edge stroke (from 3.4 kHz).
            TerraVoice.KANE -> listOf(MacroSpec("EDGE", 0f))
            // SPREAD: how long the slats take to fall, half the measured 185 ms to twice it.
            TerraVoice.BINZASARA -> listOf(MacroSpec("SPREAD", 0.5f))
            // SHAKE: 1 the measured train (strikes at 0, 139 and 345 ms, a rebound at 708), 0 one
            // strike. DAMP: a hand on the bell, its ring from full (0) to a quarter (1).
            TerraVoice.COWBELL -> listOf(MacroSpec("SHAKE", 1f), MacroSpec("DAMP", 0f))
            // SWEEP: 0 the slap (twelve flaps dying away over 106 ms), 1 the swish (sixteen swelling over 123 ms).
            TerraVoice.FAN -> listOf(MacroSpec("SWEEP", 0f))
            TerraVoice.NAGADO, TerraVoice.KA, TerraVoice.CHAPPA, TerraVoice.WATER_DRUM -> emptyList()
            else -> error("$voice is not a TERRA stick voice")
        }
    }

    private fun tuneRatio(m: Map<String, Float>) = 2.0.pow((m.getValue("TUNE") - 0.5) * 10.0 / 12.0)
    private fun decayScale(m: Map<String, Float>) = Dsp.around(m.getValue("DECAY"), 0.5f, 1f, 2f).toDouble()

    // ---------------------------------------------------------------- the strokes a voice plays

    /**
     * A stroke as [m] plays it: its [weight] in the voice, a factor on its later landings' times
     * ([timeScale]) and on its ring ([ringScale]), and the level of every landing but the loudest
     * ([othersLevel]).
     */
    private class Played(val stroke: Stroke, val weight: Double, val timeScale: Double = 1.0, val ringScale: Double = 1.0, val othersLevel: Double = 1.0)

    private fun blend(a: Stroke, b: Stroke, s: Float): List<Played> {
        val x = s.toDouble().coerceIn(0.0, 1.0)
        return listOfNotNull(if (x < 1.0) Played(a, 1.0 - x) else null, if (x > 0.0) Played(b, x) else null)
    }

    /**
     * How far a soft strike on the gong's edge reaches its centre: at [velocity] 1 the edge stroke as fitted, at the
     * softest a third of the way to the centre stroke. A longer, softer contact brings out a plate's low modes against its
     * high ones, and the edge stroke's own notes sit in one narrow band (3.4 to 7.6 kHz) that no tilt among themselves can
     * darken: measured, every tilt tried moved its onset brightness by -2 to +1 %. Analogy, not simulated: the centre
     * stroke's notes stand in for the plate's low modes. Shape: [EDGE_SOFT_REACH].
     */
    private fun kaneEdge(m: Map<String, Float>, velocity: Double): Float =
        (m.getValue("EDGE") * (1.0 - EDGE_SOFT_REACH * (1.0 - velocity))).toFloat()

    /** The share of the centre stroke a strike at velocity 0 brings to an edge stroke ([kaneEdge]). Shape. */
    private const val EDGE_SOFT_REACH = 0.45

    /**
     * The strokes [voice] sounds at [m] struck at [velocity]. [velocity] moves only KANE's edge ([kaneEdge]), and not
     * under HIT ([fixedStrokes]), whose level curves are built from the full-velocity strokes.
     */
    private fun played(voice: TerraVoice, m: Map<String, Float>, velocity: Double = 1.0, fixedStrokes: Boolean = false): List<Played> = when (voice) {
        TerraVoice.ODAIKO -> blend(TerraSticksTables.ODAIKO_A, TerraSticksTables.ODAIKO_B, m.getValue("DRUM"))
        TerraVoice.NAGADO -> listOf(Played(TerraSticksTables.NAGADO, 1.0))
        TerraVoice.SHIME -> blend(TerraSticksTables.SHIME, TerraSticksTables.SHIME_B, m.getValue("STROKE"))
        TerraVoice.KA -> listOf(Played(TerraSticksTables.KA, 1.0))
        TerraVoice.KANE -> blend(TerraSticksTables.KANE, TerraSticksTables.KANE_EDGE, if (fixedStrokes) m.getValue("EDGE") else kaneEdge(m, velocity))
        TerraVoice.CHAPPA -> listOf(Played(TerraSticksTables.CHAPPA, 1.0))
        TerraVoice.BINZASARA -> listOf(Played(TerraSticksTables.CLAPPER, 1.0, timeScale = 2.0.pow((m.getValue("SPREAD") - 0.5) * 2.0)))
        TerraVoice.COWBELL -> {
            val shake = m.getValue("SHAKE").toDouble().coerceIn(0.0, 1.0)
            // the loudest strike moves to the start as the train closes, so SHAKE 0 is one strike on the pad's first frame
            listOf(Played(TerraSticksTables.COWBELL, 1.0, timeScale = shake, ringScale = 1.0 - 0.75 * m.getValue("DAMP"), othersLevel = shake))
        }
        TerraVoice.WATER_DRUM -> listOf(Played(TerraSticksTables.WATER, 1.0))
        TerraVoice.FAN -> blend(TerraSticksTables.FAN, TerraSticksTables.FAN_SWEEP, m.getValue("SWEEP"))
        else -> error("$voice is not a TERRA stick voice")
    }

    /** The stroke's loudest landing: the one a one-strike reading keeps. */
    private fun mainHit(st: Stroke): Int {
        var best = 0; var bestSum = -1.0
        for ((k, h) in st.hits.withIndex()) {
            var sum = 0.0
            for (j in h.sin.indices) sum += abs(h.sin[j]) + abs(h.cos[j])
            if (sum > bestSum) { bestSum = sum; best = k }
        }
        return best
    }

    /** The voice's [Terra.Body] modes for HIT: every played stroke's notes at its loudest landing, as ratios to the heaviest stroke's lowest note. */
    fun bodyModes(voice: TerraVoice, m: Map<String, Float>): Pair<List<Modes.Mode>, Float> {
        val tune = tuneRatio(m); val decay = decayScale(m)
        val strokes = played(voice, m)
        val fundamental = strokes.maxByOrNull { it.weight }!!.stroke.hz[0] * tune
        val modes = strokes.flatMap { pl ->
            val h = pl.stroke.hits[mainHit(pl.stroke)]
            pl.stroke.hz.indices.map { j ->
                Modes.Mode((pl.stroke.hz[j] * tune / fundamental).toFloat(), (hypot(h.sin[j], h.cos[j]) * pl.weight).toFloat(), (pl.stroke.t60[j] * decay * pl.ringScale).toFloat())
            }
        }
        return modes to fundamental.toFloat()
    }

    // ---------------------------------------------------------------- render

    private const val NOISE_PREROLL = 2048
    private const val TOP_HZ = 20000.0

    /**
     * [voice] at the render [rate], before the output stage ([TerraBox.finish]). [take] 0 is the
     * stroke as picked; a later take moves the contact, each note's level and each later landing's
     * time, and [noiseSeed] (already the take's) re-draws the noise. [level] is HIT's curve per
     * [bodyModes] mode, indexed from the pad's first frame. [headOnly] leaves the noise out.
     */
    fun render(voice: TerraVoice, m: Map<String, Float>, rate: Int, velocity: Float, take: Int, noiseSeed: Int,
               level: Array<FloatArray>? = null, headOnly: Boolean = false, ringSeconds: Double? = null): FloatArray {
        val v = StrikeVelocity.settled(velocity).toDouble()
        val frames = if (ringSeconds == null) frames(voice, rate) else (ringSeconds * rate).toInt()
        val tune = tuneRatio(m); val decay = decayScale(m)
        val widthScale = 2.0.pow((0.5 - m.getValue("FORCE")) * 2.0)
        val attack = 10.0.pow((m.getValue("ATTACK") - 0.5) * 18.0 / 20.0)
        val strokes = played(voice, m, v, fixedStrokes = level != null)
        val nModes = strokes.sumOf { it.stroke.hz.size }
        if (level != null) require(level.size == nModes && level.all { it.isNotEmpty() }) { "a level curve per mode ($nModes), each at least one value long" }
        // a take's own landing: three uniform draws summed have unit deviation, so `spread` is the log's deviation
        val jitter = if (take == 0) null else Dsp.Noise(Dsp.seedFor("TERRA-STICKS", voice.name, take))
        fun moved(spread: Double): Double = if (jitter == null) 1.0 else exp(spread * (jitter.next() + jitter.next() + jitter.next()))
        val low = Dsp.RATE
        val up = rate / low
        val lowFrames = frames / up + 2
        val out = DoubleArray(frames)
        var modeAt = 0
        for ((si, pl) in strokes.withIndex()) {
            val st = pl.stroke
            val notes = st.hz.size
            val curves = level?.copyOfRange(modeAt, modeAt + notes)
            modeAt += notes
            val widthMs = if (st.widthMs > 0.0) st.widthMs * widthScale * moved(0.10) + (1.0 - v) * 0.6 else 0.0
            // A stroke with no contact pulse (the cowbell: metal on metal, its notes start at once) has nothing for
            // FORCE to lengthen, so there FORCE is the clapper's hardness directly: the upper notes and the strike's
            // noise up or down around the fitted stroke (0.5 is the stroke as picked). Without this FORCE was a dead
            // knob on the cowbell (TerraSticksTest caught it).
            val hardness = if (st.widthMs > 0.0) 0.0 else (m.getValue("FORCE") - 0.5) * 2.0
            val noteGain = DoubleArray(notes) { j ->
                val octaves = max(0.0, ln(st.hz[j] / st.hz[0]) / ln(2.0))
                v.pow(min(3.0, 1.0 + 0.45 * octaves)) * 2.0.pow(hardness * 0.6 * octaves) * moved(0.09)
            }
            val strikeNoise = 10.0.pow(hardness * 9.0 / 20.0)
            val main = mainHit(st)
            val parts = DoubleArray(frames)
            val noise = DoubleArray(lowFrames)
            for ((hk, h) in st.hits.withIndex()) {
                val hitLevel = if (hk == main) 1.0 else pl.othersLevel
                val time = h.time * pl.timeScale * (if (h.time > 0.0) moved(0.05) else 1.0)
                if (hitLevel <= 0.0) continue
                val start = (time * rate).toInt()
                if (start >= frames) continue
                ring(st, h, hitLevel, noteGain, tune, decay * pl.ringScale, rate, start, parts, curves)
                if (!headOnly) bursts(h, hitLevel * attack * strikeNoise, v, tune, decay, (time * low).toInt(), noise, noiseSeed * 31 + si * 977 + hk * 131, ::moved)
            }
            val body = if (widthMs > 0.0) contact(parts, widthMs, rate) else parts
            for (i in out.indices) {
                val pos = i / up; val frac = (i % up).toDouble() / up
                val wash = if (headOnly) 0.0 else noise[pos] + (noise[min(pos + 1, lowFrames - 1)] - noise[pos]) * frac
                out[i] += (body[i] + wash) * pl.weight
            }
        }
        val fade = min((0.3 * rate).toInt(), frames / 4)
        return FloatArray(frames) {
            val left = frames - it
            val taper = if (left >= fade) 1.0 else 0.5 - 0.5 * cos(PI * left / fade)
            (out[it] * taper).toFloat()
        }
    }

    /** One landing's notes into [into] from [start]: each a decaying sinusoid from its own sine and cosine level, blooming over the stroke's rise if it has one. */
    private fun ring(st: Stroke, h: TerraSticksTables.Hit, hitLevel: Double, noteGain: DoubleArray, tune: Double, decay: Double,
                     rate: Int, start: Int, into: DoubleArray, curves: Array<FloatArray>?) {
        val riseStep = if (st.riseMs > 0.0) exp(-1.0 / (st.riseMs * 1e-3 * rate)) else 0.0
        for (j in st.hz.indices) {
            val hz = st.hz[j] * tune
            if (hz >= TOP_HZ) continue
            val a = h.sin[j] * noteGain[j] * hitLevel; val b = h.cos[j] * noteGain[j] * hitLevel
            if (abs(a) + abs(b) < 1e-9) continue
            val omega = 2.0 * PI * hz / rate
            val rc = cos(omega); val rs = sin(omega)
            val step = exp(-6.9078 / (st.t60[j] * decay * rate))
            val curve = curves?.get(j)
            var env = 1.0; var c = 1.0; var s = 0.0; var unbloomed = if (riseStep > 0.0) 1.0 else 0.0
            var i = start
            while (i < into.size && env > 1e-7) {
                var x = env * (a * s + b * c)
                // past 1e-12 the bloom is over: stop there, or the factor decays into denormals and the loop crawls
                if (unbloomed > 1e-12) { x *= 1.0 - unbloomed; unbloomed *= riseStep }
                if (curve != null) x *= curve[min(i, curve.size - 1)]
                into[i] += x
                val nc = c * rc - s * rs; s = s * rc + c * rs; c = nc
                env *= step
                i++
            }
        }
    }

    /** [x] through the house half-sine contact, [widthMs] long and of unit area: a longer contact is a duller strike. */
    private fun contact(x: DoubleArray, widthMs: Double, rate: Int): DoubleArray {
        val w = max(3, (widthMs * 1e-3 * rate).toInt())
        val pulse = DoubleArray(w) { sin(PI * it / w) }
        val area = pulse.sum()
        for (k in pulse.indices) pulse[k] /= area
        var last = x.size - 1
        while (last > 0 && abs(x[last]) < 1e-12) last--
        val out = DoubleArray(x.size)
        for (i in 0..min(x.size - 1, last + w)) {
            var acc = 0.0
            val from = max(0, i - last)
            for (k in from until min(w, i + 1)) acc += pulse[k] * x[i - k]
            out[i] = acc
        }
        return out
    }

    /**
     * One landing's residual into [into] (at the output rate) from [start]: per band, noise through
     * the fit's own band-pass, levelled to unit RMS, at the band's level and decay. The filter is
     * run in before the landing, as in the fit, so the band is steady when the landing arrives.
     */
    private fun bursts(h: TerraSticksTables.Hit, gain: Double, v: Double, tune: Double, decay: Double, start: Int, into: DoubleArray,
                       seed: Int, moved: (Double) -> Double) {
        val low = Dsp.RATE
        for ((bi, b) in h.bands.withIndex()) {
            val lo = b[0] * tune; val hi = min(b[1] * tune, low / 2.0 * 0.95)
            val g = b[2] * gain * v.pow(bandExponent(b[0])) * moved(0.06)
            if (lo >= hi * 0.98 || start >= into.size) continue
            val t = b[3] * decay
            val n = min(into.size - start, (t * 140.0 / 60.0 * low).toInt() + 1)
            val length = max(n, low / 4)                       // long enough for a low band's RMS to settle
            val sections = filters.getOrPut(Triple(lo, hi, low)) { TerraFitted.bandpass(lo, hi, low) }
            val draw = Dsp.Noise(seed + bi * 7919)
            val x = DoubleArray(NOISE_PREROLL + length) { draw.next().toDouble() }
            for (sec in sections) {
                var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
                for (k in x.indices) {
                    val y = sec[0] * x[k] + sec[1] * x1 + sec[2] * x2 - sec[3] * y1 - sec[4] * y2
                    x2 = x1; x1 = x[k]; y2 = y1; y1 = y
                    x[k] = y
                }
            }
            var energy = 0.0
            for (k in NOISE_PREROLL until x.size) energy += x[k] * x[k]
            val rms = sqrt(energy / length)
            if (rms < 1e-12) continue
            val step = exp(-6.9078 / (t * low))
            var env = g / rms
            for (k in 0 until n) { into[start + k] += x[NOISE_PREROLL + k] * env; env *= step }
        }
    }

    private val filters = java.util.concurrent.ConcurrentHashMap<Triple<Double, Double, Int>, List<DoubleArray>>()

    /**
     * A band's velocity exponent, [TerraFitted]'s law: the thud under 250 Hz falls as v^0.5, and
     * above it the exponent climbs with the band's log frequency from v^0.75 to v^3 at 8 kHz.
     */
    private fun bandExponent(lo: Double): Double =
        if (lo < 250.0) 0.5 else (0.75 + 2.25 * ln(lo / 250.0) / ln(8000.0 / 250.0)).coerceIn(0.75, 3.0)
}
