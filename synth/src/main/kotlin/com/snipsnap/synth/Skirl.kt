package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.random.Random

enum class SkirlVoice { ROLL, WHIRR }

/**
 * SKIRL model 1: knock -> flutter -> whirr -> bounded shudder -> pitched decay.
 *
 * A constraint-eliminated ideal cylinder supplies contact and body-frame rim
 * phases, reaction forces and negative resistance work. A one-way acoustic
 * readout spends a bounded proxy of that work in six disk and five support
 * modes. Fine surface detail follows the same contact phase as the coarse
 * feature; it has no independent accelerating clock. The final shudder is an
 * authored finite transition, not a prediction of real multi-point impact.
 *
 * The two voice-specific macro domains were calibrated against the Python
 * rigid-body reference. COLLAPSE maps into a declared launch-dependent cutoff
 * band; it never repairs a failed trajectory after the fact. Physical state
 * is cached independently of pitch, EDGE, PLATE and acoustic velocity.
 *
 * Geometry, resistance, friction and acoustic material tables are design
 * choices. The owner liked the Python audition; this Kotlin port awaits its
 * own listen. HOLD is reserved and nonzero values are refused in model 1.
 */
object Skirl {
    const val RATE = Dsp.RATE
    const val MODEL_VERSION = 1
    const val DEFAULT_MIDI = 48
    const val MIDI_MIN = 36
    const val MIDI_MAX = 84
    const val TUNE_SEMITONES = 24

    fun macrosFor(voice: SkirlVoice): List<MacroSpec> {
        val lean = if (voice == SkirlVoice.ROLL) .55f else 0f
        val brake = if (voice == SkirlVoice.ROLL) .40f else .20f
        val collapse = if (voice == SkirlVoice.ROLL) .65f else 1f
        return listOf(
            MacroSpec("TUNE", .50f, .50f), MacroSpec("LEAN", lean, lean),
            MacroSpec("BRAKE", brake, brake), MacroSpec("EDGE", .40f, .40f),
            MacroSpec("PLATE", .50f, .50f), MacroSpec("COLLAPSE", collapse, collapse),
            MacroSpec("HOLD", 0f, 0f),
        )
    }

    fun defaults(voice: SkirlVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    internal fun settled(voice: SkirlVoice, macros: Map<String, Float>): Map<String, Float> =
        defaults(voice).mapValues { (key, default) ->
            macros[key]?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: default
        }

    private fun requireFiniteGesture(macros: Map<String, Float>) {
        require(macros.getValue("HOLD") == 0f) {
            "SKIRL model 1 is a finite rolling gesture; HOLD must be 0."
        }
    }

    fun scramble(voice: SkirlVoice, random: Random = Random.Default,
        temperature: Float = .35f, near: Patch? = null): Map<String, Float> =
        Dsp.scrambleNear(settled(voice, if (near is SkirlPatch) near.macros else emptyMap()),
            temperature.takeIf { it.isFinite() } ?: .35f, random).toMutableMap().also {
            it["HOLD"] = 0f
        }

    fun midiFor(@Suppress("UNUSED_PARAMETER") voice: SkirlVoice,
        tune: Float, midi: Int = DEFAULT_MIDI): Int {
        val finiteTune = tune.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .5f
        return (midi + ((finiteTune - .5f) * TUNE_SEMITONES).roundToInt())
            .coerceIn(MIDI_MIN, MIDI_MAX)
    }

    fun frequencyFor(midi: Int): Float = Keys.midiHz(midi)
    fun frequencyFor(voice: SkirlVoice, tune: Float, midi: Int = DEFAULT_MIDI): Float =
        frequencyFor(midiFor(voice, tune, midi))

    /** Finite pitched intent overrides the generic classifier's duration-based LOOP bucket. */
    fun drumClassFor(voice: SkirlVoice, macros: Map<String, Float> = emptyMap()): DrumClass {
        requireFiniteGesture(settled(voice, macros))
        return DrumClass.TONAL
    }

    /**
     * These are calibrated musical domains, not the old handoff's independent
     * 8..35 degree launch / 7..1 degree exit cube. The reference grid checked
     * every five-step combination plus off-grid probes with fixed time scale 1.
     */
    internal fun parametersFor(voice: SkirlVoice,
        macros: Map<String, Float> = emptyMap()): SkirlDynamics.Parameters {
        val m = settled(voice, macros)
        requireFiniteGesture(m)
        val lean = m.getValue("LEAN").toDouble()
        val brake = m.getValue("BRAKE").toDouble()
        val collapse = m.getValue("COLLAPSE").toDouble()
        val angle: Double
        val resistance: Double
        val terminal: Double
        when (voice) {
            SkirlVoice.ROLL -> {
                angle = 18.0 + 8.0 * lean
                resistance = .006 + .004 * brake
                terminal = .42 * angle - 1.1 + 2.0 * (1.0 - collapse)
            }
            SkirlVoice.WHIRR -> {
                angle = 8.0 + 4.0 * lean
                resistance = .004 + .002 * brake
                terminal = .42 * angle - 1.4 + 1.2 * (1.0 - collapse)
            }
        }
        return SkirlDynamics.Parameters(leanAlphaRad = angle * PI / 180.0,
            muRoll = resistance, terminalAlphaRad = terminal * PI / 180.0)
    }

    internal data class Probe(
        val snip: Snip,
        /** Conditioned output-rate samples before shared loudness leveling. */
        val raw: FloatArray,
        val motion: SkirlDynamics.Trace,
        val acoustics: SkirlAcoustics.Diagnostics,
        val preparationNanos: Long,
        val synthesisNanos: Long,
        val outputNanos: Long,
    )

    fun render(voice: SkirlVoice, macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI, velocity: Float = 1f): Snip =
        play(voice, macros, midi, velocity, record = false).snip

    internal fun inspect(voice: SkirlVoice, macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI, velocity: Float = 1f): Probe =
        play(voice, macros, midi, velocity, record = true)

    /** A diagnosed research fixture; physical overrides are never production patch fields. */
    internal fun inspectReference(voice: SkirlVoice, physical: SkirlDynamics.Parameters,
        macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f): Probe = play(voice, macros, midi, velocity, true, physical)

    private fun stopIfInterrupted() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("SKIRL render cancelled")
    }

