package com.snipsnap.shell

import com.snipsnap.json.JsonException
import com.snipsnap.synth.FathomVoice
import com.snipsnap.synth.PluckVoice
import com.snipsnap.synth.Presets
import com.snipsnap.synth.SkinVoice
import com.snipsnap.synth.Thump
import com.snipsnap.synth.ThumpPatch
import com.snipsnap.synth.ThumpVoice
import com.snipsnap.synth.TinesVoice
import com.snipsnap.synth.TonewheelVoice
import com.snipsnap.synth.VelvetVoice
import com.snipsnap.synth.VoxVoice
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SAVE AS PRESET (`docs/WORKSHOP.md`, WS5): the player's own presets beside the kits, and the line that promotes one. */
class UserPresetsTest {

    private fun shelf(): File = java.nio.file.Files.createTempDirectory("presets").toFile()

    /** A kick the player wrecked: SCRAMBLE's own odd floats, nothing a table would round. */
    private val wrecked = ThumpPatch("MY KICK", ThumpVoice.KICK, Thump.defaults(ThumpVoice.KICK) + Thump.scramble(ThumpVoice.KICK, Random(7)))
    private val snare = ThumpPatch("MY KICK", ThumpVoice.SNARE, Thump.defaults(ThumpVoice.SNARE))

