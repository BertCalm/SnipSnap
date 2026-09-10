package com.snipsnap.mpc3

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.io.StringReader
import java.util.zip.ZipFile
import org.xml.sax.InputSource

/**
 * "What's inside this file" for any MPC-saved program — `.xpm`, `.xpn`,
 * `.xtd`, `.xty`, `.xpj` — read, never imported. [Mpc3Importer] and
 * [XpnImporter] answer "can this become a kit" and refuse in words the
 * moment it can't; this answers a different, gentler question and never
 * refuses at all. A keygroup program, a file with no samples reachable,
 * a slot with nothing in it: every one of those still gets a [Reading],
 * honestly labeled, rather than an exception.
 *
 * Two layers, on purpose kept apart:
 * - **Structural** — [MpcFormats.detect] plus (for the ACVS side)
 *   [unlabeledFieldCount] and [Reading.rawTree]: whatever this doesn't
 *   have a name for yet is counted and shown whole, never guessed at.
 *   A field this reads as `null` is a field that wasn't there, or
 *   wasn't shaped as expected — not a zero.
 * - **Semantic** — [Reading.programs]: the same fields
 *   [Mpc3Importer]/[XpnImporter] already know how to read, minus their
 *   validation. Nothing here writes a file, resolves a sample against a
 *   folder, or invents a class - it only reads.
 */
object MpcXRay {

    /** One velocity layer's sample name (never resolved against a folder — this doesn't touch disk beyond the one file it was handed) and range. */
    data class Layer(val sampleName: String?, val velocityStart: Int?, val velocityEnd: Int?)

    /** One instrument slot. [hasSample] is false for an empty slot kept in the count rather than dropped — the whole point of asking "how many of 128 are actually used." */
    data class Pad(
        val slot: Int,
        val hasSample: Boolean,
        val layers: List<Layer>,
        val level: Float?,
        val pan: Float?,
        val tuneCoarse: Int?,
        val tuneFine: Int?,
        val muteGroup: Int?,
        val attack: Float?,
        val decay: Float?,
        val cutoff: Float?,
        val resonance: Float?,
    )

    /** One track/program. [isKeygroup] is read, never inferred from whether pads parsed — a keygroup's own zones live in the same instrument list a drum program's pads do. */
    data class Program(val trackName: String, val isKeygroup: Boolean, val pads: List<Pad>)

    data class Reading(
        /** A human label for the container generation, [MpcDiff.formatLabel]'s own style. */
        val kindLabel: String,
        val programs: List<Program> = emptyList(),
        /** Distinct field names present somewhere in the file that nothing above named — a count, never a list of guessed meanings. ACVS only; the XML side's vocabulary is small enough to name in full above. */
        val unlabeledFieldCount: Int = 0,
        /** The whole ACVS payload, for a look past what [programs] covers. Null for the XML/XPN side (nothing here needs a second tree - see this class's own KDoc) and whenever nothing parsed. */
        val rawTree: JsonValue? = null,
        /** Why nothing above could be read further, in words - set instead of throwing, never both this and a non-empty [programs]. */
        val unreadable: String? = null,
    )

    /** The same ceiling `ShelfImport.MAX_CONTAINER_BYTES` already puts on a shared MPC container — a share-sheet file gets no more trust here than it gets landing on the shelf for real. */
    const val MAX_FILE_BYTES: Long = LimitedRead.DEFAULT_LIMIT

    /** How many header bytes decide the container kind — [MpcFormats.detect]'s own read size, enough for gzip, the ZIP signature, and an XML declaration behind a BOM alike. */
    private const val HEAD_BYTES = 16

