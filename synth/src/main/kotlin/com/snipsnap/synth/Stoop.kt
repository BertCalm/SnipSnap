package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.*
import kotlin.random.Random

enum class StoopVoice { BASS, TINE, CLAV, ORGAN, LEAD, CHORD }

/** Original source settings, independent of the six performance macros. */
data class StoopSource(
    val shape: Float = .5f,
    val sub: Float = .25f,
    val fmRatio: Float = 2f,
    val fmIndex: Float = .7f,
    val harmonicTilt: Float = .5f,
    val detuneCents: Float = 0f,
) {
    init {
        require(shape.isFinite() && shape in 0f..1f) { "shape must be 0..1" }
        require(sub.isFinite() && sub in 0f..1f) { "sub must be 0..1" }
        require(fmRatio.isFinite() && fmRatio in .25f..8f) { "fmRatio must be .25..8" }
        require(fmIndex.isFinite() && fmIndex in 0f..8f) { "fmIndex must be 0..8" }
        require(harmonicTilt.isFinite() && harmonicTilt in 0f..1f) { "harmonicTilt must be 0..1" }
        require(detuneCents.isFinite() && detuneCents in -20f..20f) { "detuneCents must be -20..20" }
    }
}

/**
 * Preset-first groove voices. Sources, mild saturation and filters run at 4x;
 * polyBLEP oscillators, pitch-limited harmonic banks and bounded FM keep source
 * bandwidth controlled before the common steep low-pass and sinc decimation.
 * WEAR has no noise floor or unfiltered sample-hold stage.
 */
object Stoop {
    const val MODEL_VERSION = 1
    const val RATE = Dsp.RATE
    const val DEFAULT_MIDI = 48
    const val MIDI_MIN = 24
    const val MIDI_MAX = 96
    const val HOLD_LOOP = .99f
    const val MAX_SECONDS = 8f
    private const val INTERNAL_RATE = RATE * Dsp.OVERSAMPLE
    private const val TAU = 2.0 * PI
    private const val SOURCE_CEILING = 15_000.0
    private const val HISTORY = 256

