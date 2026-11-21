package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad

/**
 * Three Japanese genre kits from TERRA's stick voices ([TerraSticks], docs/TERRA_STICKS.md), each a
 * different musical job, not one kit at three strengths:
 *
 * - **MATSURI** (street festival, 90 BPM): the barrel drum's don as the low voice, the small drum's ten
 *   and tsu as the time, the rim's ka answering, the hand gong and the cymbals on top, one big-drum
 *   accent, a small-drum roll and a barrel-drum riser.
 * - **THUNDER** (cinematic big drums, 70 BPM): both odaiko and their pair as the weight, three tuned
 *   barrel drums as toms, the rim crack, a long cymbal wash and a low gong ring, two big-drum rolls and
 *   a big-drum riser.
 * - **BON** (dance, 115 BPM): the water drum and the shaken clapper bell lead, the small drum and a soft
 *   barrel drum under them, the slats as the shaker, a slat roll and a bell riser. No odaiko.
 * - **DUSK** (processed, 90 BPM): the owner's references include delayed and granular versions of these
 *   instruments, so this kit is built around the rack ([FxChain]). The odaiko rewound and in a large room,
 *   echoes timed near the references' 90 BPM (TIME 0.66, about 250 ms, a dotted sixteenth; TIME 0.94, about
 *   333 ms, an eighth), the shime smeared into a spring, the rim through tape, the fan ghosted, the water drum
 *   dubbed. Since round 8 three pads run GRAIN, the rack's granular scatter, for the references'
 *   granular versions of the shime, the rim and the gong's edge.
 *
 * Every pad carries three touches (a third, two thirds and all of a full strike: [TOUCHES]) and
 * [TAKES] takes per touch. A touch is a different strike, not a quieter copy: each is rendered at its
 * velocity through the voice's own law and peak-matched to its take's hard hit, as every velocity zone
 * in the house is, so level is the hardware's velocity curve's job. A take is the voice's own next
 * landing (renderer takes 0 to 3, [TerraSticks]); a roll's take is a different roll ([TerraRolls]).
 *
 * Grid pads carry no PadRecipe: a single-hit recipe cannot regenerate twelve samples. Each pad's recipe
 * rides in its source as JSON beside the take rule, as TERRA's round-robin kits' do.
 *
 * Round 8 (2026-11-21): six pads ring past the hit's cut ([TerraArticulation.RING]): THUNDER's Odaiko Long, Odaiko
 * Deep, Cymbal Wash and Gong Low, MATSURI's Chan, BON's Bell Single.
 *
 * Status: the owner approved the kits' sound (round 7 on 2026-11-20, round 8 on 2026-11-21: "Everything sounds great").
 * TerraStickKitsPinsTest pins every pad's twelve zone samples as heard.
 */
object TerraStickKits {
    enum class Kit(val id: String, val displayName: String, val bpm: Int) {
        MATSURI("terra-matsuri", "TERRA MATSURI", 90),
        THUNDER("terra-thunder", "TERRA THUNDER", 70),
        BON("terra-bon", "TERRA BON", 115),
        DUSK("terra-dusk", "TERRA DUSK", 90),
    }

    const val TAKES = 4
    const val VERSION = 1

    /** The touches, softest first, as TERRA's round-robin kits deal them. */
    val TOUCHES: List<Float> = TerraRoundRobinKits.TOUCHES

    private val HIT = TerraArticulation.HIT
    private val ROLL = TerraArticulation.ROLL
    private val RISER = TerraArticulation.RISER
    private val RING = TerraArticulation.RING

    /** A pad on [voice]: every macro written out ([Terra.defaults] plus the pad's own). */
    private fun pad(name: String, voice: TerraVoice, articulation: TerraArticulation = HIT, vararg overrides: Pair<String, Float>) =
        TerraPatch(name, voice, Terra.defaults(voice) + overrides.toMap(), articulation = articulation)

    private val ODAIKO = TerraVoice.ODAIKO
    private val NAGADO = TerraVoice.NAGADO
    private val SHIME = TerraVoice.SHIME
    private val KA = TerraVoice.KA
    private val KANE = TerraVoice.KANE
    private val CHAPPA = TerraVoice.CHAPPA
    private val SASARA = TerraVoice.BINZASARA
    private val BELL = TerraVoice.COWBELL
    private val WATER = TerraVoice.WATER_DRUM

