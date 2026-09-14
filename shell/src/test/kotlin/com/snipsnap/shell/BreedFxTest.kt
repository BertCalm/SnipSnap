package com.snipsnap.shell

import com.snipsnap.synth.FxChain
import com.snipsnap.synth.PadRecipe
import java.util.Random
import kotlin.test.Test
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
}
