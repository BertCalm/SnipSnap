package com.snipsnap.shell

import com.snipsnap.synth.Oven
import com.snipsnap.synth.OvenPatch
import com.snipsnap.synth.OvenPresets
import com.snipsnap.synth.OvenVoice
import java.io.File
import java.lang.reflect.Modifier
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OvenUserPresetsTest {
    private fun custom(voice: OvenVoice = OvenVoice.FELT) = OvenPatch(
        "MY OVEN", voice,
        linkedMapOf(
            "TUNE" to Float.fromBits(0x3EAAAAAB), "HAMMER" to .73f, "BODY" to .31f,
            "RING" to .18f, "BLOOM" to .07f, "HALO" to .24f,
        ),
        midi = 47, velocity = .43f, model = Oven.MODEL_VERSION,
    )

    @Test
    fun `every factory and custom roster line rebuilds the exact complete recipe`() {
        val custom = custom()
        val recipes = OvenPresets.all() + listOf(custom, custom.copy(name = "MY \"IRON\" \$\\", macros = emptyMap()))
        for (patch in recipes) {
            val line = UserPresets.rosterLine(patch)
            val promoted = fromRosterLine(line)
            assertEquals(patch, promoted, "${patch.name}: promotion keeps the complete saved strike")
            assertEquals(patch.macros.mapValues { it.value.toRawBits() }, promoted.macros.mapValues { it.value.toRawBits() },
                "${patch.name}: no macro is rounded by the Kotlin literals")
            assertEquals(patch.velocity.toRawBits(), promoted.velocity.toRawBits())
        }
        assertEquals("OvenPresets.kt", UserPresets.rosterFile(custom.engine))
        assertContentEquals(custom.render().samples, fromRosterLine(UserPresets.rosterLine(custom)).render().samples,
            "the promoted recipe recreates the strike the phone previewed")
    }

    @Test
    fun `saved notes and touch survive restart and a collision renamed bin restore on every voice`() {
        val shelf = createTempDirectory("oven-presets").toFile()
        try {
            for ((index, voice) in OvenVoice.entries.withIndex()) {
                val patch = custom(voice).copy(midi = 43 + index * 7, velocity = .23f + index * .1f)
                val factory = OvenPresets.forVoice(voice).first()
                assertEquals(UserPresets.Check.Factory(UserPresets.normalize(factory.name)),
                    UserPresets.check("  ${factory.name.lowercase()}  ", patch.engine, patch.voiceName, emptyList()))
                val saved = UserPresets.save(shelf, patch, nowMillis = 12L)
                assertEquals(listOf(saved), UserPresets.forVoice(UserPresets.read(shelf), patch.engine, patch.voiceName))
                val binned = requireNotNull(UserPresets.forget(shelf, patch.engine, patch.voiceName, patch.name, nowMillis = 13L))
                val newer = UserPresets.save(shelf, patch.copy(midi = 72, velocity = .9f), nowMillis = 14L)
                val restored = requireNotNull(UserPresets.unforget(shelf, binned))
                val renamed = patch.copy(name = "MY OVEN 2")
                assertEquals(renamed, restored.patch, "$voice: renaming preserves every macro, note, velocity and model")
                assertEquals(listOf(newer, restored), UserPresets.forVoice(UserPresets.read(shelf), patch.engine, patch.voiceName))
                assertEquals(renamed, fromRosterLine(UserPresets.rosterLine(restored.patch)))
            }
        } finally {
            shelf.deleteRecursively()
        }
    }

    @Test
    fun `send to bench carries the saved JSON and a pasteable constructor with identical playback`() {
        val shelf = createTempDirectory("oven-bench").toFile()
        val restoredShelf = createTempDirectory("oven-bench-restore").toFile()
        try {
            val patch = custom(OvenVoice.FORGED)
            val saved = UserPresets.save(shelf, patch, nowMillis = 20L)
            val result = BenchExport.pack(shelf, File(shelf, "share"), "2026-10-18 1200")
            assertEquals(listOf(saved), result.presets)
            ZipFile(result.file).use { zip ->
                fun text(name: String) = zip.getInputStream(zip.getEntry(name)).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val sent = text(BenchExport.PRESETS_NAME)
                assertEquals(UserPresets.file(shelf).readText(Charsets.UTF_8), sent, "the bench carries the shelf file byte for byte")
                assertEquals(listOf(saved), UserPresets.merge(restoredShelf, sent, nowMillis = 21L).landed)
                assertEquals(patch, UserPresets.read(restoredShelf).single().patch)
                val line = text(BenchExport.MANIFEST_NAME).lineSequence().single { it.trimStart().startsWith("OvenPatch(") }.trim()
                val promoted = fromRosterLine(line)
                assertEquals(patch, promoted)
                assertTrue("OvenPresets.kt\n  $line\n" in text(BenchExport.MANIFEST_NAME))
                assertContentEquals(patch.render().samples, promoted.render().samples)
            }
        } finally {
            shelf.deleteRecursively()
            restoredShelf.deleteRecursively()
        }
    }

    /** Read the emitted Kotlin, including literal types and every constructor field. */
    private fun fromRosterLine(line: String): OvenPatch {
        assertTrue(line.endsWith(","), line)
        val call = assertIs<KotlinLiteral.Call>(KotlinLiteral.parse(line.dropLast(1)))
        assertEquals("OvenPatch", call.name, "OVEN's p helper takes MIDI and velocity before macro pairs")
        assertNull(call.receiver)
        assertEquals(3, call.positional.size)
        val name = assertIs<KotlinLiteral.Str>(call.positional[0]).value
        val voice = assertIs<KotlinLiteral.Ref>(call.positional[1])
        assertEquals("OvenVoice", voice.owner)
        val map = assertIs<KotlinLiteral.Call>(call.positional[2])
        assertEquals("mapOf", map.name)
        assertNull(map.receiver)
        assertTrue(map.named.isEmpty())
        val macros = map.positional.associate { node ->
            val pair = assertIs<KotlinLiteral.Pair>(node)
            assertIs<KotlinLiteral.Str>(pair.first).value to float(pair.second)
        }
        val fields = OvenPatch::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }.map { it.name }.toSet()
        assertEquals(fields - setOf("name", "voice", "macros"), call.named.keys,
            "the promoted constructor must carry every field outside the macros")
        return OvenPatch(name, OvenVoice.valueOf(voice.name), macros,
            midi = int(call.named.getValue("midi")), velocity = float(call.named.getValue("velocity")),
            model = int(call.named.getValue("model")))
    }

    private fun float(node: KotlinLiteral.Node): Float {
        val literal = assertIs<KotlinLiteral.Num>(node)
        assertEquals(KotlinLiteral.Num.Type.FLOAT, literal.type)
        return literal.text.dropLast(1).toFloat()
    }

    private fun int(node: KotlinLiteral.Node): Int {
        val literal = assertIs<KotlinLiteral.Num>(node)
        assertEquals(KotlinLiteral.Num.Type.INT, literal.type)
        return literal.text.toInt()
    }
}