    private val settings = arrayOf(
        floatArrayOf(.35f, .40f, .15f, .25f, .35f),
        floatArrayOf(.40f, .50f, .20f, .30f, .60f),
        floatArrayOf(.60f, .55f, .15f, .25f, .25f),
        floatArrayOf(.35f, .45f, .15f, .20f, .50f),
        floatArrayOf(.45f, .60f, .20f, .20f, .55f),
        floatArrayOf(.40f, .40f, .20f, .30f, .40f),
    )
    fun macrosFor(voice: StoopVoice): List<MacroSpec> {
        val d = settings[voice.ordinal]
        return listOf(MacroSpec("TOUCH", d[0], .40f), MacroSpec("COLOR", d[1], .50f),
            MacroSpec("SHADOW", d[2], 0f), MacroSpec("WEAR", d[3], 0f),
            MacroSpec("LENGTH", d[4], .45f), MacroSpec("HOLD", 0f, 0f))
    }
    fun defaults(voice: StoopVoice) = macrosFor(voice).associate { it.name to it.default }
    fun defaultSource(voice: StoopVoice): StoopSource = when (voice) {
        StoopVoice.BASS -> StoopSource(.40f, .28f, 1f, .35f, .35f)
        StoopVoice.TINE -> StoopSource(.45f, .08f, 2f, 1.1f, .45f)
        StoopVoice.CLAV -> StoopSource(.65f, .10f, 1f, .4f, .65f)
        StoopVoice.ORGAN -> StoopSource(.35f, .16f, 2f, .35f, .45f)
        StoopVoice.LEAD -> StoopSource(.60f, .06f, 1f, .5f, .60f, 3f)
        StoopVoice.CHORD -> StoopSource(.40f, .08f, 1f, .3f, .40f, 2f)
    }
    internal fun settled(voice: StoopVoice, macros: Map<String, Float>): Map<String, Float> =
        defaults(voice).toMutableMap().also { out ->
            for ((k, v) in macros) if (k in out && v.isFinite()) out[k] = v.coerceIn(0f, 1f)
        }
    fun isLoop(hold: Float) = hold >= HOLD_LOOP
    fun drumClassFor(voice: StoopVoice, macros: Map<String, Float> = emptyMap()): DrumClass =
        if (isLoop(settled(voice, macros).getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL
    fun scramble(voice: StoopVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> =
        Dsp.scrambleNear(settled(voice, if (near is StoopPatch && near.voice == voice) near.macros else emptyMap()), temperature, random)
            .toMutableMap().also { it["HOLD"] = it.getValue("HOLD").coerceAtMost(.95f) }

    data class ShadowEvent(val frame: Int, val midi: Int, val articulation: Float, val durationFrames: Int)
    /** Components are pre-WEAR but output-rate/DC-conditioned, for clean causal comparisons. */
    data class Probe(
        val samples: FloatArray,
        val main: FloatArray,
        val shadow: FloatArray,
        val events: List<ShadowEvent>,
        /** Actual 256 frames immediately before the kept region, from one continuous render. */
        val previousCycle: FloatArray = FloatArray(0),
        val seamError: Double = 0.0,
        val sourceFrequencies: List<Double> = emptyList(),
        val maxPitchAdjustmentCents: Double = 0.0,
        val prerollFrames: Int = 0,
        /** Continuous conditioned component histories, before the shared WEAR stage. */
        val previousMain: FloatArray = FloatArray(0),
        val previousShadow: FloatArray = FloatArray(0),
    ) {
        val actualShadowEvents get() = events
    }

    private data class Tone(val hz: Double, val mod: Double, val twin: Double, val sub: Double,
        val phase: Double, val root: Boolean)
    private data class Gesture(val start: Double, val midi: Int, val touch: Double, val color: Double,
        val duration: Double, val attack: Double, val release: Double, val gain: Double,
        val tones: List<Tone>, val shadow: Boolean)
    private data class LoopPlan(val frames: Int, val tuned: Map<Double, Double>, val adjustment: Double)
    private data class Rendered(val samples: FloatArray, val main: FloatArray, val shadow: FloatArray,
        val events: List<ShadowEvent>, val previous: FloatArray, val seam: Double,
        val frequencies: List<Double>, val adjustment: Double, val warm: Int,
        val previousMain: FloatArray, val previousShadow: FloatArray)

    private fun duration(voice: StoopVoice, length: Float, hold: Float): Double {
        val short = when (voice) {
            StoopVoice.BASS -> .17f
            StoopVoice.TINE -> .28f
            StoopVoice.CLAV -> .19f
            StoopVoice.ORGAN -> .22f
            StoopVoice.LEAD, StoopVoice.CHORD -> .30f
        }
        val long = when (voice) {
            StoopVoice.TINE -> 3.4f
            StoopVoice.CLAV -> 2.1f
            StoopVoice.BASS -> 3.6f
            else -> 3.0f
        }
        val body = if (voice == StoopVoice.CLAV) .85f * length else 0f
        return ((Dsp.expMap(length, short, long) + body) * (1f + .35f * hold.coerceAtMost(.98f))).toDouble()
    }
    private fun attack(voice: StoopVoice, touch: Double): Double {
        val soft = if (voice == StoopVoice.LEAD || voice == StoopVoice.CHORD) .065 else .019
        return soft * (1.0 - touch).pow(1.6) + .0018
    }
    private fun tones(p: StoopPatch, shadow: Boolean): List<Tone> {
        val intervals = if (p.voice == StoopVoice.CHORD && !shadow) p.chordIntervals else listOf(0)
        val baseMidi = p.midi + if (shadow) p.shadowInterval else 0
        val seed = Dsp.seedFor("STOOP", MODEL_VERSION, p.voice, p.midi, p.source, if (shadow) 1 else 0)
        val phases = Dsp.phases(intervals.size, seed)
        return intervals.mapIndexed { n, interval ->
            val hz = 440.0 * 2.0.pow((baseMidi + interval - 69) / 12.0)
            Tone(hz, hz * p.source.fmRatio, hz * 2.0.pow(p.source.detuneCents / 1200.0),
                hz * .5, .035 + .09 * phases[n], interval == 0)
        }
    }

    /**
     * Fit whole cycles with a long, searched loop, rather than a fixed coarse
     * frequency grid. Root remains within 0.02 cents; the diagnostic reports
     * the worst small adjustment of the independently tuned companion/FM voices.
     * A deterministic periodic pitch-wear clock completes exactly one loop.
     */
    private fun loopPlan(tones: List<Tone>, voice: StoopVoice, source: StoopSource): LoopPlan {
        val root = tones.first { it.root }.hz * if (source.sub > 0f) .5 else 1.0
        val frequencies = buildList {
            for (t in tones) {
                add(t.hz)
                if (source.sub > 0f) add(t.sub)
                if (source.detuneCents != 0f && voice != StoopVoice.CLAV) add(t.twin)
                if (voice == StoopVoice.TINE && source.fmIndex > 0f) add(t.mod)
            }
        }.distinct()
        var bestFrames = RATE
        var bestError = Double.POSITIVE_INFINITY
        var bestMap = emptyMap<Double, Double>()
        val from = ceil(1.25 * root).toInt().coerceAtLeast(4)
        val to = floor(6.5 * root).toInt().coerceAtLeast(from)
        for (periods in from..to) {
            val frames = (periods * RATE / root).roundToInt()
            val seconds = frames.toDouble() / RATE
            val fit = frequencies.associateWith { f -> (f * seconds).roundToInt().coerceAtLeast(1) / seconds }
            val maxCents = frequencies.maxOf { abs(1200.0 * log2(fit.getValue(it) / it)) }
            // The very small time preference only breaks equivalent fits.
            val score = maxCents + seconds * .002
            if (score < bestError) { bestError = score; bestFrames = frames; bestMap = fit }
        }
        val cents = frequencies.maxOf { abs(1200.0 * log2(bestMap.getValue(it) / it)) }
        return LoopPlan(bestFrames, bestMap, cents)
    }

    private fun fitted(t: Tone, plan: LoopPlan?): Tone {
        if (plan == null) return t
        return t.copy(hz = plan.tuned[t.hz] ?: t.hz, mod = plan.tuned[t.mod] ?: t.mod,
            twin = plan.tuned[t.twin] ?: plan.tuned[t.hz] ?: t.hz, sub = plan.tuned[t.sub] ?: t.sub)
    }
    private fun gestures(p: StoopPatch, m: Map<String, Float>, plan: LoopPlan?): List<Gesture> {
        val touch = m.getValue("TOUCH").toDouble()
        val color = m.getValue("COLOR").toDouble()
        val length = m.getValue("LENGTH")
        val d = duration(p.voice, length, m.getValue("HOLD"))
        val vel = p.velocity.toDouble()
        val main = Gesture(0.0, p.midi, touch, color, d, attack(p.voice, touch),
            .035 + .13 * length, .62 * vel.pow(.85), tones(p, false).map { fitted(it, plan) }, false)
        val shadow = m.getValue("SHADOW").toDouble()
        if (shadow == 0.0) return listOf(main)
        val answerTouch = (p.shadowArticulation + .18 * shadow).coerceIn(0.0, 1.0)
        val count = if (plan != null) 1 else p.shadowReplies
        return listOf(main) + (0 until count).map { n ->
            Gesture((p.shadowDelayMs / 1000.0) * (n + 1), p.midi + p.shadowInterval,
                answerTouch, (color * .57 + .07).coerceAtMost(.65),
                (d * (.26 + .14 * length)).coerceIn(.085, 1.8), attack(p.voice, answerTouch),
                .045 + .08 * length, .62 * vel.pow(.85) * .32 * shadow.pow(1.15) * .68.pow(n),
                tones(p, true).map { fitted(it, plan) }, true)
        }
    }

    private fun envelope(g: Gesture, age: Double, voice: StoopVoice, held: Boolean, length: Double): Double {
        if (age < 0.0) return 0.0
        val a = (age / g.attack).coerceIn(0.0, 1.0)
        val onset = a * a * (3.0 - 2.0 * a)
        if (held) {
            val settled = when (voice) {
                StoopVoice.TINE, StoopVoice.CLAV -> .34 + .36 * length
                else -> .68 + .17 * length
            }
            val settle = (.035 + .23 * length) * if (g.shadow) .65 else 1.0
            val u = (age / (settle * 8.0)).coerceIn(0.0, 1.0)
            val transient = (1.0 - u).pow(4)
            return onset * (settled + (1.0 - settled) * transient)
        }
        if (age >= g.duration + g.release) return 0.0
        val decay = when (voice) {
            StoopVoice.TINE -> exp(-3.1 * age / g.duration)
            StoopVoice.CLAV -> exp(-1.45 * age / g.duration)
            StoopVoice.BASS -> .70 + .30 * exp(-age / (.045 + .04 * length))
            else -> .79 + .21 * exp(-age / (.05 + .07 * length))
        }
        val release = if (age > g.duration) (1.0 - (age - g.duration) / g.release).coerceAtLeast(0.0).pow(2) else 1.0
        return onset * decay * release
    }

    /** PolyBLEP fixes both edges at the source, before the 4x output chain. */
    private fun blep(phase: Double, dt: Double): Double = when {
        phase < dt -> { val t = phase / dt; t + t - t * t - 1.0 }
        phase > 1.0 - dt -> { val t = (phase - 1.0) / dt; t * t + t + t + 1.0 }
        else -> 0.0
    }
    private fun saw(p: Double, dt: Double) = 2.0 * p - 1.0 - blep(p, dt)
    private fun pulse(p: Double, dt: Double, duty: Double): Double {
        val second = (p - duty + 1.0) % 1.0
        return (if (p < duty) 1.0 else -1.0) - (2.0 * duty - 1.0) + blep(p, dt) - blep(second, dt)
    }
    private fun oscillator(voice: StoopVoice, t: Tone, age: Double, warpedAge: Double,
        touch: Double, color: Double, source: StoopSource, length: Double, held: Boolean, velocity: Double): Double {
        if (t.hz >= SOURCE_CEILING) return 0.0
        val p = (warpedAge * t.hz + t.phase) % 1.0
        val phase = TAU * p
        val fundamental = sin(phase)
        val dt = (t.hz / INTERNAL_RATE).coerceAtMost(.25)
        val shape = source.shape.toDouble()
        val tilt = source.harmonicTilt.toDouble()
        val sub = if (source.sub > 0f && t.sub < SOURCE_CEILING) sin(TAU * ((warpedAge * t.sub + t.phase * .5) % 1.0)) * source.sub * .36 else 0.0
        val settle = .018 + .075 * length
        val transient = if (held) (1.0 - age / (.4 + length)).coerceIn(0.0, 1.0).pow(3) else exp(-age / settle)
        // A held sample excludes its attack. Keep contact in the settled
        // source spectrum so TOUCH and the companion's own articulation
        // remain useful after settling, without repeating an attack.
        val heldContact = if (held) 1.0 else 0.0
        val bright = (.16 + .72 * color) * (.58 + .42 * velocity)
        val twin = if (source.detuneCents != 0f && voice != StoopVoice.CLAV && t.twin < SOURCE_CEILING)
            .14 * sin(TAU * ((warpedAge * t.twin + t.phase + .19) % 1.0)) else 0.0
        val body = when (voice) {
            StoopVoice.BASS -> {
                val harmonic = (1.0 - shape) * saw(p, dt) + shape * pulse(p, dt, .23 + .32 * shape)
                .64 * fundamental + (.26 + .40 * bright + .14 * tilt + .16 * touch * (transient + .75 * heldContact)) * harmonic
            }
            StoopVoice.TINE -> {
                // Carson-style sideband bound; high-note index retreats before the decimator.
                val bandwidthIndex = ((SOURCE_CEILING - t.hz) / t.mod - 1.0).coerceAtLeast(0.0)
                val index = (source.fmIndex * (.24 + 1.35 * bright + .75 * touch * (transient + .55 * heldContact)) *
                    (if (held) .55 + .45 * length else 1.0)).coerceAtMost(min(3.0, bandwidthIndex))
                val mod = sin(TAU * ((warpedAge * t.mod + .07) % 1.0))
                .64 * fundamental + .38 * sin(phase + index * mod) +
                    (if (t.hz * 2.0 < SOURCE_CEILING) .10 * shape * bright * sin(phase * 2.0) * transient else 0.0)
            }
            StoopVoice.CLAV, StoopVoice.ORGAN -> {
                val count = min(12, floor(SOURCE_CEILING / t.hz).toInt()).coerceAtLeast(1)
                val c = cos(phase)
                val s = sin(phase)
                var hs = s
                var hc = c
                var sum = fundamental
                var weight = 1.0
                for (h in 2..count) {
                    val nextS = hs * c + hc * s
                    hc = hc * c - hs * s
                    hs = nextS
                    val amplitude = if (voice == StoopVoice.CLAV) {
                        val pick = abs(sin(PI * h * (.12 + .28 * shape)))
                        val damping = if (held) exp(-h * (.10 + .23 * (1.0 - length)))
                            else .15 * exp(-age / (.18 + .70 * length)) + .85 * exp(-h * age / (.025 + .26 * length))
                        (.22 + .66 * bright + .20 * touch) * pick * damping / h.toDouble().pow(.65 + .8 * (1.0 - tilt))
                    } else {
                        val registration = if (h % 2 == 0) .22 + .60 * shape else .60 - .25 * shape
                        val contactRegistration = 1.0 + heldContact * .34 * touch * (h - 1) / count
                        registration * contactRegistration * (.16 + .84 * bright) / h.toDouble().pow(.6 + 1.2 * (1.0 - tilt))
                    }
                    sum += amplitude * hs
                    weight += amplitude
                }
                if (voice == StoopVoice.ORGAN && t.hz * 3 < SOURCE_CEILING)
                    sum += .19 * touch * sin(phase * 3.0) * (transient + .45 * heldContact)
                sum / sqrt(weight)
            }
            StoopVoice.LEAD, StoopVoice.CHORD -> {
                val harmonic = (1.0 - shape) * saw(p, dt) + shape * pulse(p, dt, .20 + .35 * shape)
                .53 * fundamental + (.34 + .35 * bright + .13 * touch * (transient + .90 * heldContact)) * harmonic
            }
        }
        return body + sub + twin
    }

    /**
     * SynthScreen previews inside `runInterruptible`, which interrupts this thread when a slider or
     * engine change makes the render stale. Without a check the multi-million-frame passes ran to the end
     * beside their replacement.
     */
    private fun stopIfUnwanted() {
        if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("STOOP render no longer wanted")
    }

    private fun condition(raw: FloatArray): FloatArray {
        stopIfUnwanted()
        Tide.bandLimit(raw, INTERNAL_RATE)
        stopIfUnwanted()
        val out = Dsp.decimate(raw, RATE)
        // Causal DC removal keeps continuous held-state history measurable.
        val dc = Dsp.OnePole(RATE)
        for (i in out.indices) out[i] -= dc.lp(out[i], 12f)
        return out
    }
    internal fun recordingCharacter(raw: FloatArray, wear: Double, voice: StoopVoice, color: Double, rate: Int = INTERNAL_RATE) {
        if (wear == 0.0) return
        val cutoff = (12_500.0 * (1.0 - wear) + (2_600.0 + 1_400.0 * color) * wear).toFloat()
        val lp1 = Dsp.OnePole(rate)
        val lp2 = Dsp.OnePole(rate)
        // A bounded odd saturator adds harmonics at 4x; bass uses a gentler profile.
        val drive = 1.0 + wear * wear * if (voice == StoopVoice.BASS || voice == StoopVoice.CHORD) 2.2 else 3.4
        val makeup = tanh(drive)
        val coarse = ((wear - .45) / .55).coerceIn(0.0, 1.0)
        val steps = 16384.0 * (128.0 / 16384.0).pow(coarse)
        // Only the upper range quantizes. The error is bounded to .45/(2*128)
        // at full WEAR, runs on the oversampled clock and is low-passed before
        // output decimation. StoopEngineTest compares 4x to a 16x reference.
        for (i in raw.indices) {
            if (i % 8192 == 0) stopIfUnwanted()
            val rounded = lp2.lp(lp1.lp(raw[i], cutoff), cutoff)
            val saturated = tanh(rounded * drive) / makeup
            val colored = (1.0 - .70 * wear) * raw[i] + .70 * wear * saturated
            val quantized = round(colored * steps) / steps
            raw[i] = (colored + .45 * coarse * (quantized - colored)).toFloat()
        }
    }

    private fun generate(p: StoopPatch, components: Boolean): Rendered {
        val m = settled(p.voice, p.macros)
        val held = isLoop(m.getValue("HOLD"))
        val allTones = tones(p, false) + if (m.getValue("SHADOW") > 0f) tones(p, true) else emptyList()
        val plan = if (held) loopPlan(allTones, p.voice, p.source) else null
        val gs = gestures(p, m, plan)
        val length = m.getValue("LENGTH").toDouble()
        val wear = m.getValue("WEAR").toDouble()
        // Eight settle times, plus DC/filter warm-up, with a bounded maximum.
        val warm = if (held) ((2.2 + 1.1 * length) * RATE).roundToInt() else 0
        val kept = plan?.frames ?: (ceil(gs.maxOf { it.start + it.duration + it.release } * RATE).toInt() + 128)
            .coerceAtMost((MAX_SECONDS * RATE).toInt())
        val total = warm + kept + 128 // decimator edge lies outside the kept sample
        val main = FloatArray(total * Dsp.OVERSAMPLE)
        val shadow = if (gs.size > 1) FloatArray(main.size) else FloatArray(0)
        val wearHz = if (held) RATE.toDouble() / kept else .73
        val drift = (2.0.pow(2.3 * wear / 1200.0) - 1.0) / (TAU * wearHz)
        val seedPhase = Random(Dsp.seedFor("STOOP_WEAR", p.voice, p.midi, p.source)).nextDouble() * TAU
        fun warp(seconds: Double): Double = if (wear == 0.0) seconds else seconds + drift * sin(TAU * wearHz * seconds + seedPhase)
        for (g in gs) {
            if (g.gain == 0.0) continue
            val dest = if (g.shadow) shadow else main
            val filter = Dsp.TptSvf(INTERNAL_RATE)
            val startFrame = (g.start * INTERNAL_RATE).roundToInt()
            val end = if (held) dest.size else min(dest.size, ceil((g.start + g.duration + g.release) * INTERNAL_RATE).toInt())
            val baseHz = g.tones.first().hz
            val cutoffBase = ((2.2 + 25.0 * g.color.pow(1.5)) * baseHz.pow(.73) * 3.1)
                .coerceIn(min(baseHz * 1.5, 11_000.0), 11_000.0)
            var cutoff = cutoffBase
            var transient = 0.0
            for (i in startFrame until end) {
                if ((i - startFrame) % 8192 == 0) stopIfUnwanted()
                val seconds = i.toDouble() / INTERNAL_RATE
                val age = seconds - g.start
                if (i % 32 == 0 || i == startFrame) {
                    transient = if (held) (1.0 - age / (.35 + length)).coerceIn(0.0, 1.0).pow(3)
                        else exp(-age / (.018 + .12 * length))
                    val heldDamping = if (held) .68 + .50 * length else 1.0
                    val heldArticulation = if (held) .82 + .40 * g.touch else 1.0
                    cutoff = (cutoffBase * heldDamping * heldArticulation * (1.0 + g.touch * transient * (1.4 + 1.2 * g.color)))
                        .coerceIn(min(baseHz * 1.3, 13_500.0), 13_500.0)
                }
                val warped = warp(seconds) - warp(g.start)
                var value = 0.0
                for (tone in g.tones) value += (if (g.tones.size > 1 && !tone.root) .76 else 1.0) *
                    oscillator(p.voice, tone, age, warped, g.touch, g.color,
                        p.source, length, held, p.velocity.toDouble())
                // Root keeps more weight in a chord, and each note retains its own source state.
                value /= sqrt(g.tones.size.toDouble())
                filter.process(value.toFloat(), cutoff.toFloat(), (1.55 - .34 * g.color).toFloat())
                val env = envelope(g, age, p.voice, held, length)
                dest[i] += (filter.low * env * g.gain).toFloat()
            }
        }
        val combined = FloatArray(main.size) { main[it] + if (shadow.isEmpty()) 0f else shadow[it] }
        recordingCharacter(combined, wear, p.voice, m.getValue("COLOR").toDouble())
        val out = condition(combined)
        val mainOut = if (components) condition(main) else FloatArray(0)
        val shadowOut = if (components && shadow.isNotEmpty()) condition(shadow) else FloatArray(0)
        val previous = if (held) out.copyOfRange(warm - HISTORY, warm) else FloatArray(0)
        val previousMain = if (held && components) mainOut.copyOfRange(warm - HISTORY, warm) else FloatArray(0)
        val previousShadow = if (held && components) {
            if (shadowOut.isNotEmpty()) shadowOut.copyOfRange(warm - HISTORY, warm) else FloatArray(HISTORY)
        } else FloatArray(0)
        val samples = out.copyOfRange(warm, warm + kept)
        // Keys' normalized energy metric is undefined for exactly silent audio.
        // Silence has no discontinuity; every sounding loop still uses the
        // actual continuous history and the unchanged project seam metric.
        val silent = held && previous.all { it == 0f } && samples.all { it == 0f }
        stopIfUnwanted()
        val seam = if (held && !silent) Keys.seamError(previous + samples, HISTORY) else 0.0
        check(!held || seam < Keys.MAX_SEAM_ERROR) { "STOOP ${p.voice} MIDI ${p.midi} loop seam $seam exceeds ${Keys.MAX_SEAM_ERROR}" }
        if (!held) Dsp.fadeTail(samples)
        val events = gs.filter { it.shadow }.map { g -> ShadowEvent((g.start * RATE).roundToInt(), g.midi,
            g.touch.toFloat(), ((g.duration + g.release) * RATE).roundToInt()) }
        return Rendered(samples, if (components) mainOut.copyOfRange(warm, warm + kept) else mainOut,
            if (shadowOut.isNotEmpty()) shadowOut.copyOfRange(warm, warm + kept) else FloatArray(kept),
            events, previous, seam, allTones.flatMap { t -> listOf(fitted(t, plan).hz, fitted(t, plan).mod, fitted(t, plan).twin) },
            plan?.adjustment ?: 0.0, warm, previousMain, previousShadow)
    }

    fun render(voice: StoopVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI, velocity: Float = 1f): Snip =
        render(StoopPatch(voice.name, voice, macros, midi = midi, velocity = velocity, source = defaultSource(voice)))
    fun render(patch: StoopPatch): Snip {
        val out = generate(patch, false).samples
        stopIfUnwanted()
        Dsp.levelTo(out, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        return Snip(out, channels = 1, sampleRate = RATE)
    }
    /** Includes DC/anti-aliasing/recording character, before shared loudness leveling. */
    fun renderRaw(patch: StoopPatch): Snip = Snip(generate(patch, false).samples, channels = 1, sampleRate = RATE)
    fun renderProbe(patch: StoopPatch, normalize: Boolean = false): Probe {
        val r = generate(patch, true)
        if (normalize) {
            val before = r.samples.maxOfOrNull { abs(it) } ?: 0f
            stopIfUnwanted()
            Dsp.levelTo(r.samples, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
            val after = r.samples.maxOfOrNull { abs(it) } ?: 0f
            val gain = if (before > 0f) after / before else 1f
            for (i in r.previous.indices) r.previous[i] *= gain
            for (i in r.previousMain.indices) r.previousMain[i] *= gain
            for (i in r.previousShadow.indices) r.previousShadow[i] *= gain
            for (i in r.main.indices) { r.main[i] *= gain; r.shadow[i] *= gain }
        }
        return Probe(r.samples, r.main, r.shadow, r.events, r.previous, r.seam, r.frequencies, r.adjustment, r.warm, r.previousMain, r.previousShadow)
    }
}
