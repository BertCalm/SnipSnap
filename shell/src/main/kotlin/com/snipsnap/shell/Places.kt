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

    /** Behind the gear in the title bar. */
    const val GEAR_SETUP = "SETUP"
    const val GEAR_HELP = "HELP"

    // ---- the dock ----

    /** Doors the dock can hold. Each carries its own arrow, as every door label does. */
    const val DOOR_CUT = "CUT ▸"
    const val DOOR_PADS = "PADS ▸"
    const val DOOR_BEAT = "BEAT ▸"
    const val DOOR_OUT = "OUT ▸"

    const val LISTEN = "LISTEN · MIC"

    /** The mic-denied box's way out: the system's page for this app, where the permission is switched on. The arrow: it opens that page. */
    const val OPEN_SETTINGS = "OPEN SETTINGS ▸"
    const val SNIPPED = "SNIPPED"
    const val STOPPED = "■"

    /** The live transport chip's face while the beat is stopped: what a tap does, where [STOPPED] read as the stop. */
    const val PLAY_GLYPH = "▶"

    /** The dock's SNIP, shown while armed. No arrow: it takes the last 60 s, it opens nothing. */
    const val SNIP = "SNIP"

    /** The dock's STOP, beside SNIP while armed: the session ends. The shelf's ArmControl said the same word in bin red. */
    const val STOP = "STOP"

    /** The transport chip's accessible names while BEAT is the stage — GROOVE's own PLAY / STOP, read by what they do to the beat. */
    const val PLAY_BEAT = "PLAY THE BEAT"
    const val STOP_BEAT = "STOP THE BEAT"

    /**
     * The first row of the dock's capture choice: the room, through the mic.
     * The chip above already says LISTEN · MIC, so the row repeating it told
     * nobody what it was for; this says it, as [ARM_INSIDE] does for the other.
     * The row's accessible name stays [LISTEN].
     */
    const val ARM_MIC = "MIC ▸ RECORD THIS ROOM"

    /** The second row of the dock's capture choice: another app's audio, with the projection consent. The arrow: it opens that consent. */
    const val ARM_INSIDE = "APP AUDIO ▸ RECORD AN APP"

    /** `ARMED · 00:41 ON TAPE` — the shelf's counter, now on every screen. */
    fun armed(secondsOnTape: Int): String {
        val s = secondsOnTape.coerceAtLeast(0)
        return "ARMED · %02d:%02d ON TAPE".format(Locale.ROOT, s / 60, s % 60)
    }

    fun playing(bpm: Float?, bar: Int): String =
        (bpm?.let { "▶ %.0f BPM".format(Locale.ROOT, it) } ?: "▶") + " · BAR ${bar.coerceAtLeast(1)}"

    fun recording(bar: Int): String = "● BAR ${bar.coerceAtLeast(1)}"

    // ---- PROJECT ▸ PADS ----

    /** The kit switcher above the grid: `KITCHEN ▾ · 2 KITS IN THIS PROJECT`, or the empty project's line. */
    fun switcher(project: Project): String {
        val active = project.activeKit ?: return NO_KITS
        val n = project.kits.size
        return "${active.uppercase(Locale.ROOT)} ▾ · $n ${if (n == 1) "KIT" else "KITS"} IN THIS PROJECT"
    }

    /**
     * The name chip's accessible name: what a tap does, then the fact the
     * drawn chip has no room for. The line is [switcher]'s, and its `▾`
     * is dropped on the way - neither face has the glyph, it drew as a
     * dot, and a screen reader would read it out as a shape.
     */
    fun switchKitName(line: String): String = "SWITCH KIT · " + line.replace(" ▾", "")

    const val NO_KITS = "NO KITS YET"
    const val ADD_FROM_SHELF = "+ FROM SHELF"

    /** The header chip that opens [ADD_FROM_SHELF]: the same door, in the room the header has. */
    const val ADD_KIT = "+ KIT"

    /** The kit sheet's last row. The arrow: it opens the shelf in pick mode. */
    const val SHEET_ADD_FROM_SHELF = "+ FROM SHELF ▸"

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
            "· HOLD A ROW TO RENAME IT, OR TO BIN IT. THE BIN KEEPS IT ${Reversal.DAYS} DAYS.",
            "· TOOLS: BACKUP, CHOP A WHOLE FOLDER, X-RAY A FILE, FIND DOUBLES.",
        ),
        "TAPE" to listOf(
            "· DECK: RECORD A SOUND. THE MIC, ANOTHER APP, OR A FILE SHARED IN.",
            "· CUT: THE MACHINE CUTS ON THE HITS AND GUESSES; ARGUE WITH IT.",
            "· INSTANT KIT AND CATCH A HIT LAND SOUNDS ON PADS WITHOUT CUT.",
            "· HUM ALONG ON CUT: THE CUTS AND LABELS FOLLOW YOUR MOUTH.",
        ),
        "PROJECT" to listOf(
            "· PADS: 16 PADS OF THE KIT YOU PICKED. TAP TO HEAR, OPEN ▸ FOR ITS BENCH.",
            "· BEAT: THE PROJECT'S PATTERNS. RECORD A TAKE, TAP STEPS IN, OR GO TO ORBIT.",
            "· OUT: WRITE THE KIT, OR THE WHOLE PROJECT AS A SESSION. THE MPC OPENS BOTH.",
            "· ONE KIT IS ACTIVE AT A TIME. SWAP IT ABOVE THE GRID; ADD MORE FROM THE SHELF.",
            "· VERSIONS: EVERY SAVE IS A TAKE. EVERY DELETE GOES TO THE BIN FIRST.",
        ),
        "MAKE" to listOf(
            "· SYNTH, SNAP AND SURFACE MAKE A SOUND FROM NOTHING AND SEND IT TO A PAD.",
            "· SURFACE IS THE ONE THAT PRINTS. TILT THE PHONE FOR RESONANCE.",
            "· INSTRUMENTS: WHAT YOU MADE ONE FROM, PLAYED ON THE SAME 16 PADS.",
        ),
        "THE DOCK" to listOf(
            "· THE BEAT PLAYS WHILE BEAT IS OPEN. THE CHIP STOPS IT.",
            "· THE LIGHT IS THE TAPE. WHILE IT IS ARMED, SNIP TAKES THE LAST 60s.",
            "· THE LINE SAYS WHAT JUST HAPPENED. A DOOR BESIDE IT STAYS UNTIL YOU TAKE IT.",
        ),
    )

    /** The longest a HELP line may be and still fit the panel at the design width. */
    const val HELP_LINE_CHARS = 80
}