    @Test
    fun `a saved preset survives a restart and re-renders the same bytes`() {
        val root = shelf()
        try {
            assertEquals(emptyList(), UserPresets.read(root), "no file, no presets")
            val saved = UserPresets.save(root, wrecked, nowMillis = 1_700_000_000_000L)
            assertEquals(wrecked, saved.patch)
            // A fresh read is the restart: nothing but the file.
            val back = UserPresets.read(root)
            assertEquals(listOf(saved), back)
            assertEquals(wrecked, back.single().patch, "every macro's exact value, the name and the voice")
            assertTrue(wrecked.render().samples.contentEquals(back.single().patch.render().samples), "the same bytes the phone heard")
            assertEquals(File(root, "presets.json"), UserPresets.file(root), "one file at the shelf root, beside the kit folders")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the name rules - blank, too long, the factory's, fresh, or one of yours`() {
        val mine = listOf(UserPresets.Saved(wrecked, 1L))
        assertEquals(UserPresets.Check.Blank, UserPresets.check("  \n ", "THUMP", "KICK", mine))
        assertEquals(UserPresets.Check.TooLong("FIFTEEN LETTERSX"), UserPresets.check("fifteen lettersx", "THUMP", "KICK", mine))
        assertEquals(UserPresets.Check.Factory("DUSTY BOOM"), UserPresets.check(" dusty   boom ", "THUMP", "KICK", mine), "a factory name is refused, whatever the case or spacing")
        assertEquals(UserPresets.Check.Fresh("DUSTY BOOM"), UserPresets.check("dusty boom", "THUMP", "SNARE", mine), "the factory's names are per voice")
        assertEquals(UserPresets.Check.Replaces("MY KICK"), UserPresets.check("my kick", "THUMP", "KICK", mine))
        assertEquals(UserPresets.Check.Fresh("MY KICK"), UserPresets.check("my kick", "THUMP", "SNARE", mine), "yours are per voice too")
        assertEquals("HAT CLOSED 2", UserPresets.normalize("  hat\tclosed   2 "))
    }

    @Test
    fun `saving under one of your names replaces it in place, and never touches another voice's namesake`() {
        val root = shelf()
        try {
            UserPresets.save(root, wrecked, 1L)
            UserPresets.save(root, snare, 2L)
            val rewrecked = ThumpPatch("MY KICK", ThumpVoice.KICK, Thump.defaults(ThumpVoice.KICK) + Thump.scramble(ThumpVoice.KICK, Random(8)))
            val replaced = UserPresets.save(root, rewrecked, 3L)
            val all = UserPresets.read(root)
            assertEquals(listOf(replaced, UserPresets.Saved(snare, 2L)), all, "the kick keeps its place in the list; the snare is untouched")
            assertEquals(rewrecked, all.first().patch)
            assertEquals(listOf(replaced), UserPresets.forVoice(all, "THUMP", "KICK"))
            assertEquals(listOf(UserPresets.Saved(snare, 2L)), UserPresets.forVoice(all, "THUMP", "SNARE"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `save refuses a name check would refuse`() {
        val root = shelf()
        try {
            assertTrue(runCatching { UserPresets.save(root, ThumpPatch("dusty boom", ThumpVoice.KICK, emptyMap()), 1L) }.isFailure, "not normalized")
            assertTrue(runCatching { UserPresets.save(root, ThumpPatch("DUSTY BOOM", ThumpVoice.KICK, emptyMap()), 1L) }.isFailure, "the factory's")
            assertTrue(runCatching { UserPresets.save(root, ThumpPatch("FIFTEEN LETTERSX", ThumpVoice.KICK, emptyMap()), 1L) }.isFailure, "too long")
            assertFalse(UserPresets.file(root).exists(), "a refusal writes nothing")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the suggested name is the voice numbered from 1, skipping what is taken, and always fits`() {
        val root = shelf()
        try {
            assertEquals("KICK 1", UserPresets.suggest("THUMP", "KICK", emptyList()))
            assertEquals("HAT CLOSED 1", UserPresets.suggest("THUMP", "HAT_CLOSED", emptyList()))
            val one = UserPresets.save(root, ThumpPatch("KICK 1", ThumpVoice.KICK, Thump.defaults(ThumpVoice.KICK)), 1L)
            assertEquals("KICK 2", UserPresets.suggest("THUMP", "KICK", listOf(one)))
            assertEquals("SNARE 1", UserPresets.suggest("THUMP", "SNARE", listOf(one)), "numbered per voice")
            // Every voice of every engine, at a number a phone will never reach.
            val voices = listOf(
                "THUMP" to ThumpVoice.entries.map { it.name }, "SKIN" to SkinVoice.entries.map { it.name },
                "TINES" to TinesVoice.entries.map { it.name }, "VELVET" to VelvetVoice.entries.map { it.name },
                "VOX" to VoxVoice.entries.map { it.name }, "PLUCK" to PluckVoice.entries.map { it.name },
                "TONEWHEEL" to TonewheelVoice.entries.map { it.name }, "FATHOM" to FathomVoice.entries.map { it.name },
            )
            for ((engine, names) in voices) {
                for (v in names) {
                    val suggestion = UserPresets.suggest(engine, v, emptyList())
                    assertTrue(suggestion.length + 2 <= UserPresets.MAX_NAME, "$engine $v: '$suggestion' leaves no room for a two-digit number")
                    assertEquals(UserPresets.Check.Fresh(suggestion), UserPresets.check(suggestion, engine, v, emptyList()))
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a file this build cannot read is never written over, and an entry it cannot read rides through a save`() {
        val root = shelf()
        try {
            val file = UserPresets.file(root)
            file.writeText("{\"version\": 1, \"presets\": [{\"savedAt\": 5, \"patch\": {\"engine\": \"FUTURE\", \"version\": 1, \"name\": \"X\", \"voice\": \"Y\", \"macros\": {}}}]}")
            assertEquals(emptyList(), UserPresets.read(root), "an engine this build does not know is skipped, not fatal")
            UserPresets.save(root, wrecked, 9L)
            assertEquals(listOf(UserPresets.Saved(wrecked, 9L)), UserPresets.read(root))
            assertTrue("\"FUTURE\"" in file.readText(), "the unread entry is still in the file for the build that can read it")
            file.writeText("not json at all")
            assertTrue(runCatching { UserPresets.read(root) }.exceptionOrNull() is JsonException, "a file that is not this store's throws")
            assertTrue(runCatching { UserPresets.save(root, wrecked, 10L) }.isFailure, "and a save refuses rather than writing over it")
            assertEquals("not json at all", file.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a roster line pastes into the engine's own table with every macro's exact value`() {
        val line = UserPresets.rosterLine(ThumpPatch("MY KICK", ThumpVoice.KICK, linkedMapOf("TUNE" to 0.34f, "SWEEP" to 0.45f)))
        assertEquals("p(ThumpVoice.KICK, \"MY KICK\", \"TUNE\" to 0.34f, \"SWEEP\" to 0.45f),", line)
        // Every factory preset, back out of its own line to the float it holds.
        val value = Regex("\"[^\"]+\" to ([-0-9.E]+)f")
        for (preset in Presets.all()) {
            val parsed = value.findAll(UserPresets.rosterLine(preset)).map { it.groupValues[1].toFloat() }.toList()
            assertEquals(preset.macros.values.toList(), parsed, "${preset.engine}/${preset.name}: the line rounds a value")
        }
        val quoted = UserPresets.rosterLine(ThumpPatch("SAY \"HI\" \$X", ThumpVoice.KICK, emptyMap()))
        assertTrue("\"SAY \\\"HI\\\" \\\$X\"" in quoted, quoted)
        val two = listOf(UserPresets.Saved(wrecked, 1L), UserPresets.Saved(snare, 2L))
        val rendered = UserPresets.renderAll(two)
        assertTrue(rendered.startsWith("ThumpPresets.kt\n  p(ThumpVoice.KICK, \"MY KICK\", "), rendered)
        assertTrue("\n  p(ThumpVoice.SNARE, \"MY KICK\", " in rendered, rendered)
    }

    @Test
    fun `every roster line names a table and a helper that exist`() {
        val engines = Presets.all().map { it.engine }.distinct()
        assertTrue(engines.size >= 8, "every registered engine ships a roster: $engines")
        for (engine in engines) {
            val table = File("../" + UserPresets.ROSTER_DIR + UserPresets.rosterFile(engine))
            assertTrue(table.isFile, "the table a $engine preset pastes into: $table")
            val helper = "private fun p(voice: ${UserPresets.voiceEnum(engine)}, name: String, vararg macros: Pair<String, Float>)"
            assertTrue(helper in table.readText(Charsets.UTF_8), "${table.name} is written with the helper the line uses: $helper")
            val enum = Class.forName("com.snipsnap.synth." + UserPresets.voiceEnum(engine))
            assertTrue(enum.isEnum, "${UserPresets.voiceEnum(engine)} is the voice enum")
        }
    }

    @Test
    fun `forget moves a preset to the bin, restore brings it back, and a save in between carries the bin`() {
        val root = shelf()
        try {
            UserPresets.save(root, wrecked, 1L)
            UserPresets.save(root, snare, 2L)
            assertEquals(null, UserPresets.forget(root, "THUMP", "KICK", "NOT MINE", 5L), "nothing by that name is there to forget")
            val binned = UserPresets.forget(root, "THUMP", "KICK", "MY KICK", 5L)
            assertEquals(UserPresets.Binned(UserPresets.Saved(wrecked, 1L), 5L), binned)
            assertEquals(listOf(UserPresets.Saved(snare, 2L)), UserPresets.read(root), "off the strip")
            assertEquals(listOf(binned), UserPresets.bin(root), "and in the bin")
            assertEquals(null, UserPresets.forget(root, "THUMP", "KICK", "MY KICK", 6L), "forgotten once is forgotten")
            // A save while it sleeps must not lose it: the bin rides every write.
            val clap = ThumpPatch("CLAP 1", ThumpVoice.CLAP, Thump.defaults(ThumpVoice.CLAP))
            UserPresets.save(root, clap, 7L)
            assertEquals(listOf(binned), UserPresets.bin(root))
            val back = UserPresets.unforget(root, binned!!)
            assertEquals(UserPresets.Saved(wrecked, 1L), back, "back under its own name, with its own savedAt")
            assertEquals(listOf(UserPresets.Saved(snare, 2L), UserPresets.Saved(clap, 7L), back), UserPresets.read(root), "at the end of the strip")
            assertEquals(emptyList(), UserPresets.bin(root))
            assertEquals(null, UserPresets.unforget(root, binned), "a row that outran the tap restores nothing")
            assertFalse("\"binned\"" in UserPresets.file(root).readText(), "an empty bin is not written")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a forgotten name can be saved again, and the restore then lands under a fresh name that fits`() {
        val root = shelf()
        try {
            UserPresets.save(root, wrecked, 1L)
            val binned = UserPresets.forget(root, "THUMP", "KICK", "MY KICK", 2L)!!
            val newer = ThumpPatch("MY KICK", ThumpVoice.KICK, Thump.defaults(ThumpVoice.KICK))
            UserPresets.save(root, newer, 3L)
            val back = UserPresets.unforget(root, binned)!!
            assertEquals("MY KICK 2", back.name, "the newer one keeps the name; the restored one is freshened")
            assertEquals(wrecked.macros, back.patch.macros, "and is still the sound that was forgotten")
            assertEquals(ThumpVoice.KICK, (back.patch as ThumpPatch).voice)
            assertEquals(listOf("MY KICK", "MY KICK 2"), UserPresets.forVoice(UserPresets.read(root), "THUMP", "KICK").map { it.name })
            // The fresh name fits the strip's rule even when the base fills it.
            val long = "FOURTEEN LETTE"
            assertEquals(UserPresets.MAX_NAME, long.length)
            assertEquals("FOURTEEN LET 2", UserPresets.freshName(long) { it == long })
            assertEquals("FOURTEEN LET 3", UserPresets.freshName(long) { it == long || it == "FOURTEEN LET 2" })
            assertEquals("MY KICK", UserPresets.freshName("MY KICK") { false })
            // And the merge's reading of a fresh name, which must agree with freshName exactly.
            assertTrue(UserPresets.freshened("MY KICK 2", "MY KICK") && UserPresets.freshened("MY KICK 10", "MY KICK"))
            assertTrue(UserPresets.freshened("FOURTEEN LET 2", long), "the trimmed base counts")
            assertFalse(UserPresets.freshened("MY KICK", "MY KICK"), "the name itself is not a fresh name")
            assertFalse(UserPresets.freshened("MY KICK 1", "MY KICK") || UserPresets.freshened("MY KICK 05", "MY KICK"), "numbers freshName never makes")
            assertFalse(UserPresets.freshened("KICK 2", "KICK 1"), "a numbered base is not the base of its neighbour")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the bin sweeps at the boundary, and the readout counts down to agree`() {
        val root = shelf()
        try {
            val day = 24L * 60 * 60 * 1000
            UserPresets.save(root, wrecked, 1L)
            UserPresets.save(root, snare, 2L)
            val old = UserPresets.forget(root, "THUMP", "KICK", "MY KICK", 10L)!!
            val young = UserPresets.forget(root, "THUMP", "SNARE", "MY KICK", 10L + 5 * day)!!
            assertEquals(UserPresets.BIN_DAYS, old.daysLeft(10L))
            assertEquals(1, old.daysLeft(10L + UserPresets.BIN_DAYS * day - 1), "a partial day still reads 1")
            assertEquals(0, old.daysLeft(10L + UserPresets.BIN_DAYS * day), "0 exactly where the sweep goes")
            assertEquals(0, old.daysLeft(10L + 40 * day), "never below zero")
            assertEquals(0, UserPresets.sweepBin(root, 10L + UserPresets.BIN_DAYS * day - 1), "not yet")
            assertEquals(1, UserPresets.sweepBin(root, 10L + UserPresets.BIN_DAYS * day), "the old one goes at the boundary")
            assertEquals(listOf(young), UserPresets.bin(root))
            assertEquals(0, UserPresets.sweepBin(root, 10L + UserPresets.BIN_DAYS * day), "and nothing is written for nothing")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a file from before the bin reads as an empty bin, and a binned entry this build cannot read is let go`() {
        val root = shelf()
        try {
            val file = UserPresets.file(root)
            val patch = wrecked.toJsonText().replace("\n", "")
            file.writeText("{\"version\": 1, \"presets\": [{\"savedAt\": 1, \"patch\": $patch}]}")
            assertEquals(listOf(UserPresets.Saved(wrecked, 1L)), UserPresets.read(root))
            assertEquals(emptyList(), UserPresets.bin(root))
            file.writeText("{\"version\": 1, \"presets\": [], \"binned\": [{\"savedAt\": 1, \"binnedAt\": 2, \"patch\": {\"engine\": \"FUTURE\"}}]}")
            assertEquals(emptyList(), UserPresets.bin(root), "not this build's to keep")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the forget lines ask first, then say where it went and how it comes back`() {
        assertEquals("FORGET MY KICK? IT WAITS IN DELETED PRESETS FOR ${Reversal.DAYS} DAYS.", Copy.presetForgetAsk("MY KICK"))
        assertEquals("MY KICK IS OFF THE STRIP. ${Reversal.MIND}", Copy.presetForgotten("MY KICK"))
        assertEquals("MY KICK 2 IS BACK UNDER YOURS.", Copy.presetRestored("MY KICK 2"))
        assertTrue("HOLD ITS CHIP TO FORGET IT" in Copy.PRESET_NAME_NOTE, "the save note says how a preset leaves: ${Copy.PRESET_NAME_NOTE}")
        assertTrue(Reversal.BIN in Copy.PRESET_NAME_NOTE, "and for how long the bin keeps it")
        assertTrue("STAYS" in Copy.PRESET_FORGET_FAILED && "BIN" in Copy.PRESET_RESTORE_FAILED)
    }

    @Test
    fun `the copy lines say what happened and what is not kept`() {
        assertEquals("MY KICK SAVED. IT IS UNDER THE FACTORY ROW ON EVERY KIT.", Copy.presetSaved("MY KICK"))
        assertEquals("MY KICK REPLACED. THE OLD SETTINGS ARE NOT KEPT.", Copy.presetReplaced("MY KICK"))
        assertEquals("REPLACES YOUR MY KICK. THE OLD SETTINGS ARE NOT KEPT.", Copy.presetReplaces("MY KICK"))
        assertEquals("THE FACTORY HAS DUSTY BOOM. PICK ANOTHER NAME.", Copy.presetNameFactory("DUSTY BOOM"))
        assertTrue("${UserPresets.MAX_NAME} LETTERS" in Copy.PRESET_NAME_NOTE, "the note says the one number the rule has: ${Copy.PRESET_NAME_NOTE}")
        assertTrue(Copy.PRESET_NAME_BLANK.endsWith("."))
        assertTrue(Copy.PRESET_SAVE_FAILED.endsWith("."))
    }


    @Test
    fun `a backup's presets merge in - the shelf's own stay, twins are skipped, namesakes land fresh, the bin rides minus what expired`() {
        val old = shelf()
        val new = shelf()
        val mine = shelf()
        try {
            val day = 24L * 60 * 60 * 1000
            val now = 1_700_000_000_000L
            // The old phone: two presets kept, two forgotten - one long expired, one with days left.
            UserPresets.save(old, wrecked, 1L)
            UserPresets.save(old, snare, 2L)
            UserPresets.save(old, ThumpPatch("CLAP 1", ThumpVoice.CLAP, Thump.defaults(ThumpVoice.CLAP)), 3L)
            UserPresets.save(old, ThumpPatch("HAT 1", ThumpVoice.HAT_CLOSED, Thump.defaults(ThumpVoice.HAT_CLOSED)), 4L)
            val expired = UserPresets.forget(old, "THUMP", "CLAP", "CLAP 1", now - 40 * day)!!
            val waiting = UserPresets.forget(old, "THUMP", "HAT_CLOSED", "HAT 1", now - 5 * day)!!
            val carried = UserPresets.file(old).readText(Charsets.UTF_8)

            // The new phone, bare: everything lands as it was; the expired one never gets out of the zip.
            val first = UserPresets.merge(new, carried, now)
            assertEquals(listOf(UserPresets.Saved(wrecked, 1L), UserPresets.Saved(snare, 2L)), first.landed)
            assertEquals(0, first.identical)
            assertEquals(1, first.binned)
            assertEquals(first.landed, UserPresets.read(new))
            assertEquals(listOf(waiting), UserPresets.bin(new), "the one with days left rides, with its own stamp; the expired one does not: $expired")

            // The same backup again: nothing lands, nothing is written.
            val file = UserPresets.file(new)
            file.setLastModified(1_000_000L)
            val again = UserPresets.merge(new, carried, now)
            assertEquals(emptyList(), again.landed)
            assertEquals(2, again.identical)
            assertEquals(0, again.binned, "a bin entry the shelf holds is not carried twice")
            assertEquals(1_000_000L, file.lastModified(), "nothing to land, nothing written")
            assertEquals(listOf(waiting), UserPresets.bin(new))

            // A shelf that saved its own MY KICK meanwhile, newer and different: it keeps the name and its place;
            // the backup's lands fresh, at the end, still the sound the backup held.
            val newer = UserPresets.save(mine, ThumpPatch("MY KICK", ThumpVoice.KICK, Thump.defaults(ThumpVoice.KICK)), 9L)
            val third = UserPresets.merge(mine, carried, now)
            assertEquals(listOf("MY KICK 2" to "KICK", "MY KICK" to "SNARE"), third.landed.map { it.name to it.voice })
            assertEquals(wrecked.macros, third.landed.first().patch.macros)
            assertEquals(listOf(newer) + third.landed, UserPresets.read(mine), "the shelf's own first and untouched; the backup's after")
            assertEquals(0, third.identical)
            // And that backup a second time: MY KICK 2 is the backup's MY KICK already, so nothing sprouts a MY KICK 3.
            val fourth = UserPresets.merge(mine, carried, now)
            assertEquals(emptyList(), fourth.landed)
            assertEquals(2, fourth.identical)
            assertEquals(listOf("MY KICK", "MY KICK 2"), UserPresets.forVoice(UserPresets.read(mine), "THUMP", "KICK").map { it.name })
        } finally {
            old.deleteRecursively()
            new.deleteRecursively()
            mine.deleteRecursively()
        }
    }

    @Test
    fun `a backup whose presets file is not one merges nothing, and an entry neither build reads rides once`() {
        val root = shelf()
        try {
            UserPresets.save(root, wrecked, 1L)
            val file = UserPresets.file(root)
            file.setLastModified(1_000_000L)
            assertTrue(runCatching { UserPresets.merge(root, "not json at all", 5L) }.exceptionOrNull() is JsonException)
            assertTrue(runCatching { UserPresets.merge(root, "{\"version\": 2, \"presets\": []}", 5L) }.exceptionOrNull() is JsonException, "a version this build does not know")
            assertEquals(1_000_000L, file.lastModified(), "a refusal writes nothing")
            assertEquals(listOf(UserPresets.Saved(wrecked, 1L)), UserPresets.read(root))

            val future = "{\"version\": 1, \"presets\": [{\"savedAt\": 5, \"patch\": {\"engine\": \"FUTURE\", \"version\": 1, \"name\": \"X\", \"voice\": \"Y\", \"macros\": {}}}]}"
            assertEquals(UserPresets.Merged(emptyList(), 0, 0), UserPresets.merge(root, future, 5L))
            assertEquals(1, Regex("\"FUTURE\"").findAll(file.readText()).count(), "carried for the build that can read it")
            UserPresets.merge(root, future, 6L)
            assertEquals(1, Regex("\"FUTURE\"").findAll(file.readText()).count(), "and never twice")
            assertEquals(listOf(UserPresets.Saved(wrecked, 1L)), UserPresets.read(root), "the shelf's own untouched")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the backup and landing lines count the presets beside the kits, and read as they always did without them`() {
        assertEquals("2 KITS ON ONE FILE. PICK WHERE IT GOES.", Copy.backedUp(2, 0))
        assertEquals("3 KITS AND 2 PRESETS ON ONE FILE. PICK WHERE IT GOES.", Copy.backedUp(3, 0, 2))
        assertEquals("1 KIT AND 1 PRESET ON ONE FILE. 1 SKIPPED. PICK WHERE IT GOES.", Copy.backedUp(1, 1, 1))
        assertEquals("1 KIT LANDED ON THE SHELF.", Copy.landed(1, 0))
        assertEquals("1 KIT LANDED ON THE SHELF. 1 PRESET UNDER YOURS.", Copy.landed(1, 0, 1))
        assertEquals("2 KITS LANDED ON THE SHELF. 3 PRESETS UNDER YOURS. 1 SKIPPED.", Copy.landed(2, 1, 3))
    }
}
