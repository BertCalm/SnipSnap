package com.snipsnap.synth

import com.snipsnap.json.JsonValue

/**
 * DRAWL's STRUM: one string per chord tone, picked [Articulation.sweepSeconds] apart from the first
 * to the last, DOWN (low to high) or UP. Picked and thumbed strings play chords this way (the
 * owner's articulation gate), and the three stops make three different strums, as the Python
 * prototype the owner heard did (round 0, 20 Nov 2026):
 * - a bar (CONE, BOTTLE, LAP) lies across every string, so the whole chord slides into place as one;
 * - STEEL's lowest tone holds while the pedals raise the top tone into the chord by SLIDE's travel
 *   and the inner tones by half of it, the steel's cry;
 * - fretted chords (FLATTOP, GUT) are fingered, not slid.
 * Every string shares the one body, a chord is scaled by 1 / sqrt(strings), and the length is the
 * single note's: the last string starts at most [MAX_SWEEP_SECONDS] in.
 */
object DrawlStrum {
    enum class Direction { DOWN, UP }

    const val VERSION = 1
    const val MIN_SWEEP_SECONDS = .01
    const val MAX_SWEEP_SECONDS = .24
    const val DEFAULT_SWEEP_SECONDS = .06

    data class Articulation(
        val intervals: List<Int>,
        val sweepSeconds: Double = DEFAULT_SWEEP_SECONDS,
        val direction: Direction = Direction.DOWN,
    ) {
        init {
            require(intervals.size in 2..6 && intervals.distinct().size == intervals.size) {
                "a DRAWL strum is 2 to 6 different tones, got $intervals"
            }
            require(0 in intervals) { "the played note must be a chord tone (interval 0)" }
            require(intervals.all { it in -24..24 } && intervals.max() - intervals.min() <= 24) {
                "a DRAWL strum spans at most two octaves, got $intervals"
            }
            require(sweepSeconds.isFinite() && sweepSeconds in MIN_SWEEP_SECONDS..MAX_SWEEP_SECONDS) {
                "a DRAWL strum sweeps in $MIN_SWEEP_SECONDS to $MAX_SWEEP_SECONDS s, got $sweepSeconds"
            }
        }

        /** The tones in the order they are picked. */
        val picked: List<Int> get() = if (direction == Direction.UP) intervals.sortedDescending() else intervals.sorted()

        fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
            "version" to JsonValue.Num(VERSION.toDouble()),
            "intervals" to JsonValue.Arr(intervals.map { JsonValue.Num(it.toDouble()) }),
            "sweepSeconds" to JsonValue.Num(sweepSeconds),
            "direction" to JsonValue.Str(direction.name),
        ))
    }

    fun fromJsonValue(value: JsonValue): Articulation {
        val obj = value.obj()
        require(obj.keys == setOf("version", "intervals", "sweepSeconds", "direction")) { "unknown or incomplete DRAWL strum fields" }
        require(obj.getValue("version").int() == VERSION) { "unsupported DRAWL strum version" }
        val direction = obj.getValue("direction").str()
        return Articulation(
            obj.getValue("intervals").arr().map { it.int() },
            obj.getValue("sweepSeconds").num(),
            Direction.entries.firstOrNull { it.name == direction } ?: throw IllegalArgumentException("unknown DRAWL strum direction $direction"),
        )
    }

    /**
     * The chord tool's chord on [patch]'s voice: its exact voicing (inversions included), its root
     * moved by whole octaves to the nearest root TUNE can reach on this voice, and TUNE set to play it.
     */
    fun fromChord(
        patch: DrawlPatch,
        chord: ChordStabs.Stab,
        sweepSeconds: Double = DEFAULT_SWEEP_SECONDS,
        direction: Direction = Direction.DOWN,
    ): DrawlPatch {
        val home = Drawl.rootMidi(patch.voice)
        var root = chord.rootMidi
        while (root > home + Drawl.TUNE_SEMITONES / 2) root -= 12
        while (root < home - Drawl.TUNE_SEMITONES / 2) root += 12
        val tune = .5f + (root - home).toFloat() / Drawl.TUNE_SEMITONES
        return patch.copy(
            macros = patch.macros + ("TUNE" to tune),
            strum = Articulation(chord.intervals, sweepSeconds, direction),
        )
    }
}
