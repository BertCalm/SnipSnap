package com.snipsnap.synth

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scale
import com.snipsnap.audio.Scales
import com.snipsnap.kit.ArrangedPad
import kotlin.math.abs

/**
 * CHORD STABS: sixteen pads, each one whole chord in a key, played by
 * STOOP's CHORD voice. The kit slot this fills is the chord stab: a pad a
 * producer hits for a full harmony, the way a chopped soul chord is
 * played, rather than one note at a time ([PhotoChord] lays one chord's
 * tones across pads for an arpeggio; this lays a key's chords across pads).
 *
 * The layout, for a seven-note key ([Scale.MAJOR] or [Scale.MINOR]):
 * - A01 to A07: the diatonic triad on each degree, I to vii, stacked in
 *   the key's own thirds;
 * - A08: the tonic triad with its root doubled an octave up, a wider I;
 * - A09 to A15: the diatonic seventh on each degree;
 * - A16: the tonic with an added ninth (Cmadd9 in C minor).
 *
 * Every chord is voiced near one centre ([voice]): of a chord's
 * inversions, the close-position one whose mean pitch is nearest the
 * centre. Neighbouring pads then move by small steps rather than jumping
 * an octave from vi to vii, which is what lets two pads in a row sound
 * like a progression. The voicing is [StoopPatch.chordIntervals] around
 * the chord's root, so intervals may be negative (an inversion with
 * tones below the root).
 *
 * Two further layouts (2026-11-07, not yet heard):
 * - [modalStabs]: the same sixteen pads in a [Mode], Dorian or
 *   Mixolydian, stacked in the mode's own thirds;
 * - [borrowedStabs]: a major key's own triads on A01 to A08, then on A09
 *   to A16 eight chords borrowed from the modes that share its tonic
 *   ([BORROWED]), each labelled with the mode it comes from.
 */
object ChordStabs {

    /**
     * The two church modes the modal layout plays, as semitones above the
     * tonic. Dorian is natural minor with a raised sixth (its IV is major);
     * Mixolydian is major with a lowered seventh (its bVII is major). Kept
     * here rather than in [Scale], which every scale picker in the app lists.
     */
    enum class Mode(val label: String, val intervals: IntArray) {
        DORIAN("dorian", intArrayOf(0, 2, 3, 5, 7, 9, 10)),
        MIXOLYDIAN("mixolydian", intArrayOf(0, 2, 4, 5, 7, 9, 10)),
    }

    /**
     * One chord a major key borrows: its numeral, its root in semitones
     * above the tonic, its tones above that root, and the mode on the same
     * tonic it comes from.
     */
    class Borrowed(val numeral: String, val rootPc: Int, val tones: List<Int>, val from: String)

    /**
     * A major key's borrowed chords, A09 to A16: the four most used from
     * the parallel minor (iv, bVII, bVI, bIII), minor v from Mixolydian,
     * the Neapolitan bII from Phrygian, major II from Lydian, and bVImaj7.
     */
    val BORROWED: List<Borrowed> = listOf(
        Borrowed("iv", 5, listOf(0, 3, 7), "minor"),
        Borrowed("bVII", 10, listOf(0, 4, 7), "minor"),
        Borrowed("bVI", 8, listOf(0, 4, 7), "minor"),
        Borrowed("bIII", 3, listOf(0, 4, 7), "minor"),
        Borrowed("v", 7, listOf(0, 3, 7), "mixolydian"),
        Borrowed("bII", 1, listOf(0, 4, 7), "phrygian"),
        Borrowed("II", 2, listOf(0, 4, 7), "lydian"),
        Borrowed("bVImaj7", 8, listOf(0, 4, 7, 11), "minor"),
    )

    /** The key's tonic in the octave G3 to F#4 (MIDI 55 to 66): C is C4, B is B3, so no key sits an octave above another. */
    internal fun tonicMidi(key: KeySpec): Int = tonicMidi(key.rootSemitone)
    internal fun tonicMidi(rootSemitone: Int): Int = 55 + (rootSemitone - 7 + 12) % 12

    /** The pitch the voicings gather around: a major third above [tonicMidi], the middle of a close triad on the tonic. */
    internal fun centreMidi(key: KeySpec): Int = centreMidi(key.rootSemitone)
    internal fun centreMidi(rootSemitone: Int): Int = tonicMidi(rootSemitone) + 4

