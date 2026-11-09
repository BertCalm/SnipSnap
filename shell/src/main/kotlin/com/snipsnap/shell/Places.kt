package com.snipsnap.shell

import com.snipsnap.kit.Project
import java.util.Locale

/**
 * The words of the four-place shape (`docs/SCREEN_PLAN_2026_10.md`): the
 * places and stages as the rows draw them, the dock's labels and doors,
 * the kit switcher's line, and HELP by place. Labels and lists, not
 * sentences — the sentences (the first-run note, the dock's resting
 * line) are in [Copy], where the shouting law holds them.
 *
 * Beside [Copy] rather than inside it on purpose: everything here is
 * furniture that stays on screen, which [Copy]'s reflective law would
 * otherwise ask to become a sentence with a full stop.
 */
object Places {

    /** The place row, in order. */
    val PLACES: List<String> = Place.entries.map { it.label }

    /** The stage rows. */
    val TAPE_STAGES: List<String> = Stage.of(Place.TAPE).map { it.label }
    val PROJECT_STAGES: List<String> = Stage.of(Place.PROJECT).map { it.label }

    // ---- the dock ----

    /** Doors the dock can hold. Each carries its own arrow, as every door label does. */
    const val DOOR_CUT = "CUT ▸"
    const val DOOR_PADS = "PADS ▸"
    const val DOOR_BEAT = "BEAT ▸"
    const val DOOR_OUT = "OUT ▸"

    const val LISTEN = "LISTEN · MIC"

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
    const val CATCH_FIRST = "CATCH YOUR FIRST SOUND"

    /** The mic-denied box's way out: the system's page for this app, where the permission is switched on. No arrow: that page is Android's, not a screen of ours. */
    const val OPEN_SETTINGS = "OPEN SETTINGS"
    const val SNIPPED = "SNIPPED"
    const val STOPPED = "■"

    /** The live transport chip's face while the beat is stopped: what a tap does, where [STOPPED] read as the stop. */
    const val PLAY_GLYPH = "▶"

    /** The dock's SNIP, shown while armed. No arrow: it takes the last 60 s, it opens nothing. */
    const val SNIP = "SNIP"

    /** The dock's STOP, beside SNIP while armed: the session ends. The shelf's ArmControl said the same word in bin red. */
    const val STOP = "STOP"

    /** The transport chip's accessible names, wherever a kit is open — the beat's own PLAY / STOP, read by what they do to the beat. */
    const val PLAY_BEAT = "PLAY THE BEAT"
    const val STOP_BEAT = "STOP THE BEAT"

    /**
     * BEAT's SHARE chip, beside BOUNCE TO SNIPS: the pattern as two passes of
     * audio, handed to the share sheet. No arrow: an action, and the sheet is Android's. Spoken
     * as [SHARE_BEAT_SPOKEN], because "share" alone does not say what.
     */
    const val SHARE_BEAT = "SHARE"
    const val SHARE_BEAT_SPOKEN = "SHARE THIS BEAT"

    /**
     * The first row of the dock's capture choice, in its sheet: the room, through
     * the mic. The chip above already says LISTEN · MIC, so the row says what it is
     * for on a line under the name, as [ARM_INSIDE_DESC] does for the other.
     * The row's accessible name stays [LISTEN].
     */
    const val ARM_MIC = "MIC"
    const val ARM_MIC_DESC = "RECORD THIS ROOM"

    /** The second row of the dock's capture choice: another app's audio, with the projection consent that it opens. */
    const val ARM_INSIDE = "APP AUDIO"
    const val ARM_INSIDE_DESC = "RECORD AN APP"

    /** The capture sheet's title. */
    const val ARM_SHEET_TITLE = "RECORD FROM"

