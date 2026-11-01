package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Scales
import com.snipsnap.kit.ArrangedPad
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NacreKitTest {
    @Test
    fun `sixteen finite notes retain native pitch strike and complete dry replay`() {
        val pads = SynthKits.nacre().mapIndexed { index, pad ->
            requireNotNull(pad) { "NACRE trial has an empty pad ${index + 1}" }
        }
        assertEquals(16, pads.size)
        assertEquals(16, pads.map { it.displayName }.distinct().size)
        val names = List(8) { "Pearl Close" } + listOf(
            "Coral Reply", "Kelp Passage", "Pearl Close", "Pearl Close",
            "Coral Reply", "Coral Reply", "Kelp Passage", "Kelp Passage",
        )
        val midis = listOf(48, 51, 53, 55, 58, 60, 63, 65) + List(8) { 48 }
        val velocities = List(10) { .65f } + listOf(.30f, 1f, .30f, 1f, .30f, 1f)
        val presets = NacrePresets.all().associateBy { it.name }
        val patches = pads.mapIndexed { index, pad ->
            val label = "A%02d".format(java.util.Locale.ROOT, index + 1)
            val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
            val patch = assertIs<NacrePatch>(recipe.patch)
            val original = presets.getValue(names[index])
            val strike = when (velocities[index]) {
                .30f -> "Soft"
                .65f -> "Medium"
                else -> "Hard"
            }
            val expectedName = "${names[index]} ${Scales.nameOf(midis[index])} $strike"
            assertEquals(original.copy(name = expectedName, midi = midis[index], velocity = velocities[index]),
                patch, "$label: the starting sound changed beyond its note and strike")
            assertEquals(expectedName, pad.displayName)
            assertEquals(3, patch.model)
            assertEquals(7L, patch.seed)
            assertEquals(0f, patch.macros.getValue("HOLD"))
            assertEquals(DrumClass.TONAL, pad.drumClass, "$label must remain available to IN KEY")
            assertTrue(pad.oneShot, "$label must finish after releasing a hardware pad")
            assertTrue(pad.takes.isEmpty())
            assertTrue(pad.softVariants.isEmpty(), "$label stores one authored strike, not hardware velocity layers")
            assertTrue(pad.layerNames.isEmpty())
            assertNull(pad.level, "$label must retain the house uniform mixer headroom")
            assertEquals(0, pad.tuneCoarse)
            assertEquals(0, pad.tuneFine)
            assertNull(recipe.fx)
            assertNull(recipe.treatment)
            assertNull(recipe.amount)
            assertNull(recipe.board)
            assertNull(recipe.pedals)
            assertNull(recipe.terminalFadeMs, "$label must keep the renderer's complete ending")
            assertFalse(recipe.alias)
            assertEquals(mapOf("engine" to "NACRE", "voice" to "PEARL", "preset" to names[index],
                "pitchMidi" to midis[index].toString(), "velocity" to velocities[index].toString(),
                "seed" to "7", "model" to "3"), pad.source)

            val restored = PadRecipe.fromJsonText(recipe.toJsonText())
            assertEquals(recipe, restored, "$label lost its complete saved performance")
            val replay = restored.render()
            assertEquals(1, pad.snip.channels)
            assertEquals(44_100, pad.snip.sampleRate)
            assertEquals(264_600, pad.snip.frameCount, "$label was trimmed or extended")
            assertTrue(pad.snip.samples.all { it.isFinite() })
            assertTrue(pad.snip.peak() in 0.001f..0.950001f)
            assertContentEquals(pad.snip.samples, replay.samples,
                "$label gained pitch processing, normalization, effects or a different ending")
            patch
        }
        assertEquals(listOf(48, 51, 53, 55, 58, 60, 63, 65), patches.take(8).map { it.midi })
        // The three C3 comparisons share medium strike strength. Their soft/hard
        // siblings keep source dynamics instead of normalizing every pad alike.
        for ((medium, soft, hard) in listOf(Triple(0, 10, 11), Triple(8, 12, 13), Triple(9, 14, 15))) {
            assertTrue(energy(pads[soft]) < energy(pads[medium]) && energy(pads[medium]) < energy(pads[hard]),
                "${patches[medium].name}: the authored strike strengths lost their native energy order")
        }
    }

    private fun energy(pad: ArrangedPad): Double = pad.snip.samples.sumOf { it.toDouble() * it }
}