    private fun play(voice: SkirlVoice, macros: Map<String, Float>, midi: Int,
        velocity: Float, record: Boolean, physical: SkirlDynamics.Parameters? = null): Probe {
        stopIfInterrupted()
        require(midi in MIDI_MIN..MIDI_MAX) { "SKIRL MIDI must be in $MIDI_MIN..$MIDI_MAX" }
        require(velocity.isFinite() && velocity in 0f..1f) { "SKIRL velocity must be finite and in 0..1" }
        val m = settled(voice, macros)
        requireFiniteGesture(m)
        val began = System.nanoTime()
        val trace = SkirlDynamics.trace(physical ?: parametersFor(voice, m))
        check(physical != null || trace.exit == SkirlDynamics.Exit.TERMINAL) {
            "SKIRL rolling regime ended at ${trace.reason}; no complete finite runout."
        }
        val prepared = System.nanoTime()
        stopIfInterrupted()
        // One seeded periodic rim map fixes acoustic polarity at contact points.
        // Transposing or touching material does not rotate that surface or
        // invent a new mechanical contact history.
        val seed = Dsp.seedFor("SKIRL", MODEL_VERSION, voice.name)
        val result = SkirlAcoustics.render(trace,
            frequencyFor(voice, m.getValue("TUNE"), midi).toDouble(),
            m.getValue("EDGE"), m.getValue("PLATE"), m.getValue("COLLAPSE"), velocity,
            seed, fineTexture = true, signedTexture = true)
        check(result.raw.all { it.isFinite() }) { "SKIRL acoustic state became nonfinite" }
        val synthesized = System.nanoTime()
        stopIfInterrupted()
        val out = condition(result.raw)
        val raw = if (record) out.copyOf() else FloatArray(0)
        Dsp.levelTo(out, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        stopIfInterrupted()
        return Probe(Snip(out, channels = 1, sampleRate = RATE), raw, trace,
            result.diagnostics, prepared - began, synthesized - prepared,
            System.nanoTime() - synthesized)
    }

    private fun condition(raw: FloatArray): FloatArray {
        Tide.bandLimit(raw, RATE * Dsp.OVERSAMPLE)
        val out = Dsp.decimate(raw, RATE)
        val mean = out.sumOf { it.toDouble() } / out.size.coerceAtLeast(1)
        val hp = Dsp.OnePole(RATE)
        for (i in out.indices) {
            val x = out[i] - mean.toFloat()
            out[i] = x - hp.lp(x, 20f)
        }
        return out
    }
}
