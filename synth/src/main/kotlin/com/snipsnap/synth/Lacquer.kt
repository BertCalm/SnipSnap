package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.*
import kotlin.random.Random

enum class LacquerVoice { KEY, GLASS, BRASS, BASS, PLUCK, SILK }
enum class LacquerRole { BODY, EDGE, TRAIL, REINFORCEMENT }
enum class LacquerSource { ROUNDED, HOLLOW, GLASS, BRASS, PLUCK, SILK }

/** Original generated cycles, never imported PCM. All times are seconds and all ratios
 * are relative to the requested note; sub-octaves and integer harmonics are the only
 * accepted pitch relationships. The complete model lives in the patch JSON. */
data class LacquerEnvelope(val attackSeconds: Float, val decaySeconds: Float,
    val sustain: Float, val releaseSeconds: Float) {
    init {
        require(attackSeconds.isFinite() && attackSeconds in .001f..1f)
        require(decaySeconds.isFinite() && decaySeconds in .01f..4f)
        require(sustain.isFinite() && sustain in 0f..1f)
        require(releaseSeconds.isFinite() && releaseSeconds in .01f..3f)
    }
}

data class LacquerTiming(val onsetSeconds: Float = 0f, val reliefSeconds: Float = 0f,
    val attackRelief: Float = 0f, val decayRelief: Float = 0f, val holdPhase: Float = 0f) {
    init {
        require(onsetSeconds.isFinite() && onsetSeconds in 0f..0.15f)
        require(reliefSeconds.isFinite() && reliefSeconds in 0f..0.15f)
        require(onsetSeconds + reliefSeconds <= .150001f)
        require(attackRelief.isFinite() && attackRelief in 0f..2f)
        require(decayRelief.isFinite() && decayRelief in 0f..1f)
        require(holdPhase.isFinite() && holdPhase in 0f..1f)
    }
}

data class LacquerLayer(val role: LacquerRole, val source: LacquerSource,
    val ratio: Float = 1f, val gain: Float = .3f, val cutoffHz: Float = 6000f,
    val filterEnvelope: Float = .3f, val envelope: LacquerEnvelope,
    val timing: LacquerTiming = LacquerTiming()) {
    init {
        require(ratio.isFinite() && (ratio == .25f || ratio == .5f || ratio in 1f..16f && ratio == floor(ratio)))
        require(gain.isFinite() && gain in 0f..0.7f)
        require(cutoffHz.isFinite() && cutoffHz in 100f..18000f)
        require(filterEnvelope.isFinite() && filterEnvelope in 0f..1f)
    }
}

data class LacquerProcessing(val drive: Float = 1.2f, val compression: Float = .15f,
    val colorHz: Float = 12000f) {
    init {
        require(drive.isFinite() && drive in 0f..3f)
        require(compression.isFinite() && compression in 0f..1f)
        require(colorHz.isFinite() && colorHz in 2500f..18000f)
    }
}

data class LacquerLoopProfile(val seconds: Float = 2f, val articulationDepth: Float = .12f,
    val spectralDepth: Float = .22f, val prerollCycles: Int = 3) {
    init {
        require(seconds.isFinite() && seconds in 1f..4f)
        require(articulationDepth.isFinite() && articulationDepth in .02f..0.3f)
        require(spectralDepth.isFinite() && spectralDepth in .02f..0.5f)
        require(prerollCycles in 2..6)
    }
}

data class LacquerProfile(val layers: List<LacquerLayer>,
    val processing: LacquerProcessing = LacquerProcessing(),
    val loop: LacquerLoopProfile = LacquerLoopProfile()) {
    init {
        require(layers.size in 1..4 && layers.map { it.role }.distinct().size == layers.size)
        require(layers.any { it.role == LacquerRole.BODY && it.ratio == 1f && it.gain > 0f })
    }
}

/** A body, a separately struck edge, and an independently developing softer support.
 * No detuned fixed chord, duplicated finished-tone tap, random onset order, or samples.
 * Nonlinearity and filters run at 4x, followed by the shared anti-alias path. */
