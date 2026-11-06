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
 */
object ChordStabs {

    /** The key's tonic in the octave G3 to F#4 (MIDI 55 to 66): C is C4, B is B3, so no key sits an octave above another. */
    internal fun tonicMidi(key: KeySpec): Int = 55 + (key.rootSemitone - 7 + 12) % 12

    /** The pitch the voicings gather around: a major third above [tonicMidi], the middle of a close triad on the tonic. */
    internal fun centreMidi(key: KeySpec): Int = tonicMidi(key) + 4

    /** One pad's chord: a name, the root's MIDI note and the voicing around it. */
    data class Stab(val name: String, val rootMidi: Int, val intervals: List<Int>) {
        val notes: List<Int> get() = intervals.map { rootMidi + it }.sorted()
    }

    /** The sixteen chords for [key], A01 first. Refuses a key that is not seven notes. */
    fun stabs(key: KeySpec): List<Stab> {
        require(key.scale == Scale.MAJOR || key.scale == Scale.MINOR) {
            "chord stabs stack the key's own thirds, which needs a seven-note scale (major or minor), not ${key.label}"
        }
        val centre = centreMidi(key)
        val triads = (0 until 7).map { voice(key, it, listOf(0, 2, 4), centre) }
        val wideTonic = triads[0].let { t -> Stab(t.name, t.rootMidi, addOctaveRoot(t)) }
        val sevenths = (0 until 7).map { voice(key, it, listOf(0, 2, 4, 6), centre) }
        val add9 = voice(key, 0, listOf(0, 2, 4, 8), centre, suffix = "add9")
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
    internal fun voice(key: KeySpec, degree: Int, steps: List<Int>, centre: Int, suffix: String? = null): Stab {
        val scale = key.scale.intervals
        // Semitones above the tonic of each chord tone, kept rising (a step past the octave adds 12).
        val above = steps.map { s -> val d = degree + s; scale[d % 7] + 12 * (d / 7) }
        val rootPc = above[0]
        val chordPcs = above.map { it - rootPc }
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
            val rootRef = 60 + key.rootSemitone + rootPc
            val base = stacked.map { rootRef + it }
            val mean = base.average()
            val shift = Math.round((centre - mean) / 12.0).toInt() * 12
            base.map { it + shift }
        }
        // Nearest the centre; on a tie the more compact voicing, then the lower one.
        val best = candidates.minWith(compareBy<List<Int>> { abs(it.average() - centre) }.thenBy { it.max() - it.min() }.thenBy { it.first() })
        // The root's MIDI note is the voiced note whose pitch class is the root.
        val rootMidi = best.first { ((it - (60 + key.rootSemitone + rootPc)) % 12 + 12) % 12 == 0 }
        val intervals = listOf(0) + best.filter { it != rootMidi }.map { it - rootMidi }
        val rootName = Scales.NOTE_NAMES[(key.rootSemitone + rootPc) % 12]
        val name = if (suffix == null) quality(chordPcs) else quality(chordPcs.take(3)).let { if (it == "m") "madd9" else "add9" }
        return Stab(rootName + name, rootMidi, intervals)
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
    fun kit(key: KeySpec = KeySpec.parse("Cm"), preset: String = PRESET): List<ArrangedPad?> {
        val base = StoopPresets.forVoice(StoopVoice.CHORD).firstOrNull { it.name == preset }
            ?: error("StoopPresets has no CHORD preset '$preset'")
        return stabs(key).map { stab ->
            val patch = base.copy(name = stab.name, midi = stab.rootMidi, chordIntervals = stab.intervals)
            val recipe = PadRecipe(patch)
            val snip = recipe.render()
            ArrangedPad(
                snip, Stoop.drumClassFor(StoopVoice.CHORD, patch.macros), recipe.toJsonValue(),
                softVariants = Velocity.variantsAt(snip, patch, count = 2),
                displayName = stab.name,
                source = mapOf("key" to key.label, "chord" to stab.name, "notes" to stab.notes.joinToString(",") { Scales.nameOf(it) }),
            )
        }
    }
}
