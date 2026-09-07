package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.ArrangedPad
import kotlin.random.Random

/**
 * SHUFFLE KIT and the remix bank — slot-machine kit design.
 *
 * [kit] rerolls every pad of the factory layout within its class: same
 * voices, same slots, scrambled macros — and the classifier audits every
 * roll, rerolling any pad that stopped being what its slot says it is. Half
 * of synth fun is the dice; the other half is that the dice can't break the
 * kit.
 *
 * [withRemixBank] doubles any kit: bank A untouched, bank B (pads 17–32)
 * the same pads re-treated through seeded FX — reversed, crunched, echoed,
 * washed, punched. Very much the culture the MPC comes from, and the first
 * export to exercise slots beyond 16.
 */
object Shuffle {

    /** What each factory slot is allowed to classify as after a reroll. */
    private data class Slot(
        val render: (Random) -> Pair<Patch, DrumClass>,
        val accept: Set<DrumClass>,
    )

    private fun thumpSlot(voice: ThumpVoice, cls: DrumClass, accept: Set<DrumClass>) = Slot(
        render = { r -> ThumpPatch("Shuffled ${voice.name.lowercase()}", voice, Thump.scramble(voice, r)) to cls },
        accept = accept,
    )

    private fun tinesSlot(voice: TinesVoice, cls: DrumClass, accept: Set<DrumClass>) = Slot(
        render = { r -> TinesPatch("Shuffled ${voice.name.lowercase()}", voice, Tines.scramble(voice, r)) to cls },
        accept = accept,
    )

    private val HATS = setOf(DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN)
    private val PERCISH = setOf(DrumClass.PERC, DrumClass.TONAL, DrumClass.SNARE, DrumClass.CLAP, DrumClass.TOM, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN)

    private val LAYOUT: List<Slot> = listOf(
        thumpSlot(ThumpVoice.KICK, DrumClass.KICK, setOf(DrumClass.KICK)),
        thumpSlot(ThumpVoice.SNARE, DrumClass.SNARE, setOf(DrumClass.SNARE)),
        thumpSlot(ThumpVoice.HAT_CLOSED, DrumClass.HAT_CLOSED, HATS),
        thumpSlot(ThumpVoice.HAT_OPEN, DrumClass.HAT_OPEN, HATS),
        thumpSlot(ThumpVoice.KICK, DrumClass.KICK, setOf(DrumClass.KICK)),
        thumpSlot(ThumpVoice.CLAP, DrumClass.CLAP, setOf(DrumClass.CLAP, DrumClass.SNARE)),
        thumpSlot(ThumpVoice.RIM, DrumClass.PERC, PERCISH),
        thumpSlot(ThumpVoice.COWBELL, DrumClass.PERC, PERCISH),
        thumpSlot(ThumpVoice.TOM, DrumClass.TOM, setOf(DrumClass.TOM)),
        thumpSlot(ThumpVoice.TOM, DrumClass.TOM, setOf(DrumClass.TOM)),
        thumpSlot(ThumpVoice.TOM, DrumClass.TOM, setOf(DrumClass.TOM)),
        thumpSlot(ThumpVoice.SNARE, DrumClass.SNARE, setOf(DrumClass.SNARE)),
        tinesSlot(TinesVoice.ZAP, DrumClass.PERC, PERCISH),
        tinesSlot(TinesVoice.BLOCK, DrumClass.PERC, PERCISH),
        tinesSlot(TinesVoice.CHIME, DrumClass.TONAL, PERCISH),
        tinesSlot(TinesVoice.BELL, DrumClass.TONAL, PERCISH),
    )

    private const val MAX_REROLLS = 8

    /** A fresh 16-pad kit from one seed: factory layout, rerolled sounds. */
    fun kit(seed: Int): List<ArrangedPad?> {
        val random = Random(seed)
        return LAYOUT.map { slot ->
            var accepted: Pair<Patch, DrumClass>? = null
            repeat(MAX_REROLLS) {
                if (accepted != null) return@repeat
                val (patch, cls) = slot.render(random)
                if (Classifier.classify(patch.render()).drumClass in slot.accept) {
                    accepted = patch to cls
                }
            }
            // The dice ran cold: factory defaults are always in class.
            val (patch, cls) = accepted ?: slot.render(random).let { (p, c) ->
                when (p) {
                    is ThumpPatch -> ThumpPatch(p.name, p.voice, emptyMap()) to c
                    is TinesPatch -> TinesPatch(p.name, p.voice, emptyMap()) to c
                    else -> p to c
                }
            }
            ArrangedPad(patch.render(), cls, PadRecipe(patch).toJsonValue())
        }
    }

    /** The five remix treatments the bank draws from. */
    internal val TREATMENTS: List<Pair<String, FxChain>> = listOf(
        "reversed" to FxChain(reverse = true, spring = mapOf("SIZE" to 0.45f, "MIX" to 0.35f)),
        "crushed" to FxChain(crunch = mapOf("BITS" to 0.85f, "RATE" to 0.7f, "TONE" to 0.5f, "GRIT" to 0.4f)),
        "slapback" to FxChain(echo = mapOf("TIME" to 0.25f, "REPEAT" to 0.3f, "TONE" to 0.4f, "MIX" to 0.5f)),
        "washed" to FxChain(spring = mapOf("SIZE" to 0.7f, "TONE" to 0.5f, "MIX" to 0.5f)),
        "punched" to FxChain(
            squash = mapOf("AMOUNT" to 0.8f, "ATTACK" to 0.7f),
            crunch = mapOf("BITS" to 0.55f, "RATE" to 0.45f, "TONE" to 0.7f, "GRIT" to 0.3f),
        ),
    )

    /**
     * Bank A as given, bank B remixed: each source pad re-treated through a
     * seeded treatment on the pad sixteen up. The remix pad's recipe is
     * fx-only — "re-treat the bank-A sound with this chain" — which is
     * exactly how it was made.
     */
    fun withRemixBank(arranged: List<ArrangedPad?>, seed: Int): List<ArrangedPad?> {
        require(arranged.size <= 16) { "remix bank doubles a 16-pad kit, got ${arranged.size}" }
        val random = Random(seed)
        val bankA = arranged + List(16 - arranged.size) { null }
        val bankB = bankA.map { pad ->
            if (pad == null) return@map null
            val (treatment, fx) = TREATMENTS[random.nextInt(TREATMENTS.size)]
            ArrangedPad(
                snip = fx.process(pad.snip),
                drumClass = pad.drumClass,
                recipe = PadRecipe(fx = fx, treatment = treatment, amount = 1f).toJsonValue(),
                level = pad.level,
            )
        }
        return bankA + bankB
    }
}
