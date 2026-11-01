package com.snipsnap.shell

/**
 * What a keyboard's controls can do on KIT, through MIDI learn: a key, pad
 * or button on [MAP] steps the footer's MIDI MAP (the way the keyboard's
 * notes land on the sixteen pads), and one on [PANIC] is the footer's PANIC.
 * Both fire once per press, like the buttons they stand for.
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

    /** What the learn panel offers, in its order, with each one's chip. */
    val TARGETS: List<Pair<String, String>> = listOf(MAP to "MAP", PANIC to "PANIC")

    /** Both are buttons: a press fires, whatever sends it. */
    fun prefer(target: String): MidiBinding.Mode? =
        if (target == MAP || target == PANIC) MidiBinding.Mode.TRIGGER else null

    /** Messages that can fire: a bend strip or pressure brushed while MAP or PANIC waits is not learned. */
    fun kinds(target: String): Set<MidiBinding.Kind>? =
        if (target == MAP || target == PANIC) setOf(MidiBinding.Kind.NOTE, MidiBinding.Kind.CC, MidiBinding.Kind.PROGRAM) else null

    /** What some actions ask of the screen: how many times to step the map, and whether to panic. */
    data class Change(val mapSteps: Int, val panic: Boolean)

    fun changes(actions: List<MidiLearn.Action>): Change {
        var steps = 0
        var panic = false
        for (a in actions) if (a is MidiLearn.Fire) when (a.target) {
            MAP -> steps++
            PANIC -> panic = true
        }
        return Change(steps, panic)
    }
}
