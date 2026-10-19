package com.snipsnap.shell

import com.snipsnap.synth.FxChain
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.BoardRecipe
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BreedFxTest {

    @Test
    fun `breeding two parents keeps every rack section`() {
        var chain = FxChain()
        for (name in FxChain.SECTION_NAMES) {
            chain = chain.withSection(name, FxChain.macrosOf(name).associate { it.name to 0.6f })
        }
        val parent = PadRecipe(fx = chain)
        for (seed in 0 until 8) {
            val child = Breed.cross(parent, parent, Random(seed.toLong()))
            for (name in FxChain.SECTION_NAMES) {
                assertTrue(
                    child.fx?.section(name) != null,
                    "seed $seed: breed dropped section '$name' - both parents had it",
                )
            }
        }
    }

    @Test
    fun `compound board recipes cannot be silently stripped by the rack crosser`() {
        val board = PadRecipe(fx = FxChain(eq = mapOf("MID" to .6f)), board = BoardRecipe(amount = .7f))
        val rack = PadRecipe(fx = FxChain(tape = mapOf("AGE" to .4f)))
        assertFailsWith<IllegalArgumentException> { Breed.cross(board, rack, Random(0)) }
        assertFailsWith<IllegalArgumentException> { Breed.cross(rack, board, Random(0)) }
    }
}
