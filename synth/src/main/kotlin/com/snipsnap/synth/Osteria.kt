package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.*
import kotlin.random.Random

/** The original quartet and five fixed-root source families. */
enum class OsteriaVoice { QUARTET, BASS, PLUCK, REED, PAD, BOWED }

/** The four members are always simulated together, in this order. */
enum class OsteriaRole { BASS, HARMONY, MELODY, RHYTHM }

/** Saved, deterministic finite-render recipe. Shores are continuous positions from 0 to 4. */
data class OsteriaConfig(
    val initialShores: List<Double> = listOf(.35, 1.25, 2.6, 3.55),
    val roleLevels: List<Double> = listOf(.90, .62, .42, .32),
    val seed: Int = 173,
    val seconds: Double = 6.0,
    val tavernShore: Double = 1.0,
    val direction: Int = 1,
    val model: Int = 2,
    val attackSeconds: Double = .035,
    val releaseSeconds: Double = .8,
) {
    init {
        require(initialShores.size == 4 && initialShores.all { it.isFinite() && it in 0.0..4.0 }) {
            "OSTERIA needs four finite initial shores in 0..4"
        }
        require(roleLevels.size == 4 && roleLevels.all { it.isFinite() && it in 0.0..1.0 }) {
            "OSTERIA needs four finite role levels in 0..1"
        }
        require(model == 1 || model == 2) { "unsupported OSTERIA model: $model" }
        val minimumSeconds = if (model == 1) 5.0 else 1.0
        require(seconds.isFinite() && seconds in minimumSeconds..8.0) {
            "OSTERIA model $model duration must be in $minimumSeconds..8 seconds"
        }
        require(attackSeconds.isFinite() && attackSeconds in .005..1.0) {
            "OSTERIA attack must be in .005..1 seconds"
        }
        require(releaseSeconds.isFinite() && releaseSeconds in .02..2.0) {
            "OSTERIA release must be in .02..2 seconds"
        }
        require(seconds >= releaseSeconds + .05) { "OSTERIA duration must leave at least .05 seconds before release" }
        require(model != 1 || (attackSeconds == .035 && releaseSeconds == .8)) {
            "OSTERIA model 1 retains its original articulation"
        }
        require(tavernShore.isFinite() && tavernShore in 0.0..4.0) { "OSTERIA tavern shore must be in 0..4" }
        require(direction == -1 || direction == 1) { "OSTERIA direction must be -1 or 1" }
    }

    internal fun snapshot(): OsteriaConfig = copy(initialShores = initialShores.toList(), roleLevels = roleLevels.toList())
}

/**
 * A finite evolving note with four spectral contributors. Model 2 shares one root phase
 * and one attack, decay, sustain and release across the ensemble. Travelling shores change
 * their spectra, damping and air without triggering another note or changing the pitch.
 * Explicit model 1 recipes retain the original scheduled quartet phrase bit for bit.
 *
 * Continuous shore interpolation changes source spectra, breath, excitation and damping.
 * A damped spring pulls each traveller toward its own target and toward the ensemble
 * centroid. A bounded, one-sample-delayed sympathetic path lets sounding members excite
 * one another. History stores seconds of actual travel, separately from the sound of the
 * fixed tavern. Everything, including oscillator, history and room state, starts fresh.
 *
 * HOLD extends this first candidate to eight seconds. It does not promise a loop.
 */
object Osteria {
    const val DEFAULT_MIDI = 48
    const val MIN_MIDI = 36
    const val MAX_MIDI = 84
    private const val INTERNAL_RATE = Dsp.RATE * Dsp.OVERSAMPLE
    private const val TABLE_SIZE = 2048
    private const val CONTROL_SECONDS = .005
    private const val OUTPUT_GAIN = .22
    private const val TABLE_CACHE_BANKS = 8

    private data class TableKey(val voice: OsteriaVoice, val midi: Int, val seed: Int, val model: Int)
    private class TableBank(val tables: Array<Array<Array<FloatArray>>>)

