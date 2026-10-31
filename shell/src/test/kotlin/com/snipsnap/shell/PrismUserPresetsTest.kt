package com.snipsnap.shell

import com.snipsnap.synth.Prism
import com.snipsnap.synth.PrismOpeningPresets
import com.snipsnap.synth.PrismPatch
import com.snipsnap.synth.PrismPresets
import com.snipsnap.synth.PrismVoice
import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrismUserPresetsTest {
    @Test
    fun `factory facet names remain protected after normalization`() {
        val patch = PrismPresets.forVoice(PrismVoice.KEY).first()
        assertEquals(UserPresets.Check.Factory(UserPresets.normalize(patch.name)),
            UserPresets.check("  ${patch.name.lowercase()}  ", patch.engine, patch.voiceName, emptyList()))
    }

    @Test
    fun `saved and restored user facets retain their complete additive recipe`() {
        val shelf = createTempDirectory("prism-presets").toFile()
        try {
            val patch = PrismPatch("MY FACETS", PrismVoice.KEY,
                Prism.defaults(PrismVoice.KEY) + mapOf("TUNE" to .25f, "GRAIN" to .7f), velocity = .4f,
                template = Prism.templateFor(PrismVoice.KEY).copy(attackSeconds = .02f, decaySeconds = 1.2f))
            val saved = UserPresets.save(shelf, patch, nowMillis = 12L)
            assertEquals(listOf(saved), UserPresets.read(shelf))
            assertEquals(patch, UserPresets.read(shelf).single().patch)
            val binned = requireNotNull(UserPresets.forget(shelf, patch.engine, patch.voiceName, patch.name, nowMillis = 13L))
            val restored = requireNotNull(UserPresets.unforget(shelf, binned))
            assertEquals(patch, restored.patch)
            assertContentEquals(patch.render().samples, restored.patch.render().samples)
            val line = UserPresets.rosterLine(patch)
            assertTrue(line.startsWith("p(PrismVoice.KEY, \"MY FACETS\", "))
            assertTrue("\"GRAIN\" to 0.7f" in line)
            assertTrue("velocity = 0.4f" in line, "promotion preserves native note energy")
            assertTrue("template = " in line, "promotion preserves independent partial contours")
            assertEquals("PrismPresets.kt", UserPresets.rosterFile(patch.engine))
        } finally {
            shelf.deleteRecursively()
        }
    }

    @Test
    fun `motion promotion reconstructs accepted openings and every authored travel field`() {
        val accepted = PrismOpeningPresets.all()
        val first = accepted.first()
        val originalMotion = assertNotNull(first.motion)
        val custom = first.copy(
            name = "MY \"BODY\" \$\\\n\t",
            macros = first.macros + ("TUNE" to Float.fromBits(0x3EAAAAAB)),
            velocity = Float.fromBits(0x3EAAAAAB),
            template = first.template.copy(attackSeconds = .031f, decaySeconds = 1.6f),
            motion = originalMotion.copy(
                morph = .17f, evolve = .73f, seconds = 1.13f, groupLag = .41f,
                amplitudesA = List(Prism.PARTIALS) { 1f / ((it + 1f) * (it + 1f)) },
                amplitudesB = originalMotion.amplitudesB.mapIndexed { i, value ->
                    if (i == 7) Float.fromBits(0x3EAAAAAB) else value
                },
            ),
        )
        val legacyMotion = first.copy(name = "Legacy motion", motion = originalMotion.copy(amplitudesA = null))
        for (patch in accepted + listOf(custom, legacyMotion)) {
            val promoted = fromMotionRosterLine(UserPresets.rosterLine(patch))
            assertEquals(patch, promoted, "${patch.name}: promotion must reconstruct the complete saved instrument")
            assertEquals(patch.toJsonText(), promoted.toJsonText(), "every implicit or authored field stays intact")
            assertContentEquals(patch.render().samples, promoted.render().samples,
                "${patch.name}: the promoted instrument must replay what was saved")
        }
        assertNull(fromMotionRosterLine(UserPresets.rosterLine(legacyMotion)).motion!!.amplitudesA,
            "legacy motion without an authored A must remain distinct from the body opening")
        val ordinary = first.copy(name = "Legacy facet", motion = null)
        assertTrue(UserPresets.rosterLine(ordinary).startsWith("p(PrismVoice.KEY, \"Legacy facet\", "))
        assertTrue(".copy(velocity = " in UserPresets.rosterLine(ordinary),
            "ordinary motion-free presets keep their established promotion form")
    }

    @Test
    fun `accepted motion survives user saving restart and collision renamed bin restore`() {
        val shelf = createTempDirectory("prism-opening-presets").toFile()
        try {
            for ((index, factory) in PrismOpeningPresets.all().withIndex()) {
                val patch = factory.copy(name = "MY OPEN ${index + 1}", velocity = .37f)
                val saved = UserPresets.save(shelf, patch, nowMillis = 12L)
                assertEquals(saved, UserPresets.read(shelf).single { it.name == patch.name })
                val binned = assertNotNull(UserPresets.forget(shelf, patch.engine, patch.voiceName, patch.name, nowMillis = 13L))
                val newer = patch.copy(motion = patch.motion!!.copy(seconds = 1.5f))
                UserPresets.save(shelf, newer, nowMillis = 14L)
                val restored = assertNotNull(UserPresets.unforget(shelf, binned))
                val expected = patch.copy(name = "${patch.name} 2")
                assertEquals(expected, restored.patch, "bin collision renaming retains both amplitude banks and the original travel law")
                assertEquals(expected, UserPresets.read(shelf).single { it.name == expected.name }.patch)
                assertEquals(newer, UserPresets.read(shelf).single { it.name == patch.name }.patch,
                    "restoring an earlier motion must not replace the newer saved movement")
                assertEquals(expected, fromMotionRosterLine(UserPresets.rosterLine(restored.patch)))
            }
        } finally {
            shelf.deleteRecursively()
        }
    }

    @Test
    fun `send to bench carries matching complete opening JSON and reconstructable roster code`() {
        val shelf = createTempDirectory("prism-opening-bench").toFile()
        val restoredShelf = createTempDirectory("prism-opening-bench-restore").toFile()
        try {
            val patch = PrismOpeningPresets.all().first().copy(name = "MY BODY")
            val saved = UserPresets.save(shelf, patch, nowMillis = 20L)
            val result = BenchExport.pack(shelf, File(shelf, "share"), "2026-10-23 1200")
            assertEquals(listOf(saved), result.presets)
            ZipFile(result.file).use { zip ->
                fun text(name: String) = zip.getInputStream(zip.getEntry(name)).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val presetFile = text(BenchExport.PRESETS_NAME)
                assertEquals(UserPresets.file(shelf).readText(Charsets.UTF_8), presetFile)
                assertEquals(listOf(saved), UserPresets.merge(restoredShelf, presetFile, nowMillis = 21L).landed)
                assertEquals(patch, UserPresets.read(restoredShelf).single().patch)
                val manifest = text(BenchExport.MANIFEST_NAME)
                val line = manifest.lineSequence().single { it.trimStart().startsWith("p(PrismVoice.") }.trim()
                assertEquals(patch, fromMotionRosterLine(line), "the pasteable bench entry must preserve the same opening as its JSON")
                assertTrue("PrismPresets.kt\n  $line\n" in manifest)
            }
        } finally {
            shelf.deleteRecursively()
            restoredShelf.deleteRecursively()
        }
    }

    /** Decode the emitted Kotlin arguments, then use the production constructor its let calls. */
    private fun fromMotionRosterLine(line: String): PrismPatch {
        val separator = ".let { "
        assertTrue(line.endsWith(" },"), line)
        assertEquals(2, line.split(separator).size, "one complete saved-patch constructor follows the table helper")
        val base = assertIs<KotlinLiteral.Call>(KotlinLiteral.parse(line.substringBefore(separator)))
        assertEquals("p", base.name)
        assertNull(base.receiver)
        assertTrue(base.named.isEmpty())
        val voice = assertIs<KotlinLiteral.Ref>(base.positional[0])
        assertEquals("PrismVoice", voice.owner)
        val name = assertIs<KotlinLiteral.Str>(base.positional[1]).value
        val macros = base.positional.drop(2).associate { value ->
            val pair = assertIs<KotlinLiteral.Pair>(value)
            val number = assertIs<KotlinLiteral.Num>(pair.second)
            assertEquals(KotlinLiteral.Num.Type.FLOAT, number.type)
            assertIs<KotlinLiteral.Str>(pair.first).value to number.text.dropLast(1).toFloat()
        }
        // Constructing the helper must succeed before Kotlin can invoke its let block.
        PrismPatch(name, PrismVoice.valueOf(voice.name), Prism.defaults(PrismVoice.valueOf(voice.name)) + macros)
        val call = assertIs<KotlinLiteral.Call>(KotlinLiteral.parse(line.substringAfter(separator).removeSuffix(" },")))
        assertEquals("fromJsonText", call.name)
        val receiver = assertIs<KotlinLiteral.Call>(call.receiver)
        assertEquals("PrismPatch", receiver.name)
        assertNull(receiver.receiver)
        assertTrue(receiver.positional.isEmpty() && receiver.named.isEmpty())
        assertTrue(call.named.isEmpty())
        val patch = PrismPatch.fromJsonText(assertIs<KotlinLiteral.Str>(call.positional.single()).value)
        assertEquals(name, patch.name)
        assertEquals(voice.name, patch.voiceName)
        assertEquals(macros.mapValues { it.value.toRawBits() }, patch.macros.mapValues { it.value.toRawBits() },
            "the visible table arguments and complete saved recipe agree exactly")
        return patch
    }
}
