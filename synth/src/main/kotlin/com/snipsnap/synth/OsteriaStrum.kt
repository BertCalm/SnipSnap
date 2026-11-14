package com.snipsnap.synth

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scale
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.JsonValue
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

/** One strike per chord tone through the unchanged PLUCK source. HOLD only extends each finite ring. */
object OsteriaStrum {
    enum class Direction { DOWN, UP }

    data class Articulation(
        val intervals: List<Int>,
        val sweepSeconds: Double = .09,
        val direction: Direction = Direction.DOWN,
    ) {
        init {
            require(intervals.size in 2..6 && intervals.distinct().size == intervals.size)
            require(0 in intervals) { "The saved root must be a chord member" }
            require(intervals.all { it in -48..48 } && intervals.max() - intervals.min() <= 48)
            require(sweepSeconds.isFinite() && sweepSeconds in .01.. .24)
        }

        val rootRange: IntRange get() = (Osteria.MIN_MIDI - intervals.min())..(Osteria.MAX_MIDI - intervals.max())
        fun notes(root: Int): List<Int> {
            require(root in rootRange) { "OSTERIA chord root must be in $rootRange" }
            return intervals.map { root + it }
        }

        fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
            "version" to JsonValue.Num(1.0),
            "intervals" to JsonValue.Arr(intervals.map { JsonValue.Num(it.toDouble()) }),
            "sweepSeconds" to JsonValue.Num(sweepSeconds),
            "direction" to JsonValue.Str(direction.name),
        ))
    }

    fun fromJsonValue(value: JsonValue): Articulation {
        val obj = value.obj()
        require(obj.keys == setOf("version", "intervals", "sweepSeconds", "direction")) { "Unknown or incomplete STRUM fields" }
        require(obj.getValue("version").int() == 1) { "Unsupported STRUM version" }
        return Articulation(obj.getValue("intervals").arr().map { it.int() },
            obj.getValue("sweepSeconds").num(), Direction.valueOf(obj.getValue("direction").str()))
    }

    /** Reuses the existing chord tool's exact root and voicing, including inversions. */
    fun fromChord(patch: OsteriaPatch, chord: ChordStabs.Stab, sweepSeconds: Double = .09,
                  direction: Direction = Direction.DOWN): OsteriaPatch =
        patch.copy(midi = chord.rootMidi, strum = Articulation(chord.intervals.toList(), sweepSeconds, direction))

    /** The panel's two initial chord choices come from the same chord tool. */
    fun triad(minor: Boolean): Articulation = Articulation(
        ChordStabs.stabs(KeySpec(0, if (minor) Scale.MINOR else Scale.MAJOR)).first().intervals.toList(),
    )

    data class Event(val midi: Int, val startFrame: Int)

    fun events(patch: OsteriaPatch): List<Event> {
        val articulation = requireNotNull(patch.strum)
        val notes = articulation.notes(patch.midi).sorted().let {
            if (articulation.direction == Direction.UP) it.reversed() else it
        }
        return notes.mapIndexed { index, midi ->
            Event(midi, (articulation.sweepSeconds * WavWriter.MPC_SAMPLE_RATE * index / (notes.size - 1)).roundToInt())
        }
    }

    /** Source duration is the original engine's finite HOLD law; last tone may start up to 240 ms later. */
    fun totalFrames(patch: OsteriaPatch): Int {
        val hold = patch.macros["HOLD"] ?: Osteria.defaults(patch.voice).getValue("HOLD")
        val seconds = patch.config.seconds + (8.0 - patch.config.seconds) * hold
        return (seconds * WavWriter.MPC_SAMPLE_RATE).roundToInt() + events(patch).last().startFrame
    }

    fun render(patch: OsteriaPatch, cancelled: () -> Boolean = { false }): Snip {
        val sources = requireNotNull(patch.strum).notes(patch.midi).associateWith { midi ->
            stop(cancelled)
            // Explicit source call prevents recursion; every saved timbre/envelope field remains intact.
            Osteria.render(patch.voice, patch.macros, midi, patch.velocity, patch.config, cancelled)
        }
        return mix(patch, sources, cancelled)
    }

    /** Same averaging and onset arithmetic as the owner-approved study; no loudness renormalization. */
    fun mix(patch: OsteriaPatch, sources: Map<Int, Snip>, cancelled: () -> Boolean = { false }): Snip {
        val events = events(patch)
        require(sources.keys == events.map { it.midi }.toSet())
        require(sources.values.all { it.sampleRate == WavWriter.MPC_SAMPLE_RATE && it.channels == 2 })
        val frames = events.maxOf { it.startFrame + sources.getValue(it.midi).frameCount }
        val out = FloatArray(frames * 2)
        val gain = 1f / events.size
        for (event in events) {
            val source = sources.getValue(event.midi).samples
            for (i in source.indices) {
                if (i % 16384 == 0) stop(cancelled)
                out[event.startFrame * 2 + i] += source[i] * gain
            }
        }
        stop(cancelled)
        return Snip(out, 2, WavWriter.MPC_SAMPLE_RATE)
    }

    private fun stop(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("OSTERIA strum cancelled")
    }
}
