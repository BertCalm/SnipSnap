package com.snipsnap.shell

import com.snipsnap.kit.KitPad
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `Retrim.of` — RE-TRIM's pure resolver — plus the tag it reads back (docs/RETRIM.md §1–2). */
class RetrimTest {

    private val temp: File = Files.createTempDirectory("retrim").toFile()
    private val snips = File(temp, SnipStore.DIR).apply { mkdirs() }

    @AfterTest
    fun cleanup() {
        temp.deleteRecursively()
    }

    private fun pad(source: Map<String, String>) = KitPad(slot = 2, sampleFile = "A02_Bass_01.wav", source = source)

    private fun tapeOnShelf(name: String = "snip_1000_BASS 5.wav"): File =
        File(snips, name).apply { writeBytes(ByteArray(64)) }

    @Test
    fun `tag writes the three keys and refuses a path or a backwards cut`() {
        assertEquals(
            mapOf("tapeFile" to "a.wav", "tapeIn" to "100", "tapeOut" to "250"),
            Retrim.tag("a.wav", 100, 250),
        )
        assertFailsWith<IllegalArgumentException> { Retrim.tag("dir/a.wav", 0, 10) }
        assertFailsWith<IllegalArgumentException> { Retrim.tag("a.wav", 10, 10) }
        assertFailsWith<IllegalArgumentException> { Retrim.tag("a.wav", -1, 10) }
    }

    @Test
    fun `ready - the tape is on the shelf and the cut comes back in frames`() {
        val tape = tapeOnShelf()
        val r = Retrim.of(pad(Retrim.tag(tape.name, 52_920, 71_442)), snips)
        val ready = assertIs<Retrim.Ready>(r)
        assertEquals(tape, ready.file)
        assertEquals(Retrim.Cut(52_920, 71_442), ready.cut)
        assertEquals("1.20–1.62s", ready.cut!!.label(44_100), "the PAD SHEET's provenance line, at the file's rate")
        assertEquals(18_522, ready.cut.lengthFrames)
    }

    @Test
    fun `no tape name is the mic or an import, unless the pad was chopped before tapes were remembered`() {
        val mic = Retrim.of(pad(emptyMap()), snips)
        assertEquals(Retrim.Refused(Copy.RETRIM_NO_TAPE), mic)

        val oldChop = Retrim.of(pad(mapOf("origin" to "chop", "sourceFrame" to "0", "lengthFrames" to "10")), snips)
        assertEquals(Retrim.Refused(Copy.RETRIM_OLD_CHOP), oldChop, "a different refusal because the fix is different")
    }

    @Test
    fun `a named tape that isn't on the shelf is gone - the pad keeps what it has`() {
        val r = Retrim.of(pad(Retrim.tag("snip_9_GONE.wav", 0, 10)), snips)
        assertEquals(Retrim.Refused(Copy.RETRIM_TAPE_GONE), r)
        // A name that would escape the shelf is never resolved as a path.
        val escaped = pad(mapOf("tapeFile" to "../kit.json", "tapeIn" to "0", "tapeOut" to "10"))
        assertEquals(Retrim.Refused(Copy.RETRIM_TAPE_GONE), Retrim.of(escaped, snips))
    }

    @Test
    fun `keys absent or nonsense but the file present opens the whole tape, never a refusal`() {
        val tape = tapeOnShelf()
        val bare = assertIs<Retrim.Ready>(Retrim.of(pad(mapOf("tapeFile" to tape.name)), snips))
        assertNull(bare.cut, "no cut = the whole file")
        val backwards = assertIs<Retrim.Ready>(
            Retrim.of(pad(mapOf("tapeFile" to tape.name, "tapeIn" to "500", "tapeOut" to "20")), snips),
        )
        assertNull(backwards.cut, "a hand-edited kit.json reads as the whole file")
        val garbage = assertIs<Retrim.Ready>(
            Retrim.of(pad(mapOf("tapeFile" to tape.name, "tapeIn" to "x", "tapeOut" to "20")), snips),
        )
        assertNull(garbage.cut)
    }

    @Test
    fun `a pad SNIPS to PAD tagged before RE-TRIM existed still opens its snip through the legacy file key`() {
        val tape = tapeOnShelf()
        val legacy = pad(mapOf("file" to tape.name, "capturedAtMillis" to "1000"))
        val ready = assertIs<Retrim.Ready>(Retrim.of(legacy, snips))
        assertEquals(tape, ready.file)
        assertNull(ready.cut, "that path always meant the whole snip")
    }

