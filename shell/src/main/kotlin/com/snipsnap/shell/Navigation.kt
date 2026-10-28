package com.snipsnap.shell

/**
 * The four-place shape as a state machine (`docs/SCREEN_PLAN_2026_10.md`
 * §1 and §6.1): four [Place]s across the top, a [Stage] row inside TAPE
 * and PROJECT, [Bench]es that stack on top of a stage and come back to
 * it, and one back stack over all of it.
 *
 * Why it lives here and not in `App.kt`: today the app draws screens with
 * a plain `when (screen)`, which throws away every local `remember` on a
 * tab change — the whole J20–J26 class of "I switched tabs and lost my
 * work". This class owns navigation *and* the per-place state the screens
 * keep ([pads], [beat], [tape], [make]), so a stage switch is not a tab
 * switch: PADS → BEAT → PADS returns to the same bank, pad and open box.
 * The Compose layer reads [location] and the state records and draws;
 * it decides nothing.
 *
 * Back pops whatever was pushed last — a bench, then a stage change, then
 * a place change — because that is the order they were pushed. There is
 * no root "go to the shelf" policy any more (J11): Back from inside
 * PROJECT lands on SHELF only if SHELF is where you came from.
 */
class Navigation(start: Location = Location(Place.SHELF)) {

    var location: Location = start
        private set

    private val history = ArrayDeque<Location>()

    /** The stage each place was last on, so leaving and coming back lands where you were. */
    private val lastStage = mutableMapOf<Place, Stage>()

    /** How many navigations happened — the sim's tap count. */
    var taps: Int = 0
        private set

    val pads = PadsState()
    val beat = BeatState()
    val tape = TapeState()
    val make = MakeState()

    private fun push(next: Location) {
        if (next == location) return
        history.addLast(location)
        while (history.size > MAX_HISTORY) history.removeFirst()
        location = next
        next.stage?.let { lastStage[next.place] = it }
        taps++
    }

    /** The place row: tapping a place. The place opens on the stage it was last on, benches closed. */
    fun go(place: Place): Location {
        if (place == location.place && location.benches.isEmpty()) return location
        push(Location(place, lastStage[place] ?: Stage.defaultFor(place)))
        return location
    }

    /** The stage row: tapping a stage of the current place. Benches close. */
    fun stage(stage: Stage): Location {
        require(stage.place == location.place) { "$stage is a stage of ${stage.place}, not ${location.place}" }
        push(Location(location.place, stage))
        return location
    }

    /** A bench on top of what is showing. A bench on a bench is allowed (PAD SHEET → SPLICE). */
    fun open(bench: Bench): Location {
        push(location.copy(benches = location.benches + bench))
        return location
    }

    /** A bench's back chip, or a bench finishing (SEND, COMMIT): the bench closes. */
    fun closeBench(): Location {
        if (location.benches.isEmpty()) return location
        push(location.copy(benches = location.benches.dropLast(1)))
        return location
    }

    /** A door — the dock's standing offer, or a button that lands somewhere else. */
    fun take(door: Door): Location = goTo(door.target)

    /**
     * A landing: straight to [target], benches and all. The primitive under
     * [take], for the code paths that know where a thing lands (a chop
     * landing on PADS, a RE-TRIM opening the DECK) without a labelled door
     * to walk through. A plain step back like any other.
     */
    fun goTo(target: Location): Location {
        push(target)
        return location
    }

    /**
     * The system Back. True when it moved; false when there is nothing to
     * go back to, which is the app's cue to leave.
     */
    fun back(): Boolean {
        val previous = history.removeLastOrNull() ?: return false
        location = previous
        taps++
        return true
    }

    /**
     * Leave [page], a place that turned out to have nothing to show (the
     * placeholders of a starter kit that failed to render), but only if that
     * is still what the user is looking at. Someone who went to TAPE while it
     * rendered is where they chose to be, and nothing moves; the caller says
     * what happened wherever they are. True when it moved the user.
     *
     * From the page it is [back], not a new landing, so the entry the page
     * pushed is popped and not left in the history: the shelf it was asked for
     * from is where Back lands. [fallback] is landed on only when Back did not
     * reach its place (a history that did not hold it, or the user's own
     * wandering since). The caller moves the dock after a true.
     */
    fun backOutOf(page: Location, fallback: Location): Boolean {
        if (location != page) return false
        back()
        if (location.place != fallback.place) goTo(fallback)
        return true
    }

    /**
     * Whether [back] has anywhere to go. The app's root Back handler reads
     * this to decide whether to take the press or leave it to the system,
     * which finishes the Activity: the true root of a session is having
     * nowhere to go back to, not being on any particular place (J11).
     */
    val canGoBack: Boolean get() = history.isNotEmpty()

    /**
     * The label the top bench's back chip reads, or null on a bare stage.
     *
     * SETUP, HELP and MIDI are behind the gear, which opens over anything, so
     * their [Bench.backChip] can only say BACK; here they name where back
     * goes: the bench under them, if one is stacked, else the stage showing,
     * else the place. A bench under the gear is named by its place, not its
     * own label, which can be long ("STACK THE TAKES") for a chip this size.
     */
    val backChip: String?
        get() {
            val top = location.benches.lastOrNull() ?: return null
            if (!top.behindGear) return top.backChip
            val below = location.benches.getOrNull(location.benches.size - 2)
            val where = when {
                below != null && below.behindGear -> below.label
                below != null -> location.place.label
                else -> location.stage?.label ?: location.place.label
            }
            return "◄ $where"
        }

