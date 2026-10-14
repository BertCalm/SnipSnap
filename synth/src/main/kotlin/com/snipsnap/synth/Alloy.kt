package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.Resampler
import kotlin.math.*
import kotlin.random.Random

enum class AlloyVoice { BASS, KEY, PLUCK, BRASS, STRING, SOFT }

/** Four-operator phase modulation with an original generated attack in its modulation port.
 * All PM depths are radians, never frequency increments. The direct attack and its conditioned
 * projection share a source, so FUSE changes the body as well as the attack/body balance.
 * Sources and all state run at 176.4 kHz. Final band limiting precedes the shared 4x decimator.
 * This is a new instrument, not a reconstruction of Yamaha operator data or PCM. */
object Alloy {
    const val RATE = Dsp.RATE
    const val MODEL_VERSION = 1
    const val DEFAULT_MIDI = 48
    const val MIDI_MIN = 24
    const val MIDI_MAX = 96
    const val TUNE_SEMITONES = 24
    const val HOLD_LOOP = .99f
    private const val INTERNAL_RATE = RATE * Dsp.OVERSAMPLE
    private const val TAU = 2.0 * PI
    // Fixed calibration of the synthesized source, not per-note peak normalization.
    private const val SOURCE_REFERENCE = .12

    private val settings = arrayOf(
        floatArrayOf(.25f, .35f, .35f, .80f, .35f),
        floatArrayOf(.45f, .45f, .45f, .60f, .55f),
        floatArrayOf(.35f, .55f, .60f, .45f, .25f),
        floatArrayOf(.80f, .40f, .50f, .60f, .55f),
        floatArrayOf(.55f, .40f, .35f, .55f, .60f),
        floatArrayOf(.40f, .25f, .20f, .65f, .75f),
    )
    fun macrosFor(voice: AlloyVoice): List<MacroSpec> {
        val s = settings[voice.ordinal]
        return listOf(MacroSpec("TUNE", .5f, .5f), MacroSpec("MATERIAL", s[0], .35f),
            MacroSpec("FUSE", s[1], .30f), MacroSpec("EDGE", s[2], .40f),
            MacroSpec("BODY", s[3], .50f), MacroSpec("LENGTH", s[4], .45f), MacroSpec("HOLD", 0f, 0f))
    }
    fun defaults(voice: AlloyVoice) = macrosFor(voice).associate { it.name to it.default }
    internal fun settled(voice: AlloyVoice, macros: Map<String, Float>) = defaults(voice).toMutableMap().also { m ->
        for ((k, v) in macros) if (k in m && v.isFinite()) m[k] = v.coerceIn(0f, 1f)
    }
    fun scramble(voice: AlloyVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> =
        Dsp.scrambleNear(settled(voice, if (near?.engine == "ALLOY") near.macros else emptyMap()), temperature, random)
            .toMutableMap().also { it["HOLD"] = it.getValue("HOLD").coerceAtMost(.95f) }
    fun isLoop(hold: Float) = hold >= HOLD_LOOP
    fun midiFor(@Suppress("UNUSED_PARAMETER") voice: AlloyVoice, tune: Float, midi: Int = DEFAULT_MIDI) =
        midi + ((tune.coerceIn(0f, 1f) - .5f) * TUNE_SEMITONES).roundToInt()
    fun frequencyFor(midi: Int) = Keys.midiHz(midi)
    fun frequencyFor(voice: AlloyVoice, tune: Float, midi: Int = DEFAULT_MIDI) = frequencyFor(midiFor(voice, tune, midi))
    fun drumClassFor(voice: AlloyVoice, macros: Map<String, Float> = emptyMap()) =
        if (isLoop(settled(voice, macros).getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL

    private fun projection(r: List<Float>, loss: List<Float>, gain: List<Float>) = AlloyConfig.Projection(r, loss, gain)
    private val recipes: List<AlloyConfig> = AlloyVoice.entries.map { voice ->
        val ratios = when (voice) {
            AlloyVoice.BASS -> listOf(1f, 1f, 2f, 3f)
            AlloyVoice.KEY -> listOf(1f, 2f, 3f, 7f)
            AlloyVoice.PLUCK -> listOf(1f, 2f, 5f, 3f)
            AlloyVoice.BRASS -> listOf(1f, 2f, 1f, 3f)
            AlloyVoice.STRING -> listOf(1f, 2f, 3f, 4f)
            AlloyVoice.SOFT -> listOf(1f, 1f, 2f, 3f)
        }
        val depths = when (voice) {
            AlloyVoice.BASS -> listOf(.85f, .30f, .18f)
            AlloyVoice.KEY -> listOf(.65f, .72f, .28f)
            AlloyVoice.PLUCK -> listOf(.62f, .45f, .48f)
            AlloyVoice.BRASS -> listOf(1.0f, .25f, .38f)
            AlloyVoice.STRING -> listOf(.72f, .42f, .22f)
            AlloyVoice.SOFT -> listOf(.42f, .30f, .18f)
        }
        val attack = when (voice) { AlloyVoice.BRASS -> .025f; AlloyVoice.STRING -> .018f; AlloyVoice.SOFT -> .045f; else -> .002f }
        val duration = when (voice) { AlloyVoice.BASS -> 1.5f; AlloyVoice.PLUCK -> 1.15f; AlloyVoice.KEY -> 1.85f; AlloyVoice.BRASS -> 2.6f; AlloyVoice.STRING -> 2.8f; AlloyVoice.SOFT -> 3.1f }
        val sustain = when (voice) { AlloyVoice.BASS -> .38f; AlloyVoice.PLUCK -> .18f; AlloyVoice.KEY -> .32f; else -> .72f }
        AlloyConfig(
            ratios.mapIndexed { j, r -> AlloyConfig.Operator(r, if (j == 0) .80f else if (j == 1) .12f else 0f,
                if (j == 0) 1.0f else .12f + .16f * (3 - j), if (j == 0) 1f else .12f + .10f * (3 - j)) },
            listOf(AlloyConfig.Route(1, 0, depths[0]), AlloyConfig.Route(2, 1, depths[1]), AlloyConfig.Route(3, 0, depths[2])),
            projection(listOf(1f, 2.71f, 4.13f), listOf(.10f, .08f, .05f), listOf(.58f, .25f, .17f)),
            projection(listOf(1f, 2f, 3.94f, 6.17f), listOf(.36f, .24f, .14f, .07f), listOf(.47f, .25f, .18f, .10f)),
            projection(listOf(1f, 2f, 3f), listOf(.20f, .16f, .09f), listOf(.35f, .38f, .27f)),
            AlloyConfig.Envelope(attack, duration, if (voice == AlloyVoice.SOFT) .42f else .24f, sustain),
            AlloyConfig.Held(if (voice == AlloyVoice.SOFT) 1.2f else .72f,
                if (voice == AlloyVoice.PLUCK) .22f else if (voice == AlloyVoice.SOFT) .18f else .20f, .35f),
            fuseRadians = if (voice == AlloyVoice.SOFT) 2.0f else 2.4f,
            cutoffRatio = if (voice == AlloyVoice.BASS) 10f else 14f,
        )
    }
    fun configFor(voice: AlloyVoice) = recipes[voice.ordinal]

    internal data class Diagnostics(val rawPeak: Double, val maxPhaseModulation: Double,
        val maxConditionedAttack: Double, val rootHz: Double, val internalRate: Int = INTERNAL_RATE)
    internal data class Probe(val samples: FloatArray, val body: FloatArray, val directAttack: FloatArray,
        val conditionedAttack: FloatArray, val previousCycle: FloatArray = FloatArray(0),
        val seamError: Double = 0.0, val stateError: Double = 0.0, val prerollCycles: Int = 0,
        val diagnostics: Diagnostics)

    /** A sum of generated modes, with bounded finite excitation or a gentle cyclic contact.
     * Held modes are fitted independently to the same loop period; no coarse carrier detune. */
    private class Source(val p: AlloyConfig.Projection, hz: Double, edge: Double, length: Double, held: Boolean, period: Int, val rate: Int) {
        val phase = DoubleArray(p.ratios.size)
        val amplitude = DoubleArray(phase.size) { p.gains[it].toDouble() }
        val step = DoubleArray(phase.size)
        val loss = DoubleArray(phase.size)
        val gain = DoubleArray(phase.size)
        init {
            for (j in phase.indices) {
                var f = hz * p.ratios[j]
                if (held) f = round(f * period / RATE) * RATE / period
                step[j] = TAU * f / rate
                loss[j] = exp(-1.0 / (rate * p.losses[j] * (.7 + .55 * length)))
                // Subordinate modes vanish before they can cross the output bandwidth ceiling.
                gain[j] = if (f > 11500.0) 0.0 else (1.0 - (f / 15000.0).pow(4)).coerceAtLeast(0.0) *
                    if (j == 0) 1.0 else (.40 + .80 * edge) * if (held) .6 + .8 * length else 1.0
            }
        }
        fun tick(held: Boolean): Double {
            var x = 0.0
            for (j in phase.indices) {
                x += sin(phase[j]) * gain[j] * if (held) p.gains[j].toDouble() else amplitude[j]
                phase[j] += step[j]; if (phase[j] >= TAU) phase[j] -= TAU
                if (!held) amplitude[j] *= loss[j]
            }
            return x
        }
        fun state() = phase.flatMap { listOf(sin(it), cos(it)) }.toDoubleArray() + amplitude
    }

    private class Engine(val voice: AlloyVoice, val m: Map<String, Float>, val midi: Int,
        val velocity: Float, val held: Boolean, val period: Int, val config: AlloyConfig, val attackModulation: Boolean, val rate: Int) {
        val material = m.getValue("MATERIAL").toDouble()
        val fuse = m.getValue("FUSE").toDouble()
        val edge = m.getValue("EDGE").toDouble()
        val body = m.getValue("BODY").toDouble()
        val length = m.getValue("LENGTH").toDouble()
        val hz = frequencyFor(voice, m.getValue("TUNE"), midi).toDouble().let {
            if (held) round(it * period / RATE) * RATE / period else it
        }
        val wood = Source(config.wood, hz, edge, length, held, period, rate)
        val tine = Source(config.tine, hz, edge, length, held, period, rate)
        val breath = Source(config.breath, hz, edge, length, held, period, rate)
        private val noise = Dsp.Noise(Dsp.seedFor("ALLOY", MODEL_VERSION, voice, midi))
        private val phase = DoubleArray(4)
        private val step = DoubleArray(4) { j ->
            val f = hz * config.operators[j].ratio
            TAU * (if (held) round(f * period / RATE) * RATE / period else f) / rate
        }
        private val opBandGain = DoubleArray(4) { j ->
            val x = ((12000.0 - hz * config.operators[j].ratio) / 4000.0).coerceIn(0.0, 1.0)
            x * x * (3 - 2 * x)
        }
        private val modulation = DoubleArray(4)
        private val op = DoubleArray(4)
        private val opEnvelope = DoubleArray(4) { 1.0 }
        private val opLoss = DoubleArray(4) { exp(-1.0 / (rate * config.operators[it].decay * (.35 + 2.1 * length))) }
        private val sourceBandwidth = min(4000.0, hz * (5.0 + 6.0 * edge))
        private val sourceLowCoefficient = 1.0 - exp(-TAU * sourceBandwidth / rate)
        private val sourceDcCoefficient = 1.0 - exp(-TAU * 28.0 / rate)
        private val noiseCoefficient = 1.0 - exp(-TAU * min(4200.0, hz * 10) / rate)
        private val outputCutoff = min(9500.0, max(hz * 2.5, hz * config.cutoffRatio * (.45 + .85 * edge) * (1.0 - .45 * body)))
        private val outputCoefficient = 1.0 - exp(-TAU * outputCutoff / rate)
        // A conservative high-register PM budget: nested PM and transient PM both taper.
        // 7.5 kHz / root sets the available sideband order, capped at the low-note voicing.
        private val sidebandScale = min(1.0, 7500.0 / (hz * (6.0 + config.operators.maxOf { it.ratio })))
        private val fuseDepth = config.fuseRadians * fuse * (.45 + .55 * velocity) * sidebandScale
        private val index = (.18 + 1.55 * edge) * (.5 + .5 * velocity) * sidebandScale
        private val routeGain = DoubleArray(config.routing.size) { config.routing[it].radians.toDouble() }
        init {
            // A ratio-only index limit misses nested graphs. Bound each node's phase
            // derivative recursively, in cycles/second, including the slew-limited attack.
            // Normalize incoming routes together so their balance survives the budget.
            val bandwidth = DoubleArray(4)
            for (j in 3 downTo 0) {
                val incoming = config.routing.indices.filter { config.routing[it].to == j }
                val depth = incoming.sumOf { routeGain[it] }
                val depthScale = if (depth > 3.0) 3.0 / depth else 1.0
                val requested = incoming.sumOf { k ->
                    val from = config.routing[k].from
                    routeGain[k] * depthScale * index * opBandGain[from] * bandwidth[from]
                }
                val transient = fuseDepth * sourceBandwidth * .80 * if (j == 0) 1.0 else if (j == 1) .42 else 0.0
                val own = hz * config.operators[j].ratio
                val available = (20000.0 - own - transient).coerceAtLeast(0.0)
                val bandwidthScale = if (requested > available && requested > 0.0) available / requested else 1.0
                for (k in incoming) routeGain[k] *= depthScale * bandwidthScale
                bandwidth[j] = own + transient + requested * bandwidthScale
            }
        }
        val duration = (config.envelope.finiteSeconds * (.55 + 1.0 * length) + m.getValue("HOLD") * 1.25).coerceIn(1.0, 5.8)
        val release = config.envelope.releaseSeconds * (.55 + 1.20 * length)
        private var sourceDc = 0.0
        private var sourceLow = 0.0
        private var projection = 0.0
        private var noiseLow = 0.0
        private var outputLow = 0.0
        private var outputLow2 = 0.0
        private var outputDc = 0.0
        private var sample = 0L
        var bodySample = 0f; private set
        var directSample = 0f; private set
        var conditionedSample = 0f; private set
        var peak = 0.0; private set
        var maxPm = 0.0; private set
        var maxProjection = 0.0; private set

        fun tick(): Float {
            val t = sample.toDouble() / rate
            val cycle = if (held) TAU * (sample % (period.toLong() * (rate / RATE))) / (period.toDouble() * (rate / RATE)) else 0.0
            val woodWeight = (1.0 - 2.0 * material).coerceAtLeast(0.0)
            val breathWeight = (2.0 * material - 1.0).coerceAtLeast(0.0)
            val tineWeight = 1.0 - woodWeight - breathWeight
            if (!held) noiseLow += noiseCoefficient * (noise.next() - noiseLow)
            val contact = if (held) config.held.drive * (.65 + .65 * length) * (.78 + .22 * cos(cycle)) else
                (1.0 - exp(-t / (.0006 + .0022 * (1 - edge))))
            val airy = if (held) .18 * config.held.roughness * sin(7 * cycle + .7 * sin(3 * cycle)) else
                noiseLow * .72 * exp(-t / (.08 + .11 * material))
            val source = (woodWeight * wood.tick(held) + tineWeight * tine.tick(held) +
                breathWeight * (breath.tick(held) + airy)) * contact * (.22 + .22 * velocity)
            // DC removal, bounded bandwidth and a bounded slew precede fixed-scale projection.
            sourceDc += sourceDcCoefficient * (source - sourceDc)
            sourceLow += sourceLowCoefficient * ((source - sourceDc) - sourceLow)
            val wanted = tanh(sourceLow / SOURCE_REFERENCE)
            val maxSlope = TAU * sourceBandwidth / rate * .80
            projection += (wanted - projection).coerceIn(-maxSlope, maxSlope)
            conditionedSample = projection.toFloat()
            maxProjection = max(maxProjection, abs(projection))
            val attackPm = if (attackModulation) projection * fuseDepth else 0.0
            modulation.fill(0.0)
            for (j in 3 downTo 0) {
                val pm = (modulation[j] + if (j == 0) attackPm else if (j == 1) attackPm * .42 else 0.0).coerceIn(-8.0, 8.0)
                maxPm = max(maxPm, abs(pm))
                val sustain = config.operators[j].sustain
                val env = if (held) sustain.toDouble() else sustain + (1 - sustain) * opEnvelope[j]
                op[j] = sin(phase[j] + pm) * env * opBandGain[j]
                for (k in config.routing.indices) {
                    val route = config.routing[k]
                    if (route.from == j) modulation[route.to] += op[j] * routeGain[k] * index
                }
                phase[j] += step[j]; if (phase[j] >= TAU) phase[j] -= TAU
                if (!held) opEnvelope[j] *= opLoss[j]
            }
            val onset = if (held) 1.0 else 1.0 - exp(-t / (config.envelope.attackSeconds * (1.2 - .65 * edge)))
            val decay = if (held) 1.0 else config.envelope.sustain + (1 - config.envelope.sustain) * exp(-t / (.11 + 1.6 * length + .45 * body))
            val tail = if (held || t <= duration - release) 1.0 else {
                val x = ((duration - t) / release).coerceIn(0.0, 1.0); x * x * (3 - 2 * x)
            }
            val envelope = onset * decay * tail * velocity.pow(.75f)
            // An unmodulated root always supports the played pitch; BODY varies its balance,
            // never introduces a shared sub-octave beneath unlike voice configurations.
            val root = sin(phase[0] - step[0]) * (.30 + .43 * body)
            val tonal = root + op.indices.sumOf { op[it] * config.operators[it].gain } * (.78 - .30 * body)
            outputLow += outputCoefficient * (tonal - outputLow)
            outputLow2 += outputCoefficient * (outputLow - outputLow2)
            bodySample = (outputLow2 * envelope * .70).toFloat()
            // Both renders of an ablation use this same coefficient, including at FUSE=1.
            directSample = (source * (.27 - .10 * fuse) * envelope).toFloat()
            val mixed = bodySample + directSample
            outputDc += sourceDcCoefficient * (mixed - outputDc)
            val out = mixed - outputDc
            peak = max(peak, abs(out)); sample++
            return out.toFloat()
        }
        fun state() = phase.flatMap { listOf(sin(it), cos(it)) }.toDoubleArray() + wood.state() + tine.state() + breath.state() +
            doubleArrayOf(sourceDc, sourceLow, projection, outputLow, outputLow2, outputDc) + opEnvelope
        fun diagnostics() = Diagnostics(peak, maxPm, maxProjection, hz, rate)
    }

    // SynthScreen previews run in runInterruptible: a newer request interrupts this
    // thread, so the long loops and the passes stop instead of finishing beside their replacement.
    private fun stopIfUnwanted() {
        if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("ALLOY render no longer wanted")
    }

    internal fun finish(raw: FloatArray, rate: Int): FloatArray {
        stopIfUnwanted()
        Tide.bandLimit(raw, rate)
        stopIfUnwanted()
        val four = if (rate == INTERNAL_RATE) raw else Resampler.resample(Snip(raw, channels = 1, sampleRate = rate), INTERNAL_RATE).samples
        stopIfUnwanted()
        return Dsp.decimate(four, RATE)
    }

    /** Diagnostic render; taps are pre-output DC removal, separately band limited and downsampled.
     * Disabling attackModulation preserves the entire direct layer and conventional PM graph.
     * A held render has no initial strike: it contains the selected material's cyclic contact.
     * With [taps] false the body, direct and projection taps are neither kept nor conditioned and
     * come back empty: [render] and [renderLoop] read only the samples, which [finish] conditions
     * from the raw sum alone, so skipping the taps leaves every sample as it was. */
    internal fun renderProbe(voice: AlloyVoice, macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI, velocity: Float = 1f, seconds: Float? = null,
        normalize: Boolean = false, attackModulation: Boolean = true, config: AlloyConfig = configFor(voice),
        oversample: Int = Dsp.OVERSAMPLE, taps: Boolean = true): Probe {
        require(midi in MIDI_MIN..MIDI_MAX) { "ALLOY midi out of $MIDI_MIN..$MIDI_MAX: $midi" }
        require(velocity.isFinite() && velocity in 0f..1f) { "ALLOY velocity out of 0..1: $velocity" }
        require(seconds == null || seconds.isFinite() && seconds in .05f..12f)
        require(oversample == 4 || oversample == 8) { "ALLOY probe oversample must be 4 or 8" }
        val rate = RATE * oversample
        val m = settled(voice, macros)
        val held = isLoop(m.getValue("HOLD"))
        val hz = frequencyFor(voice, m.getValue("TUNE"), midi).toDouble()
        val periods = round(hz * config.held.periodSeconds).coerceAtLeast(12.0)
        val period = (periods * RATE / hz).roundToInt().coerceAtLeast(2048)
        val engine = Engine(voice, m, midi, velocity, held, period, config, attackModulation, rate)
        if (velocity == 0f) {
            val frames = if (held && seconds == null) period else ((seconds ?: (engine.duration + .12).toFloat()) * RATE).roundToInt()
            val tapFrames = if (taps) frames else 0
            return Probe(FloatArray(frames), FloatArray(tapFrames), FloatArray(tapFrames), FloatArray(tapFrames),
                previousCycle = if (held && seconds == null) FloatArray(frames) else FloatArray(0), diagnostics = engine.diagnostics())
        }
        var cycles = 0
        var stateError = 0.0
        if (held) {
            var before = engine.state()
            do {
                repeat(period * oversample) { if (it % 8192 == 0) stopIfUnwanted(); engine.tick() }
                val after = engine.state()
                stateError = after.indices.maxOf { abs(after[it] - before[it]) }
                before = after; cycles++
            } while (cycles < 3 || stateError > 1e-5 && cycles < 12)
            check(stateError < 1e-3) { "ALLOY $voice held state failed to converge: $stateError" }
        }
        // Let DC removal and output conditioning settle after the finite envelope reaches zero.
        val frames = if (held && seconds == null) period * 2 else ((seconds ?: (engine.duration + .12).toFloat()) * RATE).roundToInt()
        // Actual future samples keep the sinc decimator's truncated-buffer edge away from
        // the exported seam. No tail copying or crossfade is needed to make cycles agree.
        val raw = FloatArray((frames + 256) * oversample)
        val tapSize = if (taps) raw.size else 0
        val body = FloatArray(tapSize); val direct = FloatArray(tapSize); val projection = FloatArray(tapSize)
        for (i in raw.indices) {
            if (i % 8192 == 0) stopIfUnwanted()
            raw[i] = engine.tick()
            if (taps) { body[i] = engine.bodySample; direct[i] = engine.directSample; projection[i] = engine.conditionedSample }
        }
        val out = finish(raw, rate).copyOf(frames)
        val bodyOut = if (taps) finish(body, rate).copyOf(frames) else body
        val directOut = if (taps) finish(direct, rate).copyOf(frames) else direct
        val projectionOut = if (taps) finish(projection, rate).copyOf(frames) else projection
        stopIfUnwanted()
        if (normalize && velocity > 0f) Dsp.levelTo(out, RATE, Dsp.MELODIC_LOUDNESS_TARGET * velocity.pow(.4f))
        if (held && seconds == null) {
            stopIfUnwanted()
            val seam = Keys.seamError(out, period)
            check(seam < Keys.MAX_SEAM_ERROR) { "ALLOY $voice held seam $seam" }
            fun cycle(tap: FloatArray) = if (taps) tap.copyOfRange(period, period * 2) else tap
            return Probe(out.copyOfRange(period, period * 2), cycle(bodyOut), cycle(directOut), cycle(projectionOut),
                out.copyOfRange(0, period), seam, stateError, cycles, engine.diagnostics())
        }
        return Probe(out, bodyOut, directOut, projectionOut, stateError = stateError, prerollCycles = cycles, diagnostics = engine.diagnostics())
    }

    fun renderLoop(voice: AlloyVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f, config: AlloyConfig = configFor(voice)): FloatArray =
        renderProbe(voice, macros + ("HOLD" to 1f), midi, velocity, normalize = true, config = config, taps = false).samples

    fun render(voice: AlloyVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f, config: AlloyConfig = configFor(voice)): Snip {
        val out = renderProbe(voice, macros, midi, velocity, normalize = true, config = config, taps = false).samples
        if (!isLoop(settled(voice, macros).getValue("HOLD"))) Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }
}
