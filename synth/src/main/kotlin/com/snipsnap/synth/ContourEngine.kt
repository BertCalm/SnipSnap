package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

enum class ContourVoice { BASS, KEY, PLUCK, LEAD, HOLLOW, SWELL }
enum class ContourRelation { LAG, OPPOSED, CURVE }
enum class ContourMotionShape { FALL, RISE, CYCLE }
enum class ContourFilterType { LOW_PASS, BAND_PASS }
enum class ContourDiagnostic { BODY_ONLY, EDGE_ONLY, COMBINED, FROZEN, OPEN, CLEAN }

/**
 * The patch's architecture, independently of its continuous macro settings.
 * Times are seconds; edgeLag is a fraction of a scan gesture/cycle. The optional
 * anchor is a sine at the requested root, deliberately subordinate to the body.
 */
data class ContourConfig(
    val bodyTable: ContourTable,
    val edgeTable: ContourTable,
    val edgeRatio: Float = 1f,
    val edgeLag: Float = .18f,
    val relation: ContourRelation = ContourRelation.LAG,
    val bodyLevel: Float = .74f,
    val edgeLevel: Float = .32f,
    val subLevel: Float = 0f,
    /** Relative non-root partial level; 1 preserves the original table balance. */
    val bodyHarmonics: Float = 1f,
    /** The edge's root contribution; the body continues to own the requested note. */
    val edgeRootLevel: Float = 1f,
    /** Independent edge excitation projection, before the shared filter. */
    val edgeAttack: Float = .005f,
    val edgeDecay: Float = .5f,
    val edgeSustain: Float = 1f,
    val bodyScanBias: Float = 0f,
    val edgeScanBias: Float = 0f,
    val bodyTravel: Float = .30f,
    val edgeTravel: Float = .46f,
    val ampAttack: Float = .008f,
    val ampDecay: Float = .65f,
    val ampSustain: Float = .35f,
    val ampRelease: Float = .65f,
    val sustainSeconds: Float = .8f,
    val filterAttack: Float = .005f,
    val filterDecay: Float = .5f,
    val filterAmount: Float = .7f,
    val motionShape: ContourMotionShape = ContourMotionShape.FALL,
    val motionSeconds: Float = 1.2f,
    val filterType: ContourFilterType = ContourFilterType.LOW_PASS,
    val loopSeconds: Float = 2f,
) {
    init {
        fun bounded(name: String, x: Float, lo: Float, hi: Float) =
            require(x.isFinite() && x in lo..hi) { "CONTOUR $name must be finite in $lo..$hi: $x" }
        bounded("edgeRatio", edgeRatio, .5f, 4f)
        bounded("edgeLag", edgeLag, 0f, .75f)
        bounded("bodyLevel", bodyLevel, .1f, 1f)
        bounded("edgeLevel", edgeLevel, 0f, .8f)
        bounded("subLevel", subLevel, 0f, .25f)
        require(bodyLevel >= subLevel * 2) { "CONTOUR root anchor must remain subordinate to the body" }
        bounded("bodyHarmonics", bodyHarmonics, .5f, 2.5f)
        bounded("edgeRootLevel", edgeRootLevel, 0f, 1f)
        bounded("edgeAttack", edgeAttack, .001f, 1.5f)
        bounded("edgeDecay", edgeDecay, .02f, 3f)
        bounded("edgeSustain", edgeSustain, 0f, 1f)
        bounded("bodyScanBias", bodyScanBias, -.8f, .8f)
        bounded("edgeScanBias", edgeScanBias, -.8f, .8f)
        bounded("bodyTravel", bodyTravel, 0f, .8f)
        bounded("edgeTravel", edgeTravel, 0f, 1f)
        bounded("ampAttack", ampAttack, .001f, 1.5f)
        bounded("ampDecay", ampDecay, .02f, 2f)
        bounded("ampSustain", ampSustain, 0f, 1f)
        bounded("ampRelease", ampRelease, .03f, 2.5f)
        bounded("sustainSeconds", sustainSeconds, .05f, 3f)
        require(ampAttack + ampDecay + sustainSeconds + ampRelease <= 8f) { "CONTOUR finite envelope exceeds eight seconds" }
        bounded("filterAttack", filterAttack, .001f, 1f)
        bounded("filterDecay", filterDecay, .02f, 3f)
        bounded("filterAmount", filterAmount, 0f, 1.5f)
        bounded("motionSeconds", motionSeconds, .1f, 3f)
        bounded("loopSeconds", loopSeconds, 1f, 4f)
    }

    companion object {
        /** Original model-1 recipe for JSON that predates the layer projections. */
        fun legacyForVoice(voice: ContourVoice): ContourConfig = when (voice) {
            ContourVoice.BASS -> ContourConfig(ContourTable.ROUND_PULSE, ContourTable.COPPER,
                bodyLevel = .80f, edgeLevel = .31f, subLevel = .09f,
                ampAttack = .006f, ampDecay = .55f, ampSustain = .48f, ampRelease = .55f,
                sustainSeconds = .75f, bodyTravel = .24f, edgeTravel = .50f, filterAmount = .6f)
            ContourVoice.KEY -> ContourConfig(ContourTable.SOFT_SAW, ContourTable.DIGITAL_BELL,
                bodyLevel = .76f, edgeLevel = .37f, ampDecay = .8f, ampSustain = .26f,
                ampRelease = .9f, sustainSeconds = 1.1f, relation = ContourRelation.CURVE,
                edgeTravel = .58f, filterDecay = .72f)
            ContourVoice.PLUCK -> ContourConfig(ContourTable.SAW_HOLLOW, ContourTable.CLUSTER,
                bodyLevel = .76f, edgeLevel = .39f, ampAttack = .003f, ampDecay = .45f,
                ampSustain = .07f, ampRelease = .32f, sustainSeconds = .45f,
                motionSeconds = .5f, edgeLag = .12f, filterDecay = .22f, filterAmount = .9f)
            ContourVoice.LEAD -> ContourConfig(ContourTable.COPPER, ContourTable.ODD_EVEN,
                bodyLevel = .78f, edgeLevel = .37f, ampAttack = .015f, ampDecay = .3f,
                ampSustain = .72f, ampRelease = .65f, sustainSeconds = 1.4f,
                motionShape = ContourMotionShape.CYCLE, motionSeconds = 1.1f,
                relation = ContourRelation.OPPOSED, bodyTravel = .27f, edgeTravel = .5f)
            ContourVoice.HOLLOW -> ContourConfig(ContourTable.ODD_EVEN, ContourTable.FORMANT,
                bodyLevel = .80f, edgeLevel = .35f, ampAttack = .022f, ampDecay = .6f,
                ampSustain = .52f, ampRelease = .85f, sustainSeconds = 1.2f,
                relation = ContourRelation.OPPOSED, filterType = ContourFilterType.BAND_PASS,
                // Keep the opposed travel smaller than OFFSET's displacement:
                // otherwise its two contributions cancel during the attack.
                bodyTravel = .25f, edgeTravel = .36f, filterAmount = .4f)
            ContourVoice.SWELL -> ContourConfig(ContourTable.SOFT_SAW, ContourTable.FORMANT,
                bodyLevel = .80f, edgeLevel = .32f, ampAttack = .65f, ampDecay = .65f,
                ampSustain = .78f, ampRelease = 1.5f, sustainSeconds = 2.1f,
                motionShape = ContourMotionShape.RISE, motionSeconds = 2.2f,
                bodyTravel = .32f, edgeTravel = .64f, filterAttack = .45f,
                filterDecay = 1.4f, filterAmount = .5f, loopSeconds = 3f)
        }

        /** Fresh recipes retain a solid root while exposing the moving partials. */
        fun forVoice(voice: ContourVoice): ContourConfig = legacyForVoice(voice).let { c ->
            when (voice) {
                ContourVoice.BASS -> c.copy(bodyHarmonics = 1.85f, edgeRootLevel = .45f,
                    edgeAttack = .006f, edgeDecay = .32f, edgeSustain = .78f)
                ContourVoice.KEY -> c.copy(bodyHarmonics = 1.8f, edgeRootLevel = .5f,
                    edgeAttack = .008f, edgeDecay = .65f, edgeSustain = .58f)
                ContourVoice.PLUCK -> c.copy(bodyHarmonics = 1.65f, edgeRootLevel = .45f,
                    edgeAttack = .003f, edgeDecay = .22f, edgeSustain = .4f)
                ContourVoice.LEAD -> c.copy(bodyHarmonics = 1.6f, edgeRootLevel = .55f,
                    edgeAttack = .012f, edgeDecay = .75f, edgeSustain = .86f)
                ContourVoice.HOLLOW -> c.copy(bodyHarmonics = 1.65f, edgeRootLevel = .5f,
                    edgeLevel = .65f, edgeTravel = .30f,
                    edgeAttack = .02f, edgeDecay = .85f, edgeSustain = .78f)
                ContourVoice.SWELL -> c.copy(bodyHarmonics = 1.9f, edgeRootLevel = .4f,
                    edgeAttack = .4f, edgeDecay = 1.5f, edgeSustain = .88f)
            }
        }
    }
}