    /** One pad's chord: a name, the root's MIDI note and the voicing around it; [from] names a borrowed chord's mode. */
    data class Stab(val name: String, val rootMidi: Int, val intervals: List<Int>, val from: String? = null) {
        val notes: List<Int> get() = intervals.map { rootMidi + it }.sorted()
    }

    /** The sixteen chords for [key], A01 first. Refuses a key that is not seven notes. */
    fun stabs(key: KeySpec): List<Stab> {
        require(key.scale == Scale.MAJOR || key.scale == Scale.MINOR) {
            "chord stabs stack the key's own thirds, which needs a seven-note scale (major or minor), not ${key.label}"
        }
        return layout(key.rootSemitone, key.scale.intervals)
    }

    /** The same sixteen pads as [stabs], in [mode] on the tonic [rootSemitone] (0 = C): D dorian is (2, DORIAN). */
    fun modalStabs(rootSemitone: Int, mode: Mode): List<Stab> {
        require(rootSemitone in 0..11) { "a tonic is 0 (C) to 11 (B), not $rootSemitone" }
        return layout(rootSemitone, mode.intervals)
    }

    /**
     * A major [key]'s triads and wide tonic on A01 to A08 (as in [stabs]),
     * then the eight [BORROWED] chords on A09 to A16, voiced around the same
     * centre. A minor key is refused: its borrowing runs the other way.
     */
    fun borrowedStabs(key: KeySpec): List<Stab> {
        require(key.scale == Scale.MAJOR) { "borrowed chord stabs borrow into a major key, not ${key.label}" }
        val centre = centreMidi(key)
        return stabs(key).take(8) + BORROWED.map { b ->
            voiceChord(key.rootSemitone, b.rootPc, b.tones, centre).copy(from = b.from)
        }
    }

    /** Triads I to vii, the wide tonic, sevenths I to vii and the add9, on the seven-note [scale] from [rootSemitone]. */
    private fun layout(rootSemitone: Int, scale: IntArray): List<Stab> {
        val centre = centreMidi(rootSemitone)
        val triads = (0 until 7).map { voice(rootSemitone, scale, it, listOf(0, 2, 4), centre) }
        val wideTonic = triads[0].let { t -> Stab(t.name, t.rootMidi, addOctaveRoot(t)) }
        val sevenths = (0 until 7).map { voice(rootSemitone, scale, it, listOf(0, 2, 4, 6), centre) }
        val add9 = voice(rootSemitone, scale, 0, listOf(0, 2, 4, 8), centre, suffix = "add9")
        return triads + wideTonic + sevenths + add9
    }

    /** [t] with its root doubled an octave above the voicing's top note (or the root's own octave, whichever is higher). */
    private fun addOctaveRoot(t: Stab): List<Int> {
        var octave = 12
        while (octave <= t.intervals.max()) octave += 12
        return t.intervals + octave
    }

    /**
     * The chord on scale [degree] (0 = tonic) of [key], from the scale
     * steps [steps] above it (0, 2, 4 is a triad), voiced around [centre].
     */
    internal fun voice(key: KeySpec, degree: Int, steps: List<Int>, centre: Int, suffix: String? = null): Stab =
        voice(key.rootSemitone, key.scale.intervals, degree, steps, centre, suffix)

    private fun voice(rootSemitone: Int, scale: IntArray, degree: Int, steps: List<Int>, centre: Int, suffix: String? = null): Stab {
        // Semitones above the tonic of each chord tone, kept rising (a step past the octave adds 12).
        val above = steps.map { s -> val d = degree + s; scale[d % 7] + 12 * (d / 7) }
        val rootPc = above[0]
        val chordPcs = above.map { it - rootPc }
        val stab = voiceChord(rootSemitone, rootPc, chordPcs, centre)
        if (suffix == null) return stab
        val rootName = Scales.NOTE_NAMES[(rootSemitone + rootPc) % 12]
        return stab.copy(name = rootName + if (quality(chordPcs.take(3)) == "m") "madd9" else "add9")
    }

