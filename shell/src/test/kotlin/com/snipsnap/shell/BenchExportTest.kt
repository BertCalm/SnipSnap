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

/** SEND TO BENCH (`docs/WORKSHOP.md`): every teach log and cut rating on the shelf, merged, as one file for the calibration folder. */
class BenchExportTest {

    private val kick = TeachLog.Example(DrumClass.KICK, FeatureExtractor.extract(DrumSynth.kick()), machineSaid = DrumClass.TOM)
    private val hat = TeachLog.Example(DrumClass.HAT_CLOSED, FeatureExtractor.extract(DrumSynth.closedHat()), machineSaid = DrumClass.HAT_OPEN)
    private val snare = TeachLog.Example(DrumClass.SNARE, FeatureExtractor.extract(DrumSynth.snare()), machineSaid = DrumClass.CLAP)
    /** CONFIRM ALL's line: the label is the machine's own verdict. */
    private val confirmedKick = TeachLog.Example(DrumClass.KICK, FeatureExtractor.extract(DrumSynth.kick()), machineSaid = DrumClass.KICK)
    private val rated = CutRatings.Rating(
        stars = 4, mode = "HITS", count = 16, ear = "NORMAL", cut = "ON", grid = "OFF",
        rows = 12, tries = 1, merges = 0, splits = 1, corrected = 1, confirmed = 11, seconds = 8.2f, bpm = 92.3f,
    )

    private fun shelf(): File = java.nio.file.Files.createTempDirectory("bench").toFile()

