package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.KitPad
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * LABEL THIS HIT (`docs/WORKSHOP.md`, WS3): the calibration corpus's
 * on-ramp from the phone, and the one tool in the WORKSHOP that carries
 * audio.
 *
 * `reference/calibration/` scores the classifier against real captures
 * labelled by ear (`CalibrationCorpusTest`), and has held a README and no
 * data since the corpus was named: nothing on the phone could put a
 * labelled hit anywhere a desk could reach. This is the door. On a pad's
 * sheet, with the WORKSHOP open, tapping a class copies the pad's WAV into
 * `Calibration/` beside the kits — a folder with no `kit.json`, so the
 * shelf never lists it — under the corpus's own naming rule, the label
 * before the first underscore: `kick_Break Kit_A01.wav`. SEND HITS TO
 * BENCH ([pack]) zips that folder for the chooser, under its own button
 * and its own note, so SEND TO BENCH's promise (features and labels, never
 * audio) is never widened by a tool beside it.
 *
 * The copy is the pad's file as it is on disk: treated or not, the WAV is
 * what the ear labelled. A render is refused in words ([isRender]) — the
 * thresholds were tuned on renders, and the corpus README asks for
 * captures. Tapping a pad's current label takes the copy back out; a pad
 * has one label at a time, so a relabel never leaves the old file behind
 * to contradict the new one.
 */
object LabelledHits {

    /** The folder beside the kits. */
    const val DIR = "Calibration"

    /** The zip's stem; the stamp follows it, as the other hand-outs' do. */
    const val STEM = "SnipSnap Hits"

    const val MANIFEST_NAME = "manifest.txt"

    /** The harness the manifest says to run, once the WAVs are in [BenchExport.CALIBRATION_DIR]. */
    const val HARNESS_COMMAND = "./gradlew :audio:test --tests '*CalibrationCorpusTest*'"

    const val EXTENSION = "wav"

    /**
     * The corpus's own word for each class — `CalibrationCorpusTest`'s
     * `labels` map, the first of each pair of spellings it accepts.
     * UNKNOWN has none: NOT SURE is not a label, and [label] refuses it.
     */
    val WORDS: Map<DrumClass, String> = linkedMapOf(
        DrumClass.KICK to "kick",
        DrumClass.SNARE to "snare",
        DrumClass.CLAP to "clap",
        DrumClass.HAT_CLOSED to "hatclosed",
        DrumClass.HAT_OPEN to "hatopen",
        DrumClass.TOM to "tom",
        DrumClass.PERC to "perc",
        DrumClass.TONAL to "tonal",
        DrumClass.LOOP to "loop",
    )

    /** Every spelling the harness reads, back to its class — [WORDS] plus the corpus's two alternates. */
    private val CLASSES: Map<String, DrumClass> =
        WORDS.entries.associate { (dc, word) -> word to dc } + mapOf("closedhat" to DrumClass.HAT_CLOSED, "openhat" to DrumClass.HAT_OPEN)