    companion object {
        private const val MAX_HISTORY = 64
    }
}

/** The four places across the top. Always visible, never scrolling. */
enum class Place(val label: String) {
    SHELF("SHELF"),
    TAPE("TAPE"),
    PROJECT("PROJECT"),
    MAKE("MAKE"),
}

/** The stages inside a place; SHELF and MAKE have none. */
enum class Stage(val place: Place, val label: String) {
    DECK(Place.TAPE, "DECK"),
    CUT(Place.TAPE, "CUT"),
    PADS(Place.PROJECT, "PADS"),
    BEAT(Place.PROJECT, "BEAT"),
    OUT(Place.PROJECT, "OUT"),
    ;

    companion object {
        fun of(place: Place): List<Stage> = entries.filter { it.place == place }

        /** Where a place opens the first time: its first stage, or none. */
        fun defaultFor(place: Place): Stage? = of(place).firstOrNull()
    }
}

/**
 * A screen you take one thing to and come back from. [home] is the place
 * it belongs under (the plan's §3.8 table) and [backChip] is what its
 * back chip reads — a bench always names where it returns to.
 */
enum class Bench(val home: Place, val label: String, val backChip: String) {
    // Under PROJECT ▸ PADS.
    PAD_SHEET(Place.PROJECT, "PAD SHEET", "◄ PADS"),
    GRAIN_FIELD(Place.PROJECT, "GRAIN FIELD", "◄ PAD"),
    SPLICE(Place.PROJECT, "SPLICE", "◄ PAD"),
    STACK(Place.PROJECT, "STACK THE TAKES", "◄ PAD"),
    SPREAD(Place.PROJECT, "SPREAD", "◄ PAD"),
    SPLIT(Place.PROJECT, "SPLIT", "◄ PADS"),
    VERSIONS(Place.PROJECT, "VERSIONS + BIN", "◄ PADS"),
    PAD_CAPTURE(Place.PROJECT, "PAD CAPTURE", "◄ PADS"),
    // Under PROJECT ▸ BEAT.
    ORBIT(Place.PROJECT, "ORBIT", "◄ BEAT"),
    SONG(Place.PROJECT, "SONG", "◄ BEAT"),
    LOOP(Place.PROJECT, "LOOP", "◄ BEAT"),
    // Under SHELF.
    SNIPS(Place.SHELF, "SNIPS", "◄ SHELF"),
    BIN(Place.SHELF, "BIN", "◄ SHELF"),
    XRAY(Place.SHELF, "X-RAY", "◄ SHELF"),
    DOUBLES(Place.SHELF, "DOUBLES", "◄ SHELF"),
    // Under MAKE.
    SYNTH(Place.MAKE, "SYNTH", "◄ MAKE"),
    SNAP(Place.MAKE, "SNAP", "◄ MAKE"),
    SURFACE(Place.MAKE, "SURFACE", "◄ MAKE"),
    INSTRUMENTS(Place.MAKE, "INSTRUMENTS", "◄ MAKE"),
    KEYS(Place.MAKE, "KEYS", "◄ MAKE"),
    LIVE_SNAP(Place.MAKE, "LIVE", "◄ SNAP"),
    DRAW(Place.MAKE, "DRAW", "◄ SNAP"),
    FIELD(Place.MAKE, "PHOTO FIELD", "◄ SNAP"),
    // Behind the gear, from anywhere. The chip here is the fallback:
    // [Navigation.backChip] names the place the gear was opened over.
    SETUP(Place.SHELF, "SETUP", "◄ BACK"),
    HELP(Place.SHELF, "HELP", "◄ BACK"),
    /** What is plugged in and what it sends: the MIDI diagnostics screen. */
    MIDI(Place.SHELF, "MIDI", "◄ BACK"),
    ;

    /** Opened from the gear, over whatever is showing, rather than from a place of its own. */
    val behindGear: Boolean get() = this == SETUP || this == HELP || this == MIDI
}

/** Where you are: a place, its stage if it has stages, and the benches stacked on it. */
data class Location(
    val place: Place,
    val stage: Stage? = Stage.defaultFor(place),
    val benches: List<Bench> = emptyList(),
) {
    init {
        if (stage != null) require(stage.place == place) { "$stage is not a stage of $place" }
    }

    val top: Bench? get() = benches.lastOrNull()
}

/** A standing door: a label and where it lands. The dock holds one until it is taken. */
data class Door(val label: String, val target: Location)

/** What PADS keeps across stage switches. */
class PadsState {
    var bank: Int = 0
    var selectedSlot: Int? = null
    var openBox: PadSheetBoxes.Box? = null
}

/** What BEAT keeps across stage switches. */
class BeatState {
    var program: String? = null
    var swingPercent: Int? = null
    var feel: String? = null
    var seed: Long? = null
}

/** What TAPE ▸ DECK keeps: the deck's selection and zoom, which a snip landing used to erase (J22). */
class TapeState {
    var zoom: Float? = null
    var inFrame: Long? = null
    var outFrame: Long? = null
    var odometer: Boolean = false
}

/**
 * What each MAKE lab keeps. The labs lose everything on a tab switch
 * today (SYNTH, SNAP, SURFACE have no keyed state at all), so this is a
 * bag per lab the lab writes what it likes into — engine, voice, macro
 * values, the chosen line — as strings, so this class never has to learn
 * an engine.
 */
class MakeState {
    private val labs = mutableMapOf<Bench, MutableMap<String, String>>()

    fun settings(lab: Bench): MutableMap<String, String> = labs.getOrPut(lab) { mutableMapOf() }
}