    private fun matsuri(): List<TerraPatch> = listOf(
        pad("Don", NAGADO),
        pad("Don Low", NAGADO, HIT, "TUNE" to .25f, "DECAY" to .7f),
        pad("Don Tight", NAGADO, HIT, "DECAY" to .2f, "FORCE" to .9f, "ATTACK" to .7f),
        pad("Ten", SHIME, HIT, "STROKE" to 0f),
        pad("Tsu", SHIME, HIT, "STROKE" to 1f, "FORCE" to .35f, "ATTACK" to .3f),
        pad("Ten High", SHIME, HIT, "TUNE" to .85f, "DECAY" to .3f),
        pad("Ka", KA),
        pad("Ka High", KA, HIT, "TUNE" to .8f, "DECAY" to .3f),
        pad("Chan", KANE, RING, "EDGE" to 0f),
        pad("Chiki", KANE, HIT, "EDGE" to 1f),
        pad("Chappa", CHAPPA),
        pad("Chappa Choke", CHAPPA, HIT, "DECAY" to .1f, "ATTACK" to .7f),
        // a festival accent, shorter and a little higher than THUNDER's Odaiko Pair, which rings the same pair
        pad("Don Big", ODAIKO, HIT, "DRUM" to .5f, "TUNE" to .6f, "DECAY" to .3f),
        pad("Sasara", SASARA),
        pad("Ten Roll", SHIME, ROLL, "STROKE" to 0f),
        pad("Don Riser", NAGADO, RISER),
    )

    private fun thunder(): List<TerraPatch> = listOf(
        pad("Odaiko Long", ODAIKO, RING, "DRUM" to 0f),
        pad("Odaiko Low", ODAIKO, HIT, "DRUM" to 1f),
        pad("Odaiko Pair", ODAIKO, HIT, "DRUM" to .5f),
        pad("Odaiko Deep", ODAIKO, RING, "DRUM" to 1f, "TUNE" to 0f, "DECAY" to .9f),
        pad("Odaiko Soft", ODAIKO, HIT, "DRUM" to 0f, "FORCE" to .2f, "ATTACK" to .3f),
        pad("Tom Low", NAGADO, HIT, "TUNE" to .1f, "DECAY" to .7f),
        pad("Tom Mid", NAGADO, HIT, "TUNE" to .45f),
        pad("Tom High", NAGADO, HIT, "TUNE" to .8f, "DECAY" to .4f),
        pad("Rim Crack", KA, HIT, "DECAY" to 0f, "FORCE" to 1f, "ATTACK" to 1f),
        pad("Rim Low", KA, HIT, "TUNE" to .1f, "DECAY" to .7f),
        pad("Cymbal Wash", CHAPPA, RING, "TUNE" to 0f, "DECAY" to 1f, "FORCE" to .2f, "ATTACK" to .3f),
        pad("Gong Low", KANE, RING, "TUNE" to 0f, "DECAY" to 1f, "EDGE" to 0f),
        pad("Snap", SHIME, HIT, "STROKE" to 0f, "DECAY" to .2f, "FORCE" to 1f),
        pad("Odaiko Roll", ODAIKO, ROLL, "DRUM" to 0f),
        pad("Odaiko Riser", ODAIKO, RISER, "DRUM" to .5f),
        pad("Tom Roll", NAGADO, ROLL, "TUNE" to .3f),
    )

    private fun bon(): List<TerraPatch> = listOf(
        pad("Water", WATER),
        pad("Water Low", WATER, HIT, "TUNE" to .1f, "DECAY" to .8f),
        pad("Water Tap", WATER, HIT, "DECAY" to .1f, "FORCE" to 1f, "ATTACK" to .8f),
        pad("Bell Shake", BELL),
        pad("Bell Single", BELL, RING, "SHAKE" to 0f),
        pad("Bell Muted", BELL, HIT, "SHAKE" to 0f, "DAMP" to 1f),
        pad("Ten Bright", SHIME, HIT, "STROKE" to 1f, "TUNE" to .7f),
        pad("Ten Ghost", SHIME, HIT, "STROKE" to 0f, "TUNE" to .6f, "FORCE" to .2f, "ATTACK" to .2f),
        pad("Don Soft", NAGADO, HIT, "TUNE" to .6f, "FORCE" to .25f, "ATTACK" to .3f),
        pad("Gong Mid", KANE, HIT, "EDGE" to .5f, "DECAY" to .3f),
        pad("Gong Edge Hi", KANE, HIT, "EDGE" to 1f, "TUNE" to .8f, "DECAY" to .2f),
        pad("Cymbal Small", CHAPPA, HIT, "TUNE" to 1f, "DECAY" to .3f),
        pad("Slat Snap", SASARA, HIT, "SPREAD" to 0f, "FORCE" to 1f),
        pad("Slat Slow", SASARA, HIT, "SPREAD" to 1f),
        pad("Slat Roll", SASARA, ROLL),
        pad("Bell Riser", BELL, RISER, "SHAKE" to 0f),
    )