    /** The chips on the pad sheet's BENCH box, two rows: CHOP's own chip order, without NOT SURE. */
    val ROWS: List<List<DrumClass>> = listOf(
        listOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN),
        listOf(DrumClass.TOM, DrumClass.PERC, DrumClass.TONAL, DrumClass.LOOP),
    )

    /** One labelled hit in the folder: the file, and what its name says — the label, the kit and the pad it came from. */
    data class Hit(val file: File, val label: DrumClass, val kit: String, val pad: String)

    /** What one pack wrote: the zip, and the hits in it. */
    data class Result(val file: File, val hits: List<Hit>)

    /** Where the labelled hits live under [shelfRoot]. */
    fun dir(shelfRoot: File): File = File(shelfRoot, DIR)

    /**
     * A pad whose recipe carries a synth patch — SYNTH's SEND TO PAD, a
     * starter kit, a DE-SAMPLEd capture. The bench knows its renders
     * already (every threshold was tuned on them), so the door refuses
     * these in words rather than salting the corpus with them.
     */
    fun isRender(pad: KitPad): Boolean = pad.recipe?.entries?.containsKey(PATCH_KEY) == true

    /** `kick_Break Kit_A01.wav`: the corpus's rule (the label before the first underscore), then the provenance. */
    fun fileName(label: DrumClass, kitName: String, padLabel: String): String {
        require(label in WORDS) { "$label is not a label the corpus reads" }
        return "${WORDS.getValue(label)}_${kitName}_$padLabel.$EXTENSION"
    }

    /**
     * A file in the folder as a [Hit], or null for one the harness would
     * not read either: not a WAV, or no known label before its first
     * underscore. The kit is everything between the label and the pad, so
     * a kit named with an underscore of its own still comes back whole.
     */
    fun parse(file: File): Hit? {
        if (!file.extension.equals(EXTENSION, ignoreCase = true)) return null
        val stem = file.nameWithoutExtension
        val label = CLASSES[stem.substringBefore('_').lowercase(Locale.ROOT)] ?: return null
        val rest = stem.substringAfter('_', "")
        return Hit(file, label, rest.substringBeforeLast('_', ""), rest.substringAfterLast('_'))
    }

    /** Every labelled hit in the folder, by name. */
    fun list(shelfRoot: File): List<Hit> =
        dir(shelfRoot).listFiles()?.filter { it.isFile }?.mapNotNull(::parse)?.sortedBy { it.file.name.lowercase(Locale.ROOT) }
            ?: emptyList()

    /** The label this pad carries in the folder, or null: the BENCH box's strip. */
    fun labelOf(shelfRoot: File, kitName: String, padLabel: String): DrumClass? =
        list(shelfRoot).firstOrNull { it.kit == kitName && it.pad == padLabel }?.label

    /**
     * LABEL THIS HIT: [wav] copied into the folder as [label]'s hit from
     * [kitName]'s [padLabel], any earlier label of the same pad taken out
     * first. A copy, never a move: the pad keeps its file.
     */
    fun label(shelfRoot: File, wav: File, kitName: String, padLabel: String, label: DrumClass): Hit {
        require(label in WORDS) { "$label is not a label the corpus reads" }
        require(wav.isFile) { "no such file to label: $wav" }
        val dir = dir(shelfRoot).apply { mkdirs() }
        unlabel(shelfRoot, kitName, padLabel)
        val out = File(dir, fileName(label, kitName, padLabel))
        wav.copyTo(out, overwrite = true)
        return Hit(out, label, kitName, padLabel)
    }

    /** The pad's copy out of the folder; true when there was one. The pad itself is untouched. */
    fun unlabel(shelfRoot: File, kitName: String, padLabel: String): Boolean =
        list(shelfRoot).filter { it.kit == kitName && it.pad == padLabel }.map { it.file.delete() }.any { it }

    /**
     * SEND HITS TO BENCH: every hit in the folder into
     * `<outDir>/SnipSnap Hits <stamp>.zip`, under `Calibration/` inside the
     * zip with a manifest beside it. Byte-stable for one folder and one
     * stamp, like the other hand-outs. Requires at least one hit; the app
     * asks [list] first, so the refusal can say so.
     */
    fun pack(shelfRoot: File, outDir: File, stamp: String): Result {
        val hits = list(shelfRoot)
        require(hits.isNotEmpty()) { "nothing to send: no hit is labelled under $shelfRoot" }
        outDir.mkdirs()
        val file = File(outDir, "$STEM $stamp.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                val entry = ZipEntry(name)
                entry.time = FIXED_TIME
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
            put(MANIFEST_NAME, manifest(stamp, hits).toByteArray(Charsets.UTF_8))
            for (hit in hits) put("$DIR/${hit.file.name}", hit.file.readBytes())
        }
        return Result(file, hits)
    }

    /** The manifest's text: the count by class, every file, and what to do with them — for the person at the desk. */
    fun manifest(stamp: String, hits: List<Hit>): String {
        val sb = StringBuilder()
        sb.append(STEM).append(' ').append(stamp).append('\n')
        val byClass = WORDS.keys.mapNotNull { dc -> hits.count { it.label == dc }.takeIf { it > 0 }?.let { "$it ${WORDS.getValue(dc)}" } }
        sb.append(count(hits.size, "hit", "hits")).append(", as audio, labelled by ear on the phone: ")
            .append(byClass.joinToString(", ")).append(".\n\n")
        for (hit in hits) sb.append("  ").append(hit.file.name).append('\n')
        sb.append('\n')
        sb.append("Copy the WAVs in ").append(DIR).append("/ into ").append(BenchExport.CALIBRATION_DIR).append(" and run\n")
        sb.append("  ").append(HARNESS_COMMAND).append('\n')
        sb.append("which classifies every one, prints the confusion matrix, and fails under 60%.\n")
        return sb.toString()
    }

    private fun count(n: Int, singular: String, plural: String): String = "$n ${if (n == 1) singular else plural}"

    /** The recipe key a synth patch lives under — `PadRecipe.toJsonValue`'s own, read here as text so this module stays ignorant of engines. */
    private const val PATCH_KEY = "patch"

    /** 2020-01-01T00:00:00 UTC — the fixed entry time every hand-out zip uses, so the bytes never depend on the clock. */
    private const val FIXED_TIME = 1_577_836_800_000L
}