    /** A kit folder under [root] at [path], with [examples] and [ratings] logged the way CHOP logs them. */
    private fun kitWithLog(root: File, path: String, examples: List<TeachLog.Example>, ratings: List<CutRatings.Rating> = emptyList()): File {
        val dir = File(root, path).apply { mkdirs() }
        File(dir, "kit.json").writeText("{}")
        TeachLog.append(File(dir, TeachLog.FILE_NAME), examples)
        CutRatings.append(File(dir, CutRatings.FILE_NAME), ratings)
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
            kitWithLog(root, "Quiet Kit", emptyList())
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
            kitWithLog(root, "Funk Kit", listOf(kick, hat), listOf(rated))
            kitWithLog(root, "Break Kit", listOf(snare))
            kitWithLog(root, "Silent Kit", emptyList())
            // A kit with nothing but a rating still gave something.
            kitWithLog(root, "Rated Kit", emptyList(), listOf(rated.copy(stars = 2)))
            // A kit DELETE > BIN moved under `.bin/<name>-<stamp>/`, its logs with it.
            kitWithLog(root, ".bin/Old Kit-1726000000000", listOf(hat, hat))

            val logs = BenchExport.gather(root)
            assertEquals(listOf(".bin/Old Kit-1726000000000", "Break Kit", "Funk Kit", "Rated Kit"), logs.map { it.path }, "path order, the bin's dot sorting first")
            assertEquals(listOf(2, 1, 2, 0), logs.map { it.examples.size })
            assertEquals(listOf(0, 0, 1, 1), logs.map { it.ratings.size })
            assertEquals(listOf(hat, hat), logs[0].examples, "what the bin kept is what leaves")
            assertEquals(listOf(rated), logs[2].ratings)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the zip holds the merged logs the harnesses read and a manifest that names each kit's share`() {
        val root = shelf()
        try {
            kitWithLog(root, "Funk Kit", listOf(kick, hat, confirmedKick), listOf(rated))
            kitWithLog(root, "Break Kit", listOf(snare))
            val out = File(root, "share")

            val result = BenchExport.pack(root, out, "2026-09-19 1735")
            assertEquals(File(out, "SnipSnap Bench 2026-09-19 1735.zip"), result.file)
            assertTrue(result.file.isFile)
            assertEquals(4, result.labels)
            assertEquals(3, result.corrections)
            assertEquals(1, result.confirmations)
            assertEquals(1, result.ratings)
            assertEquals(2, result.kits)

            val inside = entries(result.file)
            assertEquals(listOf(BenchExport.MANIFEST_NAME, BenchExport.LOG_NAME, BenchExport.RATINGS_NAME), inside.keys.toList(), "three entries, the manifest first")

            // The logs are exactly what their own writers would have written
            // for the merged lists, in path order - drop them in and the
            // harnesses read them.
            assertEquals(listOf(snare, kick, hat, confirmedKick), TeachLog.fromJsonl(inside.getValue(BenchExport.LOG_NAME)))
            assertEquals(listOf(rated), CutRatings.fromJsonl(inside.getValue(BenchExport.RATINGS_NAME)))

            val manifest = inside.getValue(BenchExport.MANIFEST_NAME)
            assertTrue(manifest.startsWith("SnipSnap Bench 2026-09-19 1735\n"), manifest)
            assertTrue("4 labels (3 corrections, 1 confirmation) and 1 cut rating from 2 kits" in manifest, manifest)
            assertTrue("     1       0  Break Kit\n" in manifest, manifest)
            assertTrue("     3       1  Funk Kit\n" in manifest, manifest)
            assertTrue("never audio" in manifest, "the consent line's promise, restated where the file lands: $manifest")
            assertTrue(BenchExport.CALIBRATION_DIR in manifest && BenchExport.HARNESS_COMMAND in manifest, manifest)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the notes ride along as a third file and as paste-ready lines in the manifest`() {
        val root = shelf()
        try {
            kitWithLog(root, "Break Kit", listOf(snare))
            val onPlay = BenchNotes.Note("2026-09-19 2107", "PLAY", "Break Kit", null, "hats feel late at 92")
            val onShelf = BenchNotes.Note("2026-09-19 2115", "SHELF", null, null, "first dub took about eight seconds")
            BenchNotes.append(BenchNotes.file(root), listOf(onPlay, onShelf))

            assertEquals(listOf(onPlay, onShelf), BenchExport.notes(root))
            val result = BenchExport.pack(root, File(root, "share"), "2026-09-19 1735")
            assertEquals(2, result.notes.size)
            val inside = entries(result.file)
            assertEquals(listOf(BenchExport.MANIFEST_NAME, BenchExport.LOG_NAME, BenchExport.NOTES_NAME), inside.keys.toList(), "no ratings file; the notes after the log")
            assertEquals(listOf(onPlay, onShelf), BenchNotes.fromJsonl(inside.getValue(BenchExport.NOTES_NAME)))
            val manifest = inside.getValue(BenchExport.MANIFEST_NAME)
            assertTrue("1 label (1 correction, 0 confirmations) and 0 cut ratings from 1 kit. 2 bench notes." in manifest, manifest)
            assertTrue("→ 2026-09-19 2107 · PLAY · Break Kit: hats feel late at 92\n" in manifest, "the note as a BENCH.md line: $manifest")
            assertTrue("docs/BENCH.md" in manifest, "says where the lines go: $manifest")
            assertTrue("never audio" in manifest, manifest)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the saved presets ride along as a fourth file and as roster lines in the manifest`() {
        val root = shelf()
        try {
            kitWithLog(root, "Break Kit", listOf(kick))
            val mine = com.snipsnap.synth.ThumpPatch("MY KICK", com.snipsnap.synth.ThumpVoice.KICK, linkedMapOf("TUNE" to 0.34f, "SWEEP" to 0.45f))
            UserPresets.save(root, mine, 1L)
            // A forgotten preset is in the file but not on the strip, so it
            // rides in the zip and stays out of the roster lines.
            UserPresets.save(root, com.snipsnap.synth.ThumpPatch("OLD KICK", com.snipsnap.synth.ThumpVoice.KICK, emptyMap()), 2L)
            UserPresets.forget(root, "THUMP", "KICK", "OLD KICK", 3L)
            val result = BenchExport.pack(root, File(root, "out"), "2026-09-20 0930")
            assertEquals(listOf(UserPresets.Saved(mine, 1L)), result.presets)
            val inside = entries(result.file)
            assertEquals(listOf(BenchExport.MANIFEST_NAME, BenchExport.LOG_NAME, BenchExport.PRESETS_NAME), inside.keys.toList(), "the presets after the logs and notes")
            assertEquals(UserPresets.file(root).readText(), inside.getValue(BenchExport.PRESETS_NAME), "the file byte for byte, not a re-serialization")
            val manifest = inside.getValue(BenchExport.MANIFEST_NAME)
            assertTrue("1 label (1 correction, 0 confirmations) and 0 cut ratings from 1 kit. 0 bench notes. 1 saved preset." in manifest, manifest)
            assertTrue("saved presets, as roster lines for ${UserPresets.ROSTER_DIR}" in manifest, manifest)
            assertTrue("ThumpPresets.kt\n  p(ThumpVoice.KICK, \"MY KICK\", \"TUNE\" to 0.34f, \"SWEEP\" to 0.45f),\n" in manifest, manifest)
            assertFalse("OLD KICK" in manifest, "a binned preset is not a roster line: $manifest")
            assertTrue("OLD KICK" in inside.getValue(BenchExport.PRESETS_NAME), "but the file carries it, bin and all")
            assertTrue("PresetsTest judges it" in manifest, "the manifest names the judge: $manifest")
            assertTrue(File("../" + UserPresets.ROSTER_DIR).isDirectory, "the folder the manifest names exists")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a shelf with nothing but a preset still has something to send`() {
        val root = shelf()
        try {
            UserPresets.save(root, com.snipsnap.synth.ThumpPatch("KICK 1", com.snipsnap.synth.ThumpVoice.KICK, emptyMap()), 1L)
            assertEquals(emptyList(), BenchExport.gather(root))
            val result = BenchExport.pack(root, File(root, "out"), "2026-09-20 0931")
            assertEquals(listOf(BenchExport.MANIFEST_NAME, BenchExport.PRESETS_NAME), entries(result.file).keys.toList())
            assertEquals(0, result.labels)
            assertEquals(1, result.presets.size)
            // A presets file this build cannot read is not a contribution, and never blocks the rest.
            UserPresets.file(root).writeText("not json")
            assertEquals(emptyList(), BenchExport.presets(root))
            assertTrue(runCatching { BenchExport.pack(root, File(root, "out2"), "2026-09-20 0932") }.isFailure, "nothing else on the shelf")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a shelf with nothing but notes still has something to send`() {
        val root = shelf()
        try {
            BenchNotes.append(BenchNotes.file(root), listOf(BenchNotes.Note("2026-09-19 2107", "PLAY", null, null, "tight enough to play")))
            assertEquals(emptyList(), BenchExport.gather(root), "no kit gave anything")
            val result = BenchExport.pack(root, File(root, "share"), "2026-09-19 1735")
            assertEquals(0, result.labels)
            assertEquals(0, result.kits)
            assertEquals(1, result.notes.size)
            assertEquals(listOf(BenchExport.MANIFEST_NAME, BenchExport.NOTES_NAME), entries(result.file).keys.toList())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a file with nothing to hold is not in the zip`() {
        val root = shelf()
        try {
            kitWithLog(root, "Rated Kit", emptyList(), listOf(rated))
            val result = BenchExport.pack(root, File(root, "share"), "2026-09-19 1735")
            assertEquals(0, result.labels)
            assertEquals(1, result.ratings)
            assertEquals(listOf(BenchExport.MANIFEST_NAME, BenchExport.RATINGS_NAME), entries(result.file).keys.toList(), "no empty overrides.jsonl")
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
            File(onlyTorn, CutRatings.FILE_NAME).writeText("{\"stars\":4,\"mo")

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
            kitWithLog(root, "Funk Kit", listOf(kick, hat), listOf(rated))
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
        assertTrue("1 label (1 correction, 0 confirmations) and 0 cut ratings from 1 kit." in one, one)
        val many = BenchExport.manifest(
            "2026-09-19 1735",
            listOf(BenchExport.Log("Break Kit", listOf(kick, confirmedKick), listOf(rated)), BenchExport.Log("Funk Kit", listOf(snare))),
        )
        assertTrue("3 labels (2 corrections, 1 confirmation) and 1 cut rating from 2 kits." in many, many)
    }

    /**
     * The manifest tells a person where to drop the files and what to run.
     * Both have to exist, or the instruction is a lie the moment it is
     * unzipped: the folder is read from `:shell`'s own project dir the way
     * `TeachLogTest` reaches it, and the harnesses it names are those tests.
     */
    @Test
    fun `the manifest's instructions point at a folder and harnesses that exist`() {
        assertTrue(File("../${BenchExport.CALIBRATION_DIR}").isDirectory, "reference/calibration/ is where the harnesses read from")
        for ((harness, store) in listOf("TeachLogTest" to "TeachLog", "CutRatingsTest" to "CutRatings")) {
            assertTrue(harness in BenchExport.HARNESS_COMMAND, "the manifest names $harness")
            val source = File("../shell/src/test/kotlin/com/snipsnap/shell/$harness.kt")
            assertTrue(source.isFile, "the harness the manifest names: $harness")
            // And each harness reads the very file the zip carries: the
            // store's own FILE_NAME, under the folder the manifest names,
            // spelled in the source the way the harness spells it.
            val text = source.readText(Charsets.UTF_8)
            assertTrue("reference/calibration/\$" + "{$store.FILE_NAME}" in text, "$harness reads the calibration folder's $store.FILE_NAME")
        }
        assertEquals(TeachLog.FILE_NAME, BenchExport.LOG_NAME)
        assertEquals(CutRatings.FILE_NAME, BenchExport.RATINGS_NAME)
    }

    @Test
    fun `the toasts say what left and what did not`() {
        assertEquals("14 LABELS AND 3 CUT RATINGS FROM 2 KITS ON ONE FILE. PICK WHERE IT GOES.", Copy.benchPacked(14, 3, 0, 0, 2))
        assertEquals("14 LABELS, 3 CUT RATINGS AND 2 NOTES FROM 2 KITS ON ONE FILE. PICK WHERE IT GOES.", Copy.benchPacked(14, 3, 2, 0, 2))
        assertEquals("1 LABEL FROM 1 KIT ON ONE FILE. PICK WHERE IT GOES.", Copy.benchPacked(1, 0, 0, 0, 1), "names only what the file holds")
        assertEquals("1 CUT RATING FROM 1 KIT ON ONE FILE. PICK WHERE IT GOES.", Copy.benchPacked(0, 1, 0, 0, 1))
        assertEquals("1 NOTE ON ONE FILE. PICK WHERE IT GOES.", Copy.benchPacked(0, 0, 1, 0, 0), "a note is nobody's kit")
        assertEquals("2 NOTES AND 1 PRESET ON ONE FILE. PICK WHERE IT GOES.", Copy.benchPacked(0, 0, 2, 1, 0), "a preset is nobody's kit either")
        assertTrue("SAVED PRESET" in Copy.SEND_TO_BENCH_NOTE, "the note under the button says the presets ride too: ${Copy.SEND_TO_BENCH_NOTE}")
        assertFalse("SENT" in Copy.benchPacked(3, 1, 0, 0, 2), "the chooser opening is not the file leaving")
        // The two refusals are two different answers, and the TEACH-off one
        // names the switch that fixes it.
        assertTrue(Copy.BENCH_EMPTY != Copy.BENCH_EMPTY_TEACH_OFF)
        assertTrue("TEACH THE MACHINE" in Copy.BENCH_EMPTY_TEACH_OFF, Copy.BENCH_EMPTY_TEACH_OFF)
        assertTrue("CHOP" in Copy.BENCH_EMPTY, "with TEACH on, the remedy is a chip: ${Copy.BENCH_EMPTY}")
        // The note under the button keeps the consent line true in so many
        // words: TEACH never sends, the button is the only way out.
        assertTrue("NEVER AUDIO" in Copy.SEND_TO_BENCH_NOTE, Copy.SEND_TO_BENCH_NOTE)
        assertTrue("NEVER SENDS ANYTHING BY ITSELF" in Copy.SEND_TO_BENCH_NOTE, Copy.SEND_TO_BENCH_NOTE)
        assertTrue("NOTE" in Copy.BENCH_EMPTY && "NOTE" in Copy.BENCH_EMPTY_TEACH_OFF, "both refusals name the remedy that needs no switch")
        for (line in listOf(Copy.benchPacked(3, 1, 1, 0, 2), Copy.BENCH_EMPTY, Copy.BENCH_EMPTY_TEACH_OFF, Copy.BENCH_FAILED, Copy.SEND_TO_BENCH_NOTE)) {
            assertEquals(line.uppercase(Locale.ROOT), line, "TapeOS shouts: $line")
            assertTrue(line.endsWith("."), "lands on a full stop: $line")
        }
    }

    @Test
    fun `the BENCH row's lines say what they log and when`() {
        assertEquals("3 CHIPS CONFIRMED: THE MACHINE HAD THEM RIGHT. LOGGED WHEN YOU SEND.", Copy.confirmedAll(3))
        assertEquals("1 CHIP CONFIRMED: THE MACHINE HAD IT RIGHT. LOGGED WHEN YOU SEND.", Copy.confirmedAll(1))
        assertEquals("CUTS RATED 4 OF 5. LOGGED WHEN YOU SEND, WITH THE BENCH'S SETTINGS.", Copy.cutsRated(4))
        assertTrue("WHEN YOU SEND" in Copy.BENCH_ROW_NOTE, "the note says when the log is written: ${Copy.BENCH_ROW_NOTE}")
        assertTrue("SETUP" in Copy.BENCH_ROW_TEACH_OFF, "the dim row names the switch: ${Copy.BENCH_ROW_TEACH_OFF}")
        for (line in listOf(Copy.confirmedAll(2), Copy.cutsRated(1), Copy.BENCH_ROW_NOTE, Copy.BENCH_ROW_TEACH_OFF)) {
            assertEquals(line.uppercase(Locale.ROOT), line, "TapeOS shouts: $line")
            assertTrue(line.endsWith("."), "lands on a full stop: $line")
        }
    }
}
