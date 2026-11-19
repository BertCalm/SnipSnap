package com.snipsnap.synth

import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * Accord's accepted eight-mode, feedforward body. The mechanics supply canonical
 * contacts and geometry pressure; acoustic note, velocity and material controls
 * never alter those histories. Mode tables and the 5.96 TINE upper ratio follow
 * the owner-approved Python studies. Native filters and project noise deliberately
 * have their own versioned implementation, rather than claiming NumPy PCM parity.
 */
internal object AccordAcoustics {
    const val INTERNAL_RATE = Dsp.RATE * Dsp.OVERSAMPLE
    const val PRESSURE_RATE = 2000
    private const val REFERENCE_VELOCITY = .75
    private const val DECAY_LOG = 6.9078
    private val excitationDamping = floatArrayOf(1.9616f, 1.6629f, 1.1111f, .3902f)
    private const val MAX_ROUGHNESS_BYTES = 24L * 1024 * 1024
    private val roughnessLock = Any()
    private val roughnessPrefixes = LinkedHashMap<Long, DoubleArray>(3, .75f, true)
    private var roughnessBytes = 0L
    private var roughnessHits = 0L
    private var roughnessGenerations = 0L

    data class RoughnessCacheSnapshot(val entries: Int, val bytes: Long, val hits: Long, val generations: Long)

    fun roughnessCacheSnapshot(): RoughnessCacheSnapshot = synchronized(roughnessLock) {
        RoughnessCacheSnapshot(roughnessPrefixes.size, roughnessBytes, roughnessHits, roughnessGenerations)
    }

    fun clearRoughnessCache() = synchronized(roughnessLock) {
        roughnessPrefixes.clear()
        roughnessBytes = 0L; roughnessHits = 0L; roughnessGenerations = 0L
    }

    data class Mode(val ratio: Double, val gain: Double, val t60: Double)

    data class Config(
        val voice: AccordVoice,
        val profile: AccordAcousticProfile,
        val midi: Int,
        val rootHz: Double,
        val widthMs: Double,
        val tongue: List<Mode>,
        val body: List<Mode>,
        val bodyGain: Double,
        val brushCoefficient: Double,
        val pulseGain: Double,
        val ampGain: Double,
        val releaseSeconds: Double,
        /** Null for BRUSH, WOOD, TINE and BELL, which share the accepted single-bank path. */
        val candidate: Candidate? = null,
    )

    /** Which mechanical readout, unused by the single-bank voices, chooses the resonator. */
    enum class Split { MECHANISM, DIRECTION, PRESSURE }

    /** One resonator that only hears the contacts routed to it. Width and modes are final. */
    data class Route(val modes: List<Mode>, val widthMs: Double, val gain: Double)

    /**
     * A free reed: pressure × (a tone at the tongue's own frequency + roughness)
     * blows the tongue, the tongue's swing opens and closes the slot, and the
     * chopped flow sounds through a small fixed chamber. [drive] sets the swing
     * (about 1 at normal touch and average pressure); a wider swing is chopped
     * harder, so touch and the gathering breath both brighten it. [level] takes most
     * of touch's swing back out of the loudness, which the touch amplitude law
     * already sets as for every voice. [offset] is the tongue's rest position in
     * the slot, which gives the flow its even harmonics.
     */
    data class Reed(val drive: Double, val level: Double, val noise: Double, val offset: Double, val direct: Double,
        val chamber: List<Mode>, val chamberGain: Double, val breath: Double)

    /**
     * The routed layout of REED, COURSE and ESCAPEMENT, first heard as candidates in listening
     * round accord-voices-1 and kept there (19 Nov 2026). [shared] modes (a soundboard) hear
     * every route's force.
     */
    data class Candidate(val split: Split, val routes: List<Route>, val shared: List<Mode> = emptyList(),
        val reed: Reed? = null) {
        val modes: List<Mode> get() = routes.flatMap { it.modes } + shared + reed?.chamber.orEmpty()
    }

    data class Result(
        /** House-conditioned mono samples, including velocity, before static gain/fades. */
        val samples: FloatArray,
        val rawPeak: Double,
        val maximumPoleRadius: Double,
        val excitationPeak: Double,
        val eventCount: Int,
        val ampGain: Double,
        val releaseSeconds: Double,
    )

    fun releaseSeconds(profile: AccordAcousticProfile): Double = when (profile) {
        AccordAcousticProfile.BASE, AccordAcousticProfile.REFERENCE -> .9
        AccordAcousticProfile.SOFT_ATTACK, AccordAcousticProfile.BRIGHT_EDGE,
        AccordAcousticProfile.LONG_RING -> 2.2
    }

    fun renderFrames(sourceSeconds: Double, profile: AccordAcousticProfile): Int {
        require(sourceSeconds.isFinite() && sourceSeconds in Accord.MIN_SOURCE_SECONDS..Accord.MAX_SOURCE_SECONDS)
        return Math.round((sourceSeconds + releaseSeconds(profile)) * Dsp.RATE).toInt()
    }

