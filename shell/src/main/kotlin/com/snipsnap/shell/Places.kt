package com.snipsnap.shell

import com.snipsnap.kit.Project
import java.util.Locale

/**
 * The words of the four-place shape (`docs/SCREEN_PLAN_2026_10.md`): the
 * places and stages as the rows draw them, the dock's labels and doors,
 * the kit switcher's line, and HELP by place. Labels and lists, not
 * sentences — the sentences (the first-run note, the dock's resting
 * line) are in [Copy], where the case law holds them. The two kinds are
 * marked the same way here as there: [CopyLabel] (CAPS) and [CopyCaption]
 * (a sentence fragment, such as a row's description), and an unmarked
 * string is a message. HELP's lines are messages, in sentence case.
 *
 * Beside [Copy] rather than inside it on purpose: everything here is
 * furniture that stays on screen, mostly labels. `PersonalityTest` holds
 * both objects to the same law for each kind.
 */
object Places {

    /** The place row, in order. */
    val PLACES: List<String> = Place.entries.map { it.label }

    /** The stage rows. */
    val TAPE_STAGES: List<String> = Stage.of(Place.TAPE).map { it.label }
    val PROJECT_STAGES: List<String> = Stage.of(Place.PROJECT).map { it.label }

    // ---- the dock ----

    /** Doors the dock can hold. Each carries its own arrow, as every door label does. */
    @field:CopyLabel const val DOOR_CUT = "CHOP ▸"
    @field:CopyLabel const val DOOR_PADS = "PADS ▸"
    @field:CopyLabel const val DOOR_BEAT = "BEAT ▸"
    @field:CopyLabel const val DOOR_OUT = "EXPORT ▸"

    @field:CopyLabel const val LISTEN = "LISTEN · MIC"

    /**
     * The dock's LISTEN, as the player has set it up. [LISTEN] stays the word before any choice is
     * kept (the first run still shows the sheet, and the copy that points at "LISTEN · MIC" is
     * true until then); once a source is kept the chip names it, so `LISTEN · APP AUDIO` is never
     * mistaken for the mic.
     */
    fun listen(source: CaptureSource?): String = if (source == null) LISTEN else "LISTEN · ${source.label}"

    /**
     * The cold shelf's one filled button: what LISTEN · MIC then SNIP does,
     * asked for in the player's own terms. It arms the same mic session the
     * dock's LISTEN does; its accessible name is its label.
     */
    @field:CopyLabel const val CATCH_FIRST = "LISTEN FOR YOUR FIRST SOUND"

    /** The mic-denied box's way out: the system's page for this app, where the permission is switched on. No arrow: that page is Android's, not a screen of ours. */
    @field:CopyLabel const val OPEN_SETTINGS = "OPEN SETTINGS"
    @field:CopyLabel const val SNIPPED = "SNIPPED"
    @field:CopyLabel const val STOPPED = "■"

    /** The live transport chip's face while the beat is stopped: what a tap does, where [STOPPED] read as the stop. */
    @field:CopyLabel const val PLAY_GLYPH = "▶"

    /** The dock's SNIP, shown while armed. No arrow: it takes the last 60 s, it opens nothing. */
    @field:CopyLabel const val SNIP = "SNIP"

    /** The dock's STOP, beside SNIP while armed: the session ends. The shelf's ArmControl said the same word in bin red. */
    @field:CopyLabel const val STOP = "STOP"

    /** The transport chip's accessible names, wherever a kit is open — the beat's own PLAY / STOP, read by what they do to the beat. */
    @field:CopyLabel const val PLAY_BEAT = "PLAY THE BEAT"
    @field:CopyLabel const val STOP_BEAT = "STOP THE BEAT"

    /**
     * BEAT's SHARE chip, beside BOUNCE TO SNIPS: the pattern as two passes of
     * audio, handed to the share sheet. No arrow: an action, and the sheet is Android's. Spoken
     * as [SHARE_BEAT_SPOKEN], because "share" alone does not say what.
     */
    @field:CopyLabel const val SHARE_BEAT = "SHARE"
    @field:CopyLabel const val SHARE_BEAT_SPOKEN = "SHARE THIS BEAT"

    /**
     * The first row of the dock's capture choice, in its sheet: the room, through
     * the mic. The chip above already says LISTEN · MIC, so the row says what it is
     * for on a line under the name, as [ARM_INSIDE_DESC] does for the other.
     * The row's accessible name stays [LISTEN].
     */
    @field:CopyLabel const val ARM_MIC = "MIC"
    @field:CopyCaption const val ARM_MIC_DESC = "Record this room"

    /** The second row of the dock's capture choice: another app's audio, with the projection consent that it opens. */
    @field:CopyLabel const val ARM_INSIDE = "APP AUDIO"
    @field:CopyCaption const val ARM_INSIDE_DESC = "Record an app"

    /** The capture sheet's title. */
    @field:CopyLabel const val ARM_SHEET_TITLE = "LISTEN FROM"

