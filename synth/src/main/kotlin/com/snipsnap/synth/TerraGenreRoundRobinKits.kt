package com.snipsnap.synth

import com.snipsnap.kit.ArrangedPad

/**
 * Opt-in round robin for the three kept TERRA genre kits ([TerraGenreKits]): SAHEL TAKES, MAQAM
 * TAKES and PUNJAB TAKES, each the same sixteen pads with three touches (the kit's two softer
 * strikes and the hard hit) and [TAKES] takes per touch. It follows TERRA ROUND ROBIN
 * ([TerraRoundRobinKits]) exactly and reuses its take rule unchanged ([TerraRoundRobinKits.zones]):
 * a later take is the drum struck again, with its own noise, a slightly different spot where the
 * voice has a strike point, a slightly lighter landing where the voice's velocity law allows, and
 * the fitted drums' wash re-drawn at the kept band levels. Pitch is never moved. Take 0 of every
 * zone is the kept genre kit's own sample ([TerraGenreKits.build]), reused, not re-rendered.
 *
 * Each pad's takes are the renderer takes in [STRIKES], screened per pad as TERRA ROUND ROBIN's
 * were: a hard take starts within 1 ms of the kept hit, keeps its spectral shape within
 * [TerraRoundRobinKits.SHAPE_LIMIT_DB] and under 0.35 times the nearest other pad of its kit, has
 * 90 % of its energy by 0.7 to 1.4 times the kept hit's time, and sits at least
 * [TerraRoundRobinKits.MIN_DISTANCE_DB] from it, phase-blind. Of the first twelve renderer takes from
 * 1 to 47 that pass, the kit deals the three whose set with the kept hit is furthest apart, provided its hard takes stay
 * [TerraRoundRobinKits.MIN_DISTANCE_DB] apart and its softer zones 0.02 dB apart (Doum Muted needed renderer takes up to 89
 * for that). Six sibling pads are screened against the other pads by a looser rule ([OWN_NEAREST]).
 *
 * Grid pads carry no PadRecipe; each pad's kept recipe rides in its source as JSON beside the take
 * rule. The genre starters are unchanged.
 */
object TerraGenreRoundRobinKits {
    enum class Kit(val id: String, val displayName: String, val source: TerraGenreKits.Kit) {
        SAHEL("terra-sahel-round-robin", "SAHEL TAKES", TerraGenreKits.Kit.SAHEL),
        MAQAM("terra-maqam-round-robin", "MAQAM TAKES", TerraGenreKits.Kit.MAQAM),
        PUNJAB("terra-punjab-round-robin", "PUNJAB TAKES", TerraGenreKits.Kit.PUNJAB),
    }

    const val VERSION = 1
    const val TAKES = TerraRoundRobinKits.TAKES

    /** Each pad's renderer takes behind its kit takes, in dealing order, by pad name; the first is the kept hit. */
    val STRIKES: Map<Kit, Map<String, List<Int>>> = mapOf(
        Kit.SAHEL to linkedMapOf(
            "Udu Deep" to listOf(0, 5, 11, 15),
            "Djembe Boom" to listOf(0, 14, 17, 24),
            "Djembe Low" to listOf(0, 4, 17, 19),
            "Djembe Tone" to listOf(0, 11, 64, 91),
            "Djembe Tone Hi" to listOf(0, 6, 7, 18),
            "Djembe Crack" to listOf(0, 1, 11, 13),
            "Slap Light" to listOf(0, 1, 2, 14),
            "Djembe Muffle" to listOf(0, 8, 9, 12),
            "Bell Low" to listOf(0, 2, 8, 9),
            "Bell High" to listOf(0, 2, 4, 7),
            "Bell Clack" to listOf(0, 1, 8, 10),
            "Balafon G3" to listOf(0, 2, 3, 9),
            "Balafon A3" to listOf(0, 2, 8, 9),
            "Balafon B3" to listOf(0, 1, 8, 12),
            "Balafon D4" to listOf(0, 1, 8, 12),
            "Balafon E4" to listOf(0, 1, 6, 7),
        ),
        Kit.MAQAM to linkedMapOf(
            "Doum Low" to listOf(0, 3, 7, 9),
            "Doum Open" to listOf(0, 6, 10, 15),
            "Doum Muted" to listOf(0, 9, 24, 89),
            "Doum Heavy" to listOf(0, 2, 11, 14),
            "Tek" to listOf(0, 3, 9, 10),
            "Tek Hard" to listOf(0, 1, 5, 6),
            "Ka" to listOf(0, 1, 7, 8),
            "Ka Soft" to listOf(0, 2, 9, 12),
            "Tek Ghost" to listOf(0, 1, 2, 9),
            "Tek Ring" to listOf(0, 4, 7, 9),
            "Darbuka Pop" to listOf(0, 8, 10, 11),
            "Darbuka High" to listOf(0, 1, 9, 10),
            "Darbuka Low" to listOf(0, 4, 5, 10),
            "Udu Drop" to listOf(0, 2, 6, 10),
            "Bell Ring" to listOf(0, 4, 8, 11),
            "Bell Tick" to listOf(0, 1, 2, 6),
        ),
        Kit.PUNJAB to linkedMapOf(
            "Dholak Boom" to listOf(0, 3, 14, 18),
            "Dholak Hi" to listOf(0, 4, 10, 14),
            "Dholak Thud" to listOf(0, 31, 35, 36),
            "Dholak Ring" to listOf(0, 13, 18, 28),
            "Bayan Ghe Low" to listOf(0, 3, 6, 9),
            "Bayan Swoop" to listOf(0, 6, 7, 9),
            "Bayan Ka" to listOf(0, 1, 14, 20),
            "Tabla Na Hi" to listOf(0, 3, 7, 11),
            "Tabla Tin Lo" to listOf(0, 3, 5, 6),
            "Tabla Te" to listOf(0, 3, 11, 13),
            "Tun Long" to listOf(0, 4, 7, 27),
            "Tun Knock" to listOf(0, 21, 22, 28),
            "Cajon Snap" to listOf(0, 6, 9, 12),
            "Cajon Thump" to listOf(0, 8, 12, 21),
            "Bell Chime" to listOf(0, 3, 5, 7),
            "Bell Clink" to listOf(0, 6, 8, 12),
        ),
    )