/**
 * Original two-path wavetable synthesis; the existing Contour object is a rack
 * envelope effect and remains separate. Oscillators and nonlinear excitation
 * run at 4x. The shared filter is Dsp's generic stable TPT SVF, not a circuit
 * reconstruction. No limiter sits between it and the raw diagnostic output.
 */
object ContourEngine {
    const val MODEL_VERSION = 1
    const val RATE = Dsp.RATE
    /** Radius around the voice's root: the complete TUNE travel is 48 semitones. */
    const val TUNE_SEMITONES = 24
    const val HOLD_LOOP = .95f
    private const val INTERNAL_RATE = RATE * Dsp.OVERSAMPLE
    private const val CONTROL_STRIDE = 32
    private const val TAU = 2 * PI

    private val settings = arrayOf(
        floatArrayOf(.25f, .20f, .25f, .40f, .35f),
        floatArrayOf(.35f, .35f, .30f, .55f, .20f),
        floatArrayOf(.40f, .40f, .45f, .65f, .25f),
        floatArrayOf(.45f, .35f, .40f, .70f, .30f),
        floatArrayOf(.55f, .50f, .30f, .55f, .20f),
        floatArrayOf(.35f, .45f, .55f, .60f, .15f),
    )

    fun macrosFor(voice: ContourVoice): List<MacroSpec> {
        val s = settings[voice.ordinal]
        return listOf(MacroSpec("TUNE", .5f, .5f), MacroSpec("SCAN", s[0], .40f),
            MacroSpec("OFFSET", s[1], .25f), MacroSpec("MOTION", s[2], .20f),
            MacroSpec("FILTER", s[3], .55f), MacroSpec("DRIVE", s[4], 0f), MacroSpec("HOLD", 0f, 0f))
    }