    /** `ARMED · 00:41 ON TAPE` — the shelf's counter, now on every screen. */
    fun armed(secondsOnTape: Int): String {
        val s = secondsOnTape.coerceAtLeast(0)
        return "ARMED · %02d:%02d ON TAPE".format(Locale.ROOT, s / 60, s % 60)
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

    const val NO_KITS = "NO KITS YET"
    const val ADD_FROM_SHELF = "+ FROM SHELF"

    /** The header chip that opens [ADD_FROM_SHELF]: the same door, in the room the header has. */
    const val ADD_KIT = "+ KIT"

    /** The starter sheet: its title, and the tag on a starter that rerolls on every tap. */
    const val STARTER_SHEET_TITLE = "PICK A STARTER"
    const val STARTER_REROLL_TAG = "TAPS REROLL"

    /** The shelf's TOOLS sheet: its title, and each tool as a name and the line under it. */
    const val TOOLS_TITLE = "TOOLS"
    const val TOOL_BACKUP = "BACKUP"
    const val TOOL_BACKUP_DESC = "EVERY KIT, ONE FILE"
    const val TOOL_CHOP_ALL = "CHOP ALL"
    const val TOOL_CHOP_ALL_DESC = "EVERY FILE, ONE KIT"
    const val TOOL_XRAY = "X-RAY"
    const val TOOL_XRAY_DESC = "INSPECT A FILE"
    const val TOOL_DOUBLES = "DOUBLES"
    const val TOOL_DOUBLES_DESC = "SAME SOUND, ANY KIT"

    /**
     * The project row's menu: each action a name and the line under it. The names
     * a screen reader hears carry the project's, and are made where the row is drawn.
     */
    const val PROJECT_RENAME = "RENAME"
    const val PROJECT_RENAME_DESC = "GIVE THE PROJECT A NEW NAME"
    const val PROJECT_DELETE = "DELETE"
    const val PROJECT_DELETE_DESC = "TO THE BIN, KITS STAY ON THE SHELF"
    const val PROJECT_DROP = "REMOVE A KIT"
    const val PROJECT_DROP_DESC = "TAKE ONE OUT, IT STAYS ON THE SHELF"

    /** The kit sheet's title. */
    const val SHEET_KITS_TITLE = "SWITCH KIT"

    /** The kit sheet's last row, and the line under it: it opens the shelf in pick mode. */
    const val SHEET_ADD_FROM_SHELF = "+ FROM SHELF"
    const val SHEET_ADD_FROM_SHELF_DESC = "PUT ANOTHER KIT IN THIS PROJECT"

    /**
     * One row of the kit sheet. The active kit is marked in words and a
     * dot, never colour alone, and not "ON THE GRID" - that is the
     * quantize language (CUT's ON THE GRID, GROOVE's), a different fact.
     */
    fun kitRow(name: String, active: Boolean): String {
        val upper = name.uppercase(Locale.ROOT)
        return if (active) "● $upper · ACTIVE" else upper
    }

    /** The shelf's header while `+ FROM SHELF` is picking a kit for [project]: the third pick mode beside the snip's and BREED's. */
    fun addPickHeader(project: String): String = "PICK A KIT FOR ${project.uppercase(Locale.ROOT)}"

    /**
     * The closed THIS KIT group's second line: the tools inside that
     * [thisKit] does not name (it names KEY, BANK B and VERSIONS). Each word is a
     * button's or section's name inside: SPLIT, DUST ALL, BREED, SCULPT, STRETCH, ROUND ROBIN.
     */
    const val THIS_KIT_ALSO = "ALSO: SPLIT · DUST · BREED · SCULPT · STRETCH · ROUND ROBIN"

    /** THIS KIT's heading over REMIX BANK B. */
    const val KIT_BANK_B = "BANK B"

    /** THIS KIT's heading over KEY, DUST ALL, SCULPT, STRETCH, ROUND ROBIN and SPLIT. */
    const val KIT_WHOLE_SOUND = "THE WHOLE KIT'S SOUND"

    /** THIS KIT's heading over VERSIONS + BIN and BREED. */
    const val KIT_VERSIONS_AND_KITS = "VERSIONS AND OTHER KITS"

    /** KEY's opener inside THIS KIT: a picker, so it opens a sheet (a trailing ▾). */
    const val KEY_OPEN = "KEY ▾"

    /** The KEY sheet's title. */
    const val KEY_SHEET_TITLE = "KEY"

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
        "SHELF" to listOf(
            "· YOUR PROJECTS, AND THE KITS, SNIPS AND INSTRUMENTS THEY SHARE.",
            "· A KIT IS AN MPC PROGRAM. A PROJECT IS AN MPC PROJECT. THE CARD READS BOTH.",
            "· HOLD A KIT TO RENAME IT, OR TO BIN IT. THE BIN KEEPS IT ${Reversal.DAYS} DAYS.",
            "· TOOLS: BACKUP, CHOP A WHOLE FOLDER, X-RAY A FILE, FIND DOUBLES.",
        ),
        "TAPE" to listOf(
            "· DECK: RECORD A SOUND. THE MIC, ANOTHER APP, OR A FILE SHARED IN.",
            "· CUT: THE MACHINE CUTS ON THE HITS AND GUESSES; ARGUE WITH IT.",
            "· INSTANT KIT AND CATCH A HIT LAND SOUNDS ON PADS WITHOUT CUT.",
            "· HUM ALONG ON CUT: THE CUTS AND LABELS FOLLOW YOUR MOUTH.",
        ),
        "PROJECT" to listOf(
            "· PADS: TAP TO HEAR, OPEN ▸ FOR ITS BENCH. RAW ON A STRIP: NOTHING DONE YET.",
            "· BEAT: THE PROJECT'S PATTERNS. RECORD A TAKE, TAP STEPS IN, OR GO TO ORBIT.",
            "· OUT: WRITE THE KIT, OR THE WHOLE PROJECT AS A SESSION. THE MPC OPENS BOTH.",
            "· ONE KIT IS ACTIVE AT A TIME. SWAP IT ABOVE THE GRID; ADD MORE FROM THE SHELF.",
            "· VERSIONS: EVERY SAVE IS A TAKE. EVERY DELETE GOES TO THE BIN FIRST.",
        ),
        "MAKE" to listOf(
            "· SYNTH, SNAP AND SURFACE MAKE A SOUND FROM NOTHING AND SEND IT TO A PAD.",
            "· SURFACE IS THE ONE THAT PRINTS. TILT THE PHONE FOR RESONANCE.",
            "· INSTRUMENTS: WHAT YOU MADE ONE FROM, PLAYED ON THE SAME 16 PADS.",
            "· CONFLUENCE VOICES: HEAR A RECORDED PASSAGE, THEN SAVE IT AS A PAD.",
            "· MY VOICE: A SHORT VOWEL OF YOURS BECOMES A VOICE, KEPT ON THIS PHONE.",
        ),
        "THE DOCK" to listOf(
            "· THE BEAT PLAYS FROM HERE AND KEEPS PLAYING ON PADS, OUT, MAKE AND A PAD BENCH.",
            "· THE LIGHT IS THE TAPE. WHILE IT IS ARMED, SNIP KEEPS WHAT IT HEARS, UP TO 60s.",
            "· THE LINE SAYS WHAT JUST HAPPENED. A DOOR BESIDE IT STAYS UNTIL YOU TAKE IT.",
        ),
    )

    /** The longest a HELP line may be and still fit the panel at the design width. */
    const val HELP_LINE_CHARS = 80
}
