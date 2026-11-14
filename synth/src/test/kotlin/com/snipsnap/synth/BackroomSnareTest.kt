package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.*

class BackroomSnareTest {
    private fun patch(macros: Map<String, Float> = BackroomSnare.defaults()) =
        SkinPatch("More Snap", SkinVoice.SNARE, macros, model = 2)

    @Test fun `all 150 heard pair clips retain their PCM24 hashes through saved model and velocity replay`() {
        val pins = javaClass.getResourceAsStream("/backroom-pairs.sha256")!!.bufferedReader().readLines()
        val file = Files.createTempFile("backroom", ".wav").toFile()
        try {
            for (line in pins) {
                val (hash, name) = line.split("  ")
                val fields = name.removeSuffix(".wav").split('_')
                val values = BackroomSnare.defaults() + mapOf(
                    fields[0].uppercase() to fields[2].toFloat() / 100f,
                    fields[1].uppercase() to fields[3].toFloat() / 100f,
                )
                val p = SkinPatch.fromJsonText(patch(values).toJsonText())
                val s = Velocity.layerAt(p.render(), p, null, fields[4].toFloat() / 100f,
                    Velocity.brightnessSpec(p))
                WavWriter.write(file, s, WavWriter.BitDepth.PCM_24)
                val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { "%02x".format(it) }
                assertEquals(hash, actual, name)
            }
        } finally { file.delete() }
    }

    @Test fun `legacy JSON and audio stay original while model two survives editing and recipe roundtrip`() {
        val legacy = BoomBapKits.parts().single { it.id == "snare" }.patch
        assertFalse("\"model\"" in legacy.toJsonText())
        assertEquals(1, SkinPatch.fromJsonText(legacy.toJsonText()).model)
        assertContentEquals(Skin.render(legacy.voice, legacy.macros).samples, legacy.render().samples)
        val p = Patches.edited(patch(), "Edited", BackroomSnare.defaults() + ("BODY" to .8f)) as SkinPatch
        assertEquals(2, p.model)
        val recipe = PadRecipe(p)
        assertTrue(recipe.alias)
        val restored = PadRecipe.fromJsonText(recipe.toJsonText())
        assertEquals(p, restored.patch)
        assertContentEquals(recipe.render().samples, restored.render().samples)
        assertFailsWith<IllegalArgumentException> { SkinPatch("wrong", SkinVoice.KICK, emptyMap(), 2) }
        assertFailsWith<IllegalArgumentException> { SkinPatch("wrong", SkinVoice.SNARE, emptyMap(), 3) }
        assertFails { SkinPatch.fromJsonText(p.toJsonText().replace("\"model\": 2", "\"model\": 2.5")) }
    }

    @Test fun `four control corners and soft strikes are finite bounded deterministic and order independent`() {
        for (bits in 0 until 16) {
            val values = BackroomSnare.macros.mapIndexed { i, spec -> spec.name to ((bits shr i) and 1).toFloat() }.toMap()
            val p = patch(values)
            assertEquals(BackroomSnare.recipe(values).toJsonValue(),
                BackroomSnare.recipe(values.entries.reversed().associate { it.toPair() }).toJsonValue())
            for (v in listOf(0f, .25f, .6f, 1f)) {
                val s = Velocity.atVelocity(p, v)
                assertTrue(s.samples.all { it.isFinite() && abs(it) <= 1f }, "$bits $v")
                assertTrue(s.peak() > .01f)
                assertTrue(abs(s.samples.average()) < .005)
                assertContentEquals(s.samples, Restrike.take(p, v, 17, 0)!!.samples)
            }
        }
        val p = patch()
        assertContentEquals(Restrike.take(p, .6f, 17, 2)!!.samples, Restrike.take(p, .6f, 17, 2)!!.samples)
        assertFalse(Restrike.take(p, .6f, 17, 2)!!.samples.contentEquals(Restrike.take(p, .6f, 17, 3)!!.samples))
    }

    @Test fun `controls settle safely and both shared controls retain travel at combined corners`() {
        assertContentEquals(patch().render().samples, BackroomSnare.render(mapOf("BODY" to Float.NaN)).samples)
        for (seed in 0..20) {
            val values = BackroomSnare.scramble(Random(seed))
            assertEquals(BackroomSnare.defaults().keys, values.keys)
            assertTrue(values.values.all { it in 0f..1f })
        }
        for (held in listOf(0f, .5f, 1f)) for (moving in listOf("BODY", "WIRE")) {
            val other = if (moving == "BODY") "WIRE" else "BODY"
            val decays = listOf(0f, .25f, .5f, .75f, 1f).map {
                BackroomSnare.recipe(mapOf(other to held, moving to it)).patch!!.macros.getValue("DECAY")
            }
            assertTrue(decays.zipWithNext().all { (a, b) -> b > a }, "$moving held $held: $decays")
        }
    }
}
