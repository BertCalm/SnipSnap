package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Wider material responses for the model-three voices. SWEEP keeps its original
 * tables and never passes through this object.
 *
 * The owner heard and approved the broader November 5 material studies. These
 * tables retain their upper-mode balance and short catches, with new modal pitch
 * anchors: an exact root, an integer first upper mode, and a root decay long
 * enough to survive its upper modes. CLACK keeps the other bar-like spacings;
 * CHIME and RASP retain ten percent of the studies' higher-mode dispersion.
 * These refinements are measured design shapes awaiting their own listening,
 * not measured wood, wire, glass or metal models.
 *
 * Fixed frequency ratios preserve that pitch support across SURFACE. The knob
 * changes upper participation, loss, roughness and catch width instead. Every
 * surface still receives only its own geometric contact and retains its state.
 */
internal object MeridianMaterials {
    private const val INTERNAL_RATE = Dsp.RATE * Dsp.OVERSAMPLE
    private const val PICKUP = .16
    private const val ROOT_DECAY_CYCLES = 40.0
    private val referenceReach = .6f.toDouble()
    private val referenceT60 = Meridian.baseT60(.4f)

    // Arrays remain private. Every call returns fresh surface descriptors, so
    // diagnostics cannot change the tables or a subsequent render.
    private data class Shape(
        val ratios: Array<DoubleArray>,
        val gains: DoubleArray,
        val t60s: DoubleArray,
        val noiseHz: Double,
        val pulseMilliseconds: Double,
    )

    private val clack = Shape(
        arrayOf(
            doubleArrayOf(1.0, 3.0, 5.404, 8.933),
            doubleArrayOf(1.0, 3.0, 5.71, 9.31),
            doubleArrayOf(1.0, 3.0, 5.12, 8.47),
            doubleArrayOf(1.0, 3.0, 6.07, 9.86),
        ),
        doubleArrayOf(.95, 1.0, .85, .48),
        doubleArrayOf(.20, .085, .052, .030),
        5200.0, .23,
    )

    private val chime = Shape(
        arrayOf(
            doubleArrayOf(1.0, 2.0, 4.025, 6.98),
            doubleArrayOf(1.0, 2.0, 4.971, 7.993),
            doubleArrayOf(1.0, 3.0, 5.017, 8.961),
            doubleArrayOf(1.0, 3.0, 4.963, 9.017),
        ),
        doubleArrayOf(1.7, .90, 1.0, .85),
        doubleArrayOf(2.8, 2.2, 2.8, 2.4),
        4300.0, .14,
    )

    private val rasp = Shape(
        arrayOf(
            doubleArrayOf(1.0, 3.0, 7.008, 13.014),
            doubleArrayOf(1.0, 2.0, 5.008, 11.016),
            doubleArrayOf(1.0, 3.0, 7.973, 14.027),
            doubleArrayOf(1.0, 3.0, 6.037, 12.043),
        ),
        doubleArrayOf(1.0, .75, 1.0, .90),
        doubleArrayOf(.26, .20, .13, .075),
        8200.0, .10,
    )

    internal fun setups(
        voice: MeridianVoice,
        hz: Double,
        surface: Double,
        memory: Float,
        reach: Double,
        velocity: Double,
        touch: Double = 0.0,
    ): Array<Meridian.SurfaceSetup> {
        val shape = when (voice) {
            MeridianVoice.CLACK -> clack
            MeridianVoice.CHIME -> chime
            MeridianVoice.RASP -> rasp
            MeridianVoice.SWEEP, MeridianVoice.DRAW, MeridianVoice.LIFT ->
                error("$voice does not select a model-three material table directly")
        }
        require(hz.isFinite() && hz > 0.0)
        require(surface.isFinite() && surface in 0.0..1.0)
        require(memory.isFinite() && memory in 0f..1f)
        require(reach.isFinite() && reach in 0.0..1.0)
        require(velocity.isFinite() && velocity in 0.0..1.0)
        val memoryScale = Meridian.baseT60(memory) / referenceT60
        val upperParticipation = .15 + .85 * surface
        val upperDecay = .35 + .65 * surface
        // Calibrate to the actual normalized Float default, so the reference
        // descriptors retain the scratch study's cutoff and pulse arithmetic.
        val cutoffHardness = (.65 + .35 * reach) / (.65 + .35 * referenceReach) *
            (.65 + .35 * velocity) * MeridianTouch.cutoffMul(velocity, touch)
        val pulseHardness = (.0035 - .002 * reach) / (.0035 - .002 * referenceReach) *
            (1.15 - .3 * velocity) / (1.15 - .3) * MeridianTouch.widthMul(velocity, touch)

        return Array(Meridian.SURFACES) { j ->
            val material = Meridian.materialFor(j).ordinal
            val frequencies = DoubleArray(Meridian.MODES_PER_SURFACE) { k ->
                hz * shape.ratios[material][k]
            }
            val t60s = DoubleArray(Meridian.MODES_PER_SURFACE) { k ->
                shape.t60s[k] * memoryScale * (if (k == 0) 1.0 else upperDecay) *
                    (1 + .04 * sin((j + 1) * 1.31 + k * .37))
            }
            // In the study, some bright modes outlived the root and acquired
            // the tail's perceived pitch. Keep the true modal root at least as
            // persistent. Forty root cycles also keep a low, rapidly decaying
            // note's autocorrelation peak clear of the detector's near-zero
            // lag maximum; this is a register adaptation, not a new oscillator.
            t60s[0] = max(t60s[0], ROOT_DECAY_CYCLES / hz)
            for (k in 1 until Meridian.MODES_PER_SURFACE) t60s[0] = max(t60s[0], t60s[k])

            val participation = DoubleArray(Meridian.MODES_PER_SURFACE) { k ->
                val upperFade = 1 - MeridianMotion.smooth((frequencies[k] - 14000) / 4000)
                val gain = shape.gains[k] * (if (k == 0) 1.0 else upperParticipation) *
                    (1 + .045 * sin((j + 1) * 1.7 + k)) * upperFade
                sqrt(gain) * MeridianTouch.modeTilt(velocity, touch, k)
            }
            val pickup = DoubleArray(Meridian.MODES_PER_SURFACE) { PICKUP * participation[it] }
            val cutoff = (1400 + surface * (shape.noiseHz - 1400)) *
                (1 + .04 * (material - 1.5)) * cutoffHardness
            val alpha = 1 - exp(-2 * PI * cutoff / INTERNAL_RATE)
            val q = 1 - alpha
            // The same exact two-one-pole stationary variance as SWEEP: color
            // changes do not silently change the roughness stream's RMS.
            val noiseScale = sqrt(3 * (1 + q).pow(3) / (alpha * (1 + q * q)))
            val width = max(8, (shape.pulseMilliseconds * .001 * (3 - 2 * surface) *
                pulseHardness * INTERNAL_RATE).roundToInt())
            val pulse = DoubleArray(width + 1) { i -> sin(PI * i / width).pow(2) }
            val area = pulse.sum()
            for (i in pulse.indices) pulse[i] /= area
            Meridian.SurfaceSetup(frequencies, t60s, participation, pickup, alpha, noiseScale, pulse)
        }
    }
}
