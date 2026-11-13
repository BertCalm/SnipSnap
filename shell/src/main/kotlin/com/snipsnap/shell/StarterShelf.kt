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
        Group(Copy.STARTER_HEADING_BASS, listOf("keel", "upright", "contour", "ballast", "tangle")),
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
                "libration", "meridian", "accord", "thaw", "scour", "nimbus", "suture",
                "tessera", "pitchwheel", "chamber", "alloy", "caustic", "cistern", "filament", "erosion",
            ),
        ),
        Group(Copy.STARTER_HEADING_SKINS, listOf("skin", "tremor", "revel", "triad-techno", "triad-house", "triad-minimal", "rivet-workshop", "rivet-factory", "rivet-warehouse", "velour", "velour-bloom", "backwash-soul", "backwash-psych", "fired-bronze", "cosmic-breaks", "cosmic-jungle", "cosmic-dubstep", "spore-jungle", "spore-dub", "spore-electro", "gatecrash-stone", "gatecrash-machine", "gatecrash-electro", "cypher-trap", "cypher-drill", "cypher-backroom", "cypher-lowrider", "cypher-dust", "cypher-flint", "breaker", "breaker-dub", "breaker-triphop", "mirrorball-philly", "mirrorball-loft", "mirrorball-munich", "soot", "soot-drumless", "soot-dub", "soot-noir", "soot-industrial")),
        Group(
            Copy.STARTER_HEADING_MOVING,
            listOf("cloud", "osprey", "osprey-curated", "skitter", "flock", "relay", "wick"),
        ),
    )

    /** Every group with its starters resolved, in display order. */
    fun display(): List<Pair<String, List<StarterKits.Starter>>> =
        GROUPS.map { g -> g.heading to g.ids.map { id -> checkNotNull(StarterKits.byId(id)) { "StarterShelf names unknown starter '$id'" } } }
}