    // Only immutable source spectra are shared. The complete eight-bank cache holds
    // 2,622,720 bytes of Float payload; note motion, history and filters remain local.
    private val tableBanks = object : LinkedHashMap<TableKey, TableBank>(TABLE_CACHE_BANKS, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TableKey, TableBank>?): Boolean =
            size > TABLE_CACHE_BANKS
    }

    private fun sourceTables(voice: OsteriaVoice, midi: Int, seed: Int, model: Int): Array<Array<Array<FloatArray>>> =
        synchronized(tableBanks) {
            // Access-order lookup and construction use one lock. No bank is published
            // until all tables are finished, and rendering never writes into them.
            tableBanks.getOrPut(TableKey(voice, midi, seed, model)) {
                val rootHz = Keys.midiHz(midi).toDouble()
                TableBank(Array(4) { role ->
                    Array(5) { coast ->
                        Array(2) { excitation ->
                            if (voice == OsteriaVoice.QUARTET) table(role, coast, excitation, rootHz, seed, model)
                            else familyTable(voice, role, coast, excitation, rootHz, seed)
                        }
                    }
                })
            }.tables
        }

    fun macrosFor(voice: OsteriaVoice): List<MacroSpec> = when (voice) {
        OsteriaVoice.QUARTET, OsteriaVoice.BASS, OsteriaVoice.PLUCK, OsteriaVoice.REED, OsteriaVoice.PAD,
        OsteriaVoice.BOWED -> listOf(
            MacroSpec("SHORE", .42f, neutral = .42f),
            MacroSpec("TENSION", .55f, neutral = .55f),
            MacroSpec("WANDER", .52f, neutral = .52f),
            MacroSpec("MEMORY", .45f, neutral = .45f),
            MacroSpec("ROOM", 0f, neutral = 0f),
            MacroSpec("HOLD", 0f, neutral = 0f),
        )
    }

    fun defaults(voice: OsteriaVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }
    /** HOLD always extends a finite note; no setting selects a sample loop. */
    fun drumClassFor(voice: OsteriaVoice, macros: Map<String, Float> = emptyMap()): DrumClass {
        settled(voice, macros)
        return DrumClass.TONAL
    }

    /** Change the note's color without adding room or changing its saved duration. */
    fun scramble(voice: OsteriaVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = settled(voice, base + (near?.macros?.filterKeys { it in base } ?: emptyMap()))
        val amount = (temperature.takeIf { it.isFinite() } ?: .35f).coerceIn(0f, 1f)
        return settled(voice, Dsp.scrambleNear(seed, amount, random).toMutableMap().apply {
            this["ROOM"] = seed.getValue("ROOM")
            this["HOLD"] = seed.getValue("HOLD")
        })
    }

    fun configurationFor(voice: OsteriaVoice): OsteriaConfig = when (voice) {
        OsteriaVoice.QUARTET -> OsteriaConfig()
        OsteriaVoice.BASS -> OsteriaConfig(roleLevels = listOf(.96, .38, .21, .16), attackSeconds = .008, releaseSeconds = .30)
        OsteriaVoice.PLUCK -> OsteriaConfig(roleLevels = listOf(.72, .65, .38, .30), attackSeconds = .005, releaseSeconds = .35)
        OsteriaVoice.REED -> OsteriaConfig(roleLevels = listOf(.56, .48, .82, .25), attackSeconds = .07, releaseSeconds = .50)
        OsteriaVoice.PAD -> OsteriaConfig(roleLevels = listOf(.66, .72, .54, .44), attackSeconds = .65, releaseSeconds = 1.20)
        OsteriaVoice.BOWED -> OsteriaConfig(roleLevels = listOf(.72, .64, .58, .36), attackSeconds = .18, releaseSeconds = .65)
    }

    internal fun settled(voice: OsteriaVoice, macros: Map<String, Float>): Map<String, Float> =
        defaults(voice).toMutableMap().apply {
            for ((name, value) in macros) {
                require(name in this) { "unknown OSTERIA macro: $name" }
                require(value.isFinite() && value in 0f..1f) { "OSTERIA $name must be finite and in 0..1" }
                this[name] = value
            }
        }

    internal data class ProbeOptions(
        val couplingEnabled: Boolean = true,
        val memoryEnabled: Boolean = true,
        val motionEnabled: Boolean = true,
        val roomEnabled: Boolean = true,
        val reedPressureEnabled: Boolean = true,
        val bowedFrictionEnabled: Boolean = true,
    )

    internal data class ShoreFrame(
        val timeSeconds: Double,
        val positions: List<Double>,
        val remembered: List<Double>,
    )

    /** Stereo output and stems share one gain; their sample-wise sum is the output. */
    internal data class Probe(
        val samples: FloatArray,
        val stems: Array<FloatArray>,
        val shoreTrace: List<ShoreFrame>,
        val sampleRate: Int = Dsp.RATE,
        val channels: Int = 2,
    )

    fun render(
        voice: OsteriaVoice,
        macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f,
        config: OsteriaConfig = configurationFor(voice),
        cancelled: () -> Boolean = { false },
    ): Snip {
        val raw = probe(voice, macros, midi, velocity, config, cancelled = cancelled)
        val samples = raw.samples
        // Velocity remains an audible level choice after the common melodic calibration.
        // Its separate excitation effect is already present in the source and envelopes.
        Dsp.levelTo(samples, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET * velocity, channels = 2)
        applyOutputBalance(voice, samples)
        stopIfUnwanted(cancelled)
        return Snip(samples, channels = 2, sampleRate = Dsp.RATE)
    }

    /** Listening balance: soften the reed's projection and give the slow pad a little more presence. */
    internal fun balanceTrimDbFor(voice: OsteriaVoice): Double = when (voice) {
        OsteriaVoice.REED -> -1.5
        OsteriaVoice.PAD -> 1.5
        else -> 0.0
    }

    /** Apply after leveling so RMS matching cannot undo the intended voice balance. Raw probes stay untouched. */
    internal fun applyOutputBalance(voice: OsteriaVoice, samples: FloatArray) {
        val trimDb = balanceTrimDbFor(voice)
        if (trimDb == 0.0) return
        var gain = 10.0.pow(trimDb / 20.0).toFloat()
        // A positive balance must retain the same headroom as the common output leveler.
        if (trimDb > 0.0) {
            val peak = samples.maxOfOrNull { abs(it) } ?: 0f
            if (peak * gain > .99f) gain = .99f / peak
        }
        for (index in samples.indices) samples[index] *= gain
    }

    internal fun probe(
        voice: OsteriaVoice,
        macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f,
        config: OsteriaConfig = configurationFor(voice),
        options: ProbeOptions = ProbeOptions(),
        cancelled: () -> Boolean = { false },
    ): Probe {
        require(midi in MIN_MIDI..MAX_MIDI) { "OSTERIA root must be MIDI $MIN_MIDI..$MAX_MIDI" }
        require(velocity.isFinite() && velocity in 0f..1f) { "OSTERIA velocity must be finite and in 0..1" }
        require(voice == OsteriaVoice.QUARTET || config.model == 2) { "OSTERIA model 1 is only valid for QUARTET" }
        stopIfUnwanted(cancelled)
        val recipe = config.snapshot()
        val m = settled(voice, macros)
        val shore = 4.0 * m.getValue("SHORE")
        val tension = m.getValue("TENSION").toDouble()
        val wander = if (options.motionEnabled) m.getValue("WANDER").toDouble() else 0.0
        val memory = if (options.memoryEnabled) m.getValue("MEMORY").toDouble() else 0.0
        val room = if (options.roomEnabled) m.getValue("ROOM").toDouble() else 0.0
        val seconds = recipe.seconds + (8.0 - recipe.seconds) * m.getValue("HOLD")
        val gate = seconds - recipe.releaseSeconds
        val outputFrames = (seconds * Dsp.RATE).roundToInt()
        val frames = outputFrames * Dsp.OVERSAMPLE
        val rootHz = Keys.midiHz(midi).toDouble()
        // A note's travel clock belongs to elapsed note-on time, not its eventual release.
        // A short tap therefore starts exactly like the same note held for longer.
        val travelGate = if (recipe.model == 1) gate else 5.2
        val motion = Motion(recipe, shore, tension, wander, travelGate, options.couplingEnabled, options.motionEnabled)
        val history = History(.5 + 2.5 * memory, motion.positions)
        val tables = sourceTables(voice, midi, recipe.seed, recipe.model)
        val rawStems = Array(4) { FloatArray(frames * 2) }
        val phases = Dsp.phases(4, Dsp.seedFor("OSTERIA-phase", recipe.seed, midi))
        // A common fundamental cannot beat against or cancel the other contributors.
        // Each source still owns its upper-partial phases and spectral palette.
        if (recipe.model == 2) phases.fill(phases[0])
        val noises = Array(4) { Dsp.Noise(Dsp.seedFor("OSTERIA-air", recipe.seed, it)) }
        val breathPole = DoubleArray(4)
        val sourcePole = DoubleArray(4)
        val sympatheticPole = DoubleArray(4)
        val previous = DoubleArray(4)
        val nextOutputs = DoubleArray(4)
        val controls = Controls()
        val nextControls = Controls()
        val trace = ArrayList<ShoreFrame>()
        val controlFrames = (INTERNAL_RATE * CONTROL_SECONDS).roundToInt()
        val pan = doubleArrayOf(-.20, -.48, .46, .18)
        val panL = DoubleArray(4) { cos((pan[it] + 1.0) * PI / 4.0) }
        val panR = DoubleArray(4) { sin((pan[it] + 1.0) * PI / 4.0) }
        val sympathy = if (options.couplingEnabled) .07 + .12 * tension else 0.0
        // Slow, aperiodic pressure changes the reed's upper-body excitation, never
        // its phase. The seed and elapsed control clock reproduce the same breath
        // on every render; this is a source gesture, not a pitch wobble or retrigger.
        val reedPressureNoise = Dsp.Noise(Dsp.seedFor("OSTERIA-reed-pressure", recipe.seed, midi))
        var reedPressure = 0.0
        // Spectral bow friction is local to this note. It slowly changes upper
        // excitation and damping, never oscillator phase or the shared envelope.
        // This is a bowed-string color, not a stick-slip or waveguide simulation.
        val bowedFrictionNoise = Dsp.Noise(Dsp.seedFor("OSTERIA-bowed-friction", recipe.seed, midi))
        var bowedFriction = 0.0

        fun prepare(t: Double, destination: Controls, advance: Boolean) {
            if (advance) {
                motion.advance(t, CONTROL_SECONDS)
                history.push(motion.positions)
                if (voice == OsteriaVoice.REED && options.reedPressureEnabled) {
                    reedPressure += .12 * (reedPressureNoise.next() - reedPressure)
                }
                if (voice == OsteriaVoice.BOWED && options.bowedFrictionEnabled) {
                    bowedFriction += .065 * (bowedFrictionNoise.next() - bowedFriction)
                }
            }
            val remembered = history.mean()
            val positions = motion.positions
            if (advance && ((t / CONTROL_SECONDS).roundToInt() % 2 == 0 || t >= seconds)) {
                trace.add(ShoreFrame(t.coerceAtMost(seconds), positions.toList(), remembered.toList()))
            }
            val tail = if (t <= gate) 1.0 else .5 + .5 * cos(PI * ((t - gate) / recipe.releaseSeconds).coerceIn(0.0, 1.0))
            val articulation = if (recipe.model == 1) smooth(t / .012) else if (voice == OsteriaVoice.QUARTET)
                noteEnvelope(min(t, gate), velocity.toDouble(), recipe.attackSeconds) else
                familyEnvelope(voice, min(t, gate), velocity.toDouble(), recipe.attackSeconds)
            val global = articulation * tail * velocity * (.55 + .45 * velocity)
            for (role in 0..3) {
                val now = positions[role]
                val then = remembered[role]
                val tint = .72 * memory
                destination.weights[role].fill(0.0)
                shoreWeights(now, 1.0 - tint, destination.weights[role])
                shoreWeights(then, tint, destination.weights[role])
                // Each coast changes damping and air as well as the static harmonic source.
                val decay = palette(now, doubleArrayOf(.95, 1.35, .65, 1.10, .80))
                val envelope = if (recipe.model == 1) {
                    envelope(role, min(t, gate), gate, decay, velocity.toDouble())
                } else {
                    // Coast motion gently damps a held tone; it never schedules an attack.
                    .92 + .08 * ((decay - .65) / .70).coerceIn(0.0, 1.0)
                }
                destination.envelopes[role] = envelope * global
                val excitationEnvelope = if (recipe.model == 1) envelope else envelope * articulation
                val pressureColor = if (voice == OsteriaVoice.REED) .40 * reedPressure else 0.0
                destination.excitation[role] = if (voice == OsteriaVoice.BOWED) {
                    (.14 + excitationEnvelope * (.34 + .48 * velocity) + .55 * bowedFriction).coerceIn(0.0, 1.0)
                } else (excitationEnvelope * (.24 + .76 * velocity) + .08 + pressureColor).coerceIn(0.0, 1.0)
                val breath = palette(now, doubleArrayOf(.015, .004, .028, .007, .090)) *
                    when (role) { 2 -> 1.0; 1 -> .42; 3 -> 1.25; else -> .08 }
                destination.breath[role] = when (voice) {
                    OsteriaVoice.QUARTET -> breath
                    OsteriaVoice.BASS -> breath * .08
                    OsteriaVoice.PLUCK -> breath * .35 * exp(-min(t, gate) / .10)
                    OsteriaVoice.REED -> (breath * .65 + .012) * (1.0 + .25 * reedPressure) *
                        (1.0 + .35 * exp(-min(t, gate) / .10))
                    OsteriaVoice.PAD -> breath * .24
                    OsteriaVoice.BOWED -> (.006 + .10 * breath) * (1.0 + .40 * bowedFriction) *
                        (1.0 + .25 * exp(-min(t, gate) / .08))
                }
                // Air settles with the one shared onset, then follows the same
                // aperiodic pressure as the pitched body. The noise stays quiet;
                // changing its bandwidth avoids a stationary hiss under the note.
                destination.breathFilter[role] = if (voice == OsteriaVoice.REED) {
                    val airHz = (rootHz * 8.0).coerceIn(900.0, 2600.0) * (1.0 + .35 * reedPressure) +
                        300.0 * exp(-min(t, gate) / .12)
                    1.0 - exp(-2.0 * PI * airHz / INTERNAL_RATE)
                } else if (voice == OsteriaVoice.BOWED) {
                    val frictionHz = (rootHz * 5.0).coerceIn(800.0, 2300.0) * (1.0 + .50 * bowedFriction) +
                        180.0 * exp(-min(t, gate) / .08)
                    1.0 - exp(-2.0 * PI * frictionHz / INTERNAL_RATE)
                } else .055
                val cutoff = rootHz * when (role) { 0 -> 9.0; 1 -> 18.0; 2 -> 23.0; else -> 13.0 } *
                    palette(now, doubleArrayOf(.70, 1.3, .95, 1.45, .62))
                val familyCutoff = when (voice) {
                    OsteriaVoice.QUARTET -> cutoff
                    OsteriaVoice.BASS -> cutoff * .42
                    OsteriaVoice.PLUCK -> cutoff * (.42 + 1.10 * exp(-min(t, gate) / .55))
                    // Pressure gently changes damping as well as upper excitation.
                    // This reshapes low-body harmonic ratios without moving the
                    // common oscillator phase or creating a gain pulse.
                    OsteriaVoice.REED -> cutoff * .72 * (1.0 + .65 * reedPressure)
                    OsteriaVoice.PAD -> cutoff * .70
                    OsteriaVoice.BOWED -> (cutoff * .82 * (1.0 + .65 * bowedFriction)).coerceAtMost(5000.0)
                }
                destination.filter[role] = 1.0 - exp(-2.0 * PI * familyCutoff.coerceIn(220.0, 15_000.0) / INTERNAL_RATE)
                val ratio = if (recipe.model == 1) pitchRatio(role, t, gate) else 1.0
                destination.phaseStep[role] = rootHz * ratio / INTERNAL_RATE
            }
        }

        trace.add(ShoreFrame(0.0, motion.positions.toList(), motion.positions.toList()))
        prepare(0.0, controls, false)
        prepare(CONTROL_SECONDS, nextControls, true)
        for (frame in 0 until frames) {
            if (frame % 8192 == 0) stopIfUnwanted(cancelled)
            if (frame > 0 && frame % controlFrames == 0) {
                controls.copyFrom(nextControls)
                prepare((frame + controlFrames).toDouble() / INTERNAL_RATE, nextControls, true)
            }
            val fraction = (frame % controlFrames).toDouble() / controlFrames
            val peersTotal = previous.sum()
            for (role in 0..3) {
                val excitation = lerp(controls.excitation[role], nextControls.excitation[role], fraction)
                var source = 0.0
                for (coast in 0..4) {
                    val weight = lerp(controls.weights[role][coast], nextControls.weights[role][coast], fraction)
                    if (weight > 1e-8) {
                        val low = read(tables[role][coast][0], phases[role])
                        val high = read(tables[role][coast][1], phases[role])
                        source += weight * lerp(low, high, excitation)
                    }
                }
                // Breath is filtered original noise. Only the upper answer and quiet touch
                // carry appreciable air; the bass keeps its unmodulated pitched anchor.
                val airFilter = if (voice == OsteriaVoice.REED || voice == OsteriaVoice.BOWED)
                    lerp(controls.breathFilter[role], nextControls.breathFilter[role], fraction) else .055
                breathPole[role] += airFilter * (noises[role].next() - breathPole[role])
                source += breathPole[role] * lerp(controls.breath[role], nextControls.breath[role], fraction)
                val a = lerp(controls.filter[role], nextControls.filter[role], fraction)
                sourcePole[role] += a * (source - sourcePole[role])
                val peers = (peersTotal - previous[role]) / 3.0
                sympatheticPole[role] += .024 * (peers - sympatheticPole[role])
                val e = lerp(controls.envelopes[role], nextControls.envelopes[role], fraction)
                // One delayed pass, no instantaneous cycle. |feedback| < .19 and all
                // filters are convex one-pole updates, so sympathy cannot run away.
                val output = sourcePole[role] * e + sympathy * sympatheticPole[role] * e
                nextOutputs[role] = output
                val gain = OUTPUT_GAIN * recipe.roleLevels[role]
                rawStems[role][frame * 2] = (output * gain * panL[role]).toFloat()
                rawStems[role][frame * 2 + 1] = (output * gain * panR[role]).toFloat()
                phases[role] += lerp(controls.phaseStep[role], nextControls.phaseStep[role], fraction)
                phases[role] -= floor(phases[role])
            }
            nextOutputs.copyInto(previous)
        }
        stopIfUnwanted(cancelled)
        val stems = Array(4) { role ->
            stopIfUnwanted(cancelled)
            val result = Dsp.decimate(rawStems[role], Dsp.RATE, channels = 2)
            // Independent linear DC rejection preserves exact additivity of the stems.
            val dc = Array(2) { Dsp.OnePole(Dsp.RATE) }
            for (frame in 0 until result.size / 2) {
                for (channel in 0..1) {
                    val index = frame * 2 + channel
                    result[index] -= dc[channel].lp(result[index], 8f)
                }
            }
            if (room > 0.0) tavern(result, room, recipe.tavernShore, cancelled)
            Dsp.fadeTail(result, rate = Dsp.RATE, channels = 2)
            result
        }
        val samples = FloatArray(stems[0].size) { index ->
            var sum = 0f
            for (role in 0..3) sum += stems[role][index]
            check(sum.isFinite()) { "OSTERIA finite-output invariant failed" }
            sum
        }
        stopIfUnwanted(cancelled)
        return Probe(samples, stems, trace)
    }

    private class Controls {
        val weights = Array(4) { DoubleArray(5) }
        val envelopes = DoubleArray(4)
        val excitation = DoubleArray(4)
        val breath = DoubleArray(4)
        val breathFilter = DoubleArray(4)
        val filter = DoubleArray(4)
        val phaseStep = DoubleArray(4)
        fun copyFrom(other: Controls) {
            for (role in 0..3) other.weights[role].copyInto(weights[role])
            other.envelopes.copyInto(envelopes)
            other.excitation.copyInto(excitation)
            other.breath.copyInto(breath)
            other.breathFilter.copyInto(breathFilter)
            other.filter.copyInto(filter)
            other.phaseStep.copyInto(phaseStep)
        }
    }

    /** Bounded damped travel at 200 Hz, independent of sample-rate source generation. */
    private class Motion(
        recipe: OsteriaConfig, private val shore: Double, private val tension: Double,
        private val wander: Double, private val gate: Double, private val coupled: Boolean,
        private val moving: Boolean,
    ) {
        private val initial = recipe.initialShores.toDoubleArray()
        private val state = initial.copyOf()
        private val speed = DoubleArray(4)
        private val phases = Dsp.phases(4, Dsp.seedFor("OSTERIA-motion", recipe.seed))
        private val direction = recipe.direction
        val positions = initial.copyOf()

        fun advance(t: Double, dt: Double) {
            if (!moving) return
            val centre = state.average()
            val approach = 1.0 - smooth(t / (1.35 + 1.1 * wander))
            val departure = smooth(t / .35) * (1.0 - smooth((t - .49 * gate) / (.20 * gate)))
            for (role in 0..3) {
                val ownPhase = phases[role] * .9 + role * .75
                val excursion = direction * wander * departure * (1.40 * sin(t * (1.00 + .23 * role) + ownPhase) +
                    .45 * sin(t * (2.15 + .17 * role) + 1.3 * ownPhase))
                val target = (shore + (initial[role] - shore) * approach + excursion).coerceIn(0.0, 4.0)
                val spring = 5.0 + 12.0 * tension
                val ensemble = if (coupled) (1.0 + 25.0 * tension * tension) * (centre - state[role]) else 0.0
                val acceleration = spring * (target - state[role]) + ensemble - (4.5 + 2.0 * tension) * speed[role]
                speed[role] = (speed[role] + acceleration * dt).coerceIn(-3.0, 3.0)
                state[role] = (state[role] + speed[role] * dt).coerceIn(0.0, 4.0)
                // Both directions reach precisely the same sustained destination. A smooth
                // finite reunion exposes their different histories during the last ~1.4 s.
                val reunion = 1.0 - smooth((t - .59 * gate) / (.14 * gate))
                positions[role] = (shore + reunion * (state[role] - shore)).coerceIn(0.0, 4.0)
            }
        }
    }

    /** Ring history is measured in seconds, not audio samples or a tiny block count. */
    private class History(seconds: Double, initial: DoubleArray) {
        private val count = (seconds / CONTROL_SECONDS).roundToInt().coerceAtLeast(1)
        private val ring = Array(count) { initial.copyOf() }
        private val sum = DoubleArray(4) { initial[it] * count }
        private var head = 0
        fun push(positions: DoubleArray) {
            for (role in 0..3) {
                sum[role] += positions[role] - ring[head][role]
                ring[head][role] = positions[role]
            }
            head = (head + 1) % count
        }
        fun mean(): DoubleArray = DoubleArray(4) { (sum[it] / count).coerceIn(0.0, 4.0) }
    }

    private fun envelope(role: Int, t: Double, gate: Double, damping: Double, velocity: Double): Double {
        val events = when (role) {
            0 -> doubleArrayOf(0.0, .245, .49, .755)
            1 -> doubleArrayOf(.025, .46)
            2 -> doubleArrayOf(.095, .265, .43, .61, .765)
            else -> doubleArrayOf(.04, .19, .375, .54, .725)
        }
        var age = -1.0
        var eventIndex = 0
        for (index in events.indices) if (t >= events[index] * gate) {
            age = t - events[index] * gate
            eventIndex = index
        }
        if (age < 0.0) return 0.0
        val attack = when (role) { 0 -> .008; 1 -> .15; 2 -> .055; else -> .006 } * (1.0 + .5 * (1.0 - velocity))
        val contour = when (role) {
            0 -> .18 + .82 * exp(-age / (.68 * damping))
            1 -> .65 + .35 * exp(-age / (1.8 * damping))
            2 -> .28 + .72 * exp(-age / (.58 * damping))
            else -> .10 + .90 * exp(-age / (.15 * damping))
        }
        val accent = if (role == 3 && eventIndex % 2 == 1) .65 else 1.0
        return smooth(age / attack) * contour * accent
    }

    /** One shared onset, an early decay, then stable sustain. Softer touch slightly rounds the onset. */
    private fun noteEnvelope(t: Double, velocity: Double, attackSeconds: Double): Double =
        smooth(t / (attackSeconds * (1.0 + .25 * (1.0 - velocity)))) * (.72 + .28 * exp(-t / .24))

    /** A single shared excitation: no role has a later onset or an independent retrigger. */
    private fun familyEnvelope(voice: OsteriaVoice, t: Double, velocity: Double, attackSeconds: Double): Double {
        val attack = smooth(t / (attackSeconds * (1.0 + .25 * (1.0 - velocity))))
        val body = when (voice) {
            OsteriaVoice.BASS -> .76 + .24 * exp(-t / .11)
            // The string keeps losing energy while the key is held. Release only damps
            // that existing vibration; it never creates a second excitation.
            OsteriaVoice.PLUCK -> .74 * exp(-t / .70) + .26 * exp(-t / 2.50)
            OsteriaVoice.REED -> .91 + .09 * exp(-t / .24)
            OsteriaVoice.PAD -> .90 + .10 * (1.0 - exp(-t / 1.0))
            OsteriaVoice.BOWED -> .92 + .08 * exp(-t / .20)
            OsteriaVoice.QUARTET -> error("the approved quartet uses its original envelope")
        }
        return attack * body
    }

    private fun pitchRatio(role: Int, t: Double, gate: Double): Double = when (role) {
        0 -> 1.0
        1 -> 2.0
        3 -> 2.0
        else -> {
            // One restrained C/G/C answer, anchored to the requested root. The upper
            // traveller changes source identity without introducing uncontrolled tuning.
            val positions = doubleArrayOf(.095, .265, .43, .61, .765)
            val ratios = doubleArrayOf(2.0, 3.0, 4.0, 3.0, 2.0)
            var index = 0
            for (candidate in positions.indices) if (t >= positions[candidate] * gate) index = candidate
            if (index == 0) ratios[0] else {
                val a = smooth((t - positions[index] * gate) / .035)
                lerp(ratios[index - 1], ratios[index], a)
            }
        }
    }

    private fun table(role: Int, coast: Int, excitation: Int, rootHz: Double, seed: Int, model: Int): FloatArray {
        val bright = excitation.toDouble()
        val harmonics = 24
        val amplitudes = DoubleArray(harmonics + 1)
        val nominalHz = if (model == 1) rootHz * when (role) { 0 -> 1.0; 2 -> 4.0; else -> 2.0 } else rootHz
        for (h in 1..harmonics) {
            val n = h.toDouble()
            val base = when (role) {
                0 -> if (h == 1) 1.0 else n.pow(-1.65 + .67 * bright) * (.92 + .08 * cos(h * .83))
                1 -> when (h) { 1 -> 1.0; 2 -> .65; 3 -> .28; 4 -> .47; 6 -> .28; 8 -> .11; else -> .09 / n }
                2 -> if (h == 1) 1.0 else if (h % 2 == 1) .66 / n.pow(.85 + .6 * (1.0 - bright)) else .20 / n.pow(1.2)
                else -> when (h) { 1 -> 1.0; 2 -> .42; 3 -> .52; 5 -> .27; 7 -> .13; else -> .06 / n }
            }
            // Imagined Atlantic / Nordic / Mediterranean / Pacific / Southern colors.
            val coastal = when (coast) {
                0 -> exp(-n * (.11 - .05 * bright)) * (1.0 + .25 * cos(n * .70))
                1 -> if (h == 1) 1.0 else if (h % 2 == 1) 1.15 + .75 * bright else .30
                2 -> when (h) { 2, 3, 4 -> 1.6; else -> .60 + .65 * bright }
                3 -> if (h == 1) 1.0 else if (h == 3 || h == 5 || h == 9) 1.8 + .70 * bright else .45
                else -> if (h == 1) 1.15 else if (h % 2 == 0) .14 else .70 / sqrt(n)
            }
            val excitationDamping = if (h == 1) 1.0 else .32 + .68 * bright
            // Fade the highest partials well below output Nyquist, before the 4x decimator.
            val bandLimit = 1.0 - smooth((h * nominalHz - 9_000.0) / 8_000.0)
            // Model two puts the palette contrast in upper partials, with a common
            // fundamental instead of relying on octave-separated players for contrast.
            val color = if (model == 1) coastal else if (h == 1) 1.0 else 1.7 * coastal * coastal
            amplitudes[h] = base * color * excitationDamping * bandLimit
        }
        if (model == 2) {
            // Even the strongest formant coast keeps at least one third of its unfiltered
            // table power at the root. This bounds brightness without weakening pitch.
            val upperPower = (2..harmonics).sumOf { amplitudes[it] * amplitudes[it] }
            val limit = 2.0 * amplitudes[1] * amplitudes[1]
            if (upperPower > limit) {
                val scale = sqrt(limit / upperPower)
                for (h in 2..harmonics) amplitudes[h] *= scale
            }
        }
        // All model 2 sources share this table fundamental; upper partials carry their
        // distinct identities. Model 1 retains its octave/fifth phrase tables unchanged.
        val rms = sqrt(amplitudes.sumOf { it * it } / 2.0).coerceAtLeast(.001)
        val partialPhases = Dsp.phases(harmonics + 1, Dsp.seedFor("OSTERIA-table", seed, role))
        val output = FloatArray(TABLE_SIZE + 1) { index ->
            val angle = 2.0 * PI * (index % TABLE_SIZE) / TABLE_SIZE
            var value = 0.0
            for (h in 1..harmonics) {
                val phase = if (h == 1) 0.0 else when (role) {
                    0 -> .35 * sin(partialPhases[h] * 2.0 * PI)
                    1 -> .08 * sin(partialPhases[h] * 2.0 * PI)
                    2 -> PI / 3.0 + .2 * sin(partialPhases[h] * 2.0 * PI)
                    else -> .60 * sin(partialPhases[h] * 2.0 * PI)
                }
                value += amplitudes[h] * sin(h * angle + phase)
            }
            (.42 * value / rms).toFloat()
        }
        val peak = output.maxOf { abs(it) }
        if (peak > 1.35f) for (index in output.indices) output[index] *= 1.35f / peak
        return output
    }

    /**
     * New families own their source spectra; shore and memory tint those sources.
     * Every partial is an integer multiple of the requested root. The four members
     * therefore change color together without introducing detuning or another note.
     * This is separate from [table] so both original models retain their exact PCM.
     */
    private fun familyTable(
        voice: OsteriaVoice, role: Int, coast: Int, excitation: Int, rootHz: Double, seed: Int,
    ): FloatArray {
        val bright = excitation.toDouble()
        val harmonics = 24
        val amplitudes = DoubleArray(harmonics + 1)
        for (h in 1..harmonics) {
            val n = h.toDouble()
            val base = if (h == 1) 1.0 else when (voice) {
                OsteriaVoice.BASS -> when (h) {
                    2 -> .25 + .14 * bright
                    3 -> .085 + .085 * bright
                    4 -> .038 + .038 * bright
                    else -> .025 / n.pow(1.3)
                }
                // A bright, harmonic string excitation; the low/high tables are
                // crossfaded by the same decaying energy that drives its envelope.
                OsteriaVoice.PLUCK -> (.45 + .55 * sin(n * .91).pow(2.0)) /
                    n.pow(1.18 - .48 * bright)
                OsteriaVoice.REED -> {
                    // A rounded vibrating air column: even partials give the low
                    // body some width, while the faster rolloff removes the old
                    // rigid odd-harmonic buzz. This is a spectral voice, not a
                    // claim to simulate a particular reed or instrument bore.
                    val hz = n * rootHz
                    val body = .65 + .55 * exp(-((hz - (500.0 + 50.0 * role)) / 450.0).pow(2.0))
                    (if (h % 2 == 1) 1.45 else .81) / n.pow(1.15) * body * exp(-hz / 4200.0)
                }
                OsteriaVoice.PAD -> when (h) {
                    2 -> .68
                    3 -> .13
                    4 -> .40
                    6 -> .25
                    8 -> .16
                    10 -> .10
                    else -> .035 / n
                }
                OsteriaVoice.BOWED -> {
                    // A continuous string draw has both odd and even partials.
                    // Low/high excitation changes their slope as well as amount;
                    // h2/h4 carry the body without the pad's sparse even ladder.
                    val body = when (h) { 2 -> 1.35; 4 -> 1.28; else -> 1.0 }
                    val slope = 1.45 - .40 * bright
                    val rolloff = 1.0 / sqrt(1.0 + (n * rootHz / 4600.0).pow(4.0))
                    (.95 + .09 * cos(n * .62)) * body / n.pow(slope) * rolloff
                }
                OsteriaVoice.QUARTET -> error("the approved quartet uses its original tables")
            }
            val coastColor = if (h == 1) 1.0 else if (voice == OsteriaVoice.REED) when (coast) {
                0 -> exp(-n * .16) * if (h == 2) 1.35 else 1.0
                1 -> exp(-n * .04) * if (h % 2 == 1) 1.50 else .50
                2 -> if (h in 2..4) 1.75 else .55
                3 -> when (h) { 2 -> 1.40; 3 -> 1.80; 5 -> 1.20; else -> .45 }
                else -> exp(-n * .10) * if (h % 2 == 0) .90 else .65
            } else if (voice == OsteriaVoice.BOWED) when (coast) {
                0 -> exp(-n * .055) * if (h == 2 || h == 4) 1.10 else 1.0
                1 -> exp(-n * .022) * if (h % 2 == 1) 1.08 else .92
                2 -> when (h) { 2, 4 -> 1.50; 3 -> .98; else -> .78 }
                3 -> when (h) { 3, 6 -> 1.55; 5, 9 -> 1.18; else -> .82 }
                else -> exp(-n * .08) * if (h == 2) 1.35 else 1.0
            } else when (coast) {
                0 -> exp(-n * .075) * (1.0 + .16 * cos(n * .70))
                1 -> if (h % 2 == 1) 1.45 else .70
                2 -> if (h in 2..4) 1.55 else .85
                3 -> if (h == 3 || h == 5 || h == 9) 1.65 else .78
                else -> if (h % 2 == 0) .64 else 1.05 / n.pow(.12)
            }
            val roleColor = if (h == 1) 1.0 else when (role) {
                0 -> exp(-n * .038)
                1 -> if (h % 2 == 0) 1.22 else .82
                2 -> if (h % 2 == 1) 1.20 else .78
                else -> .82 + .32 * sin(n * .77).pow(2.0)
            }
            val excitationColor = if (h == 1) 1.0 else if (voice == OsteriaVoice.BOWED) .70 + .30 * bright else .38 + .62 * bright
            val bandLimit = 1.0 - smooth((h * rootHz - 9_000.0) / 8_000.0)
            amplitudes[h] = base * coastColor * roleColor * excitationColor * bandLimit
        }
        // Preserve a reliable root even in the reed's strongest formant and the
        // pluck's brightest excitation. This is not output loudness normalization.
        val upperPower = (2..harmonics).sumOf { amplitudes[it] * amplitudes[it] }
        if (upperPower > 1.8) {
            val scale = sqrt(1.8 / upperPower)
            for (h in 2..harmonics) amplitudes[h] *= scale
        }
        val rms = sqrt(amplitudes.sumOf { it * it } / 2.0).coerceAtLeast(.001)
        val partialPhases = Dsp.phases(harmonics + 1, Dsp.seedFor("OSTERIA-${voice.name}-table", seed, role))
        val output = FloatArray(TABLE_SIZE + 1) { index ->
            val angle = 2.0 * PI * (index % TABLE_SIZE) / TABLE_SIZE
            var value = 0.0
            for (h in 1..harmonics) {
                val phase = if (h == 1) 0.0 else when (voice) {
                    OsteriaVoice.BASS -> .08 * sin(partialPhases[h] * 2.0 * PI)
                    OsteriaVoice.PLUCK -> .50 * sin(partialPhases[h] * 2.0 * PI)
                    OsteriaVoice.REED -> .20 * sin(partialPhases[h] * 2.0 * PI)
                    OsteriaVoice.PAD -> .18 * sin(partialPhases[h] * 2.0 * PI)
                    OsteriaVoice.BOWED -> .30 * sin(partialPhases[h] * 2.0 * PI)
                    OsteriaVoice.QUARTET -> 0.0
                }
                value += amplitudes[h] * sin(h * angle + phase)
            }
            (.42 * value / rms).toFloat()
        }
        val peak = output.maxOf { abs(it) }
        if (peak > 1.35f) for (index in output.indices) output[index] *= 1.35f / peak
        return output
    }

    /** Fixed, quiet early reflections; this shore never follows the travelling members. */
    private fun tavern(samples: FloatArray, amount: Double, shore: Double, cancelled: () -> Boolean) {
        val frames = samples.size / 2
        val dry = samples.copyOf()
        val scale = .85 + .16 * shore
        val delays = intArrayOf((.031 * scale * Dsp.RATE).roundToInt(),
            (.053 * scale * Dsp.RATE).roundToInt(), (.083 * scale * Dsp.RATE).roundToInt())
        val color = .10 + .028 * (4.0 - shore)
        val diffuse = DoubleArray(2)
        for (frame in 0 until frames) {
            if (frame % 8192 == 0) stopIfUnwanted(cancelled)
            for (channel in 0..1) {
                var reflection = 0.0
                for (tap in delays.indices) {
                    val from = frame - delays[tap]
                    if (from >= 0) reflection += dry[from * 2 + if (tap % 2 == 0) 1 - channel else channel] *
                        when (tap) { 0 -> .18; 1 -> .12; else -> .08 }
                }
                diffuse[channel] += color * (reflection - diffuse[channel])
                samples[frame * 2 + channel] += (amount * diffuse[channel]).toFloat()
            }
        }
    }

    private fun shoreWeights(position: Double, gain: Double, weights: DoubleArray) {
        val p = position.coerceIn(0.0, 4.0)
        val lo = floor(p).toInt()
        val hi = min(4, lo + 1)
        val blend = smooth(p - lo)
        weights[lo] += gain * (1.0 - blend)
        weights[hi] += gain * blend
    }

    private fun palette(position: Double, values: DoubleArray): Double {
        val p = position.coerceIn(0.0, 4.0)
        val lo = floor(p).toInt()
        return lerp(values[lo], values[min(4, lo + 1)], smooth(p - lo))
    }

    private fun read(table: FloatArray, phase: Double): Double {
        val position = phase * TABLE_SIZE
        val index = position.toInt().coerceIn(0, TABLE_SIZE - 1)
        return lerp(table[index].toDouble(), table[index + 1].toDouble(), position - index)
    }

    private fun smooth(value: Double): Double = value.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }
    private fun lerp(a: Double, b: Double, fraction: Double): Double = a + (b - a) * fraction
    private fun stopIfUnwanted(cancelled: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || cancelled()) throw CancellationException("OSTERIA render no longer wanted")
    }
}
