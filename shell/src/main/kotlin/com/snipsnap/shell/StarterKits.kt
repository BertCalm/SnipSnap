package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.Names
import com.snipsnap.synth.Shuffle
import com.snipsnap.synth.ConfluenceVoiceBank
import com.snipsnap.synth.SkinKits
import com.snipsnap.synth.SynthKits
import com.snipsnap.synth.ThumpKits
import com.snipsnap.synth.Velocity
import java.io.File

/**
 * The FRESH TAPE menu as data — every "new kit from nothing" the app
 * offers, wrapping the main-source synth builders so the menu is a table,
 * not a when-expression in a screen.
 *
 * This is the cold-start answer: the app is useful in the first thirty
 * seconds, before anything has been captured, and the empty grid never
 * looks empty. Every starter renders offline through the same pipeline as
 * captured audio, and its pads carry per-pad recipes, so a starter kit's
 * main samples remain editable and regenerable. CLOUD, CONFLUENCE, LUCKY DIP A/B
 * and VELOCITY qualify that. CONFLUENCE's eight wordless pads are finite rendered
 * recordings; their checked source bank and exact arrangement metadata travel
 * beside the kit, rather than pretending to be asset-free synth recipes.
 * CLOUD's GRAINS clouds (A07-A16) carry no recipe;
 * only its VOX pads (A01-A06) do, as `SynthKits.cloud` says. LUCKY DIP
 * A/B's bank-B twins carry fx-only recipes (`Shuffle.withRemixBank`), the
 * chain that re-treated each bank-A pad, which re-treat a sound rather
 * than rebuild one. VELOCITY's pads all carry recipes, but a recipe
 * rebuilds only the main hit, not the soft velocity layers that define
 * that kit. Seeded starters reroll; fixed ones always land the same
 * beloved kit.
 */
object StarterKits {
    const val CONFLUENCE_ID = "confluence-wordless"
    const val CONFLUENCE_BANK_DIR = "confluence-voice-bank"

    class Starter internal constructor(
        /** Stable machine id. */
        val id: String,
        /** Menu line, in voice. */
        val displayName: String,
        /** One sentence under it. */
        val blurb: String,
        /** True = REROLL changes the result; false = a fixed recipe. */
        val seeded: Boolean,
        /** A key the kit's melodic content is in, when it has one. */
        val key: KeySpec? = null,
        /** Optional source payloads written before kit.json makes a kit visible. */
        private val prepare: ((directory: File) -> Unit)? = null,
        private val build: (seed: Int) -> List<ArrangedPad?>,
    ) {
        /**
         * Render into [dir] as a full kit folder (WAVs + `kit.json`),
         * exactly the shape every other kit source produces.
         */
        fun render(name: String, dir: File, seed: Int = 0): Kit {
            require(Names.isMpcSafe(name)) { "kit name isn't MPC-safe: '$name'" }
            prepare?.invoke(dir)
            return KitAssembler.assembleArranged(name, build(seed), dir, key)
        }
    }

