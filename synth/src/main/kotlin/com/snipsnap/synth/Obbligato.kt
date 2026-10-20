package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** A relationship's exact position is its ordinal / 7, with interpolation between neighbors. */
enum class ObbligatoRelationship {
    HARMONY, PLAY, DARE, FIGHT, CRY, FORGIVE, PROTECT, TRANSCEND;

    val bond: Float get() = ordinal / 7f
}

/**
 * First OBBLIGATO listening candidate: an air jet and a reed on independent tuned pipes.
 * A synthetic player controller coordinates effort, entry, and pitch, and lets each player
 * respond to the other pipe's measured activity. This is an invented duet, not a model of
 * two people sharing one lung or a claim of acoustic accuracy for their mutual influence.
 *
 * Dry mono, explicit C4–C5 notes, independently seeded players and one final loudness target.
 * HOLD extends a finite gesture, including at 1. It does not yet claim a settled sustain loop.
 * The controller and calibration are provisional until the owner hears the audition pack.
 */
object Obbligato {
    const val MODEL = 5
    // Keep source personalities stable between listening revisions. Models 1–4
    // remain renderable; model 5 makes Fight's challenges uneven and distinct.
    private const val SOURCE_SEED_MODEL = 1
    const val DEFAULT_MIDI = 60
    const val MIDI_MIN = 60
    const val MIDI_MAX = 72
    const val DEFAULT_VELOCITY = .8f
    private const val RATE = Dsp.RATE * Dsp.OVERSAMPLE
    private const val CONTROL_BLOCK = 64
    private const val RELEASE_SECONDS = .75
    private const val MAX_DETUNE_CENTS = 32f
    private val FIGHT_ENTRIES = doubleArrayOf(.19, .37, .62, .78, .91)
    private val FIGHT_CROSSOVERS = doubleArrayOf(.10, .13, .09, .14, .11)

    // Fixed radiation weights, shared by every relationship/note/velocity. Neither player is
    // independently normalized. At C4/.8 velocity, the initial .55/.32 weights left the reed
    // 22 dB below the air source. The .045/.32 calibration brings Harmony's raw stem RMS close;
    // the audition retains both stems at their duet's gain so other notes can be judged too.
    private const val AIR_GAIN = .045f
    private const val REED_GAIN = .32f

    fun macrosFor(): List<MacroSpec> = listOf(
        MacroSpec("BREATH", .65f),
        MacroSpec("BOND", 0f),
        MacroSpec("MISCHIEF", .2f),
        MacroSpec("HOLD", .45f),
    )

    fun defaults(): Map<String, Float> = macrosFor().associate { it.name to it.default }

    internal fun settled(macros: Map<String, Float>): Map<String, Float> {
        val result = defaults().toMutableMap()
        for ((name, value) in macros) {
            require(name in result) { "unknown OBBLIGATO macro: $name" }
            require(value.isFinite() && value in 0f..1f) { "OBBLIGATO $name must be finite and in 0..1: $value" }
            result[name] = value
        }
        return result
    }

    internal fun validateNote(midi: Int, velocity: Float) {
        require(midi in MIDI_MIN..MIDI_MAX) { "OBBLIGATO MIDI must be in $MIDI_MIN..$MIDI_MAX: $midi" }
        require(velocity.isFinite() && velocity in 0f..1f) { "OBBLIGATO velocity must be finite and in 0..1: $velocity" }
    }

    fun drumClassFor(): DrumClass = DrumClass.TONAL

    /** Prelevel conditioned taps; muting a tap for audition does not remove its player. */
    data class Report(
        val snip: Snip,
        val air: FloatArray,
        val reed: FloatArray,
        val diagnostics: Map<String, Double>,
        val airEffort: FloatArray,
        val reedEffort: FloatArray,
    )