    /** The resolved tail is part of the actual acoustic performance, including silence. */
    fun renderFrames(config: Config, sourceSeconds: Double): Int {
        require(sourceSeconds.isFinite() && sourceSeconds in Accord.MIN_SOURCE_SECONDS..Accord.MAX_SOURCE_SECONDS)
        require(config.releaseSeconds.isFinite() && config.releaseSeconds > 0.0)
        return Math.round((sourceSeconds + config.releaseSeconds) * Dsp.RATE).toInt()
    }

    /** Profile overrides precede note/velocity, CONTACT, then RAFT. Width is final. */
    fun configuration(
        voice: AccordVoice,
        profile: AccordAcousticProfile,
        midi: Int,
        velocity: Float,
        contact: Float = .5f,
        raft: Float = .5f,
    ): Config {
        require(midi in 36..72)
        require(velocity.isFinite() && velocity in 0f..1f)
        require(contact.isFinite() && contact in 0f..1f)
        require(raft.isFinite() && raft in 0f..1f)
        require(if (voice == AccordVoice.TINE) profile != AccordAcousticProfile.BASE
                else profile == AccordAcousticProfile.BASE) { "Accord voice/profile mismatch" }
        val tongue: List<Mode>
        val body: List<Mode>
        val baseWidth: Double
        val baseBody: Double
        val baseBrush: Double
        val basePulse: Double
        when (voice) {
            AccordVoice.BRUSH -> {
                tongue = listOf(Mode(1.0, 1.0, 1.05), Mode(2.0, .13, .52),
                    Mode(3.0, .025, .29), Mode(4.0, .009, .17))
                body = listOf(Mode(1.0, .26, 1.35), Mode(1.5, .06, .55),
                    Mode(2.25, .018, .31), Mode(3.25, .009, .19))
                baseWidth = 4.5; baseBody = .58; baseBrush = .0024; basePulse = .55
            }
            AccordVoice.WOOD -> {
                tongue = listOf(Mode(1.0, .85, .48), Mode(2.0, .50, .29),
                    Mode(3.0, .24, .17), Mode(4.04, .10, .10))
                body = listOf(Mode(1.0, .22, .72), Mode(1.51, .07, .22),
                    Mode(2.29, .035, .16), Mode(3.41, .018, .10))
                baseWidth = 2.1; baseBody = .50; baseBrush = 0.0; basePulse = 1.0
            }
            AccordVoice.TINE -> {
                val bright = profile == AccordAcousticProfile.BRIGHT_EDGE
                tongue = listOf(Mode(1.0, 1.0, .83), Mode(2.0, if (bright) .85 else .38, .40),
                    Mode(3.0, if (bright) .50 else .20, .24),
                    Mode(5.96, if (bright) 1.50 else .60, .26))
                body = listOf(Mode(1.0, .14, .62), Mode(1.5, .035, .28),
                    Mode(2.42, .025, .17), Mode(3.24, .012, .11))
                baseWidth = if (profile == AccordAcousticProfile.SOFT_ATTACK) 3.2 else .72
                baseBody = .32; baseBrush = 0.0; basePulse = 1.0
            }
            // Kept in listening round accord-voices-1. `tongue` is always the bank that
            // carries the root, so freeResponse(rootOnly) checks each pitch authority.
            AccordVoice.REED -> {
                // The tongue alone; the pressure readout blows it (renderCandidate).
                tongue = listOf(Mode(1.0, 1.0, .9))
                // A small fixed chamber: formants in Hz, not following the note.
                val rootHz = 440.0 * 2.0.pow((midi - 69) / 12.0)
                body = REED_CHAMBER.map { (hz, gain, t60) -> Mode(hz / rootHz, gain, t60) }
                baseWidth = 6.0; baseBody = .55; baseBrush = 0.0; basePulse = 1.0
            }
            AccordVoice.COURSE -> {
                // The centre string of three; the other two are detuned copies.
                tongue = courseModes(0.0)
                body = listOf(Mode(1.0, .14, .55), Mode(1.48, .05, .21),
                    Mode(2.31, .03, .14), Mode(3.6, .015, .09))
                baseWidth = 1.0; baseBody = .45; baseBrush = 0.0; basePulse = 1.0
            }
            AccordVoice.ESCAPEMENT -> {
                // The tock: a dark harmonic block in a hollow box (backward passages).
                tongue = listOf(Mode(1.0, 1.0, .42), Mode(2.0, .32, .20), Mode(3.0, .10, .12))
                body = listOf(Mode(1.0, .30, .50), Mode(1.47, .10, .18), Mode(2.2, .04, .10))
                baseWidth = 3.0; baseBody = .55; baseBrush = 0.0; basePulse = 1.0
            }
            AccordVoice.BELL -> {
                // Minor-third bell: prime, tierce, quint, nominal; hum and upper partials as body.
                tongue = listOf(Mode(1.0, 1.0, 3.2), Mode(1.2, .35, 1.6),
                    Mode(1.5, .2, 1.2), Mode(2.0, .55, 1.3))
                body = listOf(Mode(.5, .13, 2.4), Mode(2.51, .09, .8),
                    Mode(3.0, .16, .6), Mode(4.0, .08, .4))
                baseWidth = .5; baseBody = 1.0; baseBrush = 0.0; basePulse = 1.0
            }
        }
        val register = (midi - 48) / 12.0
        val v = velocity.toDouble()
        val delta = if (v <= REFERENCE_VELOCITY) (v - REFERENCE_VELOCITY) / REFERENCE_VELOCITY
                    else (v - REFERENCE_VELOCITY) / (1.0 - REFERENCE_VELOCITY)
        val contactOffset = 2.0 * contact - 1.0
        val raftOffset = 2.0 * raft - 1.0
        val profileDecay = if (profile == AccordAcousticProfile.LONG_RING) 3.0 else 1.0
        val decay = profileDecay * 2.0.pow(-.08 * register) * exp(.12 * delta)
        fun mapped(modes: List<Mode>, tilt: Double, bodyBank: Boolean): List<Mode> = modes.map { mode ->
            val upper = (log2(mode.ratio) / 2.0).coerceIn(0.0, 1.0)
            Mode(mode.ratio, mode.gain * exp((tilt * delta - .10 * max(register, 0.0)) * upper),
                mode.t60 * decay * if (bodyBank) 2.0.pow(.25 * raftOffset) else 1.0)
        }
        val amp = when {
            v == 0.0 -> 0.0
            v <= REFERENCE_VELOCITY -> (v / REFERENCE_VELOCITY).pow(.9)
            else -> {
                val t = (v - REFERENCE_VELOCITY) / (1.0 - REFERENCE_VELOCITY)
                1.0 + .3 * t - .1 * t * t * t
            }
        }
        val resolvedTongue = mapped(tongue, .42, false)
        val resolvedBody = mapped(body, .20, true)
        val candidate = candidate(voice, register, delta, contactOffset, raftOffset, resolvedTongue, resolvedBody,
            baseWidth, baseBody) { modes -> mapped(modes, .42, false) }
        // The accepted captured tails are minima, rather than a reason to cut
        // a material mode while it still rings. After 1.1 t60 every unforced
        // mode has lost at least66dB; the final house fade is then only an edge.
        val longestT60 = max((resolvedTongue + resolvedBody).maxOf { it.t60 },
            candidate?.modes?.maxOf { it.t60 } ?: 0.0)
        val release = max(releaseSeconds(profile), 1.1 * longestT60)
        return Config(voice, profile, midi, 440.0 * 2.0.pow((midi - 69) / 12.0),
            baseWidth * min(1.0, 2.0.pow(-register)) * exp(-ln(1.5) * delta) * 2.0.pow(-contactOffset),
            resolvedTongue, resolvedBody,
            baseBody * exp(.10 * delta) * 2.0.pow(.5 * raftOffset),
            baseBrush * 10.0.pow(brushRegisterDb(register) / 20.0) * exp(-.18 * delta - .15 * contactOffset),
            basePulse * exp(.13 * delta + .15 * contactOffset), amp, release, candidate)
    }

