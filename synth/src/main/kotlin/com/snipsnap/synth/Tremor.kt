package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip

/**
 * TREMOR — a tuned hide drum, up to four coordinated strikers, loose beads on an internal tray,
 * and a six-string cage with a powered pickup/actuator and load faults.
 *
 * Pitched percussion, not a melodic pad that had its attack removed. One-shots are kept under the
 * classifier's 1.5 s LOOP line (`Classifier`'s own rule) so a hit can still file TOM or PERC; HOLD
 * is the sustained region and files LOOP by that same length rule. See
 * `docs/superpowers/specs/2026-10-04-tremor-engine-design.md`.
 *
 * The sequence is the instrument: skin onset, beads scatter and return, the cage rings, the
 * circuit blooms and faults, then everything passive settles. Constants marked shape are a first
 * design, not measured hide or a claim about a real circuit.
 *
 * Endpoints, so the knobs do not go dead:
 * - CURRENT 0 leaves the strings passive. The actuator and the fuzz path are exactly zero, so a
 *   fault cannot inject a powered burst. FAULT still raises loop loss while it is loaded.
 * - CAGE 0 keeps a small radiation path ([CAGE_FLOOR]) so CURRENT and FAULT still have something
 *   to act on.
 * - BEADS 0 still places a few beads, so the bottom of the knob is quiet contact rather than a
 *   disconnected layer.
 */
enum class TremorVoice { HIDE, UNISON, ROLL, WIRE, CHARGE, FRACTURE }

/**
 * Which couplings a render keeps. The defaults are the instrument. Tests turn one off to show
 * that the beads and the cage are consequences of the drum, not a second recording played beside it.
 */
data class TremorProbe(
    val headToTray: Boolean = true,
    val trayToHead: Boolean = true,
    val headToBody: Boolean = true,
    val headToCage: Boolean = true,
    val trayToCage: Boolean = true,
    val cageToHead: Boolean = true,
    val beadMotion: Boolean = true,
    val beadReaction: Boolean = true,
    val circuit: Boolean = true,
)

/** One coordinated strike, with its onset in internal samples and the integrated force impulse. */
data class TremorStrike(
    val index: Int,
    val sample: Int,
    val impulse: Double,
)

/** A load fault. [kind] is LOADED or RECOVER, at an internal sample. */
data class TremorFault(
    val sample: Int,
    val kind: String,
    val depth: Double,
)

/**
 * A render plus the traces the acceptance tests read. [snip] is the levelled mono export.
 * [raw] is the internal-rate mix before decimation and loudness, so a later gain cannot hide a
 * runaway. [loopStart] is -1 on a one-shot and the seam marker on HOLD.
 */
data class TremorRender(
    val snip: Snip,
    val rawPeak: Float,
    val rawDc: Double,
    val railHits: Int,
    val strikes: List<TremorStrike>,
    val contactCount: Int,
    val contactImpulse: Double,
    val lastContactSample: Int,
    val faults: List<TremorFault>,
    val circuitWork: Double,
    val actuatorPeak: Double,
    val maxModalEnergy: Double,
    val endModalEnergy: Double,
    val endBeadEnergy: Double,
    val stringEnergy: Double,
    val maxTrayExcursion: Double,
    val energy: DoubleArray,
    val loopStart: Int,
)

object Tremor {
    const val MODEL = TremorV2.MODEL
    const val LEGACY_MODEL = TremorV1.MODEL
    const val ROOT_MIDI = TremorV2.ROOT_MIDI
    const val TUNE_SEMITONES = TremorV2.TUNE_SEMITONES
    const val HOLD_LOOP = TremorV2.HOLD_LOOP
    const val CAGE_FLOOR = TremorV2.CAGE_FLOOR
    const val ONESHOT_CAP_SECONDS = TremorV2.ONESHOT_CAP_SECONDS

    fun macrosFor(voice: TremorVoice): List<MacroSpec> = TremorV2.macrosFor(voice)
    fun defaults(voice: TremorVoice): Map<String, Float> = TremorV2.defaults(voice)
    fun settled(macros: Map<String, Float>, voice: TremorVoice): Map<String, Float> =
        TremorV2.settled(macros, voice)
    fun midiFor(voice: TremorVoice, tune: Float): Int = TremorV2.midiFor(voice, tune)
    fun frequencyFor(voice: TremorVoice, tune: Float): Float = TremorV2.frequencyFor(voice, tune)

    /** Authored pad roles remain independent of the saved synthesis model. */
    fun drumClassFor(voice: TremorVoice, macros: Map<String, Float> = emptyMap()): DrumClass =
        TremorV2.drumClassFor(voice, macros)

    /** New renders use model 2; saved model-1 recipes retain the frozen renderer. */
    fun render(
        voice: TremorVoice,
        macros: Map<String, Float> = emptyMap(),
        velocity: Float = 1f,
        model: Int = MODEL,
    ): Snip = play(voice, macros, velocity, model = model).snip

    fun play(
        voice: TremorVoice,
        macros: Map<String, Float> = emptyMap(),
        velocity: Float = 1f,
        probe: TremorProbe = TremorProbe(),
        model: Int = MODEL,
    ): TremorRender = when (model) {
        LEGACY_MODEL -> TremorV1.play(voice, macros, velocity, probe)
        MODEL -> TremorV2.play(voice, macros, velocity, probe)
        else -> throw IllegalArgumentException("unsupported tremor model $model")
    }

    internal fun ensembleWeights(ensemble: Double): DoubleArray = TremorV2.ensembleWeights(ensemble)
}
