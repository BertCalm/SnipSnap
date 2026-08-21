package com.snipsnap.audio

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The note grid: keys, scales, and the 4×4 pad layouts built from them.
 *
 * This is the keys-on-pads foundation from docs/UI_DESIGN.md made real:
 * root bottom-left (A01), ascending left→right bottom→top. CHROMATIC gives
 * 16 semitones; SCALE gives 16 in-scale notes — wrong notes physically
 * impossible, which is the mode most people should live in.
 *
 * Scale list is deliberately short (playability rule: presets first). More
 * scales are a data change, not a design change.
 */
enum class Scale(val intervals: IntArray) {
    CHROMATIC(intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)),
    MAJOR(intArrayOf(0, 2, 4, 5, 7, 9, 11)),
    MINOR(intArrayOf(0, 2, 3, 5, 7, 8, 10)),
    MAJOR_PENTATONIC(intArrayOf(0, 2, 4, 7, 9)),
    MINOR_PENTATONIC(intArrayOf(0, 3, 5, 7, 10)),
}

object Scales {

    /** Note names for display; index is semitones above C. */
    val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    const val A4_HZ = 440f
    private const val A4_MIDI = 69

    /**
     * The 16-pad layout: semitone offsets from the root note, pad A01 first.
     * CHROMATIC spans just over an octave; a pentatonic spans three-plus.
     */
    fun layout(scale: Scale, padCount: Int = 16): IntArray =
        IntArray(padCount) { pad ->
            val degree = pad % scale.intervals.size
            val octave = pad / scale.intervals.size
            scale.intervals[degree] + 12 * octave
        }

    fun midiToHz(midi: Int): Float = A4_HZ * 2f.pow((midi - A4_MIDI) / 12f)

    fun hzToMidi(hz: Float): Float = A4_MIDI + 12f * (ln(hz / A4_HZ) / ln(2f))

    /** "A2", "C#4" — display name for a MIDI note. */
    fun nameOf(midi: Int): String = NOTE_NAMES[((midi % 12) + 12) % 12] + (midi / 12 - 1)

    /**
     * The nearest note to [hz] that belongs to [scale] rooted at
     * [rootSemitone] (semitones above C, 0..11), searched across octaves.
     */
    fun nearestInKey(hz: Float, rootSemitone: Int, scale: Scale): Int {
        require(rootSemitone in 0..11) { "root is 0..11 semitones above C, got $rootSemitone" }
        val exact = hzToMidi(hz)
        val center = exact.roundToInt()
        val degrees = scale.intervals.toSet()
        var best = 0
        var bestDist = Float.MAX_VALUE
        // The nearest in-key note is always within an octave of the input.
        for (midi in center - 12..center + 12) {
            val degree = (((midi - rootSemitone) % 12) + 12) % 12
            if (degree !in degrees) continue
            val d = abs(midi - exact)
            if (d < bestDist) { bestDist = d; best = midi }
        }
        return best
    }
}
