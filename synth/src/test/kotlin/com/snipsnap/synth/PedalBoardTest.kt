package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The board: the pedals on one pad, rendered as passes around the amp.
 * The first test is the one that protects everything: a one-sided board
 * is exactly the folded chain through the character door.
 */
class PedalBoardTest {

    private val snare: Snip by lazy { Thump.render(ThumpVoice.SNARE) }

    private fun pick(key: String, amount: Float = 1f, side: PedalSide? = null) =
        side?.let { PedalBoard.Pick(key, amount, it) } ?: PedalBoard.Pick(key, amount)

    @Test
    fun `a one-pick board is exactly the chip - every entry, at the sheet's AMT and at full`() {
        for (e in Pedals.entries.filter { !it.isBoard }) {
            for (amount in listOf(0.7f, 1f)) {
                val board = PedalBoard(listOf(pick(e.key, amount)))
                val viaBoard = board.process(snare)
                val viaDoor = Treatments.apply(e.key, snare, amount).snip
                assertContentEquals(viaDoor.samples, viaBoard.samples, "${e.word} at $amount: the board is not the chip")
                assertEquals(viaDoor.channels, viaBoard.channels)
            }
        }
    }

    @Test
    fun `a board entry on its own equals its folded chain through the door's own fade`() {
        val rig = Pedals.byKey("rig")
        for (amount in listOf(0.7f, 1f)) {
            val board = PedalBoard(listOf(pick("rig", amount)))
            val chain = rig.chainAt(amount)
            val expected = chain.process(Treatments.endFaded(snare, chain, rig.chain))
            assertContentEquals(expected.samples, board.process(snare).samples, "RIG at $amount is not its chain with the fade ahead of its amp")
        }
    }

    @Test
    fun `two picks on one side fold into one pass in rack order, the later pick winning a shared section`() {
        val board = PedalBoard(listOf(pick("wahed", 0.7f, PedalSide.PRE), pick("compressed", 1f, PedalSide.PRE)))
        val folded = board.chainFor(PedalSide.PRE)!!
        assertEquals(Treatments.chain("wahed", 0.7f).wah, folded.wah)
        assertEquals(Treatments.chain("compressed", 1f).squash, folded.squash)
        assertContentEquals(folded.process(snare).samples, board.process(snare).samples, "one side is one pass")

        // DRIVE then RIG both set valve: RIG, picked later, owns it.
        val stacked = PedalBoard(listOf(pick("driven", 0.5f), pick("rig", 1f)))
        assertEquals(Pedals.byKey("rig").chain.valve, stacked.chainFor(PedalSide.AMP)!!.valve)
        assertEquals("rig", stacked.owner(PedalSide.AMP, "valve")!!.key)
        assertEquals("rig", stacked.owner(PedalSide.AMP, "echo")!!.key)
    }

    @Test
    fun `WAH before the amp is the rig order - two renders in a row - and after the amp is a different sound`() {
        val pre = PedalBoard(listOf(pick("wahed", 1f, PedalSide.PRE), pick("driven", 1f)))
        val rig = Treatments.apply("driven", Treatments.chain("wahed", 1f).process(snare), 1f).snip
        assertContentEquals(rig.samples, pre.process(snare).samples, "PRE then AMP is not the pedal and then the amp")

        val post = PedalBoard(listOf(pick("wahed", 1f, PedalSide.POST), pick("driven", 1f)))
        val padSheetWay = Treatments.chain("wahed", 1f).process(Treatments.apply("driven", snare, 1f).snip)
        assertContentEquals(padSheetWay.samples, post.process(snare).samples, "AMP then POST is not the amp and then the pedal")
        assertFalse(pre.process(snare).samples.contentEquals(post.process(snare).samples), "the side of the amp is audible in the samples")
    }

    @Test
    fun `the end fade runs ahead of the amp pass - a DRIVE pick at full ends the snare at least 53 dB under its peak`() {
        val out = PedalBoard(listOf(pick("driven", 1f))).process(snare)
        val peak = out.samples.maxOf { abs(it) }
        val tail = (0.020f * out.sampleRate).toInt() * out.channels
        val endPeak = out.samples.copyOfRange(out.samples.size - tail, out.samples.size).maxOf { abs(it) }
        val db = 20 * log10(endPeak / peak)
        assertTrue(db <= -53f, "the amp pass ended ${"%.1f".format(db)} dB under the peak; the bar is 53")
    }

    @Test
    fun `the board owns one tail budget over the original`() {
        val rig = PedalBoard(listOf(pick("rig", 1f))).process(snare)
        assertTrue(rig.frameCount <= snare.frameCount + (FxChain.MAX_CHAIN_TAIL_SECONDS * snare.sampleRate).toInt(), "RIG's slapback ran past the budget")
        // The cap is the chain's own: a pass that stays within it is untouched (the equivalence tests above prove it).
    }

