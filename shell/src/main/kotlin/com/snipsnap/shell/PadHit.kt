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
        /** -1 = finite; otherwise the native voice repeats from this frame. */
        val loopStartFrame: Long = -1,
    ) {
        init {
            require(startFrame >= 0 && endFrameExclusive > startFrame) { "empty window $startFrame..$endFrameExclusive" }
        }
    }

    /** Touch velocity 0..1 as the MPC's 0..127. */
    fun midiVelocity(velocity: Float): Int = (velocity.coerceIn(0f, 1f) * 127f).roundToInt()

    /** Shared by playback and its layer indicator, including the existing gap fallback. */
    fun layerIndex(pad: KitPad, velocity: Float): Int? {
        if (pad.velocityLayers.isEmpty()) return null
        val midi = midiVelocity(velocity)
        return pad.velocityLayers.indexOfFirst { midi in it.velStart..it.velEnd }
            .takeIf { it >= 0 } ?: pad.velocityLayers.lastIndex
    }

    /**
     * The softest a touch-Y velocity can be: quiet, and deliberately not
     * silent — a pad that makes no sound reads as broken rather than soft.
     *
     * One quantity, one place: this was `private const val MIN_VELOCITY`
     * in **two** files, with the formula around it retyped in **three**
     * more, which is how J37 managed to ship a fourth copy with a
     * different floor *and* the axis inverted without anything noticing.
     *
     * **0.20, chosen so SOFT HITS is actually playable.** `midiVelocity`
     * rounds it to 25, inside the softest window `StackTakes.windows` lays
     * out at every depth it allows — 1..63 for one soft zone, 1..41 for
     * two, 1..31 for three.
     *
     * It read 0.35 (MIDI 44) until this was measured, with a comment
     * claiming 44 sat inside "one soft zone (MIDI 1..63) and two (1..41)".
     * 44 is not inside 1..41. On any pad stacked two or three deep the
     * softest layer could not be reached from the grid at all, so the
     * feature built velocity layers nothing could trigger. 0.20 is also
     * what this floor was before the J37 inversion fix moved it, where it
     * was picked for exactly this reason; the move was never re-argued
     * against the zone maths.
     *
     * The cost is real and deliberate: PLAY, GROOVE and KEYS share this
     * curve, so their softest touch is quieter than it was. A floor that
     * cannot reach the layer it exists to reach is the worse of the two.
     *
     * `the floor reaches the softest take at every stack depth` holds this
     * to [StackTakes.windows] rather than to a number, which is why the
     * old assertion — a plain `assertEquals(0.35f, SOFTEST)` — could never
     * have caught it.
     */
    const val SOFTEST = 0.20f

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
     * own level, the far side fading as pan leaves it. This is a linear
     * balance law, not constant power: both sides sit at full level at
     * centre, and a hard pan keeps one side at full level and silences the
     * other, so a centred pad is about 3 dB louder in total than a
     * hard-panned one (the gap shrinks to nothing as pan returns to centre).
     * It is the same map the SoundPool player used, so a kit sounds as it
     * did. (KitPreview's audition uses an equal-power law instead.)
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
    fun resolve(pad: KitPad, velocity: Float, hitIndex: Int, framesOf: (String) -> Long?): Hit? =
        resolve(pad, velocity, hitIndex, velocity, framesOf)

    /** Separate sample/chain selection from level, for a strip that changes tone at steady gain. */
    fun resolve(pad: KitPad, velocity: Float, hitIndex: Int, gainVelocity: Float, framesOf: (String) -> Long?): Hit? {
        require(velocity in 0f..1f) { "velocity 0..1, got $velocity" }
        require(gainVelocity in 0f..1f) { "gain velocity 0..1, got $gainVelocity" }
        require(hitIndex >= 0) { "hitIndex is a count, got $hitIndex" }
        val midi = midiVelocity(velocity)
        val file = layerIndex(pad, velocity)?.let { pad.velocityLayers[it].sampleFile } ?: pad.sampleFile
        val frames = framesOf(file)?.takeIf { it > 0 } ?: return null
        val (left, right) = gains(pad.level, pad.pan, gainVelocity)
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
        return Hit(file, window.first, window.last + 1, left, right, ratio,
            loopStartFrame = if (pad.loop && !pad.oneShot && pad.chain == null) window.first else -1)
    }
}
