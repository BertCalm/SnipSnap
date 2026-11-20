package com.snipsnap.synth

import kotlin.math.pow

/**
 * CHAMBER's candidate voices (listening round chamber-voices-1, 20 Nov 2026). Nothing here is heard yet and none is
 * offered anywhere until the owner keeps it ([Chamber.KEPT_VOICES]); each renders on the register model only.
 *
 * HYBRID and AQUARIUM are two rooms around the same struck quartet. Each candidate keeps the engine whole (one
 * shared strike, modal instruments, the passive room network that takes energy from them and gives it back, the
 * listener taps) and changes what the room holds and how the instruments meet it:
 *  - TIMBER: a marimba. The bar's modes are tuned 1:4:10 like a marimba bar, and every wooden wall carries a
 *    resonance on the played note (the register model's sympathetic mode, here at every pitch and much stronger);
 *    the bar is coupled to the room hard enough for those resonators to take its energy and sing it back, as a
 *    marimba's tubes do.
 *  - MASS: a big drum in a stone hall. The membrane leads, coupled strongly to a large concrete room, so the
 *    hall drinks the boom and answers it.
 *  - ALLOY: a steel pan. The cowbell's shell is retuned to a pan's tone field (fundamental, octave and twelfth
 *    in tune, a few weak skirt modes) and played on the note, in a metal room.
 *  - GLAZE: a singing bowl in a glass room. Every bowl mode is a pair 5 cents apart, about a hertz at middle C (a bowl is never
 *    perfectly round), struck together, so each partial slowly beats.
 *  - BOUNCE (the spare): a hammered string, a cimbalom. The bar becomes a string (harmonic partials with a little
 *    stiffness) and the shared strike is a hammer that bounces: two quick lighter contacts follow the first.
 *
 * Touch for the candidates is wider than the kept rooms': velocity runs from soft (-1 at .3) through the default
 * (0 at .75, the sound a KEEP would keep) to hard (+1 at 1). It moves the strike's hardness and contact, tilts every
 * instrument's modes ([Touch.tiltSoft] or [Touch.tiltHard] per mode index), and sets the level after leveling (soft [Touch.softDb] under,
 * hard [Touch.hardDb] over, never levelled back).
 */
internal object ChamberCandidates {
    /** One instrument's modes, replacing the quartet's own table for that instrument. */
    class Instrument(
        val ratios: DoubleArray,
        val weights: DoubleArray,
        val t60: Double,
        val decaySlope: Double = .2,
        /** Upper-mode roll-off at hardness 0, and how much hardness lifts it. */
        val soft: Double = .5,
        val hard: Double = .35,
        /** A second mode this far above each mode (a fraction of its frequency), at [pairWeight]. */
        val pair: Double = 0.0,
        val pairWeight: Double = 0.0,
        /** Modes are rolled off above about this frequency, so the top of the range never turns shrill. */
        val rolloffHz: Double = 5000.0,
    )

    /** Hardness per unit of touch; mode tilt per mode index and unit of touch, under and over the default; level. */
    class Touch(val hardness: Double, val tiltSoft: Double, val tiltHard: Double, val softDb: Double, val hardDb: Double)

    class Shape(
        val instruments: Array<Instrument?>,
        /** The rate each instrument hands energy to the room (1/s); the kept quartet's are .5, .25, 15 and 8. */
        val ports: DoubleArray,
        /** Listener calibration per instrument (outside the network). */
        val pressure: DoubleArray,
        /** A note-tuned mode on every boundary at every pitch, at this port share (0: the register model's own). */
        val resonator: Double = 0.0,
        /** Later contacts of the shared strike: seconds after the first, and strength against it. */
        val bounces: List<Pair<Double, Double>> = emptyList(),
        val touch: Touch = Touch(.3, .9, .3, 9.0, 2.0),
        val releaseSeconds: Float = .8f,
        /** Listener calibration of the room's answer (early, surface and late taps), outside the network. */
        val roomGain: Double = 1.0,
    )

    private val all = arrayOf(true, true, true, true)

    fun defaults(voice: ChamberVoice): FloatArray = when (voice) {
        // STRIKE, BALANCE, MATERIAL, DIVIDE, PLACEMENT
        ChamberVoice.TIMBER -> floatArrayOf(.45f, .33f, .05f, .20f, .35f)
        ChamberVoice.MASS -> floatArrayOf(.50f, .05f, .25f, .25f, .55f)
        ChamberVoice.ALLOY -> floatArrayOf(.55f, .67f, .50f, .40f, .65f)
        ChamberVoice.GLAZE -> floatArrayOf(.35f, 1f, .75f, .35f, .50f)
        ChamberVoice.BOUNCE -> floatArrayOf(.55f, .33f, .15f, .30f, .40f)
        else -> error("$voice is not a candidate")
    }

