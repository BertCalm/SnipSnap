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
     * The softest a touch-Y velocity can be: quiet, and deliberately not
     * silent — a pad that makes no sound reads as broken rather than soft.
     *
     * 0.35 because that is what PLAY, GROOVE and KEYS have always used. It
     * was `private const val MIN_VELOCITY` in **two** files, with the
     * formula around it retyped in **three** places, which is how J37
     * managed to ship a fourth copy with a different floor *and* the axis
     * inverted without anything noticing. One quantity, one place.
     *
     * `midiVelocity` rounds it to 44, inside the softest zone
     * `StackTakes.windows` lays out for one soft zone (MIDI 1..63) and for
     * two (1..41) — so SOFT HITS is reachable on either.
     */
    const val SOFTEST = 0.35f

    /**
     * Velocity from where in a pad the finger landed: [y] down from the top
     * of a cell [height] tall. **The bottom is the hardest hit, the top the
     * softest.**
     *
     * **Why position at all.** SOFT HITS builds real velocity-layer WAVs
     * and [resolve] has always chosen a layer by velocity, but a tap on
     * glass carries no force — so a grid had nothing to pass. Position is
     * the one thing a tap does carry.
     *
     * **Why this direction.** Not a judgement: it is the one PLAY, GROOVE
     * and KEYS already had ("top of the pad is softest, bottom is full
     * velocity"). J37 gave KIT the opposite and printed a legend saying so,
     * and the two conventions contradicted each other until someone tapped
     * a pad on a phone. A grid that disagrees with the grid on the next
     * screen is worse than either choice.
     *
     * A non-positive or non-finite [height] answers [CENTER] rather than
     * guessing an end: it means the caller does not know where the finger
     * was, which is the same thing a synthesized click means.
     */
    fun velocityAt(y: Float, height: Float, floor: Float = SOFTEST): Float {
        if (!height.isFinite() || height <= 0f || !y.isFinite()) return floor + (1f - floor) * 0.5f
        val down = (y / height).coerceIn(0f, 1f)
        return floor + (1f - floor) * down
    }

    /**
     * What a tap at the pad's vertical centre would have produced.
     *
     * The velocity a synthesized TalkBack click uses: [velocityAt] needs a
     * real touch Y, which a semantics `onClick` action does not have —
     * neither the softest nor the hardest hit available to a sighted
     * finger. J37's KIT wiring used full velocity instead, which was a
     * third disagreement with the rest of the app.
     */
    val CENTER: Float = velocityAt(0.5f, 1f)

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
