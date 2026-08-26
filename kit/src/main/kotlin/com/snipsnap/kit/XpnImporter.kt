package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

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

    /** What a whole multi-program archive (a pack) yielded. */
    data class AllResult(
        val kits: List<ImportResult>,
        /** Program entry name to the reason it was skipped. */
        val skipped: List<Pair<String, String>>,
    )

    fun import(xpnFile: File, destRoot: File, overwrite: Boolean = false): ImportResult {
        require(xpnFile.isFile) { "no such file: $xpnFile" }
        ZipFile(xpnFile).use { zip ->
            val entries = zip.entries().toList().filter { !it.isDirectory }
            val programEntry = programEntries(entries).firstOrNull()
                ?: throw IllegalArgumentException("no .xpm program inside $xpnFile")
            return importProgram(zip, entries, programEntry, xpnFile, destRoot, overwrite)
        }
    }

    /**
     * Every drum program in the archive becomes its own kit folder — the
     * receive half of a multi-kit pack. Programs that refuse (keygroups,
     * missing samples) are skipped and named, not fatal.
     */
    fun importAll(xpnFile: File, destRoot: File, overwrite: Boolean = false): AllResult {
        require(xpnFile.isFile) { "no such file: $xpnFile" }
        ZipFile(xpnFile).use { zip ->
            val entries = zip.entries().toList().filter { !it.isDirectory }
            val programs = programEntries(entries)
            require(programs.isNotEmpty()) { "no .xpm program inside $xpnFile" }
            val kits = mutableListOf<ImportResult>()
            val skipped = mutableListOf<Pair<String, String>>()
            for (program in programs) {
                try {
                    kits += importProgram(zip, entries, program, xpnFile, destRoot, overwrite)
                } catch (e: IllegalArgumentException) {
                    skipped += program.name to (e.message ?: "refused")
                }
            }
            return AllResult(kits, skipped)
        }
    }

    private fun programEntries(entries: List<java.util.zip.ZipEntry>) = entries
        .filter { it.name.endsWith(".xpm", ignoreCase = true) && !inPreviews(it.name) }
        .sortedWith(compareBy({ if (it.name.contains("Programs/")) 0 else 1 }, { it.name }))

    private fun importProgram(
        zip: ZipFile,
        entries: List<java.util.zip.ZipEntry>,
        programEntry: java.util.zip.ZipEntry,
        xpnFile: File,
        destRoot: File,
        overwrite: Boolean,
    ): ImportResult {
        run {
            val xml = zip.getInputStream(programEntry).use {
                com.snipsnap.mpc3.LimitedRead.bytes(it, what = "program ${programEntry.name}")
            }.toString(Charsets.UTF_8)
            // A real DOM parse, not a regex: attribute order, extra attributes,
            // whitespace, comments, CDATA and numeric entities are all handled,
            // where the old pattern-matcher silently dropped pads it couldn't
            // shape-match. Parse refusal (not XML at all) is a typed reason.
            val program = try {
                parseProgram(xml)
            } catch (e: Exception) {
                if (e is IllegalArgumentException) throw e
                throw IllegalArgumentException("'${programEntry.name}' isn't a readable MPC program: ${e.message}")
            }
            require(!program.isKeygroup) {
                "'${programEntry.name}' is a keygroup program - only drum programs import as kits"
            }

            val kitName = (program.name?.takeIf { Names.isMpcSafe(it) })
                ?: Names.sanitizeStem(File(programEntry.name).nameWithoutExtension)

            val instruments = program.instruments
            require(instruments.isNotEmpty()) { "'${programEntry.name}' has no pads with samples" }

            // Base detection: a number-0 instrument anywhere in the file
            // (sampled or empty) marks a 0-based program — what we write,
            // hardware-verified; otherwise vendor 1-based.
            val zeroBased = program.zeroBased

            // Samples resolve by bare stem, case-insensitive, anywhere.
            val wavByStem = HashMap<String, java.util.zip.ZipEntry>()
            for (e in entries) {
                if (!e.name.endsWith(".wav", ignoreCase = true) || inPreviews(e.name)) continue
                wavByStem.putIfAbsent(File(e.name).nameWithoutExtension.lowercase(), e)
            }

            // Names were made safe basenames at parse time; SafePath.child is
            // the enforced invariant on the WAVs we write.
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
                    SafePath.child(destDir, "$stem.wav").outputStream().use {
                        com.snipsnap.mpc3.LimitedRead.copy(src, it, what = "sample $stem.wav")
                    }
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

    private data class ParsedProgram(
        val isKeygroup: Boolean,
        val name: String?,
        val instruments: List<ParsedInstrument>,
        val zeroBased: Boolean,
    )

    /**
     * The `.xpm` program, parsed as XML rather than pattern-matched. A DOM
     * parse is immune to the things a regex silently mishandles — attribute
     * order and extras, insignificant whitespace, comments, CDATA sections —
     * and resolves character entities (named *and* numeric) for free. The
     * factory disables DOCTYPE, so a hostile program can't mount an XXE or
     * billion-laughs attack through the parser.
     */
    private fun parseProgram(xml: String): ParsedProgram {
        val dbf = DocumentBuilderFactory.newInstance().apply {
            try {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            } catch (_: Exception) {
            }
            isExpandEntityReferences = false
            isNamespaceAware = false
        }
        // An XML declaration must sit at byte zero; a BOM or leading
        // whitespace (both seen in real files) would otherwise make the
        // parser reject the whole document. Strip them first.
        val cleaned = xml.removePrefix("﻿").trimStart()
        val doc = dbf.newDocumentBuilder()
            .parse(org.xml.sax.InputSource(java.io.StringReader(cleaned)))
        doc.documentElement.normalize()

        val programEl = doc.getElementsByTagName("Program").item(0) as? org.w3c.dom.Element
        val isKeygroup = programEl?.getAttribute("type")?.equals("Keygroup", ignoreCase = true) == true

        val name = firstText(doc.documentElement, "ProgramName")?.trim()?.ifBlank { null }

        val instrumentEls = elements(doc, "Instrument")
        val zeroBased = instrumentEls.any { it.getAttribute("number").trim() == "0" }

        val instruments = instrumentEls.mapNotNull { inst ->
            val number = inst.getAttribute("number").trim().toIntOrNull() ?: return@mapNotNull null
            val layers = childElements(inst, "Layer").mapNotNull { layer ->
                val raw = firstText(layer, "SampleName")?.trim()
                if (raw.isNullOrEmpty()) return@mapNotNull null
                ParsedLayer(
                    // Bare names are the rule; a legit subpath flattens, a
                    // traversal is refused (SafePath) - untrusted input.
                    sampleName = SafePath.basename(raw).substringBeforeLast('.'),
                    velStart = firstText(layer, "VelStart")?.trim()?.toIntOrNull() ?: 0,
                    velEnd = firstText(layer, "VelEnd")?.trim()?.toIntOrNull() ?: 127,
                )
            }
            if (layers.isEmpty()) return@mapNotNull null
            ParsedInstrument(
                number = number,
                layers = layers,
                volume = directText(inst, "Volume")?.toFloatOrNull() ?: 0.707946f,
                pan = directText(inst, "Pan")?.toFloatOrNull() ?: 0.5f,
                tuneCoarse = directText(inst, "TuneCoarse")?.toIntOrNull() ?: 0,
                tuneFine = directText(inst, "TuneFine")?.toIntOrNull() ?: 0,
                muteGroup = directText(inst, "MuteGroup")?.toIntOrNull() ?: 0,
                oneShot = directText(inst, "OneShot")?.equals("False", ignoreCase = true) != true,
            )
        }
        return ParsedProgram(isKeygroup, name, instruments, zeroBased)
    }

    // ---- DOM helpers -------------------------------------------------------

    private fun elements(doc: org.w3c.dom.Document, tag: String): List<org.w3c.dom.Element> {
        val nl = doc.getElementsByTagName(tag)
        return (0 until nl.length).mapNotNull { nl.item(it) as? org.w3c.dom.Element }
    }

    /** All descendant elements of [tag] under [parent]. */
    private fun childElements(parent: org.w3c.dom.Element, tag: String): List<org.w3c.dom.Element> {
        val nl = parent.getElementsByTagName(tag)
        return (0 until nl.length).mapNotNull { nl.item(it) as? org.w3c.dom.Element }
    }

    /** Text of the first descendant [tag] under [parent], entities resolved. */
    private fun firstText(parent: org.w3c.dom.Element, tag: String): String? =
        (parent.getElementsByTagName(tag).item(0) as? org.w3c.dom.Element)?.textContent

    /**
     * Text of a **direct** child [tag] — so an instrument's own `<Volume>`
     * is never confused with a `<Volume>` nested deeper (a layer's, say).
     */
    private fun directText(parent: org.w3c.dom.Element, tag: String): String? {
        val kids = parent.childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if (n is org.w3c.dom.Element && n.tagName == tag) return n.textContent?.trim()
        }
        return null
    }

    private fun inPreviews(path: String): Boolean =
        path.split('/', '\\').any { it.equals("[Previews]", ignoreCase = true) }
}