    /** REED chamber formants: Hz, peak gain, t60 seconds. */
    private val REED_CHAMBER = listOf(Triple(680.0, 1.6, .020), Triple(1650.0, 1.1, .015), Triple(2900.0, .5, .010))
    private const val COURSE_STIFFNESS = 1.2e-4
    private const val COURSE_STRIKE = 1 / 7.3
    /**
     * Three strings per course. Spec section 10 warns against independently detuned banks;
     * the owner approved these three by keeping COURSE (accord-voices-1, 19 Nov 2026).
     */
    private val COURSE_CENTS = listOf(-2.5, 0.0, 2.5)

    /** A stiff string struck near one end: n*sqrt(1+Bn^2) partials, a strike-position comb, faster upper decay. */
    private fun courseModes(cents: Double): List<Mode> {
        val detune = 2.0.pow(cents / 1200.0)
        val stretch = sqrt(1.0 + COURSE_STIFFNESS)
        val strike = sin(PI * COURSE_STRIKE)
        return (1..8).map { n ->
            Mode(n * sqrt(1.0 + COURSE_STIFFNESS * n * n) / stretch * detune,
                abs(sin(PI * n * COURSE_STRIKE)) / strike / n.toDouble().pow(1.2), 2.6 / (1.0 + .3 * (n - 1)))
        }
    }

    /**
     * A tuned bar (1, 4, 9.9, as marimba and vibraphone bars are cut), not the raw
     * free-free 1 : 2.756 : 5.404: those partials pulled the measured pitch of C3 by
     * more than a semitone. Low notes taper the upper partials.
     */
    private fun tickModes(register: Double): List<Mode> {
        val t = ((register + 1.0) / 1.5).coerceIn(0.0, 1.0)
        val taper = .35 + .65 * t * t * (3.0 - 2.0 * t)
        return listOf(Mode(1.0, .6, .8), Mode(4.0, .8 * taper, .4),
            Mode(9.9, .45 * taper, .15))
    }

