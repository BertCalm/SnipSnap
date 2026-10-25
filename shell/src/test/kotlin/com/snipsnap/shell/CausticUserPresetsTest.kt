package com.snipsnap.shell

import com.snipsnap.synth.CausticPatch
import com.snipsnap.synth.CausticVoice
import com.snipsnap.synth.Patches
import com.snipsnap.synth.Presets
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CausticUserPresetsTest {
    private fun sound(name: String = "MY ALLOY") = CausticPatch(name, CausticVoice.ALLOY,
        linkedMapOf("BODY" to .33333334f, "LENGTH" to .27f), midi = 57, velocity = .43f, model = 1)

    @Test
    fun `native Caustic shelf and JSON retain sparse macros root strike and model`() {
        val shelf = createTempDirectory("caustic-presets").toFile()
        try {
            val patch = sound()
            val saved = UserPresets.save(shelf, patch, nowMillis = 10L)
            assertEquals(listOf(saved), UserPresets.read(shelf))
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            val fields = patch.toJsonValue().entries
            assertEquals(1, fields.getValue("model").int())
            assertEquals(57, fields.getValue("midi").int())
            assertEquals(.43f.toDouble(), fields.getValue("velocity").num())
            assertEquals(setOf("BODY", "LENGTH"), fields.getValue("macros").obj().keys,
                "omitted macros must continue to use their saved native model defaults")
            val factory = Presets.forVoice(CausticPatch.ENGINE, CausticVoice.ALLOY.name).first()
            assertEquals(UserPresets.Check.Factory(UserPresets.normalize(factory.name)),
                UserPresets.check(" ${factory.name.lowercase()} ", factory.engine, factory.voiceName, emptyList()))
            assertTrue(UserPresets.suggest(patch.engine, patch.voiceName, listOf(saved)).length + 2 <= UserPresets.MAX_NAME)
        } finally { shelf.deleteRecursively() }
    }

    @Test
    fun `bin restore and backup collisions preserve the complete Caustic sound`() {
        val shelf = createTempDirectory("caustic-bin").toFile()
        val destination = createTempDirectory("caustic-backup").toFile()
        try {
            val original = sound()
            UserPresets.save(shelf, original, nowMillis = 10L)
            val incoming = UserPresets.file(shelf).readText()
            val binned = requireNotNull(UserPresets.forget(shelf, original.engine, original.voiceName,
                original.name, nowMillis = 11L))
            val replacement = original.copy(midi = 69, velocity = .71f)
            UserPresets.save(shelf, replacement, nowMillis = 12L)
            val restored = requireNotNull(UserPresets.unforget(shelf, binned))
            assertEquals(original.copy(name = "MY ALLOY 2"), restored.patch)

            UserPresets.save(destination, replacement, nowMillis = 13L)
            val merged = UserPresets.merge(destination, incoming, nowMillis = 14L)
            assertEquals(0, merged.identical, "a different native note or strike is a different saved sound")
            assertEquals(listOf(restored.patch), merged.landed.map { it.patch })
            assertEquals(listOf(replacement, restored.patch), UserPresets.read(destination).map { it.patch })
            assertEquals(1, UserPresets.merge(destination, incoming, nowMillis = 15L).identical)
        } finally { shelf.deleteRecursively(); destination.deleteRecursively() }
    }

    @Test
    fun `promoted Caustic roster expressions reconstruct the saved model note and strike`() {
        val patch = sound("MY \"\$ALLOY\"")
        val line = UserPresets.rosterLine(patch)
        assertEquals("CausticPresets.kt", UserPresets.rosterFile(patch.engine))
        assertTrue(line.startsWith("p(CausticVoice.ALLOY, "), line)
        assertTrue(line.endsWith(".copy(midi = 57, velocity = 0.43f, model = 1),"), line)
        val copy = KotlinLiteral.parse(line.dropLast(1)) as KotlinLiteral.Call
        assertEquals("copy", copy.name)
        val p = copy.receiver as KotlinLiteral.Call
        assertEquals("p", p.name)
        val voice = p.positional[0] as KotlinLiteral.Ref
        assertEquals("CausticVoice", voice.owner)
        val macros = p.positional.drop(2).associate {
            val pair = it as KotlinLiteral.Pair
            (pair.first as KotlinLiteral.Str).value to float(pair.second)
        }
        val rebuilt = CausticPatch((p.positional[1] as KotlinLiteral.Str).value,
            CausticVoice.valueOf(voice.name), macros,
            midi = (copy.named.getValue("midi") as KotlinLiteral.Num).text.toInt(),
            velocity = float(copy.named.getValue("velocity")),
            model = (copy.named.getValue("model") as KotlinLiteral.Num).text.toInt())
        assertEquals(patch, rebuilt)
        assertEquals("CausticPresets.kt\n  $line\n",
            UserPresets.renderAll(listOf(UserPresets.Saved(patch, 1L))))
    }

    private fun float(node: KotlinLiteral.Node): Float = (node as KotlinLiteral.Num).text
        .removeSuffix("f").removeSuffix("F").toFloat()
}
