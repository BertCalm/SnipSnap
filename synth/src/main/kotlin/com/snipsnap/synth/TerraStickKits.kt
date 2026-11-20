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
 * Status: provisional. The owner picked the voices by ear from the prototype's renders; nobody has heard
 * these kits from the app. Not pinned.
 */
object TerraStickKits {
    enum class Kit(val id: String, val displayName: String, val bpm: Int) {
        MATSURI("terra-matsuri", "TERRA MATSURI", 90),
        THUNDER("terra-thunder", "TERRA THUNDER", 70),
        BON("terra-bon", "TERRA BON", 115),
    }

    const val TAKES = 4
    const val VERSION = 1

    /** The touches, softest first, as TERRA's round-robin kits deal them. */
    val TOUCHES: List<Float> = TerraRoundRobinKits.TOUCHES

    private val HIT = TerraArticulation.HIT
    private val ROLL = TerraArticulation.ROLL
    private val RISER = TerraArticulation.RISER

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
        pad("Chan", KANE, HIT, "EDGE" to 0f),
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
        pad("Odaiko Long", ODAIKO, HIT, "DRUM" to 0f),
        pad("Odaiko Low", ODAIKO, HIT, "DRUM" to 1f),
        pad("Odaiko Pair", ODAIKO, HIT, "DRUM" to .5f),
        pad("Odaiko Deep", ODAIKO, HIT, "DRUM" to 1f, "TUNE" to 0f, "DECAY" to .9f),
        pad("Odaiko Soft", ODAIKO, HIT, "DRUM" to 0f, "FORCE" to .2f, "ATTACK" to .3f),
        pad("Tom Low", NAGADO, HIT, "TUNE" to .1f, "DECAY" to .7f),
        pad("Tom Mid", NAGADO, HIT, "TUNE" to .45f),
        pad("Tom High", NAGADO, HIT, "TUNE" to .8f, "DECAY" to .4f),
        pad("Rim Crack", KA, HIT, "DECAY" to 0f, "FORCE" to 1f, "ATTACK" to 1f),
        pad("Rim Low", KA, HIT, "TUNE" to .1f, "DECAY" to .7f),
        pad("Cymbal Wash", CHAPPA, HIT, "TUNE" to 0f, "DECAY" to 1f, "FORCE" to .2f, "ATTACK" to .3f),
        pad("Gong Low", KANE, HIT, "TUNE" to 0f, "DECAY" to 1f, "EDGE" to 0f),
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
        pad("Bell Single", BELL, HIT, "SHAKE" to 0f),
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

    /** The sixteen recipes of [kit], in MPC pad order A01 to A16. */
    fun patches(kit: Kit): List<TerraPatch> = when (kit) {
        Kit.MATSURI -> matsuri()
        Kit.THUNDER -> thunder()
        Kit.BON -> bon()
    }

    /**
     * The pads that choke one another, by kit: a pressed cymbal cuts the open one, a hand on the bell cuts
     * its ring. Mute group 1; every other pad is 0.
     */
    private fun muteGroup(kit: Kit, name: String): Int = when (kit) {
        Kit.MATSURI -> if (name == "Chappa" || name == "Chappa Choke") 1 else 0
        Kit.BON -> if (name.startsWith("Bell ") && name != "Bell Riser") 1 else 0
        Kit.THUNDER -> 0
    }

    /** One zone sample: [patch] as kit take [take] at [velocity], a softer touch peak-matched to its take's hard hit. Pure. */
    fun render(patch: TerraPatch, velocity: Float, take: Int): Snip {
        require(velocity in TOUCHES && take in 0 until TAKES) { "a touch in $TOUCHES and a take in 0 until $TAKES" }
        val hard = patch.render(1f, take)
        return if (velocity == 1f) hard else Velocity.peakMatch(hard, patch.render(velocity, take))
    }

    /** The sixteen pads of [kit], each [TOUCHES] by [TAKES] zone samples. */
    fun build(kit: Kit): List<ArrangedPad> {
        val patches = patches(kit)
        val grids = patches.parallelStream().map { p ->
            val hard = List(TAKES) { t -> p.render(1f, t) }
            TOUCHES.map { v -> List(TAKES) { t -> if (v == 1f) hard[t] else Velocity.peakMatch(hard[t], p.render(v, t)) } }
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
                ),
            )
        }
    }
}