    @Test
    fun `RIG and DELAY on one board run two echoes, one per pass, inside the board's one tail budget`() {
        val board = PedalBoard(listOf(pick("rig", 1f), pick("delayed", 1f)))
        assertEquals("rig", board.owner(PedalSide.AMP, "echo")!!.key, "RIG's slapback is the AMP pass's echo")
        assertEquals("delayed", board.owner(PedalSide.POST, "echo")!!.key, "DELAY is the POST pass's echo")
        val out = board.process(snare)
        assertTrue(
            out.frameCount <= snare.frameCount + (FxChain.MAX_CHAIN_TAIL_SECONDS * snare.sampleRate).toInt(),
            "two tail-making picks ran past the board's budget",
        )
    }

    @Test
    fun `deterministic, finite, bypassed at AMT 0, and refused above six, twice, or on a side it cannot sit`() {
        val board = PedalBoard(listOf(pick("compressed", 0.6f), pick("driven", 0.8f)))
        assertContentEquals(board.process(snare).samples, board.process(snare).samples)
        assertTrue(board.process(snare).samples.all { it.isFinite() })

        val off = PedalBoard(listOf(pick("compressed", 0f), pick("driven", 0f)))
        assertTrue(off.isBypass)
        assertContentEquals(snare.samples, off.process(snare).samples, "AMT 0 everywhere is the pad itself")

        assertFailsWith<IllegalArgumentException> { PedalBoard(emptyList()) }
        assertFailsWith<IllegalArgumentException> { PedalBoard(listOf(pick("driven"), pick("driven"))) }
        assertFailsWith<IllegalArgumentException> { pick("driven", 1f, PedalSide.PRE) }
        assertFailsWith<IllegalArgumentException> { pick("rig", 1f, PedalSide.POST) }
        assertFailsWith<IllegalArgumentException> { pick("sparkled") }
        assertEquals(6, PedalBoard.MAX_PICKS)
    }

    @Test
    fun `json round trip is exact, strict, and sits under pedals on a recipe - never under board`() {
        val board = PedalBoard(listOf(pick("wahed", 0.7f, PedalSide.POST), pick("driven", 1f), pick("compressed", 0.5f)))
        assertEquals(board, PedalBoard.fromJsonText(board.toJsonText()))

        val recipe = PadRecipe(pedals = board)
        val text = recipe.toJsonText()
        assertTrue("\"pedals\"" in text && "\"board\"" !in text, text)
        val back = PadRecipe.fromJsonText(text)
        assertEquals(board, back.pedals)
        assertNull(back.fx)
        assertNull(back.board)
        assertContentEquals(board.process(snare).samples, back.process(snare).samples, "the saved board renders as the board")

        assertFailsWith<JsonException> { PedalBoard.fromJsonText("""{"pedals":1}""") }
        assertFailsWith<JsonException> { PedalBoard.fromJsonText("""{"pedals":2,"picks":[]}""") }
        assertFailsWith<JsonException> { PedalBoard.fromJsonText("""{"pedals":1,"picks":[{"key":"sparkled","amount":1,"side":"pre"}]}""") }
        assertFailsWith<JsonException> { PedalBoard.fromJsonText("""{"pedals":1,"picks":[{"key":"driven","amount":1,"side":"pre"}]}""") }
        assertFailsWith<JsonException> { PedalBoard.fromJsonText("""{"pedals":1,"picks":[{"key":"driven","amount":1.5,"side":"amp"}]}""") }
        assertFailsWith<JsonException> { PedalBoard.fromJsonText("""{"pedals":1,"picks":[{"key":"driven","amount":1,"side":"amp","extra":0}]}""") }
    }

    @Test
    fun `a recipe without pedals is untouched - no key written, byte-identical audio`() {
        val chip = Treatments.apply("compressed", snare, 0.7f)
        assertFalse("\"pedals\"" in Json.write(chip.recipe))
        val parsed = PadRecipe.fromJsonValue(chip.recipe)
        assertNull(parsed.pedals)
        assertContentEquals(chip.snip.samples, parsed.process(snare).samples)
        assertEquals(PadRecipe.VERSION, 2, "the field is optional: no version bump")
    }

    @Test
    fun `an old treatment recipe whose name is a pedal reads as a one-pick board on its default side`() {
        val one = PedalBoard.fromCharacter("driven", 0.7f)
        assertNotNull(one)
        assertEquals(listOf(PedalBoard.Pick("driven", 0.7f, PedalSide.AMP)), one.picks)
        assertContentEquals(Treatments.apply("driven", snare, 0.7f).snip.samples, one.process(snare).samples)
        assertNull(PedalBoard.fromCharacter("washed", 0.7f), "a character that is not in the library is not a pedal")
    }

    @Test
    fun `the character door still fades driven alone - the chain rule reproduces the by-name rule`() {
        for (name in Treatments.names.filter { it != "driven" }) {
            val applied = Treatments.apply(name, snare, 1f).snip
            val direct = Treatments.chain(name, 1f).process(snare)
            assertContentEquals(direct.samples, applied.samples, "$name is faded now and was not before")
        }
    }
}