object Lacquer {
    const val RATE = Dsp.RATE
    const val DEFAULT_MIDI = 48
    const val ROOT_MIDI = DEFAULT_MIDI
    const val MIDI_MIN = 24
    const val MIDI_MAX = 96
    const val TUNE_SEMITONES = 24
    const val HOLD_LOOP = .99f
    const val LOOP_THRESHOLD = HOLD_LOOP
    const val MODEL_VERSION = 1
    internal const val MAX_FINITE_SECONDS = 8f
    /** Room after the latest release so the finite buffer neither truncates it nor exceeds the host budget. */
    private const val TAIL_PAD_SECONDS = .035
    private const val INTERNAL_RATE = RATE * Dsp.OVERSAMPLE
    private const val TAU = 2.0 * PI
    private const val TABLE_SIZE = 2048

    private val settings = arrayOf(
        floatArrayOf(.35f, .45f, .60f, .45f, .25f),
        floatArrayOf(.45f, .70f, .40f, .50f, .20f),
        floatArrayOf(.30f, .55f, .65f, .55f, .30f),
        floatArrayOf(.20f, .35f, .80f, .35f, .30f),
        floatArrayOf(.30f, .60f, .45f, .25f, .20f),
        floatArrayOf(.60f, .40f, .60f, .80f, .25f))

