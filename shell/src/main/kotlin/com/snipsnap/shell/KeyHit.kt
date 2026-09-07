package com.snipsnap.shell

import com.snipsnap.kit.InstrumentStore
import kotlin.math.pow

/**
 * What one key press asks the audio engine to play - the keygroup's
 * semantics resolved on the JVM, the same way [PadHit] does it for pads:
 * the zone that covers the note, the speed from the note's distance to
 * the zone's root, the loop the zone declares, the release the
 * instrument declares. The native voice only ever hears "this sample,
 * from here, looping back to there, at this gain and speed".
 *
 * The map is [InstrumentEngine]'s own, so a note sounds the same on the
 * native path as it did on the JVM one that engine still renders for
 * the CLI and the tests.
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
     * A loop start at or past the end is played unlooped (the JVM engine's
     * own rule), never a zero-length loop.
     */
    fun resolve(instrument: InstrumentStore.Instrument, note: Int, velocity: Float, framesOf: (String) -> Long?): Hit? {
        require(velocity in 0f..1f) { "velocity 0..1, got $velocity" }
        val zone = instrument.zoneFor(note) ?: return null
        val frames = framesOf(zone.sample)?.takeIf { it > 0 } ?: return null
        val loop = if (zone.loopStartFrame > 0 && zone.loopStartFrame < frames - 1) zone.loopStartFrame else -1L
        return Hit(
            sampleFile = zone.sample,
            frames = frames,
            loopStartFrame = loop,
            gain = velocity * InstrumentEngine.VOICE_LEVEL,
            pitchRatio = 2.0.pow((note - zone.rootNote) / 12.0),
        )
    }

    /** The instrument's release as the fade a note-off asks for, at least a millisecond. */
    fun releaseMs(instrument: InstrumentStore.Instrument): Float = (instrument.release * 1000f).coerceAtLeast(1f)
}