    fun defaults(voice: ContourVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    internal fun settled(voice: ContourVoice, macros: Map<String, Float>): Map<String, Float> =
        defaults(voice).toMutableMap().also { m ->
            for ((key, value) in macros) if (key in m && value.isFinite()) m[key] = value.coerceIn(0f, 1f)
        }

    fun scramble(voice: ContourVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> =
        Dsp.scrambleNear(settled(voice, if (near is ContourPatch) near.macros else emptyMap()), temperature, random)
            .toMutableMap().also { it["HOLD"] = it.getValue("HOLD").coerceAtMost(.9f) }

    fun isLoop(hold: Float): Boolean = hold >= HOLD_LOOP
    fun rootMidi(voice: ContourVoice): Int = when (voice) {
        ContourVoice.BASS -> 36
        ContourVoice.KEY, ContourVoice.PLUCK, ContourVoice.LEAD -> 60
        ContourVoice.HOLLOW, ContourVoice.SWELL -> 48
    }
    fun midiFor(voice: ContourVoice, tune: Float): Int =
        rootMidi(voice) + (((if (tune.isFinite()) tune else .5f).coerceIn(0f, 1f) - .5f) * 2 * TUNE_SEMITONES).roundToInt()
    fun frequencyFor(voice: ContourVoice, tune: Float): Float = Keys.midiHz(midiFor(voice, tune))
    fun drumClassFor(voice: ContourVoice, macros: Map<String, Float> = emptyMap()): DrumClass =
        if (isLoop(settled(voice, macros).getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL

    /** A smooth bounded coordinate: no reflected path or hard-clamp corners. */
    private fun coordinate(x: Double): Double = 1.0 / (1.0 + exp(-x))
    private fun motionCycles(motion: Double, seconds: Double, config: ContourConfig): Int =
        if (motion <= 0) 0 else ((.35 + 2.8 * motion) * seconds / config.motionSeconds).roundToInt().coerceIn(1, 16)

    /** Independent source-coordinate probe, before the filter and output gain. */
    internal fun scanCoordinates(voice: ContourVoice, macros: Map<String, Float>, timeSeconds: Double,
        config: ContourConfig = ContourConfig.forVoice(voice), held: Boolean = false,
        loopSeconds: Double = config.loopSeconds.toDouble()): Pair<Double, Double> =
        scanAt(settled(voice, macros), timeSeconds, config, held, loopSeconds)

    private fun scanAt(m: Map<String, Float>, t: Double, c: ContourConfig, held: Boolean,
        seconds: Double): Pair<Double, Double> {
        val scan = m.getValue("SCAN").toDouble()
        val offset = m.getValue("OFFSET").toDouble()
        val motion = m.getValue("MOTION").toDouble()
        val base = (scan - .5) * 5.5
        val depth = 1.0 - exp(-motion / .28)
        val speed = .65 + 2.2 * motion
        val duration = c.motionSeconds / speed
        fun wave(at: Double): Double {
            if (held) {
                val angle = TAU * motionCycles(motion, seconds, c) * at / seconds
                return sin(angle) * .82 + sin(2 * angle) * .18
            }
            // A lag approaches the beginning of a finite gesture smoothly;
            // hard-clamping negative time would make a slope corner there.
            val knee = duration * .04
            val elapsed = (at + sqrt(at * at + knee * knee)) * .5
            return when (c.motionShape) {
                ContourMotionShape.FALL -> exp(-elapsed / duration)
                ContourMotionShape.RISE -> 1.0 - 2.0 * exp(-elapsed / duration)
                ContourMotionShape.CYCLE -> sin(TAU * at / duration) * .82 + sin(2 * TAU * at / duration) * .18
            }
        }
        val bodyWave = wave(t)
        val delayed = wave(t - c.edgeLag * (if (held) seconds / max(1, motionCycles(motion, seconds, c)) else duration))
        val related = when (c.relation) {
            ContourRelation.LAG -> delayed
            ContourRelation.OPPOSED -> -delayed
            ContourRelation.CURVE -> .68 * delayed + .32 * (2 * delayed * delayed - .5)
        }
        // OFFSET displaces spectral position, and continuously increases the
        // edge's different response. It never changes either oscillator's pitch.
        val edgeWave = bodyWave + (.15 + .85 * offset) * (related - bodyWave)
        return coordinate(base + c.bodyScanBias * 3 + depth * c.bodyTravel * 4 * bodyWave) to
            coordinate(base + c.edgeScanBias * 3 + offset * 2.5 + depth * c.edgeTravel * 4 * edgeWave)
    }

    private fun duration(c: ContourConfig, hold: Double): Double =
        min(8.0, c.ampAttack + c.ampDecay + c.sustainSeconds * (1 + 1.6 * hold) + c.ampRelease * (1 + .6 * hold))

    private fun amplitude(t: Double, c: ContourConfig, hold: Double, held: Boolean): Double {
        if (held) return .58 + .32 * c.ampSustain
        val attack = c.ampAttack.toDouble()
        val decay = c.ampDecay.toDouble()
        val sustain = c.ampSustain.toDouble()
        val releaseStart = min(duration(c, hold) - c.ampRelease * (1 + .6 * hold),
            attack + decay + c.sustainSeconds * (1 + 1.6 * hold))
        return when {
            t < attack -> (t / attack).let { it * it * (3 - 2 * it) }
            t < attack + decay -> sustain + (1 - sustain) *
                (exp(-5 * (t - attack) / decay) - exp(-5.0)) / (1 - exp(-5.0))
            t < releaseStart -> sustain
            else -> {
                val x = ((t - releaseStart) / (duration(c, hold) - releaseStart)).coerceIn(0.0, 1.0)
                sustain * (1 - x).pow(3)
            }
        }
    }

    /** A bounded edge flare; neutral patches bypass its arithmetic exactly. */
    private fun edgeProjection(t: Double, c: ContourConfig, held: Boolean): Double {
        if (c.edgeSustain == 1f) return 1.0
        if (held) return c.edgeSustain.toDouble()
        return c.edgeSustain + (1 - c.edgeSustain) * (1 - exp(-t / c.edgeAttack)) * exp(-t / c.edgeDecay)
    }

    private data class LoopPlan(val frames: Int, val rootCycles: Int, val noteHz: Double,
        val actualHz: Double, val edgeRatio: Double)
    private fun planLoop(hz: Double, c: ContourConfig): LoopPlan {
        val k = (hz * c.loopSeconds).roundToInt().coerceAtLeast(16)
        val frames = (k * RATE / hz).roundToInt().coerceAtLeast(4096)
        // Only the sub-frame residual of a whole-number root-cycle plan is
        // corrected: this is ordinarily under .02 cents, never coarse retuning.
        return LoopPlan(frames, k, hz, k * RATE.toDouble() / frames,
            (k * c.edgeRatio).roundToInt().coerceAtLeast(1).toDouble() / k)
    }

    private class Engine(private val voice: ContourVoice, private val m: Map<String, Float>,
        private val velocity: Float, private val c: ContourConfig, private val held: Boolean,
        private val plan: LoopPlan?, private val diagnostic: ContourDiagnostic) {
        private val hz = plan?.actualHz ?: frequencyFor(voice, m.getValue("TUNE")).toDouble()
        private val ratio = plan?.edgeRatio ?: c.edgeRatio.toDouble()
        private val bodyLookup = ContourTables.lookup(c.bodyTable, hz)
        private val edgeLookup = ContourTables.lookup(c.edgeTable, hz * ratio)
        private val phaseSeed = Dsp.phases(2, Dsp.seedFor("CONTOUR", MODEL_VERSION, voice, c.bodyTable, c.edgeTable))
        private var bodyPhase = phaseSeed[0]
        // Related start phase avoids fragile unison cancellation while retaining
        // fresh deterministic state for every render.
        private var edgePhase = (bodyPhase + .035 + .025 * phaseSeed[1]) % 1.0
        private var anchorPhase = bodyPhase
        private val filter = Dsp.TptSvf(INTERNAL_RATE)
        private val hold = m.getValue("HOLD").toDouble()
        private val filterMacro = if (diagnostic == ContourDiagnostic.OPEN) 1.0 else m.getValue("FILTER").toDouble()
        private val drive = if (diagnostic == ContourDiagnostic.CLEAN) 0.0 else m.getValue("DRIVE").toDouble()
        private val driveGain = 1 + 10 * drive.pow(1.45)
        private val driveCompensation = 1 / sqrt(1 + 2.8 * drive)
        private val mixScale = .64 / max(1.0, c.bodyLevel + c.edgeLevel + c.subLevel.toDouble())
        private val minCutoff = (hz * 1.75).coerceIn(50.0, 1800.0)
        private val baseCutoff = minCutoff * (15500 / minCutoff).pow(filterMacro.pow(.85))
        private val damping = (1.8 - .65 * filterMacro).toFloat()
        private val effectiveMacros = if (diagnostic == ContourDiagnostic.FROZEN) m + ("MOTION" to 0f) else m
        private var sample = 0L
        private var bodyScan = 0.0
        private var edgeScan = 0.0
        private var amp = 0.0
        private var edgeGain = 1.0
        private var cutoff = baseCutoff
        private var db = 0.0
        private var de = 0.0
        private var da = 0.0
        private var dg = 0.0
        private var dc = 0.0

        private fun controls(t: Double): DoubleArray {
            val scans = scanAt(effectiveMacros, t, c, held, plan?.frames?.toDouble()?.div(RATE) ?: c.loopSeconds.toDouble())
            val env = if (held) 0.0 else (1 - exp(-t / c.filterAttack)) * exp(-t / c.filterDecay)
            val cut = (baseCutoff * 2.0.pow(env * c.filterAmount * (1 - filterMacro) * (1.0 + 1.7 * velocity))).coerceIn(35.0, 18000.0)
            // Velocity acts before the output level stage: brighter excitation
            // and a more pronounced filter gesture survive loudness matching.
            val emphasis = (velocity - .5) * .13
            return doubleArrayOf(scans.first + emphasis * 1.4 * scans.first * (1 - scans.first),
                scans.second + emphasis * 4 * scans.second * (1 - scans.second),
                amplitude(t, c, hold, held), cut, edgeProjection(t, c, held))
        }

        fun tick(): Float {
            if (sample % CONTROL_STRIDE == 0L) {
                val current = controls(sample.toDouble() / INTERNAL_RATE)
                val next = controls((sample + CONTROL_STRIDE).toDouble() / INTERNAL_RATE)
                bodyScan = current[0]; edgeScan = current[1]; amp = current[2]; cutoff = current[3]
                edgeGain = current[4]
                db = (next[0] - current[0]) / CONTROL_STRIDE
                de = (next[1] - current[1]) / CONTROL_STRIDE
                da = (next[2] - current[2]) / CONTROL_STRIDE
                dc = (next[3] - current[3]) / CONTROL_STRIDE
                dg = (next[4] - current[4]) / CONTROL_STRIDE
            }
            val body = if (diagnostic == ContourDiagnostic.EDGE_ONLY) 0.0 else {
                val wave = bodyLookup.sample(bodyPhase, bodyScan)
                (if (c.bodyHarmonics == 1f) wave else {
                    val root = bodyLookup.root(bodyPhase)
                    root + (wave - root) * c.bodyHarmonics
                }) * c.bodyLevel
            }
            val edge = if (diagnostic == ContourDiagnostic.BODY_ONLY) 0.0 else {
                val wave = edgeLookup.sample(edgePhase, edgeScan)
                val colored = if (c.edgeRootLevel == 1f) wave else
                    wave - (1 - c.edgeRootLevel) * edgeLookup.root(edgePhase)
                val projected = if (c.edgeSustain == 1f) colored else colored * edgeGain
                projected * c.edgeLevel
            }
            val anchor = if (diagnostic == ContourDiagnostic.EDGE_ONLY || c.subLevel == 0f) 0.0 else
                sin(TAU * anchorPhase) * c.subLevel
            val excitation = (body + edge + anchor) * mixScale * (.28 + .72 * velocity)
            val driven = if (drive <= 0) excitation else tanh(excitation * driveGain) * driveCompensation
            filter.process(driven.toFloat(), cutoff.toFloat(), damping)
            val filtered = if (c.filterType == ContourFilterType.LOW_PASS || diagnostic == ContourDiagnostic.OPEN)
                filter.low.toDouble() else filter.low * .42 + filter.band * .75
            val out = filtered * amp
            check(out.isFinite() && abs(out) < 8) { "CONTOUR unstable raw state at sample $sample: $out" }
            bodyPhase += hz / INTERNAL_RATE; if (bodyPhase >= 1) bodyPhase -= 1
            edgePhase += hz * ratio / INTERNAL_RATE; if (edgePhase >= 1) edgePhase -= 1
            anchorPhase += hz / INTERNAL_RATE; if (anchorPhase >= 1) anchorPhase -= 1
            bodyScan += db; edgeScan += de; amp += da; cutoff += dc; edgeGain += dg; sample++
            return out.toFloat()
        }

        fun state(): DoubleArray = doubleArrayOf(bodyPhase, edgePhase, anchorPhase,
            filter.low.toDouble(), filter.band.toDouble(), filter.high.toDouble())
    }

    // SynthScreen previews run in runInterruptible and keygroup exports pass a cancel
    // callback; either stops a long render instead of letting it finish unwanted.
    private fun stopIfUnwanted(cancelled: () -> Boolean = { false }) {
        if (Thread.currentThread().isInterrupted || cancelled()) throw java.util.concurrent.CancellationException("CONTOUR render no longer wanted")
    }

    /** Band limiting and DC conditioning only; deliberately no limiter or gain. */
    internal fun finish(raw: FloatArray, normalize: Boolean = false, fade: Boolean = false): FloatArray {
        stopIfUnwanted()
        val filtered = raw.copyOf()
        Tide.bandLimit(filtered, INTERNAL_RATE)
        val out = Dsp.decimate(filtered, RATE)
        val mean = out.sumOf { it.toDouble() } / out.size.coerceAtLeast(1)
        val hp = Dsp.OnePole(RATE)
        for (i in out.indices) { val x = out[i] - mean.toFloat(); out[i] = x - hp.lp(x, 8f) }
        if (normalize) Dsp.levelTo(out, RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        if (fade) Dsp.fadeTail(out)
        return out
    }

    internal data class LoopRender(val samples: FloatArray, val loopStart: Int,
        val noteHz: Double, val actualHz: Double, val motionCycles: Int, val edgeRatio: Double,
        val seamBeforeBlend: Double = 0.0, val periodicError: Double = 0.0,
        val stateError: Double = 0.0, val prerollCycles: Int = 3)

    internal fun renderLoopMeasured(voice: ContourVoice, macros: Map<String, Float> = emptyMap(),
        velocity: Float = 1f, config: ContourConfig = ContourConfig.forVoice(voice),
        normalize: Boolean = true, diagnostic: ContourDiagnostic = ContourDiagnostic.COMBINED,
        cancelled: () -> Boolean = { false }): LoopRender {
        require(velocity.isFinite() && velocity in 0f..1f) { "CONTOUR velocity must be finite in 0..1" }
        val m = settled(voice, macros) + ("HOLD" to 1f) +
            (if (diagnostic == ContourDiagnostic.FROZEN) mapOf("MOTION" to 0f) else emptyMap())
        val plan = planLoop(frequencyFor(voice, m.getValue("TUNE")).toDouble(), config)
        if (velocity == 0f) return LoopRender(FloatArray(plan.frames * 2), plan.frames,
            plan.noteHz, plan.actualHz, motionCycles(m.getValue("MOTION").toDouble(), plan.frames.toDouble() / RATE, config), plan.edgeRatio)
        val engine = Engine(voice, m, velocity, config, true, plan, diagnostic)
        // Three full periods settle oscillator/filter states. A trailing period
        // protects the chosen region from the resampler's boundary extension.
        val periodInternal = plan.frames * Dsp.OVERSAMPLE
        var precedingState = DoubleArray(0)
        var stateError = 0.0
        val raw = FloatArray(periodInternal * 6) { i ->
            if (i % 8192 == 0) stopIfUnwanted(cancelled)
            val x = engine.tick()
            if (i + 1 == periodInternal * 4) precedingState = engine.state()
            if (i + 1 == periodInternal * 5) {
                val state = engine.state()
                stateError = state.indices.maxOf { abs(state[it] - precedingState[it]) }
            }
            x
        }
        val complete = finish(raw)
        val out = complete.copyOfRange(plan.frames * 3, plan.frames * 5)
        var difference = 0.0
        var energy = 0.0
        for (i in 0 until plan.frames) {
            val d = out[i + plan.frames] - out[i].toDouble()
            difference += d * d
            energy += out[i].toDouble() * out[i]
        }
        val seamBeforeBlend = if (energy <= 1e-20) 0.0 else Keys.seamError(out, plan.frames)
        val periodicError = sqrt(difference / energy.coerceAtLeast(1e-30))
        check(seamBeforeBlend < 1e-3 && periodicError < 1e-3 && stateError < 1e-3) {
            "CONTOUR $voice held states did not settle: seam $seamBeforeBlend, cycle $periodicError, state $stateError"
        }
        val need = min(1280, plan.frames / 3)
        val fade = need - 256
        for (i in 0 until need) {
            val w = if (i >= fade) 1.0 else (i.toDouble() / fade).let { it * it * (3 - 2 * it) }
            val preceding = plan.frames - need + i
            val ending = out.size - need + i
            out[ending] = ((1 - w) * out[ending] + w * out[preceding]).toFloat()
        }
        if (normalize) Dsp.levelTo(out, RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        check(energy <= 1e-20 || Keys.seamError(out, plan.frames) < 1e-3) { "CONTOUR $voice loop seam failed" }
        return LoopRender(out, plan.frames, plan.noteHz, plan.actualHz,
            motionCycles(m.getValue("MOTION").toDouble(), plan.frames.toDouble() / RATE, config), plan.edgeRatio,
            seamBeforeBlend, periodicError, stateError)
    }

    fun renderLoop(voice: ContourVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f,
        config: ContourConfig = ContourConfig.forVoice(voice), cancelled: () -> Boolean = { false }): FloatArray =
        renderLoopMeasured(voice, macros, velocity, config, cancelled = cancelled).let { it.samples.copyOfRange(it.loopStart, it.samples.size) }

    internal fun renderInternalRaw(voice: ContourVoice, macros: Map<String, Float> = emptyMap(),
        velocity: Float = 1f, config: ContourConfig = ContourConfig.forVoice(voice),
        diagnostic: ContourDiagnostic = ContourDiagnostic.COMBINED): FloatArray {
        require(velocity.isFinite() && velocity in 0f..1f) { "CONTOUR velocity must be finite in 0..1" }
        val m = settled(voice, macros)
        val frames = (duration(config, m.getValue("HOLD").toDouble()) * INTERNAL_RATE).roundToInt()
        if (velocity == 0f) return FloatArray(frames)
        val engine = Engine(voice, m, velocity, config, false, null, diagnostic)
        return FloatArray(frames) { if (it % 8192 == 0) stopIfUnwanted(); engine.tick() }
    }

    fun renderDiagnosticRaw(voice: ContourVoice, diagnostic: ContourDiagnostic,
        macros: Map<String, Float> = emptyMap(), velocity: Float = 1f,
        config: ContourConfig = ContourConfig.forVoice(voice)): FloatArray {
        val m = settled(voice, macros)
        if (isLoop(m.getValue("HOLD"))) return renderLoopMeasured(voice, m, velocity, config,
            normalize = false, diagnostic = diagnostic).let { it.samples.copyOfRange(it.loopStart, it.samples.size) }
        return finish(renderInternalRaw(voice, m, velocity, config, diagnostic), fade = true)
    }

    fun renderRaw(voice: ContourVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f,
        config: ContourConfig = ContourConfig.forVoice(voice)): FloatArray =
        renderDiagnosticRaw(voice, ContourDiagnostic.COMBINED, macros, velocity, config)

    fun renderDiagnostic(voice: ContourVoice, diagnostic: ContourDiagnostic,
        macros: Map<String, Float> = emptyMap(), velocity: Float = 1f,
        config: ContourConfig = ContourConfig.forVoice(voice)): Snip {
        if (isLoop(settled(voice, macros).getValue("HOLD"))) {
            val loop = renderLoopMeasured(voice, macros, velocity, config, diagnostic = diagnostic)
            return Snip(loop.samples.copyOfRange(loop.loopStart, loop.samples.size), channels = 1, sampleRate = RATE)
        }
        val out = renderDiagnosticRaw(voice, diagnostic, macros, velocity, config)
        Dsp.levelTo(out, RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        return Snip(out, channels = 1, sampleRate = RATE)
    }

    fun render(voice: ContourVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f,
        config: ContourConfig = ContourConfig.forVoice(voice)): Snip =
        renderDiagnostic(voice, ContourDiagnostic.COMBINED, macros, velocity, config)
}
