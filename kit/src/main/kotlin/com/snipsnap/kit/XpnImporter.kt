package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Reads an `.xpn` archive back into a kit folder — the receive half of
 * one-file kit sharing. [XpnPackager] sends; this lands what arrives on
 * the shelf.
 *
 * Deliberately forgiving about layout, because real archives are: the
 * program may sit anywhere in the tree (ours under `Programs/`, some
 * vendors' at the root or deeply foldered), samples are resolved by
 * **bare name anywhere in the archive** — the same search rule the MPC
 * itself applies — and instrument numbering is accepted in either base
 * (an `<Instrument number="0">` marks a 0-based file; vendor archives
 * count from 1). `[Previews]` content is ignored.
 *
 * Import is faithful, not clever: pads keep the level/pan/tune/mute-group
 * the program declares (clamped into model range), names come from the
 * sample stems, and no class or colour is invented — an imported pad is
 * [DrumClass.UNKNOWN] until someone (or the classifier, in the app) says
 * otherwise.
 */
object XpnImporter {

    data class ImportResult(
        val kit: Kit,
        val directory: File,
        /** Archive path of the program that was imported. */
        val programEntry: String,
    )

    fun import(xpnFile: File, destRoot: File, overwrite: Boolean = false): ImportResult {
        require(xpnFile.isFile) { "no such file: $xpnFile" }
        ZipFile(xpnFile).use { zip ->
            val entries = zip.entries().toList().filter { !it.isDirectory }

            val programEntry = entries
                .filter { it.name.endsWith(".xpm", ignoreCase = true) && !inPreviews(it.name) }
                .sortedBy { if (it.name.contains("Programs/")) 0 else 1 }
                .firstOrNull()
                ?: throw IllegalArgumentException("no .xpm program inside $xpnFile")

            val xml = zip.getInputStream(programEntry).readBytes().toString(Charsets.UTF_8)
            require(!Regex("<Program\\s+type=\"Keygroup\"").containsMatchIn(xml)) {
                "'${programEntry.name}' is a keygroup program - only drum programs import as kits"
            }

            val programName = Regex("<ProgramName>(.*?)</ProgramName>")
                .find(xml)?.groupValues?.get(1)?.let(::xmlUnescape)
            val kitName = (programName?.takeIf { Names.isMpcSafe(it) })
                ?: Names.sanitizeStem(File(programEntry.name).nameWithoutExtension)

            val instruments = parseInstruments(xml)
            require(instruments.isNotEmpty()) { "'${programEntry.name}' has no pads with samples" }

            // Base detection: a number-0 instrument anywhere in the file
            // (sampled or empty) marks a 0-based program — what we write,
            // hardware-verified; otherwise vendor 1-based. Checked against
            // the raw XML so an empty A01 can't disguise a 0-based file.
            val zeroBased = INSTRUMENT_NUMBER.findAll(xml).any { it.groupValues[1] == "0" }

            // Samples resolve by bare stem, case-insensitive, anywhere.
            val wavByStem = HashMap<String, java.util.zip.ZipEntry>()
            for (e in entries) {
                if (!e.name.endsWith(".wav", ignoreCase = true) || inPreviews(e.name)) continue
                wavByStem.putIfAbsent(File(e.name).nameWithoutExtension.lowercase(), e)
            }

            val referenced = instruments.flatMap { it.layers.map { l -> l.sampleName } }.distinct()
            val missing = referenced.filter { wavByStem[it.lowercase()] == null }
            require(missing.isEmpty()) {
                "samples referenced by the program are missing from the archive: " +
                    missing.joinToString(", ")
            }

            val destDir = File(destRoot, kitName)
            if (File(destDir, "kit.json").exists() && !overwrite) {
                throw IOException("kit already exists: $destDir (pass overwrite=true to replace it)")
            }
            destDir.mkdirs()
            for (stem in referenced) {
                val entry = wavByStem.getValue(stem.lowercase())
                zip.getInputStream(entry).use { src ->
                    File(destDir, "$stem.wav").outputStream().use { src.copyTo(it) }
                }
            }

            val pads = instruments.mapNotNull { inst ->
                val slot = if (zeroBased) inst.number + 1 else inst.number
                if (slot !in 1..128) return@mapNotNull null
                val ordered = inst.layers.sortedBy { it.velStart }
                val main = ordered.last()
                KitPad(
                    slot = slot,
                    sampleFile = "${main.sampleName}.wav",
                    displayName = main.sampleName,
                    drumClass = DrumClass.UNKNOWN,
                    level = inst.volume.coerceIn(0f, 1f),
                    pan = inst.pan.coerceIn(0f, 1f),
                    tuneCoarse = inst.tuneCoarse.coerceIn(-36, 36),
                    tuneFine = inst.tuneFine.coerceIn(-100, 100),
                    muteGroup = inst.muteGroup.coerceIn(0, 32),
                    oneShot = inst.oneShot,
                    source = mapOf("importedFrom" to xpnFile.name),
                    velocityLayers = if (ordered.size < 2) emptyList() else {
                        ordered.map { KitLayer("${it.sampleName}.wav", it.velStart, it.velEnd) }
                    },
                )
            }
            require(pads.isNotEmpty()) { "no pads landed in the 128-slot range" }

            val kit = Kit(kitName, pads)
            KitStore.save(kit, destDir)
            return ImportResult(kit, destDir, programEntry.name)
        }
    }

    // ---------- program parsing ----------

    private data class ParsedLayer(val sampleName: String, val velStart: Int, val velEnd: Int)

    private data class ParsedInstrument(
        val number: Int,
        val layers: List<ParsedLayer>,
        val volume: Float,
        val pan: Float,
        val tuneCoarse: Int,
        val tuneFine: Int,
        val muteGroup: Int,
        val oneShot: Boolean,
    )

    private val INSTRUMENT = Regex("<Instrument number=\"(\\d+)\">([\\s\\S]*?)</Instrument>")
    private val INSTRUMENT_NUMBER = Regex("<Instrument number=\"(\\d+)\">")
    private val LAYER = Regex("<Layer number=\"\\d+\">([\\s\\S]*?)</Layer>")

    private fun parseInstruments(xml: String): List<ParsedInstrument> =
        INSTRUMENT.findAll(xml).mapNotNull { m ->
            val body = m.groupValues[2]
            val layers = LAYER.findAll(body).mapNotNull { lm ->
                val lb = lm.groupValues[1]
                val name = tag(lb, "SampleName")?.let(::xmlUnescape)?.trim()
                if (name.isNullOrEmpty()) return@mapNotNull null
                ParsedLayer(
                    // Bare names are the rule, but strip a path if a
                    // nonconforming archive carries one anyway.
                    sampleName = File(name.replace('\\', '/')).nameWithoutExtension,
                    velStart = tag(lb, "VelStart")?.toIntOrNull() ?: 0,
                    velEnd = tag(lb, "VelEnd")?.toIntOrNull() ?: 127,
                )
            }.toList()
            if (layers.isEmpty()) return@mapNotNull null
            ParsedInstrument(
                number = m.groupValues[1].toInt(),
                layers = layers,
                volume = tag(body, "Volume")?.toFloatOrNull() ?: 0.707946f,
                pan = tag(body, "Pan")?.toFloatOrNull() ?: 0.5f,
                tuneCoarse = tag(body, "TuneCoarse")?.toIntOrNull() ?: 0,
                tuneFine = tag(body, "TuneFine")?.toIntOrNull() ?: 0,
                muteGroup = tag(body, "MuteGroup")?.toIntOrNull() ?: 0,
                oneShot = tag(body, "OneShot")?.equals("False", ignoreCase = true) != true,
            )
        }.toList()

    /** First occurrence of `<name>…</name>` in [body], or null. */
    private fun tag(body: String, name: String): String? =
        Regex("<$name>(.*?)</$name>").find(body)?.groupValues?.get(1)

    private fun inPreviews(path: String): Boolean =
        path.split('/', '\\').any { it.equals("[Previews]", ignoreCase = true) }

    private fun xmlUnescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&amp;", "&")
}