    private val FAN = TerraVoice.FAN

    /** The rack's settings for an echo: TIME 0.66 is about 250 ms (a dotted sixteenth at 90 BPM), 0.94 about 333 ms (an eighth). */
    private fun echo(time: Float, repeat: Float, tone: Float, mix: Float) = mapOf("TIME" to time, "REPEAT" to repeat, "TONE" to tone, "MIX" to mix)

    /** DUSK: each pad's recipe and the chain baked into every one of its zone samples. Every recipe differs from every other kit's. */
    private fun dusk(): List<Pair<TerraPatch, FxChain>> = listOf(
        pad("Odaiko Rewind", ODAIKO, HIT, "DRUM" to .5f, "DECAY" to .7f) to FxChain(rewind = mapOf("MIX" to .8f, "TIME" to .55f, "REPEAT" to .3f)),
        pad("Odaiko Hall", ODAIKO, HIT, "DRUM" to 0f, "DECAY" to .8f, "TUNE" to .4f) to FxChain(room = mapOf("SIZE" to .85f, "WALLS" to .4f, "DISTANCE" to .75f)),
        pad("Don Echo", NAGADO, HIT, "TUNE" to .45f, "DECAY" to .4f) to FxChain(echo = echo(.66f, .5f, .6f, .4f)),
        pad("Don Ghost", NAGADO, HIT, "TUNE" to .3f, "DECAY" to .6f) to FxChain(ghost = mapOf("AMOUNT" to .7f), room = mapOf("SIZE" to .6f, "WALLS" to .5f, "DISTANCE" to .7f)),
        pad("Ten Echo", SHIME, HIT, "STROKE" to 0f, "DECAY" to .4f) to FxChain(echo = echo(.66f, .35f, .55f, .35f)),
        pad("Ten Grains", SHIME, HIT, "STROKE" to 1f, "TUNE" to .4f) to FxChain(grain = mapOf("MIX" to .7f, "SIZE" to .35f, "DENSITY" to .6f, "SCATTER" to .55f), spring = mapOf("SIZE" to .5f, "TONE" to .5f, "MIX" to .3f)),
        pad("Ka Echo", KA, HIT, "DECAY" to .3f, "ATTACK" to .7f) to FxChain(echo = echo(.3f, .6f, .7f, .45f)),
        pad("Ka Grains", KA, HIT, "TUNE" to .3f, "DECAY" to .6f) to FxChain(grain = mapOf("MIX" to .8f, "SIZE" to .2f, "DENSITY" to .75f, "SCATTER" to .5f)),
        pad("Chan Echo", KANE, HIT, "EDGE" to 0f, "DECAY" to .6f) to FxChain(echo = echo(.94f, .55f, .4f, .45f)),
        pad("Chiki Grains", KANE, HIT, "EDGE" to 1f, "DECAY" to .4f) to FxChain(grain = mapOf("MIX" to .7f, "SIZE" to .45f, "DENSITY" to .5f, "SCATTER" to .75f), room = mapOf("SIZE" to .5f, "WALLS" to .4f, "DISTANCE" to .7f)),
        pad("Cymbal Spring", CHAPPA, HIT, "DECAY" to .6f, "FORCE" to .4f) to FxChain(spring = mapOf("SIZE" to .7f, "TONE" to .45f, "MIX" to .5f)),
        pad("Fan Ghost", FAN, HIT, "SWEEP" to 1f, "DECAY" to .7f) to FxChain(ghost = mapOf("AMOUNT" to .6f), room = mapOf("SIZE" to .7f, "WALLS" to .3f, "DISTANCE" to .8f)),
        pad("Bell Echo", BELL, HIT, "SHAKE" to 0f, "DECAY" to .4f) to FxChain(echo = echo(.66f, .45f, .5f, .35f)),
        pad("Water Dub", WATER, HIT, "TUNE" to .4f, "DECAY" to .6f) to FxChain(dub = mapOf("GENERATIONS" to .6f), echo = echo(.94f, .35f, .35f, .3f)),
        pad("Ten Roll Echo", SHIME, ROLL, "STROKE" to 1f, "DECAY" to .4f) to FxChain(echo = echo(.66f, .3f, .55f, .3f)),
        pad("Odaiko Swell", ODAIKO, RISER, "DRUM" to .3f, "DECAY" to .6f) to FxChain(room = mapOf("SIZE" to .9f, "WALLS" to .4f, "DISTANCE" to .7f)),
    )

