package com.snipsnap.shell

import java.util.Locale
import kotlin.random.Random

/**
 * A name for a thing the player has not named yet: a new project, a kit
 * that landed by itself. One word, in the voice of the starters on the
 * PICK A STARTER sheet - THAW, NIMBUS, CIRCUIT, CLOUD, CHIP - drawn from
 * the rooms of a workshop: weather, metal, light, rock, the radio bench,
 * the kitchen.
 *
 * What a word has to be, and what [NamesTest] holds it to:
 *  - **One word, 3 to 8 letters, upper case.** A shelf row and a dock line
 *    both have to hold it; nothing here wraps.
 *  - **Nothing that names the music.** No drum, no note, no scale - a kit
 *    called SNARE that holds none is a lie, and a project called TEMPO is
 *    a label on the wrong thing.
 *  - **No two a letter apart.** FLARE and GLARE, MIST and MAST: on a shelf
 *    of thirty they read as one name typed twice, and a player rolling for
 *    a fresh one would not see the difference.
 *  - **Not a starter's name.** A kit called THAW that is not THAW is a
 *    fault waiting for the shelf to open.
 *  - **Nothing that reads as a brand or a person.** No AMBER, no JADE, no
 *    ZEPHYR; a roll must never look like the app is naming your kit after
 *    someone.
 *
 * The roll is the base, not the final name: callers still run it through
 * the shelf's own de-duplication (`ShelfModel.newProject`, `KitShelf.freshName`),
 * which is why [roll] only has to avoid what it is told is [taken].
 */
object Names {

    /** The words, grouped by the room they came from; the grouping is for the next editor, not for the roll. */
    val WORDS: List<String> = listOf(
        // weather
        "SLEET", "HAIL", "SQUALL", "GALE", "DRIZZLE", "MIST", "HAZE", "FROST", "GUST", "BREEZE",
        "MONSOON", "FLURRY", "RIME", "DEW", "SMOG", "CYCLONE", "TYPHOON", "SLUSH", "THUNDER",
        "TORNADO", "BLIZZARD", "DOWNPOUR", "DROUGHT",
        // metal
        "ZINC", "IRON", "COPPER", "STEEL", "PEWTER", "SOLDER", "NICKEL", "TITANIUM", "BRONZE",
        "INGOT", "FOIL", "TIN", "LATTEN", "BILLET", "ANVIL", "BULLION", "CARBIDE",
        "TUNGSTEN", "CADMIUM", "SLAG",
        // light
        "FLARE", "GLOW", "BEAM", "DAWN", "DUSK", "SHEEN", "LUSTER", "GLEAM", "EMBER", "FLICKER",
        "LANTERN", "FILAMENT", "TWILIGHT", "STROBE", "CANDLE", "TORCH", "DAZZLE", "HALOGEN",
        "FLASH", "SPECTRUM", "RAINBOW", "LASER", "SUNSET", "DIMMER",
        // geology
        "SHALE", "GRANITE", "BASALT", "QUARTZ", "LODE", "SILT", "LOAM", "SCREE", "MORAINE",
        "STRATA", "GYPSUM", "GEODE", "MAGMA", "PUMICE", "TALUS", "ESKER", "FAULT", "CRAG",
        "CHERT", "OBSIDIAN", "GNEISS", "MESA", "MARL", "TUFF", "BEDROCK", "CANYON", "CAVERN",
        "CINDER", "FOSSIL", "GLACIER", "GRAVEL", "PEBBLE", "GEYSER", "OUTCROP", "PLATEAU", "KARST",
        // radio
        "STATIC", "CARRIER", "SQUELCH", "ANTENNA", "DIPOLE", "BEACON", "SIGNAL", "DIAL", "ETHER",
        "SIDEBAND", "ANODE", "CATHODE", "AERIAL", "CHANNEL", "RECEIVER",
        "REPEATER", "RESISTOR", "RHEOSTAT", "SOCKET", "SWITCH", "VOLTAGE",
        // kitchen
        "LADLE", "WHISK", "COLANDER", "GRATER", "SPATULA", "TONGS", "CLEAVER", "MORTAR", "PESTLE",
        "TRIVET", "SIEVE", "GRIDDLE", "TEAPOT", "PANTRY", "CARAFE", "RAMEKIN", "TUREEN", "SKEWER",
        "APRON", "FUNNEL", "PLATTER", "PITCHER", "SAUCEPAN", "SKIMMER", "STRAINER", "TUMBLER",
        "SIFTER", "NAPKIN", "COASTER", "CRUMB", "GLAZE", "TRAY",
    )

    /**
     * A word from [WORDS] that [taken] does not hold (compared without
     * regard to case or surrounding space, because a shelf folder and a
     * typed name are not always the same case). When every word is held,
     * a random one counted up the way `ProjectStore.freshName` counts:
     * "SLEET 2", "SLEET 3"... the first number nothing holds.
     *
     * @param random the dice; a seeded one makes the roll repeatable, which
     *   is how the tests hold it.
     */
    fun roll(random: Random = Random.Default, taken: Set<String> = emptySet()): String {
        val held = taken.mapTo(HashSet()) { it.trim().uppercase(Locale.ROOT) }
        val free = WORDS.filter { it !in held }
        if (free.isNotEmpty()) return free[random.nextInt(free.size)]
        val base = WORDS[random.nextInt(WORDS.size)]
        var n = 2
        while ("$base $n" in held) n++
        return "$base $n"
    }
}