    /**
     * The routed layouts use the same touch, CONTACT and RAFT laws as the
     * single-bank voices ([mapped], width, body gain), so a soft/hard or RAFT move
     * means the same thing on every voice. BELL is a table only and returns null.
     */
    private fun candidate(voice: AccordVoice, register: Double, delta: Double, contactOffset: Double,
        raftOffset: Double, tongue: List<Mode>, body: List<Mode>, baseWidth: Double, baseBody: Double,
        mapped: (List<Mode>) -> List<Mode>): Candidate? {
        val widthScale = min(1.0, 2.0.pow(-register)) * exp(-ln(1.5) * delta) * 2.0.pow(-contactOffset)
        val bodyGain = baseBody * exp(.10 * delta) * 2.0.pow(.5 * raftOffset)
        fun sent(modes: List<Mode>) = modes.map { it.copy(gain = it.gain * bodyGain) }
        return when (voice) {
            AccordVoice.BRUSH, AccordVoice.WOOD, AccordVoice.TINE, AccordVoice.BELL -> null
            // Mechanism i strikes string i mod 3; the soundboard hears every strike.
            AccordVoice.COURSE -> Candidate(Split.MECHANISM,
                COURSE_CENTS.map { cents -> Route(mapped(courseModes(cents)), baseWidth * widthScale, 1.0) },
                shared = sent(body))
            // Forward passages strike a bright bar (tick), backward ones a dark block (tock).
            AccordVoice.ESCAPEMENT -> Candidate(Split.DIRECTION, listOf(
                Route(mapped(tickModes(register)), .3 * widthScale, 1.0),
                Route(tongue + sent(body), baseWidth * widthScale, 1.0)))
            AccordVoice.REED -> Candidate(Split.PRESSURE, listOf(Route(tongue, baseWidth * widthScale, 1.0)), reed =
                Reed(drive = 14.0 * 2.0.pow(-.1 * register) * exp(1.2 * delta), level = exp(-.9 * delta),
                    noise = 12.0, offset = .75, direct = .5, chamber = body, chamberGain = bodyGain,
                    breath = .12 * exp(-.18 * delta - .15 * contactOffset)))
        }
    }

    private fun brushRegisterDb(register: Double): Double {
        fun smooth(x: Double): Double {
            val t = x.coerceIn(0.0, 1.0)
            return t * t * (3.0 - 2.0 * t)
        }
        return when {
            register <= 0.0 -> 6.662590280187672 * smooth(-register)
            register <= 1.0 -> -.495396182549136 * smooth(register)
            else -> -.495396182549136 + (-.75 + .495396182549136) * smooth(register - 1.0)
        }
    }

