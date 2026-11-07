package com.snipsnap.synth

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE

/**
 * ARCO held: a bowed note as a keygroup zone that sounds while a key is down
 * (docs/superpowers/specs/2026-11-04-arco-research.md section 9, and the design record's "R2: the held keygroup, as built").
 *
 * Each zone is one file: the bow's own stroke (the attack a one-shot at the same BOW plays, its bite included), then exactly one loop of
 * whole periods, with the loop marker where that loop starts ([KeyNote.loopStartFrame] over 0). The stroke keeps its own character for
 * 80 ms and has handed over to the loop by 300 ms ([HeldStart.SHORT], the owner's pick of 2026-11-06: the full stroke and the string's
 * settling made the hit too long and too unlike the held part). A held key sustains on the loop, which is the shipped LOOP's ([Arco.renderLoopMeasured]): the
 * same retune, the same humanised vibrato fitted to the loop ([Arco.LOOP_VIBRATO], the variant the owner picked on 2026-11-04), the
 * same BUZZ with its noise repeating with the loop. The render is [Arco.renderHeldMeasured]; this file is the zone plan, the two
 * velocity layers and the refusal.
 *
 * Never a crossfade. The seam is the file's own ([Keys.seamError] at the loop start, the Organ's bar [Keys.MAX_SEAM_ERROR]); a zone that
 * does not close is rendered once more at twice the warm-up, and if it still does not close it is refused by name ([Keys.requireSeam]).
 *
 * Velocity is timbre, never level (`Velocity.peakMatch`'s rule): both layers are levelled on their loop region to the same target, and
 * the player's velocity curve owns the level. TUNE plays no part (every zone is its key's exact pitch); HOLD plays no part (a held key
 * sustains). Nobody has heard a held key.
 */
object ArcoKeys {

    /** The zones step by a minor third: every third semitone of the voice's TUNE travel from its root. */
    const val ZONE_STEP = 3

    /**
     * The soft layer's share of the patch's BOW and GRIP: 0.28 of each, the research's default for decision 4 (`atVelocity`'s floor ratio,
     * 0.9 / 3.2, as the research's own table rounds it). BOW 0.5 becomes 0.14, a 239 ms stroke with no bite; GRIP 0.6 becomes 0.168, a lighter
     * grip and a darker corner (CELLO C3's from 1942 Hz to 1204 Hz, the research's computed table). Every GRIP from 0 to 1 locks at every
     * TUNE step at BOW 0.5 (0 of 495 cells fail, R1b), so a soft grip is inside the window by construction. A listening value.
     */
    const val SOFT_SHARE = 0.28f

    /** A zone's two velocity layers, soft first: the order a keygroup's layers run in. */
    enum class Layer { SOFT, HARD }

    /** The keys a held [voice] plays: its TUNE travel from its root (CELLO C2 to C4, ERHU D4 to A5, SARANGI D3 to D5). */
    fun midiRange(voice: ArcoVoice): IntRange = Arco.rootMidi(voice)..Arco.rootMidi(voice) + Arco.tuneSemitones(voice)

    /**
     * The zone roots for [voice]: every [ZONE_STEP] semitones from the root, and the top of the voice's travel as a root of its own where
     * the steps miss it (Crease's "exact roots at clipped range boundaries"). CELLO: nine zones, C2 to C4. ERHU: eight, D4 to G#5 and A5. SARANGI:
     * nine, D3 to D5 (its 24 semitones land on its top, so no extra root is added).
     * ERHU's next steps up, B5 and D6, are past its top: R1b found no grip that holds one slip a period at D6, B5 was the last step that
     * did, and ERHU stops at A5 ([Arco.ERHU_TUNE_SEMITONES]), so those keys are refused by name ([macrosFor]) and the top zone repitches
     * up to them.
     */
    fun zoneMidis(voice: ArcoVoice): List<Int> {
        val top = Arco.tuneSemitones(voice)
        val steps = (0..top step ZONE_STEP).toList()
        return (if (steps.last() == top) steps else steps + top).map { Arco.rootMidi(voice) + it }
    }

