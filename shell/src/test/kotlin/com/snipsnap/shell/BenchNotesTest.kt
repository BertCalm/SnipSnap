package com.snipsnap.shell

import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** BENCH NOTES (`docs/WORKSHOP.md`, WS4): the tester's own words, stamped with where they were said. */
class BenchNotesTest {

    private val onPlay = BenchNotes.Note("2026-09-19 2107", "PLAY", "Break Kit", null, "hats feel late at 92, maybe the choke fade")
    private val onSheet = BenchNotes.Note("2026-09-19 2112", "KIT", "Break Kit", "A03", "the open hat rings past the bar")
    private val onShelf = BenchNotes.Note("2026-09-19 2115", "SHELF", null, null, "first dub took about eight seconds")

    @Test
    fun `a note carries its context in one line, the parts that are known in order`() {
        assertEquals("PLAY · Break Kit", onPlay.context)
        assertEquals("KIT · Break Kit · A03", onSheet.context)
        assertEquals("SHELF", onShelf.context, "no kit and no pad is just the screen")
        assertEquals(onSheet.context, BenchNotes.context("KIT", "Break Kit", "A03"), "the dialog's stamp is the note's own")
        assertEquals("→ 2026-09-19 2112 · KIT · Break Kit · A03: the open hat rings past the bar", BenchNotes.render(onSheet))
        assertTrue(BenchNotes.render(onShelf).startsWith("→ 2026-09-19 2115 · SHELF: "), BenchNotes.render(onShelf))
    }

    @Test
    fun `the stamp read at the tap is the note's own context and the note KEEP writes`() {
        val stamp = BenchNotes.Stamp("2026-09-19 2112", "KIT", "Break Kit", "A03")
        assertEquals(onSheet.context, stamp.context, "the dialog shows exactly what the note will be filed under")
        assertEquals(onSheet, stamp.note("the open hat rings past the bar"))
        assertEquals("SHELF", BenchNotes.Stamp("2026-09-19 2115", "SHELF", null, null).context)
    }

    @Test
    fun `a note is one line, and an empty one is not a note`() {
        assertEquals("hats late  and the snare early".replace("  ", " "), BenchNotes.oneLine("  hats late \n and\tthe snare early \n"))
        assertEquals("", BenchNotes.oneLine(" \n\t "))
        assertTrue(runCatching { BenchNotes.Note("2026-09-19 2107", "PLAY", null, null, "   ") }.isFailure, "KEEP is dim for a blank note, and the store refuses one too")
    }

    @Test
    fun `notes round-trip jsonl with their nulls, and a torn line is dropped`() {
        val jsonl = BenchNotes.toJsonl(listOf(onPlay, onSheet, onShelf))
        assertEquals(3, jsonl.trim().lines().size, "one line per note")
        assertEquals(listOf(onPlay, onSheet, onShelf), BenchNotes.fromJsonl(jsonl))
        val torn = jsonl + "{\"at\":\"2026-09-19 2120\",\"scr"
        assertEquals(3, BenchNotes.fromJsonl(torn).size, "a killed append loses only its own line")
        val quoted = BenchNotes.Note("2026-09-19 2130", "CHOP", "Kit \"Two\"", null, "said \"snap\" and a \\ backslash")
        assertEquals(listOf(quoted), BenchNotes.fromJsonl(BenchNotes.toJsonl(listOf(quoted))), "quotes and backslashes survive")
    }

    @Test
    fun `append accumulates under the shelf and read of nothing is empty`() {
        val root = java.nio.file.Files.createTempDirectory("notes").toFile()
        try {
            val file = BenchNotes.file(root)
            assertEquals(File(root, "Bench/notes.jsonl"), file)
            assertEquals(emptyList(), BenchNotes.read(file))
            BenchNotes.append(file, listOf(onPlay))
            BenchNotes.append(file, listOf(onSheet, onShelf))
            BenchNotes.append(file, emptyList()) // no-op
            assertEquals(listOf(onPlay, onSheet, onShelf), BenchNotes.read(file), "in the order they were taken")
            assertEquals(BenchNotes.render(onPlay) + "\n" + BenchNotes.render(onSheet) + "\n" + BenchNotes.render(onShelf) + "\n", BenchNotes.renderAll(BenchNotes.read(file)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the lines say where the note went`() {
        assertEquals("NOTED ON PLAY. SEND TO BENCH CARRIES IT.", Copy.noted("PLAY"))
        assertTrue("SEND TO BENCH" in Copy.NOTE_PROMPT, "the prompt says how the words leave: ${Copy.NOTE_PROMPT}")
        for (line in listOf(Copy.noted("KIT"), Copy.NOTE_PROMPT, Copy.NOTE_FAILED)) {
            assertEquals(line.uppercase(Locale.ROOT), line, "TapeOS shouts: $line")
            assertTrue(line.endsWith("."), "lands on a full stop: $line")
        }
        assertFalse("SENT" in Copy.noted("PLAY"), "kept is not sent")
    }
}
