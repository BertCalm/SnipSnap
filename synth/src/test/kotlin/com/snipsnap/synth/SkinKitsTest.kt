package com.snipsnap.synth

import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.DrumClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [SkinKits] held to the same layout contract [ThumpKitTest] holds the
 * analog kit to, plus the two things that are this kit's own.
 */
class SkinKitsTest {

    @Test
    fun `the acoustic kit lands on the conventional layout`() {
        val kit = SkinKits.classic()
        assertEquals(16, kit.size)
        assertEquals(DrumClass.KICK, kit[0]?.drumClass)
        assertEquals(DrumClass.SNARE, kit[1]?.drumClass)
        assertEquals(DrumClass.HAT_CLOSED, kit[2]?.drumClass)
        assertEquals(DrumClass.HAT_OPEN, kit[3]?.drumClass)
        assertTrue(kit.all { it != null }, "every pad of a starter kit is filled")
    }

    /**
     * The reason this kit exists rather than SKIN pads being folded into
     * the existing sixteen: it is the only place a player meets SHAKER,
     * STICK and RIDE without going to SYNTH and choosing a voice.
     */
    @Test
    fun `every SKIN voice reaches a pad`() {
        val voices = SkinKits.classic().mapNotNull { pad ->
            pad?.recipe?.let { PadRecipe.fromJsonValue(it) }?.patch?.voiceName
        }.toSet()
        assertEquals(
            SkinVoice.entries.map { it.name }.toSet(),
            voices,
            "a voice with no pad here has no front door outside the SYNTH screen",
        )
    }

    /**
     * The trap this kit had to step around, and the reason RIDE is
     * declared PERC while `SkinTest` says the classifier hears HAT_OPEN.
     *
     * `AutoPlace.muteGroupFor` puts HAT_CLOSED and HAT_OPEN - and nothing
     * else - into one choke group. A ride declared by what it *measures*
     * as would therefore be cut dead by every closed-hat hit. This checks
     * the consequence rather than the label: no pad on this kit shares
     * the hats' choke group except the two hats.
     */
    @Test
    fun `only the hats choke each other`() {
        val kit = SkinKits.classic()
        val hatGroup = AutoPlace.muteGroupFor(DrumClass.HAT_CLOSED)
        val choking = kit.withIndex().filter { (_, pad) ->
            pad != null && AutoPlace.muteGroupFor(pad.drumClass) == hatGroup
        }.map { (i, pad) -> "A${"%02d".format(i + 1)}:${pad?.drumClass}" }
        assertEquals(
            listOf("A03:HAT_CLOSED", "A04:HAT_OPEN"),
            choking,
            "something other than the two hats landed in the hat choke group. A ride or a shaker in " +
                "there is silenced by every hat hit, which is not what those instruments do.",
        )
    }

    /**
     * The kit names its sounds by preset so the macro values have one
     * home. If a preset is renamed, building the kit must fail loudly
     * rather than quietly drift onto different numbers.
     */
    @Test
    fun `every pad's macros come from a preset that still exists`() {
        for (pad in SkinKits.classic().filterNotNull()) {
            val patch = PadRecipe.fromJsonValue(pad.recipe!!).patch
                ?: error("a SkinKits pad carries a recipe with no patch in it")
            val voice = SkinVoice.entries.first { it.name == patch.voiceName }
            assertTrue(
                SkinPresets.forVoice(voice).any { it.macros == patch.macros },
                "${patch.name} carries macros that match no ${voice.name} preset - the values have " +
                    "drifted away from SkinPresets, which is the one home they are supposed to have.",
            )
        }
    }
}