    /**
     * Reads [file]'s kind from a small header first — never the whole file
     * just to answer "which container is this." The one throw left is a
     * missing file; everything the *content* can say, however broken
     * (including too large to read at all), comes back as a [Reading].
     * Only the ACVS/XML/JSON sides read the file whole, because their own
     * parse needs the whole text regardless; the ZIP side never does —
     * [readXpn] opens [file] directly, a ZIP's own central directory
     * being exactly what random access exists for.
     */
    fun read(file: File): Reading {
        require(file.isFile) { "no such file: $file" }
        if (file.length() > MAX_FILE_BYTES) {
            return Reading(
                "not recognized",
                unreadable = "too large to read (${file.length() / (1024 * 1024)} MB, the most is ${MAX_FILE_BYTES / (1024 * 1024)} MB)",
            )
        }
        val head = file.inputStream().use { s ->
            val b = ByteArray(HEAD_BYTES)
            var n = 0
            while (n < b.size) {
                val r = s.read(b, n, b.size - n)
                if (r < 0) break
                n += r
            }
            b.copyOf(n)
        }
        return when {
            Acvs.isGzip(head) -> readAcvs(file.readBytes())
            looksLikeZip(head) -> readXpn(file)
            looksLikeXml(head) -> readXpm(file.readText(), suffix = "")
            else -> {
                val text = runCatching { file.readText() }.getOrNull()
                val firstChar = text?.trimStart()?.firstOrNull()
                if (text != null && (firstChar == '{' || firstChar == '[')) {
                    val tree = runCatching { Json.parse(text) }.getOrNull()
                    if (tree != null) Reading("JSON", rawTree = tree)
                    else Reading("not recognized", unreadable = "looks like JSON but won't parse")
                } else {
                    Reading("not recognized", unreadable = "not an MPC 3 container, MPC 2 XML, a .xpn pack, or JSON")
                }
            }
        }
    }

    // ---- ACVS (.xtd, .xty, .xpj) --------------------------------------------

    private fun readAcvs(bytes: ByteArray): Reading {
        val project = try {
            Mpc3Project.read(bytes)
        } catch (e: Exception) {
            return Reading("MPC 3 (ACVS)", unreadable = e.message ?: "the container won't inflate")
        }
        val kindLabel = "MPC 3 (ACVS ${project.container.header.objectType})"
        val payload = project.container.payload
            ?: return Reading(kindLabel, unreadable = "the container's payload isn't JSON")
        val programs = project.tracks.map { track ->
            val trackName = (track["name"] as? JsonValue.Str)?.value ?: "(unnamed)"
            val programMap = (track["program"] as? JsonValue.Obj)?.entries
            val type = (programMap?.get("type") as? JsonValue.Num)?.value?.toInt()
            val instruments = ((programMap?.get("drum") as? JsonValue.Obj)?.entries
                ?.get("instruments") as? JsonValue.Arr)?.items.orEmpty()
            Program(trackName, isKeygroup = type == 1, pads = instruments.mapIndexed { i, j -> acvsPad(i, j) })
        }
        val seenKeys = mutableSetOf<String>().also { collectKeyNames(payload, it) }
        val unlabeled = (seenKeys - ACVS_KNOWN_KEYS).size
        return Reading(kindLabel, programs, unlabeled, payload)
    }

    private fun acvsPad(index: Int, instJson: JsonValue): Pad {
        val inst = (instJson as? JsonValue.Obj)?.entries
        val layers = ((inst?.get("layersv") as? JsonValue.Arr)?.items.orEmpty()).map { layerJson ->
            val layer = (layerJson as? JsonValue.Obj)?.entries
            Layer(
                sampleName = (layer?.get("sampleFile") as? JsonValue.Str)?.value?.takeIf { it.isNotBlank() }
                    ?: (layer?.get("sampleName") as? JsonValue.Str)?.value?.takeIf { it.isNotBlank() },
                velocityStart = (layer?.get("velocityStart") as? JsonValue.Num)?.value?.toInt(),
                velocityEnd = (layer?.get("velocityEnd") as? JsonValue.Num)?.value?.toInt(),
            )
        }
        val mixable = (inst?.get("mixable") as? JsonValue.Obj)?.entries
        return Pad(
            slot = index + 1,
            hasSample = layers.any { it.sampleName != null },
            layers = layers,
            level = (mixable?.get("volume") as? JsonValue.Num)?.value?.toFloat(),
            pan = (mixable?.get("pan") as? JsonValue.Num)?.value?.toFloat(),
            tuneCoarse = (inst?.get("coarseTune") as? JsonValue.Num)?.value?.toInt(),
            tuneFine = (inst?.get("fineTune") as? JsonValue.Num)?.value?.toInt(),
            muteGroup = (inst?.get("whichMuteGroup") as? JsonValue.Num)?.value?.toInt(),
            attack = ampField(inst, "Attack")?.toFloat(),
            decay = ampField(inst, "Decay")?.toFloat(),
            cutoff = filterField(inst, "filterCutoff")?.toFloat(),
            resonance = filterField(inst, "filterResonance")?.toFloat(),
        )
    }

