package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.kit.KitBackup
import com.snipsnap.synth.Onset
import com.snipsnap.synth.OnsetPatch
import com.snipsnap.synth.OnsetPresets
import com.snipsnap.synth.OnsetVoice
import com.snipsnap.synth.Patches
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OnsetUserPresetsTest {
    @Test
    fun `factory names stay reserved and a saved earlier model survives backup merge and promotion`() {
        val shelf = kotlin.io.path.createTempDirectory("onset-presets").toFile()
        val restored = kotlin.io.path.createTempDirectory("onset-restored").toFile()
        try {
            for (factory in OnsetPresets.all()) {
                assertEquals(
                    UserPresets.Check.Factory(UserPresets.normalize(factory.name)),
                    UserPresets.check(" ${factory.name.lowercase()} ", factory.engine, factory.voiceName, emptyList()),
                )
            }
            val original = OnsetPatch("MY MEMBRANE", OnsetVoice.KICK,
                Onset.defaults(OnsetVoice.KICK) + ("BLEND" to .75f), modelVersion = 2)
            UserPresets.save(shelf, original, 12L)
            val loaded = UserPresets.read(shelf).single().patch as OnsetPatch
            assertEquals(original, loaded)
            val edited = Patches.edited(loaded, loaded.name, loaded.macros + ("DECAY" to .7f)) as OnsetPatch
            assertEquals(2, edited.modelVersion, "a slider edit keeps the saved renderer")
            UserPresets.save(shelf, edited, 13L)
            val merged = UserPresets.merge(restored, UserPresets.file(shelf).readText(), 14L)
            assertEquals(listOf(edited), merged.landed.map { it.patch })
            assertEquals(edited, UserPresets.read(restored).single().patch)
            assertEquals(1, UserPresets.merge(restored, UserPresets.file(shelf).readText(), 15L).identical)
            val line = UserPresets.rosterLine(edited)
            assertTrue(line.startsWith("OnsetPatch(\"MY MEMBRANE\", OnsetVoice.KICK, mapOf("), line)
            assertTrue("modelVersion = 2" in line, "promotion must not adopt the current renderer: $line")
            assertEquals("OnsetPresets.kt", UserPresets.rosterFile(edited.engine))
        } finally {
            shelf.deleteRecursively()
            restored.deleteRecursively()
        }
    }

    @Test
    fun `native study user presets return through shelf backup extras and promote as their saved model`() {
        val root = kotlin.io.path.createTempDirectory("onset-native-keeps").toFile()
        try {
            val source = File(root, "source")
            val destination = File(root, "destination")
            val kit = KitBuilderModel.create("BACKUP KEEPS", File(source, "BACKUP KEEPS"))
            kit.assign(1, DrumSynth.kick(), DrumClass.KICK)
            kit.save()
            val patches = OnsetPresets.extensionStudy().mapIndexed { index, patch ->
                patch.copy(name = "MY STUDY ${index + 1}")
            }
            assertEquals(7, patches.size)
            val saved = patches.mapIndexed { index, patch -> UserPresets.save(source, patch, index.toLong()) }
            val archive = File(root, "new-phone.zip")
            val backup = KitBackup.backup(source, archive,
                extras = mapOf(UserPresets.FILE_NAME to UserPresets.file(source)))
            assertTrue(backup.skipped.isEmpty(), backup.skipped.toString())

            // The ordinary kit is rendered audio; complete instrument patches
            // travel in the backup's explicit user-preset extra.
            val landed = ShelfImport.land(archive, archive.name, destination)
            assertTrue(landed.skipped.isEmpty(), landed.skipped.toString())
            assertEquals(7, landed.presets)
            assertEquals(saved, UserPresets.read(destination))
            for (entry in UserPresets.read(destination)) {
                val patch = assertIs<OnsetPatch>(entry.patch)
                assertEquals(4, patch.modelVersion)
                assertEquals(6, patch.macros.size)
                val line = UserPresets.rosterLine(patch)
                assertEquals(patch, promoted(line), "the promoted constructor preserves ${patch.voice}")
                assertEquals("OnsetPresets.kt", UserPresets.rosterFile(patch.engine))
            }
            assertEquals(0, ShelfImport.land(archive, archive.name, destination).presets,
                "the same backup does not create another copy of a kept patch")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a current core model and a saved model three remain distinct in a backup collision`() {
        val source = kotlin.io.path.createTempDirectory("onset-old-model").toFile()
        val destination = kotlin.io.path.createTempDirectory("onset-current-model").toFile()
        try {
            val old = OnsetPatch("MY KICK", OnsetVoice.KICK,
                Onset.defaults(OnsetVoice.KICK) + ("BLEND" to .37f), modelVersion = 3)
            val current = old.copy(modelVersion = 4)
            UserPresets.save(source, old, 1L)
            UserPresets.save(destination, current, 2L)
            val incoming = UserPresets.file(source).readText()
            val merged = UserPresets.merge(destination, incoming, 3L)
            assertEquals(0, merged.identical, "a saved renderer identity is part of the recipe")
            assertEquals(listOf(old.copy(name = "MY KICK 2")), merged.landed.map { it.patch })
            assertEquals(listOf(current, old.copy(name = "MY KICK 2")),
                UserPresets.read(destination).map { it.patch })
            assertEquals(1, UserPresets.merge(destination, incoming, 4L).identical)
        } finally {
            source.deleteRecursively()
            destination.deleteRecursively()
        }
    }

    /** Read the literal constructor as a paste into OnsetPresets would. */
    private fun promoted(line: String): OnsetPatch {
        assertTrue(line.endsWith(","), line)
        val call = assertIs<KotlinLiteral.Call>(KotlinLiteral.parse(line.dropLast(1)))
        assertEquals("OnsetPatch", call.name)
        assertEquals(3, call.positional.size)
        assertEquals(setOf("modelVersion"), call.named.keys)
        val name = assertIs<KotlinLiteral.Str>(call.positional[0]).value
        val voice = assertIs<KotlinLiteral.Ref>(call.positional[1])
        assertEquals("OnsetVoice", voice.owner)
        val map = assertIs<KotlinLiteral.Call>(call.positional[2])
        assertEquals("mapOf", map.name)
        val macros = map.positional.associate { node ->
            val pair = assertIs<KotlinLiteral.Pair>(node)
            val number = assertIs<KotlinLiteral.Num>(pair.second)
            assertEquals(KotlinLiteral.Num.Type.FLOAT, number.type)
            assertIs<KotlinLiteral.Str>(pair.first).value to number.text.removeSuffix("f").toFloat()
        }
        val version = assertIs<KotlinLiteral.Num>(call.named.getValue("modelVersion"))
        assertEquals(KotlinLiteral.Num.Type.INT, version.type)
        return OnsetPatch(name, OnsetVoice.valueOf(voice.name), macros, version.text.toInt())
    }
}