    /**
     * pressure is the ungated geometry readout at [PRESSURE_RATE], starting
     * at the selected source start. Canonical speed is never scaled by PACE.
     * The same project-noise prefix is used at every note/velocity/contact.
     */
    fun renderConditioned(
        config: Config,
        events: List<AccordDynamics.Event>,
        pressure: DoubleArray,
        sourceSeconds: Double,
        seed: Long,
        sourceStartCycles: Double,
        paceHz: Double,
    ): Result {
        require(sourceStartCycles.isFinite() && sourceStartCycles >= 0.0)
        require(paceHz.isFinite() && paceHz > 0.0)
        require(pressure.all { it.isFinite() && it >= 0.0 && it <= 2.0000001 })
        val frames = renderFrames(config, sourceSeconds)
        if (config.ampGain == 0.0) return Result(FloatArray(frames), 0.0, maximumPole(config), 0.0, 0, 0.0, config.releaseSeconds)
        config.candidate?.let {
            return renderCandidate(config, it, events, pressure, sourceSeconds, seed, sourceStartCycles, paceHz)
        }
        val forceBuffer = DoubleArray(frames * Dsp.OVERSAMPLE)
        val positive = kernel(config.widthMs)
        val negative = kernel(config.widthMs * 1.12)
        val endCycle = sourceStartCycles + sourceSeconds * paceHz
        var eventCount = 0
        for (event in events) {
            if (event.cycle < sourceStartCycles || event.cycle >= endCycle) continue
            require(event.canonicalSpeed.isFinite() && event.canonicalSpeed >= 0.0)
            require(event.direction == -1 || event.direction == 1)
            val position = (event.cycle - sourceStartCycles) * INTERNAL_RATE / paceHz
            val index = floor(position).toInt()
            val fraction = position - index
            val pulse = if (event.direction > 0) positive else negative
            val amplitude = sqrt(min(event.canonicalSpeed / .8, 2.0)) / 15.0
            for (j in pulse.indices) {
                val at = index + j
                val force = pulse[j] * amplitude
                if (at < forceBuffer.size) forceBuffer[at] += force * (1.0 - fraction)
                if (at + 1 < forceBuffer.size) forceBuffer[at + 1] += force * fraction
            }
            eventCount++
        }
        val roughness = if (config.brushCoefficient > 0.0)
            roughnessPrefix(seed, kotlin.math.ceil(sourceSeconds * INTERNAL_RATE).toInt()) else null
        var excitationPeak = 0.0
        val gateSeconds = min(.060, sourceSeconds)
        for (i in forceBuffer.indices) {
            if (i and 16383 == 0 && Thread.currentThread().isInterrupted) throw CancellationException("Accord acoustics cancelled")
            val time = i.toDouble() / INTERNAL_RATE
            val gate = when {
                time >= sourceSeconds -> 0.0
                time <= sourceSeconds - gateSeconds -> 1.0
                else -> .5 + .5 * cos(PI * (time - sourceSeconds + gateSeconds) / gateSeconds)
            }
            var force = forceBuffer[i] * config.pulseGain
            // Consume roughness on every audio tick, even between contacts.
            // Branching draws on pressure would resequence future excitation.
            if (roughness != null) {
                val noise = if (i < roughness.size) roughness[i] else 0.0
                val controlPosition = time * PRESSURE_RATE
                val at = floor(controlPosition).toInt()
                val p = if (at in pressure.indices && controlPosition <= pressure.lastIndex) {
                    val fraction = controlPosition - at
                    pressure[at] * (1.0 - fraction) + (pressure.getOrElse(at + 1) { 0.0 }) * fraction
                } else 0.0
                force += config.brushCoefficient * p * noise
            }
            force *= gate
            require(force.isFinite()) { "Accord nonfinite acoustic force" }
            excitationPeak = max(excitationPeak, abs(force))
            forceBuffer[i] = force
        }
        // This is a force-bandwidth filter, separate from the house anti-alias
        // filter below. Sharing the linear brush/contact bank preserves modes.
        filterExcitation(forceBuffer)
        val bank = Bank.of(config, false)
        val raw = FloatArray(forceBuffer.size)
        var rawPeak = 0.0
        for (i in raw.indices) {
            if (i and 16383 == 0 && Thread.currentThread().isInterrupted) throw CancellationException("Accord acoustics cancelled")
            val sample = bank.step(forceBuffer[i])
            require(sample.isFinite()) { "Accord nonfinite resonator state" }
            rawPeak = max(rawPeak, abs(sample))
            raw[i] = sample.toFloat()
        }
        return Result(conditioned(raw, config), rawPeak, bank.maximumPoleRadius, excitationPeak, eventCount,
            config.ampGain, config.releaseSeconds)
    }

    /** House output: anti-alias, decimate, remove the mean, 20 Hz high-pass, then touch amplitude. */
    private fun conditioned(raw: FloatArray, config: Config): FloatArray {
        Tide.bandLimit(raw, INTERNAL_RATE)
        val samples = Dsp.decimate(raw, Dsp.RATE)
        var mean = 0.0
        for (sample in samples) mean += sample
        mean /= samples.size.coerceAtLeast(1)
        val highPass = Dsp.FixedOnePole(20f, Dsp.RATE)
        for (i in samples.indices) {
            val centered = (samples[i] - mean).toFloat()
            samples[i] = ((centered - highPass.lp(centered)) * config.ampGain).toFloat()
            require(samples[i].isFinite()) { "Accord nonfinite conditioned output" }
        }
        return samples
    }

    private fun radius(t60: Double): Double = exp(-DECAY_LOG / (t60 * INTERNAL_RATE))

    /**
     * REED's slot. The flow keeps the tongue's swing (its current amplitude), so
     * loudness follows the breath, while its shape hardens with the swing: nearly
     * a sine for a small swing, a buzzing asymmetric pulse for a wide one. The
     * swing moves slowly, so its hardness terms are refreshed every 16 samples.
     */
    private class Slot(private val offset: Double) {
        private var swing = 0.0
        private var hardness = 0.0
        private var rest = 0.0
        private var scale = 0.0
        fun update(swing: Double) {
            this.swing = swing
            hardness = .3 + 2.5 * swing
            rest = tanh(hardness * offset)
            scale = swing / tanh(hardness)
        }
        fun flow(x: Double): Double = if (swing < 1e-12) x else scale * (tanh(hardness * (x / swing + offset)) - rest)
    }

