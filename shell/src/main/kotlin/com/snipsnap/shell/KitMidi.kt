package com.snipsnap.shell

/**
 * What a keyboard's controls can do on KIT, through MIDI learn: a key, pad
 * or button on [MAP] steps the footer's MIDI MAP (the way the keyboard's
 * notes land on the sixteen pads), and one on [PANIC] is the footer's PANIC.
 * Both fire once per press, like the buttons they stand for. A knob or
 * fader on [VOLUME] is the kit's volume, the whole mix through the engine's
 * master gain on the same square law as KEYS' ([KeysMidi.gain]); like KEYS'
 * it jumps from full rather than waiting for pickup, since each visit
 * starts the kit as it was made.
 *
 * A control learned here is consumed, so a pad on PANIC no longer plays a
 * pad as well; the panics a keyboard sends itself (CC 120 and 123) are never
 * learned and still reach the pads' own rules.
 */
object KitMidi {

    /** The screen's name in MIDI learn's targets and in [MidiLearnStore.of]. */
    const val SCREEN = "kit"

    const val MAP = "$SCREEN.map"
    const val PANIC = "$SCREEN.panic"
    const val VOLUME = "$SCREEN.volume"

    /** What the learn panel offers, in its order, with each one's chip. */
    val TARGETS: List<Pair<String, String>> = listOf(VOLUME to "VOL", MAP to "MAP", PANIC to "PANIC")

    /** A VOL knob learned here jumps rather than picks up, as KEYS' does. */
    fun settle(learned: MidiBinding): MidiBinding =
        if (learned.target == VOLUME && learned.mode == MidiBinding.Mode.ABSOLUTE) learned.copy(takeover = MidiBinding.Takeover.JUMP) else learned

    /** Both are buttons: a press fires, whatever sends it. */
    fun prefer(target: String): MidiBinding.Mode? =
        if (target == MAP || target == PANIC) MidiBinding.Mode.TRIGGER else null

    /** Messages that can fire: a bend strip or pressure brushed while MAP or PANIC waits is not learned. */
    fun kinds(target: String): Set<MidiBinding.Kind>? =
        if (target == MAP || target == PANIC) setOf(MidiBinding.Kind.NOTE, MidiBinding.Kind.CC, MidiBinding.Kind.PROGRAM) else null

    /**
     * What some actions ask of the screen: how many times to step the map,
     * whether to panic, and the volume knob's new position 0..1 (null: not moved).
     */
    data class Change(val mapSteps: Int, val panic: Boolean, val volume: Float? = null)

    fun changes(actions: List<MidiLearn.Action>): Change {
        var steps = 0
        var panic = false
        var volume: Float? = null
        for (a in actions) when {
            a is MidiLearn.Fire && a.target == MAP -> steps++
            a is MidiLearn.Fire && a.target == PANIC -> panic = true
            a is MidiLearn.SetValue && a.target == VOLUME -> volume = a.value.coerceIn(0f, 1f)
        }
        return Change(steps, panic, volume)
    }
}