    /** `synthSection.ampEnvelope.<name>.value0` on one instrument, or null — [Mpc3Importer.ampField]'s own reader, duplicated: a five-line generic map accessor, not worth a cross-module export. */
    private fun ampField(inst: Map<String, JsonValue>?, name: String): Double? {
        val synth = (inst?.get("synthSection") as? JsonValue.Obj)?.entries ?: return null
        val env = (synth["ampEnvelope"] as? JsonValue.Obj)?.entries ?: return null
        val holder = (env[name] as? JsonValue.Obj)?.entries ?: return null
        return (holder["value0"] as? JsonValue.Num)?.value
    }

    /** `synthSection.filterData.value0.<name>` on one instrument, or null — [Mpc3Importer.filterField]'s own reader, duplicated for the same reason [ampField] is. */
    private fun filterField(inst: Map<String, JsonValue>?, name: String): Double? {
        val synth = (inst?.get("synthSection") as? JsonValue.Obj)?.entries ?: return null
        val data = (synth["filterData"] as? JsonValue.Obj)?.entries ?: return null
        val slot = (data["value0"] as? JsonValue.Obj)?.entries ?: return null
        return (slot[name] as? JsonValue.Num)?.value
    }

    private val VALUE_N = Regex("value\\d+")

    /** Every key name appearing anywhere in [v] — `valueN` pad-table keys normalised to one entry, [MpcDiff]'s own convention, so a 128-pad table isn't 128 "unlabeled" fields. */
    private fun collectKeyNames(v: JsonValue, out: MutableSet<String>) {
        when (v) {
            is JsonValue.Obj -> v.entries.forEach { (k, child) ->
                out.add(if (VALUE_N.matches(k)) "value*" else k)
                collectKeyNames(child, out)
            }
            is JsonValue.Arr -> v.items.forEach { collectKeyNames(it, out) }
            else -> {}
        }
    }

    /** Every field name this class's ACVS side reads. Anything else in the tree is [Reading.unlabeledFieldCount]. */
    private val ACVS_KNOWN_KEYS = setOf(
        "name", "program", "type", "drum", "instruments", "layersv", "sampleFile", "sampleName",
        "velocityStart", "velocityEnd", "mixable", "volume", "pan", "coarseTune", "fineTune",
        "whichMuteGroup", "synthSection", "ampEnvelope", "Attack", "Decay", "value*", "value0",
        "filterData", "filterCutoff", "filterResonance",
    )

    // ---- MPC 2 XML (.xpm) and .xpn (a ZIP of .xpm) --------------------------

    private fun looksLikeXml(head: ByteArray): Boolean = MpcFormats.detect(head) == MpcFormat.MPC2_XML

