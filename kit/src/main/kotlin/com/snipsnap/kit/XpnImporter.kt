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

    /**
     * A spend-down budget shared across many sample writes that together
     * bound the total one untrusted archive may cause to land on disk —
     * each sample is already capped on its own by [com.snipsnap.mpc3.LimitedRead]'s
     * per-entry ceiling, but a program with hundreds of pads (or, from
     * [KitBackup], a backup of many programs) would otherwise multiply that
     * ceiling by however many samples it declares. One [WriteBudget] shared
     * across a whole [import]/[importAll] call — or, from [KitBackup], across
     * a whole restore — bounds the operation itself, the same "one budget
     * for the whole" rule [com.snipsnap.shell.ShelfImport.unzipSafely] already
     * holds the MPC-track ZIP path to.
     */
    class WriteBudget(val max: Long) {
        init {
            require(max >= 0) { "a budget cannot be negative: $max" }
        }
        private var spent = 0L
        val remaining: Long get() = (max - spent).coerceAtLeast(0L)

        /**
         * [n] is always a byte count a caller actually wrote — never
         * negative — but this is part of a security boundary, so it does
         * not trust that: a negative [n] would shrink [spent] and hand back
         * budget that was never earned, and enough additions could in
         * principle overflow a [Long] the same way. Refuse the first,
         * saturate at [Long.MAX_VALUE] against the second, so a caller
         * mistake can only ever make this budget stricter, not looser.
         */
        fun spend(n: Long) {
            require(n >= 0) { "spent a negative byte count: $n" }
            spent = if (spent > Long.MAX_VALUE - n) Long.MAX_VALUE else spent + n
        }
    }

    /** The default cap for one archive's worth of sample writes — [com.snipsnap.shell.ShelfImport]'s own ZIP budget, held here too since `:kit` doesn't depend on `:shell`. */
    const val DEFAULT_BUDGET_BYTES: Long = 512L * 1024 * 1024

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

    fun import(
        xpnFile: File,
        destRoot: File,
        overwrite: Boolean = false,
        budget: WriteBudget = WriteBudget(DEFAULT_BUDGET_BYTES),
    ): ImportResult {
        require(xpnFile.isFile) { "no such file: $xpnFile" }
        ZipFile(xpnFile).use { zip ->
            val entries = zip.entries().toList().filter { !it.isDirectory }
            val programEntry = programEntries(entries).firstOrNull()
                ?: throw IllegalArgumentException("no .xpm program inside $xpnFile")
            return importProgram(zip, entries, programEntry, xpnFile, destRoot, overwrite, budget)
        }
    }

    /**
     * Every drum program in the archive becomes its own kit folder — the
     * receive half of a multi-kit pack. Programs that refuse (keygroups,
     * missing samples) are skipped and named, not fatal. [budget] is one
     * ceiling shared across every program in the archive, not reset per
     * program — the point is bounding what the *archive* can cause, not
     * each program's slice of it.
     */
    fun importAll(
        xpnFile: File,
        destRoot: File,
        overwrite: Boolean = false,
        budget: WriteBudget = WriteBudget(DEFAULT_BUDGET_BYTES),
    ): AllResult {
        require(xpnFile.isFile) { "no such file: $xpnFile" }
        ZipFile(xpnFile).use { zip ->
            val entries = zip.entries().toList().filter { !it.isDirectory }
            val programs = programEntries(entries)
            require(programs.isNotEmpty()) { "no .xpm program inside $xpnFile" }
            val kits = mutableListOf<ImportResult>()
            val skipped = mutableListOf<Pair<String, String>>()
            for (program in programs) {
                try {
                    kits += importProgram(zip, entries, program, xpnFile, destRoot, overwrite, budget)
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
        budget: WriteBudget,
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

            // Every write lands in a fresh staging folder first, moved into
            // destDir only once the whole program - every sample, kit.json -
            // is down safely; on any failure (budget, a bad zip entry, a
            // full disk) the staging folder is simply deleted, never
            // destDir. A program refused partway used to leave WAVs sitting
            // in destDir with no kit.json to name them - invisible to the
            // shelf's own listing, but not to the disk - and importAll
            // moving on to the next program in the archive meant a refused
            // one could still leave debris behind.
            destRoot.mkdirs()
            val staging = java.nio.file.Files.createTempDirectory(destRoot.toPath(), "xpn-import-").toFile()
            try {
                for (stem in referenced) {
                    val entry = wavByStem.getValue(stem.lowercase())
                    val out = SafePath.child(staging, "$stem.wav")
                    try {
                        zip.getInputStream(entry).use { src ->
                            out.outputStream().use {
                                // The per-entry ceiling alone caps one sample; a
                                // program with hundreds of pads (or importAll's
                                // whole archive) would otherwise multiply it by
                                // however many samples it declares — [budget]
                                // bounds the sum instead.
                                com.snipsnap.mpc3.LimitedRead.copy(src, it, limit = budget.remaining, what = "sample $stem.wav")
                            }
                        }
                    } catch (e: com.snipsnap.mpc3.LimitedRead.TooLargeException) {
                        // copy() throws before writing the chunk that would
                        // overrun, but earlier chunks in this same call already
                        // landed on disk - importAll skips a program that throws
                        // and moves on to the next, so an unspent partial write
                        // here would let every remaining program in an archive
                        // repeat it against the same unchanged budget.remaining,
                        // multiplying exactly what this budget exists to bound.
                        val partial = out.length()
                        out.delete()
                        budget.spend(partial)
                        throw com.snipsnap.mpc3.LimitedRead.TooLargeException(
                            "'${xpnFile.name}' writes past ${budget.max / (1024 * 1024)} MB of samples at '$stem.wav' - refused",
                        )
                    }
                    budget.spend(out.length())
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
                        attack = inst.attack,
                        decay = inst.decay,
                        cutoff = inst.cutoff,
                        resonance = inst.resonance,
                        source = mapOf("importedFrom" to xpnFile.name),
                        velocityLayers = if (ordered.size < 2) emptyList() else {
                            ordered.map { KitLayer("${it.sampleName}.wav", it.velStart, it.velEnd) }
                        },
                    )
                }
                require(pads.isNotEmpty()) { "no pads landed in the 128-slot range" }

                val kit = Kit(kitName, pads)
                KitStore.save(kit, staging)
                // Only now, with everything down safely, does destDir
                // change: the pre-existing-kit check above already
                // confirmed either nothing is there or overwrite says
                // replacing it is fine. A kit already there is moved aside
                // rather than deleted outright - if the swap below fails
                // partway (a full disk, an I/O error), the old kit goes
                // back rather than being the price of a failed overwrite.
                val displaced = if (destDir.exists()) {
                    File(destRoot, ".xpn-replaced-${destDir.name}-${System.nanoTime()}").also { aside ->
                        if (!destDir.renameTo(aside)) {
                            throw IOException("could not stage '$kitName' for replacement - nothing landed")
                        }
                    }
                } else {
                    null
                }
                if (!staging.renameTo(destDir) && !staging.copyRecursively(destDir, overwrite = true)) {
                    // copyRecursively gives up on the first failed file but
                    // does not undo what it already copied, so destDir may
                    // now hold a half-landed kit under its real name - that
                    // must not stay visible, and the restore below needs
                    // the spot cleared to land in either way. A clear that
                    // doesn't fully succeed must not be built on: restoring
                    // into what's left could merge the old kit's files with
                    // wreckage from the failed landing into one corrupted
                    // mix - worse than an honest refusal.
                    val cleared = destDir.deleteRecursively()
                    if (displaced != null) {
                        val restored = cleared && (displaced.renameTo(destDir) || displaced.copyRecursively(destDir, overwrite = true))
                        if (!restored) {
                            throw IOException(
                                "could not land '$kitName', and could not restore the kit that was there either - " +
                                    "it survives at '${displaced.name}' under $destRoot",
                            )
                        }
                        displaced.deleteRecursively() // a no-op once renameTo already moved it
                        throw IOException("could not land '$kitName' on the shelf - the kit that was there is back, nothing else changed")
                    }
                    if (!cleared) {
                        throw IOException(
                            "could not land '$kitName' on the shelf, and could not clear the wreckage left behind - " +
                                "a partial, broken '$kitName' may remain at $destDir",
                        )
                    }
                    throw IOException("could not land '$kitName' on the shelf - nothing landed")
                }
                displaced?.deleteRecursively()
                return ImportResult(kit, destDir, programEntry.name)
            } finally {
                // A no-op once renameTo has moved staging into destDir; the
                // one cleanup funnel for every other exit - a refused
                // sample, a bad program, a full disk.
                staging.deleteRecursively()
            }
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
        /** Shape fields, already default-collapsed to null (see [shapeOrNull]). */
        val attack: Float?,
        val decay: Float?,
        val cutoff: Float?,
        val resonance: Float?,
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
                attack = shapeOrNull(directText(inst, "VolumeAttack"), default = 0f),
                decay = shapeOrNull(directText(inst, "VolumeDecay"), default = 0.047244f),
                cutoff = shapeOrNull(directText(inst, "Cutoff"), default = 1f),
                resonance = shapeOrNull(directText(inst, "Resonance"), default = 0f),
            )
        }
        return ParsedProgram(isKeygroup, name, instruments, zeroBased)
    }

    /**
     * A shape field reads back as null when it carries the format's own
     * default - "unshaped" and "default-shaped" are the same pad, and
     * keeping them null keeps re-exports byte-identical.
     */
    private fun shapeOrNull(text: String?, default: Float): Float? {
        val v = text?.toFloatOrNull() ?: return null
        if (kotlin.math.abs(v - default) < 1e-4f) return null
        return v.coerceIn(0f, 1f)
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
