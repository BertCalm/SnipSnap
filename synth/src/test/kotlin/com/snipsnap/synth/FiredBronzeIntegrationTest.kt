package com.snipsnap.synth

import kotlin.test.*

class FiredBronzeIntegrationTest {
    @Test fun `editable factory kit preserves all 64 approved renders and metadata`() {
        val approved = FiredBronzePerformance.study()
        // Model 1 is the kit approved on MPC; model 2 deals the same layers as a round-robin grid.
        val editable = FiredBronzeInstrument.kit(model = 1)
        assertEquals(16, editable.size)
        approved.zip(editable).forEach { (old, new) ->
            assertContentEquals(old.snip.samples, new.snip.samples)
            old.softVariants.zip(new.softVariants).forEach { (a, b) -> assertContentEquals(a.samples, b.samples) }
            assertEquals(old.level, new.level)
            assertEquals(old.muteGroup, new.muteGroup)
            assertEquals(old.layerNames, new.layerNames)
            val recipe = PadRecipe.fromJsonValue(new.recipe!!)
            assertContentEquals(new.snip.samples, recipe.render().samples)
            assertEquals(recipe, PadRecipe.fromJsonText(recipe.toJsonText()))
        }
    }

    @Test fun `macros remain editable after patch roundtrip and each changes the render`() {
        val p = FiredBronzePatch("Edge Ring", FiredBronzeDryKit.Pad.TIN)
        val base = p.render()
        for (spec in FiredBronzeInstrument.macros) {
            val changed = p.withMacros(p.macros + (spec.name to if (spec.default < .5f) .9f else .1f))
            val restored = Patches.fromJsonValue(changed.toJsonValue())
            val rendered = restored.render()
            assertEquals(changed, restored)
            assertFalse(base.samples.contentEquals(rendered.samples), spec.name)
            assertTrue(rendered.samples.all { it.isFinite() && kotlin.math.abs(it) <= 1f }, spec.name)
            assertContentEquals(rendered.samples, restored.render().samples)
        }
        assertFailsWith<IllegalArgumentException> { p.withMacros(mapOf("HEAT" to Float.NaN)) }
        assertEquals(FiredBronzeInstrument.defaults(), FiredBronzeInstrument.settled(mapOf("HEAT" to Float.NaN)))
    }

    @Test fun `model 2 keeps every layer's first strike and deals fresh strikes after it`() {
        // Closeness as the kit tests measure it: the best alignment within 3 ms over the first half second.
        // A copy is more than 40 dB apart.
        fun apart(a: FloatArray, b: FloatArray): Double {
            val n = minOf(a.size, b.size, 22050); val lag = 132
            var best = 0.0
            for (d in -lag..lag) {
                var ab = 0.0; var aa = 0.0; var bb = 0.0
                for (i in maxOf(0, -d) until minOf(n, n - d)) { val x = a[i].toDouble(); val y = b[i + d].toDouble(); ab += x * y; aa += x * x; bb += y * y }
                best = maxOf(best, ab / kotlin.math.sqrt(aa * bb + 1e-30))
            }
            return -10 * kotlin.math.log10(2 * (1 - best) + 1e-12)
        }
        for (preset in FiredBronzePresets.all()) {
            val now = preset.copy(model = 2); val approved = preset.copy(model = 1)
            for (v in FiredBronzePerformance.layerVelocities) {
                val takes = (0 until FiredBronzeInstrument.TAKES).map { now.render(v, it).samples }
                assertContentEquals(approved.render(v, 0).samples, takes[0], "${preset.voice} at $v: the first strike is the approved layer")
                var closest = Double.NEGATIVE_INFINITY
                for (i in takes.indices) for (j in i + 1 until takes.size) closest = maxOf(closest, apart(takes[i], takes[j]))
                println("FIRED BRONZE ${preset.voice} at $v: closest strikes ${"%.1f".format(closest)} dB apart")
                assertTrue(closest < 40.0, "${preset.voice} at $v: every strike is another strike ($closest dB apart)")
                assertTrue(takes.all { t -> t.all { it.isFinite() && kotlin.math.abs(it) <= 1f } }, "${preset.voice} at $v")
                assertContentEquals(takes[1], now.render(v, 1).samples, "${preset.voice} at $v: a strike replays exactly")
            }
            // Model 1 ignores a take, so its saves keep their one strike.
            assertContentEquals(approved.render(1f, 0).samples, approved.render(1f, 2).samples, "${preset.voice}")
        }
    }

    @Test fun `a patch keeps its model through JSON, and a save made before models is model 1`() {
        val now = FiredBronzePatch("Low Kettle", FiredBronzeDryKit.Pad.KETTLE_LOW)
        assertEquals(FiredBronzeInstrument.MODEL_VERSION, now.model)
        assertEquals(now, Patches.fromJsonValue(now.toJsonValue()))
        val approved = now.copy(model = 1)
        assertFalse("model" in approved.toJsonValue().entries, "model 1 writes no model, as the approved kit's saves")
        assertEquals(approved, Patches.fromJsonValue(approved.toJsonValue()))
        assertFailsWith<IllegalArgumentException> { now.copy(model = FiredBronzeInstrument.MODEL_VERSION + 1) }
        assertTrue(Restrike.canRestrike(now))
        assertFalse(Restrike.canRestrike(approved))
    }
}
