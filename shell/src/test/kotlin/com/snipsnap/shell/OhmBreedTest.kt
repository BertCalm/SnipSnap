package com.snipsnap.shell

import com.snipsnap.synth.Ohm
import com.snipsnap.synth.OhmOptions
import com.snipsnap.synth.OhmPatch
import com.snipsnap.synth.PadRecipe
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class OhmBreedTest {
    @Test
    fun `crossing source and reply instruments retains the first parent's playable recipe`() {
        val finite = Ohm.replyRingPatch().copy(reply = .2f, ring = .8f)
        val sustained = OhmPatch("Saved source", Ohm.defaults(4) + ("HOLD" to 1f), model = 4,
            options = OhmOptions(grains = .3f), releaseSeconds = .8f)
        for (seed in 0L..7L) {
            val child = assertIs<OhmPatch>(Breed.cross(PadRecipe(finite), PadRecipe(sustained), Random(seed)).patch)
            assertEquals(5, child.model)
            assertEquals(finite.reply, child.reply)
            assertEquals(finite.ring, child.ring)
            assertEquals(finite.options, child.options)
            assertFalse("HOLD" in child.macros)
            val reverse = assertIs<OhmPatch>(Breed.cross(PadRecipe(sustained), PadRecipe(finite), Random(seed)).patch)
            assertEquals(4, reverse.model)
            assertEquals(sustained.options, reverse.options)
            assertEquals(sustained.releaseSeconds, reverse.releaseSeconds)
            assertEquals(1f, reverse.macros["HOLD"])
            assertEquals(reverse, OhmPatch.fromJsonText(reverse.toJsonText()))
        }
    }
}
