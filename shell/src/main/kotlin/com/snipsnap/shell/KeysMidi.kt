package com.snipsnap.shell

/**
 * What a keyboard's controls can do on KEYS, through MIDI learn: a knob
 * or fader on [VOLUME], and two buttons, keys or pads on [OCT_DOWN] and
 * [OCT_UP], the screen's own OCT − and OCT + (which also let every note go).
 *
 * The rules are here and the screen applies the [Change]: what volume to
 * set and which way to step the octave. A control learned here is
 * consumed, so a key on OCT + stops playing its note.
 */
object KeysMidi {

    /** The screen's name in MIDI learn's targets and in [MidiLearnStore.of]. */
    const val SCREEN = "keys"

    const val VOLUME = "$SCREEN.volume"
    const val OCT_DOWN = "$SCREEN.octDown"
    const val OCT_UP = "$SCREEN.octUp"

    /** What the learn panel offers, in its order, with each one's chip. */
    val TARGETS: List<Pair<String, String>> = listOf(VOLUME to "VOL", OCT_DOWN to "OCT −", OCT_UP to "OCT +")

    /** The octave buttons fire once per press, whatever sends them; volume follows. */
    fun prefer(target: String): MidiBinding.Mode? =
        if (target == OCT_DOWN || target == OCT_UP) MidiBinding.Mode.TRIGGER else null

    /**
     * [learned] as KEYS keeps it. A knob on VOL jumps rather than picks up:
     * the screen has no volume control of its own and starts each visit at
     * full, so pickup would leave the knob dead until it reached the top,
     * and a jump from full can only make it quieter.
     */
    fun settle(learned: MidiBinding): MidiBinding =
        if (learned.target == VOLUME && learned.mode == MidiBinding.Mode.ABSOLUTE) learned.copy(takeover = MidiBinding.Takeover.JUMP) else learned

    /**
     * The octave buttons learn from keys, pads and buttons only: a bend strip
     * or pressure brushed while one waits would bind a control that never
     * fires. VOL takes anything continuous, a strip included.
     */
    fun kinds(target: String): Set<MidiBinding.Kind>? =
        if (target == OCT_DOWN || target == OCT_UP) setOf(MidiBinding.Kind.NOTE, MidiBinding.Kind.CC, MidiBinding.Kind.PROGRAM) else null

    /** What some actions ask of the screen: a new [volume] (0..1), or null to keep it, and an [octaveStep]. */
    data class Change(val volume: Float?, val octaveStep: Int)

    fun changes(actions: List<MidiLearn.Action>): Change {
        var volume: Float? = null
        var step = 0
        for (a in actions) when {
            a is MidiLearn.SetValue && a.target == VOLUME -> volume = a.value.coerceIn(0f, 1f)
            a is MidiLearn.Fire && a.target == OCT_DOWN -> step--
            a is MidiLearn.Fire && a.target == OCT_UP -> step++
        }
        return Change(volume, step)
    }

    /** [octave] moved by [step] and held inside the grid's range, as OCT − and OCT + are. */
    fun octave(octave: Int, step: Int): Int = (octave + step).coerceIn(KeysLayout.OCTAVE_MIN, KeysLayout.OCTAVE_MAX)

    /**
     * The gain a volume position plays at: the square, so the knob's travel
     * is spread over the loudness an ear hears (half way is about −12 dB,
     * not −6), and full is the instrument exactly as it was made.
     */
    fun gain(volume: Float): Float = volume.coerceIn(0f, 1f).let { it * it }

    /** The volume as the screen prints it. */
    fun label(volume: Float): String = "VOL ${Math.round(volume.coerceIn(0f, 1f) * 100)}%"
}
