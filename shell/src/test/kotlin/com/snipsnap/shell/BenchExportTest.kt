package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.FeatureExtractor
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SEND TO BENCH (`docs/WORKSHOP.md`): every teach log on the shelf, merged, as one file for the calibration folder. */
class BenchExportTest {

    private val kick = TeachLog.Example(DrumClass.KICK, FeatureExtractor.extract(DrumSynth.kick()), machineSaid = DrumClass.TOM)
    private val hat = TeachLog.Example(DrumClass.HAT_CLOSED, FeatureExtractor.extract(DrumSynth.closedHat()), machineSaid = DrumClass.HAT_OPEN)
    private val snare = TeachLog.Example(DrumClass.SNARE, FeatureExtractor.extract(DrumSynth.snare()), machineSaid = DrumClass.CLAP)

    private fun shelf(): File = java.nio.file.Files.createTempDirectory("bench").toFile()

    /** A kit folder under [root] at [path], with [examples] logged the way CHOP logs them. */
    private fun kitWithLog(root: File, path: String, examples: List<TeachLog.Example>): File {
        val dir = File(root, path).apply { mkdirs() }
        File(dir, "kit.json").writeText("{}")
        TeachLog.append(File(dir, TeachLog.FILE_NAME), examples)
        return dir
    }

    private fun entries(zip: File): Map<String, String> = ZipFile(zip).use { z ->
        z.entries().asSequence().associate { e -> e.name to z.getInputStream(e).readBytes().toString(Charsets.UTF_8) }
    }