    fun macrosFor(voice: LacquerVoice): List<MacroSpec> {
        val s = settings[voice.ordinal]
        return listOf(MacroSpec("TUNE", .5f, .5f), MacroSpec("RELIEF", s[0], .30f),
            MacroSpec("SHEEN", s[1], .45f), MacroSpec("BODY", s[2], .50f),
            MacroSpec("CONTOUR", s[3], .45f), MacroSpec("GLUE", s[4], 0f), MacroSpec("HOLD", 0f, 0f))
    }
    fun defaults(voice: LacquerVoice) = macrosFor(voice).associate { it.name to it.default }
    internal fun settled(voice: LacquerVoice, macros: Map<String, Float>) =
        defaults(voice).toMutableMap().also { m ->
            for ((k, v) in macros) if (k in m && v.isFinite()) m[k] = v.coerceIn(0f, 1f)
        }
    fun midiFor(@Suppress("UNUSED_PARAMETER") voice: LacquerVoice, tune: Float, midi: Int = DEFAULT_MIDI) =
        midi + (((if (tune.isFinite()) tune else .5f).coerceIn(0f, 1f) - .5f) * TUNE_SEMITONES).roundToInt()
    fun frequencyFor(midi: Int) = Keys.midiHz(midi)
    fun frequencyFor(voice: LacquerVoice, tune: Float, midi: Int = DEFAULT_MIDI) = frequencyFor(midiFor(voice, tune, midi))
    fun isLoop(hold: Float) = hold >= HOLD_LOOP
    fun drumClassFor(voice: LacquerVoice, macros: Map<String, Float> = emptyMap()) =
        if (isLoop(settled(voice, macros).getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL
    fun scramble(voice: LacquerVoice, random: Random, temperature: Float = .35f, near: Patch? = null) =
        Dsp.scrambleNear(settled(voice, near?.macros ?: emptyMap()), temperature, random)
            .toMutableMap().also { it["HOLD"] = it.getValue("HOLD").coerceAtMost(.95f) }

    /** Small initial bank: source identities and all role timing are explicit patch data. */
    fun profileFor(voice: LacquerVoice): LacquerProfile {
        fun layer(role: LacquerRole, source: LacquerSource, gain: Float, cutoff: Float,
            attack: Float, decay: Float, sustain: Float, release: Float,
            delay: Float, phase: Float, ratio: Float = 1f) = LacquerLayer(role, source,
            ratio, gain, cutoff, if (role == LacquerRole.EDGE) .65f else .28f,
            LacquerEnvelope(attack, decay, sustain, release),
            LacquerTiming(reliefSeconds = delay, attackRelief = if (role == LacquerRole.TRAIL) .8f else .2f,
                decayRelief = if (role == LacquerRole.EDGE) .10f else .35f, holdPhase = phase))
        val body = LacquerRole.BODY; val edge = LacquerRole.EDGE; val trail = LacquerRole.TRAIL
        val layers = when (voice) {
            LacquerVoice.KEY -> listOf(
                layer(body, LacquerSource.ROUNDED, .42f, 4800f, .006f, 1.05f, .20f, .55f, 0f, 0f),
                layer(edge, LacquerSource.GLASS, .20f, 9500f, .003f, .30f, .035f, .25f, .018f, .25f),
                layer(trail, LacquerSource.HOLLOW, .16f, 2600f, .027f, 1.35f, .16f, .60f, .075f, .58f))
            LacquerVoice.GLASS -> listOf(
                layer(body, LacquerSource.HOLLOW, .39f, 5500f, .009f, 1.25f, .18f, .70f, .014f, .16f),
                layer(edge, LacquerSource.GLASS, .25f, 12500f, .003f, .62f, .03f, .30f, 0f, 0f),
                layer(trail, LacquerSource.SILK, .14f, 3300f, .035f, 1.45f, .20f, .65f, .09f, .65f))
            LacquerVoice.BRASS -> listOf(
                layer(body, LacquerSource.BRASS, .43f, 4800f, .017f, 1.35f, .48f, .65f, 0f, 0f),
                layer(edge, LacquerSource.PLUCK, .18f, 8000f, .007f, .42f, .10f, .28f, .024f, .28f),
                layer(trail, LacquerSource.ROUNDED, .16f, 2900f, .052f, 1.45f, .40f, .72f, .085f, .62f))
            LacquerVoice.BASS -> listOf(
                layer(body, LacquerSource.ROUNDED, .50f, 2200f, .006f, .85f, .25f, .42f, 0f, 0f),
                layer(edge, LacquerSource.PLUCK, .16f, 6000f, .003f, .20f, .015f, .16f, .012f, .30f),
                layer(trail, LacquerSource.HOLLOW, .12f, 1900f, .019f, 1.10f, .15f, .45f, .050f, .60f))
            LacquerVoice.PLUCK -> listOf(
                layer(body, LacquerSource.PLUCK, .44f, 6200f, .003f, .76f, .05f, .35f, 0f, 0f),
                layer(edge, LacquerSource.GLASS, .21f, 11000f, .002f, .22f, .01f, .14f, .014f, .28f, 2f),
                layer(trail, LacquerSource.HOLLOW, .13f, 2800f, .014f, .90f, .08f, .40f, .060f, .62f))
            LacquerVoice.SILK -> listOf(
                layer(body, LacquerSource.SILK, .39f, 4700f, .048f, 2.10f, .58f, 1.05f, 0f, 0f),
                layer(edge, LacquerSource.GLASS, .16f, 8500f, .016f, 1.10f, .18f, .65f, .040f, .24f),
                layer(trail, LacquerSource.HOLLOW, .20f, 3000f, .105f, 2.35f, .52f, 1.10f, .145f, .62f),
                layer(LacquerRole.REINFORCEMENT, LacquerSource.ROUNDED, .05f, 1700f,
                    .028f, 1.80f, .40f, .85f, .075f, .42f, 2f))
        }
        return LacquerProfile(layers, LacquerProcessing(
            drive = if (voice == LacquerVoice.BRASS || voice == LacquerVoice.BASS) 1.6f else 1.2f,
            compression = .25f, colorHz = if (voice == LacquerVoice.BASS) 8500f else 14000f),
            LacquerLoopProfile(seconds = if (voice == LacquerVoice.SILK) 2.7f else 2f,
                // These are projection contours, never full attack retriggers. A
                // 20–22% range keeps the separated support audible in the complete
                // mix while the anchored body retains at least 78% of its sustain.
                articulationDepth = if (voice == LacquerVoice.PLUCK) .22f else .20f,
                spectralDepth = .36f, prerollCycles = 3))
    }

    internal data class Probe(val samples: FloatArray, val raw: FloatArray,
        val layers: Map<LacquerRole, FloatArray>, val onsetSeconds: Map<LacquerRole, Double>,
        val layerEndSeconds: Map<LacquerRole, Double>, val durationSeconds: Double,
        val requestedHz: Double, val renderedHz: Double, val seamError: Double = 0.0,
        val previousCycle: FloatArray = FloatArray(0), val prerollCycles: Int = 0,
        val renderNanos: Long = 0, val unwrappedSeamError: Double = 0.0)

    private data class EnvelopePlan(val onset: Double, val attack: Double,
        val decay: Double, val gate: Double, val release: Double, val sustain: Double) {
        val duration get() = onset + attack + decay + gate + release
        fun value(time: Double): Double {
            val t = time - onset
            if (t < 0.0 || t >= duration - onset) return 0.0
            if (t < attack) return smooth(t / attack)
            if (t < attack + decay) {
                val q = (t - attack) / decay
                return sustain + (1 - sustain) * (exp(-5 * q) - exp(-5.0)) / (1 - exp(-5.0))
            }
            if (t < attack + decay + gate) return sustain
            return sustain * (1 - smooth((t - attack - decay - gate) / release))
        }
        fun scaled(s: Double) = copy(onset = onset * s, attack = attack * s,
            decay = decay * s, gate = gate * s, release = release * s)
    }
    private fun smooth(x: Double) = x.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }

    private fun envelopes(voice: LacquerVoice, m: Map<String, Float>, profile: LacquerProfile): List<EnvelopePlan> {
        val relief = m.getValue("RELIEF").toDouble(); val contour = m.getValue("CONTOUR").toDouble()
        val hold = m.getValue("HOLD").toDouble()
        val gate = when (voice) {
            LacquerVoice.KEY -> .25; LacquerVoice.GLASS -> .3; LacquerVoice.BRASS -> .8
            LacquerVoice.BASS -> .15; LacquerVoice.PLUCK -> .08; LacquerVoice.SILK -> 1.0
        } * contour * (.3 + 1.7 * hold)
        val plans = profile.layers.map { l ->
            val e = l.envelope; val t = l.timing
            EnvelopePlan(t.onsetSeconds + t.reliefSeconds * relief,
                e.attackSeconds * (.45 + 1.5 * contour) * (1 + t.attackRelief * relief),
                e.decaySeconds * (.45 + 1.5 * contour) * (1 + t.decayRelief * relief),
                gate, e.releaseSeconds * (.5 + 1.5 * contour),
                e.sustain * (.45 + .55 * contour))
        }
        // Compress the complete schedule together, retaining the scheduled trail and
        // relative articulation. Nothing is cut before its last active envelope.
        val scale = min(1.0, (MAX_FINITE_SECONDS - TAIL_PAD_SECONDS) / plans.maxOf { it.duration })
        return plans.map { it.scaled(scale) }
    }

    private data class LoopPlan(val frames: Int, val hz: Double)
    private fun loopPlan(hz: Double, profile: LacquerProfile): LoopPlan {
        val multiple = if (profile.layers.any { it.ratio == .25f }) 4 else if (profile.layers.any { it.ratio == .5f }) 2 else 1
        val cycles = (profile.loop.seconds * hz / multiple).roundToInt().coerceAtLeast(8) * multiple
        val frames = (cycles * RATE / hz).roundToInt()
        return LoopPlan(frames, cycles * RATE.toDouble() / frames)
    }

    /** Each template is an original harmonic recipe, rendered to a note-bandlimited
     * cycle. Table interpolation at 4x is followed by explicit output band-limiting. */
    private fun cycle(layer: LacquerLayer, hz: Double, sheen: Double, body: Double,
        velocity: Double, phase: Double): FloatArray {
        val recipe = when (layer.source) {
            LacquerSource.ROUNDED -> doubleArrayOf(1.0, .30, .18, .10, .065, .035)
            LacquerSource.HOLLOW -> doubleArrayOf(1.0, .035, .42, .022, .19, .018, .085)
            LacquerSource.GLASS -> doubleArrayOf(1.0, .15, .75, .09, .44, .06, .24, .025, .12, .01, .05)
            LacquerSource.BRASS -> doubleArrayOf(1.0, .72, .50, .37, .29, .22, .18, .14, .10, .08, .06, .04)
            LacquerSource.PLUCK -> doubleArrayOf(1.0, .58, .38, .26, .18, .12, .085, .06, .035)
            LacquerSource.SILK -> doubleArrayOf(1.0, .20, .36, .12, .18, .06, .085, .025)
        }
        val coefficients = DoubleArray(recipe.size) { j ->
            val h = j + 1
            val bandwidth = ((18500 - h * hz) / 1800).coerceIn(0.0, 1.0)
            val tilt = if (j == 0) 1.0 else (.30 + 1.35 * sheen) * (.65 + .35 * velocity) /
                h.toDouble().pow((1 - sheen) * .45)
            val rounding = if (layer.role == LacquerRole.BODY && j > 0) 1.25 - .65 * body else 1.0
            recipe[j] * bandwidth * tilt * rounding
        }
        val norm = coefficients.sum().coerceAtLeast(1.0)
        return FloatArray(TABLE_SIZE + 1) { i ->
            var y = 0.0
            val p = TAU * (i % TABLE_SIZE) / TABLE_SIZE
            for (j in coefficients.indices) y += coefficients[j] * sin((j + 1) * p + phase * j / (j + 1))
            (y / norm).toFloat()
        }
    }

    private class LayerState(val layer: LacquerLayer, val env: EnvelopePlan, hz: Double,
        val m: Map<String, Float>, val velocity: Double, val gain: Double,
        val held: Boolean, val period: Int, val profile: LacquerLoopProfile, phaseSeed: Double) {
        private val table = cycle(layer, hz * layer.ratio, m.getValue("SHEEN").toDouble(),
            m.getValue("BODY").toDouble(), velocity, phaseSeed)
        private val step = hz * layer.ratio / INTERNAL_RATE
        private var phase = 0.0
        private var filter1 = 0.0
        private var filter2 = 0.0
        private val relief = m.getValue("RELIEF").toDouble()
        private val sheen = m.getValue("SHEEN").toDouble()
        private val body = m.getValue("BODY").toDouble()
        private val contour = m.getValue("CONTOUR").toDouble()
        fun tick(index: Long): Float {
            val t = index.toDouble() / INTERNAL_RATE
            if (!held && t < env.onset) return 0f
            val at = phase * TABLE_SIZE
            val j = at.toInt().coerceIn(0, TABLE_SIZE - 1)
            val source = table[j] + (table[j + 1] - table[j]) * (at - j)
            phase += step
            phase -= floor(phase)
            val development = if (held) {
                val angle = TAU * ((index % period).toDouble() / period - layer.timing.holdPhase * relief)
                // Low-depth periodic projection retains articulation after the real
                // onsets have settled. Phase separation is the documented HOLD map.
                (.5 + .5 * cos(angle)) * (.75 + .25 * cos(angle * 2 - contour * .5))
            } else exp(-max(0.0, t - env.onset) / max(.012, env.decay * (.18 + .32 * contour)))
            val amplitude = if (held) (1 - profile.articulationDepth * (.65 + .35 * contour) +
                profile.articulationDepth * (.65 + .35 * contour) * development) else env.value(t)
            val spectral = if (held) 1 - profile.spectralDepth + profile.spectralDepth * development else 1.0
            val cutoff = (layer.cutoffHz * (.30 + 1.15 * sheen) *
                (if (layer.role == LacquerRole.BODY) 1.1 - .30 * body else 1.0) *
                (1 + layer.filterEnvelope * development * (.25 + .75 * velocity)) * spectral)
                .coerceIn(100.0, 18500.0)
            val a = TAU * cutoff / (INTERNAL_RATE + TAU * cutoff)
            filter1 += a * (source - filter1)
            filter2 += a * (filter1 - filter2)
            return (filter2 * amplitude * gain).toFloat()
        }
    }

    private class Glue(val m: Map<String, Float>, val processing: LacquerProcessing) {
        private val glue = m.getValue("GLUE").toDouble()
        private var envelope = 0.0
        private var color = 0.0
        private val attack = exp(-1.0 / (.012 * INTERNAL_RATE))
        private val release = exp(-1.0 / (.18 * INTERNAL_RATE))
        private val cutoff = processing.colorHz * (1 - .35 * glue)
        private val a = TAU * cutoff / (INTERNAL_RATE + TAU * cutoff)
        fun tick(input: Double): Float {
            val level = abs(input)
            val coefficient = if (level > envelope) attack else release
            envelope = coefficient * envelope + (1 - coefficient) * level
            val compressed = input / (1 + processing.compression * glue * envelope * 1.5)
            val drive = 1 + processing.drive * glue
            val saturated = if (glue == 0.0) compressed else tanh(compressed * drive) / drive * (1 + .14 * glue)
            color += a * (saturated - color)
            return ((1 - .22 * glue) * saturated + .22 * glue * color).toFloat()
        }
    }

    private fun finish(raw: FloatArray): FloatArray {
        // The tick loop polls the preview thread's interrupt; this band-limit and resample
        // pass over the full 4x buffer is long enough to deserve its own check first.
        if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("LACQUER render cancelled")
        val filtered = raw.copyOf()
        Tide.bandLimit(filtered, INTERNAL_RATE)
        val out = Dsp.decimate(filtered, RATE)
        if (out.isEmpty()) return out
        val hp = Dsp.OnePole(RATE)
        for (i in out.indices) { val x = out[i]; out[i] = x - hp.lp(x, 12f) }
        return out
    }

    private fun play(voice: LacquerVoice, macros: Map<String, Float>, midi: Int,
        velocity: Float, profile: LacquerProfile, seconds: Float?, normalize: Boolean,
        solo: LacquerRole?, recordLayers: Boolean): Probe {
        require(midi in MIDI_MIN..MIDI_MAX) { "LACQUER midi out of $MIDI_MIN..$MIDI_MAX: $midi" }
        require(velocity.isFinite() && velocity in 0f..1f) { "LACQUER velocity out of 0..1: $velocity" }
        require(seconds == null || seconds.isFinite() && seconds in .01f..MAX_FINITE_SECONDS)
        require(solo == null || profile.layers.any { it.role == solo })
        val started = System.nanoTime()
        val m = settled(voice, macros)
        val plans = envelopes(voice, m, profile)
        val requestedHz = frequencyFor(voice, m.getValue("TUNE"), midi).toDouble()
        val held = isLoop(m.getValue("HOLD")) && seconds == null
        val loop = loopPlan(requestedHz, profile)
        val hz = if (held) loop.hz else requestedHz
        val strength = velocity.toDouble().pow(.75)
        val body = m.getValue("BODY").toDouble(); val sheen = m.getValue("SHEEN").toDouble()
        val gains = profile.layers.map { l -> l.gain * when (l.role) {
            LacquerRole.BODY -> .70 + .65 * body
            LacquerRole.EDGE -> (.35 + 1.00 * sheen) * (.65 + .35 * velocity)
            LacquerRole.TRAIL -> (.65 + .40 * body) * (.75 + .25 * m.getValue("CONTOUR"))
            LacquerRole.REINFORCEMENT -> .30 + .70 * body
        } }
        // Structural mix bound before shared leveling or saturation. Positive unity
        // source bounds and nonresonant filters make this a genuine peak budget.
        val mixScale = min(1.0, .82 / gains.sum().coerceAtLeast(.001)) * strength
        val random = Random(Dsp.seedFor("LACQUER", MODEL_VERSION, voice, midi, profile))
        val period = loop.frames * Dsp.OVERSAMPLE
        val states = profile.layers.indices.map { i -> LayerState(profile.layers[i], plans[i], hz,
            m, velocity.toDouble(), gains[i] * mixScale, held, period, profile.loop,
            random.nextDouble(-.08, .08)) }
        val glue = Glue(m, profile.processing)
        val duration = (plans.maxOf { it.duration } + TAIL_PAD_SECONDS).coerceAtMost(MAX_FINITE_SECONDS.toDouble())
        val n = if (held) period * 2 else (((seconds?.toDouble() ?: duration) * RATE).roundToInt() * Dsp.OVERSAMPLE)
        val raw = FloatArray(n)
        val taps = if (recordLayers) profile.layers.associate { it.role to FloatArray(n) } else emptyMap()
        var index = 0L
        fun tick(destination: Int) {
            if (index and 8191L == 0L && Thread.currentThread().isInterrupted)
                throw CancellationException("LACQUER render cancelled")
            var sum = 0.0
            for (i in states.indices) {
                val x = states[i].tick(index)
                if (destination >= 0) taps[profile.layers[i].role]?.set(destination, x)
                if (solo == null || profile.layers[i].role == solo) sum += x
            }
            val x = glue.tick(sum)
            if (destination >= 0) raw[destination] = x
            index++
        }
        if (held) repeat(period * profile.loop.prerollCycles) { tick(-1) }
        for (i in raw.indices) tick(i)
        val finished = finish(raw)
        val layerSamples = taps.mapValues { finish(it.value) }
        var seam = 0.0
        val unwrappedSeam = if (held && velocity > 0f) Keys.seamError(finished, loop.frames) else 0.0
        if (held) {
            check(unwrappedSeam < Keys.MAX_SEAM_ERROR) { "LACQUER $voice sustain failed to settle: $unwrappedSeam" }
            // Project-standard unity-sum wrap using the genuinely preceding cycle.
            // The oscillator frequencies move only the fraction of a sample needed
            // to fit whole cycles (under one cent even at the shortest profile).
            val need = min(1280, loop.frames / 3)
            val fade = need - 256
            for (i in 0 until need) {
                val w = if (i >= fade) 1.0 else smooth(i.toDouble() / fade)
                val dst = finished.size - need + i
                val src = loop.frames - need + i
                finished[dst] = ((1 - w) * finished[dst] + w * finished[src]).toFloat()
            }
            if (normalize && velocity > 0f) Dsp.levelTo(finished, RATE, Dsp.MELODIC_LOUDNESS_TARGET)
            seam = if (velocity == 0f) 0.0 else Keys.seamError(finished, loop.frames)
            check(seam < Keys.MAX_SEAM_ERROR) { "LACQUER $voice held seam $seam" }
        } else {
            if (normalize && velocity > 0f) Dsp.levelTo(finished, RATE, Dsp.MELODIC_LOUDNESS_TARGET)
            Dsp.fadeTail(finished)
        }
        return Probe(if (held) finished.copyOfRange(loop.frames, finished.size) else finished,
            if (held) raw.copyOfRange(period, raw.size) else raw,
            if (held) layerSamples.mapValues { it.value.copyOfRange(loop.frames, it.value.size) } else layerSamples,
            profile.layers.indices.associate { profile.layers[it].role to plans[it].onset },
            profile.layers.indices.associate { profile.layers[it].role to plans[it].duration },
            if (held) loop.frames.toDouble() / RATE else finished.size.toDouble() / RATE,
            requestedHz, hz, seam,
            if (held) finished.copyOfRange(0, loop.frames) else FloatArray(0),
            if (held) profile.loop.prerollCycles else 0, System.nanoTime() - started, unwrappedSeam)
    }

    internal fun renderProbe(voice: LacquerVoice, macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI, velocity: Float = 1f, profile: LacquerProfile = profileFor(voice),
        seconds: Float? = null, normalize: Boolean = false, solo: LacquerRole? = null) =
        play(voice, macros, midi, velocity, profile, seconds, normalize, solo, recordLayers = true)

    fun render(voice: LacquerVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f, profile: LacquerProfile = profileFor(voice)): Snip =
        Snip(play(voice, macros, midi, velocity, profile, null, true, null, recordLayers = false).samples,
            channels = 1, sampleRate = RATE)

    /** Diagnostic solos preserve the layer's actual source, schedule and macro mapping.
     * Loudness matching makes the quieter trail audible without changing normal mixing. */
    fun renderSolo(voice: LacquerVoice, role: LacquerRole, macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI, velocity: Float = 1f, profile: LacquerProfile = profileFor(voice)): Snip =
        Snip(play(voice, macros, midi, velocity, profile, null, true, role, recordLayers = false).samples,
            channels = 1, sampleRate = RATE)
}
