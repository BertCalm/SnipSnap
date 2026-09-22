package com.snipsnap.shell

import com.snipsnap.synth.Draw
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Snap
import com.snipsnap.synth.SnapPatch
import com.snipsnap.synth.SnapVoice
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class BreedSnapTest {

    @Test
    fun `two ramps crossed with a seeded coin give a table that is a point-by-point mix of both`() {
        val up = IntArray(Snap.TABLE_SIZE) { it * 255 / (Snap.TABLE_SIZE - 1) }
        val down = IntArray(Snap.TABLE_SIZE) { 255 - it * 255 / (Snap.TABLE_SIZE - 1) }
        val a = PadRecipe(patch = SnapPatch("A", SnapVoice.HORIZON, mapOf("TUNE" to 0.2f), up))
        val b = PadRecipe(patch = SnapPatch("B", SnapVoice.HORIZON, mapOf("TUNE" to 0.8f), down))
        val child = Breed.cross(a, b, Random(1))
        val table = (child.patch as SnapPatch).table
        for (i in table.indices) {
            assertTrue(
                table[i] == up[i] || table[i] == down[i] || table[i] == (up[i] + down[i]) / 2,
                "point $i landed outside the three choices: ${table[i]}",
            )
        }
        assertTrue(table.indices.any { table[it] != up[it] }, "the child is not simply A's own line")
        assertTrue(table.indices.any { table[it] != down[it] }, "the child is not simply B's own line")
    }

    @Test
    fun `identical SNAP parents give an identical child`() {
        val table = Draw.wave(Draw.Wave.SAW)
        val recipe = PadRecipe(patch = SnapPatch("P", SnapVoice.DRAWN, mapOf("TUNE" to 0.5f), table))
        val child = Breed.cross(recipe, recipe, Random(9))
        assertTrue((child.patch as SnapPatch).table.contentEquals(table))
    }

    @Test
    fun `a cross that lands flat every try falls back to A's own table`() {
        // Every point alternates 0/255 in one parent and 255/0 in the
        // other, so the mean at every single point is the same 127 —
        // a coin that always picks "mean" crosses this pair to a flat
        // line on every attempt, deterministically (no luck required).
        val a = IntArray(Snap.TABLE_SIZE) { if (it % 2 == 0) 0 else 255 }
        val b = IntArray(Snap.TABLE_SIZE) { if (it % 2 == 0) 255 else 0 }
        val aRecipe = PadRecipe(patch = SnapPatch("A", SnapVoice.DRAWN, mapOf("TUNE" to 0.5f), a))
        val bRecipe = PadRecipe(patch = SnapPatch("B", SnapVoice.DRAWN, mapOf("TUNE" to 0.5f), b))
        val alwaysMean = object : Random(0) {
            override fun nextInt(bound: Int): Int = 2
        }
        val child = Breed.cross(aRecipe, bRecipe, alwaysMean)
        assertTrue((child.patch as SnapPatch).table.contentEquals(a), "a table that stayed flat on both tries falls back to A's own")
    }

    @Test
    fun `an envelope only one parent drew rides half the time, and both crossed give a mix`() {
        val table = Draw.wave(Draw.Wave.SINE)
        val shape = Draw.shape(Draw.Shape.PLUCK)
        val withShape = PadRecipe(patch = SnapPatch("A", SnapVoice.DRAWN, mapOf("TUNE" to 0.5f), table, shape))
        val withoutShape = PadRecipe(patch = SnapPatch("B", SnapVoice.DRAWN, mapOf("TUNE" to 0.5f), table))
        var withEnvelope = 0
        var withoutEnvelope = 0
        for (seed in 0 until 40) {
            val child = (Breed.cross(withShape, withoutShape, Random(seed.toLong())).patch as SnapPatch)
            if (child.envelope != null) withEnvelope++ else withoutEnvelope++
        }
        assertTrue(withEnvelope > 0 && withoutEnvelope > 0, "a shape only one parent drew should ride some of the time and not others: $withEnvelope/$withoutEnvelope")

        val otherShape = Draw.shape(Draw.Shape.SWELL)
        val bothShapes = PadRecipe(patch = SnapPatch("B", SnapVoice.DRAWN, mapOf("TUNE" to 0.5f), table, otherShape))
        val child = (Breed.cross(withShape, bothShapes, Random(3)).patch as SnapPatch).envelope!!
        for (i in child.indices) {
            assertTrue(child[i] == shape[i] || child[i] == otherShape[i] || child[i] == (shape[i] + otherShape[i]) / 2)
        }
    }
}