    private data class Relationship(
        val trade: Double,
        val lag: Double,
        val detune: Double,
        val answerAir: Double,
        val answerReed: Double,
        val airWeight: Double = 1.0,
        val reedWeight: Double = 1.0,
        val swell: Double = .07,
        val callDepth: Double = 0.0,
        val yieldTail: Double = 0.0,
        val fluteLead: Double = 0.0,
        val rivalry: Double = 0.0,
        val irregularity: Double = 0.0,
    ) {
        fun between(other: Relationship, f: Double) = Relationship(
            mix(trade, other.trade, f), mix(lag, other.lag, f), mix(detune, other.detune, f),
            mix(answerAir, other.answerAir, f), mix(answerReed, other.answerReed, f),
            mix(airWeight, other.airWeight, f), mix(reedWeight, other.reedWeight, f),
            mix(swell, other.swell, f),
            mix(callDepth, other.callDepth, f), mix(yieldTail, other.yieldTail, f),
            mix(fluteLead, other.fluteLead, f),
            mix(rivalry, other.rivalry, f),
            mix(irregularity, other.irregularity, f),
        )
    }

    private val relationships = arrayOf(
        Relationship(.00, .015, .0, .10, .10),                   // Harmony: join.
        Relationship(.24, .075, 2.0, .07, .07, callDepth = 1.0), // Play: call, answer, return.
        Relationship(.15, .045, 4.0, .12, .16, swell = .13),    // Dare: follow a stronger gesture.
        Relationship(.40, .12, 11.0, -.16, -.16, swell = .03,
            rivalry = 1.0, irregularity = 1.0),                // Fight: interrupt and counter with unequal gestures.
        Relationship(.03, .04, 2.0, .17, .17, swell = .23),     // Cry: swell together.
        Relationship(.10, .035, 1.0, .11, .11, swell = .11),    // Forgive: reconnect.
        Relationship(.06, .12, .0, -.06, .14, 1.0, 1.02, .04,
            yieldTail = .5, fluteLead = .65),                  // Protect: flute opens, reed carries the ending.
        Relationship(.00, .00, .0, .14, .14, swell = .10),      // Transcend: unified gesture.
    )

    private fun relationship(bond: Float): Relationship {
        val position = bond * (relationships.size - 1)
        val index = floor(position.toDouble()).toInt().coerceAtMost(relationships.lastIndex)
        return relationships[index].between(relationships[minOf(index + 1, relationships.lastIndex)],
            (position - index).toDouble())
    }