    private fun looksLikeZip(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            ((head[2] == 3.toByte() && head[3] == 4.toByte()) ||
                (head[2] == 5.toByte() && head[3] == 6.toByte()) ||
                (head[2] == 7.toByte() && head[3] == 8.toByte()))

    /**
     * Every `.xpm` entry inside a `.xpn` (or any ZIP) — a multi-kit pack
     * reads as one [Reading] with every program it holds, `Programs/`
     * first, [XpnImporter.programEntries]'s own ordering. Opens [file]
     * directly rather than reading it into memory first: a ZIP's central
     * directory is exactly what random access exists for, and the file
     * is already on disk - there is nothing a copy of its bytes would add.
     */
    private fun readXpn(file: File): Reading {
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory }
                .filter { it.name.endsWith(".xpm", ignoreCase = true) }
                .sortedWith(compareBy({ if (it.name.contains("Programs/")) 0 else 1 }, { it.name }))
                .toList()
            if (entries.isEmpty()) {
                return Reading("a ZIP (not a .xpn - no .xpm program inside)", unreadable = "no .xpm program inside")
            }
            // A bounded read per entry, same reasoning as the whole-file
            // check in read(): an .xpm is a few KB of XML in every real
            // pack, so a declared size past the ceiling is a bomb, not
            // a program - skipped rather than aborting the whole pack,
            // XpnImporter.importAll's own "one bad program, not the
            // rest" rule.
            val programs = entries.flatMap { entry ->
                val xmlBytes = runCatching {
                    zip.getInputStream(entry).use { LimitedRead.bytes(it, MAX_FILE_BYTES, entry.name) }
                }.getOrNull() ?: return@flatMap emptyList()
                readXpm(xmlBytes.toString(Charsets.UTF_8), suffix = " (${entry.name})").programs
            }
            // Programs actually returned, not files scanned - the two
            // usually agree, but a program whose XML failed to parse
            // (fewer) or an .xpm carrying more than one <Program> (more)
            // would otherwise make this header disagree with the list
            // right below it.
            return Reading(
                "MPC 2 (XML, inside a .xpn pack) — ${programs.size} program(s) from ${entries.size} file(s)",
                programs,
            )
        }
    }

    private fun readXpm(xml: String, suffix: String): Reading {
        val doc = try {
            val cleaned = xml.removePrefix("﻿").trimStart()
            SafeXml.newFactory().newDocumentBuilder().parse(InputSource(StringReader(cleaned))).also { it.documentElement.normalize() }
        } catch (e: Exception) {
            return Reading("MPC 2 (XML)$suffix", unreadable = e.message ?: "not readable XML")
        }

        val programEls = elements(doc, "Program")
        val programs = if (programEls.isEmpty()) listOf(xmlProgram(doc.documentElement)) else programEls.map(::xmlProgram)
        return Reading("MPC 2 (XML)$suffix", programs)
    }

    private fun xmlProgram(programEl: org.w3c.dom.Element): Program {
        val isKeygroup = programEl.getAttribute("type").equals("Keygroup", ignoreCase = true)
        val name = firstText(programEl, "ProgramName")?.trim()?.ifBlank { null } ?: "(unnamed)"
        val instrumentEls = childElements(programEl, "Instrument")
        val pads = instrumentEls.mapIndexedNotNull { i, inst ->
            val slot = inst.getAttribute("number").trim().toIntOrNull() ?: return@mapIndexedNotNull null
            val layers = childElements(inst, "Layer").map { layer ->
                Layer(
                    sampleName = firstText(layer, "SampleName")?.trim()?.ifBlank { null },
                    velocityStart = firstText(layer, "VelStart")?.trim()?.toIntOrNull(),
                    velocityEnd = firstText(layer, "VelEnd")?.trim()?.toIntOrNull(),
                )
            }
            Pad(
                slot = slot,
                hasSample = layers.any { it.sampleName != null },
                layers = layers,
                level = directText(inst, "Volume")?.toFloatOrNull(),
                pan = directText(inst, "Pan")?.toFloatOrNull(),
                tuneCoarse = directText(inst, "TuneCoarse")?.toIntOrNull(),
                tuneFine = directText(inst, "TuneFine")?.toIntOrNull(),
                muteGroup = directText(inst, "MuteGroup")?.toIntOrNull(),
                attack = directText(inst, "VolumeAttack")?.toFloatOrNull(),
                decay = directText(inst, "VolumeDecay")?.toFloatOrNull(),
                cutoff = directText(inst, "Cutoff")?.toFloatOrNull(),
                resonance = directText(inst, "Resonance")?.toFloatOrNull(),
            )
        }
        return Program(name, isKeygroup, pads)
    }

    // ---- DOM helpers — [XpnImporter]'s own, duplicated (see this file's other small-reader comments) ----

    private fun elements(doc: org.w3c.dom.Document, tag: String): List<org.w3c.dom.Element> {
        val nl = doc.getElementsByTagName(tag)
        return (0 until nl.length).mapNotNull { nl.item(it) as? org.w3c.dom.Element }
    }

    private fun childElements(parent: org.w3c.dom.Element, tag: String): List<org.w3c.dom.Element> {
        val nl = parent.getElementsByTagName(tag)
        return (0 until nl.length).mapNotNull { nl.item(it) as? org.w3c.dom.Element }
    }

    private fun firstText(parent: org.w3c.dom.Element, tag: String): String? =
        (parent.getElementsByTagName(tag).item(0) as? org.w3c.dom.Element)?.textContent

    private fun directText(parent: org.w3c.dom.Element, tag: String): String? {
        val kids = parent.childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if (n is org.w3c.dom.Element && n.tagName == tag) return n.textContent?.trim()
        }
        return null
    }
}