    /** `ARMED · 00:41 ON TAPE` — the shelf's counter, now on every screen. */
    fun armed(secondsOnTape: Int): String {
        val s = secondsOnTape.coerceAtLeast(0)
        return "LISTENING · %02d:%02d BUFFERED".format(Locale.ROOT, s / 60, s % 60)
    }

    fun playing(bpm: Float?, bar: Int): String =
        (bpm?.let { "▶ %.0f BPM".format(Locale.ROOT, it) } ?: "▶") + " · BAR ${bar.coerceAtLeast(1)}"

    fun recording(bar: Int): String = "● BAR ${bar.coerceAtLeast(1)}"

    // ---- PROJECT ▸ PADS ----

    /** The kit switcher above the grid: `KITCHEN · 2 KITS IN THIS PROJECT ▾`, or the empty project's line. The `▾`: the chip opens a sheet. */
    fun switcher(project: Project): String {
        val active = project.activeKit ?: return NO_KITS
        val n = project.kits.size
        val kits = if (n == 1) "KIT" else "KITS"
        return "${active.uppercase(Locale.ROOT)} · $n $kits IN THIS PROJECT ▾"
    }

    /**
     * The name chip's accessible name: what a tap does, then the fact the
     * drawn chip has no room for. The line is [switcher]'s, and its `▾`
     * is dropped on the way: a screen reader would read it out as a shape,
     * and "SWITCH KIT" already says the chip opens a list.
     */
    fun switchKitName(line: String): String = "SWITCH KIT · " + line.removeSuffix(" ▾")

    @field:CopyLabel const val NO_KITS = "NO KITS YET"
    @field:CopyLabel const val ADD_FROM_SHELF = "+ FROM LIBRARY"

    /** The header chip that opens [ADD_FROM_SHELF]: the same door, in the room the header has. */
    @field:CopyLabel const val ADD_KIT = "+ KIT"

    /** The starter sheet: its title, and the tag on a starter that rerolls on every tap. */
    @field:CopyLabel const val STARTER_SHEET_TITLE = "PICK A STARTER"
    @field:CopyLabel const val STARTER_REROLL_TAG = "TAPS REROLL"

    /** The shelf's TOOLS sheet: its title, and each tool as a name and the line under it. */
    @field:CopyLabel const val TOOLS_TITLE = "TOOLS"
    @field:CopyLabel const val TOOL_BACKUP = "BACKUP"
    @field:CopyCaption const val TOOL_BACKUP_DESC = "Every kit, one file"
    @field:CopyLabel const val TOOL_CHOP_ALL = "CHOP ALL"
    @field:CopyCaption const val TOOL_CHOP_ALL_DESC = "Every file, one kit"
    @field:CopyLabel const val TOOL_XRAY = "INSPECT FILE"
    @field:CopyCaption const val TOOL_XRAY_DESC = "Inspect a file"
    @field:CopyLabel const val TOOL_DOUBLES = "FIND SIMILAR SOUNDS"
    @field:CopyCaption const val TOOL_DOUBLES_DESC = "Same sound, any kit"

    /**
     * The project row's menu: each action a name and the line under it. The names
     * a screen reader hears carry the project's, and are made where the row is drawn.
     */
    @field:CopyLabel const val PROJECT_RENAME = "RENAME"
    @field:CopyCaption const val PROJECT_RENAME_DESC = "Give the project a new name"
    @field:CopyLabel const val PROJECT_DELETE = "DELETE"
    @field:CopyCaption const val PROJECT_DELETE_DESC = "To the trash, kits stay in the LIBRARY"
    @field:CopyLabel const val PROJECT_DROP = "REMOVE A KIT"
    @field:CopyCaption const val PROJECT_DROP_DESC = "Take one out, it stays in the LIBRARY"

    /** The kit sheet's title. */
    @field:CopyLabel const val SHEET_KITS_TITLE = "SWITCH KIT"

    /** The kit sheet's last row, and the line under it: it opens the shelf in pick mode. */
    @field:CopyLabel const val SHEET_ADD_FROM_SHELF = "+ FROM LIBRARY"
    @field:CopyCaption const val SHEET_ADD_FROM_SHELF_DESC = "Put another kit in this project"

    /**
     * One row of the kit sheet. The active kit is marked in words and a
     * dot, never colour alone, and not "ON THE GRID" - that is the
     * quantize language (CUT's ON THE GRID, GROOVE's), a different fact.
     */
    fun kitRow(name: String, active: Boolean): String {
        val upper = name.uppercase(Locale.ROOT)
        return if (active) "● $upper · ACTIVE" else upper
    }

    /**
     * A control that opens a sheet: its label, then `▾` (the arrow rule, clarity
     * spec 7.3). The label is the control's current value where it has one, so
     * the closed control says what is chosen and the sheet says what else there is.
     */
    fun opener(label: String): String = "$label ▾"

    /** A row of a picking sheet: the chosen one is marked `● `, in words' reach and never colour alone. */
    fun pickedRow(name: String, picked: Boolean): String = if (picked) "● $name" else name

    /** The shelf's header while `+ FROM SHELF` is picking a kit for [project]: the third pick mode beside the snip's and BREED's. */
    fun addPickHeader(project: String): String = "PICK A KIT FOR ${project.uppercase(Locale.ROOT)}"