    /**
     * The macros a held [voice] renders at [midi] in [layer]: [macros] settled (every key at its default, every value in 0..1), TUNE set to
     * the key, and for [Layer.SOFT] BOW and GRIP at [SOFT_SHARE] of their values. Refused by name for a key outside [midiRange].
     */
    fun macrosFor(voice: ArcoVoice, macros: Map<String, Float>, midi: Int, layer: Layer): Map<String, Float> {
        val range = midiRange(voice)
        require(midi in range) {
            "ARCO $voice keys are MIDI ${range.first}..${range.last} (${Scales.nameOf(range.first)} to ${Scales.nameOf(range.last)}), " +
                "got $midi (${Scales.nameOf(midi.coerceIn(0, 127))})"
        }
        val m = Arco.settled(macros, voice).toMutableMap()
        m["TUNE"] = (midi - Arco.rootMidi(voice)) / Arco.tuneSemitones(voice).toFloat()
        check(Arco.midiFor(voice, m.getValue("TUNE")) == midi) { "ARCO $voice: TUNE ${m["TUNE"]} does not land on MIDI $midi" }
        if (layer == Layer.SOFT) {
            m["BOW"] = m.getValue("BOW") * SOFT_SHARE
            m["GRIP"] = m.getValue("GRIP") * SOFT_SHARE
        }
        return m
    }

    /** One layer of one zone, with what the render measured and whether the retry at twice the warm-up was needed. */
    internal class Zone(val note: KeyNote, val render: Arco.HeldRender, val retried: Boolean)

    /** [pad] with its measurements; [law] is for the refusal's control (a free-running BUZZ noise, which does not close). */
    internal fun padMeasured(
        voice: ArcoVoice, macros: Map<String, Float>, midi: Int, layer: Layer,
        cancelled: () -> Boolean = { false }, law: Arco.BuzzLaw = Arco.BUZZ_LAW,
    ): Zone {
        val m = macrosFor(voice, macros, midi, layer)
        fun note(r: Arco.HeldRender) = KeyNote(Snip(r.samples, channels = 1, sampleRate = RATE), loopStartFrame = r.loopStart.toLong())
        val first = Arco.renderHeldMeasured(voice, m, warmScale = 1, law = law, cancelled = cancelled)
        if (first.seam < Keys.MAX_SEAM_ERROR) return Zone(note(first), first, retried = false)
        // One retry with twice the warm-up: a string that locks late is still settling after it has locked. A second failure is
        // refused, naming the zone: never a crossfade, never a click shipped.
        val second = Arco.renderHeldMeasured(voice, m, warmScale = 2, law = law, cancelled = cancelled)
        val label = "ARCO $voice ${layer.name.lowercase()} layer at ${Scales.nameOf(midi)}, retried at twice the warm-up " +
            "(the first try's seam %.2e)".format(java.util.Locale.ROOT, first.seam)
        Keys.requireSeam(label, second.samples, second.loopStart)
        return Zone(note(second), second, retried = true)
    }

    /**
     * One held key: [voice] at [midi] in [layer], the stroke then the loop, levelled on the loop and held under 0.99, its start shaped by
     * [hit] ([HeldStart.shape]; null keeps the whole stroke and the string's settling, as the zone was first built, for the audition's
     * reference). A key outside the voice's range, or a zone whose loop does not close at twice the warm-up, is refused by name. [cancelled]
     * is asked before every pass of the retune (a pass is a few hundred milliseconds).
     */
    fun pad(
        voice: ArcoVoice, macros: Map<String, Float>, midi: Int, layer: Layer, hit: HeldStart.Hit? = HeldStart.SHORT,
        cancelled: () -> Boolean = { false },
    ): KeyNote = HeldStart.shape(padMeasured(voice, macros, midi, layer, cancelled).note, hit)

    /** Both layers of the zone at [midi], soft first, each with its start shaped by [hit]. */
    fun layers(
        voice: ArcoVoice, macros: Map<String, Float>, midi: Int, hit: HeldStart.Hit? = HeldStart.SHORT,
        cancelled: () -> Boolean = { false },
    ): List<KeyNote> = Layer.entries.map { pad(voice, macros, midi, it, hit, cancelled) }
}