    @Test
    fun `provenanceTag with a frame count writes the cut as the whole snip`() {
        val tape = tapeOnShelf()
        val tag = SnipStore.provenanceTag(tape, frameCount = 44_100)
        assertEquals(tape.name, tag["file"])
        assertEquals("1000", tag["capturedAtMillis"])
        assertEquals(tape.name, tag[Retrim.FILE_KEY])
        assertEquals("0", tag[Retrim.IN_KEY])
        assertEquals("44100", tag[Retrim.OUT_KEY])
        val ready = assertIs<Retrim.Ready>(Retrim.of(pad(tag), snips))
        assertEquals(Retrim.Cut(0, 44_100), ready.cut)
    }

    @Test
    fun `TapeRef ofSnip only names a file on the SNIPS shelf and clamps a negative offset`() {
        val tape = tapeOnShelf()
        assertEquals(ChopReviewModel.TapeRef(tape.name, 0), ChopReviewModel.TapeRef.ofSnip(tape, -40, snips))
        assertEquals(ChopReviewModel.TapeRef(tape.name, 99), ChopReviewModel.TapeRef.ofSnip(tape, 99, snips))
        val kitSample = File(File(temp, "SomeKit"), "A01_Kick_01.wav")
        assertNull(ChopReviewModel.TapeRef.ofSnip(kitSample, 0, snips), "a kit sample TAPE fell back to is not a tape to go back to")
        // The directory itself, not its name: a kit that happens to be called "snips" is not the shelf.
        val lookalike = File(File(File(temp, "kits"), SnipStore.DIR), "A01_Kick_01.wav")
        assertNull(ChopReviewModel.TapeRef.ofSnip(lookalike, 0, snips))
        assertTrue(Retrim.of(pad(Retrim.tag(tape.name, 0, 1)), snips) is Retrim.Ready)
    }

    @Test
    fun `a layered or chained pad refuses before TAPE opens - both ride on the old file`() {
        val tape = tapeOnShelf()
        val tagged = pad(Retrim.tag(tape.name, 0, 10))
        val layered = tagged.copy(velocityLayers = listOf(com.snipsnap.kit.KitLayer("A02_Bass_01_v1.wav", 0, 63), com.snipsnap.kit.KitLayer("A02_Bass_01.wav", 64, 127)))
        assertEquals(Retrim.Refused(Copy.RETRIM_LAYERED), Retrim.of(layered, snips))
        val chained = tagged.copy(chain = com.snipsnap.kit.ChainInfo(listOf(0L, 100L, 200L), cycle = 3))
        assertEquals(Retrim.Refused(Copy.RETRIM_CHAINED), Retrim.of(chained, snips))
    }

    @Test
    fun `treatmentLeft names the card's word, sees SMEAR, and is null for an untreated pad`() {
        val bare = pad(emptyMap())
        assertNull(Retrim.treatmentLeft(bare))
        val crushed = bare.copy(
            recipe = com.snipsnap.json.JsonValue.Obj(
                mapOf("treatment" to com.snipsnap.json.JsonValue.Str("crush"), "amount" to com.snipsnap.json.JsonValue.Num(0.5)),
            ),
        )
        val word = Retrim.treatmentLeft(crushed)
        assertTrue(word != null && word == word.uppercase(), "the toast shouts: $word")
        val smeared = bare.copy(
            recipe = com.snipsnap.json.JsonValue.Obj(
                mapOf("verb" to com.snipsnap.json.JsonValue.Str("smear"), "amount" to com.snipsnap.json.JsonValue.Num(0.3)),
            ),
        )
        assertEquals("SMEAR", Retrim.treatmentLeft(smeared), "PadSheet.read can't see SMEAR on purpose; this must")
        assertEquals(
            "A02 RE-CUT. THE SMEAR STAYED WITH THE OLD ONE - IT'S IN THE BIN.",
            Copy.retrimLanded("A02", Retrim.treatmentLeft(smeared)),
        )
    }

    @Test
    fun `cut is the slice as CHOP would make it - clamped, DC-free, faded, never trimmed or normalised`() {
        val rate = 44_100
        val mono = com.snipsnap.audio.Snip(FloatArray(rate) { 0.2f + 0.1f * kotlin.math.sin(it * 0.05).toFloat() }, 1, rate)
        val piece = Retrim.cut(mono, 1_000 until 5_410)
        assertEquals(4_410, piece.frameCount, "no silence trim: the cut keeps its exact length")
        assertEquals(rate, piece.sampleRate)
        assertTrue(kotlin.math.abs(piece.samples.average()) < 0.01, "DC removed, as SLICE_CLEANUP does")
        // The head is faded (a click guard), not the raw 0.2 DC the source sits on.
        assertTrue(kotlin.math.abs(piece.samples[0]) < 0.01f, "click-guard fade at the head: ${piece.samples[0]}")
        val clamped = Retrim.cut(mono, -500 until rate + 500)
        assertEquals(rate, clamped.frameCount)
        assertFailsWith<IllegalArgumentException> { Retrim.cut(mono, 500 until 500) }
    }
}
