package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportOutcome
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.xpm.PadNoteMap
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/** The same dry, finite recipes reach the phone starter and both MPC program formats. */
object TidalKitGenerator {
    const val KIT_NAME = "SnipSnap Tidal Kit"
    const val ARCHIVE_NAME = "tidal-kit.zip"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "supply at most one TIDAL kit output directory" }
        val archive = generate(File(args.firstOrNull() ?: "../testkit/tidal-kit").canonicalFile)
        println("TIDAL kit: ${archive.absolutePath} (${archive.length()} bytes)")
    }

    fun generate(destination: File): File {
        require(!destination.exists()) { "TIDAL kit output already exists: $destination; choose a new directory" }
        val work = Files.createTempDirectory("tidal-kit-source-").toFile()
        try {
            val arranged = SynthKits.tidal()
            check(arranged.size == 16 && arranged.all { it != null })
            val kit = KitAssembler.assembleArranged(KIT_NAME, arranged, work, key = KeySpec.parse("Cminpent"))
            check(kit.pads.map { it.slot } == (1..16).toList())
            check(kit.pads.all { it.oneShot && it.drumClass == DrumClass.LOOP &&
                it.chain == null && it.velocityLayers.isEmpty() && it.muteGroup == 0 })
            check(!Preflight.check(kit, work).blocked())
            val result = Mpc3Exporter.exportTrack(kit, work, destination, mpc2Twin = true)
            val data = result.samples.first().parentFile
            val twin = File(data, "$KIT_NAME.xpm")
            val restored = KitStore.load(data)
            check(restored.copy(pads = restored.pads.map { it.copy(source = it.source - "rawSha256") }) == kit)
            val findings = listOf(
                ExportOutcome(ExportFormat.MPC3_TRACK, result.program, data, result.findings),
                ExportOutcome(ExportFormat.PROGRAM_FOLDER, twin, null, result.findings),
            ).flatMap { ExportReadBack.verify(kit, it) }
            check(!findings.blocked()) { "TIDAL kit read-back failed: $findings" }
            File(destination, "read-back.txt").writeText(findings.joinToString("\n") { "${it.severity}: ${it.message}" } + "\n")

            // Long-sample classification must not turn this finite encounter into a loop.
            val instruments = (Mpc3Project.read(result.program).drumPrograms().single()
                .getValue("instruments") as JsonValue.Arr).items
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val xml = factory.newDocumentBuilder().parse(twin)
            for (tag in listOf("SliceLoop", "SliceLoopStart")) {
                val entries = xml.getElementsByTagName(tag)
                check((0 until entries.length).all { entries.item(it).textContent == "0" }) { "TIDAL export enabled $tag" }
            }
            File(destination, "pads.tsv").writeText(buildString {
                append("pad\tname\tmidi\tvelocity\tseed\tmodel\tAPPROACH\tPASSAGE\tTILT\tBIND\tTAIL\tHOLD\tsample\n")
                for ((index, pad) in kit.pads.withIndex()) {
                    val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
                    val patch = recipe.patch as TidalPatch
                    check(patch.model == 2 && patch.macros.getValue("HOLD") == 0f)
                    check(recipe.fx == null && recipe.board == null && recipe.pedals == null && recipe.treatment == null)
                    val exportedPad = restored.pads.single { it.slot == pad.slot }
                    check(exportedPad.recipe == pad.recipe && exportedPad.oneShot)
                    val wav = File(data, pad.sampleFile)
                    check(wav.readBytes().contentEquals(File(work, pad.sampleFile).readBytes()))
                    check(exportedPad.source["rawSha256"] == sha256(wav))
                    val instrument = instruments[index].obj()
                    check(instrument.getValue("triggerMode").int() == 0)
                    val layer = (instrument.getValue("layersv") as JsonValue.Arr).items.first().obj()
                    check(layer.getValue("loop") == JsonValue.Bool(false))
                    val slice = layer.getValue("sliceInfo").obj()
                    check(slice.getValue("LoopMode").int() == 0 && slice.getValue("LoopStart").int() == 0)
                    val recipeFile = File(destination, "recipes/${PadNoteMap.labelForPad(pad.slot)}.json")
                    recipeFile.parentFile.mkdirs()
                    recipeFile.writeText(recipe.toJsonText() + "\n")
                    check(PadRecipe.fromJsonText(recipeFile.readText()) == recipe)
                    append("${PadNoteMap.labelForPad(pad.slot)}\t${patch.name}\t${patch.midi}\t${patch.velocity}\t${patch.seed}\t${patch.model}")
                    for (macro in listOf("APPROACH", "PASSAGE", "TILT", "BIND", "TAIL", "HOLD")) append("\t${patch.macros.getValue(macro)}")
                    append("\t${pad.sampleFile}\n")
                }
            })
            File(destination, "README.txt").writeText("""
                TIDAL / finite PAIR and STREAM encounters

                Sixteen dry model-2 one-shot pads, in C minor pentatonic.
                A01-A05: PAIR C3, Eb3, F3, G3, Bb3; A06-A08: the three PAIR starters.
                A09-A13: STREAM at the same notes; A14-A16: the three STREAM starters.

                MPC 3: copy this folder intact and load $KIT_NAME.xtd.
                MPC 2: open the TrackData folder and load $KIT_NAME.xpm.
                kit.json and recipes/ retain note, velocity, exact seed, source model and all controls.
                pads.tsv records the same performances in a readable table.

                Every encounter is 9.8 seconds with a finite source and passive release.
                LOOP is filing metadata for a long sample. Playback is one-shot, with no sustain
                loop or held reconstruction. HOLD stays zero. There are no finishing effects.
                read-back.txt verifies SnipSnap's file readers; target MPC hardware listening remains separate.
            """.trimIndent() + "\n")
            val files = destination.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(destination).invariantSeparatorsPath }.toList()
            File(destination, "SHA256SUMS.txt").writeText(files.joinToString("\n") {
                "${sha256(it)}  ${it.relativeTo(destination).invariantSeparatorsPath}"
            } + "\n")
            val archive = File(destination, ARCHIVE_NAME)
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                for (file in destination.walkTopDown().filter { it.isFile && it != archive }
                    .sortedBy { it.relativeTo(destination).invariantSeparatorsPath }) {
                    zip.putNextEntry(ZipEntry("$KIT_NAME/" + file.relativeTo(destination).invariantSeparatorsPath).also { it.time = 0L })
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            return archive
        } finally {
            work.deleteRecursively()
        }
    }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
}
