package com.snipsnap.shell

import com.snipsnap.kit.KitPad
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * What one pad hit asks the audio engine to play — the whole of a pad's
 * semantics resolved on the JVM, so the native voice only ever reads
 * "this sample, these frames, these gains, this speed". The layer a
 * velocity taps into, the slice a chain steps to, level and pan as the
 * MPC means them, tune as a ratio: all here, all tested, none in C++.
 */
object PadHit {

    data class Hit(
        val sampleFile: String,
        val startFrame: Long,
        val endFrameExclusive: Long,
        val gainLeft: Float,
        val gainRight: Float,
        /** Playback speed over the sample's own rate; 1.0 = as recorded. */
        val pitchRatio: Double,
    ) {
        init {
            require(startFrame >= 0 && endFrameExclusive > startFrame) { "empty window $startFrame..$endFrameExclusive" }
        }
    }

    /** Touch velocity 0..1 as the MPC's 0..127. */
    fun midiVelocity(velocity: Float): Int = (velocity.coerceIn(0f, 1f) * 127f).roundToInt()

    /**
     * The softest a tap can be: quiet, and deliberately not silent.
     *
     * A pad that makes no sound reads as broken rather than as soft, so the
     * bottom edge of a cell still speaks. `midiVelocity` rounds this to 25,
     * which is inside the softest zone `StackTakes.windows` lays out for
     * either one soft zone (MIDI 1..63) or two (1..41) — the floor has to
     * clear the tighter of those, or SOFT HITS stays inaudible on exactly
     * the pads that have the most layers.
     */
    const val SOFTEST = 0.2f

    /**
     * Velocity from where in a pad the finger landed: [y] down from the top
     * of a cell [height] tall. Top is the hardest hit, bottom the softest.
     *
     * **Why position at all.** SOFT HITS builds real velocity-layer WAVs
     * and [resolve] has always chosen a layer by velocity, but a tap on
     * glass carries no force — so the grid had nothing to pass and passed
     * `1f`, and the layers could be built and never heard. Position is the
     * one thing a tap does carry.
     *
     * **Where the soft/live line falls depends on the pad**, and
     * deliberately is not tuned to any one of them: with one soft zone the
     * boundary is MIDI 63, with two it is 41, so no single split point is
     * right for both. The map is a plain ramp from [SOFTEST] to full, and
     * where it crosses is whatever that pad's own zones say.
     *
     * A non-positive or non-finite [height] cannot divide, and answers a
     * full hit — the pad then plays exactly as it did before any of this
     * existed, which is the safe direction for a degenerate input on its
     * way to an audio callback.
     */
    fun velocityAt(y: Float, height: Float, floor: Float = SOFTEST): Float {
        if (!height.isFinite() || height <= 0f || !y.isFinite()) return 1f
        val down = (y / height).coerceIn(0f, 1f)
        return floor + (1f - floor) * (1f - down)
    }

    /**
     * Level and pan into left/right gains, scaled by velocity: the pad's
     * own level, the far side fading as pan leaves it (constant-ish power,
     * the same map the SoundPool player used, so a kit sounds as it did).
     */
    fun gains(level: Float, pan: Float, velocity: Float): Pair<Float, Float> {
        val v = velocity.coerceIn(0f, 1f)
        val left = level * (2f * (1f - pan)).coerceAtMost(1f) * v
        val right = level * (2f * pan).coerceAtMost(1f) * v
        return left.coerceIn(0f, 1f) to right.coerceIn(0f, 1f)
    }

    /** Coarse semitones plus fine cents as a speed ratio. */
    fun pitchRatio(tuneCoarse: Int, tuneFine: Int): Double =
        2.0.pow((tuneCoarse + tuneFine / 100.0) / 12.0)

    /**
     * Resolve [pad] hit at [velocity] for the [hitIndex]-th time (a chain
     * steps a slice per hit). [framesOf] answers a sample file's length in
     * frames, or null when the engine never loaded it — then the hit is
     * null too, and the caller plays nothing rather than guessing.
     */
    fun resolve(pad: KitPad, velocity: Float, hitIndex: Int, framesOf: (String) -> Long?): Hit? {
        require(velocity in 0f..1f) { "velocity 0..1, got $velocity" }
        require(hitIndex >= 0) { "hitIndex is a count, got $hitIndex" }
        val midi = midiVelocity(velocity)
        val file = if (pad.velocityLayers.isEmpty()) {
            pad.sampleFile
        } else {
            (pad.velocityLayers.firstOrNull { midi in it.velStart..it.velEnd } ?: pad.velocityLayers.last()).sampleFile
        }
        val frames = framesOf(file)?.takeIf { it > 0 } ?: return null
        val (left, right) = gains(pad.level, pad.pan, velocity)
        val ratio = pitchRatio(pad.tuneCoarse, pad.tuneFine)
        val chain = pad.chain
        val window = if (chain == null) {
            0L until frames
        } else {
            val zone = chain.zoneFor(midi)
            val base = zone?.baseSlice ?: 0
            val cycle = zone?.cycle ?: chain.cycle
            chain.window(base + hitIndex % cycle, frames)
        }
        if (window.isEmpty()) return null
        return Hit(file, window.first, window.last + 1, left, right, ratio)
    }
}
