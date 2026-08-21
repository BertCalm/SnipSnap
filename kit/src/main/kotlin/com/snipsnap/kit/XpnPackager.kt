package com.snipsnap.kit

import com.snipsnap.xpm.DrumProgram
import com.snipsnap.xpm.Pad
import com.snipsnap.xpm.VelocityLayer
import com.snipsnap.xpm.WavInfo
import com.snipsnap.xpm.XpmWriter
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packages a kit as a single `.xpn` file — one shareable archive instead of
 * a folder tree.
 *
 * An `.xpn` is a ZIP with a specific internal structure ("get the structure
 * wrong and nothing loads — no error, just silence"):
 *
 * ```
 * MyPack.xpn
 * ├── Expansions/manifest        (plain-text Name=/Version=/Author=)
 * ├── Expansions/Expansion.xml   (lowercase <expansion> schema)
 * ├── Programs/<Kit>.xpm         (layers carry Samples/<Kit>/… File paths)
 * ├── Programs/<Kit>.wav         (preview — same base name, Rex Rule #4)
 * ├── Samples/<Kit>/…wav
 * └── artwork.png
 * ```
 *
 * **Provenance:** structure and rules from the XO_OX XPN toolchain
 * (BertCalm/XO_OX-XOmnibus, `xpn_packager.py` / `xpn_validator.py`), which
 * ships MPC-loadable packs this way. This *revises* our earlier docs claim
 * that `.xpn` is desktop-only — the acceptance question moved to "does the
 * MPC's expansion import take this file", which the testkit pack answers.
 *
 * Output is deterministic: fixed entry timestamps, stable ordering — the
 * same kit zips to the same bytes.
 */
object XpnPackager {

    fun write(
        kit: Kit,
        kitDir: File,
        outputFile: File,
        meta: ExpansionMeta,
        artworkPng: ByteArray? = null,
        preview: com.snipsnap.audio.Snip? = null,
        overwrite: Boolean = false,
    ): File {
        val findings = Preflight.check(kit, kitDir)
        if (findings.blocked()) throw ExportBlockedException(findings)
        if (outputFile.exists() && !overwrite) {
            throw IOException("destination already exists: $outputFile (pass overwrite=true to replace same-named files)")
        }
        outputFile.parentFile?.mkdirs()

        val programStem = kit.name
        val samplesPrefix = "Samples/$programStem"

        // Sanitize + measure exactly the way the folder exporters do, but
        // emit into the archive with pack-root-relative File paths.
        data class Entry(val stem: String, val source: File, val frames: Long)

        val used = HashSet<String>()
        val bySlot = HashMap<Int, Entry>()
        val layerEntries = HashMap<Int, List<Pair<Entry, KitLayer>>>()
        val allEntries = mutableListOf<Entry>()

        fun claim(file: String): Entry {
            val stem = Names.sanitizeStem(file.substringBeforeLast('.'))
            check(used.add(stem.lowercase())) { "preflight let a name collision through: $stem" }
            val src = File(kitDir, file)
            val e = Entry(stem, src, WavInfo.read(src).frameCount)
            allEntries += e
            return e
        }

        for (p in kit.pads.sortedBy { it.slot }) {
            if (p.velocityLayers.isEmpty()) {
                bySlot[p.slot] = claim(p.sampleFile)
            } else {
                val zones = p.velocityLayers.map { l -> claim(l.sampleFile) to l }
                layerEntries[p.slot] = zones
                bySlot[p.slot] = zones.last().first
            }
        }

        val slots = arrayOfNulls<Pad>(kit.highestSlot)
        for (p in kit.pads) {
            val main = bySlot.getValue(p.slot)
            slots[p.slot - 1] = Pad(
                sampleName = main.stem,
                frameCount = main.frames,
                level = p.level,
                pan = p.pan,
                tuneCoarse = p.tuneCoarse,
                tuneFine = p.tuneFine,
                muteGroup = p.muteGroup,
                oneShot = p.oneShot,
                velocityLayers = layerEntries[p.slot]?.map { (e, l) ->
                    VelocityLayer(e.stem, e.frames, l.velStart, l.velEnd)
                },
            )
        }
        val programXml = XpmWriter(samplePathPrefix = samplesPrefix)
            .write(DrumProgram(kit.name, slots.toList()))

        ZipOutputStream(outputFile.outputStream().buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                val entry = ZipEntry(name)
                entry.time = FIXED_TIME
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
            put("Expansions/manifest", ExpansionWriter.renderManifest(meta).toByteArray(Charsets.UTF_8))
            put("Expansions/${ExpansionWriter.XML_NAME}", ExpansionWriter.renderXml(meta, if (artworkPng != null) "artwork.png" else null).toByteArray(Charsets.UTF_8))
            put("Programs/$programStem.xpm", programXml.toByteArray(Charsets.UTF_8))
            preview?.let {
                val tmp = File.createTempFile("preview", ".wav")
                try {
                    com.snipsnap.audio.WavWriter.write(tmp, it)
                    put("Programs/$programStem.wav", tmp.readBytes())
                } finally {
                    tmp.delete()
                }
            }
            for (e in allEntries) put("$samplesPrefix/${e.stem}.wav", e.source.readBytes())
            artworkPng?.let { put("artwork.png", it) }
        }
        return outputFile
    }

    /** 2020-01-01T00:00:00 UTC — any fixed stamp keeps output byte-stable. */
    private const val FIXED_TIME = 1_577_836_800_000L
}