    @Test
    fun `a shelf with no log, or no shelf at all, has nothing to send`() {
        val root = shelf()
        try {
            assertEquals(emptyList(), BenchExport.gather(File(root, "never made")))
            kitWithLog(root, "Quiet Kit", emptyList()).also { File(it, TeachLog.FILE_NAME).delete() }
            assertEquals(emptyList(), BenchExport.gather(root), "a kit that never logged is not a contribution")
            assertTrue(runCatching { BenchExport.pack(root, File(root, "out"), "2026-09-19 1735") }.isFailure, "pack refuses rather than writing an empty zip")
            assertFalse(File(root, "out").exists(), "and leaves nothing behind")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `every log under the shelf is gathered in path order, the bin included`() {
        val root = shelf()
        try {
            kitWithLog(root, "Funk Kit", listOf(kick, hat))
            kitWithLog(root, "Break Kit", listOf(snare))
            kitWithLog(root, "Silent Kit", emptyList())
            // A kit DELETE > BIN moved under `.bin/<name>-<stamp>/`, its log with it.
            kitWithLog(root, ".bin/Old Kit-1726000000000", listOf(hat, hat))

            val logs = BenchExport.gather(root)
            assertEquals(listOf(".bin/Old Kit-1726000000000", "Break Kit", "Funk Kit"), logs.map { it.path }, "path order, the bin's dot sorting first")
            assertEquals(listOf(2, 1, 2), logs.map { it.examples.size })
            assertEquals(listOf(hat, hat), logs[0].examples, "what the bin kept is what leaves")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the zip holds the merged log the harness reads and a manifest that names each kit`() {
        val root = shelf()
        try {
            kitWithLog(root, "Funk Kit", listOf(kick, hat))
            kitWithLog(root, "Break Kit", listOf(snare))
            val out = File(root, "share")

            val result = BenchExport.pack(root, out, "2026-09-19 1735")
            assertEquals(File(out, "SnipSnap Bench 2026-09-19 1735.zip"), result.file)
            assertTrue(result.file.isFile)
            assertEquals(3, result.corrections)
            assertEquals(2, result.kits)

            val inside = entries(result.file)
            assertEquals(listOf(BenchExport.MANIFEST_NAME, BenchExport.LOG_NAME), inside.keys.toList(), "two entries, the manifest first")

            // The log is exactly what TeachLog would have written for the
            // merged list, in path order - drop it in and the harness reads it.
            val back = TeachLog.fromJsonl(inside.getValue(BenchExport.LOG_NAME))
            assertEquals(listOf(snare, kick, hat), back)

            val manifest = inside.getValue(BenchExport.MANIFEST_NAME)
            assertTrue(manifest.startsWith("SnipSnap Bench 2026-09-19 1735\n"), manifest)
            assertTrue("3 corrections from 2 kits" in manifest, manifest)
            assertTrue("     1  Break Kit\n" in manifest, manifest)
            assertTrue("     2  Funk Kit\n" in manifest, manifest)
            assertTrue("never audio" in manifest, "the consent line's promise, restated where the file lands: $manifest")
            assertTrue(BenchExport.CALIBRATION_DIR in manifest && BenchExport.HARNESS_COMMAND in manifest, manifest)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a torn line is dropped on the way out, and a log of nothing but torn lines is not a contribution`() {
        val root = shelf()
        try {
            val torn = kitWithLog(root, "Killed Kit", listOf(kick))
            File(torn, TeachLog.FILE_NAME).appendText("{\"label\":\"KICK\",\"mach")
            val onlyTorn = kitWithLog(root, "Worse Kit", emptyList())
            File(onlyTorn, TeachLog.FILE_NAME).writeText("{\"label\":\"SNA")

            val logs = BenchExport.gather(root)
            assertEquals(listOf("Killed Kit"), logs.map { it.path })
            assertEquals(listOf(kick), logs[0].examples)

            val result = BenchExport.pack(root, File(root, "share"), "2026-09-19 1735")
            val text = entries(result.file).getValue(BenchExport.LOG_NAME)
            assertEquals(1, text.trim().lines().size, "one clean line; the torn one never leaves the phone")
            assertEquals(listOf(kick), TeachLog.fromJsonl(text))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the same shelf and the same stamp pack to the same bytes`() {
        val root = shelf()
        try {
            kitWithLog(root, "Funk Kit", listOf(kick, hat))
            val a = BenchExport.pack(root, File(root, "a"), "2026-09-19 1735").file.readBytes()
            val b = BenchExport.pack(root, File(root, "b"), "2026-09-19 1735").file.readBytes()
            assertTrue(a.contentEquals(b), "a fixed entry time and a fixed order: nothing in the zip depends on the clock")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the manifest counts one of something in the singular`() {
        val one = BenchExport.manifest("2026-09-19 1735", listOf(BenchExport.Log("Break Kit", listOf(kick))))
        assertTrue("1 correction from 1 kit." in one, one)
        val many = BenchExport.manifest("2026-09-19 1735", listOf(BenchExport.Log("Break Kit", listOf(kick, hat)), BenchExport.Log("Funk Kit", listOf(snare))))
        assertTrue("3 corrections from 2 kits." in many, many)
    }

    /**
     * The manifest tells a person where to drop the file and what to run.
     * Both have to exist, or the instruction is a lie the moment it is
     * unzipped: the folder is read from `:shell`'s own project dir the way
     * `TeachLogTest` reaches it, and the harness it names is that test.
     */
    @Test
    fun `the manifest's instructions point at a folder and a harness that exist`() {
        assertTrue(File("../${BenchExport.CALIBRATION_DIR}").isDirectory, "reference/calibration/ is where TeachLogTest reads ${TeachLog.FILE_NAME} from")
        assertTrue("TeachLogTest" in BenchExport.HARNESS_COMMAND)
        assertTrue(File("../shell/src/test/kotlin/com/snipsnap/shell/TeachLogTest.kt").isFile, "the harness the manifest names")
        // And that harness reads the very file name the zip carries.
        val harness = File("../shell/src/test/kotlin/com/snipsnap/shell/TeachLogTest.kt").readText(Charsets.UTF_8)
        assertTrue("reference/calibration/\${TeachLog.FILE_NAME}" in harness, "TeachLogTest reads the calibration folder's ${TeachLog.FILE_NAME}")
    }

    @Test
    fun `the toasts say what left and what did not`() {
        assertEquals("3 CORRECTIONS FROM 2 KITS ON ONE FILE. PICK WHERE IT GOES.", Copy.benchPacked(3, 2))
        assertEquals("1 CORRECTION FROM 1 KIT ON ONE FILE. PICK WHERE IT GOES.", Copy.benchPacked(1, 1))
        assertFalse("SENT" in Copy.benchPacked(3, 2), "the chooser opening is not the file leaving")
        // The two refusals are two different answers, and the TEACH-off one
        // names the switch that fixes it.
        assertTrue(Copy.BENCH_EMPTY != Copy.BENCH_EMPTY_TEACH_OFF)
        assertTrue("TEACH THE MACHINE" in Copy.BENCH_EMPTY_TEACH_OFF, Copy.BENCH_EMPTY_TEACH_OFF)
        assertTrue("CHOP" in Copy.BENCH_EMPTY, "with TEACH on, the remedy is a correction: ${Copy.BENCH_EMPTY}")
        // The note under the button keeps the consent line true in so many
        // words: TEACH never sends, the button is the only way out.
        assertTrue("NEVER AUDIO" in Copy.SEND_TO_BENCH_NOTE, Copy.SEND_TO_BENCH_NOTE)
        assertTrue("NEVER SENDS ANYTHING BY ITSELF" in Copy.SEND_TO_BENCH_NOTE, Copy.SEND_TO_BENCH_NOTE)
        for (line in listOf(Copy.benchPacked(3, 2), Copy.BENCH_EMPTY, Copy.BENCH_EMPTY_TEACH_OFF, Copy.BENCH_FAILED, Copy.SEND_TO_BENCH_NOTE)) {
            assertEquals(line.uppercase(Locale.ROOT), line, "TapeOS shouts: $line")
            assertTrue(line.endsWith("."), "lands on a full stop: $line")
        }
    }
}
