package com.snipsnap.shell

/**
 * The starter picker's DISPLAY order: [StarterKits.ALL] filed under headings
 * by what each starter sounds like (UAT finding E1-PICK, owner ruling
 * 2026-11-06). A separate table on purpose. [StarterKits.ALL] is the stored
 * order: StarterKitsTest pins it, and the ids, names and blurbs in it flow into
 * XPN metadata, so grouping the menu must not reorder or rename anything there.
 * The picker reads this table; nothing else does.
 *
 * Every starter sits in exactly one group (StarterShelfTest), so a new starter
 * fails that test until it is placed here. Order inside a group is the order
 * shown.
 */
object StarterShelf {
    class Group internal constructor(val heading: String, val ids: List<String>)

    val GROUPS: List<Group> = listOf(
        Group(
            Copy.STARTER_HEADING_START_HERE,
            listOf("blank", "factory", "velocity", "lucky-dip", "lucky-dip-ab", "strike-fx", "chip", "vent"),
        ),
        Group(Copy.STARTER_HEADING_BASS, listOf("tempest-tuned", com.snipsnap.synth.EtchKits.ID, "keel", "upright", "contour", "ballast", "tangle", "bristle")),
        Group(
            Copy.STARTER_HEADING_KEYS,
            listOf(
                "chord-stabs", "chord-stabs-dorian", "chord-stabs-mixolydian", "chord-stabs-borrowed", "melodic", "rime", "rime-complete", "origami", "prism",
                "stoop", "lacquer", "crease", "opensky", "otis", "halobar", "halobar-six",
            ),
        ),
        Group(
            Copy.STARTER_HEADING_PLUCKS,
            listOf(
                "ohm", "tidal", "nacre", "overlap", "trio", "trefoil",
                "lattice", "scanned-chain", "spool", "shoal",
            ),
        ),
        Group(
            Copy.STARTER_HEADING_BREATH,
            listOf("embouchure", "bellows", "obbligato", "circuit", "undertow", "aerostat"),
        ),
        Group(
            Copy.STARTER_HEADING_VOICES,
            listOf(StarterKits.CONFLUENCE_ID, StarterKits.CONFLUENCE_VOICES_ID, "murmur", "murmur-selected", "murmur-kept"),
        ),
        Group(
            Copy.STARTER_HEADING_METAL,
            listOf(
                "libration", "meridian", "meridian-materials", "meridian-six", "accord", com.snipsnap.synth.AccordRoundRobinKits.ID, StarterKits.ACCORD_VOICES_ID, "thaw", "scour", "nimbus", "suture",
                "tessera", "pitchwheel", "chamber", "alloy", "caustic", "cistern", "filament", "erosion", com.snipsnap.synth.ErosionRoundRobinKits.ID, SplayDelivery.STARTER_ID, SplayDelivery.BODIES_STARTER_ID,
                "cairn",
            ),
        ),
        Group(Copy.STARTER_HEADING_SKINS, listOf("sunroom-early", "sunroom-orchestral", "sunroom-psych", "soul-rock-pocket", "soul-rock-roadhouse", "soul-rock-open-room", "drumline-field", "drumline-street", "samba-bateria", "skin", "tremor", "revel", "east-africa-bongo-flava", "east-africa-singeli", "east-africa-bongopiano", "east-africa-genge", "east-africa-kapuka", "timber-pocket", "timber-boom-bap", "timber-two-step", "triad-techno", "triad-house", "triad-minimal", "rivet-workshop", "rivet-factory", "rivet-warehouse", "knee-break-boom-bap", "knee-break-trip-hop", "knee-break-funk", "knee-break-body", "knee-break-knee", "knee-break-cassette", "knee-break-tight", "knee-break-tight-knee", "knee-break-tight-cassette",  "velour-close", "velour", "velour-bloom", "backwash-soul", "backwash-psych", "fired-bronze", "cuttings-pocket", "cuttings-night", "cuttings-circuit", "cosmic-breaks", "cosmic-jungle", "cosmic-dubstep", "spore-synth-jungle", "spore-synth-dub", com.snipsnap.synth.Splinter.ID, com.snipsnap.synth.Shatter.id, com.snipsnap.synth.Soundclash.id, com.snipsnap.synth.Redline.id, com.snipsnap.synth.Lull.id, com.snipsnap.synth.Strobe.id, com.snipsnap.synth.Cartridge.id, com.snipsnap.synth.Barricade.id, "spore-synth-broken", "spore-jungle", "spore-dub", "spore-electro", "gatecrash-stone", "gatecrash-machine", "gatecrash-electro", "cypher-trap", "cypher-drill", "cypher-backroom", "cypher-lowrider", "cypher-dust", "cypher-flint", "loose-crate-hummin", "loose-crate-cloth", "loose-crate-haze", "loose-crate-ritual", "loose-crate-blue", "loose-crate-speed", "loose-crate-raga", "stagger-cardboard", "stagger-matinee", "stagger-stumble", "facsimile-shuffle", "facsimile-knock", "facsimile-half-time", "facsimile-texture", "breaker", "breaker-dub", "breaker-triphop", "mirrorball-philly", "mirrorball-loft", "mirrorball-munich", "soot", "soot-drumless", "soot-dub", "soot-noir", "soot-industrial", "kerf-dust", "kerf-soul", "kerf-funk", "kerf-stomp", "scuff-detroit", "scuff-flint", "kindred-techno", "kindred-industrial", "kindred-electro", "kindred-ambient", "footwork-tap-jazz", "footwork-street-breaks", "footwork-industrial-stepping", "hiccup-hop", "hiccup-lab", "hiccup-sugar", "clutter-lofi", "clutter-street", "clutter-boombap", "clutter-micro", "servo-electro", "servo-jit", "servo-neuro", "calabash-afrobeat", "calabash-afrobeats", "calabash-mbalax", "calabash-afrocuban", "riddim-clave-dancehall", "riddim-dembow", "riddim-boom-riddim", "riddim-baile-funk", "riddim-sabar-swing", "riddim-wood-bronze", "ember", "ember-after-hours", "ember-soul-jazz", "ember-free", "ember-latin-jazz", "tempest", com.snipsnap.synth.TempestRoundRobinKits.ID, "tempest-circuit", com.snipsnap.synth.TempestGenreRoundRobinKits.Kit.CIRCUIT.id, "tempest-foundry", com.snipsnap.synth.TempestGenreRoundRobinKits.Kit.FOUNDRY.id, "tempest-step", com.snipsnap.synth.TempestGenreRoundRobinKits.Kit.STEP.id, "terra", com.snipsnap.synth.TerraRoundRobinKits.ID, "terra-sahel", com.snipsnap.synth.TerraGenreRoundRobinKits.Kit.SAHEL.id, "terra-maqam", com.snipsnap.synth.TerraGenreRoundRobinKits.Kit.MAQAM.id, "terra-punjab", com.snipsnap.synth.TerraGenreRoundRobinKits.Kit.PUNJAB.id, com.snipsnap.synth.TerraStickKits.Kit.MATSURI.id, com.snipsnap.synth.TerraStickKits.Kit.THUNDER.id, com.snipsnap.synth.TerraStickKits.Kit.BON.id, com.snipsnap.synth.TerraStickKits.Kit.DUSK.id) + StarterKits.PATIO_IDS.toList() + StarterKits.SIDEROOM_IDS.toList()),
        Group(
            Copy.STARTER_HEADING_MOVING,
            listOf("cloud", "osprey", "osprey-curated", "skitter", "flock", "relay", "wick", "osteria", "osteria-six-voice"),
        ),
    )

    /** Every group with its starters resolved, in display order. */
    fun display(): List<Pair<String, List<StarterKits.Starter>>> =
        GROUPS.map { g -> g.heading to g.ids.map { id -> checkNotNull(StarterKits.byId(id)) { "StarterShelf names unknown starter '$id'" } } }
}
