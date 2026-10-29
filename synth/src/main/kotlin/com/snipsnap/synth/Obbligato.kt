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
 * An air jet and a reed on independent tuned pipes.
 * A synthetic player controller coordinates effort, entry, and pitch, and lets each player
 * respond to the other pipe's measured activity. This is an invented duet, not a model of
 * two people sharing one lung or a claim of acoustic accuracy for their mutual influence.
 *
 * Dry mono, explicit C4–C5 notes, independently seeded players and one final loudness target.
 * HOLD extends a finite gesture, including at 1. It does not yet claim a settled sustain loop.
 * Model 5 is accepted. Model 6 adds activity-driven Fight entries. Model 7 is an explicit
 * gentler-voiced listening revision. Model 8 adds remembered articulations for Play,
 * Fight and Protect. Model 9 lets an answer join before the outgoing phrase yields.
 * These listening revisions remain opt-in.
 */
object Obbligato {
    /** The accepted sound stays the default until the responsive experiment is heard. */
    const val MODEL = 5
    const val EXPERIMENTAL_MODEL = 6
    const val MUSICAL_MODEL = 7
    const val CONVERSATION_MODEL = 8
    const val OVERLAP_MODEL = 9
    const val MAX_MODEL = OVERLAP_MODEL
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
    // Less rasp compresses the reed less: at the reference note its raw RMS nearly
    // doubles. One fixed radiation weight restores the duet's balance for this voicing;
    // every note and stem keeps it, rather than being normalized independently.
    private const val MUSICAL_REED_GAIN = .17f

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
        val interactionEvents: List<ObbligatoInteraction.Event> = emptyList(),
        val conversationEvents: List<ObbligatoConversation.Event> = emptyList(),
    )

    /** A physical pressure intervention used only for causal listening and tests. */
    internal data class SourceWeakening(
        val air: Boolean,
        val startSeconds: Double,
        val endSeconds: Double,
        val strength: Float = .1f,
    ) {
        init {
            require(startSeconds.isFinite() && startSeconds >= 0.0)
            require(endSeconds.isFinite() && endSeconds > startSeconds)
            require(strength.isFinite() && strength in 0f..1f)
        }

        fun scaleAt(seconds: Double): Float {
            if (seconds <= startSeconds || seconds >= endSeconds) return 1f
            val ramp = minOf(.01, (endSeconds - startSeconds) * .25)
            val edge = minOf((seconds - startSeconds) / ramp, (endSeconds - seconds) / ramp).coerceIn(0.0, 1.0)
            val amount = .5 - .5 * cos(PI * edge)
            return (1.0 - (1.0 - strength) * amount).toFloat()
        }
    }

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
     * Physical source removal is a causal probe. For models 1–5, responses=false keeps the
     * saved timed gesture and player seeds but removes reciprocal pressure response. In
     * models 6–8 it also disables activity-driven answers and partner memory. Each
     * player's independent opening remains, providing an exact survivor negative control.
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
    ): Report = requireNotNull(synthesize(macros, midi, velocity, seed, responses, sourceA, sourceB, model,
        capture = true).report)

    /** The surviving source must not change under this intervention when responses are disabled. */
    internal fun probeWithWeakening(
        macros: Map<String, Float>,
        midi: Int = DEFAULT_MIDI,
        velocity: Float = DEFAULT_VELOCITY,
        seed: Int = 0,
        responses: Boolean = true,
        model: Int = EXPERIMENTAL_MODEL,
        weakening: SourceWeakening,
    ): Report = requireNotNull(synthesize(macros, midi, velocity, seed, responses, true, true, model,
        capture = true, weakening = weakening).report)

    /** Production rendering keeps the same synthesis arithmetic without retaining an audition report. */
    private data class Synthesis(val samples: FloatArray, val report: Report? = null)
    private val EMPTY_TRACE = FloatArray(0)

    private fun synthesize(
        macros: Map<String, Float>, midi: Int, velocity: Float, seed: Int,
        responses: Boolean, sourceA: Boolean, sourceB: Boolean, model: Int, capture: Boolean,
        weakening: SourceWeakening? = null,
    ): Synthesis {
        require(model in 1..MAX_MODEL) { "unsupported OBBLIGATO model: $model" }
        validateNote(midi, velocity)
        val m = settled(macros)
        val breath = m.getValue("BREATH")
        val mischief = m.getValue("MISCHIEF")
        val heldSeconds = 2.0 + m.getValue("HOLD") * 3.0
        val seconds = heldSeconds + RELEASE_SECONDS
        val frames = (seconds * RATE).toInt()
        val traces = (frames + CONTROL_BLOCK - 1) / CONTROL_BLOCK
        val effortAir = if (capture) FloatArray(traces) else EMPTY_TRACE
        val effortReed = if (capture) FloatArray(traces) else EMPTY_TRACE
        if (breath == 0f || velocity == 0f || (!sourceA && !sourceB)) {
            val out = FloatArray(frames / Dsp.OVERSAMPLE)
            return Synthesis(out, if (capture) Report(Snip(out, 1, Dsp.RATE), out.copyOf(), out.copyOf(),
                linkedMapOf("rawPeak" to 0.0, "airRms" to 0.0, "reedRms" to 0.0,
                    "maxResponseAir" to 0.0, "maxResponseReed" to 0.0,
                    "maxPitchCents" to 0.0, "effortTraceRate" to RATE.toDouble() / CONTROL_BLOCK),
                effortAir, effortReed) else null)
        }

        val current = relationship(m.getValue("BOND"))
        val r = when (model) {
            1 -> current.copy(callDepth = 0.0, yieldTail = 0.0, fluteLead = 0.0, rivalry = 0.0, irregularity = 0.0)
            2 -> current.copy(fluteLead = 0.0, rivalry = 0.0, irregularity = 0.0)
            3 -> current.copy(rivalry = 0.0, irregularity = 0.0)
            4 -> current.copy(irregularity = 0.0)
            else -> current
        }
        val conversationMode = if (model >= CONVERSATION_MODEL) when {
            r.callDepth > 0.0 -> ObbligatoConversation.Mode.PLAY
            r.rivalry > 0.0 -> ObbligatoConversation.Mode.FIGHT
            r.fluteLead > 0.0 -> ObbligatoConversation.Mode.PROTECT
            else -> null
        } else null
        val conversationDepth = when (conversationMode) {
            ObbligatoConversation.Mode.PLAY -> r.callDepth
            ObbligatoConversation.Mode.FIGHT -> r.rivalry
            ObbligatoConversation.Mode.PROTECT -> r.fluteLead / .65
            null -> 0.0
        }.coerceIn(0.0, 1.0)
        val conversation = conversationMode?.let { ObbligatoConversation(it, heldSeconds, mischief, seed,
            overlap = model == OVERLAP_MODEL) }
        val interaction = if (conversation == null && model >= EXPERIMENTAL_MODEL && r.rivalry > 0.0)
            ObbligatoInteraction(heldSeconds, mischief, seed) else null
        // A .12-second lag overlapped the reed with the flute's first useful tone.
        // Protect now exposes that tone before the reed joins. Subtracting the same
        // entry delay from its held length keeps the absolute ending unchanged.
        val reedLag = r.lag * (1.0 - r.rivalry) + r.fluteLead
        // Deep pressure trades stopped the self-excited pipes speaking. Fight keeps
        // both captured, with a smaller reciprocal pressure response; its stronger
        // prominence changes happen in overlapping radiation envelopes below.
        val legacyPressureTrade = r.trade * (1.0 - r.rivalry)
        val pressureTrade = if (conversationMode == ObbligatoConversation.Mode.PLAY)
            legacyPressureTrade * (1.0 - conversationDepth) else legacyPressureTrade
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
        var pressureAir = 1f
        var pressureReed = 1f
        val meterPressureAir = airPlayer.basePressure.coerceAtLeast(1e-6f)
        val meterPressureReed = reedPlayer.basePressure.coerceAtLeast(1e-6f)
        val presence = Presence()

        for (frame in 0 until frames) {
            if (frame % CONTROL_BLOCK == 0) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("OBBLIGATO render cancelled")
                val t = frame.toDouble() / RATE
                // Every decision reads the previous complete source-activity snapshot.
                // The two bores retain stable mouth pressure throughout a prominence trade.
                if (frame > 0) interaction?.step(CONTROL_BLOCK.toDouble() / RATE, heardAir, heardReed, responses)
                if (frame > 0) conversation?.step(CONTROL_BLOCK.toDouble() / RATE, heardAir, heardReed, responses)
                val phase = (t / heldSeconds).coerceIn(0.0, 1.0)
                val exchange = sin(2.0 * PI * (1.1 * t / heldSeconds))
                val arch = 1.0 - r.swell + r.swell * sin(PI * phase) * sin(PI * phase)
                val targetAir = if (responses) r.answerAir * responseDepth * heardReed else 0.0
                val targetReed = if (responses) r.answerReed * responseDepth * heardAir else 0.0
                answerAir += (targetAir - answerAir) * responseCoeff
                answerReed += (targetReed - answerReed) * responseCoeff
                pressureAir = (1.0 + answerAir).toFloat()
                pressureReed = (1.0 + answerReed).toFloat()
                if (conversation != null) {
                    pressureAir *= mix(1.0, conversation.airPressure, conversationDepth).toFloat()
                    pressureReed *= mix(1.0, conversation.reedPressure, conversationDepth).toFloat()
                }
                if (capture) {
                    maxResponseAir = maxOf(maxResponseAir, abs(answerAir))
                    maxResponseReed = maxOf(maxResponseReed, abs(answerReed))
                }
                val airGate = gate(t, heldSeconds - r.yieldTail, .13)
                val reedGate = gate(t - reedLag, heldSeconds - reedLag, .17)
                envAir = (airGate * arch * (1.0 - pressureTrade * .5 + pressureTrade * .5 * exchange) * r.airWeight).toFloat()
                envReed = (reedGate * arch * (1.0 - pressureTrade * .5 - pressureTrade * .5 * exchange) * r.reedWeight).toFloat()
                // The requested note remains the centre of the duet's pitch tension.
                val wanderAir = sin(2.0 * PI * (.37 * t + phases[0]))
                val wanderReed = sin(2.0 * PI * (.31 * t + phases[1]))
                pitchAir = (r.detune + mischief * (8.0 + 3.0 * wanderAir)).toFloat()
                pitchReed = (-r.detune - mischief * (8.0 + 3.0 * wanderReed)).toFloat()
                if (r.irregularity > 0.0 && conversation == null) {
                    pitchAir += (r.irregularity * (interaction?.airPitchPush
                        ?: rivalPitchPush(t, heldSeconds, air = true))).toFloat()
                    pitchReed += (r.irregularity * (interaction?.reedPitchPush
                        ?: rivalPitchPush(t, heldSeconds, air = false))).toFloat()
                }
                // Keep the same conversational inflections, closer to the requested note.
                // The prior opposing offsets produced about 24 cents of separation at
                // default Fight, so upper harmonics beat faster than the phrase itself.
                if (model >= MUSICAL_MODEL) {
                    pitchAir *= .25f
                    pitchReed *= .25f
                }
                if (conversation != null) {
                    pitchAir += (conversation.airPitch * conversationDepth).toFloat()
                    pitchReed += (conversation.reedPitch * conversationDepth).toFloat()
                }
                if (capture) {
                    maxPitch = maxOf(maxPitch, abs(pitchAir.toDouble()), abs(pitchReed.toDouble()))
                    val index = frame / CONTROL_BLOCK
                    effortAir[index] = if (sourceA) envAir * pressureAir *
                        (if (weakening?.air == true) weakening.scaleAt(t) else 1f) else 0f
                    effortReed[index] = if (sourceB) envReed * pressureReed *
                        (if (weakening?.air == false) weakening.scaleAt(t) else 1f) else 0f
                }
            }
            val t = frame.toDouble() / RATE
            val airPressure = if (weakening?.air == true) pressureAir * weakening.scaleAt(t) else pressureAir
            val reedPressure = if (weakening?.air == false) pressureReed * weakening.scaleAt(t) else pressureReed
            val a = if (sourceA) {
                if (conversation == null) airPlayer.tick(envAir, t, pressureScale = airPressure, pitchCents = pitchAir)
                else airPlayer.tick(envAir, t, pressureScale = airPressure, pitchCents = pitchAir,
                    turbulenceScale = mix(1.0, conversation.airTurbulence, conversationDepth).toFloat())
            } else 0f
            val b = if (sourceB && t >= reedLag) {
                if (conversation == null) reedPlayer.tick(envReed, t - reedLag, pressureScale = reedPressure, pitchCents = pitchReed)
                else reedPlayer.tick(envReed, t - reedLag, pressureScale = reedPressure, pitchCents = pitchReed,
                    turbulenceScale = mix(1.0, conversation.reedTurbulence, conversationDepth).toFloat())
            } else 0f
            rawAir[frame] = a
            rawReed[frame] = b
            if (capture) rawPeak = maxOf(rawPeak, abs(a.toDouble()), abs(b.toDouble()))
            // Meter the actual outgoing pipe pressure, rather than the requested envelope.
            // Both meters read the previous frame before either new response is evaluated.
            // Play's audible phrase envelope leaves mouth pressure in its calibrated
            // speaking range. The other player hears that emphasis after normalization;
            // applying it inside tanh would hide quiet turns behind meter saturation.
            if (responses) {
                val levelAir: Double
                val levelReed: Double
                if (conversation == null) {
                    presence.update(t, heldSeconds, r.callDepth,
                        if (interaction == null) r.rivalry else 0.0, r.irregularity)
                    val rivalAir = if (interaction == null) presence.rivalAir else mix(1.0, interaction.airPresence, r.rivalry)
                    val rivalReed = if (interaction == null) presence.rivalReed else mix(1.0, interaction.reedPresence, r.rivalry)
                    levelAir = tanh(4.0 * abs(a) / meterPressureAir) * presence.callAir * rivalAir
                    levelReed = tanh(4.0 * abs(b) / meterPressureReed) * presence.callReed * rivalReed
                } else {
                    levelAir = tanh(4.0 * abs(a) / meterPressureAir) * mix(1.0, conversation.airPresence, conversationDepth)
                    levelReed = tanh(4.0 * abs(b) / meterPressureReed) * mix(1.0, conversation.reedPresence, conversationDepth)
                }
                heardAir += (levelAir - heardAir) * meterCoeff
                heardReed += (levelReed - heardReed) * meterCoeff
            }
        }

        val air = Bore.condition(rawAir, RATE, 0f, 0f, Bore.Voicing.NONE)
        // The standalone sax's deliberately raspy radiation is too forceful for a warm
        // duet. Change the audible reed, leaving its calibrated mouth pressure and pipe
        // untouched. These restrained voicing choices still require a listening verdict.
        val reed = if (model >= MUSICAL_MODEL) {
            Bore.condition(rawReed, RATE, 2f, .06f, Bore.Voicing(-4f, 3f))
        } else {
            Bore.condition(rawReed, RATE, Bore.biteBoostDb(BoreVoice.SAX, .60f),
                Bore.raspAmount(BoreVoice.SAX, .60f, contactBreath), Bore.voicingFor(BoreVoice.SAX))
        }
        val inputEnergy = sqrt(breath) * velocity
        val reactiveRadiation = interaction?.radiationReader()
        val conversationRadiation = conversation?.performanceReader()
        val airTone = if (conversation != null) Dsp.FixedOnePole(hz * 2.5f, Dsp.RATE) else null
        val reedTone = if (conversation != null) Dsp.FixedOnePole(hz * 2.5f, Dsp.RATE) else null
        for (i in air.indices) {
            air[i] *= AIR_GAIN * inputEnergy
            reed[i] *= (if (model >= MUSICAL_MODEL) MUSICAL_REED_GAIN else REED_GAIN) * inputEnergy
            if (conversationRadiation != null) {
                conversationRadiation.at(i.toDouble() / Dsp.RATE)
                // A fixed, gentle split changes the upper partials with the actual
                // articulation. It cannot add highs beyond the gentler source voicing.
                val lowAir = requireNotNull(airTone).lp(air[i])
                val lowReed = requireNotNull(reedTone).lp(reed[i])
                val airTop = mix(1.0, .45 + .55 * conversationRadiation.airTone, conversationDepth).toFloat()
                val reedTop = mix(1.0, .45 + .55 * conversationRadiation.reedTone, conversationDepth).toFloat()
                air[i] = (lowAir + (air[i] - lowAir) * airTop) *
                    mix(1.0, conversationRadiation.airPresence, conversationDepth).toFloat()
                reed[i] = (lowReed + (reed[i] - lowReed) * reedTop) *
                    mix(1.0, conversationRadiation.reedPresence, conversationDepth).toFloat()
            }
            if (conversation == null && (r.callDepth > 0.0 || r.rivalry > 0.0)) {
                presence.update(i.toDouble() / Dsp.RATE, heldSeconds, r.callDepth,
                    if (interaction == null) r.rivalry else 0.0, r.irregularity)
            }
            if (r.callDepth > 0.0 && conversation == null) {
                air[i] *= presence.callAir.toFloat()
                reed[i] *= presence.callReed.toFloat()
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
            if (r.rivalry > 0.0 && conversation == null) {
                if (reactiveRadiation == null) {
                    air[i] *= presence.rivalAir.toFloat()
                    reed[i] *= presence.rivalReed.toFloat()
                } else {
                    // Reconstruct each actual entry's smooth curve at audio rate. This
                    // avoids a control-rate staircase after the anti-aliasing filters.
                    reactiveRadiation.at(i.toDouble() / Dsp.RATE)
                    air[i] *= mix(1.0, reactiveRadiation.air, r.rivalry).toFloat()
                    reed[i] *= mix(1.0, reactiveRadiation.reed, r.rivalry).toFloat()
                }
            }
        }
        Dsp.fadeTail(air)
        Dsp.fadeTail(reed)
        // Report taps must remain additive. Production owns both private conditioned stems,
        // so its sum can reuse the air buffer without changing a sample or copying the mix.
        if (!capture) {
            for (i in air.indices) air[i] += reed[i]
            return Synthesis(air)
        }
        val out = FloatArray(air.size) { air[it] + reed[it] }
        val diagnostics = linkedMapOf(
            "rawPeak" to rawPeak,
            "airRms" to rms(air), "reedRms" to rms(reed),
            "maxResponseAir" to maxResponseAir, "maxResponseReed" to maxResponseReed,
            "maxPitchCents" to maxPitch,
            "effortTraceRate" to RATE.toDouble() / CONTROL_BLOCK,
        )
        return Synthesis(out, Report(Snip(out, 1, Dsp.RATE), air, reed, diagnostics, effortAir, effortReed,
            interaction?.events?.toList() ?: emptyList(), conversation?.events?.toList() ?: emptyList()))
    }

    fun render(macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
               velocity: Float = DEFAULT_VELOCITY, seed: Int = 0, model: Int = MODEL): Snip {
        val output = synthesize(macros, midi, velocity, seed, responses = true, sourceA = true,
            sourceB = true, model = model, capture = false).samples
        Dsp.levelTo(output, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        return Snip(output, 1, Dsp.RATE)
    }

    /**
     * Evaluate each phrase once for both players. Their final operation order is kept:
     * complementary Double shares are not reconstructed from an already rounded gain,
     * and the model-four/model-five mix still performs its original cancellation.
     */
    private class Presence {
        var callAir = 1.0
        var callReed = 1.0
        var rivalAir = 1.0
        var rivalReed = 1.0

        fun update(t: Double, hold: Double, callDepth: Double, rivalry: Double, irregularity: Double) {
            if (callDepth == 0.0) {
                callAir = 1.0
                callReed = 1.0
            } else {
                fun handoff(center: Double): Double {
                    val phase = ((t - center + .11) / .22).coerceIn(0.0, 1.0)
                    return .5 - .5 * cos(PI * phase)
                }
                val airTurn = 1.0 - handoff(hold * .35) + handoff(hold * .70)
                callAir = 1.0 - .82 * callDepth * (1.0 - airTurn)
                callReed = 1.0 - .82 * callDepth * airTurn
            }
            if (rivalry == 0.0) {
                rivalAir = 1.0
                rivalReed = 1.0
                return
            }
            val contest = sin(2.0 * PI * 3.0 * t / hold)
            val airShare = .5 + .5 * contest
            val reedShare = .5 + .5 * -contest
            val floorPower = .35 * .35
            var targetAir = sqrt(floorPower + (1.0 - floorPower) * airShare)
            var targetReed = sqrt(floorPower + (1.0 - floorPower) * reedShare)
            if (irregularity > 0.0) {
                var challengedAirShare = 1.0
                for (i in FIGHT_ENTRIES.indices) {
                    val width = minOf(FIGHT_CROSSOVERS[i], hold * .055)
                    val phase = (t - FIGHT_ENTRIES[i] * hold + width * .5) / width
                    val change = when {
                        phase <= 0.0 -> 0.0
                        phase >= 1.0 -> 1.0
                        else -> .5 - .5 * cos(PI * phase)
                    }
                    challengedAirShare += if (i % 2 == 0) -change else change
                }
                val quietPower = .18 * .18
                val challengedAir = sqrt(quietPower + (1.0 - quietPower) * challengedAirShare.coerceIn(0.0, 1.0))
                val challengedReed = sqrt(quietPower + (1.0 - quietPower) * (1.0 - challengedAirShare).coerceIn(0.0, 1.0))
                targetAir = mix(targetAir, challengedAir, irregularity)
                targetReed = mix(targetReed, challengedReed, irregularity)
            }
            rivalAir = mix(1.0, targetAir, rivalry)
            rivalReed = mix(1.0, targetReed, rivalry)
        }
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
