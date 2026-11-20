package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad

/**
 * Opt-in round robin for the kept classic TERRA kit ([TerraKits.classic]): the sixteen pads, three
 * touches (the kit's two softer strikes and the hard hit), [TAKES] takes per touch. Take 0 is the
 * kept kit's zone sample, sample for sample: [build] takes it from [TerraKits.classic] itself.
 * The kit was kept by ear in terra-rr-1 (16 Nov 2026); TerraRoundRobinPinsTest pins every take.
 *
 * A later take is a genuine re-render of the same recipe at the same touch, the drum struck again
 * ([strike]). What changes is what a player's next stroke changes on that drum, per family:
 * - **Every voice: its own noise.** The renderer's take ([Terra.render]'s `take`) re-draws every
 *   noise TERRA draws: the palm's or stick's grit, the slap's smack, the tek's snap, the tin's
 *   tick, the doum's thud, the cajón's wires, the bar's and the low cajón's buzz, the agogô's
 *   clack and the fitted drums' residual wash. The drum itself (its modes, split pairs, partials,
 *   bends and knock) is the same on every take.
 * - **A slightly different spot**, on every voice with a strike point (POS): the udu and low
 *   cajón, the agogôs, the balafon, the cajón's tapa, the djembe slap and the dumbek tek. At most
 *   [POSITION_SPREAD] either way, drawn per pad and take by [Restrike.landing].
 * - **A slightly lighter landing**, where the voice's velocity law is gentle enough to stay the
 *   same stroke ([forceSpread]): the stick and palm voices (udu, low cajón, agogôs, balafon) land up
 *   to 15 % under their touch, as [Restrike] does; the cajón slap and the doum up to 7.5 %, the
 *   doum because it has no strike point and its thud is too quiet for fresh noise to be heard.
 *   The slap, tek and tin keep their touch: their smack, snap and tick grow as v^1.5, so a lighter
 *   landing reads as another stroke, and their fresh noise and spot already vary them. The fitted
 *   drums (djembe bass and open, dholak, bayan, tun) keep their touch: their residual wash,
 *   re-drawn, is their variation.
 * - **The fitted drums' wash at the kept levels.** A fresh draw of a fitted drum's residual moves
 *   each short band's energy by several dB: up to 4 dB off the kept hit's shape, and up to five
 *   times its energy time on the djembe bass. These takes ([Terra.renderFittedTake])
 *   hold every band at the energy the kept hit realised, so only the wash's grain is new.
 *
 * Every hard take is levelled by TERRA's output stage; the softer zones are peak-matched to their
 * own take's hard hit, as the kept kit's are. Each pad's takes are the renderer takes in [STRIKES],
 * screened (TerraRoundRobinKitsTest) to start when the kept hit starts, keep its spectral shape
 * within [SHAPE_LIMIT_DB], have 90 % of their energy by 0.7 to 1.4 times the kept hit's time, and
 * sit at least [MIN_DISTANCE_DB] from it, phase-blind. A pad's own list, because no one renderer
 * take passed on all sixteen pads: the fitted drums' washes and the udu's light landings drift
 * out on different takes.
 *
 * Grid pads carry no PadRecipe (a single-hit recipe cannot regenerate four takes); each pad's
 * kept recipe rides in its source as JSON beside the take rule. The TERRA starter is unchanged.
 */
object TerraRoundRobinKits {
    const val ID = "terra-round-robin"
    const val VERSION = 1
    const val TAKES = 4

    /**
     * Each pad's renderer takes behind its kit takes, in dealing order; the first is the kept hit.
     * Picked per pad by the screen (TerraRoundRobinKitsTest's bounds) from renderer takes 1 to 47,
     * the three passing takes whose set is furthest apart, phase-blind.
     */
    val STRIKES: Map<String, List<Int>> = linkedMapOf(
        "Udu Whoomp" to listOf(0, 1, 4, 17),
        "Bayan Drag" to listOf(0, 6, 9, 14),
        "Djembe Bass" to listOf(0, 3, 4, 12),
        "Cajon Low" to listOf(0, 2, 4, 6),
        "Dholak Bass" to listOf(0, 1, 4, 10),
        "Dumbek Doum" to listOf(0, 3, 4, 8),
        "Cajon Slap" to listOf(0, 6, 7, 12),
        "Djembe Open" to listOf(0, 5, 8, 11),
        "Tabla Tin" to listOf(0, 3, 4, 13),
        "Tabla Tun" to listOf(0, 7, 21, 36),
        "Dumbek Tek" to listOf(0, 3, 5, 10),
        "Djembe Slap" to listOf(0, 2, 7, 11),
        "Agogo Low" to listOf(0, 4, 8, 10),
        "Agogo High" to listOf(0, 3, 6, 7),
        "Agogo Clack" to listOf(0, 1, 3, 9),
        "Balafon Key" to listOf(0, 1, 2, 4),
    )

    /** [patch]'s renderer takes, by its name in the kept kit. */
    fun strikesFor(patch: TerraPatch): List<Int> = STRIKES.getValue(patch.name)

    /** How far a take's strike point moves, either way, on POS's 0..1: [Restrike.POSITION_SPREAD]. */
    const val POSITION_SPREAD = Restrike.POSITION_SPREAD

    /** The screen's spectral-shape bound against the kept hit, in dB (TerraRoundRobinKitsTest). */
    const val SHAPE_LIMIT_DB = 2.0

    /** The screen's least phase-blind distance of a take from the kept hit, in dB (TerraRoundRobinMeasure.spectroDistance). */
    const val MIN_DISTANCE_DB = 0.2

