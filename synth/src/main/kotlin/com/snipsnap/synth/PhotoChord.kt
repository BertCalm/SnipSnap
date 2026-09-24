package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Scales
import com.snipsnap.kit.ArrangedPad

/**
 * A CHORD FROM ONE PHOTO: the picture picks a chord, and the chord lands
 * on the pads low to high so an arpeggio can walk it.
 *
 * Three readings, each on its own axis so they do not move together:
 *
 *  - **Root** from hue, by SNAP's own TUNE mapping ([Snap.macrosFrom]) —
 *    so a photo's chord is rooted on the note its SNAP pad already plays,
 *    and a grey photo lands on SNAP's centre detent, A.
 *  - **Third** from brightness: brighter than mid-grey is major, darker is
 *    minor. A sunny photo and a night shot of the same red door share a
 *    root and differ in mood, which is the point of the axis.
 *  - **Seventh** from colourfulness, at the same halfway point
 *    [Snap.macrosFrom] stretches saturation around: a vivid photo adds one.
 *
 * The pads are every chord tone inside SNAP's own two-octave TUNE range,
 * starting from the root's first appearance so the root sits on A01 —
 * `Scales`' keys-on-pads rule, root bottom-left. That is four pads for a
 * high root (root, third, fifth, root again) up to nine for a low seventh
 * chord, so an UP arpeggio across them always spans at least an octave
 * without reaching into a second bank.
 *
 * Every pad plays the whole photo's HORIZON line, one pitch each, and
 * wears the colour SNAP would read back as its own pitch ([colorFor]) —
 * photograph a pad's light and SNAP plays that pad's note.
 */
object PhotoChord {

    /** What the photo decided, in the intervals a chord is made of. */
    enum class Quality(val suffix: String, val intervals: IntArray) {
        MAJOR("", intArrayOf(0, 4, 7)),
        MINOR("m", intArrayOf(0, 3, 7)),
        MAJOR7("maj7", intArrayOf(0, 4, 7, 11)),
        MINOR7("m7", intArrayOf(0, 3, 7, 10)),
    }

    /**
     * A chord as the photo read it. [root] is semitones above SNAP's A2
     * (0..11) — the unit TUNE counts in, so no conversion sits between the
     * reading and the pads.
     */
    data class Chord(val root: Int, val quality: Quality) {
        init { require(root in 0..11) { "root is 0..11 semitones above A, got $root" } }

        /** "C", "F#m", "Amaj7" — `Scales`' note names, which count from C. */
        val name: String get() = Scales.NOTE_NAMES[(root + A_ABOVE_C) % 12] + quality.suffix
    }

    /** What [build] lands: the chord, the kit's sixteen slots (nulls past the last tone), and the slots an arpeggio should walk. */
    data class Built(val chord: Chord, val pads: List<ArrangedPad?>, val slots: List<Int>)

    /** Brightness at or above this reads major. Mid-grey, so the split is the one a person would draw. */
    internal const val MAJOR_LUMINANCE = 0.5f

    /** Saturation at or above this adds a seventh: the halfway point [Snap.macrosFrom] stretches saturation around. */
    internal const val SEVENTH_SATURATION = 0.25f

    /** A is nine semitones above C, which is where `Scales.NOTE_NAMES` starts counting. */
    private const val A_ABOVE_C = 9

    private const val PADS = 16

    /** The chord [reading] picks; see the class doc for which number decides what. */
    fun chordOf(reading: Snap.Reading): Chord {
        val tune = Snap.macrosFrom(reading).getValue("TUNE")
        val root = Math.round(tune * Snap.TUNE_SEMITONES) % 12
        val major = reading.luminance >= MAJOR_LUMINANCE
        val seventh = reading.saturation >= SEVENTH_SATURATION
        val quality = when {
            major && seventh -> Quality.MAJOR7
            major -> Quality.MAJOR
            seventh -> Quality.MINOR7
            else -> Quality.MINOR
        }
        return Chord(root, quality)
    }

    /**
     * [chord]'s tones as semitones above A2, low to high, from the root's
     * first appearance to the top of SNAP's TUNE range — one pad each.
     */
    fun tones(chord: Chord): List<Int> {
        val classes = chord.quality.intervals.map { (chord.root + it) % 12 }.toSet()
        return (chord.root..Snap.TUNE_SEMITONES).filter { (it % 12) in classes }
    }

    /**
     * The colour whose hue SNAP reads as [semitones] above A2
     * ([Snap.hueForSemitones]), at full saturation and brightness.
     * Semitones are 15° of hue apart, so the 8-bit rounding of a pure hue
     * lands well inside its own semitone. The circle closes on itself, so
     * the bottom A and the top A share a colour.
     */
    fun colorFor(semitones: Int): Int {
        require(semitones in 0..Snap.TUNE_SEMITONES) { "SNAP tunes 0..${Snap.TUNE_SEMITONES} semitones, got $semitones" }
        return Photo.hue(Snap.hueForSemitones(semitones.toFloat()))
    }

    /**
     * The chord kit off [photo], named [name]: [chordOf] its reading, one
     * SNAP pad per [tones] entry from A01 up, each the photo's own line at
     * that tone's pitch. The recipe is the patch's, so the kit regenerates
     * from its sidecar like every other synth kit.
     */
    fun build(photo: Photo, name: String): Built {
        val reading = Snap.look(photo)
        val chord = chordOf(reading)
        val macros = Snap.macrosFrom(reading)
        // The kit must fill every tone, so a flat line (a plain wall) takes
        // PhotoField's own blend toward a sine rather than refusing.
        val (table, _) = PhotoField.cellTable(Snap.table(photo, SnapVoice.HORIZON))
        val pads = arrayOfNulls<ArrangedPad>(PADS)
        val slots = ArrayList<Int>()
        for ((i, semis) in tones(chord).withIndex()) {
            val tune = semis.toFloat() / Snap.TUNE_SEMITONES
            val patch = SnapPatch("$name ${noteName(semis)}", SnapVoice.HORIZON, macros + ("TUNE" to tune), table)
            val snip = patch.render()
            pads[i] = ArrangedPad(
                snip = snip,
                drumClass = Classifier.classify(snip).drumClass,
                recipe = PadRecipe(patch = patch).toJsonValue(),
                colorHex = "#%06x".format(colorFor(semis) and 0xFFFFFF),
            )
            slots += i + 1
        }
        return Built(chord, pads.toList(), slots)
    }

    /** "C3", "A2" — the note [semitones] above SNAP's A2 names. */
    private fun noteName(semitones: Int): String = Scales.nameOf(A2_MIDI + semitones)

    private const val A2_MIDI = 45
}
