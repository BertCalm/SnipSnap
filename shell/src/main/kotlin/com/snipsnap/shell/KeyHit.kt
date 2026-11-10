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
 * which [InstrumentEngine] does not. [InstrumentEngine] is the JVM reference
 * rendered by its tests and [BellowsPadMaker]'s finite MAKE INSTRUMENT preview.
 * The phone's live keyboard still plays through its native voice.
 */
object KeyHit {
    class CycleCursor {
        private val next = mutableMapOf<Triple<Int, Int, Int>, Int>()
        fun clear() = next.clear()
        internal fun take(note: Int, zoneIndex: Int, zone: InstrumentStore.Zone, velocity: Float): Int {
            if (!zone.cycleLayers) return 0
            val midi = (velocity * 127).roundToInt().coerceIn(1, 127)
            val matches = zone.playableLayers.filter { midi in it.velStart..it.velEnd }
            if (matches.isEmpty()) return 0
            val key = Triple(note, zoneIndex, matches.first().velStart)
            val take = next[key] ?: 0
            next[key] = (take + 1) % matches.size
            return take
        }
    }


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
        return resolveZone(zone, note, velocity, framesOf = framesOf)
    }

    data class Component(val hit: Hit, val zoneIndex: Int, val releaseSeconds: Float)

    /**
     * Opt-in stacks must share a clock: equal lengths, loop markers and roots for the
     * selected touch. A missing or incompatible component silences the entire note.
     * At most four components leave room for eight held keys in the native 32-voice bank.
     */
    fun resolveAll(instrument: InstrumentStore.Instrument, note: Int, velocity: Float,
                   framesOf: (String) -> Long?): List<Component> = resolveAll(instrument, note, velocity, null, framesOf)

    fun resolveAll(instrument: InstrumentStore.Instrument, note: Int, velocity: Float,
                   cursor: CycleCursor?, framesOf: (String) -> Long?): List<Component> =
        resolveAllByZone(instrument, note, velocity, cursor) { file, _ -> framesOf(file) }

    /** The reference player's legacy per-zone sample list may reuse a filename. */
    internal fun resolveAllByZone(instrument: InstrumentStore.Instrument, note: Int, velocity: Float,
                                 cursor: CycleCursor? = null, framesOf: (String, Int) -> Long?): List<Component> {
        require(velocity in 0f..1f) { "velocity 0..1, got $velocity" }
        val zones = instrument.zones.withIndex().filter { note in it.value.lowNote..it.value.highNote }
            .let { if (instrument.stackZones) it else it.take(1) }
        if (zones.size > 4) return emptyList()
        val components = zones.map { (index, zone) ->
            val hit = resolveZone(zone, note, velocity, cursor?.take(note, index, zone, velocity) ?: 0) { file -> framesOf(file, index) } ?: return emptyList()
            Component(hit, index, instrument.releaseFor(zone))
        }
        val first = components.firstOrNull()?.hit ?: return emptyList()
        if (components.any { it.hit.frames != first.frames || it.hit.loopStartFrame != first.loopStartFrame ||
                it.hit.pitchRatio != first.pitchRatio }) return emptyList()
        return components
    }

    private fun resolveZone(zone: InstrumentStore.Zone, note: Int, velocity: Float,
                            take: Int = 0, framesOf: (String) -> Long?): Hit? {
        // MIDI velocity zero is note-off; a zero-gain touch still resolves the softest positive zone.
        val midiVelocity = (velocity * 127).roundToInt().coerceIn(1, 127)
        val layer = zone.layerFor(midiVelocity, take) ?: return null
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
