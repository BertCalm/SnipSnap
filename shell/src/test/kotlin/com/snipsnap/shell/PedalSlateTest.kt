package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.WavReader
import com.snipsnap.json.JsonValue
import com.snipsnap.synth.BoardType
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.PedalBoard
import com.snipsnap.synth.PedalSide
import com.snipsnap.synth.Pedals
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Row seven's slate (docs/superpowers/specs/2026-10-28-pedal-slate-design.md): the app-wide row and
 * its store, PUT ON ROW, what a pad carries, the card and MORE, the gesture rule, the door and its
 * lines. The owner's answers are pinned by name: the default five, PUT ON ROW replacing the chip the
 * user picks with nothing else moving, MORE lit with a count for a pad's pedals off the row, one row
 * everywhere.
 */
class PedalSlateTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("slate").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun board(vararg picks: PedalBoard.Pick) = PedalBoard(picks.toList())
    private fun recipeOf(board: PedalBoard): JsonValue.Obj = PadRecipe(pedals = board).toJsonValue()
    private fun row(vararg keys: String) = PedalSlate.Row(keys.toList())

    /** A single-chip recipe as `characterPad` leaves it: the character's chain at the AMT, its name and the AMT. */
    private fun chip(name: String, amount: Float): JsonValue.Obj =
        PadRecipe(fx = com.snipsnap.synth.Treatments.chain(name, amount), treatment = name, amount = amount).toJsonValue()

    // ---------- the slate and the row ----------

    @Test
    fun `the slate is the library's gated pedals in library order, the words the card dispatches`() {
        assertEquals(listOf("DRIVE", "SUSTAIN", "WAH", "TOUCH", "ROCK", "FORGE", "DELAY"), PedalSlate.slate().map { it.word })
        assertEquals(Pedals.gated().filter { !it.isBoard }, PedalSlate.slate())
        assertEquals(PadSheet.PEDAL_SEGMENTS, PedalSlate.slate().map { it.word })
        for (e in PedalSlate.slate()) {
            assertEquals(PadSheet.Treatment.Character(e.key), PadSheet.treatmentFor(e.word), "${e.word} behind MORE must dispatch")
        }
    }

    @Test
    fun `the default five are the owner's - DRIVE, SUSTAIN, WAH, FORGE, DELAY - stored as keys`() {
        assertEquals(listOf("DRIVE", "SUSTAIN", "WAH", "FORGE", "DELAY"), PedalSlate.DEFAULT.words)
        assertEquals(PedalSlate.DEFAULT_ROW, PedalSlate.DEFAULT.keys, "the default must survive its own repair untouched")
        for (key in PedalSlate.DEFAULT_ROW) {
            val e = Pedals.byKey(key)
            assertTrue(e.gated && !e.isBoard, "${e.word} on the default row must be a slate pedal")
        }
        assertEquals(5, PedalSlate.ROW_SIZE)
        assertEquals(5, PedalSlate.rowSize)
    }

    @Test
    fun `a row holds five distinct slate pedals and refuses anything else`() {
        assertFailsWith<IllegalArgumentException> { row("driven", "compressed", "wahed", "forged") }
        assertFailsWith<IllegalArgumentException> { row("driven", "compressed", "wahed", "forged", "delayed", "touchwahed") }
        assertFailsWith<IllegalArgumentException> { row("driven", "driven", "wahed", "forged", "delayed") }
        assertFailsWith<IllegalArgumentException> { row("driven", "compressed", "wahed", "forged", "rig") }
        assertFailsWith<IllegalArgumentException> { row("driven", "compressed", "wahed", "forged", "washed") }
        assertFailsWith<IllegalArgumentException> { row("driven", "compressed", "wahed", "forged", "DELAY") }
    }

    // ---------- the store ----------

    @Test
    fun `the row survives a restart, as keys, and anything that is not the format reads as the default`() {
        val mine = row("touchwahed", "compressed", "wahed", "forged", "driven")
        val text = PedalSlate.write(mine)
        assertEquals(mine, PedalSlate.read(text))
        assertTrue("\"touchwahed\"" in text && "TOUCH" !in text, "the store holds keys, never words: $text")
        assertEquals("pedals.row", PedalSlate.PREFS_KEY, "the key every saved row lives under; changing it forgets every row")
        assertEquals(1.0, (com.snipsnap.json.Json.parse(text).obj()["version"] as JsonValue.Num).value, text)
        for (bad in listOf(null, "", "   ", "not json", "[]", "{}", "\"row\"", "{\"version\":99,\"row\":[]}",
            "{\"version\":\"1\",\"row\":[\"rockwahed\"]}", "{\"version\":1}", "{\"version\":1,\"row\":\"rockwahed\"}",
            "{\"version\":1.5,\"row\":[\"rockwahed\"]}", "{\"version\":1,\"row\":[\"rockwahed\"]")) {
            assertEquals(PedalSlate.DEFAULT, PedalSlate.read(bad), "'$bad' should read as the default")
        }
    }

    @Test
    fun `a damaged row is repaired in place - good keys keep their places, holes fill from the default five then the slate`() {
        // touchwahed stays first, wahed stays third; an unknown key, the RIG board and a repeat leave holes
        // that fill with the default's driven, compressed, forged (wahed is already on the row).
        val text = "{\"version\":1,\"row\":[\"touchwahed\",\"sparkled\",\"wahed\",\"rig\",\"wahed\"]}"
        assertEquals(listOf("touchwahed", "driven", "wahed", "compressed", "forged"), PedalSlate.read(text).keys)
        // A key that is not a string is a hole too.
        assertEquals(
            listOf("driven", "compressed", "rockwahed", "wahed", "forged"),
            PedalSlate.read("{\"version\":1,\"row\":[1,null,\"rockwahed\",{},[]]}").keys,
        )
        // Too short: padded from the default. Too long: cut to five.
        assertEquals(listOf("rockwahed", "driven", "compressed", "wahed", "forged"), PedalSlate.repair(listOf("rockwahed")).keys)
        assertEquals(
            listOf("delayed", "forged", "rockwahed", "touchwahed", "wahed"),
            PedalSlate.repair(listOf("delayed", "forged", "rockwahed", "touchwahed", "wahed", "compressed", "driven")).keys,
        )
        // A good key past the cut fills a hole before the defaults do.
        assertEquals(
            listOf("delayed", "forged", "rockwahed", "touchwahed", "wahed"),
            PedalSlate.repair(listOf("sparkled", "forged", "rockwahed", "touchwahed", "wahed", "delayed")).keys,
        )
        // A hole takes the first default pedal the row lacks. (The slate in library order comes next, which
        // only a build missing a default pedal reaches: five places, five defaults.)
        assertEquals(
            listOf("driven", "compressed", "wahed", "forged", "delayed"),
            PedalSlate.repair(listOf("driven", "compressed", "wahed", "forged", "forged")).keys,
        )
        assertEquals(PedalSlate.DEFAULT, PedalSlate.repair(emptyList()))
    }

    @Test
    fun `repair is idempotent and always gives a legal row`() {
        val inputs = listOf(
            emptyList(), listOf(null), listOf("rig", "rig", "rig"), PedalSlate.DEFAULT_ROW,
            listOf("touchwahed", "sparkled", "wahed", "rig", "wahed"), PedalSlate.slate().map { it.key }.reversed(),
        )
        for (input in inputs) {
            val once = PedalSlate.repair(input)
            assertEquals(once, PedalSlate.repair(once.keys), "repair moved a repaired row: $input")
        }
    }

    @Test
    fun `reading the store never throws, whatever the damage`() {
        val rng = Random(20261028)
        val good = PedalSlate.write(row("touchwahed", "compressed", "wahed", "forged", "driven"))
        val alphabet = "{}[]\",:01 .-eE\\nulltrueversionrowdrivenwahedtouchwahedrig"
        repeat(3000) { round ->
            val text = when (round % 3) {
                0 -> good.toCharArray().also { c -> repeat(1 + rng.nextInt(4)) { c[rng.nextInt(c.size)] = alphabet[rng.nextInt(alphabet.length)] } }.concatToString()
                1 -> good.substring(0, rng.nextInt(good.length + 1))
                else -> String(CharArray(rng.nextInt(60)) { alphabet[rng.nextInt(alphabet.length)] })
            }
            val read = PedalSlate.read(text)
            assertEquals(PedalSlate.ROW_SIZE, read.keys.size, "round $round: '$text'")
        }
        assertEquals(PedalSlate.DEFAULT, PedalSlate.read("[".repeat(10_000)), "nesting past the parser's depth reads as the default")
    }

    // ---------- PUT ON ROW ----------

    @Test
    fun `PUT ON ROW puts the pedal in the chip's place and nothing else moves`() {
        val swapped = PedalSlate.putOnRow(PedalSlate.DEFAULT, incoming = "touchwahed", outgoing = "forged")
        assertEquals(listOf("DRIVE", "SUSTAIN", "WAH", "TOUCH", "DELAY"), swapped.words)
        assertEquals(PedalSlate.DEFAULT, PedalSlate.putOnRow(swapped, incoming = "forged", outgoing = "touchwahed"), "and back again")
        assertEquals(listOf("ROCK", "SUSTAIN", "WAH", "FORGE", "DELAY"), PedalSlate.putOnRow(PedalSlate.DEFAULT, "rockwahed", "driven").words)
    }

    @Test
    fun `PUT ON ROW refuses a pedal already on the row, a chip not on it, and anything off the slate`() {
        assertFailsWith<IllegalArgumentException> { PedalSlate.putOnRow(PedalSlate.DEFAULT, "wahed", "forged") }
        assertFailsWith<IllegalArgumentException> { PedalSlate.putOnRow(PedalSlate.DEFAULT, "touchwahed", "rockwahed") }
        assertFailsWith<IllegalArgumentException> { PedalSlate.putOnRow(PedalSlate.DEFAULT, "rig", "forged") }
        assertFailsWith<IllegalArgumentException> { PedalSlate.putOnRow(PedalSlate.DEFAULT, "washed", "forged") }
    }

    // ---------- what a pad carries ----------

    @Test
    fun `a pad's pedals are its board, or an old pedal chip read as one pick, and nothing else`() {
        val b = board(PedalBoard.Pick("wahed", 0.5f, PedalSide.POST), PedalBoard.Pick("driven", 1f))
        assertEquals(b, PedalSlate.boardOf(recipeOf(b)))
        assertNull(PedalSlate.boardOf(null))
        assertEquals(board(PedalBoard.Pick("compressed", 0.7f, PedalSide.PRE)), PedalSlate.boardOf(chip("compressed", 0.7f)))
        assertNull(PedalSlate.boardOf(chip("washed", 0.7f)), "WASH is not a pedal")
        assertNull(PedalSlate.boardOf(JsonValue.Obj(linkedMapOf("era" to JsonValue.Str("sp1200"), "amount" to JsonValue.Num(0.5)))))
        assertNull(PedalSlate.boardOf(JsonValue.Obj(linkedMapOf("verb" to JsonValue.Str("smear"), "amount" to JsonValue.Num(0.5)))))
        val damaged = PadRecipe(fx = com.snipsnap.synth.Treatments.chain("driven", 1f), treatment = "driven", amount = 1.5f).toJsonValue()
        assertNull(PedalSlate.boardOf(damaged), "a chip AMT out of range is no board, not a crash")
    }

    @Test
    fun `an old chip whose chain is not the pedal's today is an older treatment, not that pedal`() {
        // SUSTAIN was the SQUASH section until 2026-10-24: a chip from then carries another chain under the same
        // name, and re-rendering it as today's SUSTAIN beside a new pedal would change a sound the user never touched.
        val stale = PadRecipe(fx = com.snipsnap.synth.Treatments.chain("punched", 0.7f), treatment = "compressed", amount = 0.7f).toJsonValue()
        assertNull(PedalSlate.boardOf(stale))
        val noChain = JsonValue.Obj(linkedMapOf("recipe" to JsonValue.Num(2.0), "treatment" to JsonValue.Str("compressed"), "amount" to JsonValue.Num(0.7)))
        assertNull(PedalSlate.boardOf(noChain), "no chain, no claim")
        assertNull(PedalSlate.boardOf(chip("driven", 0f)), "a chip at AMT 0 is no pedal")
    }

    @Test
    fun `a lit pedal is a pedal you hear - picks at AMT 0 and picks a later one silences are not read`() {
        // WAH then TOUCH on one side: both set the wah section, the later replaces it whole, so TOUCH alone plays.
        val shadowed = board(PedalBoard.Pick("wahed", 0.7f), PedalBoard.Pick("touchwahed", 0.7f), PedalBoard.Pick("driven", 0f), PedalBoard.Pick("delayed", 0.5f))
        assertEquals(board(PedalBoard.Pick("touchwahed", 0.7f), PedalBoard.Pick("delayed", 0.5f)), PedalSlate.boardOf(recipeOf(shadowed)))
        val heard = DrumSynth.snare()
        assertContentEquals(
            shadowed.process(heard).samples,
            board(PedalBoard.Pick("touchwahed", 0.7f), PedalBoard.Pick("delayed", 0.5f)).process(heard).samples,
            "dropping the silent picks must change no sample",
        )
        // On opposite sides both wahs play.
        val both = board(PedalBoard.Pick("wahed", 0.7f, PedalSide.POST), PedalBoard.Pick("touchwahed", 0.7f))
        assertEquals(both, PedalSlate.boardOf(recipeOf(both)))
        // RIG after DRIVE takes the amp; RIG before DRIVE keeps its compressor and slapback, so both are heard.
        assertEquals(board(PedalBoard.Pick("rig", 0.7f)), PedalSlate.boardOf(recipeOf(board(PedalBoard.Pick("driven", 0.7f), PedalBoard.Pick("rig", 0.7f)))))
        val rigFirst = board(PedalBoard.Pick("rig", 0.7f), PedalBoard.Pick("driven", 0.7f))
        assertEquals(rigFirst, PedalSlate.boardOf(recipeOf(rigFirst)))
        assertNull(PedalSlate.boardOf(recipeOf(board(PedalBoard.Pick("driven", 0f), PedalBoard.Pick("wahed", 0.7f))))?.picks?.firstOrNull { it.key == "driven" })
    }

    // ---------- the PEDALS card's chain (2026-10-30-pad-pedalboard-card-design.md) ----------

    @Test
    fun `the chain draws each side in rack order, because that is the order the sound meets it`() {
        // SUSTAIN tapped before WAH, both before the amp: the side folds into one chain, which runs the wah
        // section before the compressor, so the card draws WAH -> SUSTAIN.
        val tapped = board(PedalBoard.Pick("compressed", 0.7f, PedalSide.PRE), PedalBoard.Pick("wahed", 0.7f, PedalSide.PRE), PedalBoard.Pick("driven", 0.7f), PedalBoard.Pick("delayed", 0.5f))
        val chain = PedalSlate.chain(recipeOf(tapped), null, aroundTheAmp = true)
        assertEquals(listOf(PedalSide.PRE, PedalSide.AMP, PedalSide.POST), chain.passes.map { it.side })
        assertEquals(listOf(listOf("WAH", "SUSTAIN"), listOf("DRIVE"), listOf("DELAY")), chain.passes.map { p -> p.links.map { it.word } })
        assertEquals("WAH → SUSTAIN → DRIVE → AMP → DELAY", Copy.pedalChain(chain))
        // And that order is the sound's, not the drawing's: the other tap order renders the same samples.
        val heard = DrumSynth.snare()
        val other = board(PedalBoard.Pick("wahed", 0.7f, PedalSide.PRE), PedalBoard.Pick("compressed", 0.7f, PedalSide.PRE), PedalBoard.Pick("driven", 0.7f), PedalBoard.Pick("delayed", 0.5f))
        assertContentEquals(tapped.process(heard).samples, other.process(heard).samples, "tap order within a side changed the sound")
    }

    @Test
    fun `the chain keeps every side as a place, marks the AMT pedal, and names no amp a pad does not have`() {
        val b = board(PedalBoard.Pick("wahed", 0.7f, PedalSide.POST), PedalBoard.Pick("delayed", 1f))
        val chain = PedalSlate.chain(recipeOf(b), "wahed", aroundTheAmp = false)
        assertEquals(listOf(0, 0, 2), chain.passes.map { it.links.size }, "an empty side is still a place on the card")
        assertEquals(listOf("WAH", "DELAY"), chain.links.map { it.word }, "WAH sits before DELAY in the rack")
        assertEquals(listOf(true, false), chain.links.map { it.marked })
        assertEquals(listOf(true, false), chain.links.map { it.canFlip }, "WAH may cross the amp, DELAY may not")
        assertEquals("WAH → DELAY", Copy.pedalChain(chain), "a pad with no amp of its own names none")
        // The card shows only what plays: a silenced pick is not a link.
        val shadowed = board(PedalBoard.Pick("wahed", 0.7f), PedalBoard.Pick("touchwahed", 0.7f))
        assertEquals(listOf("TOUCH"), PedalSlate.chain(recipeOf(shadowed), null, aroundTheAmp = true).links.map { it.word })
        val empty = PedalSlate.chain(null, null, aroundTheAmp = true)
        assertTrue(empty.isEmpty)
        assertEquals(Copy.PEDALS_NONE, Copy.pedalChain(empty))
    }

    @Test
    fun `around the amp reads from a board recipe alone, and asks the file only for a pad with no pedals`() {
        val wah = board(PedalBoard.Pick("wahed", 0.7f))
        val jangle = com.snipsnap.synth.MagnetPatch("Jangle", com.snipsnap.synth.MagnetVoice.JANGLE, emptyMap())
        val landing = com.snipsnap.synth.Magnet.landingChain(com.snipsnap.synth.MagnetVoice.JANGLE)
        assertEquals(true, PedalSlate.aroundTheAmp(PadRecipe(patch = jangle, fx = landing, pedals = wah).toJsonValue()))
        assertEquals(false, PedalSlate.aroundTheAmp(recipeOf(wah)), "no patch kept: the pedals went after the amp")
        assertEquals(false, PedalSlate.aroundTheAmp(PadRecipe(patch = jangle, pedals = wah).toJsonValue()), "no landing chain, no amp to sit in front of")
        assertNull(PedalSlate.aroundTheAmp(PadRecipe(patch = jangle, fx = landing).toJsonValue()), "no pedals yet: only the file can say")
        assertNull(PedalSlate.aroundTheAmp(null))
    }

    @Test
    fun `the mark is the last pedal tapped while it is on the pad, else the newest`() {
        val b = board(PedalBoard.Pick("driven", 0.7f), PedalBoard.Pick("delayed", 1f))
        assertNull(PedalSlate.marked(null, "driven"))
        assertEquals("driven", PedalSlate.marked(b, "driven"))
        assertEquals("delayed", PedalSlate.marked(b, null))
        assertEquals("delayed", PedalSlate.marked(b, "wahed"), "a pedal tapped off hands the mark to the newest")
    }

    // ---------- the card ----------

    @Test
    fun `an untouched pad lights nothing on row seven, MORE is dark, and AMT keeps its old target`() {
        val card = PedalSlate.card(PedalSlate.DEFAULT, null, null)
        assertEquals(listOf("DRIVE", "SUSTAIN", "WAH", "FORGE", "DELAY"), card.chips.map { it.word })
        assertTrue(card.chips.none { it.lit || it.marked })
        assertEquals(0, card.moreCount)
        assertFalse(card.moreLit)
        assertFalse(card.moreMarked, "no pedals, no mark anywhere")
        assertNull(card.marked)
        assertNull(card.amount)
        assertEquals("MORE", Copy.pedalMore(card.moreCount))
    }

    @Test
    fun `pedals on the row light their chips, the newest carries the mark and AMT reads its amount`() {
        val b = board(PedalBoard.Pick("driven", 0.7f), PedalBoard.Pick("delayed", 1f))
        val card = PedalSlate.card(PedalSlate.DEFAULT, recipeOf(b), null)
        assertEquals(listOf("DRIVE", "DELAY"), card.chips.filter { it.lit }.map { it.word })
        assertEquals(listOf("DELAY"), card.chips.filter { it.marked }.map { it.word })
        assertEquals(0, card.moreCount, "both pedals are on the row")
        assertEquals("delayed", card.marked)
        assertEquals(1f, card.amount)
        assertEquals("AMT · DELAY", Copy.pedalAmt("DELAY"))
        val drive = PedalSlate.card(PedalSlate.DEFAULT, recipeOf(b), "driven")
        assertEquals(0.7f, drive.amount, "DRIVE tapped last takes the AMT")
        assertEquals(listOf("DRIVE"), drive.chips.filter { it.marked }.map { it.word })
    }

    @Test
    fun `a pad's pedal off the row lights MORE with a count, and PUT ON ROW brings it onto the row`() {
        val b = board(PedalBoard.Pick("driven", 0.7f), PedalBoard.Pick("touchwahed", 0.6f))
        val card = PedalSlate.card(PedalSlate.DEFAULT, recipeOf(b), null)
        assertEquals(1, card.moreCount)
        assertTrue(card.moreLit)
        assertEquals("MORE · 1", Copy.pedalMore(card.moreCount))
        assertTrue(card.moreMarked, "TOUCH went on last and sits behind MORE, so MORE carries the mark")
        assertEquals(0.6f, card.amount)
        assertTrue(card.chips.none { it.marked })

        val swapped = PedalSlate.putOnRow(PedalSlate.DEFAULT, "touchwahed", "forged")
        val after = PedalSlate.card(swapped, recipeOf(b), null)
        assertEquals(0, after.moreCount, "TOUCH is on the row now")
        assertEquals(listOf("DRIVE", "TOUCH"), after.chips.filter { it.lit }.map { it.word })
        assertEquals(listOf("TOUCH"), after.chips.filter { it.marked }.map { it.word })
        assertFalse(after.moreMarked)
    }

    @Test
    fun `MORE counts every pedal the row hides, an old chip and a board from the CLI included`() {
        assertEquals(1, PedalSlate.card(PedalSlate.DEFAULT, chip("touchwahed", 0.7f), null).moreCount)
        val withRig = board(PedalBoard.Pick("rig", 0.7f), PedalBoard.Pick("rockwahed", 0.5f, PedalSide.POST), PedalBoard.Pick("wahed", 1f))
        assertEquals(2, PedalSlate.card(PedalSlate.DEFAULT, recipeOf(withRig), null).moreCount, "RIG and ROCK sit behind MORE")
        val none = PedalSlate.card(PedalSlate.DEFAULT, chip("washed", 0.7f), null)
        assertEquals(0, none.moreCount)
        assertNull(none.marked, "WASH is not a pedal; AMT keeps the chip's own target")
        assertFalse(none.moreMarked)
        assertEquals("MORE · 3", Copy.pedalMore(3))
    }

    // ---------- MORE's list ----------

    @Test
    fun `MORE lists the whole library, says which are on, on the row, movable, and which can go on the row`() {
        val idle = PedalSlate.more(PedalSlate.DEFAULT, null, null)
        assertEquals(listOf("DRIVE", "SUSTAIN", "WAH", "TOUCH", "ROCK", "FORGE", "DELAY"), idle.map { it.word })
        assertEquals(Pedals.gated().map { it.word }, idle.map { it.word }, "MORE is the library's gated entries, boards included once one is gated")
        assertTrue(idle.none { it.on || it.marked || it.canFlip })
        assertTrue(idle.all { it.inLibrary && it.onSlate })
        assertEquals(listOf("TOUCH", "ROCK"), idle.filter { it.canPutOnRow }.map { it.word })
        assertEquals(listOf("DRIVE", "SUSTAIN", "WAH", "FORGE", "DELAY"), idle.filter { it.onRow }.map { it.word })

        val b = board(PedalBoard.Pick("wahed", 0.5f, PedalSide.POST), PedalBoard.Pick("driven", 1f), PedalBoard.Pick("rig", 0.7f))
        val listed = PedalSlate.more(PedalSlate.DEFAULT, recipeOf(b), "wahed")
        // RIG after DRIVE takes the amp, so DRIVE is silent and not read as on.
        assertEquals(listOf("DRIVE", "SUSTAIN", "WAH", "TOUCH", "ROCK", "FORGE", "DELAY", "RIG"), listed.map { it.word }, "a pick the library does not offer comes last")
        val wah = listed.single { it.word == "WAH" }
        assertTrue(wah.on && wah.marked && wah.canFlip)
        assertEquals(PedalSide.POST, wah.side)
        assertEquals(0.5f, wah.amount)
        assertFalse(listed.single { it.word == "DRIVE" }.on, "DRIVE under RIG's amp is silent, so not lit")
        val rig = listed.single { it.word == "RIG" }
        assertTrue(rig.on && !rig.inLibrary && !rig.onSlate && !rig.canPutOnRow && !rig.canFlip, "a pick off the library can be moved and taken off, nothing else")
        assertFalse(listed.single { it.word == "FORGE" }.canFlip, "FORGE is off and fixed")
        val drive = PedalSlate.more(PedalSlate.DEFAULT, recipeOf(board(PedalBoard.Pick("driven", 1f))), null).single { it.word == "DRIVE" }
        assertTrue(drive.on && !drive.canFlip, "the amp cannot move")
    }

    // ---------- the gesture rule ----------

    @Test
    fun `a tap puts a pedal on at 70, on its own side, after the ones already on`() {
        val first = PedalSlate.change(null, PedalSlate.Gesture.On("delayed"))
        assertEquals(PedalSlate.Change.Render(board(PedalBoard.Pick("delayed", PadSheet.DEFAULT_AMOUNT, PedalSide.POST))), first)
        val b = board(PedalBoard.Pick("delayed", 0.3f))
        assertEquals(
            PedalSlate.Change.Render(board(PedalBoard.Pick("delayed", 0.3f), PedalBoard.Pick("wahed", PadSheet.DEFAULT_AMOUNT, PedalSide.PRE))),
            PedalSlate.change(b, PedalSlate.Gesture.On("wahed")),
            "70, not the marked pedal's AMT: setting AMT then tapping must not have moved the old pedal",
        )
        assertEquals(PedalSlate.Change.Same, PedalSlate.change(b, PedalSlate.Gesture.On("delayed")), "a second tap that lands after the first does nothing")
    }

    @Test
    fun `a tap on a lit pedal takes it off, the rest keep their order, and the last one off clears the pad`() {
        val b = board(PedalBoard.Pick("driven", 0.7f), PedalBoard.Pick("wahed", 0.4f), PedalBoard.Pick("delayed", 1f))
        assertEquals(
            PedalSlate.Change.Render(board(PedalBoard.Pick("driven", 0.7f), PedalBoard.Pick("delayed", 1f))),
            PedalSlate.change(b, PedalSlate.Gesture.Off("wahed")),
        )
        assertEquals(PedalSlate.Change.Clear, PedalSlate.change(board(PedalBoard.Pick("driven", 0.7f)), PedalSlate.Gesture.Off("driven")))
        assertEquals(PedalSlate.Change.Same, PedalSlate.change(b, PedalSlate.Gesture.Off("touchwahed")))
        assertEquals(PedalSlate.Change.Same, PedalSlate.change(null, PedalSlate.Gesture.Off("touchwahed")))
        val withRig = board(PedalBoard.Pick("rig", 0.7f), PedalBoard.Pick("wahed", 0.4f))
        assertEquals(
            PedalSlate.Change.Render(board(PedalBoard.Pick("wahed", 0.4f))),
            PedalSlate.change(withRig, PedalSlate.Gesture.Off("rig")),
            "a pick the library does not offer can still come off",
        )
        assertEquals(
            PedalSlate.Change.Render(board(PedalBoard.Pick("rig", 0.3f), PedalBoard.Pick("wahed", 0.4f))),
            PedalSlate.change(withRig, PedalSlate.Gesture.Amount("rig", 0.3f)),
            "and take the AMT",
        )
    }

    @Test
    fun `one wah section per side - TOUCH on a pad with WAH replaces it, and says so`() {
        val b = board(PedalBoard.Pick("wahed", 0.5f), PedalBoard.Pick("delayed", 0.7f))
        assertEquals(
            PedalSlate.Change.Render(board(PedalBoard.Pick("delayed", 0.7f), PedalBoard.Pick("touchwahed", PadSheet.DEFAULT_AMOUNT)), replaced = listOf("wahed")),
            PedalSlate.change(b, PedalSlate.Gesture.On("touchwahed")),
        )
        // On the other side it is a second wah, and both play.
        val post = board(PedalBoard.Pick("wahed", 0.5f, PedalSide.POST))
        assertEquals(
            PedalSlate.Change.Render(board(PedalBoard.Pick("wahed", 0.5f, PedalSide.POST), PedalBoard.Pick("touchwahed", PadSheet.DEFAULT_AMOUNT))),
            PedalSlate.change(post, PedalSlate.Gesture.On("touchwahed")),
        )
    }

    /** Six heard at once: RIG (a board, from the CLI) at the amp, SUSTAIN, WAH before the amp, TOUCH after it, FORGE and DELAY. */
    private val sixHeard = PedalBoard(
        listOf(
            PedalBoard.Pick("rig", 0.7f), PedalBoard.Pick("compressed", 0.7f), PedalBoard.Pick("wahed", 0.7f),
            PedalBoard.Pick("touchwahed", 0.7f, PedalSide.POST), PedalBoard.Pick("forged", 0.7f), PedalBoard.Pick("delayed", 0.7f),
        ),
    )

    @Test
    fun `a seventh pedal is refused, a pedal that replaces one fits, and a full board still takes one off`() {
        assertEquals(PedalBoard.MAX_PICKS, sixHeard.picks.size)
        assertEquals(sixHeard, PedalSlate.boardOf(recipeOf(sixHeard)), "all six are heard")
        // DRIVE takes RIG's amp, but RIG keeps its compressor and slapback: seven heard, one too many.
        assertEquals(PedalSlate.Change.Full, PedalSlate.change(sixHeard, PedalSlate.Gesture.On("driven")))
        // ROCK before the amp replaces WAH: still six, so it fits.
        val rock = PedalSlate.change(sixHeard, PedalSlate.Gesture.On("rockwahed"))
        assertIs<PedalSlate.Change.Render>(rock)
        assertEquals(listOf("wahed"), rock.replaced)
        assertEquals(PedalBoard.MAX_PICKS, rock.board.picks.size)
        assertIs<PedalSlate.Change.Render>(PedalSlate.change(sixHeard, PedalSlate.Gesture.Off("forged")))
    }

    @Test
    fun `AMT moves one pick in place, and AMT 0 takes it off because a lit chip always sounds`() {
        val b = board(PedalBoard.Pick("driven", 0.7f), PedalBoard.Pick("wahed", 0.4f, PedalSide.POST))
        assertEquals(
            PedalSlate.Change.Render(board(PedalBoard.Pick("driven", 0.3f), PedalBoard.Pick("wahed", 0.4f, PedalSide.POST))),
            PedalSlate.change(b, PedalSlate.Gesture.Amount("driven", 0.3f)),
            "the pick keeps its place and its side",
        )
        assertEquals(
            PedalSlate.Change.Render(board(PedalBoard.Pick("wahed", 0.4f, PedalSide.POST))),
            PedalSlate.change(b, PedalSlate.Gesture.Amount("driven", 0f)),
        )
        assertEquals(PedalSlate.Change.Clear, PedalSlate.change(board(PedalBoard.Pick("driven", 0.7f)), PedalSlate.Gesture.Amount("driven", 0f)))
        assertEquals(
            PedalSlate.Change.Render(board(PedalBoard.Pick("driven", 1f), PedalBoard.Pick("wahed", 0.4f, PedalSide.POST))),
            PedalSlate.change(b, PedalSlate.Gesture.Amount("driven", 1.5f)),
        )
    }

    @Test
    fun `a movable pick flips sides and goes last there, replacing a wah it now silences, and the amp and FORGE refuse`() {
        val b = board(PedalBoard.Pick("wahed", 0.4f), PedalBoard.Pick("driven", 0.7f), PedalBoard.Pick("forged", 0.5f))
        val flipped = PedalSlate.change(b, PedalSlate.Gesture.Flip("wahed", PedalSide.POST))
        assertEquals(PedalSlate.Change.Render(board(PedalBoard.Pick("driven", 0.7f), PedalBoard.Pick("forged", 0.5f), PedalBoard.Pick("wahed", 0.4f, PedalSide.POST))), flipped)
        assertEquals(
            PedalSlate.Change.Render(board(PedalBoard.Pick("driven", 0.7f), PedalBoard.Pick("forged", 0.5f), PedalBoard.Pick("wahed", 0.4f))),
            PedalSlate.change((flipped as PedalSlate.Change.Render).board, PedalSlate.Gesture.Flip("wahed", PedalSide.PRE)),
        )
        // The flip says where it goes, not "the other side": a second tap that lands late does nothing.
        assertEquals(PedalSlate.Change.Same, PedalSlate.change(flipped.board, PedalSlate.Gesture.Flip("wahed", PedalSide.POST)))
        // WAH flipped onto TOUCH's side wins there, so TOUCH comes off rather than stay lit and silent.
        val apart = board(PedalBoard.Pick("wahed", 0.4f), PedalBoard.Pick("touchwahed", 0.6f, PedalSide.POST))
        assertEquals(
            PedalSlate.Change.Render(board(PedalBoard.Pick("wahed", 0.4f, PedalSide.POST)), replaced = listOf("touchwahed")),
            PedalSlate.change(apart, PedalSlate.Gesture.Flip("wahed", PedalSide.POST)),
        )
        val wahGone = PedalSlate.change(apart, PedalSlate.Gesture.Flip("wahed", PedalSide.POST)) as PedalSlate.Change.Render
        assertEquals(PedalSlate.Change.Same, PedalSlate.change(wahGone.board, PedalSlate.Gesture.Flip("wahed", PedalSide.POST)), "doubled, it does not undo itself or bring TOUCH back")
        assertEquals(PedalSlate.Change.Same, PedalSlate.change(wahGone.board, PedalSlate.Gesture.Flip("touchwahed", PedalSide.PRE)), "a late flip for a pedal no longer on is nothing")
        assertFailsWith<IllegalArgumentException> { PedalSlate.change(b, PedalSlate.Gesture.Flip("driven", PedalSide.POST)) }
        assertFailsWith<IllegalArgumentException> { PedalSlate.change(b, PedalSlate.Gesture.Flip("forged", PedalSide.POST)) }
    }

    @Test
    fun `the rule refuses in words what no screen should send`() {
        val b = board(PedalBoard.Pick("driven", 0.7f))
        assertFailsWith<IllegalArgumentException> { PedalSlate.change(null, PedalSlate.Gesture.On("rig")) }
        assertFailsWith<IllegalArgumentException> { PedalSlate.change(null, PedalSlate.Gesture.On("sparkled")) }
        assertFailsWith<IllegalArgumentException> { PedalSlate.change(b, PedalSlate.Gesture.Amount("driven", Float.NaN)) }
        assertFailsWith<IllegalArgumentException> { PedalSlate.change(b, PedalSlate.Gesture.Flip("sparkled", PedalSide.PRE)) }
        // A late AMT or flip for a pedal no longer on the pad is nothing, not an error: the screen had not caught up.
        assertEquals(PedalSlate.Change.Same, PedalSlate.change(b, PedalSlate.Gesture.Amount("wahed", 0.5f)))
        assertEquals(PedalSlate.Change.Same, PedalSlate.change(null, PedalSlate.Gesture.Amount("driven", 0f)))
        assertEquals(PedalSlate.Change.Same, PedalSlate.change(b, PedalSlate.Gesture.Flip("wahed", PedalSide.POST)))
    }

    // ---------- the door ----------

    private fun samples(m: KitBuilderModel, dir: File, slot: Int): FloatArray = WavReader.read(File(dir, m.pad(slot)!!.sampleFile)).samples

    @Test
    fun `taps build a board from the original every time, so pedals never stack, and the last one off gives the original back`() {
        val dir = File(temp, "Taps")
        val m = KitBuilderModel.create("Taps", dir)
        val snare = DrumSynth.snare()
        for (slot in 1..3) m.assign(slot, snare, DrumClass.SNARE)
        m.save()
        val original = File(dir, m.pad(1)!!.sampleFile).readBytes()

        val on = PedalSlate.land(m, 1, PedalSlate.Gesture.On("driven"), m.pad(1)!!.sampleFile)
        assertEquals(PedalSlate.Landed.Applied(board(PedalBoard.Pick("driven", 0.7f)), stacked = false), on)
        val both = PedalSlate.land(m, 1, PedalSlate.Gesture.On("delayed"))
        val expected = board(PedalBoard.Pick("driven", 0.7f), PedalBoard.Pick("delayed", 0.7f))
        assertEquals(PedalSlate.Landed.Applied(expected, stacked = false), both)
        m.save()
        assertEquals(expected, PadSheet.readPedals(m.pad(1)!!.recipe), "the recipe is the whole board")
        m.pedalPad(2, expected)
        m.save()
        assertContentEquals(samples(m, dir, 2), samples(m, dir, 1), "DRIVE then DELAY is the board over the original, not DELAY over DRIVE's audio")

        // A second tap that reaches the door after the first changes nothing at all.
        val bytes = File(dir, m.pad(1)!!.sampleFile).readBytes()
        assertEquals(PedalSlate.Landed.Unchanged, PedalSlate.land(m, 1, PedalSlate.Gesture.On("delayed")))
        assertContentEquals(bytes, File(dir, m.pad(1)!!.sampleFile).readBytes())

        // DELAY off: DRIVE alone, from the original, exactly as a one-pick board.
        val off = PedalSlate.land(m, 1, PedalSlate.Gesture.Off("delayed"))
        assertEquals(PedalSlate.Landed.Applied(board(PedalBoard.Pick("driven", 0.7f)), stacked = false), off)
        m.pedalPad(3, board(PedalBoard.Pick("driven", 0.7f)))
        m.save()
        assertContentEquals(samples(m, dir, 3), samples(m, dir, 1), "taking DELAY off left the drive exactly as before")

        assertEquals(PedalSlate.Landed.Cleared, PedalSlate.land(m, 1, PedalSlate.Gesture.Off("driven")))
        m.save()
        assertNull(m.pad(1)!!.recipe, "the pad is itself again")
        assertContentEquals(original, File(dir, m.pad(1)!!.sampleFile).readBytes(), "the original, byte for byte")
    }

    @Test
    fun `AMT re-renders the marked pick from the original, and AMT 0 on the only pick gives the original back`() {
        val dir = File(temp, "Amt")
        val m = KitBuilderModel.create("Amt", dir)
        val snare = DrumSynth.snare()
        m.assign(1, snare, DrumClass.SNARE)
        m.assign(2, snare, DrumClass.SNARE)
        m.save()
        val original = File(dir, m.pad(1)!!.sampleFile).readBytes()
        PedalSlate.land(m, 1, PedalSlate.Gesture.On("compressed"))
        assertEquals(
            PedalSlate.Landed.Applied(board(PedalBoard.Pick("compressed", 0.3f)), stacked = false),
            PedalSlate.land(m, 1, PedalSlate.Gesture.Amount("compressed", 0.3f)),
        )
        m.pedalPad(2, board(PedalBoard.Pick("compressed", 0.3f)))
        m.save()
        assertContentEquals(samples(m, dir, 2), samples(m, dir, 1))
        assertEquals(PedalSlate.Landed.Cleared, PedalSlate.land(m, 1, PedalSlate.Gesture.Amount("compressed", 0f)))
        m.save()
        assertContentEquals(original, File(dir, m.pad(1)!!.sampleFile).readBytes())
    }

    @Test
    fun `an old pedal chip grows into a board, and a pedal replaces an era as a chip does`() {
        val dir = File(temp, "Grow")
        val m = KitBuilderModel.create("Grow", dir)
        val snare = DrumSynth.snare()
        for (slot in 1..4) m.assign(slot, snare, DrumClass.SNARE)
        m.save()
        m.characterPad(1, "driven", 0.7f)
        assertEquals(board(PedalBoard.Pick("driven", 0.7f)), PedalSlate.boardOf(m.pad(1)!!.recipe), "the chip's own recipe reads as today's DRIVE")
        val grown = PedalSlate.land(m, 1, PedalSlate.Gesture.On("wahed"))
        val expected = board(PedalBoard.Pick("driven", 0.7f), PedalBoard.Pick("wahed", 0.7f, PedalSide.PRE))
        assertEquals(PedalSlate.Landed.Applied(expected, stacked = false), grown)
        m.pedalPad(2, expected)
        m.save()
        assertContentEquals(samples(m, dir, 2), samples(m, dir, 1), "the chip came off first, so DRIVE is not doubled")

        m.eraPad(3, PadSheet.eraFor("CRUSH")!!, 0.5f)
        assertEquals(
            PedalSlate.Landed.Applied(board(PedalBoard.Pick("delayed", 0.7f)), stacked = false),
            PedalSlate.land(m, 3, PedalSlate.Gesture.On("delayed")),
        )
        m.pedalPad(4, board(PedalBoard.Pick("delayed", 0.7f)))
        m.save()
        assertContentEquals(samples(m, dir, 4), samples(m, dir, 3), "the era came off; one recipe per pad")
        assertNull(PadSheet.read(m.pad(3)!!.recipe), "CRUSH no longer claims the pad")
    }

    @Test
    fun `a pad whose pedals the bin cannot restore refuses, while a pad with another treatment stacks and says so`() {
        val dir = File(temp, "NotBinned")
        val m = KitBuilderModel.create("NotBinned", dir)
        val snare = DrumSynth.snare()
        m.assign(1, snare, DrumClass.SNARE)
        m.assign(2, snare, DrumClass.SNARE)
        m.save()
        PedalSlate.land(m, 1, PedalSlate.Gesture.On("driven"))
        m.eraPad(2, PadSheet.eraFor("CRUSH")!!, 0.5f)
        m.save()
        m.emptyBin()
        val before = File(dir, m.pad(1)!!.sampleFile).readBytes()
        assertEquals(PedalSlate.Landed.NotBinned, PedalSlate.land(m, 1, PedalSlate.Gesture.On("delayed")))
        assertEquals(PedalSlate.Landed.NotBinned, PedalSlate.land(m, 1, PedalSlate.Gesture.Off("driven")), "nor can DRIVE come off: it is in the audio")
        assertContentEquals(before, File(dir, m.pad(1)!!.sampleFile).readBytes(), "nothing touched")
        assertEquals(board(PedalBoard.Pick("driven", 0.7f)), PadSheet.readPedals(m.pad(1)!!.recipe))

        val stacked = PedalSlate.land(m, 2, PedalSlate.Gesture.On("delayed"))
        assertEquals(PedalSlate.Landed.Applied(board(PedalBoard.Pick("delayed", 0.7f)), stacked = true), stacked)

        // An old DRIVE chip is that pedal too: with its original gone it refuses rather than stack WAH on DRIVE's audio.
        m.assign(3, snare, DrumClass.SNARE)
        m.characterPad(3, "driven", 0.7f)
        m.emptyBin()
        assertEquals(PedalSlate.Landed.NotBinned, PedalSlate.land(m, 3, PedalSlate.Gesture.On("wahed")))
        // A full pad that cannot change at all says that, not "tap one off".
        m.update(1) { it.copy(recipe = recipeOf(sixHeard)) }
        assertEquals(PedalSlate.Landed.NotBinned, PedalSlate.land(m, 1, PedalSlate.Gesture.On("driven")))
    }

    /**
     * The open question D5 left (docs/superpowers/specs/2026-10-31-bin-keeps-what-a-pad-needs-design.md):
     * a pedal pad left alone past the bin's days still changes, because the bin keeps its original.
     */
    @Test
    fun `a pedal pad's next change after the bin's sweep lands, because the bin kept its original`() {
        val dir = File(temp, "Swept")
        val m = KitBuilderModel.create("Swept", dir)
        val snare = DrumSynth.snare()
        m.assign(1, snare, DrumClass.SNARE)
        m.assign(2, snare, DrumClass.SNARE)
        m.save()
        PedalSlate.land(m, 1, PedalSlate.Gesture.On("driven"))
        m.save()
        val swept = KitBuilderModel.open(dir).purgeBin(olderThanDays = 0.0, nowMillis = System.currentTimeMillis() + 1000)
        assertEquals(0, swept, "the sweep took the pedal pad's original")

        val expected = board(PedalBoard.Pick("driven", 0.7f), PedalBoard.Pick("delayed", 0.7f))
        assertEquals(PedalSlate.Landed.Applied(expected, stacked = false), PedalSlate.land(m, 1, PedalSlate.Gesture.On("delayed")))
        m.pedalPad(2, expected)
        m.save()
        assertContentEquals(samples(m, dir, 2), samples(m, dir, 1), "DELAY joined DRIVE over the original, not over DRIVE's audio")
    }

    @Test
    fun `TOUCH on a pad with WAH lands in WAH's place, and the line says so`() {
        val dir = File(temp, "Replace")
        val m = KitBuilderModel.create("Replace", dir)
        val snare = DrumSynth.snare()
        m.assign(1, snare, DrumClass.SNARE)
        m.assign(2, snare, DrumClass.SNARE)
        m.save()
        PedalSlate.land(m, 1, PedalSlate.Gesture.On("wahed"))
        val landed = PedalSlate.land(m, 1, PedalSlate.Gesture.On("touchwahed"))
        assertEquals(PedalSlate.Landed.Applied(board(PedalBoard.Pick("touchwahed", 0.7f)), stacked = false, replaced = listOf("wahed")), landed)
        assertEquals("TOUCH ON A01 IN PLACE OF WAH. ORIGINAL SLEEPS IN THE BIN.", PedalSlate.toast(PedalSlate.Gesture.On("touchwahed"), landed, "A01"))
        m.pedalPad(2, board(PedalBoard.Pick("touchwahed", 0.7f)))
        m.save()
        assertContentEquals(samples(m, dir, 2), samples(m, dir, 1), "TOUCH alone, from the original")
    }

    @Test
    fun `a printed board pad, a changed sample, ghosts after the treatment and a full board each refuse without touching the pad`() {
        val dir = File(temp, "Refuse")
        val m = KitBuilderModel.create("Refuse", dir)
        val snare = DrumSynth.snare()
        for (slot in 1..3) m.assign(slot, snare, DrumClass.SNARE)
        m.save()

        BoardSheet.apply(m, 1, 0.7f, BoardType.WIDE)
        m.save()
        val wide = File(dir, m.pad(1)!!.sampleFile).readBytes()
        assertEquals(PedalSlate.Landed.Printed("WIDE"), PedalSlate.land(m, 1, PedalSlate.Gesture.On("driven")))
        assertContentEquals(wide, File(dir, m.pad(1)!!.sampleFile).readBytes())
        // AQUARIUM's marker too, which pedalPad's own WIDE guard would not catch: the slate's door checks the marker.
        val aquarium = m.update(2) { it.copy(recipe = JsonValue.Obj(linkedMapOf("aquariumPad" to JsonValue.Obj(linkedMapOf())))) }
        val aquariumBytes = File(dir, aquarium.sampleFile).readBytes()
        assertEquals(PedalSlate.Landed.Printed("AQUARIUM"), PedalSlate.land(m, 2, PedalSlate.Gesture.On("driven")))
        assertContentEquals(aquariumBytes, File(dir, m.pad(2)!!.sampleFile).readBytes())
        m.update(2) { it.copy(recipe = null) }

        val untouched = m.pad(2)!!
        assertEquals(PedalSlate.Landed.Gone, PedalSlate.land(m, 2, PedalSlate.Gesture.On("driven"), sampleFile = "not-this-one.wav"))
        assertEquals(untouched, m.pad(2), "the screen's pad is gone; nothing touched")

        PedalSlate.land(m, 2, PedalSlate.Gesture.On("driven"))
        m.addGhostLayers(2)
        m.save()
        val ghosted = m.pad(2)!!
        assertEquals(PedalSlate.Landed.GhostsPostdate, PedalSlate.land(m, 2, PedalSlate.Gesture.On("delayed")))
        assertEquals(ghosted, m.pad(2), "nothing touched")

        // Rendered through the door, so the original is in the bin: a pad that cannot change at all refuses
        // before a full one does (the NotBinned test), so Full needs a pad that could.
        m.pedalPad(3, sixHeard)
        m.save()
        val full = File(dir, m.pad(3)!!.sampleFile).readBytes()
        val binned = m.binContents().size
        assertEquals(PedalSlate.Landed.Full, PedalSlate.land(m, 3, PedalSlate.Gesture.On("driven")))
        assertContentEquals(full, File(dir, m.pad(3)!!.sampleFile).readBytes())
        assertEquals(binned, m.binContents().size, "nothing restored, nothing binned")
    }

    // ---------- the lines ----------

    @Test
    fun `every landing says what happened, in the chip's word`() {
        val on = PedalSlate.Gesture.On("delayed")
        val off = PedalSlate.Gesture.Off("delayed")
        val withDelay = PedalSlate.Landed.Applied(board(PedalBoard.Pick("delayed", 0.7f)), stacked = false)
        val withoutDelay = PedalSlate.Landed.Applied(board(PedalBoard.Pick("compressed", 0.7f), PedalBoard.Pick("driven", 0.7f)), stacked = false)
        assertEquals(Copy.treated("DELAY", "A01"), PedalSlate.toast(on, withDelay, "A01"))
        assertEquals(Copy.treatedStacked("DELAY", "A01"), PedalSlate.toast(on, withDelay.copy(stacked = true), "A01"))
        assertEquals("DELAY OFF A01. AMT NOW MOVES DRIVE.", PedalSlate.toast(off, withoutDelay, "A01"))
        assertEquals("DELAY OFF A01. AMT NOW MOVES DRIVE.", PedalSlate.toast(PedalSlate.Gesture.Amount("delayed", 0f), withoutDelay, "A01"))
        assertEquals(Copy.treated("DELAY", "A01"), PedalSlate.toast(PedalSlate.Gesture.Amount("delayed", 0.4f), withDelay, "A01"))
        val touch = PedalSlate.Landed.Applied(board(PedalBoard.Pick("touchwahed", 0.7f)), stacked = false, replaced = listOf("wahed"))
        assertEquals("TOUCH ON A01 IN PLACE OF WAH. ORIGINAL SLEEPS IN THE BIN.", PedalSlate.toast(PedalSlate.Gesture.On("touchwahed"), touch, "A01"))
        val flip = PedalSlate.Gesture.Flip("wahed", PedalSide.POST)
        assertEquals("WAH NOW AFTER THE AMP ON A01.", PedalSlate.toast(flip, PedalSlate.Landed.Applied(board(PedalBoard.Pick("wahed", 0.5f, PedalSide.POST)), false), "A01"))
        assertEquals("WAH NOW BEFORE THE AMP ON A01.", PedalSlate.toast(flip, PedalSlate.Landed.Applied(board(PedalBoard.Pick("wahed", 0.5f)), false), "A01"))
        assertEquals(
            "WAH NOW AFTER THE AMP ON A01, IN PLACE OF TOUCH. ORIGINAL SLEEPS IN THE BIN.",
            PedalSlate.toast(flip, PedalSlate.Landed.Applied(board(PedalBoard.Pick("wahed", 0.5f, PedalSide.POST)), false, listOf("touchwahed")), "A01"),
        )
        assertEquals(Copy.unTreated("A01"), PedalSlate.toast(on, PedalSlate.Landed.Cleared, "A01"))
        assertNull(PedalSlate.toast(on, PedalSlate.Landed.Unchanged, "A01"), "nothing happened; nothing to say")
        assertEquals(Copy.BIN_ITEM_GONE, PedalSlate.toast(on, PedalSlate.Landed.Gone, "A01"))
        assertEquals("A PAD HOLDS 6 PEDALS. TAP ONE OFF FIRST.", PedalSlate.toast(on, PedalSlate.Landed.Full, "A01"))
        assertEquals("REMOVE WIDE BEFORE ADDING PEDALS.", PedalSlate.toast(on, PedalSlate.Landed.Printed("WIDE"), "A01"))
        assertEquals(Copy.RETREAT_REFUSED, PedalSlate.toast(on, PedalSlate.Landed.GhostsPostdate, "A01"))
        assertEquals(Copy.PEDALS_NOT_BINNED, PedalSlate.toast(on, PedalSlate.Landed.NotBinned, "A01"))
        assertTrue("VERSIONS" !in Copy.PEDALS_NOT_BINNED, "VERSIONS cannot bring back audio the bin let go of")
        assertEquals("TOUCH ON THE ROW IN EVERY KIT. FORGE MOVES BEHIND MORE.", Copy.rowSwapped("TOUCH", "FORGE"))
        assertEquals("NOW TAP THE ROW CHIP TO SWAP FOR TOUCH.", Copy.putOnRowPrompt("TOUCH"))
    }
}