    /** The kept kit's touches, softest first, as [Velocity.variantsAt] strikes its two softer layers, then the hard hit. */
    val TOUCHES: List<Float> = List(2) { i -> 1f - (2 - i).toFloat() / 3 } + 1f

    /** The most a take of [voice] lands under its touch, as a fraction of it (the class doc says why). */
    fun forceSpread(voice: TerraVoice): Float = when (voice) {
        TerraVoice.RESONANT_CAVITY, TerraVoice.CONICAL_BELL, TerraVoice.TUNED_BAR, TerraVoice.COMPOUND_MEMBRANE -> Restrike.FORCE_SPREAD
        TerraVoice.BOX, TerraVoice.DOUM -> Restrike.FORCE_SPREAD / 2
        TerraVoice.SLAP, TerraVoice.DARBUKA, TerraVoice.DAYAN,
        TerraVoice.DJEMBE, TerraVoice.DHOLAK, TerraVoice.BAYAN, TerraVoice.TUN -> 0f
        // a stick voice's take is its own landing (TerraSticks): no second spread on top
        in TerraSticks.VOICES -> 0f
        else -> error("$voice has no force spread")
    }

    /** The kept classic kit's sixteen recipes, in pad order. */
    fun patches(): List<TerraPatch> = TerraKits.classic().map { PadRecipe.fromJsonValue(it!!.recipe!!).patch as TerraPatch }

    /** Where renderer take [strike] of [patch] lands: its touch factor and its POS offset. Take 0 is (1, 0). */
    internal fun landing(patch: TerraPatch, strike: Int): Pair<Float, Float> {
        if (strike == 0) return 1f to 0f
        val (force, offset) = Restrike.landing(1f, Dsp.seedFor("TERRA ROUND ROBIN", patch.name), strike)
        // Restrike.landing lands up to its own FORCE_SPREAD under the touch; scaled to this voice's spread.
        val factor = 1f - (1f - force) / Restrike.FORCE_SPREAD * forceSpread(patch.voice)
        val hasPos = Terra.defaults(patch.voice).containsKey("POS")
        return factor to (if (hasPos) offset else 0f)
    }

    /** [patch]'s macros with its strike point moved by [offset]. */
    private fun movedMacros(patch: TerraPatch, offset: Float): Map<String, Float> {
        if (offset == 0f) return patch.macros
        val pos = patch.macros["POS"] ?: Terra.defaults(patch.voice).getValue("POS")
        return patch.macros + ("POS" to (pos + offset).coerceIn(0f, 1f))
    }

    /** Renderer take [strike] of [patch] struck at [velocity], before any peak matching. Take 0 is the kept render. */
    internal fun strike(patch: TerraPatch, velocity: Float, strike: Int): Snip {
        val (factor, offset) = landing(patch, strike)
        if (patch.voice in Terra.FITTED_HANDS && patch.striker == null) return Terra.renderFittedTake(patch.voice, movedMacros(patch, offset), velocity * factor, strike)
        return patch.render(velocity * factor, strike, movedMacros(patch, offset))
    }

    /** One renderer take's three zones, softest first, the softer two peak-matched to its hard hit. */
    internal fun zones(patch: TerraPatch, strike: Int): List<Snip> {
        val hard = strike(patch, 1f, strike)
        return TOUCHES.map { v -> if (v == 1f) hard else Velocity.peakMatch(hard, strike(patch, v, strike)) }
    }

    /** The finished zone sample for one recipe, touch and kit take. Pure: the same arguments give the same bytes. */
    fun render(patch: TerraPatch, velocity: Float, take: Int): Snip {
        require(velocity in TOUCHES && take in 0 until TAKES)
        val s = strikesFor(patch)[take]
        val hard = strike(patch, 1f, s)
        return if (velocity == 1f) hard else Velocity.peakMatch(hard, strike(patch, velocity, s))
    }

    /** The sixteen pads. Take 0 of every zone is the kept kit's own sample ([TerraKits.classic]), reused, not re-rendered. */
    fun build(): List<ArrangedPad> = build { strikesFor(it) }

    /** [build] dealing each pad the renderer takes [strikes] gives it (the first must be 0): the screen tries candidates through this. */
    internal fun build(strikes: (TerraPatch) -> List<Int>): List<ArrangedPad> {
        val kept = TerraKits.classic().map { it!! }
        val patches = kept.map { PadRecipe.fromJsonValue(it.recipe!!).patch as TerraPatch }
        for (p in patches) strikes(p).let { require(it.size == TAKES && it[0] == 0 && it.toSet().size == TAKES) { "${p.name}: $it" } }
        val later = patches.parallelStream().map { patch -> strikes(patch).drop(1).map { zones(patch, it) } }.toList()
        return kept.indices.map { i ->
            val pad = kept[i]
            val patch = patches[i]
            val accepted = pad.softVariants + pad.snip
            check(accepted.size == TOUCHES.size) { "${patch.name}: the kept pad has ${accepted.size} touches" }
            ArrangedPad(
                snip = pad.snip,
                drumClass = pad.drumClass,
                displayName = patch.name,
                velocityTakes = TOUCHES.indices.map { z -> listOf(accepted[z]) + later[i].map { it[z] } },
                source = mapOf("engine" to TerraPatch.ENGINE, "voice" to patch.voice.name,
                    "touch" to "${TOUCHES.joinToString(",")}; softer zones peak-matched to their own take's hard hit",
                    "recipe" to patch.toJsonText(),
                    "roundRobin" to "$TAKES takes per touch: take 1 is the kept hit, later takes are the drum struck again (fresh noise, the strike point moved, a lighter landing where the voice allows)",
                    "rendererTakes" to strikes(patch).joinToString(","),
                    "roundRobinVersion" to VERSION.toString()),
            )
        }
    }
}