    /**
     * Physical source removal is a causal probe. With responses=false the saved gesture and
     * player seeds remain identical, but neither player's measured activity alters the other.
     * This is not a live relationship across pads: each call starts two new source states.
     */
    fun probe(
        macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI,
        velocity: Float = DEFAULT_VELOCITY,
        seed: Int = 0,
        responses: Boolean = true,
        sourceA: Boolean = true,
        sourceB: Boolean = true,
        model: Int = MODEL,
    ): Report {
        require(model in 1..MODEL) { "unsupported OBBLIGATO model: $model" }
        validateNote(midi, velocity)
        val m = settled(macros)
        val breath = m.getValue("BREATH")
        val mischief = m.getValue("MISCHIEF")
        val heldSeconds = 2.0 + m.getValue("HOLD") * 3.0
        val seconds = heldSeconds + RELEASE_SECONDS
        val frames = (seconds * RATE).toInt()
        val traces = (frames + CONTROL_BLOCK - 1) / CONTROL_BLOCK
        val effortAir = FloatArray(traces)
        val effortReed = FloatArray(traces)
        if (breath == 0f || velocity == 0f || (!sourceA && !sourceB)) {
            val out = FloatArray(frames / Dsp.OVERSAMPLE)
            return Report(Snip(out, 1, Dsp.RATE), out.copyOf(), out.copyOf(),
                linkedMapOf("rawPeak" to 0.0, "airRms" to 0.0, "reedRms" to 0.0,
                    "maxResponseAir" to 0.0, "maxResponseReed" to 0.0,
                    "maxPitchCents" to 0.0, "effortTraceRate" to RATE.toDouble() / CONTROL_BLOCK),
                effortAir, effortReed)
        }

        val current = relationship(m.getValue("BOND"))
        val r = when (model) {
            1 -> current.copy(callDepth = 0.0, yieldTail = 0.0, fluteLead = 0.0, rivalry = 0.0, irregularity = 0.0)
            2 -> current.copy(fluteLead = 0.0, rivalry = 0.0, irregularity = 0.0)
            3 -> current.copy(rivalry = 0.0, irregularity = 0.0)
            4 -> current.copy(irregularity = 0.0)
            else -> current
        }
        // A .12-second lag overlapped the reed with the flute's first useful tone.
        // Protect now exposes that tone before the reed joins. Subtracting the same
        // entry delay from its held length keeps the absolute ending unchanged.
        val reedLag = r.lag * (1.0 - r.rivalry) + r.fluteLead
        // Deep pressure trades stopped the self-excited pipes speaking. Fight keeps
        // both captured, with a smaller reciprocal pressure response; its stronger
        // prominence changes happen in overlapping radiation envelopes below.
        val pressureTrade = r.trade * (1.0 - r.rivalry)
        val responseDepth = 1.0 - .75 * r.rivalry
        val hz = Keys.midiHz(midi)
        // Native velocity changes the contact operating point and radiation, not just final gain.
        val contactBreath = (breath * (.8f + .2f * velocity)).coerceIn(0f, 1f)
        val airMacros = Bore.defaults(BoreVoice.FLUTE) + mapOf(
            "BREATH" to contactBreath, "LIP" to .48f, "CHIFF" to .30f, "HOLD" to 0f)
        val reedMacros = Bore.defaults(BoreVoice.SAX) + mapOf(
            "BREATH" to contactBreath, "LIP" to .60f, "CHIFF" to .24f, "HOLD" to 0f)
        val airPlayer = Bore.Player(BoreVoice.FLUTE, Bore.tunedHz(BoreVoice.FLUTE, hz), airMacros,
            maxDetuneCents = MAX_DETUNE_CENTS, noiseSeed = Dsp.seedFor("OBBLIGATO", SOURCE_SEED_MODEL, "AIR", midi, seed))
        val reedPlayer = Bore.Player(BoreVoice.SAX, Bore.tunedHz(BoreVoice.SAX, hz), reedMacros,
            maxDetuneCents = MAX_DETUNE_CENTS, noiseSeed = Dsp.seedFor("OBBLIGATO", SOURCE_SEED_MODEL, "REED", midi, seed))
        val rawAir = FloatArray(frames)
        val rawReed = FloatArray(frames)
        val phases = Dsp.phases(2, Dsp.seedFor("OBBLIGATO", SOURCE_SEED_MODEL, "PERSONALITY", midi, seed))
        val meterCoeff = 1.0 - exp(-1.0 / (.04 * RATE))
        val responseCoeff = 1.0 - exp(-CONTROL_BLOCK / (.09 * RATE))
        var heardAir = 0.0
        var heardReed = 0.0
        var answerAir = 0.0
        var answerReed = 0.0
        var envAir = 0f
        var envReed = 0f
        var pitchAir = 0f
        var pitchReed = 0f
        var maxResponseAir = 0.0
        var maxResponseReed = 0.0
        var maxPitch = 0.0
        var rawPeak = 0.0

        for (frame in 0 until frames) {
            if (frame % CONTROL_BLOCK == 0) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("OBBLIGATO render cancelled")
                val t = frame.toDouble() / RATE
                val phase = (t / heldSeconds).coerceIn(0.0, 1.0)
                val exchange = sin(2.0 * PI * (1.1 * t / heldSeconds))
                val arch = 1.0 - r.swell + r.swell * sin(PI * phase) * sin(PI * phase)
                val targetAir = if (responses) r.answerAir * responseDepth * heardReed else 0.0
                val targetReed = if (responses) r.answerReed * responseDepth * heardAir else 0.0
                answerAir += (targetAir - answerAir) * responseCoeff
                answerReed += (targetReed - answerReed) * responseCoeff
                maxResponseAir = maxOf(maxResponseAir, abs(answerAir))
                maxResponseReed = maxOf(maxResponseReed, abs(answerReed))
                val airGate = gate(t, heldSeconds - r.yieldTail, .13)
                val reedGate = gate(t - reedLag, heldSeconds - reedLag, .17)
                envAir = (airGate * arch * (1.0 - pressureTrade * .5 + pressureTrade * .5 * exchange) * r.airWeight).toFloat()
                envReed = (reedGate * arch * (1.0 - pressureTrade * .5 - pressureTrade * .5 * exchange) * r.reedWeight).toFloat()
                // The requested note remains the centre of the duet's pitch tension.
                val wanderAir = sin(2.0 * PI * (.37 * t + phases[0]))
                val wanderReed = sin(2.0 * PI * (.31 * t + phases[1]))
                pitchAir = (r.detune + mischief * (8.0 + 3.0 * wanderAir)).toFloat()
                pitchReed = (-r.detune - mischief * (8.0 + 3.0 * wanderReed)).toFloat()
                if (r.irregularity > 0.0) {
                    pitchAir += (r.irregularity * rivalPitchPush(t, heldSeconds, air = true)).toFloat()
                    pitchReed += (r.irregularity * rivalPitchPush(t, heldSeconds, air = false)).toFloat()
                }
                maxPitch = maxOf(maxPitch, abs(pitchAir.toDouble()), abs(pitchReed.toDouble()))
                val index = frame / CONTROL_BLOCK
                effortAir[index] = if (sourceA) envAir * (1.0 + answerAir).toFloat() else 0f
                effortReed[index] = if (sourceB) envReed * (1.0 + answerReed).toFloat() else 0f
            }
            val t = frame.toDouble() / RATE
            val a = if (sourceA) airPlayer.tick(envAir, t, pressureScale = (1.0 + answerAir).toFloat(),
                pitchCents = pitchAir) else 0f
            val b = if (sourceB && t >= reedLag) reedPlayer.tick(envReed, t - reedLag, pressureScale = (1.0 + answerReed).toFloat(),
                pitchCents = pitchReed) else 0f
            rawAir[frame] = a
            rawReed[frame] = b
            rawPeak = maxOf(rawPeak, abs(a.toDouble()), abs(b.toDouble()))
            // Meter the actual outgoing pipe pressure, rather than the requested envelope.
            // Both meters read the previous frame before either new response is evaluated.
            // Play's audible phrase envelope leaves mouth pressure in its calibrated
            // speaking range. The other player hears that emphasis after normalization;
            // applying it inside tanh would hide quiet turns behind meter saturation.
            val levelAir = tanh(4.0 * abs(a) / airPlayer.basePressure.coerceAtLeast(1e-6f)) *
                callPresence(t, heldSeconds, r.callDepth, air = true) *
                rivalPresence(t, heldSeconds, r.rivalry, r.irregularity, air = true)
            val levelReed = tanh(4.0 * abs(b) / reedPlayer.basePressure.coerceAtLeast(1e-6f)) *
                callPresence(t, heldSeconds, r.callDepth, air = false) *
                rivalPresence(t, heldSeconds, r.rivalry, r.irregularity, air = false)
            heardAir += (levelAir - heardAir) * meterCoeff
            heardReed += (levelReed - heardReed) * meterCoeff
        }

