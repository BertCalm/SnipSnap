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

    /**
     * The one way on from a box: a chip under the lines that closes the box
     * and does [go]. Only a landing has somewhere to send you (a refusal's
     * way on is to fix the thing), so only [dubbed] sets one.
     */
    class Route(val label: String, val go: () -> Unit)

    /**
     * A picture for the top of a box, as the PNG bytes the app decodes. A
     * class of its own and not a bare `ByteArray` so [Note] stays a data
     * class without comparing pixels.
     */
    class Tile(val png: ByteArray)

    /**
     * The box: a shouted [title] in LCD type, the [lines] under it in pixel
     * type, and the one [button]; optionally a [tile] above the title and a
     * [route] between the lines and the button.
     */
    data class Note(
        val title: String,
        val lines: List<Line>,
        val button: String = Copy.CAPTURE_BLOCKED_BUTTON,
        val route: Route? = null,
        val tile: Tile? = null,
    )

    /** The most lines the box lists before folding the rest into "+N MORE" - a phone-height box, not a scroll. */
    const val MAX_LINES = 6

    /**
     * A kit file landed [kits] on the shelf and [skipped] the rest (the
     * door's "name: reason" strings). Null when nothing was skipped - the
     * toast's one line says it all; else that line as the title, the
     * skips first.
     */
    fun landed(displayName: String, kits: List<String>, skipped: List<String>, presets: Int = 0): Note? {
        if (skipped.isEmpty()) return null
        val lines = skipped.map { Line("SKIPPED · ${shout(it)}", trouble = true) } + kits.map { Line("LANDED · ${shout(it)}") }
        return Note(Copy.landed(kits.size, skipped.size, presets), fold(lines))
    }

    /** BACKUP: null when every kit made it; else the box naming each kit preflight refused and its reason, the packed ones after. [presets] rode along beside them. */
    fun backedUp(packed: List<String>, skipped: Map<String, String>, presets: Int = 0): Note? {
        if (skipped.isEmpty()) return null
        val lines = skipped.map { (kit, why) -> Line("SKIPPED · ${shout(kit)}: ${shout(why)}", trouble = true) } +
            packed.map { Line("PACKED · ${shout(it)}") }
        return Note(Copy.backedUp(packed.size, skipped.size, presets), fold(lines))
    }

    /**
     * EXPORT refused the write (J35): the box naming every FAIL that
     * blocked it.
     *
     * Null when nothing blocks — only `Severity.FAIL` does. A WARN exports
     * anyway (`Preflight`'s own rule: "WARNs export anyway; FAILs block,
     * because 'it exported but the kit is broken' is the worst outcome a
     * tool that writes to someone's SD card can produce"), so a box raised
     * on warnings would tell the user their export failed when it did not.
     *
     * The box rather than a toast, and rather than nothing at all. Nothing
     * at all is what this was: the justification was that the refreshed
     * checklist is the message, but the checklist is the first card in a
     * scroll and the button is pinned at the bottom, so on a phone the row
     * that changed is very likely off-screen at the moment of the tap — and
     * a refusal you have to go looking for is a refusal you experience as
     * the button doing nothing. This box already listed what a *backup*
     * preflight refused ([backedUp]); EXPORT's own refusal did not use it.
     */
    fun exportBlocked(findings: List<com.snipsnap.kit.Finding>): Note? {
        val blocking = findings.filter { it.severity == com.snipsnap.kit.Severity.FAIL }
        if (blocking.isEmpty()) return null
        return Note(
            Copy.EXPORT_BLOCKED,
            fold(blocking.map { Line("BLOCKED · ${shout(it.message)}", trouble = true) }),
        )
    }

    /** A share the shelf refused: the file's name and the refuser's own words, kept until read rather than gone in two seconds. */
    fun refused(displayName: String, reason: String): Note =
        Note(
            Copy.NOTHING_LANDED,
            listOf(Line(shout(displayName.ifBlank { "the share" })), Line(shout(reason.ifBlank { "it said no" }), trouble = true)),
        )

    /**
     * WRITE KIT or WRITE SESSION landed: the box that says what, and where.
     * The title names the thing and where it is ([Copy.dubOnCard] when the
     * card has it, [Copy.dubOnPhone] when only the phone does); the lines are
     * the fact the track showed while it was queued - how many files, and the
     * two sides they fill - and [where], the path as it is (a path is
     * case-sensitive, so it is not shouted like the rest).
     *
     * [share] is the only way on and is the caller's to give: SHARE KIT packs
     * the *open kit*, so a session, which is not that file, passes null
     * rather than a door that sends something else. [tile] is the browser
     * tile the dub wrote, for the formats that write one.
     */
    fun dubbed(
        kit: String,
        onCard: Boolean,
        files: Int,
        where: String,
        share: (() -> Unit)? = null,
        tile: Tile? = null,
    ): Note = Note(
        title = if (onCard) Copy.dubOnCard(kit) else Copy.dubOnPhone(kit),
        lines = listOf(
            Line("${Copy.countOf(files, "FILE", "FILES")} · ${ExportWizardModel.DUB_SIDES}"),
            Line(where),
        ),
        route = share?.let { Route(SHARE_ROUTE, it) },
        tile = tile,
    )

    /** The label on [dubbed]'s way on: the same door as OUT's ALSO row, which says what it shares. */
    const val SHARE_ROUTE = "SHARE ▸"

    /**
     * A dub threw — storage pulled, disk full, permission yanked. A box
     * rather than the dock's two lines, because the line is replaced by the
     * next thing said and this one needs the user to go and do something.
     */
    fun dubFailed(): Note = Note(Copy.DUB_FAILED, emptyList())

    /** DUST threw: the title, and [reason] — the exception's own words — in the warn colour. */
    fun dustFailed(reason: String): Note =
        Note(Copy.DUST_FAILED, listOf(Line(shout(reason.ifBlank { "it said no" }), trouble = true)))

    /** The house casing: uppercase in Locale.ROOT (never the phone's language), no trailing full stop, no space left where it was. */
    fun shout(s: String): String = s.uppercase(java.util.Locale.ROOT).trim().trimEnd('.').trimEnd()

    private fun fold(lines: List<Line>): List<Line> {
        if (lines.size <= MAX_LINES) return lines
        val kept = MAX_LINES - 1
        val rest = lines.drop(kept)
        return lines.take(kept) + Line("+${rest.size} MORE", trouble = rest.any { it.trouble })
    }
}