    /**
     * REED, COURSE and ESCAPEMENT only; BRUSH, WOOD, TINE and BELL never reach this path.
     * Contacts are placed exactly as in [renderConditioned], then routed by the
     * readout the single-bank voices discard: MECHANISM (COURSE: which string),
     * DIRECTION (ESCAPEMENT: tick or tock) or PRESSURE (REED: the raft blows a
     * tongue). Each route has its own kernel, force-bandwidth filter and bank.
     */
    private fun renderCandidate(config: Config, candidate: Candidate, events: List<AccordDynamics.Event>,
        pressure: DoubleArray, sourceSeconds: Double, seed: Long, sourceStartCycles: Double, paceHz: Double): Result {
        val frames = renderFrames(config, sourceSeconds)
        val routes = candidate.routes
        val forces = Array(routes.size) { DoubleArray(frames * Dsp.OVERSAMPLE) }
        val kernels = routes.map { kernel(it.widthMs) }
        val endCycle = sourceStartCycles + sourceSeconds * paceHz
        var eventCount = 0
        for (event in events) {
            if (event.cycle < sourceStartCycles || event.cycle >= endCycle) continue
            require(event.canonicalSpeed.isFinite() && event.canonicalSpeed >= 0.0)
            require(event.direction == -1 || event.direction == 1)
            val route = when (candidate.split) {
                Split.MECHANISM -> event.mechanism % routes.size
                Split.DIRECTION -> if (event.direction > 0) 0 else 1
                Split.PRESSURE -> 0
            }
            val buffer = forces[route]
            val pulse = kernels[route]
            val position = (event.cycle - sourceStartCycles) * INTERNAL_RATE / paceHz
            val index = floor(position).toInt()
            val fraction = position - index
            val amplitude = sqrt(min(event.canonicalSpeed / .8, 2.0)) / 15.0 * routes[route].gain * config.pulseGain
            for (j in pulse.indices) {
                val at = index + j
                val force = pulse[j] * amplitude
                if (at < buffer.size) buffer[at] += force * (1.0 - fraction)
                if (at + 1 < buffer.size) buffer[at + 1] += force * fraction
            }
            eventCount++
        }
        val reed = candidate.reed
        val roughness = reed?.let { roughnessPrefix(seed, kotlin.math.ceil(sourceSeconds * INTERNAL_RATE).toInt()) }
        val breath = if (reed != null) DoubleArray(forces[0].size) else null
        // Steady drive at the tongue's own frequency gives a swing of `drive * pressure`.
        val tongueNorm = 1.0 - radius(config.tongue.first().t60).let { it * it }
        val tongueOmega = 2.0 * PI * config.rootHz / INTERNAL_RATE
        var excitationPeak = 0.0
        val gateSeconds = min(.060, sourceSeconds)
        for (i in forces[0].indices) {
            if (i and 16383 == 0 && Thread.currentThread().isInterrupted) throw CancellationException("Accord acoustics cancelled")
            val time = i.toDouble() / INTERNAL_RATE
            val gate = when {
                time >= sourceSeconds -> 0.0
                time <= sourceSeconds - gateSeconds -> 1.0
                else -> .5 + .5 * cos(PI * (time - sourceSeconds + gateSeconds) / gateSeconds)
            }
            if (reed != null && roughness != null && breath != null) {
                val noise = if (i < roughness.size) roughness[i] else 0.0
                val controlPosition = time * PRESSURE_RATE
                val at = floor(controlPosition).toInt()
                val p = if (at in pressure.indices && controlPosition <= pressure.lastIndex) {
                    val fraction = controlPosition - at
                    pressure[at] * (1.0 - fraction) + (pressure.getOrElse(at + 1) { 0.0 }) * fraction
                } else 0.0
                forces[0][i] += reed.drive * tongueNorm * p * (sin(tongueOmega * i) + reed.noise * noise)
                breath[i] = reed.breath * p * noise * gate
            }
            for (buffer in forces) {
                val force = buffer[i] * gate
                require(force.isFinite()) { "Accord nonfinite acoustic force" }
                excitationPeak = max(excitationPeak, abs(force))
                buffer[i] = force
            }
        }
        for (buffer in forces) filterExcitation(buffer)
        val banks = routes.map { Bank(config.rootHz, it.modes) }
        val shared = if (candidate.shared.isEmpty()) null else Bank(config.rootHz, candidate.shared)
        // Chamber formants are scaled to their peak gain, so they colour the flow rather than ring.
        val chamber = reed?.let { r -> Bank(config.rootHz, r.chamber.map {
            it.copy(gain = it.gain * r.chamberGain * (1.0 - radius(it.t60).let { x -> x * x }))
        }) }
        // The chopped flow carries an envelope-rate offset; two 30 Hz poles keep it out of the output.
        val slot = reed?.let { Slot(it.offset) }
        val flowPole = 1.0 - exp(-2.0 * PI * 30.0 / INTERNAL_RATE)
        var flowLow = 0.0
        var flowLower = 0.0
        val raw = FloatArray(forces[0].size)
        var rawPeak = 0.0
        for (i in raw.indices) {
            if (i and 16383 == 0 && Thread.currentThread().isInterrupted) throw CancellationException("Accord acoustics cancelled")
            var sample = 0.0
            var all = 0.0
            for (r in banks.indices) {
                sample += banks[r].step(forces[r][i])
                all += forces[r][i]
            }
            if (shared != null) sample += shared.step(all)
            if (reed != null && chamber != null && breath != null && slot != null) {
                // The tongue's swing against the slot: nearly linear when small, a buzzing chop when large.
                if (i and 15 == 0) slot.update(banks[0].swing())
                val flow = slot.flow(sample) * reed.level
                flowLow += flowPole * (flow - flowLow)
                val once = flow - flowLow
                flowLower += flowPole * (once - flowLower)
                val blocked = once - flowLower
                sample = reed.direct * blocked + chamber.step(blocked) + breath[i]
            }
            require(sample.isFinite()) { "Accord nonfinite resonator state" }
            rawPeak = max(rawPeak, abs(sample))
            raw[i] = sample.toFloat()
        }
        val maximumPole = (banks + listOfNotNull(shared, chamber)).maxOf { it.maximumPoleRadius }
        return Result(conditioned(raw, config), rawPeak, maximumPole, excitationPeak, eventCount,
            config.ampGain, config.releaseSeconds)
    }

