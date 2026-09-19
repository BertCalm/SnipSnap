package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitPad
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** LABEL THIS HIT and SEND HITS TO BENCH (`docs/WORKSHOP.md`, WS3): the calibration corpus's door from the phone. */
class LabelledHitsTest {

    private fun shelf(): File = java.nio.file.Files.createTempDirectory("hits").toFile()

    /** A kit folder with one rendered WAV on A01, the way a chop leaves it. */
    private fun kitWithWav(root: File, kitName: String, fileName: String = "A01_Kick_01.wav"): File {
        val dir = File(root, kitName).apply { mkdirs() }
        File(dir, "kit.json").writeText("{}")
        WavWriter.write(File(dir, fileName), DrumSynth.kick())
        return File(dir, fileName)
    }

    private fun entries(zip: File): Map<String, ByteArray> = ZipFile(zip).use { z ->
        z.entries().asSequence().associate { e -> e.name to z.getInputStream(e).readBytes() }
    }

    @Test
    fun `a label copies the pad's WAV under the corpus's own name, and reads back`() {
        val root = shelf()
        try {
            val wav = kitWithWav(root, "Break Kit")
            assertNull(LabelledHits.labelOf(root, "Break Kit", "A01"))
            assertEquals(emptyList(), LabelledHits.list(root), "no folder yet is no hits")

            val hit = LabelledHits.label(root, wav, "Break Kit", "A01", DrumClass.KICK)
            assertEquals(File(root, "Calibration/kick_Break Kit_A01.wav"), hit.file)
            assertTrue(hit.file.isFile)
            assertTrue(wav.readBytes().contentEquals(hit.file.readBytes()), "the copy is the pad's file, byte for byte")
            assertTrue(wav.isFile, "a copy, never a move")
            assertEquals(DrumClass.KICK, LabelledHits.labelOf(root, "Break Kit", "A01"))
            assertEquals(listOf(hit), LabelledHits.list(root))
            assertEquals("Break Kit", hit.kit)
            assertEquals("A01", hit.pad)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a relabel leaves one file for the pad, and unlabel takes it out`() {
        val root = shelf()
        try {
            val wav = kitWithWav(root, "Break Kit")
            LabelledHits.label(root, wav, "Break Kit", "A01", DrumClass.KICK)
            val snare = LabelledHits.label(root, wav, "Break Kit", "A01", DrumClass.SNARE)
            assertEquals(listOf(snare), LabelledHits.list(root), "the kick file is gone; two labels for one hit would contradict each other")
            assertEquals(DrumClass.SNARE, LabelledHits.labelOf(root, "Break Kit", "A01"))

            assertTrue(LabelledHits.unlabel(root, "Break Kit", "A01"))
            assertEquals(emptyList(), LabelledHits.list(root))
            assertFalse(LabelledHits.unlabel(root, "Break Kit", "A01"), "nothing to take out the second time")
            assertTrue(wav.isFile, "the pad's own file is untouched either way")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `NOT SURE is not a label, and a missing file is refused`() {
        val root = shelf()
        try {
            val wav = kitWithWav(root, "Break Kit")
            assertTrue(runCatching { LabelledHits.label(root, wav, "Break Kit", "A01", DrumClass.UNKNOWN) }.isFailure)
            assertTrue(runCatching { LabelledHits.fileName(DrumClass.UNKNOWN, "Break Kit", "A01") }.isFailure)
            assertTrue(runCatching { LabelledHits.label(root, File(root, "nope.wav"), "Break Kit", "A01", DrumClass.KICK) }.isFailure)
            assertEquals(emptyList(), LabelledHits.list(root), "a refusal writes nothing")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `parse reads the harness's rule, both hat spellings, and a kit named with an underscore`() {
        assertEquals(DrumClass.HAT_CLOSED, LabelledHits.parse(File("hatclosed_Kit_A03.wav"))?.label)
        assertEquals(DrumClass.HAT_CLOSED, LabelledHits.parse(File("closedhat_vinyl.wav"))?.label, "the corpus's alternate spelling")
        assertEquals(DrumClass.HAT_OPEN, LabelledHits.parse(File("OPENHAT_x.WAV"))?.label, "case and extension case do not matter")
        val underscored = LabelledHits.parse(File("kick_Break_Kit_A01.wav"))
        assertEquals("Break_Kit", underscored?.kit)
        assertEquals("A01", underscored?.pad)
        assertNull(LabelledHits.parse(File("notes.txt")), "not a WAV")
        assertNull(LabelledHits.parse(File("bass_x.wav")), "no such label")
        assertNull(LabelledHits.parse(File("kick.wav"))?.takeIf { it.pad != "" }, "a bare label has no pad to name")
    }

    @Test
    fun `a render is a pad whose recipe carries a patch`() {
        val bare = KitPad(slot = 1, sampleFile = "A01.wav")
        assertFalse(LabelledHits.isRender(bare))
        val treated = bare.copy(recipe = JsonValue.Obj(mapOf("recipe" to JsonValue.Num(1.0), "treatment" to JsonValue.Str("crush"))))
        assertFalse(LabelledHits.isRender(treated), "a treated capture is still a capture")
        val rendered = bare.copy(recipe = JsonValue.Obj(mapOf("recipe" to JsonValue.Num(1.0), "patch" to JsonValue.Obj(emptyMap()))))
        assertTrue(LabelledHits.isRender(rendered))
    }

    /**
     * The words this door writes are the words the harness reads, and the
     * rule it writes them by is the harness's own. Both are read from
     * `CalibrationCorpusTest`'s source, the same `../` reach the other
     * harness laws use, so a renamed label fails here before the corpus
     * fills with files nothing scores.
     */
    @Test
    fun `the labels and the naming rule are the harness's own`() {
        val harness = File("../audio/src/test/kotlin/com/snipsnap/audio/CalibrationCorpusTest.kt")
        assertTrue(harness.isFile, "the harness the manifest names")
        val src = harness.readText(Charsets.UTF_8)
        assertTrue("substringBefore('_')" in src, "the harness reads the label before the first underscore")
        for ((dc, word) in LabelledHits.WORDS) {
            assertTrue("\"$word\" to DrumClass.${dc.name}" in src, "the harness reads '$word' as $dc")
        }
        assertTrue("\"closedhat\" to DrumClass.HAT_CLOSED" in src && "\"openhat\" to DrumClass.HAT_OPEN" in src, "and both alternate spellings")
        assertTrue("CalibrationCorpusTest" in LabelledHits.HARNESS_COMMAND)
        assertTrue(File("../${BenchExport.CALIBRATION_DIR}").isDirectory, "the folder the manifest names")
    }

    @Test
    fun `the zip holds every hit under Calibration and a manifest that counts by class`() {
        val root = shelf()
        try {
            LabelledHits.label(root, kitWithWav(root, "Break Kit"), "Break Kit", "A01", DrumClass.KICK)
            LabelledHits.label(root, kitWithWav(root, "Funk Kit"), "Funk Kit", "A02", DrumClass.SNARE)
            LabelledHits.label(root, kitWithWav(root, "Soul Kit"), "Soul Kit", "A01", DrumClass.KICK)

            val result = LabelledHits.pack(root, File(root, "share"), "2026-09-19 1735")
            assertEquals(File(root, "share/SnipSnap Hits 2026-09-19 1735.zip"), result.file)
            assertEquals(3, result.hits.size)
            val inside = entries(result.file)
            assertEquals(
                listOf("manifest.txt", "Calibration/kick_Break Kit_A01.wav", "Calibration/kick_Soul Kit_A01.wav", "Calibration/snare_Funk Kit_A02.wav"),
                inside.keys.toList(),
                "the manifest first, then the hits by name",
            )
            assertTrue(File(root, "Calibration/kick_Break Kit_A01.wav").readBytes().contentEquals(inside.getValue("Calibration/kick_Break Kit_A01.wav")))
            val manifest = inside.getValue("manifest.txt").toString(Charsets.UTF_8)
            assertTrue(manifest.startsWith("SnipSnap Hits 2026-09-19 1735\n"), manifest)
            assertTrue("3 hits, as audio, labelled by ear on the phone: 2 kick, 1 snare." in manifest, manifest)
            assertTrue("  snare_Funk Kit_A02.wav\n" in manifest, manifest)
            assertTrue(BenchExport.CALIBRATION_DIR in manifest && LabelledHits.HARNESS_COMMAND in manifest, manifest)

            val again = LabelledHits.pack(root, File(root, "again"), "2026-09-19 1735").file.readBytes()
            assertTrue(result.file.readBytes().contentEquals(again), "the same folder and stamp pack to the same bytes")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an empty folder has nothing to send`() {
        val root = shelf()
        try {
            assertTrue(runCatching { LabelledHits.pack(root, File(root, "share"), "2026-09-19 1735") }.isFailure)
            assertFalse(File(root, "share").exists(), "and leaves nothing behind")
            assertEquals("1 hit, as audio, labelled by ear on the phone: 1 kick.", LabelledHits.manifest("s", listOf(LabelledHits.Hit(File("kick_K_A01.wav"), DrumClass.KICK, "K", "A01"))).lines()[1])
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the lines say what went where, and the strip is furniture`() {
        assertEquals("A01 LABELLED KICK. IN THE CALIBRATION FOLDER; SEND HITS TO BENCH CARRIES IT.", Copy.hitLabelled("A01", "KICK"))
        val gone = Copy.hitUnlabelled("A01")
        assertTrue("GONE" in gone && "UNTOUCHED" in gone, "a destructive line answers what is left: $gone")
        assertEquals("LABELLED KICK", Copy.hitStrip("KICK"))
        assertEquals("NOT LABELLED", Copy.hitStrip(null))
        assertFalse(Copy.hitStrip(null).endsWith("."), "a strip is furniture, not a sentence")
        assertEquals("12 HITS ON ONE FILE, AS AUDIO. PICK WHERE IT GOES.", Copy.hitsPacked(12))
        assertEquals("1 HIT ON ONE FILE, AS AUDIO. PICK WHERE IT GOES.", Copy.hitsPacked(1))
        assertTrue("SENDS SOUND" in Copy.SEND_HITS_NOTE, "the one button that sends audio says so: ${Copy.SEND_HITS_NOTE}")
        assertTrue("RENDERS" in Copy.HIT_IS_A_RENDER && "RENDERS" in Copy.HITS_BOX_NOTE, "the refusal and the note give the same reason")
        for (line in listOf(Copy.hitLabelled("A01", "KICK"), gone, Copy.hitsPacked(2), Copy.HITS_BOX_NOTE, Copy.HIT_IS_A_RENDER, Copy.SEND_HITS_NOTE, Copy.HITS_EMPTY, Copy.HITS_FAILED)) {
            assertEquals(line.uppercase(Locale.ROOT), line, "TapeOS shouts: $line")
            assertTrue(line.endsWith("."), "lands on a full stop: $line")
        }
    }
}
