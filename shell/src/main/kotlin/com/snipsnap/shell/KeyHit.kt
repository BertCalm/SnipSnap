package com.snipsnap.shell

import com.snipsnap.kit.InstrumentStore
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * What one key press asks the audio engine to play - the keygroup's
 * semantics resolved on the JVM, the same way [PadHit] does it for pads:
 * the zone that covers the note, the speed from the note's distance to
 * the zone's root, the loop the zone declares, the release the
 * instrument declares. The native voice only ever hears "this sample,
 * from here, looping back to there, at this gain and speed".
 *
 * The map is [InstrumentEngine]'s own: the zone, the speed, the loop and
 * the gain at [InstrumentEngine.VOICE_LEVEL] are resolved as that engine
 * resolves them. What happens after the voices are summed is not shared.
 * [InstrumentEngine] soft-clips its bus above 0.9; the phone's native bus
 * (`PadEngine::render` in `app/src/main/cpp/PadEngine.cpp`) hard-clamps at
 * full scale, so a chord that sums past 0.9 is shaped differently on the
 * phone, and one past full scale flat-tops there. Before the sum, the
 * native voice also caps its read speed at 64x (PadEngine's `kMaxSpeed`),
 * which [InstrumentEngine] does not. Nothing in the shop
 * plays through [InstrumentEngine]: it is the JVM reference that
 * `InstrumentEngineTest` and the pad makers' tests render.
 */
object KeyHit {

    data class Hit(
        val sampleFile: String,
        /** Frames; the voice plays to the sample's end. */
        val frames: Long,
        /** -1 = plays once to the end; otherwise the frame the read wraps back to. */
        val loopStartFrame: Long,
        val gain: Float,
        /** Playback speed over the sample's own rate: 2^(semitones from the root / 12). */
        val pitchRatio: Double,
    ) {
        init {
            require(frames > 0) { "a hit has frames" }
            require(loopStartFrame < frames) { "loop start $loopStartFrame is past $frames frames" }
        }

        val loops: Boolean get() = loopStartFrame >= 0
    }

    /**
     * Resolve [note] at [velocity] on [instrument]. Null when no zone covers
     * the note, or [framesOf] says the zone's sample never loaded - silence
     * rather than a guess, exactly as the JVM engine stays silent there.
     * A loop of fewer than two frames (a loop start at or past the last
     * frame) is played unlooped (the JVM engine's own rule).
     */
    fun resolve(instrument: InstrumentStore.Instrument, note: Int, velocity: Float, framesOf: (String) -> Long?): Hit? {
        require(velocity in 0f..1f) { "velocity 0..1, got $velocity" }
        val zone = instrument.zoneFor(note) ?: return null
        // MIDI velocity zero is note-off; a zero-gain touch still resolves the softest positive zone.
        val midiVelocity = (velocity * 127).roundToInt().coerceIn(1, 127)
        val layer = zone.layerFor(midiVelocity) ?: return null
        val frames = framesOf(layer.sample)?.takeIf { it > 0 } ?: return null
        val loop = if (layer.loopStartFrame > 0 && layer.loopStartFrame < frames - 1) layer.loopStartFrame else -1L
        return Hit(
            sampleFile = layer.sample,
            frames = frames,
            loopStartFrame = loop,
            gain = velocity * InstrumentEngine.VOICE_LEVEL,
            pitchRatio = 2.0.pow((note - zone.rootNote) / 12.0),
        )
    }

    /** The instrument's release as the fade a note-off asks for, at least a millisecond. */
    fun releaseMs(instrument: InstrumentStore.Instrument): Float = (instrument.playableRelease * 1000f).coerceAtLeast(1f)
}
