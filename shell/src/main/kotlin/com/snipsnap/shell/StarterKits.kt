package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.ConfluenceBankFiles
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.Names
import com.snipsnap.synth.MurmurKeptBank
import com.snipsnap.synth.ChordStabs
import com.snipsnap.synth.Shuffle
import com.snipsnap.synth.ConfluenceVoiceBank
import com.snipsnap.synth.SkinKits
import com.snipsnap.synth.SynthKits
import com.snipsnap.synth.ThumpKits
import com.snipsnap.synth.UprightKits
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
 * COSMIC BREAKS, VELOUR and VELOUR BLOOM are fixed rendered banks with source provenance;
 * their printed wet pads do not pretend to have dry-source synth recipes.
 * TRIAD starters are versioned rendered banks: three velocity zones and four takes
 * per zone, including finite bus phrases, with source provenance on every pad.
 * GATECRASH starters are the same kind of bank: three zones of four takes, each pad printed
 * through its own gated space, with two finite phrases and source provenance on every pad.
 * CYPHER starters follow GATECRASH's shape: dry or printed hits, two phrases, provenance on every pad.
 * MIRRORBALL starters are built the same way, each pad printed through an FET compressor,
 * a multi-head tape echo and an amp.
 * SCUFF's starter is the same shape, every strike rendered by the SCUFF engine at a velocity and a take, its two
 * phrases through one bus clipper. The HICCUP kits are the same shape, every strike rendered by the HICCUP engine at a
 * velocity and a take, with a roll and a riser phrase and no bus.
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

    /** FIRED BRONZE's starter: model 2's round-robin grid, four strike layers of fresh strikes each. */
    const val FIRED_BRONZE_ID = "fired-bronze"
    const val CONFLUENCE_BANK_DIR = "confluence-voice-bank"
    const val CONFLUENCE_VOICES_ID = "confluence-voices"

    /** ACCORD's four voices kept in accord-voices-1 (REED, COURSE, ESCAPEMENT, BELL), each at C2 to C5. */
    const val ACCORD_VOICES_ID = "accord-voices"

    /** CONSORT's starter: FLUTE notes up the minor pentatonic and eight recipes, solo and in sections. */
    const val CONSORT_ID = "consort"

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
        /** Authored tempo, when timing is part of the kit recipe. */
        val tempoBpm: Float? = null,
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
            val kit = KitAssembler.assembleArranged(name, build(seed), dir, key, tempoBpm = tempoBpm)
            // BACKWASH, BREAKER and FIRED BRONZE pads carry their recipes; the shared robin deals each pad's
            // velocity zones and re-struck takes from it (FIRED BRONZE model 2 its own four strike layers), then
            // the pad keeps its recipe.
            val breaker = com.snipsnap.synth.BreakerKits.byId(id)
            if (id.startsWith("backwash-") || breaker != null || id == FIRED_BRONZE_ID) {
                val model = KitBuilderModel.open(dir)
                for (pad in kit.pads) {
                    val (zones, takes) = if (breaker != null) com.snipsnap.synth.BreakerKits.gridFor(pad.slot, breaker) else 3 to 3
                    Robin.apply(model, pad.slot, takes = takes, zones = zones)
                    model.update(pad.slot) { it.copy(recipe=pad.recipe) }
                }
                model.save(accrueWear=false)
                return model.kit
            }
            return kit
        }
    }

    val SIDEROOM_IDS = com.snipsnap.synth.SideroomKits.Kit.entries.map { it.id }.toSet()
    val PATIO_IDS = com.snipsnap.synth.PatioPerformance.Genre.entries.map { it.id }.toSet()
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
            "strike-fx", "FX BY VELOCITY",
            "Four short hits: DRY, REVERB, ECHO. Open VELOCITY STRIP, turn STRIP ON, set RELEASE to STAY and LEVEL to VOL OFF, then tap while moving the strip.",
            seeded = false,
        ) { com.snipsnap.synth.StrikeDemoKit.build() },
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
            "halobar", "HALOBAR",
            "Vibraphone, marimba and xylophone with rhythmic dub echoes.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.halobar() },
        Starter(
            "halobar-six", "HALOBAR SIX STUDY",
            "Six mallet voices, paired dry and dub. New bell voices are listening studies.",
            seeded = false, key = KeySpec.parse("Cminpent"),
        ) { SynthKits.halobarCompleteStudy() },
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
            "obbligato", "OBBLIGATO",
            "Flute and reed share the lead across four relationships, with soft, medium and strong strikes.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.obbligato() },
        Starter(
            "accord", "ACCORD",
            "Brushed wood and ringing tines. Sixteen finite pitched performances with shared mechanical timing.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.accord() },
        Starter(
            "otis", "OTIS",
            "Organ, steam pipe, blues harmonica and shared Squeeze chords. Sixteen finite performances.",
            seeded = false,
        ) { SynthKits.otis() },
        Starter(
            "libration", "LIBRATION",
            "Wood, glass and departing orbits. Sixteen finite notes with soft and hard touch.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.libration() },
        Starter(
            "meridian", "MERIDIAN",
            "Swinging contacts and ringing materials. Sixteen finite notes, including two held-sound captures.",
            seeded = false,
        ) { SynthKits.meridian() },
        Starter(
            "tidal", "TIDAL",
            "String and metal populations in C minor pentatonic. Gather, pass, and unravel into ringing strands.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.tidal() },
        Starter(
            "nacre", "NACRE",
            "Paired harp notes with soft and hard touch, coral replies and seaweed paths.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.nacreVelocity() },
        Starter(
            "ohm", "OHM",
            "A warm string in C minor pentatonic, with independent companions and shared gatherings.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.ohm() },
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
            CONFLUENCE_VOICES_ID, "CONFLUENCE VOICES",
            "Nine selected Yaman, kulning and khöömei passages, with pairs, groups and a short answer.",
            seeded = false,
            prepare = { dir ->
                val bank = ConfluenceSelectedWorkshop.bank
                bank.writePortableBank(ConfluenceBankFiles.bankDirectory(dir, bank.manifestSha256))
            },
        ) { ConfluenceSelectedWorkshop.starterPads() },
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
            "scour", "SCOUR",
            "Metal, glass, thunder-sheet, girder, disc and rod impacts that build. Builds cut for 120 and 90 BPM land their slam on the downbeat.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.scour() },
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
            "rime", "RIME",
            "Contour bass, bright keys and unfolding bodies, with three native velocity layers.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.rime() },
        Starter(
            "rime-complete", "RIME COMPLETE",
            "All six contour voices, with three native velocity layers per pad.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.rimeComplete() },
        Starter(
            "filament", "FILAMENT",
            "Glass threads, pulsing routes and blooming curls in C minor pentatonic.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.filament() },
        Starter(
            "chamber", "CHAMBER",
            "Four struck instruments share wood, glass and aquarium rooms in C minor pentatonic.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.chamber() },
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
            "caustic", "CAUSTIC",
            "Finite metal-bodied notes in C minor pentatonic. Source and pickup carve a resonant body.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.caustic() },
        Starter(
            "crease", "CREASE",
            "Pressure-shaped bass, keys and elastic leads in C minor pentatonic, with unfolding held layers.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.crease() },
        Starter(
            "lattice", "LATTICE",
            "Warm, plucked and hollow notes in C minor pentatonic, shaped by directional gates and a rejected outline.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.lattice() },
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
            "murmur-selected", "MURMUR SELECTED",
            "Selected vowel keys, four voice contrasts and lower-air choir at C2 and C3.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.murmurSelected() },
        Starter(
            "scanned-chain", "SCANNED CHAIN",
            "Moving shapes become plucks, metal and growing tones in C minor pentatonic.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.scannedChain() },
        Starter(
            "trefoil", "TREFOIL",
            "Three-body plucks, keys, moving wires and long memory, with three touch layers in C minor pentatonic.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.trefoil() },
        Starter(
            "trio", "TRIO",
            "Plucked performers and single-strike replies in C minor pentatonic. Low, middle and high drama.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.trio() },
        Starter(
            "osprey", "OSPREY",
            "Five coasts at calm, rough and storm energy. Dry finite sea gestures rooted at C4.",
            seeded = false,
        ) { SynthKits.osprey() },
        Starter(
            "osprey-curated", "OSPREY CURATED",
            "Warm foundations, soft gestures, moving textures and long swells in C minor pentatonic.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.ospreyCurated() },
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
            "upright", "UPRIGHT BASS",
            "Round and muted finger plucks on the same E minor pentatonic notes.",
            seeded = false,
            key = KeySpec.parse("Eminpent"),
        ) { UprightKits.classic() },
        Starter(
            "aerostat", "AEROSTAT",
            "Paddle-struck PVC, catching pipes and drifting steam. Five distinct factory gestures.",
            seeded = false,
        ) { SynthKits.aerostat() },
        Starter(
            "tremor", "TREMOR",
            "Tuned hides, returning beads and a wire cage, with clean and powered voices.",
            seeded = false,
        ) { SynthKits.tremor() },
        Starter(
            "ballast", "BALLAST",
            "Deep bass drives sympathetic wires, a resonant frame and hanging glass tiles.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.ballast() },
        Starter(
            "keel", "KEEL",
            "Plucked, FM and sine-and-saw bass whose bright attack settles into a full body. Two held loops.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.keel() },
        Starter(
            com.snipsnap.synth.EtchKits.ID, "ETCH",
            "Squelching step-line bass: a SAW walk in C minor pentatonic, five more voices and three 130 BPM lines. Three touch zones, four takes.",
            seeded = false, tempoBpm = com.snipsnap.synth.EtchKits.BPM.toFloat(),
            key = KeySpec.parse("Cminpent"),
        ) { com.snipsnap.synth.EtchKits.build() },
        Starter(
            "wick", "WICK",
            "Six pitched voices with recovering motion and three RATTLE bodies.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.wick() },
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
            // do. KICK is struck softer (StrikeVelocity, since 2026-11-07);
            // TOM/COWBELL/RIM still fall back inside it, same as everywhere
            // else atVelocity has no macro to move.
            ThumpKits.classic().map { pad ->
                if (pad == null) return@map null
                val recipe = Breed.recipeOf(pad.recipe)
                pad.copy(softVariants = Velocity.variantsAt(pad.snip, recipe?.patch, recipe?.fx, count = 2))
            }
        },
        Starter(
            "chord-stabs", "CHORD STABS",
            "Every chord in C minor, one per pad: triads, then sevenths. Hit a progression.",
            seeded = false,
            key = KeySpec.parse("Cm"),
        ) { ChordStabs.kit(KeySpec.parse("Cm")) },
        // The modal and borrowed layouts, heard and accepted 2026-11-07. A
        // mode's kit carries the major key with the same notes (D dorian and
        // G mixolydian are C major's notes), so anything the app snaps to the
        // kit's key stays inside the mode. The borrowed kit carries its own
        // major key; its borrowed pads leave that key on purpose.
        Starter(
            "chord-stabs-dorian", "CHORD STABS DORIAN",
            "Every chord in D dorian, one per pad: minor at home, a major IV. Hit Dm then G.",
            seeded = false,
            key = KeySpec.parse("C"),
        ) { ChordStabs.modalKit(2, ChordStabs.Mode.DORIAN) },
        Starter(
            "chord-stabs-mixolydian", "CHORD STABS MIXOLYDIAN",
            "Every chord in G mixolydian, one per pad: major at home, a major flat seven. Hit G, F, C.",
            seeded = false,
            key = KeySpec.parse("C"),
        ) { ChordStabs.modalKit(7, ChordStabs.Mode.MIXOLYDIAN) },
        Starter(
            "chord-stabs-borrowed", "CHORD STABS BORROWED",
            "C major's own chords, then eight borrowed ones: Fm, Bb, Ab, Eb, Gm, Db, D and Abmaj7.",
            seeded = false,
            key = KeySpec.parse("C"),
        ) { ChordStabs.borrowedKit(KeySpec.parse("C")) },
        Starter(
            "erosion", "EROSION",
            "Eight dry percussion roles, from low thuds to bright crackle, with soft, medium and hard touch.",
            seeded = false,
        ) { com.snipsnap.synth.ErosionKits.contrast() },
        Starter(
            "east-africa-bongo-flava", "BONGO FLAVA",
            "Fitted-synthesis drums, hand percussion, wood, metal and log bass. Twenty-two pads, three touch levels and three takes.",
            seeded = false, tempoBpm = 100f,
        ) { com.snipsnap.synth.EastAfricaKits.build(com.snipsnap.synth.EastAfricaKits.Genre.BONGO_FLAVA) },
        Starter(
            "east-africa-singeli", "SINGELI",
            "Fitted-synthesis drums, hand percussion, wood, metal and log bass. Twenty-two pads, three touch levels and three takes.",
            seeded = false, tempoBpm = 200f,
        ) { com.snipsnap.synth.EastAfricaKits.build(com.snipsnap.synth.EastAfricaKits.Genre.SINGELI) },
        Starter(
            "east-africa-bongopiano", "BONGOPIANO",
            "Fitted-synthesis drums, hand percussion, wood, metal and log bass. Twenty-two pads, three touch levels and three takes.",
            seeded = false, tempoBpm = 112f,
        ) { com.snipsnap.synth.EastAfricaKits.build(com.snipsnap.synth.EastAfricaKits.Genre.BONGOPIANO) },
        Starter(
            "east-africa-genge", "GENGE",
            "Fitted-synthesis drums, hand percussion, wood, metal and log bass. Twenty-two pads, three touch levels and three takes.",
            seeded = false, tempoBpm = 96f,
        ) { com.snipsnap.synth.EastAfricaKits.build(com.snipsnap.synth.EastAfricaKits.Genre.GENGE) },
        Starter(
            "east-africa-kapuka", "KAPUKA",
            "Fitted-synthesis drums, hand percussion, wood, metal and log bass. Twenty-two pads, three touch levels and three takes.",
            seeded = false, tempoBpm = 108f,
        ) { com.snipsnap.synth.EastAfricaKits.build(com.snipsnap.synth.EastAfricaKits.Genre.KAPUKA) },
        Starter(
            "timber-pocket", "TIMBER FOLK POCKET",
            "Guitar, acoustic bass, cello and upright percussion. Three strike levels, four takes, a roll and a riser.",
            seeded = false, tempoBpm = 92f,
        ) { com.snipsnap.synth.TimberKits.build(com.snipsnap.synth.TimberKits.Genre.POCKET) },
        Starter(
            "timber-boom-bap", "TIMBER WOOD BOOM BAP",
            "Guitar, acoustic bass, cello and upright percussion. Three strike levels, four takes, a roll and a riser.",
            seeded = false, tempoBpm = 86f,
        ) { com.snipsnap.synth.TimberKits.build(com.snipsnap.synth.TimberKits.Genre.BOOM_BAP) },
        Starter(
            "timber-two-step", "TIMBER STRING TWO STEP",
            "Guitar, acoustic bass, cello and upright percussion. Three strike levels, four takes, a roll and a riser.",
            seeded = false, tempoBpm = 132f,
        ) { com.snipsnap.synth.TimberKits.build(com.snipsnap.synth.TimberKits.Genre.TWO_STEP) },
        Starter(
            "triad-techno", "TRIAD WAREHOUSE TECHNO",
            "Hybrid drums, hand percussion, rolls and shared-bus phrases. Three touch zones and four takes per zone.",
            seeded = false, tempoBpm = 128f,
        ) { com.snipsnap.synth.TriadKits.build(com.snipsnap.synth.TriadKits.Genre.TECHNO) },
        Starter(
            "triad-house", "TRIAD SUBMERGED HOUSE",
            "Hybrid drums, hand percussion, rolls and shared-bus phrases. Three touch zones and four takes per zone.",
            seeded = false, tempoBpm = 118f,
        ) { com.snipsnap.synth.TriadKits.build(com.snipsnap.synth.TriadKits.Genre.DEEP_HOUSE) },
        Starter(
            "triad-minimal", "TRIAD FRACTURE MINIMAL",
            "Hybrid drums, hand percussion, rolls and shared-bus phrases. Three touch zones and four takes per zone.",
            seeded = false, tempoBpm = 126f,
        ) { com.snipsnap.synth.TriadKits.build(com.snipsnap.synth.TriadKits.Genre.MINIMAL) },
        Starter(
            "rivet-workshop", "RIVET WORKSHOP",
            "Industrial drums in a small room. Sixteen pads, three strike strengths and choking metal hats.",
            seeded = false,
        ) { SynthKits.rivet(.10f) },
        Starter(
            "rivet-factory", "RIVET FACTORY",
            "Industrial drums with a wider room. The same sixteen roles and three strike strengths.",
            seeded = false,
        ) { SynthKits.rivet(.55f) },
        Starter(
            "rivet-warehouse", "RIVET WAREHOUSE",
            "Industrial drums with long warehouse reflections. The same sources, with room to ring.",
            seeded = false,
        ) { SynthKits.rivet(1f) },
        Starter("sunroom-early", "SUNROOM EARLY POP", "Stick drums, toms, hats and wooden percussion. Three touches and three variations, with a finite roll and riser.", seeded = false, tempoBpm = 116f) { com.snipsnap.synth.SunroomKits.build(com.snipsnap.synth.SunroomKits.Choice.EARLY) },
        Starter("sunroom-orchestral", "SUNROOM ORCHESTRAL POP", "Brush snare, soft kick, castanet, slit block and triangle. Three touches and three variations, with brush gestures.", seeded = false, tempoBpm = 116f) { com.snipsnap.synth.SunroomKits.build(com.snipsnap.synth.SunroomKits.Choice.ORCHESTRAL) },
        Starter("sunroom-psych", "SUNROOM PSYCH POP", "Resonant kick, bongos, clap and cabasa, with a full cymbal crescendo. Three touches and three variations.", seeded = false, tempoBpm = 116f) { com.snipsnap.synth.SunroomKits.build(com.snipsnap.synth.SunroomKits.Choice.PSYCH) },
        Starter("soul-rock-pocket", "SOUL POCKET", "Close acoustic soul drums, tambourine and claps. Three velocity layers and three strikes, with a roll and swell.", seeded = false, tempoBpm = 108f) { com.snipsnap.synth.SoulRockKits.build(com.snipsnap.synth.SoulRockKits.Choice.SOUL) },
        Starter("soul-rock-roadhouse", "ROADHOUSE ROCK", "Full acoustic rock drums, open hats and cowbell. Three velocity layers and three strikes, with a roll and swell.", seeded = false, tempoBpm = 134f) { com.snipsnap.synth.SoulRockKits.build(com.snipsnap.synth.SoulRockKits.Choice.ROADHOUSE) },
        Starter("soul-rock-open-room", "OPEN ROOM ROCK", "Open acoustic rock drums, ride and three tom colors. Three velocity layers and three strikes, with a roll and swell.", seeded = false, tempoBpm = 89f) { com.snipsnap.synth.SoulRockKits.build(com.snipsnap.synth.SoulRockKits.Choice.OPEN_ROOM) },
        Starter("knee-break-boom-bap", "KNEE BREAK BOOM BAP", "Compact kick and rimshot shells with dry cymbals. Three velocity layers and three strikes per layer.", seeded = false, tempoBpm = 90f) { com.snipsnap.synth.KneeBreakGenreKits.build(com.snipsnap.synth.KneeBreakGenreKits.Choice.BOOM_BAP) },
        Starter("knee-break-trip-hop", "KNEE BREAK TRIP HOP", "Lower tuning and long filtered cassette tails. Three velocity layers and three strikes per layer.", seeded = false, tempoBpm = 76f) { com.snipsnap.synth.KneeBreakGenreKits.build(com.snipsnap.synth.KneeBreakGenreKits.Choice.TRIP_HOP) },
        Starter("knee-break-funk", "KNEE BREAK FUNK BREAKS", "Tight dry drums with raised snare and hat tuning. Three velocity layers and three strikes per layer.", seeded = false, tempoBpm = 108f) { com.snipsnap.synth.KneeBreakGenreKits.build(com.snipsnap.synth.KneeBreakGenreKits.Choice.FUNK) },
        Starter("knee-break-body", "KNEE BREAK BODY", "Fitted synth drums, percussion and finite snare gestures. Three velocity layers and three strikes per layer.", seeded = false, tempoBpm = com.snipsnap.synth.KneeBreakKits.TEMPO) { com.snipsnap.synth.KneeBreakKits.build(com.snipsnap.synth.KneeBreakKits.Choice.BODY) },
        Starter("knee-break-knee", "KNEE BREAK KNEE", "Fitted synth drums, percussion and finite snare gestures. Three velocity layers and three strikes per layer.", seeded = false, tempoBpm = com.snipsnap.synth.KneeBreakKits.TEMPO) { com.snipsnap.synth.KneeBreakKits.build(com.snipsnap.synth.KneeBreakKits.Choice.KNEE) },
        Starter("knee-break-cassette", "KNEE BREAK CASSETTE", "Fitted synth drums, percussion and finite snare gestures. Three velocity layers and three strikes per layer.", seeded = false, tempoBpm = com.snipsnap.synth.KneeBreakKits.TEMPO) { com.snipsnap.synth.KneeBreakKits.build(com.snipsnap.synth.KneeBreakKits.Choice.CASSETTE) },
        Starter("knee-break-tight", "KNEE BREAK TIGHT", "Fitted synth drums, percussion and finite snare gestures. Three velocity layers and three strikes per layer.", seeded = false, tempoBpm = com.snipsnap.synth.KneeBreakKits.TEMPO) { com.snipsnap.synth.KneeBreakKits.build(com.snipsnap.synth.KneeBreakKits.Choice.TIGHT) },
        Starter("knee-break-tight-knee", "KNEE BREAK TIGHT KNEE", "Fitted synth drums, percussion and finite snare gestures. Three velocity layers and three strikes per layer.", seeded = false, tempoBpm = com.snipsnap.synth.KneeBreakKits.TEMPO) { com.snipsnap.synth.KneeBreakKits.build(com.snipsnap.synth.KneeBreakKits.Choice.TIGHT_KNEE) },
        Starter("knee-break-tight-cassette", "KNEE BREAK TIGHT CASSETTE", "Fitted synth drums, percussion and finite snare gestures. Three velocity layers and three strikes per layer.", seeded = false, tempoBpm = com.snipsnap.synth.KneeBreakKits.TEMPO) { com.snipsnap.synth.KneeBreakKits.build(com.snipsnap.synth.KneeBreakKits.Choice.TIGHT_CASSETTE) },
        Starter(
            "velour-close", "VELOUR CLOSE",
            "Close drums and percussion through a warm amp. Three touch layers, with short natural tails.",
            seeded = false, tempoBpm = com.snipsnap.synth.VelourApprovedKits.TEMPO,
        ) { com.snipsnap.synth.VelourApprovedKits.build(com.snipsnap.synth.VelourApprovedKits.Treatment.CLOSE) },
        Starter(
            "velour", "VELOUR",
            "Muted drums, scattered echoes and a warm amp. Three touch layers and four percussion pads.",
            seeded = false, tempoBpm = com.snipsnap.synth.VelourApprovedKits.TEMPO,
        ) { com.snipsnap.synth.VelourApprovedKits.build() },
        Starter(
            "velour-bloom", "VELOUR BLOOM",
            "The same drums with denser grains, longer space and more amp drive. Three touch zones.",
            seeded = false, tempoBpm = com.snipsnap.synth.VelourApprovedKits.TEMPO,
        ) { com.snipsnap.synth.VelourApprovedKits.build(com.snipsnap.synth.VelourApprovedKits.Treatment.BLOOM) },
        Starter("backwash-soul", "BACKWASH SOUL", "Tight soul drums, cross-stick pocket and acoustic percussion. Native recorded alternates and a tambourine roll/riser.", seeded=false, tempoBpm=94f) {
            com.snipsnap.synth.BackwashKits.build(com.snipsnap.synth.BackwashKits.Genre.SOUL_POCKET)
        },
        Starter("backwash-psych", "BACKWASH PSYCH", "Low rock kick, open snare, ringing toms and cowbell accents. Acoustic percussion, alternate takes and roll/riser.", seeded=false, tempoBpm=108f) {
            com.snipsnap.synth.BackwashKits.build(com.snipsnap.synth.BackwashKits.Genre.PSYCH_ROCK)
        },
        Starter(
            FIRED_BRONZE_ID, "FIRED BRONZE",
            "Skin, clay and bronze through a driven processor and amp. Sixteen pads, four strike layers, three fresh strikes each.",
            seeded = false, tempoBpm = 96f,
        ) { com.snipsnap.synth.FiredBronzeInstrument.kit() },
        Starter("spore-synth-jungle", "SPORE DRY JUNGLE",
            "Approved synthesized kick and snare, dry hats, toms and ghost strokes. Space accents, roll and riser.", seeded=false, tempoBpm=170f) {
            com.snipsnap.synth.SporeSyntheticKits.arranged(com.snipsnap.synth.SporeSyntheticKits.Genre.JUNGLE)
        },
        Starter("spore-synth-dub", "SPORE DUB BREAKS",
            "Deep kick, low snare, cross-stick and hand percussion. Dark echo answers and triplet roll.", seeded=false, tempoBpm=85f) {
            com.snipsnap.synth.SporeSyntheticKits.arranged(com.snipsnap.synth.SporeSyntheticKits.Genre.DUB)
        },
        Starter(com.snipsnap.synth.Splinter.ID, "SPLINTER BREAKCORE",
            "Sped-up break hits, clipped and gabber kicks, chopped slices, stutters and a stretched snare. Snare rush and riser.", seeded=false, tempoBpm=com.snipsnap.synth.Splinter.BPM.toFloat()) {
            com.snipsnap.synth.Splinter.arranged()
        },
        Starter(com.snipsnap.synth.Shatter.id, "SHATTER BREAKCORE",
            "One two-bar break cut into sixteen slices, each carrying the room of its neighbours. Slice repeat and break rush.", seeded=false, tempoBpm=com.snipsnap.synth.Shatter.bpm.toFloat()) {
            com.snipsnap.synth.Breakcore.arranged(com.snipsnap.synth.Shatter)
        },
        Starter(com.snipsnap.synth.Soundclash.id, "SOUNDCLASH BREAKCORE",
            "Slam kick, subs, reese, siren, horn, rewind and echo throws. Dub roll and siren rise.", seeded=false, tempoBpm=com.snipsnap.synth.Soundclash.bpm.toFloat()) {
            com.snipsnap.synth.Breakcore.arranged(com.snipsnap.synth.Soundclash)
        },
        Starter(com.snipsnap.synth.Redline.id, "REDLINE BREAKCORE",
            "Speedcore at 250 BPM: tuned terror kicks, clipped noise snares and hats, a scream and feedback stab. Snare roll and scream rise.", seeded=false, tempoBpm=com.snipsnap.synth.Redline.bpm.toFloat()) {
            com.snipsnap.synth.Breakcore.arranged(com.snipsnap.synth.Redline)
        },
        Starter(com.snipsnap.synth.Lull.id, "LULL BREAKCORE",
            "Tender piano chords in A minor, strings, choir, a music box and a worn tape, then a sudden stop. Fading repeat and swell rise.", seeded=false, tempoBpm=com.snipsnap.synth.Lull.bpm.toFloat()) {
            com.snipsnap.synth.Breakcore.arranged(com.snipsnap.synth.Lull)
        },
        Starter(com.snipsnap.synth.Strobe.id, "STROBE BREAKCORE",
            "The 1992 rave: a whine that dives, hard piano and organ stabs, an orchestra hit and a sped-up break. Stab roll and whine rise.", seeded=false, tempoBpm=com.snipsnap.synth.Strobe.bpm.toFloat()) {
            com.snipsnap.synth.Breakcore.arranged(com.snipsnap.synth.Strobe)
        },
        Starter(com.snipsnap.synth.Cartridge.id, "CARTRIDGE BREAKCORE",
            "Chip breakcore: pulse duty, a 4-bit triangle, shift-register noise drums and a crushed break, all on a 60 Hz frame. Noise roll and arp rise.", seeded=false, tempoBpm=com.snipsnap.synth.Cartridge.bpm.toFloat()) {
            com.snipsnap.synth.Breakcore.arranged(com.snipsnap.synth.Cartridge)
        },
        Starter(com.snipsnap.synth.Barricade.id, "BARRICADE BREAKCORE",
            "Digital hardcore at 165 BPM in E: power chords through a cranked amp, a punk kick and snare, harsh noise. Chug roll and feedback rise.", seeded=false, tempoBpm=com.snipsnap.synth.Barricade.bpm.toFloat()) {
            com.snipsnap.synth.Breakcore.arranged(com.snipsnap.synth.Barricade)
        },
        Starter("spore-synth-broken", "SPORE ORBITAL BREAKS",
            "Short kick, wire clap, higher toms and metallic percussion. Reverse-metal accents and accelerating riser.", seeded=false, tempoBpm=155f) {
            com.snipsnap.synth.SporeSyntheticKits.arranged(com.snipsnap.synth.SporeSyntheticKits.Genre.BROKEN)
        },
        Starter(
            "spore-jungle", "SPORE JUNGLE",
            "Psychedelic breaks, tight ghosts and canopy echoes. Three touch zones and three takes; B01 roll and B02 riser.",
            seeded = false, tempoBpm = 170f,
        ) { SynthKits.sporeStation() },
        Starter(
            "spore-dub", "SPORE DUB",
            "Low, rounded drums with dark half-time space. Three touch zones and three takes; B01 roll and B02 riser.",
            seeded = false, tempoBpm = 85f,
        ) { com.snipsnap.synth.SporeStation.performance(com.snipsnap.synth.SporeStation.Genre.DUB) },
        Starter(
            "spore-electro", "SPORE ELECTRO",
            "Short synthetic drums, bright wires and metallic accents. Three touch zones and three takes; B01 roll and B02 riser.",
            seeded = false, tempoBpm = 130f,
        ) { com.snipsnap.synth.SporeStation.performance(com.snipsnap.synth.SporeStation.Genre.ELECTRO) },
        Starter("cuttings-pocket", "CUTTINGS POCKET FINDS", "Boom-bap collage with bass catches, borrowed rooms and sampler grain. Three layers, four takes; 1/2/4-bar fills.",
            seeded = false, tempoBpm = 89f) { com.snipsnap.synth.CuttingsKits.build(com.snipsnap.synth.CuttingsKits.Genre.POCKET) },
        Starter("cuttings-night", "CUTTINGS NIGHT FRAGMENTS", "Trip-hop felt kicks, brush backbeats and hand percussion. Three layers, four takes; 1/2/4-bar fills.",
            seeded = false, tempoBpm = 76f) { com.snipsnap.synth.CuttingsKits.build(com.snipsnap.synth.CuttingsKits.Genre.NIGHT) },
        Starter("cuttings-circuit", "CUTTINGS CUT CIRCUIT", "Big-beat clap backbeats, alternating kicks and abrupt metal cuts. Three layers, four takes; 1/2/4-bar fills.",
            seeded = false, tempoBpm = 124f) { com.snipsnap.synth.CuttingsKits.build(com.snipsnap.synth.CuttingsKits.Genre.CIRCUIT) },
        Starter(
            "cosmic-breaks", "COSMIC BREAKS",
            "Sixteen cosmic drums, glitches and rides. Three strike layers, four takes each.",
            seeded = false, tempoBpm = com.snipsnap.synth.CosmicBreaksKits.TEMPO,
        ) { com.snipsnap.synth.CosmicBreaksGenres.build(com.snipsnap.synth.CosmicBreaksGenres.Genre.ORBIT) },
        Starter("cosmic-jungle", "COSMIC JUNGLE", "Jungle crack, tight hats and restless glitches. Includes a roll and riser.",
            seeded = false, tempoBpm = 174f) { com.snipsnap.synth.CosmicBreaksGenres.build(com.snipsnap.synth.CosmicBreaksGenres.Genre.JUNGLE) },
        Starter("cosmic-dubstep", "COSMIC DUBSTEP", "Deep kicks, dark snares and spaced echoes. Includes a roll and riser.",
            seeded = false, tempoBpm = 140f) { com.snipsnap.synth.CosmicBreaksGenres.build(com.snipsnap.synth.CosmicBreaksGenres.Genre.DUBSTEP) },
        Starter(
            "gatecrash-stone", "GATECRASH STONE ROOM",
            "Acoustic drums and six concert toms in a hard room, crushed and gated. Three touch zones, four takes; a tom fill and a snare build.",
            seeded = false, tempoBpm = com.snipsnap.synth.GatecrashKits.Kit.STONE_ROOM.bpm.toFloat(),
        ) { com.snipsnap.synth.GatecrashKits.build(com.snipsnap.synth.GatecrashKits.Kit.STONE_ROOM) },
        Starter(
            "gatecrash-machine", "GATECRASH MACHINE POP",
            "Analog drums and four diving synth toms through a burst of reflections that stops dead. Three touch zones, four takes; a fill and a build.",
            seeded = false, tempoBpm = com.snipsnap.synth.GatecrashKits.Kit.MACHINE_POP.bpm.toFloat(),
        ) { com.snipsnap.synth.GatecrashKits.build(com.snipsnap.synth.GatecrashKits.Kit.MACHINE_POP) },
        Starter(
            "gatecrash-electro", "GATECRASH ELECTRO FUNK",
            "A long dry boom kick with a snappy snare and clap in a small, short-gated room. Three touch zones, four takes; a tom break and a build.",
            seeded = false, tempoBpm = com.snipsnap.synth.GatecrashKits.Kit.ELECTRO_FUNK.bpm.toFloat(),
        ) { com.snipsnap.synth.GatecrashKits.build(com.snipsnap.synth.GatecrashKits.Kit.ELECTRO_FUNK) },
        Starter(
            "cypher-trap", "CYPHER TRAP",
            "A short punch over subs that hold and slide, a piercing snare, hats that roll in triplets and sixty-fourths, a bell and a zap. Dry. Three touch zones, four takes; a hat roll and a sub line.",
            seeded = false, tempoBpm = com.snipsnap.synth.CypherKits.Kit.TRAP.bpm.toFloat(),
        ) { com.snipsnap.synth.CypherKits.build(com.snipsnap.synth.CypherKits.Kit.TRAP) },
        Starter(
            "cypher-drill", "CYPHER DRILL",
            "Subs that land then slide down on the off-beat, a snare and clap in a dark big room on the triplet grid, hats ticking under, metal hits. Three touch zones, four takes; a triplet snare bar and a sub call.",
            seeded = false, tempoBpm = com.snipsnap.synth.CypherKits.Kit.DRILL.bpm.toFloat(),
        ) { com.snipsnap.synth.CypherKits.build(com.snipsnap.synth.CypherKits.Kit.DRILL) },
        Starter(
            "cypher-backroom", "CYPHER BACKROOM",
            "Boom bap: the accepted Backroom six through their rack, completed to sixteen pads. Three touch zones, four takes; swung hats and a break drop.",
            seeded = false, tempoBpm = com.snipsnap.synth.CypherKits.Kit.BACKROOM.bpm.toFloat(),
        ) { com.snipsnap.synth.CypherKits.build(com.snipsnap.synth.CypherKits.Kit.BACKROOM) },
        Starter(
            "cypher-lowrider", "CYPHER LOWRIDER",
            "G-funk: a hybrid thump, a fat snare and clap that bloom into a long room, hats and a shaker behind the beat, a bell. Three touch zones, four takes; a laid-back groove and a big fill.",
            seeded = false, tempoBpm = com.snipsnap.synth.CypherKits.Kit.LOWRIDER.bpm.toFloat(),
        ) { com.snipsnap.synth.CypherKits.build(com.snipsnap.synth.CypherKits.Kit.LOWRIDER) },
        Starter(
            "cypher-dust", "CYPHER DUST",
            "Underground cinematic boom bap: raw tape thuds, a high wooden crack, a crash, no compressor anywhere. Three touch zones, four takes; an unquantized break with and without the crash.",
            seeded = false, tempoBpm = com.snipsnap.synth.CypherKits.Kit.DUST.bpm.toFloat(),
        ) { com.snipsnap.synth.CypherKits.build(com.snipsnap.synth.CypherKits.Kit.DUST) },
        Starter(
            "cypher-flint", "CYPHER FLINT",
            "Michigan scat: a fast kick and an early-sliding sub, a high piercing snare, wooden clicks and high rims off the grid, an off-beat bell. Three touch zones, four takes; a click scatter and a slide stack.",
            seeded = false, tempoBpm = com.snipsnap.synth.CypherKits.Kit.FLINT.bpm.toFloat(),
        ) { com.snipsnap.synth.CypherKits.build(com.snipsnap.synth.CypherKits.Kit.FLINT) },
        Starter(
            "loose-crate-hummin", "LOOSE CRATE HUMMIN",
            "Beat-tape boom bap built around a kick that hums: a flam snare, a bouncing snare, dark hats that drag, a record's rumble under everything. Three touch zones, four takes; a flam roll and a loose four-bar groove.",
            seeded = false, tempoBpm = com.snipsnap.synth.LooseCrateKits.Kit.HUMMIN.bpm.toFloat(),
        ) { com.snipsnap.synth.LooseCrateKits.build(com.snipsnap.synth.LooseCrateKits.Kit.HUMMIN) },
        Starter(
            "loose-crate-cloth", "LOOSE CRATE CLOTH",
            "Dry and close: a kick that drops an octave, the deep bouncing snare, tight hats, clean gaps. Three touch zones, four takes; a bounce roll and a tight four-bar groove.",
            seeded = false, tempoBpm = com.snipsnap.synth.LooseCrateKits.Kit.CLOTH.bpm.toFloat(),
        ) { com.snipsnap.synth.LooseCrateKits.build(com.snipsnap.synth.LooseCrateKits.Kit.CLOTH) },
        Starter(
            "loose-crate-haze", "LOOSE CRATE HAZE",
            "Half-time haze: a lazy flam, a washing cymbal, bells and metal, the tape wobbling and the top cut hardest. Three touch zones, four takes; a ghost roll and a half-time four-bar groove.",
            seeded = false, tempoBpm = com.snipsnap.synth.LooseCrateKits.Kit.HAZE.bpm.toFloat(),
        ) { com.snipsnap.synth.LooseCrateKits.build(com.snipsnap.synth.LooseCrateKits.Kit.HAZE) },
        Starter(
            "loose-crate-ritual", "LOOSE CRATE RITUAL",
            "Sparse and dub-leaning: a short sub thud, a high slap, hand drums and a sub that drops. Three touch zones, four takes; a slap roll and a one-drop four-bar groove.",
            seeded = false, tempoBpm = com.snipsnap.synth.LooseCrateKits.Kit.RITUAL.bpm.toFloat(),
        ) { com.snipsnap.synth.LooseCrateKits.build(com.snipsnap.synth.LooseCrateKits.Kit.RITUAL) },
        Starter(
            "loose-crate-blue", "LOOSE CRATE BLUE",
            "Jazz breaks off a record: a felt kick, brushes, a bright stick ride with a light ring that fades quickly, a foot hat and a shaker, on a fresher tape. Three touch zones, four takes; a tap roll and a swung four-bar break.",
            seeded = false, tempoBpm = com.snipsnap.synth.LooseCrateKits.Kit.BLUE.bpm.toFloat(),
        ) { com.snipsnap.synth.LooseCrateKits.build(com.snipsnap.synth.LooseCrateKits.Kit.BLUE) },
        Starter(
            "loose-crate-speed", "LOOSE CRATE SPEED",
            "HUMMIN's record played at 45: every drum shorter, tighter and higher, the humming kick lifted. Three touch zones, four takes; a sped roll and a four-bar groove at 108.",
            seeded = false, tempoBpm = com.snipsnap.synth.LooseCrateKits.Kit.SPEED.bpm.toFloat(),
        ) { com.snipsnap.synth.LooseCrateKits.build(com.snipsnap.synth.LooseCrateKits.Kit.SPEED) },
        Starter(
            "loose-crate-raga", "LOOSE CRATE RAGA",
            "Indian-record percussion with the tabla leading: dayan strokes, a bending bayan, dholak under them, a thud kick and a slap snare. Three touch zones, four takes; a tabla roll and a four-bar groove.",
            seeded = false, tempoBpm = com.snipsnap.synth.LooseCrateKits.Kit.RAGA.bpm.toFloat(),
        ) { com.snipsnap.synth.LooseCrateKits.build(com.snipsnap.synth.LooseCrateKits.Kit.RAGA) },
        Starter(
            "stagger-cardboard", "STAGGER CARDBOARD",
            "A muffled, click-free kick against a dry crack and a hat at a whisper, timpani, gong, zap and punch pitched down through a 12-bit converter. Three touch zones, four takes; a timpani roll and a two-bar groove whose clock drags and catches up.",
            seeded = false, tempoBpm = com.snipsnap.synth.StaggerKits.Kit.CARDBOARD.bpm.toFloat(),
        ) { com.snipsnap.synth.StaggerKits.build(com.snipsnap.synth.StaggerKits.Kit.CARDBOARD) },
        Starter(
            "stagger-matinee", "STAGGER MATINEE",
            "The cinematic kit: a stage kick and snare, then ten effects, two timpani, two gongs, glass breaking, two zaps, a punch and a stab, all pitched down through the converter. Three touch zones, four takes; a timpani riser into the crash and a groove with a glass break in it.",
            seeded = false, tempoBpm = com.snipsnap.synth.StaggerKits.Kit.MATINEE.bpm.toFloat(),
        ) { com.snipsnap.synth.StaggerKits.build(com.snipsnap.synth.StaggerKits.Kit.MATINEE) },
        Starter(
            "stagger-stumble", "STAGGER STUMBLE",
            "The drunkest clock: a humming muffled kick, a rimshot in the snare's place, no hats in the groove, the tape worn. Three touch zones, four takes; a dragging snare roll and a two-bar groove that leans and catches up.",
            seeded = false, tempoBpm = com.snipsnap.synth.StaggerKits.Kit.STUMBLE.bpm.toFloat(),
        ) { com.snipsnap.synth.StaggerKits.build(com.snipsnap.synth.StaggerKits.Kit.STUMBLE) },
        Starter(
            "facsimile-shuffle", "FACSIMILE SHUFFLE",
            "Heavy-swing boom bap rebuilt by synthesis from two loops: a long falling kick, roomy backbeat snares, hats pushed late on the swung sixteenths. Three touch zones, four takes; the Alley and Swing grooves, hit for hit.",
            seeded = false, tempoBpm = com.snipsnap.synth.FacsimileKits.Kit.SHUFFLE.bpm.toFloat(),
        ) { com.snipsnap.synth.FacsimileKits.build(com.snipsnap.synth.FacsimileKits.Kit.SHUFFLE) },
        Starter(
            "facsimile-knock", "FACSIMILE KNOCK",
            "A knocking kick with a pulsing boom, a snare that blooms into its room, and a fusion kit's hats and cracks, all synthesized. Three touch zones, four takes; the Knocker and Fusion grooves, hit for hit.",
            seeded = false, tempoBpm = com.snipsnap.synth.FacsimileKits.Kit.KNOCK.bpm.toFloat(),
        ) { com.snipsnap.synth.FacsimileKits.build(com.snipsnap.synth.FacsimileKits.Kit.KNOCK) },
        Starter(
            "facsimile-half-time", "FACSIMILE HALF-TIME",
            "Half-time weight rebuilt by synthesis: punchy kicks, falling subs, a clap and a stacked clap, open hats. Three touch zones, four takes; the Doves and Mover grooves, hit for hit.",
            seeded = false, tempoBpm = com.snipsnap.synth.FacsimileKits.Kit.HALF_TIME.bpm.toFloat(),
        ) { com.snipsnap.synth.FacsimileKits.build(com.snipsnap.synth.FacsimileKits.Kit.HALF_TIME) },
        Starter(
            "facsimile-texture", "FACSIMILE TEXTURE",
            "Toms and tone rebuilt by synthesis: pitched toms, sub notes, a crisp hat and a backbeat tambourine. Three touch zones, four takes; the Tom, World and Thin Perc grooves, hit for hit.",
            seeded = false, tempoBpm = com.snipsnap.synth.FacsimileKits.Kit.TEXTURE.bpm.toFloat(),
        ) { com.snipsnap.synth.FacsimileKits.build(com.snipsnap.synth.FacsimileKits.Kit.TEXTURE) },
        Starter(
            com.snipsnap.synth.BreakerKits.ID, "BREAKER",
            "Surf rock by way of boom bap: dusty drums through a spring tank, a crush and a surf amp. Three touch zones, three takes; a snare riser and a surf tom fill.",
            seeded = false, tempoBpm = com.snipsnap.synth.BreakerKits.BPM,
        ) { com.snipsnap.synth.BreakerKits.build() },
        Starter(
            com.snipsnap.synth.BreakerKits.Kit.DUB_TANK.id, com.snipsnap.synth.BreakerKits.Kit.DUB_TANK.title,
            "One-drop dub through the spring: a rimshot on three, a snare thrown into a long echo, open toms and the tank kicked. Three touch zones, three takes.",
            seeded = false, tempoBpm = com.snipsnap.synth.BreakerKits.Kit.DUB_TANK.bpm,
        ) { com.snipsnap.synth.BreakerKits.build(com.snipsnap.synth.BreakerKits.Kit.DUB_TANK) },
        Starter(
            com.snipsnap.synth.BreakerKits.Kit.TRIP_HOP.id, com.snipsnap.synth.BreakerKits.Kit.TRIP_HOP.title,
            "A heavy, dusty break: a fatter kick and snare a semitone down, ghost notes, a crushed break snare and a dusty ride, kept dry. Three touch zones, three takes.",
            seeded = false, tempoBpm = com.snipsnap.synth.BreakerKits.Kit.TRIP_HOP.bpm,
        ) { com.snipsnap.synth.BreakerKits.build(com.snipsnap.synth.BreakerKits.Kit.TRIP_HOP) },
        Starter(
            "mirrorball-philly", "MIRRORBALL PHILLY STRINGS",
            "A dry live disco kit: dead kick, towelled snare, congas and tambourine through an FET squeeze, tape repeats and a clean amp. Three touch zones, four takes; a tom fill and a snare build.",
            seeded = false, tempoBpm = com.snipsnap.synth.MirrorballKits.Kit.PHILLY.bpm.toFloat(),
        ) { com.snipsnap.synth.MirrorballKits.build(com.snipsnap.synth.MirrorballKits.Kit.PHILLY) },
        Starter(
            "mirrorball-loft", "MIRRORBALL LOFT PARTY",
            "A percussion-first disco kit: clap snare, hand drums, timbales and cowbell riding long tape repeats into a warm amp. Three touch zones, four takes; a timbale fill and a clap build.",
            seeded = false, tempoBpm = com.snipsnap.synth.MirrorballKits.Kit.LOFT.bpm.toFloat(),
        ) { com.snipsnap.synth.MirrorballKits.build(com.snipsnap.synth.MirrorballKits.Kit.LOFT) },
        Starter(
            "mirrorball-munich", "MIRRORBALL MUNICH MACHINE",
            "Machine disco: analog kick, four falling synth toms and a sequencer hat on the dotted-eighth head, into a pushed amp. Three touch zones, four takes; a pew fill and a snare build.",
            seeded = false, tempoBpm = com.snipsnap.synth.MirrorballKits.Kit.MUNICH.bpm.toFloat(),
        ) { com.snipsnap.synth.MirrorballKits.build(com.snipsnap.synth.MirrorballKits.Kit.MUNICH) },
        Starter(
            "soot", "SOOT",
            "Grimy street boom bap from physical models: a sagging skin over a sub, a hot snare into a tight coil, detuned metal and a dictaphone. Three touch zones, four takes; a coil roll and four bars.",
            seeded = false, tempoBpm = com.snipsnap.synth.SootKits.BPM.toFloat(),
        ) { com.snipsnap.synth.SootKits.build() },
        Starter(
            "soot-drumless", "SOOT DRUMLESS",
            "No kick: tension rods, cups and plates carry the groove over muted snares, all from SOOT's physical models. Three touch zones, four takes; a rod roll and four bars.",
            seeded = false, tempoBpm = com.snipsnap.synth.SootGenreKits.Genre.DRUMLESS.bpm.toFloat(),
        ) { com.snipsnap.synth.SootGenreKits.build(com.snipsnap.synth.SootGenreKits.Genre.DRUMLESS) },
        Starter(
            "soot-dub", "SOOT BASEMENT DUB",
            "A one-drop: a heavy kick and a high rimshot thrown deep into the coil, plates in the spring. Three touch zones, four takes; a spring roll and four bars.",
            seeded = false, tempoBpm = com.snipsnap.synth.SootGenreKits.Genre.DUB.bpm.toFloat(),
        ) { com.snipsnap.synth.SootGenreKits.build(com.snipsnap.synth.SootGenreKits.Genre.DUB) },
        Starter(
            "soot-noir", "SOOT BRUSHED NOIR",
            "Soft and close: a damp kick, brush-weight snares, pure hats and soft metal under a light dictaphone. Three touch zones, four takes; a brush roll and four bars.",
            seeded = false, tempoBpm = com.snipsnap.synth.SootGenreKits.Genre.NOIR.bpm.toFloat(),
        ) { com.snipsnap.synth.SootGenreKits.build(com.snipsnap.synth.SootGenreKits.Genre.NOIR) },
        Starter(
            "soot-industrial", "SOOT WAREHOUSE INDUSTRIAL",
            "Straight and hard: a driven kick, bench snares, struck plates and rods, the dictaphone pushed. Three touch zones, four takes; a snare roll and four bars.",
            seeded = false, tempoBpm = com.snipsnap.synth.SootGenreKits.Genre.INDUSTRIAL.bpm.toFloat(),
        ) { com.snipsnap.synth.SootGenreKits.build(com.snipsnap.synth.SootGenreKits.Genre.INDUSTRIAL) },
        Starter(
            "kerf-dust", "KERF DUST",
            "Synthetic chops for boom bap: short kicks and snares rebuilt from fitted numbers, heavy bleed, a loose knife. Three touch zones, four takes; a ghost roll and a stack riser.",
            seeded = false, tempoBpm = com.snipsnap.synth.KerfKits.Genre.DUST.bpm.toFloat(),
        ) { com.snipsnap.synth.KerfKits.build(com.snipsnap.synth.KerfKits.Genre.DUST) },
        Starter(
            "kerf-soul", "KERF SOUL",
            "Synthetic chops from a soul break: round kicks, a roomy snare and long hats, the bleed and knife as measured. Three touch zones, four takes; a snare roll and a hat riser.",
            seeded = false, tempoBpm = com.snipsnap.synth.KerfKits.Genre.SOUL.bpm.toFloat(),
        ) { com.snipsnap.synth.KerfKits.build(com.snipsnap.synth.KerfKits.Genre.SOUL) },
        Starter(
            "kerf-funk", "KERF FUNK",
            "Synthetic chops for a tight funk break: quick kicks, crisp snares, open cymbals, a clean knife and little bleed. Three touch zones, four takes; a hat roll and a cymbal riser.",
            seeded = false, tempoBpm = com.snipsnap.synth.KerfKits.Genre.FUNK.bpm.toFloat(),
        ) { com.snipsnap.synth.KerfKits.build(com.snipsnap.synth.KerfKits.Genre.FUNK) },
        Starter(
            "kerf-stomp", "KERF STOMP",
            "Synthetic chops, hard and dirty: big kicks, a fat snare and a crash stack, the wash pushed and the knife sloppiest. Three touch zones, four takes; a snare roll and a crash riser.",
            seeded = false, tempoBpm = com.snipsnap.synth.KerfKits.Genre.STOMP.bpm.toFloat(),
        ) { com.snipsnap.synth.KerfKits.build(com.snipsnap.synth.KerfKits.Genre.STOMP) },
        Starter(
            MurmurKeptBank.ID, "MURMUR KEPT",
            "Kept plucks, vowel keys and lower-air choir. Held pads have a short delivery fade.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
            prepare = { directory -> MurmurKeptBankFiles.write(directory) },
        ) { SynthKits.murmurKept() },
        Starter(
            "scuff-detroit", "SCUFF DETROIT",
            "A dry Michigan kit: punch kick, whip clap, staccato bass hits, two-peak rims and a chopper hat, crushed, boxed, barked and slammed. Three touch zones, four takes; two bars through one bus and a clap riser.",
            seeded = false, tempoBpm = com.snipsnap.synth.ScuffKits.Kit.DETROIT.bpm.toFloat(),
        ) { com.snipsnap.synth.ScuffKits.build(com.snipsnap.synth.ScuffKits.Kit.DETROIT) },
        Starter(
            "scuff-flint", "SCUFF FLINT",
            "The faster, brighter Michigan kit: a higher, shorter kick, a tighter clap, bright hats, short hard bass hits and three stank rims, crushed and slammed harder. Three touch zones, four takes; frantic two bars and a rim riser.",
            seeded = false, tempoBpm = com.snipsnap.synth.ScuffKits.Kit.FLINT.bpm.toFloat(),
        ) { com.snipsnap.synth.ScuffKits.build(com.snipsnap.synth.ScuffKits.Kit.FLINT) },
        Starter(
            "kindred-techno", "KINDRED TECHNO",
            "Warehouse techno from one analog-style voice: a punchy kick with a short pitch dive, tight hats, a rumble, ride, cowbell and a sub hit. Three touch zones, four fresh strikes; an eight-step kick phrase and a clap riser.",
            seeded = false, tempoBpm = com.snipsnap.synth.KindredKits.Kit.TECHNO.bpm.toFloat(),
        ) { com.snipsnap.synth.KindredKits.build(com.snipsnap.synth.KindredKits.Kit.TECHNO) },
        Starter(
            "kindred-industrial", "KINDRED INDUSTRIAL",
            "Industrial and EBM from one analog-style voice: driven square waves, long pitch dives, anvil, pipe, hammer, steam and grind. Three touch zones, four fresh strikes; an eight-step zap phrase and a riser that turns into a tone.",
            seeded = false, tempoBpm = com.snipsnap.synth.KindredKits.Kit.INDUSTRIAL.bpm.toFloat(),
        ) { com.snipsnap.synth.KindredKits.build(com.snipsnap.synth.KindredKits.Kit.INDUSTRIAL) },
        Starter(
            "kindred-electro", "KINDRED ELECTRO",
            "Electro and breaks from one analog-style voice: a long round boom, snappy snare and clap, cowbell, three toms, lasers up and down, a conga. Three touch zones, four fresh strikes; an FM conga phrase and a rising tom roll.",
            seeded = false, tempoBpm = com.snipsnap.synth.KindredKits.Kit.ELECTRO.bpm.toFloat(),
        ) { com.snipsnap.synth.KindredKits.build(com.snipsnap.synth.KindredKits.Kit.ELECTRO) },
        Starter(
            "kindred-ambient", "KINDRED AMBIENT",
            "Ambient and downtempo from one analog-style voice: a soft kick, brush, FM bells, wood, a water drop, glass, breath and swell. Three touch zones, four fresh strikes; an eight-step bell phrase and a pluck riser that becomes a tone.",
            seeded = false, tempoBpm = com.snipsnap.synth.KindredKits.Kit.AMBIENT.bpm.toFloat(),
        ) { com.snipsnap.synth.KindredKits.build(com.snipsnap.synth.KindredKits.Kit.AMBIENT) },
        Starter(
            "footwork-tap-jazz", "FOOTWORK TAP JAZZ",
            "Weighted metallic tap, heel support, two-way brushes and body percussion on a close stage. Eighteen pads with authored roll and riser; three touch zones and four takes. Full dynamics.",
            seeded = false, tempoBpm = com.snipsnap.synth.FootworkKits.Kit.TAP.bpm.toFloat(),
        ) { com.snipsnap.synth.FootworkKits.build(com.snipsnap.synth.FootworkKits.Kit.TAP) },
        Starter(
            "footwork-street-breaks", "FOOTWORK STREET BREAKS",
            "Damped street footwork, low body impacts and palm-and-stick objects. Eighteen pads with authored roll and riser; three touch zones and four takes. Full dynamics.",
            seeded = false, tempoBpm = com.snipsnap.synth.FootworkKits.Kit.STREET.bpm.toFloat(),
        ) { com.snipsnap.synth.FootworkKits.build(com.snipsnap.synth.FootworkKits.Kit.STREET) },
        Starter(
            "footwork-industrial-stepping", "FOOTWORK INDUSTRIAL STEPPING",
            "Hard plate steps, braced-deck knocks and loose metal objects. Eighteen pads with authored roll and riser; three touch zones and four takes. Full dynamics.",
            seeded = false, tempoBpm = com.snipsnap.synth.FootworkKits.Kit.INDUSTRIAL.bpm.toFloat(),
        ) { com.snipsnap.synth.FootworkKits.build(com.snipsnap.synth.FootworkKits.Kit.INDUSTRIAL) },
        Starter(
            "hiccup-hop", "HICCUP HOP",
            "Glitch hop drums that trip over themselves. Three touch zones, four takes; a stumbling roll and a clap riser.",
            seeded = false, tempoBpm = com.snipsnap.synth.HiccupKits.Kit.HOP.bpm.toFloat(),
        ) { com.snipsnap.synth.HiccupKits.build(com.snipsnap.synth.HiccupKits.Kit.HOP) },
        Starter(
            "hiccup-lab", "HICCUP LAB",
            "IDM drums whose stutters turn into notes. Three touch zones, four takes; a shattering roll and a blip riser.",
            seeded = false, tempoBpm = com.snipsnap.synth.HiccupKits.Kit.LAB.bpm.toFloat(),
        ) { com.snipsnap.synth.HiccupKits.build(com.snipsnap.synth.HiccupKits.Kit.LAB) },
        Starter(
            "hiccup-sugar", "HICCUP SUGAR",
            "Hyperpop toy drums, bright and broken. Three touch zones, four takes; a popping roll and a coin riser.",
            seeded = false, tempoBpm = com.snipsnap.synth.HiccupKits.Kit.SUGAR.bpm.toFloat(),
        ) { com.snipsnap.synth.HiccupKits.build(com.snipsnap.synth.HiccupKits.Kit.SUGAR) },
        Starter(
            "clutter-lofi", "CLUTTER KITCHEN LO-FI",
            "Found-sound lo-fi from modelled household objects: a soft bucket, a box of rice brushed, a matchbox and loose keys, struck by palms and wooden spoons with the kitchen in every hit. Three touch zones, four takes.",
            seeded = false, tempoBpm = com.snipsnap.synth.ClutterKits.Kit.LOFI.bpm.toFloat(),
        ) { com.snipsnap.synth.ClutterKits.build(com.snipsnap.synth.ClutterKits.Kit.LOFI) },
        Starter(
            "clutter-street", "CLUTTER STREET BUCKET",
            "A busker drumline from modelled junk: a big bucket and its rim, a bin lid crash, pans and a ladle, everything loose and ringing, close and dry. Three touch zones, four takes.",
            seeded = false, tempoBpm = com.snipsnap.synth.ClutterKits.Kit.STREET.bpm.toFloat(),
        ) { com.snipsnap.synth.ClutterKits.build(com.snipsnap.synth.ClutterKits.Kit.STREET) },
        Starter(
            "clutter-boombap", "CLUTTER JUNK BOOM BAP",
            "Rap drums from modelled junk: a suitcase and a heel for the knock, a paper bag and a slammed book for the crack, tight jar and matchbox hats. Three touch zones, four takes.",
            seeded = false, tempoBpm = com.snipsnap.synth.ClutterKits.Kit.BOOMBAP.bpm.toFloat(),
        ) { com.snipsnap.synth.ClutterKits.build(com.snipsnap.synth.ClutterKits.Kit.BOOMBAP) },
        Starter(
            "clutter-micro", "CLUTTER MICROBEAT",
            "Tiny modelled objects tuned up and damped, the phone almost touching: a light switch, a zip, a sand jar, a mug and a can, clicky and detailed. Three touch zones, four takes.",
            seeded = false, tempoBpm = com.snipsnap.synth.ClutterKits.Kit.MICRO.bpm.toFloat(),
        ) { com.snipsnap.synth.ClutterKits.build(com.snipsnap.synth.ClutterKits.Kit.MICRO) },
        Starter(
            "servo-electro", "SERVO ELECTRO",
            "Electro machine drums on a weak power rail: a long low boom, a snap, a cowbell and toms that fall like lasers, each hit diving as it lands. Three touch zones, four takes; a roll and a riser that sink on one shared rail.",
            seeded = false, tempoBpm = com.snipsnap.synth.ServoKits.Kit.ELECTRO.bpm.toFloat(),
        ) { com.snipsnap.synth.ServoKits.build(com.snipsnap.synth.ServoKits.Kit.ELECTRO) },
        Starter(
            "servo-jit", "SERVO JIT",
            "Ghetto tech from the same machine, tuned up and run hot: a hard short kick, claps, rims and offbeat hats squared off against the rail and gone before the next sixteenth. Three touch zones, four takes; a roll and a riser.",
            seeded = false, tempoBpm = com.snipsnap.synth.ServoKits.Kit.JIT.bpm.toFloat(),
        ) { com.snipsnap.synth.ServoKits.build(com.snipsnap.synth.ServoKits.Kit.JIT) },
        Starter(
            "servo-neuro", "SERVO NEURO",
            "Neurofunk on a rail that rings: a punch kick, a snare with its body near 200 Hz, growling tails and bass stabs that snarl through a moving filter. Three touch zones, four takes; a roll and a riser.",
            seeded = false, tempoBpm = com.snipsnap.synth.ServoKits.Kit.NEURO.bpm.toFloat(),
        ) { com.snipsnap.synth.ServoKits.build(com.snipsnap.synth.ServoKits.Kit.NEURO) },
        Starter(
            "calabash-afrobeat", "CALABASH AFROBEAT",
            "Lagos in the 1970s, a big band's rhythm section live in one room: a round kick, a tight high snare, hats opening and closing, two congas and a tumba, a shekere, sticks and a bell, on warm sixteen-track tape. Three touch zones, four takes; a shekere riser.",
            seeded = false, tempoBpm = com.snipsnap.synth.CalabashKits.Kit.AFROBEAT.bpm.toFloat(),
        ) { com.snipsnap.synth.CalabashKits.build(com.snipsnap.synth.CalabashKits.Kit.AFROBEAT) },
        Starter(
            "calabash-afrobeats", "CALABASH AFROBEATS",
            "Today's Lagos and Accra pop, programmed close and dry: a deep round kick, a crisp rim, a clap and snaps, a swung shaker, congas, a talking drum and a high bell. Three touch zones, four takes; a talking-drum riser.",
            seeded = false, tempoBpm = com.snipsnap.synth.CalabashKits.Kit.AFROBEATS.bpm.toFloat(),
        ) { com.snipsnap.synth.CalabashKits.build(com.snipsnap.synth.CalabashKits.Kit.AFROBEATS) },
        Starter(
            "calabash-mbalax", "CALABASH MBALAX",
            "Dakar's dance bands: a sabar ensemble over a kit, the nder's stick and hand, the mbeng-mbeng and the col beneath, a tama talking over it and a high cracking snare, in a bright live room. Three touch zones, four takes; a sabar roll.",
            seeded = false, tempoBpm = com.snipsnap.synth.CalabashKits.Kit.MBALAX.bpm.toFloat(),
        ) { com.snipsnap.synth.CalabashKits.build(com.snipsnap.synth.CalabashKits.Kit.MBALAX) },
        Starter(
            "calabash-afrocuban", "CALABASH AFRO-CUBAN JAZZ",
            "New York in the 1950s, a Latin section beside a jazz drummer: congas, bongos, timbales and paila, a mambo bell, a clave, a ride and an open kick, heard across a big studio through valve and tape. Three touch zones, four takes; an abanico.",
            seeded = false, tempoBpm = com.snipsnap.synth.CalabashKits.Kit.AFROCUBAN.bpm.toFloat(),
        ) { com.snipsnap.synth.CalabashKits.build(com.snipsnap.synth.CalabashKits.Kit.AFROCUBAN) },
        Starter(
            "riddim-clave-dancehall", "RIDDIM CLAVE DANCEHALL",
            "Bright, tight dancehall on the 3-2 clave: a punch kick and a digital rimshot, a clap, choked hats, a shaker, claves, sticks and a cowbell, and four hard-mallet woods in F sharp minor. Three touch zones, four takes; a rim roll.",
            seeded = false, tempoBpm = com.snipsnap.synth.RiddimKits.Kit.CLAVE_DANCEHALL.bpm.toFloat(),
        ) { com.snipsnap.synth.RiddimKits.build(com.snipsnap.synth.RiddimKits.Kit.CLAVE_DANCEHALL) },
        Starter(
            "riddim-dembow", "RIDDIM DEMBOW",
            "Deep and heavy dembow: a driven sub kick, a lower snare and a wide clap, darker hats, a conga, a bongo, a talking drum and a low bronze bar that wobbles. Three touch zones, four takes; a snare roll.",
            seeded = false, tempoBpm = com.snipsnap.synth.RiddimKits.Kit.DEMBOW.bpm.toFloat(),
        ) { com.snipsnap.synth.RiddimKits.build(com.snipsnap.synth.RiddimKits.Kit.DEMBOW) },
        Starter(
            "riddim-boom-riddim", "RIDDIM BOOM RIDDIM",
            "The kick is the bass: six tuned booms from F1 to E flat 2, one sliding, that cut each other off like one bass voice, with a ringing rim, choked hats, a clap and bronze bars in F minor. Three touch zones, four takes; a hat riser.",
            seeded = false, tempoBpm = com.snipsnap.synth.RiddimKits.Kit.BOOM_RIDDIM.bpm.toFloat(),
        ) { com.snipsnap.synth.RiddimKits.build(com.snipsnap.synth.RiddimKits.Kit.BOOM_RIDDIM) },
        Starter(
            "riddim-baile-funk", "RIDDIM BAILE FUNK",
            "Brazilian funk at 130: one tight kick a bar, the tamborzao on a tumba and a quinto, a clap, choked hats, a high ting bell, an agogo and a wooden ratchet you can count. Three touch zones, four takes; a ratchet riser.",
            seeded = false, tempoBpm = com.snipsnap.synth.RiddimKits.Kit.BAILE_FUNK.bpm.toFloat(),
        ) { com.snipsnap.synth.RiddimKits.build(com.snipsnap.synth.RiddimKits.Kit.BAILE_FUNK) },
        Starter(
            "riddim-sabar-swing", "RIDDIM SABAR SWING",
            "Slow, dark and swung: a deep sub kick just after each beat, a low snare and rim, dark hats, the sabar family (mbeng, nder, col), a dundun, a talking-drum call, a shekere, a tama and a G1 boom. Three touch zones, four takes; a sabar roll.",
            seeded = false, tempoBpm = com.snipsnap.synth.RiddimKits.Kit.SABAR_SWING.bpm.toFloat(),
        ) { com.snipsnap.synth.RiddimKits.build(com.snipsnap.synth.RiddimKits.Kit.SABAR_SWING) },
        Starter(
            "riddim-wood-bronze", "RIDDIM WOOD AND BRONZE",
            "Quiet and pitched, in D minor: five hard-mallet woods and four bronze pairs that beat, over a soft kick, sticks, claves and quiet hats. Three touch zones, four takes; a wood tremolo.",
            seeded = false, tempoBpm = com.snipsnap.synth.RiddimKits.Kit.WOOD_BRONZE.bpm.toFloat(),
        ) { com.snipsnap.synth.RiddimKits.build(com.snipsnap.synth.RiddimKits.Kit.WOOD_BRONZE) },
        Starter(
            "ember", "EMBER",
            "A live jazz kit in a small club in 1962, hard bop on sticks: an open calfskin kick feathered under the band, a crisp snare, a riveted ride carrying the time, a press roll and a mallet cymbal swell. Three touch zones, four takes.",
            seeded = false, tempoBpm = com.snipsnap.synth.EmberKits.Kit.CLUB.bpm.toFloat(),
        ) { com.snipsnap.synth.EmberKits.build(com.snipsnap.synth.EmberKits.Kit.CLUB) },
        Starter(
            "ember-after-hours", "EMBER AFTER HOURS",
            "Three in the morning, a ballad on brushes: slow sweeps, taps on 2 and 4, a far sizzle, a damped kick, two people snapping at the back, a brush roll. Three touch zones, four takes.",
            seeded = false, tempoBpm = com.snipsnap.synth.EmberKits.Kit.AFTER_HOURS.bpm.toFloat(),
        ) { com.snipsnap.synth.EmberKits.build(com.snipsnap.synth.EmberKits.Kit.AFTER_HOURS) },
        Starter(
            "ember-soul-jazz", "EMBER SOUL JAZZ",
            "The organ-trio set: a fat loose backbeat, tambourine and the room clapping, a sizzle crash, two congas, a snare roll, the 1962 chain pushed warmer. Three touch zones, four takes.",
            seeded = false, tempoBpm = com.snipsnap.synth.EmberKits.Kit.SOUL.bpm.toFloat(),
        ) { com.snipsnap.synth.EmberKits.build(com.snipsnap.synth.EmberKits.Kit.SOUL) },
        Starter(
            "ember-free", "EMBER FREE",
            "The late set: mallets on toms tuned wide, a bowed cymbal, snares off, an elbow in the floor tom, loose rattling hats and a cymbal swell. Three touch zones, four takes.",
            seeded = false, tempoBpm = com.snipsnap.synth.EmberKits.Kit.FREE.bpm.toFloat(),
        ) { com.snipsnap.synth.EmberKits.build(com.snipsnap.synth.EmberKits.Kit.FREE) },
        Starter(
            "ember-latin-jazz", "EMBER LATIN JAZZ",
            "A cha-cha-cha in the small club, 1962: the jazz drummer plays Latin on the kit (the clave on the rim, the hat foot) while a percussionist adds the guiro, a cha-cha bell, timbales, bongos, a conga and a shaker. Three touch zones, four takes.",
            seeded = false, tempoBpm = com.snipsnap.synth.EmberKits.Kit.LATIN.bpm.toFloat(),
        ) { com.snipsnap.synth.EmberKits.build(com.snipsnap.synth.EmberKits.Kit.LATIN) },
        Starter(
            "meridian-materials", "MERIDIAN MATERIALS",
            "Sixteen finite pads across four ringing voices, with three touch levels and five held-sound captures.",
            seeded = false,
        ) { SynthKits.meridianMaterials() },
        Starter(
            "meridian-six", "MERIDIAN SIX",
            "Sixteen finite pads across six voices, with three native touch levels and three octave roles.",
            seeded = false,
        ) { SynthKits.meridianSix() },
        Starter(
            "tempest", "TEMPEST",
            "Six-voice digital drum kit: kicks, snares, claps, toms, choking hats and driven metal.",
            seeded = false,
        ) { SynthKits.tempest() },
        Starter(
            "tempest-tuned", "TEMPEST TUNED",
            "The original E minor pentatonic kicks, alternate strikes and six metal colors.",
            seeded = false,
            key = KeySpec.parse("Eminpent"),
        ) { SynthKits.tempestTuned() },
        Starter(
            SplayDelivery.STARTER_ID, "SPLAY POPULATIONS",
            "One TONGUE voice. Five finite settings: 1, 2, 4, 8 and 16 mechanisms.",
            seeded = false,
        ) { SplayDelivery.starterPads() },
        Starter(
            "osteria", "OSTERIA",
            "The original evolving Note in C minor, with soft touches and a short tap.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.osteria() },
        Starter(
            "osteria-six-voice", "OSTERIA SIX VOICES",
            "Note, bass, pluck, reed, bowed and pad: twelve evolving sounds with three native touch levels.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { com.snipsnap.synth.OsteriaKits.sixVoice() },
        Starter(
            "tempest-circuit", "TEMPEST CIRCUIT",
            "Electro TEMPEST kit: dry tight kicks, three claps, zaps, choking hats and tuned bells.",
            seeded = false,
        ) { SynthKits.tempestCircuit() },
        Starter(
            "tempest-foundry", "TEMPEST FOUNDRY",
            "Industrial TEMPEST kit: driven kicks with echoes, metal snares, pipe toms and anvil and chain hits.",
            seeded = false,
        ) { SynthKits.tempestFoundry() },
        Starter(
            "tempest-step", "TEMPEST STEP",
            "UK garage TEMPEST kit: short kicks, rim and ghost snares, five choking hats and pitched blips.",
            seeded = false,
        ) { SynthKits.tempestStep() },
        Starter(
            "bristle", "BRISTLE",
            "Bass walk, six new source voices and the approved A/B routes. Three touch levels, two takes per pad.",
            seeded = false, key = KeySpec.parse("Cminpent"),
        ) { SynthKits.bristle() },
        Starter("samba-bateria", "SAMBA BATERIA", "Rio-style bateria: three surdo roles, caixa and tamborim sections, repinique calls, chocalho and agogo. Three touch levels and three takes.", seeded = false, tempoBpm = 140f) { com.snipsnap.synth.SambaKits.build() },
        Starter("drumline-field", "DRUMLINE FIELD CADENCE", "Tight marching battery: independent snares and tenors, five bass sizes and ringing cymbals. Three touch levels and three takes.", seeded = false, tempoBpm = 120f) { com.snipsnap.synth.DrumlineKits.build(com.snipsnap.synth.DrumlineKits.Kit.FIELD) },
        Starter("drumline-street", "DRUMLINE STREET CADENCE", "Open street battery: lower heads, smaller sections, softer mallets, split bass and an accelerating snare. Three touch levels and three takes.", seeded = false, tempoBpm = 100f) { com.snipsnap.synth.DrumlineKits.build(com.snipsnap.synth.DrumlineKits.Kit.STREET) },
        Starter(
            "terra", "TERRA",
            "World percussion kit: udu and cajon bass, djembe, dholak, dumbek and tabla, agogo bells and a balafon key.",
            seeded = false,
        ) { com.snipsnap.synth.TerraKits.classic() },
        Starter(
            "cairn", "CAIRN",
            "Stones that knock, answer, tumble, sing, grind and roll in a bowl, at C4.",
            seeded = false,
        ) { SynthKits.cairn() },
    ).plus(com.snipsnap.synth.PatioPerformance.Genre.entries.map { genre ->
        Starter(genre.id, genre.title.uppercase(java.util.Locale.ROOT), "Latin percussion, three velocities and four takes per voice, with a hand-drum roll and cuica.", seeded = false) {
            com.snipsnap.synth.PatioPerformance.build(genre)
        }
    }).plus(listOf(
        // Appended after PATIO: the stored starter order only grows at the end.
        Starter(
            SplayDelivery.BODIES_STARTER_ID, "SPLAY BODIES",
            "Five more things SPLAY's catches strike: WIRE, RUB, AIR, BAR and SKIN, each as you kept it.",
            seeded = false,
        ) { SplayDelivery.bodyStarterPads() },
        // TERRA's three genre kits, owner KEEP in round terra-presets-1, appended after SPLAY BODIES.
        Starter(
            "terra-sahel", "TERRA SAHEL",
            "West African TERRA kit: a deep udu, djembe bass, tones and slaps, two bells and a pentatonic balafon.",
            seeded = false,
        ) { com.snipsnap.synth.TerraGenreKits.build(com.snipsnap.synth.TerraGenreKits.Kit.SAHEL) },
        Starter(
            "terra-maqam", "TERRA MAQAM",
            "Darbuka-led TERRA kit: doums, teks and kas across three heads, a clay drop and two bells.",
            seeded = false,
        ) { com.snipsnap.synth.TerraGenreKits.build(com.snipsnap.synth.TerraGenreKits.Kit.MAQAM) },
        Starter(
            "terra-punjab", "TERRA PUNJAB",
            "Dholak and tabla TERRA kit: dholak booms, bayan bends, dayan strokes, a cajon backbeat and bells.",
            seeded = false,
        ) { com.snipsnap.synth.TerraGenreKits.build(com.snipsnap.synth.TerraGenreKits.Kit.PUNJAB) },
        // Opt-in beside the accepted EROSION starter, which stays as it is; appended after TERRA PUNJAB.
        Starter(
            com.snipsnap.synth.ErosionRoundRobinKits.ID, "EROSION ROUND ROBIN",
            "The eight EROSION pads with soft, medium and hard touch and four takes on each: a repeated hit sparks differently every time.",
            seeded = false,
        ) { com.snipsnap.synth.ErosionRoundRobinKits.build() },
        // Opt-in beside the kept TERRA starter, which stays as it is; appended after EROSION ROUND ROBIN.
        Starter(
            com.snipsnap.synth.TerraRoundRobinKits.ID, "TERRA ROUND ROBIN",
            "The sixteen TERRA pads with soft, medium and hard touch and four takes on each: a repeated hit lands a little differently every time.",
            seeded = false,
        ) { com.snipsnap.synth.TerraRoundRobinKits.build() },
        // Opt-in beside the accepted TEMPEST starter, which stays as it is; appended after TERRA ROUND ROBIN.
        Starter(
            com.snipsnap.synth.TempestRoundRobinKits.ID, "TEMPEST ROUND ROBIN",
            "The sixteen TEMPEST pads with four takes each: repeated hats, snares and claps vary like a real drum machine's.",
            seeded = false,
        ) { com.snipsnap.synth.TempestRoundRobinKits.build() },
        // Opt-in beside each kept TEMPEST genre starter, which stays as it is; appended after TEMPEST ROUND ROBIN.
        Starter(
            com.snipsnap.synth.TempestGenreRoundRobinKits.Kit.CIRCUIT.id, "CIRCUIT TAKES",
            "The sixteen TEMPEST CIRCUIT pads with four takes each: repeated hats, claps and zaps vary like a real drum machine's.",
            seeded = false,
        ) { com.snipsnap.synth.TempestGenreRoundRobinKits.build(com.snipsnap.synth.TempestGenreRoundRobinKits.Kit.CIRCUIT) },
        Starter(
            com.snipsnap.synth.TempestGenreRoundRobinKits.Kit.FOUNDRY.id, "FOUNDRY TAKES",
            "The sixteen TEMPEST FOUNDRY pads with four takes each: repeated snares, pipes and metal vary like a real drum machine's.",
            seeded = false,
        ) { com.snipsnap.synth.TempestGenreRoundRobinKits.build(com.snipsnap.synth.TempestGenreRoundRobinKits.Kit.FOUNDRY) },
        Starter(
            com.snipsnap.synth.TempestGenreRoundRobinKits.Kit.STEP.id, "STEP TAKES",
            "The sixteen TEMPEST STEP pads with four takes each: repeated hats, rims and claps vary like a real drum machine's.",
            seeded = false,
        ) { com.snipsnap.synth.TempestGenreRoundRobinKits.build(com.snipsnap.synth.TempestGenreRoundRobinKits.Kit.STEP) },
        // Opt-in beside each kept TERRA genre starter, which stays as it is; appended after STEP TAKES.
        Starter(
            com.snipsnap.synth.TerraGenreRoundRobinKits.Kit.SAHEL.id, "SAHEL TAKES",
            "The sixteen TERRA SAHEL pads with soft, medium and hard touch and four takes on each: a repeated hit lands a little differently every time.",
            seeded = false,
        ) { com.snipsnap.synth.TerraGenreRoundRobinKits.build(com.snipsnap.synth.TerraGenreRoundRobinKits.Kit.SAHEL) },
        Starter(
            com.snipsnap.synth.TerraGenreRoundRobinKits.Kit.MAQAM.id, "MAQAM TAKES",
            "The sixteen TERRA MAQAM pads with soft, medium and hard touch and four takes on each: a repeated hit lands a little differently every time.",
            seeded = false,
        ) { com.snipsnap.synth.TerraGenreRoundRobinKits.build(com.snipsnap.synth.TerraGenreRoundRobinKits.Kit.MAQAM) },
        Starter(
            com.snipsnap.synth.TerraGenreRoundRobinKits.Kit.PUNJAB.id, "PUNJAB TAKES",
            "The sixteen TERRA PUNJAB pads with soft, medium and hard touch and four takes on each: a repeated hit lands a little differently every time.",
            seeded = false,
        ) { com.snipsnap.synth.TerraGenreRoundRobinKits.build(com.snipsnap.synth.TerraGenreRoundRobinKits.Kit.PUNJAB) },
        // TERRA's Japanese kits from the stick voices (docs/TERRA_STICKS.md); provisional, appended after PUNJAB TAKES.
        Starter(
            com.snipsnap.synth.TerraStickKits.Kit.MATSURI.id, "TERRA MATSURI",
            "Japanese street festival drums: barrel drum, small drum, rim clicks, hand gong, cymbals, a roll and a riser. Three touch levels and four takes.",
            seeded = false, tempoBpm = com.snipsnap.synth.TerraStickKits.Kit.MATSURI.bpm.toFloat(),
        ) { com.snipsnap.synth.TerraStickKits.build(com.snipsnap.synth.TerraStickKits.Kit.MATSURI) },
        Starter(
            com.snipsnap.synth.TerraStickKits.Kit.THUNDER.id, "TERRA THUNDER",
            "Big Japanese drums: two odaiko and their pair, tuned barrel-drum toms, rim cracks, a cymbal wash, two rolls and a riser. Three touch levels and four takes.",
            seeded = false, tempoBpm = com.snipsnap.synth.TerraStickKits.Kit.THUNDER.bpm.toFloat(),
        ) { com.snipsnap.synth.TerraStickKits.build(com.snipsnap.synth.TerraStickKits.Kit.THUNDER) },
        Starter(
            com.snipsnap.synth.TerraStickKits.Kit.BON.id, "TERRA BON",
            "Japanese dance percussion: water drum, shaken cowbell, small drum, hand gong and slats, a slat roll and a bell riser. Three touch levels and four takes.",
            seeded = false, tempoBpm = com.snipsnap.synth.TerraStickKits.Kit.BON.bpm.toFloat(),
        ) { com.snipsnap.synth.TerraStickKits.build(com.snipsnap.synth.TerraStickKits.Kit.BON) },
        Starter(
            com.snipsnap.synth.TerraStickKits.Kit.DUSK.id, "TERRA DUSK",
            "Processed Japanese percussion: rewound and roomy odaiko, echoed drums, rim and gong, a smeared small drum, a ghosted fan, a dubbed water drum. Three touch levels and four takes.",
            seeded = false, tempoBpm = com.snipsnap.synth.TerraStickKits.Kit.DUSK.bpm.toFloat(),
        ) { com.snipsnap.synth.TerraStickKits.build(com.snipsnap.synth.TerraStickKits.Kit.DUSK) },
    )) + com.snipsnap.synth.SideroomKits.Kit.entries.map { kit ->
        Starter(kit.id, "SIDEROOM ${kit.title.uppercase(java.util.Locale.ROOT)}",
            "Synthesized ${kit.title} drums: eight instruments, a snare roll and a drum fill. Three strike strengths and three alternate hits.",
            seeded = false, tempoBpm = kit.bpm.toFloat()) { com.snipsnap.synth.SideroomKits.build(kit) }
    } + listOf(
        // Opt-in beside the kept ACCORD starter, which stays as it is; appended after the SIDEROOM kits.
        Starter(
            ACCORD_VOICES_ID, "ACCORD VOICES",
            "Reed, hammered strings, clock tick and tock, and bell. Each at C2, C3, C4 and C5, finite and dry.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.accordVoices() },
        // Opt-in beside the kept ACCORD starter, which stays as it is; appended after ACCORD VOICES.
        Starter(
            com.snipsnap.synth.AccordRoundRobinKits.ID, "ACCORD TAKES",
            "The sixteen ACCORD pads with soft, medium and hard touch and two takes on each: a repeated note is the same machine caught at another moment.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { com.snipsnap.synth.AccordRoundRobinKits.build() },
        // CONSORT's starter and its opt-in round robin, appended after ACCORD TAKES.
        Starter(
            CONSORT_ID, "CONSORT",
            "Flute, sax, oboe and duduk alone and in sections: lead lines with octaves, fifths and sevenths, in a room.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { SynthKits.consort() },
        Starter(
            com.snipsnap.synth.ConsortRoundRobinKits.ID, "CONSORT TAKES",
            "The sixteen CONSORT pads with soft, medium and hard breath and two takes on each: play harder to overblow, again to hear another way of playing it.",
            seeded = false,
            key = KeySpec.parse("Cminpent"),
        ) { com.snipsnap.synth.ConsortRoundRobinKits.build() },
        // DRAWL's starter and its takes, appended after CONSORT TAKES.
        Starter(
            com.snipsnap.synth.DrawlKits.ID, "DRAWL",
            "Resonator, bottleneck, lap and pedal steel, flat-top and nylon. Sixteen finite slides, notes and strums in D.",
            seeded = false,
            key = KeySpec.parse(com.snipsnap.synth.DrawlKits.KEY),
        ) { com.snipsnap.synth.DrawlKits.arranged() },
        Starter(
            com.snipsnap.synth.DrawlKits.TAKES_ID, "DRAWL TAKES",
            "The sixteen DRAWL pads with soft, medium and hard picking and four takes on each: a repeated note is another pass of the player.",
            seeded = false,
            key = KeySpec.parse(com.snipsnap.synth.DrawlKits.KEY),
        ) { com.snipsnap.synth.DrawlKits.takesArranged() },
    )

    fun byId(id: String): Starter? = ALL.firstOrNull { it.id == id }
}