    fun config(voice: ChamberVoice): ChamberConfig = when (voice) {
        ChamberVoice.TIMBER -> ChamberConfig(
            dimensions = ChamberDimensions(5f, 6f, 3f), layout = ChamberLayout.UNIFORM,
            materialReference = .05f, targets = ChamberSurfaceTargets(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, .1f, 0f),
            instrumentEnabled = ChamberInstrumentEnabled(membrane = false, bar = true, cowbell = false, triangle = false),
        )
        ChamberVoice.MASS -> ChamberConfig(
            dimensions = ChamberDimensions(14f, 15f, 8f), layout = ChamberLayout.UNIFORM,
            materialReference = .25f, targets = ChamberSurfaceTargets(.25f, .25f, .25f, .25f, .25f, .25f, .25f, .25f, .25f, .3f),
            instrumentEnabled = ChamberInstrumentEnabled(membrane = true, bar = true, cowbell = false, triangle = false),
        )
        ChamberVoice.ALLOY -> ChamberConfig(
            dimensions = ChamberDimensions(6f, 7f, 3.5f), layout = ChamberLayout.OPPOSING_WALLS,
            materialReference = .5f, targets = ChamberSurfaceTargets(.5f, .5f, .5f, .5f, .5f, .5f, .5f, .5f, .25f, .5f),
            tuningRatios = ChamberTuningRatios(membrane = 1f, bar = 1f, cowbell = 1f, triangle = 2f),
            instrumentEnabled = ChamberInstrumentEnabled(membrane = false, bar = false, cowbell = true, triangle = false),
        )
        ChamberVoice.GLAZE -> ChamberConfig(
            dimensions = ChamberDimensions(9f, 10f, 6f), layout = ChamberLayout.LOWER_UPPER,
            materialReference = .75f, targets = ChamberSurfaceTargets(.75f, .75f, .75f, .75f, .75f, .75f, .75f, .75f, .5f, .75f),
            tuningRatios = ChamberTuningRatios(membrane = 1f, bar = 1f, cowbell = 2f, triangle = 1f),
            instrumentEnabled = ChamberInstrumentEnabled(membrane = false, bar = false, cowbell = false, triangle = true),
        )
        ChamberVoice.BOUNCE -> ChamberConfig(
            dimensions = ChamberDimensions(7f, 8f, 4f), layout = ChamberLayout.ALTERNATING_WALLS,
            materialReference = .15f, targets = ChamberSurfaceTargets(0f, 0f, .25f, .25f, 0f, 0f, .25f, .25f, 0f, .25f),
            instrumentEnabled = ChamberInstrumentEnabled(membrane = false, bar = true, cowbell = false, triangle = false),
        )
        else -> error("$voice is not a candidate")
    }

    private val strings = DoubleArray(10) { (it + 1) * sqrt1(it + 1) }
    private fun sqrt1(n: Int) = kotlin.math.sqrt(1 + .0006 * n * n) / kotlin.math.sqrt(1.0006)

    fun shape(voice: ChamberVoice): Shape? = when (voice) {
        ChamberVoice.TIMBER -> Shape(
            arrayOf(null, Instrument(doubleArrayOf(1.0, 4.0, 10.0, 17.6), doubleArrayOf(1.6, .55, .3, .15), 3.0, 1.2, .55, .35), null, null),
            ports = doubleArrayOf(.5, .5, 15.0, 8.0), pressure = doubleArrayOf(12.0, 4.0, 1.0, 1.0), resonator = .9,
            touch = Touch(.3, 1.1, .45, 9.0, 2.0),
        )
        ChamberVoice.MASS -> Shape(
            // The quartet's kettle-loaded membrane ratios, a bigger and longer skin.
            arrayOf(Instrument(doubleArrayOf(1.0, 1.505, 1.985, 2.48, 2.96, 3.43, 4.08), doubleArrayOf(1.4, .7, 1.0, .6, .8, .5, .6), 2.4, .2, .45, .2),
                null, null, null),
            ports = doubleArrayOf(2.0, .25, 15.0, 8.0), pressure = doubleArrayOf(34.0, 4.0, 1.0, 1.0),
            releaseSeconds = 1.0f, roomGain = 2.5,
        )
        ChamberVoice.ALLOY -> Shape(
            arrayOf(null, null, Instrument(doubleArrayOf(1.0, 2.0, 3.0, 2.42, 4.0, 5.31), doubleArrayOf(1.4, 1.4, 1.1, .15, .4, .1), 1.7, .25, .45, .3, rolloffHz = 2600.0), null),
            ports = doubleArrayOf(.5, .25, 1.5, 8.0), pressure = doubleArrayOf(1.0, 1.0, 1.4, .5),
        )
        ChamberVoice.GLAZE -> Shape(
            arrayOf(null, null, null, Instrument(doubleArrayOf(1.0, 2.71, 5.15, 8.17, 11.9), doubleArrayOf(1.0, .6, .35, .2, .1), 4.2, .45, .5, .3,
                pair = .003, pairWeight = .9, rolloffHz = 2500.0)),
            ports = doubleArrayOf(.5, .25, 15.0, 3.0), pressure = doubleArrayOf(1.0, 1.0, 1.0, 1.0), releaseSeconds = 1.0f,
            touch = Touch(.3, 1.5, .6, 9.0, 2.0),
        )
        ChamberVoice.BOUNCE -> Shape(
            arrayOf(null, Instrument(strings, DoubleArray(10) { if (it == 0) 2.0 else (it + 1.0).pow(-.8) }, 3.0, .3, .35, .3), null, null),
            ports = doubleArrayOf(.5, .3, 15.0, 8.0), pressure = doubleArrayOf(1.0, 6.0, 1.0, 1.0),
            bounces = listOf(.021 to .45, .034 to .2),
        )
        else -> null
    }

    /** Candidates' touch: -1 at .3, 0 at the default .75, +1 at 1. */
    fun touch(velocity: Float): Double {
        val v = velocity.toDouble()
        val pivot = Chamber.NORMAL_VELOCITY.toDouble()
        return if (v < pivot) ((v - pivot) / (pivot - .3)).coerceAtLeast(-1.0) else (v - pivot) / (1 - pivot)
    }

    fun tilt(shape: Shape, touch: Double) = touch * if (touch < 0) shape.touch.tiltSoft else shape.touch.tiltHard

    fun levelDb(shape: Shape, touch: Double) = if (touch < 0) shape.touch.softDb * touch else shape.touch.hardDb * touch
}
