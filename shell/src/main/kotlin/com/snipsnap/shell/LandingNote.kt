package com.snipsnap.shell

/**
 * The landing's honest little message box, as data (wave FFF).
 *
 * The share door used to answer in a two-second toast that held counts
 * only: which kit was skipped, and why, went nowhere. The design
 * language already had the answer - "error dialogs are honest little
 * message boxes" (TAPE JAM + [FINE]). The rule here: the toast stays the
 * one-line voice for a clean landing; the box appears when there is more
 * to read than a line holds - a landing with skips, a backup preflight
 * refused part of, a refusal - and it stays until read. Trouble comes
 * first, in the warn colour, with the door's own reason; what landed
 * follows; past [MAX_LINES] the rest folds into "+N MORE".
 */
object LandingNote {

    /** One line of the box; a [trouble] line is drawn in the warn colour - what was skipped or refused. */
    data class Line(val text: String, val trouble: Boolean = false)

    /** The box: a shouted [title] in LCD type, the [lines] under it in pixel type, and the one [button]. */
    data class Note(val title: String, val lines: List<Line>, val button: String = Copy.CAPTURE_BLOCKED_BUTTON)

    /** The most lines the box lists before folding the rest into "+N MORE" - a phone-height box, not a scroll. */
    const val MAX_LINES = 6

    /**
     * A kit file landed [kits] on the shelf and [skipped] the rest (the
     * door's "name: reason" strings). Null when nothing was skipped - the
     * toast's one line says it all; else that line as the title, the
     * skips first.
     */
    fun landed(displayName: String, kits: List<String>, skipped: List<String>): Note? {
        if (skipped.isEmpty()) return null
        val lines = skipped.map { Line("SKIPPED · ${shout(it)}", trouble = true) } + kits.map { Line("LANDED · ${shout(it)}") }
        return Note(Copy.landed(kits.size, skipped.size), fold(lines))
    }

    /** BACKUP: null when every kit made it; else the box naming each kit preflight refused and its reason, the packed ones after. */
    fun backedUp(packed: List<String>, skipped: Map<String, String>): Note? {
        if (skipped.isEmpty()) return null
        val lines = skipped.map { (kit, why) -> Line("SKIPPED · ${shout(kit)}: ${shout(why)}", trouble = true) } +
            packed.map { Line("PACKED · ${shout(it)}") }
        return Note(Copy.backedUp(packed.size, skipped.size), fold(lines))
    }

    /** A share the shelf refused: the file's name and the refuser's own words, kept until read rather than gone in two seconds. */
    fun refused(displayName: String, reason: String): Note =
        Note(
            Copy.NOTHING_LANDED,
            listOf(Line(shout(displayName.ifBlank { "the share" })), Line(shout(reason.ifBlank { "it said no" }), trouble = true)),
        )

    /** The house casing: uppercase in Locale.ROOT (never the phone's language), no trailing full stop. */
    fun shout(s: String): String = s.trim().uppercase(java.util.Locale.ROOT).trimEnd('.')

    private fun fold(lines: List<Line>): List<Line> {
        if (lines.size <= MAX_LINES) return lines
        val kept = MAX_LINES - 1
        val rest = lines.drop(kept)
        return lines.take(kept) + Line("+${rest.size} MORE", trouble = rest.any { it.trouble })
    }
}