    val ALL: List<Starter> = listOf(
        // displayName is "EMPTY GRID", not "BLANK" — the other
        // entries are all pre-composed kits, and a plain "BLANK" reads as
        // just one more label in that list rather than the one option
        // that's actually empty. This has to work as both a menu entry
        // (predicts the effect, matches its own blurb below) and a shelf
        // row afterward (`KitShelf.render` names the kit after it) — a
        // noun phrase reads fine as a kit name where an imperative like
        // "START EMPTY" would not.
        Starter(
            "blank", "EMPTY GRID",
            "Empty grid — capture straight onto the pads.",
            seeded = false,
        ) { emptyList() },
        Starter(
            "factory", "FACTORY",
            "The house kit. Kick, snare, hats that choke, the works.",
            seeded = false,
        ) { ThumpKits.classic() },
        Starter(
            "lucky-dip", "LUCKY DIP",
            "Dice-rolled drums. The dice are audited - they can't roll a bad kit.",
            seeded = true,
        ) { Shuffle.kit(it) },
        Starter(
            "lucky-dip-ab", "LUCKY DIP A/B",
            "The dice kit plus a remixed bank B - every pad's evil twin.",
            seeded = true,
        ) { Shuffle.withRemixBank(Shuffle.kit(it), it) },
        Starter(
            "melodic", "MELODIC",
            "Plucks and organ stabs in A minor pentatonic. Runs, not just hits.",
            seeded = false,
            key = KeySpec.parse("Aminpent"),
        ) {
            // Tonal content gates: PLAY holds the note while the pad is
            // pressed, so runs sustain instead of firing as drum-style hits.
            SynthKits.melodic().map { pad -> pad?.copy(oneShot = false) }
        },
        Starter(
            "chip", "CHIP",
            "Everything through a ~9-bit converter. 1987 in a kit.",
            seeded = false,
        ) { SynthKits.chip() },
        Starter(
            "overlap", "OVERLAP",
            "Twelve resonator plucks, finite textures and breaths, with four lower pluck answers.",
            seeded = false,
        ) { SynthKits.overlap() },
        Starter(
            "cloud", "CLOUD",
            "Choirs and grain clouds. Atmosphere, not drums.",
            seeded = false,
        ) { SynthKits.cloud() },
        Starter(
            CONFLUENCE_ID, "CONFLUENCE",
            "Wordless recorded voices: open, airy and rounded vowels, with answering gatherings.",
            seeded = false,
            prepare = { dir -> ConfluenceVoiceBank.bundled().writePortableBank(File(dir, CONFLUENCE_BANK_DIR), overwrite = true) },
        ) { ConfluenceVoiceBank.bundled().arrangedPads() },
        Starter(
            "circuit", "CIRCUIT",
            "Three breaths, moving accents, and canyon answers in C minor pentatonic.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.circuit() },
        Starter(
            "thaw", "THAW",
            "Ice plates and copper runners in C minor pentatonic. Cold contact warms into song.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.thaw() },
        Starter(
            "nimbus", "NIMBUS",
            "Six metal plates spread and gather in C minor pentatonic. Rings, shimmer and held suspension.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.nimbus() },
        Starter(
            "undertow", "UNDERTOW",
            "Weighted ceramic flaps and hollow breath in C minor pentatonic. One draw wakes the shell.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.undertow() },
        Starter(
            "suture", "SUTURE",
            "Bronze vessels and sliding cords in C minor pentatonic. Open bloom closes into a seam murmur.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.suture() },
        Starter(
            "revel", "REVEL",
            "Interlocking skins and friction in C minor pentatonic, heard by moving microphones.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.revel() },
        Starter(
            "tessera", "TESSERA",
            "Wood, paired wire and metal answer through a moving chamber in C minor pentatonic.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.tessera() },
        Starter(
            "pitchwheel", "PITCHWHEEL",
            "Wooden teeth and stretching resin in C minor pentatonic. Push, catch, pluck and recoil.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.pitchwheel() },
        Starter(
            "prism", "PRISM",
            "Harmonic keys, glass and separating partials in C minor pentatonic.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.prism() },
        Starter(
            "origami", "ORIGAMI",
            "Paper keys, warm fifths and soft upper chords in C minor pentatonic.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.origami() },
        Starter(
            "alloy", "ALLOY",
            "Generated wood, tine and breath attacks fuse into pitched bodies in C minor pentatonic.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.alloy() },
        Starter(
            "contour", "CONTOUR",
            "A solid wavetable body and moving digital edge in C minor pentatonic.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.contour() },
        Starter(
            "stoop", "STOOP",
            "Bass, keys and compact chords in C minor pentatonic. Warm grooves with quiet answers.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.stoop() },
        Starter(
            "lacquer", "LACQUER",
            "Polished keys, glass, brass and soft layers in C minor pentatonic. Separate arrivals, one finished tone.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.lacquer() },
        Starter(
            "spool", "SPOOL",
            "Recorded strings, choir, flute and brass in C minor pentatonic. Diagonal heads and backward answers.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.spool() },
        Starter(
            "skitter", "SKITTER",
            "Recovering oscillator pairs provoke a fluttering circuit in C minor pentatonic.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.skitter() },
        Starter(
            "tangle", "TANGLE",
            "Oscillators catch and escape in C minor pentatonic. Rooted bass, crawling pulses and metal.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.tangle() },
        Starter(
            "crease", "CREASE",
            "Pressure-shaped bass, keys and elastic leads in C minor pentatonic, with unfolding held layers.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.crease() },
        Starter(
            "flock", "FLOCK",
            "Gathering notes, traveling ribbons and split harmonics in C minor pentatonic. Finite moving flocks.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.flock() },
        Starter(
            "relay", "RELAY",
            "One note, four source roles. Ordered handoffs in C minor pentatonic.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.relay() },
        Starter(
            "shoal", "SHOAL",
            "Wood, bowl and wire bodies carried by moving energy in C minor pentatonic.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.shoal() },
        Starter(
            "embouchure", "EMBOUCHURE",
            "Dry expressive reeds, muted brass, breathy bass and bowed notes in C minor pentatonic.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.embouchure() },
        Starter(
            "bellows", "BELLOWS",
            "Warm reeds, push-pull colors, folk bite and a wheel drone in C minor pentatonic.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.bellows() },
        Starter(
            "murmur", "MURMUR",
            "Talking FM bass, vowel keys and mouth-shaped plucks in C minor pentatonic.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.murmur() },
        Starter(
            "osprey", "OSPREY",
            "Five coasts at calm, rough and storm energy. Dry finite sea gestures rooted at C4.",
            seeded = false,
        ) { SynthKits.osprey() },
        Starter(
            "opensky", "OPENSKY",
            "Wide anthems, clear leads, glowing pads and bells in C minor pentatonic.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.openSky() },
        Starter(
            "vent", "VENT",
            "Twelve dry orbit sounds: bass, notes, hits and textures. Four pads left for your captures.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.vent() },
        Starter(
            "skin", "SKIN",
            "A room, not a circuit. Struck heads and brass, all eight SKIN voices.",
            seeded = false,
        ) { SkinKits.classic() },
        Starter(
            "cistern", "CISTERN",
            "Drops answer a struck skin. Wet melodic surfaces in C minor pentatonic.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.cistern() },
        Starter(
            "velocity", "VELOCITY",
            "The house kit with ghost notes. Soft hits sound soft, not just quiet.",
            seeded = false,
        ) {
            // THUMP's own patches, so ghost notes re-render at velocity
            // (METAL on the hats, TONE on clap, SNAP on snare - see
            // Velocity.kt's SNARE_TONE_EXCLUDED) rather than low-passing
            // the same take - Velocity.variantsAt makes the same
            // patch-vs-captured call Robin and KitBuilder's velocity doors
            // do (KICK/TOM/COWBELL/RIM still fall back inside it, same as
            // everywhere else atVelocity has no macro to move).
            ThumpKits.classic().map { pad ->
                if (pad == null) return@map null
                val recipe = Breed.recipeOf(pad.recipe)
                pad.copy(softVariants = Velocity.variantsAt(pad.snip, recipe?.patch, recipe?.fx, count = 2))
            }
        },
    )

    fun byId(id: String): Starter? = ALL.firstOrNull { it.id == id }
}
