package com.snipsnap.shell

import com.snipsnap.synth.CreasePatch
import com.snipsnap.synth.CreaseVoice
import com.snipsnap.synth.Patches
import com.snipsnap.synth.Presets
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CreaseUserPresetsTest {
    @Test
    fun `saved Crease JSON keeps every voice model note velocity and partial macro map`() {
        val shelf = createTempDirectory("crease-presets").toFile()
        try {
            val patches = (1..3).flatMap { model ->
                CreaseVoice.entries.mapIndexed { index, voice ->
                    CreasePatch("MY ${voice.name} $model", voice,
                        linkedMapOf("FOLD" to .12345679f, "HOLD" to 0f),
                        midi = 36 + index * 12, velocity = .43f, model = model)
                }
            }
            val saved = patches.mapIndexed { index, patch ->
                UserPresets.save(shelf, patch, nowMillis = index.toLong())
            }
            assertEquals(saved, UserPresets.read(shelf))
            for (patch in patches) {
                val json = patch.toJsonText()
                assertEquals(patch, Patches.fromJsonText(json))
                val fields = patch.toJsonValue().entries
                assertEquals(patch.model, fields.getValue("model").int())
                assertEquals(patch.midi, fields.getValue("midi").int())
                assertEquals(patch.velocity.toDouble(), fields.getValue("velocity").num())
                assertEquals(patch.macros.keys, fields.getValue("macros").obj().keys,
                    "omitted controls retain their saved model's defaults")
            }
            for (voice in CreaseVoice.entries) {
                val factory = Presets.forVoice(CreasePatch.ENGINE, voice.name).first()
                assertEquals(UserPresets.Check.Factory(UserPresets.normalize(factory.name)),
                    UserPresets.check(" ${factory.name.lowercase()} ", factory.engine, factory.voiceName, emptyList()))
                val suggestion = UserPresets.suggest(CreasePatch.ENGINE, voice.name, saved)
                assertTrue(suggestion.length + 2 <= UserPresets.MAX_NAME)
            }
        } finally { shelf.deleteRecursively() }
    }

    @Test
    fun `bin restore and backup collisions retain the Crease render model`() {
        val shelf = createTempDirectory("crease-bin").toFile()
        val destination = createTempDirectory("crease-backup").toFile()
        try {
            val legacy = CreasePatch("MY CREASE", CreaseVoice.RUBBER,
                mapOf("FOLD" to .31f, "PRESSURE" to .62f), midi = 57, velocity = .37f, model = 1)
            UserPresets.save(shelf, legacy, nowMillis = 10L)
            val incoming = UserPresets.file(shelf).readText()
            val binned = requireNotNull(UserPresets.forget(shelf, legacy.engine, legacy.voiceName,
                legacy.name, nowMillis = 11L))
            val current = legacy.copy(model = 3)
            UserPresets.save(shelf, current, nowMillis = 12L)
            val restored = requireNotNull(UserPresets.unforget(shelf, binned))
            assertEquals(legacy.copy(name = "MY CREASE 2"), restored.patch)

            UserPresets.save(destination, current, nowMillis = 13L)
            val merged = UserPresets.merge(destination, incoming, nowMillis = 14L)
            assertEquals(0, merged.identical, "a render-model difference is part of the saved sound")
            assertEquals(listOf(restored), merged.landed)
            assertEquals(listOf(current, restored.patch), UserPresets.read(destination).map { it.patch })
            assertEquals(1, UserPresets.merge(destination, incoming, nowMillis = 15L).identical)
        } finally {
            shelf.deleteRecursively()
            destination.deleteRecursively()
        }
    }

    @Test
    fun `promoted Crease Kotlin lines reconstruct exact recipes and render the saved sound`() {
        for (model in 1..3) for (voice in CreaseVoice.entries) {
            val patch = CreasePatch("MY \"\$CREASE\"", voice,
                linkedMapOf("PRESSURE" to .33333334f, "RELAX" to .05f),
                midi = 65, velocity = .47f, model = model)
            val line = UserPresets.rosterLine(patch)
            assertEquals("CreasePresets.kt", UserPresets.rosterFile(patch.engine))
            assertTrue(line.startsWith("p(CreaseVoice.${voice.name}, "), line)
            assertTrue(line.endsWith(".copy(midi = 65, velocity = 0.47f, model = $model),"), line)
            val rebuilt = fromRosterLine(line)
            assertEquals(patch, rebuilt)
            assertEquals("CreasePresets.kt\n  $line\n",
                UserPresets.renderAll(listOf(UserPresets.Saved(patch, 1L))))
            if (model == 3 && voice == CreaseVoice.RUBBER) {
                assertContentEquals(patch.render().samples, rebuilt.render().samples)
            }
        }
    }

    /** Read the expression as CreasePresets' p helper and its subsequent copy would. */
    private fun fromRosterLine(line: String): CreasePatch {
        assertTrue(line.endsWith(","), line)
        val copy = KotlinLiteral.parse(line.dropLast(1)) as KotlinLiteral.Call
        assertEquals("copy", copy.name)
        assertTrue(copy.positional.isEmpty())
        assertEquals(setOf("midi", "velocity", "model"), copy.named.keys)
        val p = copy.receiver as KotlinLiteral.Call
        assertEquals("p", p.name)
        assertTrue(p.receiver == null && p.named.isEmpty())
        val voice = p.positional[0] as KotlinLiteral.Ref
        assertEquals("CreaseVoice", voice.owner)
        val macros = p.positional.drop(2).associate { node ->
            val pair = node as KotlinLiteral.Pair
            (pair.first as KotlinLiteral.Str).value to float(pair.second)
        }
        return CreasePatch((p.positional[1] as KotlinLiteral.Str).value,
            CreaseVoice.valueOf(voice.name), macros,
            midi = int(copy.named.getValue("midi")), velocity = float(copy.named.getValue("velocity")),
            model = int(copy.named.getValue("model")))
    }

    private fun float(node: KotlinLiteral.Node): Float {
        val literal = node as KotlinLiteral.Num
        assertEquals(KotlinLiteral.Num.Type.FLOAT, literal.type)
        return literal.text.removeSuffix("f").removeSuffix("F").toFloat()
    }

    private fun int(node: KotlinLiteral.Node): Int {
        val literal = node as KotlinLiteral.Num
        assertEquals(KotlinLiteral.Num.Type.INT, literal.type)
        return literal.text.toInt()
    }
}
