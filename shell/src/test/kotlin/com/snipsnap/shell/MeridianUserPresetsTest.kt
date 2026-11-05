package com.snipsnap.shell

import com.snipsnap.synth.Meridian
import com.snipsnap.synth.MeridianPatch
import com.snipsnap.synth.MeridianPresets
import com.snipsnap.synth.MeridianVoice
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Complete recipes survive both the player shelf and the actual factory-table paste helper. */
class MeridianUserPresetsTest {
    private val factory get() = MeridianPresets.forVoice(MeridianVoice.SWEEP).single { it.name == "FINE STRAND" }
    private fun custom() = MeridianPatch("MY ARC \"\$X\"", MeridianVoice.SWEEP,
        Meridian.defaults(MeridianVoice.SWEEP) + mapOf("LATITUDE" to .3f, "REACH" to .8f, "HOLD" to 1f),
        midi = 69, velocity = .35f, model = 1, seedContext = -37)

    @Test
    fun `factory and custom Meridian promotion lines reconstruct through the actual roster helper`() {
        for (patch in listOf(factory, custom())) {
            assertEquals(patch, MeridianPatch.fromJsonText(patch.toJsonText()))
            assertEquals("MeridianPresets.kt", UserPresets.rosterFile(patch))
            val line = UserPresets.rosterLine(patch)
            assertTrue(line.endsWith(","), line)
            val copy = assertIs<KotlinLiteral.Call>(KotlinLiteral.parse(line.removeSuffix(",")))
            assertEquals("copy", copy.name)
            assertTrue(copy.positional.isEmpty())
            assertEquals(setOf("midi", "velocity", "model", "seedContext"), copy.named.keys)
            val base = assertIs<KotlinLiteral.Call>(copy.receiver)
            assertEquals("p", base.name)
            assertNull(base.receiver)
            assertTrue(base.named.isEmpty())
            val voice = assertIs<KotlinLiteral.Ref>(base.positional[0])
            assertEquals("MeridianVoice", voice.owner)
            val name = assertIs<KotlinLiteral.Str>(base.positional[1]).value
            val macros = base.positional.drop(2).map { argument ->
                val pair = assertIs<KotlinLiteral.Pair>(argument)
                val key = assertIs<KotlinLiteral.Str>(pair.first).value
                val value = assertIs<KotlinLiteral.Num>(pair.second)
                assertEquals(KotlinLiteral.Num.Type.FLOAT, value.type, "a Double literal will not compile as a macro")
                key to value.text.removeSuffix("f").toFloat()
            }.toTypedArray()
            // Execute the actual private table helper. This catches a paste line
            // whose assumed helper signature or defaults no longer exist.
            val helper = MeridianPresets::class.java.getDeclaredMethod("p", MeridianVoice::class.java,
                String::class.java, macros.javaClass).apply { isAccessible = true }
            val assembled = assertIs<MeridianPatch>(helper.invoke(MeridianPresets,
                MeridianVoice.valueOf(voice.name), name, macros))
            fun integer(key: String): Int {
                val value = assertIs<KotlinLiteral.Num>(copy.named.getValue(key))
                assertEquals(KotlinLiteral.Num.Type.INT, value.type)
                return value.text.toInt()
            }
            val velocity = assertIs<KotlinLiteral.Num>(copy.named.getValue("velocity"))
            assertEquals(KotlinLiteral.Num.Type.FLOAT, velocity.type)
            val promoted = assembled.copy(midi = integer("midi"),
                velocity = velocity.text.removeSuffix("f").toFloat(), model = integer("model"),
                seedContext = integer("seedContext"))
            assertEquals(patch, promoted, "promotion lost its native note, velocity, model, seed or authored macros")
        }
    }

    @Test
    fun `the user shelf retains factory-derived and historical custom Meridian recipes`() {
        val root = createTempDirectory("meridian-user-presets").toFile()
        try {
            // Factory names are reserved; saving a player's named copy preserves
            // every performance field while giving it its own shelf identity.
            val copies = listOf(factory.copy(name = "MY FILAMENT"), custom())
            for ((index, patch) in copies.withIndex()) UserPresets.save(root, patch, index.toLong())
            val back = UserPresets.read(root)
            assertEquals(copies.toSet(), back.map { it.patch }.toSet())
            val pasted = UserPresets.renderAll(back)
            assertTrue(pasted.startsWith("MeridianPresets.kt\n"), pasted)
            for (patch in copies) assertTrue(UserPresets.rosterLine(patch) in pasted)
            val historical = assertIs<MeridianPatch>(back.single { it.name == custom().name }.patch)
            assertEquals(1, historical.model)
            assertEquals(69, historical.midi)
            assertEquals(.35f, historical.velocity)
            assertEquals(-37, historical.seedContext)
        } finally {
            root.deleteRecursively()
        }
    }
}
