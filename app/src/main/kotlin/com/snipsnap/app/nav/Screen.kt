package com.snipsnap.app.nav

/**
 * The nine menu destinations, in the prototype's order
 * (`design/TapeOS Oilslick.dc.html`, the menu row). [title] is what the
 * titlebar reads on that screen — the deck and the properties panel rename
 * the window, everything else is SNIPSNAP.EXE.
 *
 * M0 implements KITS, KIT and PROPS; the rest are routable so the menu row
 * is honest about what the app will be, and each lights up in its
 * milestone.
 *
 * The title is written out per constant rather than referenced from the
 * companion: Kotlin evaluates enum constructor arguments before the
 * companion object is initialised, so `KITS("KITS", APP_TITLE)` does not
 * compile.
 */
enum class Screen(val menuLabel: String, val title: String) {
    KITS("KITS", "SNIPSNAP.EXE"),
    KIT("KIT", "SNIPSNAP.EXE"),
    TAPE("TAPE", "TAPE DECK"),
    CHOP("CHOP", "SNIPSNAP.EXE"),
    PLAY("PLAY", "SNIPSNAP.EXE"),
    SYNTH("SYNTH", "SNIPSNAP.EXE"),
    EXPORT("EXPORT", "SNIPSNAP.EXE"),
    PROPS("⚙", "TAPE PROPERTIES"),
    HELP("HELP", "SNIPSNAP.EXE"),
}