    /**
     * The chord with root [rootPc] semitones above the tonic [rootSemitone]
     * and tones [chordPcs] above that root, voiced around [centre].
     */
    private fun voiceChord(rootSemitone: Int, rootPc: Int, chordPcs: List<Int>, centre: Int): Stab {
        // Every inversion in close position: rotate, then stack each tone above the last.
        val candidates = chordPcs.indices.map { r ->
            val rotated = chordPcs.drop(r) + chordPcs.take(r)
            val stacked = ArrayList<Int>()
            for (pc in rotated) {
                var n = pc
                while (stacked.isNotEmpty() && n <= stacked.last()) n += 12
                stacked.add(n)
            }
            // Shift the whole stack (in octaves) so its mean sits nearest the centre.
            val rootRef = 60 + rootSemitone + rootPc
            val base = stacked.map { rootRef + it }
            val mean = base.average()
            val shift = Math.round((centre - mean) / 12.0).toInt() * 12
            base.map { it + shift }
        }
        // Nearest the centre; on a tie the more compact voicing, then the lower one.
        val best = candidates.minWith(compareBy<List<Int>> { abs(it.average() - centre) }.thenBy { it.max() - it.min() }.thenBy { it.first() })
        // The root's MIDI note is the voiced note whose pitch class is the root.
        val rootMidi = best.first { ((it - (60 + rootSemitone + rootPc)) % 12 + 12) % 12 == 0 }
        val intervals = listOf(0) + best.filter { it != rootMidi }.map { it - rootMidi }
        val rootName = Scales.NOTE_NAMES[(rootSemitone + rootPc) % 12]
        return Stab(rootName + quality(chordPcs), rootMidi, intervals)
    }

    /** The chord symbol's quality from its tones above the root (0, 3, 7 is "m"). */
    internal fun quality(pcs: List<Int>): String {
        val set = pcs.map { ((it % 12) + 12) % 12 }.toSortedSet().toList()
        return when (set) {
            listOf(0, 4, 7) -> ""
            listOf(0, 3, 7) -> "m"
            listOf(0, 3, 6) -> "dim"
            listOf(0, 4, 8) -> "aug"
            listOf(0, 4, 7, 11) -> "maj7"
            listOf(0, 4, 7, 10) -> "7"
            listOf(0, 3, 7, 10) -> "m7"
            listOf(0, 3, 6, 10) -> "m7b5"
            listOf(0, 3, 7, 11) -> "mMaj7"
            listOf(0, 3, 6, 9) -> "dim7"
            else -> "(" + set.joinToString(",") + ")"
        }
    }

    /**
     * The STOOP CHORD preset the kit plays: "Small Cinema", the clean,
     * short stab (WEAR 0). WEAR's saturator runs on the summed chord, so it
     * intermodulates the tones; [ChordStabsTest] measures how much.
     */
    const val PRESET = "Small Cinema"

    /** The kit: [stabs] for [key] on STOOP CHORD, dry, each pad with two softer takes for the MPC's velocity zones. */
    fun kit(key: KeySpec = KeySpec.parse("Cm"), preset: String = PRESET): List<ArrangedPad?> =
        kitOf(stabs(key), key.label, preset)

    /** [modalStabs] as a kit, like [kit]. */
    fun modalKit(rootSemitone: Int, mode: Mode, preset: String = PRESET): List<ArrangedPad?> =
        kitOf(modalStabs(rootSemitone, mode), "${Scales.NOTE_NAMES[rootSemitone]} ${mode.label}", preset)

    /** [borrowedStabs] as a kit, like [kit]; a borrowed pad's source names the mode it comes from. */
    fun borrowedKit(key: KeySpec = KeySpec.parse("C"), preset: String = PRESET): List<ArrangedPad?> =
        kitOf(borrowedStabs(key), key.label, preset)

    private fun kitOf(stabs: List<Stab>, keyLabel: String, preset: String): List<ArrangedPad?> {
        val base = StoopPresets.forVoice(StoopVoice.CHORD).firstOrNull { it.name == preset }
            ?: error("StoopPresets has no CHORD preset '$preset'")
        return stabs.map { stab ->
            val patch = base.copy(name = stab.name, midi = stab.rootMidi, chordIntervals = stab.intervals)
            val recipe = PadRecipe(patch)
            val snip = recipe.render()
            ArrangedPad(
                snip, Stoop.drumClassFor(StoopVoice.CHORD, patch.macros), recipe.toJsonValue(),
                softVariants = Velocity.variantsAt(snip, patch, count = 2),
                displayName = stab.name,
                source = mapOf("key" to keyLabel, "chord" to stab.name, "notes" to stab.notes.joinToString(",") { Scales.nameOf(it) }) +
                    (stab.from?.let { mapOf("borrowedFrom" to it) } ?: emptyMap()),
            )
        }
    }
}