    /** The chain baked into [kit]'s pad [index], or null for a dry kit. */
    fun fxFor(kit: Kit, index: Int): FxChain? = if (kit == Kit.DUSK) dusk()[index].second else null

    /**
     * The sixteen recipes of [kit], in MPC pad order A01 to A16. Every roll and riser is locked to the kit's tempo
     * ([TerraPatch.bpm], round 8): a roll fills a bar, a riser climbs over two to a downbeat accent.
     */
    fun patches(kit: Kit): List<TerraPatch> = when (kit) {
        Kit.MATSURI -> matsuri()
        Kit.THUNDER -> thunder()
        Kit.BON -> bon()
        Kit.DUSK -> dusk().map { it.first }
    }.map { p -> if (p.articulation == ROLL || p.articulation == RISER) p.copy(bpm = kit.bpm) else p }

    /**
     * The pads that choke one another, by kit: a pressed cymbal cuts the open one, a hand on the bell cuts
     * its ring. Mute group 1; every other pad is 0.
     */
    private fun muteGroup(kit: Kit, name: String): Int = when (kit) {
        Kit.MATSURI -> if (name == "Chappa" || name == "Chappa Choke") 1 else 0
        Kit.BON -> if (name.startsWith("Bell ") && name != "Bell Riser") 1 else 0
        Kit.THUNDER, Kit.DUSK -> 0
    }

    /**
     * One zone sample: [patch] as kit take [take] at [velocity], through [fx] when the kit bakes one, a softer touch
     * peak-matched to its take's processed hard hit. The strike is softened before the chain, so a soft touch is a
     * softer strike through the same rack, not a quieter echo. Pure.
     */
    fun render(patch: TerraPatch, velocity: Float, take: Int, fx: FxChain? = null): Snip {
        require(velocity in TOUCHES && take in 0 until TAKES) { "a touch in $TOUCHES and a take in 0 until $TAKES" }
        val hard = processed(patch.render(1f, take), fx)
        return if (velocity == 1f) hard else Velocity.peakMatch(hard, processed(patch.render(velocity, take), fx))
    }

    private fun processed(snip: Snip, fx: FxChain?): Snip = fx?.process(snip) ?: snip

    /** The sixteen pads of [kit], each [TOUCHES] by [TAKES] zone samples. */
    fun build(kit: Kit): List<ArrangedPad> {
        val patches = patches(kit)
        val chains = patches.indices.map { fxFor(kit, it) }
        val grids = patches.indices.toList().parallelStream().map { i ->
            val p = patches[i]; val fx = chains[i]
            val hard = List(TAKES) { t -> processed(p.render(1f, t), fx) }
            TOUCHES.map { v -> List(TAKES) { t -> if (v == 1f) hard[t] else Velocity.peakMatch(hard[t], processed(p.render(v, t), fx)) } }
        }.toList()
        return patches.indices.map { i ->
            val p = patches[i]
            val grid = grids[i]
            val main = grid.last().first()
            ArrangedPad(
                snip = main,
                drumClass = Classifier.classify(main).drumClass,
                displayName = p.name,
                velocityTakes = grid,
                muteGroup = muteGroup(kit, p.name),
                source = mapOf(
                    "engine" to TerraPatch.ENGINE, "voice" to p.voice.name, "kit" to kit.displayName,
                    "articulation" to p.articulation.name, "status" to "provisional",
                    "touch" to "${TOUCHES.joinToString(",")}; softer zones peak-matched to their own take's hard hit",
                    "recipe" to p.toJsonText(),
                    "roundRobin" to "$TAKES takes per touch: renderer takes 0 to ${TAKES - 1}, each the voice's own next landing",
                    "roundRobinVersion" to VERSION.toString(),
                ) + (chains[i]?.let { mapOf("fx" to it.toJsonText()) } ?: emptyMap()),
            )
        }
    }
}