    private fun kernel(widthMs: Double): DoubleArray {
        val frames = max(4, Math.rint(widthMs * .001 * INTERNAL_RATE).toInt())
        val kernel = DoubleArray(frames) { .5 - .5 * cos(2.0 * PI * it / (frames - 1)) }
        var sum = 0.0
        for (value in kernel) sum += value
        for (i in kernel.indices) kernel[i] /= sum
        return kernel
    }

    /**
     * Native sparse-force profiling found Float SVF subnormals dominated WOOD
     * and TINE rerenders. This engine-local Double TPT law retains the same
     * Butterworth sections without changing the shared Dsp filter. A state
     * floor only removes input AND both integrators below 1e-100 (−2000 dB),
     * before Double subnormals become a CPU cost; modal ringing is untouched.
     * The false branch exists solely for independent numeric/performance probes.
     */
    fun filterExcitation(input: DoubleArray, tinyStateFloor: Boolean = true) {
        val g = tan(PI * 10_000.0 / INTERNAL_RATE)
        for (damping in excitationDamping) {
            val a1 = 1.0 / (1.0 + g * (g + damping.toDouble()))
            val a2 = g * a1
            val a3 = g * a2
            var ic1 = 0.0
            var ic2 = 0.0
            for (i in input.indices) {
                val x = input[i]
                if (tinyStateFloor && abs(x) < 1e-100 && abs(ic1) < 1e-100 && abs(ic2) < 1e-100) {
                    ic1 = 0.0; ic2 = 0.0; input[i] = 0.0
                } else {
                    val v3 = x - ic2
                    val v1 = a1 * ic1 + a2 * v3
                    val v2 = ic2 + a2 * ic1 + a3 * v3
                    ic1 = 2.0 * v1 - ic1
                    ic2 = 2.0 * v2 - ic2
                    input[i] = v2
                }
            }
        }
    }

    /** Cache owns its arrays; no render result or public callback exposes one. */
    private fun roughnessPrefix(seed: Long, frames: Int): DoubleArray {
        require(frames in 1..8 * INTERNAL_RATE)
        synchronized(roughnessLock) {
            roughnessPrefixes[seed]?.takeIf { it.size >= frames }?.let {
                roughnessHits++
                return it
            }
        }
        val source = Roughness(seed)
        val generated = DoubleArray(frames) { i ->
            if (i and 16383 == 0 && Thread.currentThread().isInterrupted) throw CancellationException("Accord roughness cancelled")
            source.next()
        }
        synchronized(roughnessLock) {
            roughnessGenerations++
            roughnessPrefixes[seed]?.takeIf { it.size >= frames }?.let {
                roughnessHits++
                return it
            }
            roughnessPrefixes.remove(seed)?.let { roughnessBytes -= it.size.toLong() * 8 }
            val bytes = generated.size.toLong() * 8
            while (roughnessPrefixes.isNotEmpty() &&
                (roughnessPrefixes.size >= 2 || roughnessBytes + bytes > MAX_ROUGHNESS_BYTES)) {
                roughnessBytes -= roughnessPrefixes.remove(roughnessPrefixes.keys.first())!!.size.toLong() * 8
            }
            roughnessPrefixes[seed] = generated
            roughnessBytes += bytes
            return generated
        }
    }

    /** One scalar from the declared active interval, never from an edited velocity. */
    fun calibrationGain(conditioned: FloatArray, activeSeconds: Double = 8.0): Double {
        val frames = min(conditioned.size, Math.round(activeSeconds * Dsp.RATE).toInt())
        require(frames > 0)
        val active = conditioned.copyOf(frames)
        var index = 0
        for (i in active.indices) if (abs(active[i]) > abs(active[index])) index = i
        require(active[index].isFinite() && abs(active[index]) > 1e-9f) { "Accord quiet calibration fixture" }
        val original = active[index].toDouble()
        Dsp.levelTo(active, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET, ceiling = .94f)
        val gain = active[index] / original
        require(gain.isFinite() && gain > 0.0)
        // Dsp.levelTo is scalar; verify that assumption rather than silently
        // deriving a usable-looking gain from an envelope processor.
        for (i in active.indices) {
            val expected = conditioned[i] * gain
            require(abs(active[i] - expected) <= 2e-6 * max(1.0, abs(expected))) {
                "Accord reference leveling is not scalar"
            }
        }
        return gain
    }

