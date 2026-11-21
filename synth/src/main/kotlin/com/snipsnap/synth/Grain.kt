package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * GRAIN (saved key `grain`): a granular scatter. The characteristic gesture is: the hit plays, then pieces of it come
 * back as a cloud of short grains, scattered over the moment after it, thinning out as they go.
 *
 * The owner's Japanese references include granular versions of the rim clicks and the hand gong, and nothing in the
 * rack made that: ECHO repeats the whole hit on a fixed grid, REWIND plays windows backwards on a fixed grid, SMEAR and
 * GHOST take the attack away. TERRA DUSK first stood those in for granular (docs/TERRA_STICKS.md); this is the effect.
 *
 * How it works: [grainCount] grains (DENSITY), each a Hann-windowed piece of SIZE's length (10 to 120 ms, log) cut
 * from somewhere in the hit's first [SOURCE_SEC] after its onset, and placed somewhere in the SCATTER window after the
 * onset (0.1 to 1.0 s, log). Where a grain lands and where it is cut from are drawn from one fixed seed per setting
 * ([Dsp.seedFor] of the macros), so the same pad and settings always give the same cloud, and a pad re-rendered gives
 * the same samples (DeterminismTest's rule). Later grains are quieter: each grain's level falls with how late it
 * lands, to a tenth at the end of the window, so the cloud thins out rather than stops. One grain in four is
 * played backwards, the granular smear of the references. MIX is the cloud's level against the untouched dry, and the
 * sum is matched to the pad's peak, as ECHO's and REWIND's are.
 *
 * Linked across channels: one onset and one cloud for every channel. Mono stays mono.
 *
 * Sits after REWIND and before SPRING: a time effect, beside the delays, in front of the spring and the room so the
 * grains have the same space on them as the hit.
 *
 * Not yet a pedal chip: a pedal needs its own listening gate (docs/SYNTH_ROADMAP.md, the pedals' rule). Until then it
 * is reached through a saved chain, as TERRA DUSK's pads use it.
 */
object Grain {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("MIX", 0.5f),       // the cloud's level against the dry: 0 off .. 1 the cloud at the pad's own level
        MacroSpec("SIZE", 0.4f),      // each grain's length, GRAIN_MIN_SEC .. GRAIN_MAX_SEC (log)
        MacroSpec("DENSITY", 0.5f),   // how many grains: MIN_GRAINS .. MAX_GRAINS
        MacroSpec("SCATTER", 0.5f),   // how far after the onset grains land, SCATTER_MIN_SEC .. SCATTER_MAX_SEC (log)
    )

    const val GRAIN_MIN_SEC = 0.010
    const val GRAIN_MAX_SEC = 0.120
    const val SCATTER_MIN_SEC = 0.10
    const val SCATTER_MAX_SEC = 1.0
    const val MIN_GRAINS = 6
    const val MAX_GRAINS = 64

    /** Grains are cut from this long after the onset: the hit, not its tail. */
    const val SOURCE_SEC = 0.15

    /** The onset: the first frame within this many dB of the pad's peak (REWIND's rule). */
    private const val ONSET_DB = 30.0

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> = MACROS.associate { it.name to random.nextFloat() }

    fun grainSeconds(size: Float): Double = GRAIN_MIN_SEC * (GRAIN_MAX_SEC / GRAIN_MIN_SEC).pow(size.toDouble())
    fun scatterSeconds(scatter: Float): Double = SCATTER_MIN_SEC * (SCATTER_MAX_SEC / SCATTER_MIN_SEC).pow(scatter.toDouble())
    fun grainCount(density: Float): Int = (MIN_GRAINS + (MAX_GRAINS - MIN_GRAINS) * density).toInt()

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val mix = m.getValue("MIX")
        if (mix <= 0.001f || snip.frameCount == 0) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)
        val ch = snip.channels
        val rate = snip.sampleRate
        val frames = snip.frameCount
        val grain = max(8, (grainSeconds(m.getValue("SIZE")) * rate).toInt())
        val scatter = (scatterSeconds(m.getValue("SCATTER")) * rate).toInt()
        val count = grainCount(m.getValue("DENSITY"))

        // the onset, linked across channels
        var peak = 0f
        for (v in snip.samples) peak = max(peak, if (v < 0) -v else v)
        if (peak <= 0f) return Snip(snip.samples.copyOf(), ch, rate)
        val threshold = peak * 10.0.pow(-ONSET_DB / 20.0).toFloat()
        var onset = 0
        run { for (i in 0 until frames) for (c in 0 until ch) if (kotlin.math.abs(snip.samples[i * ch + c]) >= threshold) { onset = i; return@run } }
        val sourceEnd = min(frames - grain, onset + (SOURCE_SEC * rate).toInt()).coerceAtLeast(onset)

        val outFrames = max(frames, onset + scatter + grain)
        val wet = FloatArray(outFrames * ch)
        val window = FloatArray(grain) { (0.5 - 0.5 * cos(2.0 * PI * it / (grain - 1))).toFloat() }
        val draw = Dsp.Noise(Dsp.seedFor("GRAIN", m.getValue("SIZE"), m.getValue("DENSITY"), m.getValue("SCATTER")))
        fun unit(): Float = (draw.next() + 1f) / 2f
        for (g in 0 until count) {
            val landing = (unit() * scatter).toInt()
            val from = onset + (unit() * (sourceEnd - onset).coerceAtLeast(0)).toInt()
            val backwards = unit() < 0.25f
            val level = 1f - 0.9f * landing.toFloat() / scatter.coerceAtLeast(1)
            val at = onset + landing
            for (k in 0 until grain) {
                val src = from + (if (backwards) grain - 1 - k else k)
                if (src >= frames || at + k >= outFrames) break
                val w = window[k] * level
                for (c in 0 until ch) wet[(at + k) * ch + c] += snip.samples[src * ch + c] * w
            }
        }
        // the cloud at the pad's own level when MIX is 1: its peak matched to the dry's, as a grain count grows
        var wetPeak = 0f
        for (v in wet) wetPeak = max(wetPeak, if (v < 0) -v else v)
        val wetGain = if (wetPeak > 0f) mix * peak / wetPeak else 0f
        val out = FloatArray(outFrames * ch)
        for (i in out.indices) out[i] = wet[i] * wetGain + (if (i < snip.samples.size) snip.samples[i] else 0f)
        var outPeak = 0f
        for (v in out) outPeak = max(outPeak, if (v < 0) -v else v)
        if (outPeak > 0f) { val g = peak / outPeak; for (i in out.indices) out[i] *= g }
        return Snip(out, ch, rate)
    }

    /** The cloud's energy against the whole output's, for tests: 0 when MIX is 0. */
    internal fun wetShare(dry: Snip, out: Snip): Double {
        var e = 0.0; var d = 0.0
        for (i in out.samples.indices) { val x = out.samples[i].toDouble(); e += x * x; if (i >= dry.samples.size) d += x * x }
        return if (e > 0) sqrt(d / e) else 0.0
    }
}
