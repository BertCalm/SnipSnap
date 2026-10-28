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

    /** A single-chip recipe as `characterPad` leaves it: the name and the AMT (the fx beside them is not read here). */
    private fun chip(name: String, amount: Double) = JsonValue.Obj(linkedMapOf("treatment" to JsonValue.Str(name), "amount" to JsonValue.Num(amount)))

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
        assertEquals(1.0, (com.snipsnap.json.Json.parse(text).obj()["version"] as JsonValue.Num).value, text)
        for (bad in listOf(null, "", "   ", "not json", "[]", "{}", "\"row\"", "{\"version\":99,\"row\":[]}",
            "{\"version\":\"1\",\"row\":[\"rockwahed\"]}", "{\"version\":1}", "{\"version\":1,\"row\":\"driven\"}",
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
        val chip = chip("compressed", 0.7)
        assertEquals(board(PedalBoard.Pick("compressed", 0.7f, PedalSide.PRE)), PedalSlate.boardOf(chip))
        assertNull(PedalSlate.boardOf(chip("washed", 0.7)), "WASH is not a pedal")
        assertNull(PedalSlate.boardOf(JsonValue.Obj(linkedMapOf("era" to JsonValue.Str("crush"), "amount" to JsonValue.Num(0.5)))))
        assertNull(PedalSlate.boardOf(JsonValue.Obj(linkedMapOf("verb" to JsonValue.Str("smear"), "amount" to JsonValue.Num(0.5)))))
        val damaged = JsonValue.Obj(linkedMapOf("treatment" to JsonValue.Str("driven"), "amount" to JsonValue.Num(1.5)))
        assertNull(PedalSlate.boardOf(damaged), "a chip AMT out of range is no board, not a crash")
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
        val oldTouch = chip("touchwahed", 0.7)
        assertEquals(1, PedalSlate.card(PedalSlate.DEFAULT, oldTouch, null).moreCount)
        val withRig = board(PedalBoard.Pick("rig", 0.7f), PedalBoard.Pick("rockwahed", 0.5f), PedalBoard.Pick("wahed", 1f))
        assertEquals(2, PedalSlate.card(PedalSlate.DEFAULT, recipeOf(withRig), null).moreCount, "RIG and ROCK sit behind MORE")
        val washed = chip("washed", 0.7)
        val none = PedalSlate.card(PedalSlate.DEFAULT, washed, null)
        assertEquals(0, none.moreCount)
        assertNull(none.marked, "WASH is not a pedal; AMT keeps the chip's own target")
        assertEquals("MORE · 3", Copy.pedalMore(3))
    }

    // ---------- MORE's list ----------

    @Test
    fun `MORE lists the whole slate, says which are on, on the row, movable, and which can go on the row`() {
        val idle = PedalSlate.more(PedalSlate.DEFAULT, null, null)
        assertEquals(listOf("DRIVE", "SUSTAIN", "WAH", "TOUCH", "ROCK", "FORGE", "DELAY"), idle.map { it.word })
        assertTrue(idle.none { it.on || it.marked || it.canFlip })
        assertEquals(listOf("TOUCH", "ROCK"), idle.filter { it.canPutOnRow }.map { it.word })
        assertEquals(listOf("DRIVE", "SUSTAIN", "WAH", "FORGE", "DELAY"), idle.filter { it.onRow }.map { it.word })

        val b = board(PedalBoard.Pick("wahed", 0.5f, PedalSide.POST), PedalBoard.Pick("driven", 1f), PedalBoard.Pick("rig", 0.7f))
        val listed = PedalSlate.more(PedalSlate.DEFAULT, recipeOf(b), "wahed")
        assertEquals(listOf("DRIVE", "SUSTAIN", "WAH", "TOUCH", "ROCK", "FORGE", "DELAY", "RIG"), listed.map { it.word }, "an off-slate pick comes last")
        val wah = listed.single { it.word == "WAH" }
        assertTrue(wah.on && wah.marked && wah.canFlip)
        assertEquals(PedalSide.POST, wah.side)
        assertEquals(0.5f, wah.amount)
        val drive = listed.single { it.word == "DRIVE" }
        assertTrue(drive.on && !drive.canFlip, "the amp cannot move")
        val rig = listed.single { it.word == "RIG" }
        assertTrue(rig.on && !rig.onSlate && !rig.canPutOnRow && !rig.canFlip, "a pick off the slate can only come off")
        assertFalse(listed.single { it.word == "FORGE" }.canFlip, "FORGE is off and fixed")
    }

    // ---------- the gesture rule ----------

    @Test
    fun `a tap puts a pedal on at the AMT on screen, on its own side, after the ones already on`() {
        val first = PedalSlate.change(null, PedalSlate.Gesture.Tap("delayed", 0.7f))
        assertEquals(PedalSlate.Change.Render(board(PedalBoard.Pick("delayed", 0.7f, PedalSide.POST))), first)
        val second = PedalSlate.change((first as PedalSlate.Change.Render).board, PedalSlate.Gesture.Tap("wahed", 0.4f))
        assertEquals(PedalSlate.Change.Render(board(PedalBoard.Pick("delayed", 0.7f), PedalBoard.Pick("wahed", 0.4f, PedalSide.PRE))), second)
        assertEquals(
            PedalSlate.Change.Render(board(PedalBoard.Pick("driven", PadSheet.DEFAULT_AMOUNT))),
            PedalSlate.change(null, PedalSlate.Gesture.Tap("driven", 0f)),
            "a tap at AMT 0 lands at 70, so it is heard",
        )
        assertEquals(PedalSlate.Change.Render(board(PedalBoard.Pick("driven", 1f))), PedalSlate.change(null, PedalSlate.Gesture.Tap("driven", 1.2f)))
    }

    @Test
    fun `a tap on a lit pedal takes it off, the rest keep their order, and the last one off clears the pad`() {
        val b = board(PedalBoard.Pick("driven", 0.7f), PedalBoard.Pick("wahed", 0.4f), PedalBoard.Pick("delayed", 1f))
        assertEquals(
            PedalSlate.Change.Render(board(PedalBoard.Pick("driven", 0.7f), PedalBoard.Pick("delayed", 1f))),
            PedalSlate.change(b, PedalSlate.Gesture.Tap("wahed", 0.9f)),
        )
        assertEquals(PedalSlate.Change.Clear, PedalSlate.change(board(PedalBoard.Pick("driven", 0.7f)), PedalSlate.Gesture.Tap("driven", 0.7f)))
        val withRig = board(PedalBoard.Pick("rig", 0.7f), PedalBoard.Pick("wahed", 0.4f))
        assertEquals(
            PedalSlate.Change.Render(board(PedalBoard.Pick("wahed", 0.4f))),
            PedalSlate.change(withRig, PedalSlate.Gesture.Tap("rig", 0.7f)),
            "a pick off the slate can still come off",
        )
    }

    @Test
    fun `a seventh pedal is refused, and a full board still takes one off`() {
        val six = PedalBoard(listOf("driven", "compressed", "wahed", "touchwahed", "rockwahed", "forged").map { PedalBoard.Pick(it, 0.7f) })
        assertEquals(PedalBoard.MAX_PICKS, six.picks.size)
        assertEquals(PedalSlate.Change.Full, PedalSlate.change(six, PedalSlate.Gesture.Tap("delayed", 0.7f)))
        assertIs<PedalSlate.Change.Render>(PedalSlate.change(six, PedalSlate.Gesture.Tap("forged", 0.7f)))
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
    fun `a movable pick flips sides, and the amp and FORGE refuse`() {
        val b = board(PedalBoard.Pick("wahed", 0.4f), PedalBoard.Pick("driven", 0.7f), PedalBoard.Pick("forged", 0.5f))
        val flipped = PedalSlate.change(b, PedalSlate.Gesture.Flip("wahed"))
        assertEquals(PedalSlate.Change.Render(board(PedalBoard.Pick("wahed", 0.4f, PedalSide.POST), PedalBoard.Pick("driven", 0.7f), PedalBoard.Pick("forged", 0.5f))), flipped)
        assertEquals(PedalSlate.Change.Render(b), PedalSlate.change((flipped as PedalSlate.Change.Render).board, PedalSlate.Gesture.Flip("wahed")))
        assertFailsWith<IllegalArgumentException> { PedalSlate.change(b, PedalSlate.Gesture.Flip("driven")) }
        assertFailsWith<IllegalArgumentException> { PedalSlate.change(b, PedalSlate.Gesture.Flip("forged")) }
    }

    @Test
    fun `the rule refuses in words what no screen should send`() {
        val b = board(PedalBoard.Pick("driven", 0.7f))
        assertFailsWith<IllegalArgumentException> { PedalSlate.change(null, PedalSlate.Gesture.Tap("rig", 0.7f)) }
        assertFailsWith<IllegalArgumentException> { PedalSlate.change(null, PedalSlate.Gesture.Tap("sparkled", 0.7f)) }
        assertFailsWith<IllegalArgumentException> { PedalSlate.change(null, PedalSlate.Gesture.Tap("driven", Float.NaN)) }
        assertFailsWith<IllegalArgumentException> { PedalSlate.change(b, PedalSlate.Gesture.Amount("wahed", 0.5f)) }
        assertFailsWith<IllegalArgumentException> { PedalSlate.change(b, PedalSlate.Gesture.Amount("driven", Float.NaN)) }
        assertFailsWith<IllegalArgumentException> { PedalSlate.change(null, PedalSlate.Gesture.Amount("driven", 0.5f)) }
        assertFailsWith<IllegalArgumentException> { PedalSlate.change(b, PedalSlate.Gesture.Flip("wahed")) }
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

        val on = PedalSlate.land(m, 1, PedalSlate.Gesture.Tap("driven", 0.7f))
        assertEquals(PedalSlate.Landed.Applied(board(PedalBoard.Pick("driven", 0.7f)), stacked = false), on)
        val both = PedalSlate.land(m, 1, PedalSlate.Gesture.Tap("delayed", 1f))
        val expected = board(PedalBoard.Pick("driven", 0.7f), PedalBoard.Pick("delayed", 1f))
        assertEquals(PedalSlate.Landed.Applied(expected, stacked = false), both)
        m.save()
        assertEquals(expected, PadSheet.readPedals(m.pad(1)!!.recipe), "the recipe is the whole board")
        m.pedalPad(2, expected)
        m.save()
        assertContentEquals(samples(m, dir, 2), samples(m, dir, 1), "DRIVE then DELAY is the board over the original, not DELAY over DRIVE's audio")

        // DELAY off: DRIVE alone, from the original, exactly as a one-pick board.
        val off = PedalSlate.land(m, 1, PedalSlate.Gesture.Tap("delayed", 1f))
        assertEquals(PedalSlate.Landed.Applied(board(PedalBoard.Pick("driven", 0.7f)), stacked = false), off)
        m.pedalPad(3, board(PedalBoard.Pick("driven", 0.7f)))
        m.save()
        assertContentEquals(samples(m, dir, 3), samples(m, dir, 1), "taking DELAY off left the drive exactly as before")

        val cleared = PedalSlate.land(m, 1, PedalSlate.Gesture.Tap("driven", 0.7f))
        assertEquals(PedalSlate.Landed.Cleared, cleared)
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
        PedalSlate.land(m, 1, PedalSlate.Gesture.Tap("compressed", 0.7f))
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
        val grown = PedalSlate.land(m, 1, PedalSlate.Gesture.Tap("wahed", 0.5f))
        val expected = board(PedalBoard.Pick("driven", 0.7f), PedalBoard.Pick("wahed", 0.5f, PedalSide.PRE))
        assertEquals(PedalSlate.Landed.Applied(expected, stacked = false), grown)
        m.pedalPad(2, expected)
        m.save()
        assertContentEquals(samples(m, dir, 2), samples(m, dir, 1), "the chip came off first, so DRIVE is not doubled")

        m.eraPad(3, PadSheet.eraFor("CRUSH")!!, 0.5f)
        assertEquals(
            PedalSlate.Landed.Applied(board(PedalBoard.Pick("delayed", 0.7f)), stacked = false),
            PedalSlate.land(m, 3, PedalSlate.Gesture.Tap("delayed", 0.7f)),
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
        PedalSlate.land(m, 1, PedalSlate.Gesture.Tap("driven", 0.7f))
        m.eraPad(2, PadSheet.eraFor("CRUSH")!!, 0.5f)
        m.save()
        m.emptyBin()
        val before = File(dir, m.pad(1)!!.sampleFile).readBytes()
        assertEquals(PedalSlate.Landed.NotBinned, PedalSlate.land(m, 1, PedalSlate.Gesture.Tap("delayed", 0.7f)))
        assertEquals(PedalSlate.Landed.NotBinned, PedalSlate.land(m, 1, PedalSlate.Gesture.Tap("driven", 0.7f)), "nor can DRIVE come off: it is in the audio")
        assertContentEquals(before, File(dir, m.pad(1)!!.sampleFile).readBytes(), "nothing touched")
        assertEquals(board(PedalBoard.Pick("driven", 0.7f)), PadSheet.readPedals(m.pad(1)!!.recipe))

        val stacked = PedalSlate.land(m, 2, PedalSlate.Gesture.Tap("delayed", 0.7f))
        assertEquals(PedalSlate.Landed.Applied(board(PedalBoard.Pick("delayed", 0.7f)), stacked = true), stacked)
    }

    @Test
    fun `a printed board pad, ghosts after the treatment and a full board each refuse without touching the pad`() {
        val dir = File(temp, "Refuse")
        val m = KitBuilderModel.create("Refuse", dir)
        val snare = DrumSynth.snare()
        for (slot in 1..3) m.assign(slot, snare, DrumClass.SNARE)
        m.save()

        BoardSheet.apply(m, 1, 0.7f, BoardType.WIDE)
        m.save()
        val wide = File(dir, m.pad(1)!!.sampleFile).readBytes()
        assertEquals(PedalSlate.Landed.Printed("WIDE"), PedalSlate.land(m, 1, PedalSlate.Gesture.Tap("driven", 0.7f)))
        assertContentEquals(wide, File(dir, m.pad(1)!!.sampleFile).readBytes())

        PedalSlate.land(m, 2, PedalSlate.Gesture.Tap("driven", 0.7f))
        m.addGhostLayers(2)
        m.save()
        val ghosted = m.pad(2)!!
        assertEquals(PedalSlate.Landed.GhostsPostdate, PedalSlate.land(m, 2, PedalSlate.Gesture.Tap("delayed", 0.7f)))
        assertEquals(ghosted, m.pad(2), "nothing touched")

        val six = PedalBoard(listOf("driven", "compressed", "wahed", "touchwahed", "rockwahed", "forged").map { PedalBoard.Pick(it, 0.7f) })
        m.pedalPad(3, six)
        m.save()
        val full = File(dir, m.pad(3)!!.sampleFile).readBytes()
        val binned = m.binContents().size
        assertEquals(PedalSlate.Landed.Full, PedalSlate.land(m, 3, PedalSlate.Gesture.Tap("delayed", 0.7f)))
        assertContentEquals(full, File(dir, m.pad(3)!!.sampleFile).readBytes())
        assertEquals(binned, m.binContents().size, "nothing restored, nothing binned")
    }

    // ---------- the lines ----------

    @Test
    fun `every landing says what happened, in the chip's word`() {
        val tap = PedalSlate.Gesture.Tap("delayed", 0.7f)
        val withDelay = PedalSlate.Landed.Applied(board(PedalBoard.Pick("delayed", 0.7f)), stacked = false)
        val withoutDelay = PedalSlate.Landed.Applied(board(PedalBoard.Pick("driven", 0.7f)), stacked = false)
        assertEquals(Copy.treated("DELAY", "A01"), PedalSlate.toast(tap, withDelay, "A01"))
        assertEquals(Copy.treatedStacked("DELAY", "A01"), PedalSlate.toast(tap, withDelay.copy(stacked = true), "A01"))
        assertEquals("DELAY OFF A01. THE OTHER PEDALS PLAY ON.", PedalSlate.toast(tap, withoutDelay, "A01"))
        assertEquals(Copy.treated("DELAY", "A01"), PedalSlate.toast(PedalSlate.Gesture.Amount("delayed", 0.4f), withDelay, "A01"))
        val flip = PedalSlate.Gesture.Flip("wahed")
        assertEquals("WAH NOW AFTER THE AMP ON A01.", PedalSlate.toast(flip, PedalSlate.Landed.Applied(board(PedalBoard.Pick("wahed", 0.5f, PedalSide.POST)), false), "A01"))
        assertEquals("WAH NOW BEFORE THE AMP ON A01.", PedalSlate.toast(flip, PedalSlate.Landed.Applied(board(PedalBoard.Pick("wahed", 0.5f)), false), "A01"))
        assertEquals(Copy.unTreated("A01"), PedalSlate.toast(tap, PedalSlate.Landed.Cleared, "A01"))
        assertEquals("A BOARD HOLDS 6 PEDALS. TAP ONE OFF FIRST.", PedalSlate.toast(tap, PedalSlate.Landed.Full, "A01"))
        assertEquals("REMOVE WIDE BEFORE ADDING PEDALS.", PedalSlate.toast(tap, PedalSlate.Landed.Printed("WIDE"), "A01"))
        assertEquals(Copy.RETREAT_REFUSED, PedalSlate.toast(tap, PedalSlate.Landed.GhostsPostdate, "A01"))
        assertEquals(Copy.PEDALS_NOT_BINNED, PedalSlate.toast(tap, PedalSlate.Landed.NotBinned, "A01"))
        assertEquals("TOUCH ON THE ROW IN EVERY KIT. FORGE WAITS BEHIND MORE.", Copy.rowSwapped("TOUCH", "FORGE"))
    }
}