    /**
     * The closed THIS KIT group's second line: the tools inside that
     * [thisKit] does not name (it names KEY, BANK B and VERSIONS). Each word is a
     * button's or section's name inside: SPLIT, DUST ALL, BREED, SCULPT, STRETCH, ROUND ROBIN.
     */
    @field:CopyCaption const val THIS_KIT_ALSO = "Also: SPLIT · DUST · COMBINE KITS · SCULPT · STRETCH · ROUND ROBIN"

    /** THIS KIT's heading over REMIX BANK B. */
    @field:CopyLabel const val KIT_BANK_B = "BANK B"

    /** THIS KIT's heading over KEY, DUST ALL, SCULPT, STRETCH, ROUND ROBIN and SPLIT. */
    @field:CopyLabel const val KIT_WHOLE_SOUND = "THE WHOLE KIT'S SOUND"

    /** THIS KIT's heading over VERSIONS + BIN and BREED. */
    @field:CopyLabel const val KIT_VERSIONS_AND_KITS = "VERSIONS AND OTHER KITS"

    /** KEY's opener inside THIS KIT: a picker, so it opens a sheet (a trailing ▾). */
    @field:CopyLabel const val KEY_OPEN = "KEY ▾"

    /** The KEY sheet's title. */
    @field:CopyLabel const val KEY_SHEET_TITLE = "KEY"

    /**
     * The closed line of a SCULPT or STRETCH section inside THIS KIT. With a pad
     * to read from it says what GO would render: the mode, the knob's value and
     * the pad ("CLOUD · 8 S · FROM A07"). With no pad yet it says what is inside,
     * the modes ("CLOUD · SCRUB · SWARM"), never an empty or untouched word.
     */
    fun textureLine(modes: List<String>, mode: String, knobText: String, sourceTag: String?): String =
        if (sourceTag == null) modes.joinToString(" · ") else "$mode · ${knobText.uppercase(Locale.ROOT)} · FROM $sourceTag"

    /** The closed THIS KIT group's line: what it holds, like a pad sheet box. */
    fun thisKit(keyLabel: String?, bankBEmpty: Boolean, versions: Int): String {
        val parts = mutableListOf<String>()
        parts += keyLabel?.let { "KEY $it" } ?: "NO KEY"
        parts += if (bankBEmpty) "BANK B EMPTY" else "BANK B IN USE"
        parts += "$versions ${if (versions == 1) "VERSION" else "VERSIONS"}"
        return parts.joinToString(" · ")
    }

    // ---- HELP, by place ----

    /**
     * HELP rewritten as one short section per place and one for the dock,
     * three to five lines each. The first section's first line names the
     * MPC: the app is built for its owner and the front door never said
     * so (persona review, P2). The 21 WORTH KNOWING lines are redistributed
     * under the place they belong to; a line about a bench sits under the
     * place that holds the bench.
     */
    val HELP: Map<String, List<String>> = linkedMapOf(
        "LIBRARY" to listOf(
            "· Your projects, and the kits, samples and instruments they share.",
            "· A kit is an MPC program. A project is an MPC project. The card reads both.",
            "· Hold a kit to rename it, or to trash it. The trash keeps it ${Reversal.DAYS} days.",
            "· TOOLS holds BACKUP, CHOP ALL, INSPECT FILE and FIND SIMILAR SOUNDS.",
        ),
        "SAMPLE" to listOf(
            "· RECORD: a sound from the mic, another app, or a file shared in.",
            "· CHOP: the machine cuts on the hits and guesses; argue with it.",
            "· INSTANT KIT and CATCH A HIT land sounds on PADS without CHOP.",
            "· HUM along on CHOP: the cuts and labels FOLLOW your mouth.",
        ),
        "PROJECT" to listOf(
            "· PADS: tap to hear, OPEN ▸ for its bench. Raw on a strip: nothing done yet.",
            "· BEAT: the project's patterns. Record a take, tap steps in, or go to ORBIT.",
            "· EXPORT: write the kit, or the project as a session. The MPC opens both.",
            "· One kit is active at a time. Swap it above the grid; add more from LIBRARY.",
            "· VERSIONS: every save is a version. Every delete goes to the trash first.",
        ),
        "CREATE" to listOf(
            "· SYNTH, PHOTO TO SOUND, TOUCH SURFACE: a sound from nothing, to a pad.",
            "· TOUCH SURFACE is the one that prints. Tilt the phone for resonance.",
            "· INSTRUMENTS: what you made one from, played on the same 16 pads.",
            "· CONFLUENCE VOICES: hear a recorded passage, then save it as a pad.",
            "· MY VOICE: a short vowel of yours becomes a voice, kept on this phone.",
        ),
        "THE DOCK" to listOf(
            "· The beat keeps playing on PADS, EXPORT, CREATE and a pad bench.",
            "· The light means listening. While on, SNIP keeps what it hears, up to 60s.",
            "· The line says what just happened. A door beside it stays until you take it.",
        ),
    )

    /** The longest a HELP line may be and still fit the panel at the design width. */
    const val HELP_LINE_CHARS = 80
}