    /**
     * The sibling pads screened against the other pads by "nearer its own kept hit than any other pad's" instead of
     * "under 0.35 times the nearest other pad". These kits voice the same drum twice on purpose: Djembe Tone and Djembe
     * Muffle are 0.81 dB apart, Djembe Boom and Djembe Low 2.0 dB, Tun Long and Tun Knock 1.95 dB. A fitted drum's
     * re-drawn wash moves its whole-hit shape 1.2 to 2.3 dB, so under the classic kit's rule not one of renderer takes 1
     * to 95 passed on these six. Every other bound holds unchanged.
     */
    val OWN_NEAREST: Set<String> = setOf("Djembe Boom", "Djembe Low", "Djembe Tone", "Djembe Muffle", "Tun Long", "Tun Knock")

    /** [patch]'s renderer takes in [kit]. */
    fun strikesFor(kit: Kit, patch: TerraPatch): List<Int> = STRIKES.getValue(kit).getValue(patch.name)

    /** The finished zone sample for one recipe, touch and kit take. Pure: the same arguments give the same bytes. */
    fun render(kit: Kit, patch: TerraPatch, velocity: Float, take: Int): com.snipsnap.audio.Snip {
        require(velocity in TerraRoundRobinKits.TOUCHES && take in 0 until TAKES)
        val s = strikesFor(kit, patch)[take]
        val hard = TerraRoundRobinKits.strike(patch, 1f, s)
        return if (velocity == 1f) hard else Velocity.peakMatch(hard, TerraRoundRobinKits.strike(patch, velocity, s))
    }

    /** The sixteen pads of [kit]. Take 0 of every zone is the kept genre kit's own sample. */
    fun build(kit: Kit): List<ArrangedPad> = build(kit) { strikesFor(kit, it) }

    /** [build] dealing each pad the renderer takes [strikes] gives it (the first must be 0): the screen tries candidates through this. */
    internal fun build(kit: Kit, strikes: (TerraPatch) -> List<Int>): List<ArrangedPad> {
        val kept = TerraGenreKits.build(kit.source).map { it!! }
        val patches = kept.map { PadRecipe.fromJsonValue(it.recipe!!).patch as TerraPatch }
        for (p in patches) strikes(p).let { require(it.size == TAKES && it[0] == 0 && it.toSet().size == TAKES) { "${p.name}: $it" } }
        val later = patches.parallelStream().map { patch -> strikes(patch).drop(1).map { TerraRoundRobinKits.zones(patch, it) } }.toList()
        val touches = TerraRoundRobinKits.TOUCHES
        return kept.indices.map { i ->
            val pad = kept[i]
            val patch = patches[i]
            val accepted = pad.softVariants + pad.snip
            check(accepted.size == touches.size) { "${patch.name}: the kept pad has ${accepted.size} touches" }
            ArrangedPad(
                snip = pad.snip,
                drumClass = pad.drumClass,
                displayName = patch.name,
                velocityTakes = touches.indices.map { z -> listOf(accepted[z]) + later[i].map { it[z] } },
                source = mapOf("engine" to TerraPatch.ENGINE, "voice" to patch.voice.name, "kit" to kit.source.displayName,
                    "status" to "kept", "round" to TerraGenreKits.ROUND,
                    "touch" to "${touches.joinToString(",")}; softer zones peak-matched to their own take's hard hit",
                    "recipe" to patch.toJsonText(),
                    "roundRobin" to "$TAKES takes per touch: take 1 is the kept hit, later takes are the drum struck again (fresh noise, the strike point moved, a lighter landing where the voice allows)",
                    "rendererTakes" to strikes(patch).joinToString(","),
                    "roundRobinVersion" to VERSION.toString()),
            )
        }
    }
}