        val air = Bore.condition(rawAir, RATE, 0f, 0f, Bore.Voicing.NONE)
        val reed = Bore.condition(rawReed, RATE, Bore.biteBoostDb(BoreVoice.SAX, .60f),
            Bore.raspAmount(BoreVoice.SAX, .60f, contactBreath), Bore.voicingFor(BoreVoice.SAX))
        val inputEnergy = sqrt(breath) * velocity
        for (i in air.indices) {
            air[i] *= AIR_GAIN * inputEnergy
            reed[i] *= REED_GAIN * inputEnergy
            if (r.callDepth > 0.0) {
                val t = i.toDouble() / Dsp.RATE
                air[i] *= callPresence(t, heldSeconds, r.callDepth, air = true).toFloat()
                reed[i] *= callPresence(t, heldSeconds, r.callDepth, air = false).toFloat()
            }
            if (r.fluteLead > 0.0) {
                // Conditioning can put a small DC-removal transient before a late
                // source entry. Expose the flute alone, then admit the reed smoothly.
                // Weight by the interpolated lead depth so BOND stays continuous.
                val t = i.toDouble() / Dsp.RATE
                val phase = ((t - reedLag) / .10).coerceIn(0.0, 1.0)
                val entry = .5 - .5 * cos(PI * phase)
                reed[i] *= (1.0 - r.fluteLead / .65 * (1.0 - entry)).toFloat()
            }
            if (r.rivalry > 0.0) {
                val t = i.toDouble() / Dsp.RATE
                air[i] *= rivalPresence(t, heldSeconds, r.rivalry, r.irregularity, air = true).toFloat()
                reed[i] *= rivalPresence(t, heldSeconds, r.rivalry, r.irregularity, air = false).toFloat()
            }
        }
        Dsp.fadeTail(air)
        Dsp.fadeTail(reed)
        val out = FloatArray(air.size) { air[it] + reed[it] }
        val diagnostics = linkedMapOf(
            "rawPeak" to rawPeak,
            "airRms" to rms(air), "reedRms" to rms(reed),
            "maxResponseAir" to maxResponseAir, "maxResponseReed" to maxResponseReed,
            "maxPitchCents" to maxPitch,
            "effortTraceRate" to RATE.toDouble() / CONTROL_BLOCK,
        )
        return Report(Snip(out, 1, Dsp.RATE), air, reed, diagnostics, effortAir, effortReed)
    }

    fun render(macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
               velocity: Float = DEFAULT_VELOCITY, seed: Int = 0, model: Int = MODEL): Snip {
        val report = probe(macros, midi, velocity, seed, model = model)
        val output = report.snip.samples.copyOf()
        Dsp.levelTo(output, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        return Snip(output, 1, Dsp.RATE)
    }

    /** Two raised-cosine handoffs: air calls, reed answers, air returns. */
    private fun callPresence(t: Double, hold: Double, depth: Double, air: Boolean): Double {
        if (depth == 0.0) return 1.0
        fun handoff(center: Double): Double {
            val phase = ((t - center + .11) / .22).coerceIn(0.0, 1.0)
            return .5 - .5 * cos(PI * phase)
        }
        val airTurn = 1.0 - handoff(hold * .35) + handoff(hold * .70)
        return 1.0 - .82 * depth * if (air) 1.0 - airTurn else airTurn
    }

    /** Keep legacy sine rivalry; model 5 spends less time in a fused, balanced duet. */
    private fun rivalPresence(t: Double, hold: Double, depth: Double, irregularity: Double, air: Boolean): Double {
        if (depth == 0.0) return 1.0
        val contest = sin(2.0 * PI * 3.0 * t / hold)
        val share = .5 + .5 * if (air) contest else -contest
        val floorPower = .35 * .35
        var target = sqrt(floorPower + (1.0 - floorPower) * share)
        if (irregularity > 0.0) {
            var airShare = 1.0
            for (i in FIGHT_ENTRIES.indices) {
                val width = minOf(FIGHT_CROSSOVERS[i], hold * .055)
                val phase = (t - FIGHT_ENTRIES[i] * hold + width * .5) / width
                val change = when {
                    phase <= 0.0 -> 0.0
                    phase >= 1.0 -> 1.0
                    else -> .5 - .5 * cos(PI * phase)
                }
                airShare += if (i % 2 == 0) -change else change
            }
            val challengedShare = (if (air) airShare else 1.0 - airShare).coerceIn(0.0, 1.0)
            val quietPower = .18 * .18
            val challenged = sqrt(quietPower + (1.0 - quietPower) * challengedShare)
            target = mix(target, challenged, irregularity)
        }
        return mix(1.0, target, depth)
    }

    /** A player's own brief pitch push follows its entry, rather than a common pitch LFO. */
    private fun rivalPitchPush(t: Double, hold: Double, air: Boolean): Double {
        val length = minOf(.38, hold * .13)
        fun push(entry: Double): Double {
            val phase = (t - entry) / length
            if (phase <= 0.0 || phase >= 1.0) return 0.0
            val curve = sin(PI * phase)
            return curve * curve
        }
        var amount = if (air) push(0.0) else 0.0
        for (i in FIGHT_ENTRIES.indices) {
            val airEntry = i % 2 == 1
            if (air == airEntry) amount += push(FIGHT_ENTRIES[i] * hold)
        }
        return (if (air) 5.0 else -5.0) * amount
    }

    private fun gate(t: Double, hold: Double, attack: Double): Double = when {
        t <= 0.0 -> 0.0
        t < attack -> .5 - .5 * cos(PI * t / attack)
        t < hold -> 1.0
        t < hold + RELEASE_SECONDS -> .5 + .5 * cos(PI * (t - hold) / RELEASE_SECONDS)
        else -> 0.0
    }

    private fun mix(a: Double, b: Double, f: Double): Double = a + (b - a) * f
    private fun rms(x: FloatArray): Double = sqrt(x.sumOf { it.toDouble() * it } / x.size.coerceAtLeast(1))
}
