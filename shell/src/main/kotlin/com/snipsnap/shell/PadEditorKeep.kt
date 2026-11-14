package com.snipsnap.shell

/**
 * What decides how long the pad editor's saved state lives (spec §7.3.1, "Screens in the stack
 * keep their state"). The editor leaves composition when GRAIN FIELD, SPLICE or STACK opens over
 * it, and `App` parks its state in a `SaveableStateHolder` under [PadEditorKeys.keyFor]. The state
 * must come back when Back lands on the editor, and must not come back at any other time: not for
 * another pad, not for another kit, not after the editor has left the stack.
 *
 * Kept free of Compose so the rules are testable on the JVM: the holder's `removeState` is passed in.
 */
object PadEditorKeep {
    /** The benches that are the pad editor or draw as it (EFFECTS and VARIATIONS are its own pages). */
    val EDITOR_BENCHES: Set<Bench> = setOf(Bench.PAD_SHEET, Bench.PAD_EFFECTS, Bench.PAD_VARIATIONS)

    /** Whether the pad editor is anywhere in [location]'s stack, on top or under a tool. */
    fun inStack(location: Location): Boolean = location.benches.any { it in EDITOR_BENCHES }

    /** The holder key for the editor over the kit at [kitDirPath]. The pad is not in it: stepping pads re-keys the state itself. */
    fun keyFor(kitDirPath: String): String = "pad-editor:$kitDirPath"
}

/**
 * The keys handed to the holder so far, so they can all be dropped together. One editor is in the
 * stack at a time, but the kit it is over can change, so a key is not assumed to be the last one used.
 */
class PadEditorKeys(restored: Collection<String> = emptyList(), private val removeState: (String) -> Unit) {
    // [restored]: the keys a previous process had handed out. The holder's parked state is saved to the
    // instance Bundle and comes back under exactly these keys after process death, while navigation does
    // not (the new process starts on the shelf), so [release] must know them or the next pad opened on
    // the same kit inherits the old pad's state.
    private val used = linkedSetOf<String>().apply { addAll(restored) }
    private var kitDir: String? = null
    private var slot: Int? = null

    /** The keys handed out so far, for the saved instance state ([restored] on the way back in). */
    fun heldKeys(): List<String> = used.toList()

    /** The key to draw the editor under now, remembered for [release]. */
    fun keyFor(kitDirPath: String): String = PadEditorKeep.keyFor(kitDirPath).also { used += it }

    /**
     * The editor is being opened (not stepped) on [kitDirPath] and [newSlot]. Saved state belongs to
     * one pad: a different pad or kit opened while saved state is still held must not inherit it.
     * Opening the same pad again keeps what is parked. (RE-TRIM and AUDITION leave through a place switch, so the editor has left the stack and been released by then: Back from them lands on an editor with its defaults, as before.)
     */
    fun opened(kitDirPath: String, newSlot: Int) {
        if (kitDirPath != kitDir || newSlot != slot) release()
        kitDir = kitDirPath
        slot = newSlot
    }

    /** The editor stepped to another pad in place (the pad arrows). */
    fun retarget(newSlot: Int) {
        slot = newSlot
    }

    /** The editor left the stack: nothing it held may come back. */
    fun release() {
        used.forEach(removeState)
        used.clear()
        kitDir = null
        slot = null
    }
}