    /** Independent free-pole/pitch probe, before filters, gain and noise. */
    fun freeResponse(config: Config, seconds: Double = .5, rootOnly: Boolean = false): DoubleArray {
        require(seconds.isFinite() && seconds > 0.0 && seconds <= 4.0)
        val bank = Bank.of(config, rootOnly)
        return DoubleArray(Math.round(seconds * INTERNAL_RATE).toInt()) { i -> bank.step(if (i == 0) 1.0 else 0.0) }
    }

    private fun maximumPole(config: Config): Double = (config.tongue + config.body + config.candidate?.modes.orEmpty())
        .maxOf { exp(-DECAY_LOG / (it.t60 * INTERNAL_RATE)) }

    /** Double recurrence avoids low-note pole quantization at the 4x clock. */
    private class Bank(rootHz: Double, private val modes: List<Mode>) {
        companion object {
            fun of(config: Config, rootOnly: Boolean) = Bank(config.rootHz,
                if (rootOnly) listOf(config.tongue.first()) else config.tongue +
                    config.body.map { it.copy(gain = it.gain * config.bodyGain) })
        }
        private val a1 = DoubleArray(modes.size)
        private val a2 = DoubleArray(modes.size)
        private val gain = DoubleArray(modes.size)
        private val y1 = DoubleArray(modes.size)
        private val y2 = DoubleArray(modes.size)
        private val cosine = DoubleArray(modes.size)
        private val sine = DoubleArray(modes.size)
        var maximumPoleRadius = 0.0
            private set
        init {
            for (j in modes.indices) {
                val mode = modes[j]
                require(mode.t60.isFinite() && mode.t60 > 0.0)
                val hz = rootHz * mode.ratio
                val radius = exp(-DECAY_LOG / (mode.t60 * INTERNAL_RATE))
                val angle = 2.0 * PI * hz / INTERNAL_RATE
                maximumPoleRadius = max(maximumPoleRadius, radius)
                cosine[j] = cos(angle); sine[j] = sin(angle)
                a1[j] = 2.0 * radius * cos(angle)
                a2[j] = -radius * radius
                // The kept C2..C5 tables stay below 3.2 kHz (the routed voices reach 5.2 kHz). Keep a
                // frequency guard here so future modes cannot fold on export.
                gain[j] = if (hz > 0.0 && hz < Dsp.RATE * .45) mode.gain * sin(angle) else 0.0
            }
        }
        /** The first mode's current amplitude, from its last two outputs (exact for a steady sinusoid). */
        fun swing(): Double = sqrt(max(0.0, y1[0] * y1[0] + y2[0] * y2[0] - 2.0 * y1[0] * y2[0] * cosine[0])) / sine[0]

        fun step(input: Double): Double {
            var sum = 0.0
            for (j in modes.indices) {
                val y = gain[j] * input + a1[j] * y1[j] + a2[j] * y2[j]
                y2[j] = y1[j]; y1[j] = y
                sum += y
            }
            return sum
        }
    }

    /**
     * ROUGHNESS_V1: paired Box-Muller, Dsp.Noise open-unit endpoints, cos then
     * sin, tanh, and a Double two-section Butterworth 80/2600 Hz bandpass.
     * Coefficients are the bilinear Butterworth law at the fixed 176.4 kHz
     * internal rate. This is explicitly different from NumPy's draw sequence.
     */
    private class Roughness(seed: Long) {
        private val noise = Dsp.Noise(Dsp.seedFor("ACCORD", "ROUGHNESS_V1", seed))
        private var spare = 0.0
        private var hasSpare = false
        private val lowSection = Section(.0018928740100842294, .0037857480201684587,
            .0018928740100842294, -1.8767182684543435, .88432924290529202)
        private val highSection = Section(1.0, -2.0, 1.0, -1.9959859777720299, .99599457603048114)
        fun next(): Double {
            val gaussian = if (hasSpare) {
                hasSpare = false
                spare
            } else {
                val floor = 1.0 / 16777216.0
                val u1 = ((noise.next().toDouble() + 1.0) * .5).coerceIn(floor, 1.0 - floor)
                val u2 = ((noise.next().toDouble() + 1.0) * .5).coerceIn(floor, 1.0 - floor)
                val radius = sqrt(-2.0 * ln(u1))
                val angle = 2.0 * PI * u2
                spare = radius * sin(angle)
                hasSpare = true
                radius * cos(angle)
            }
            return highSection.step(lowSection.step(tanh(gaussian)))
        }
    }

    private class Section(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        private var z1 = 0.0
        private var z2 = 0.0
        fun step(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }
}
