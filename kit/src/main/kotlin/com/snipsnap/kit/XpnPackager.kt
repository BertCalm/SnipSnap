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
 * An `.xpn` is a ZIP with exactly one structural invariant, measured across
 * four commercial archives from three vendors (docs/MPC_EXPORT.md):
 * **`Expansion.xml` sits at the archive root** — 4 of 4, flat and deeply
 * foldered alike. Everything else is free, so we keep a tidy layout:
 *
 * ```
 * MyPack.xpn
 * ├── Expansion.xml              (root — the invariant; lowercase schema)
 * ├── artwork.png                (root, referenced by <img>)
 * ├── Programs/<Kit>.xpm         (bare SampleNames — MPC finds WAVs by search)
 * ├── Samples/<Kit>/…wav
 * └── [Previews]/<Kit>.xpm.wav   (the real packs' preview convention)
 * ```
 *
 * No manifest rides in the archive (none of the real ones carries one — the
 * plain-text manifest belongs to the on-card `Expansions/` folder layout,
 * see [ExpansionWriter]), and no `<File>`/`<SampleFile>` paths appear in the
 * program: across every harvested pack samples are referenced by bare name
 * even when they live in a subfolder.
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

        // Sanitize + measure exactly the way the folder exporters do; the
        // program references bare stems and the MPC finds the WAVs itself.
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
                color = with(KitExporter) { p.packedColor() },
                attack = p.attack,
                decay = p.decay,
                cutoff = p.cutoff,
                resonance = p.resonance,
                humanize = p.humanize,
            )
        }
        val programXml = XpmWriter().write(DrumProgram(kit.name, slots.toList()))

        ZipOutputStream(outputFile.outputStream().buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                val entry = ZipEntry(name)
                entry.time = FIXED_TIME
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
            put(ExpansionWriter.XML_NAME, ExpansionWriter.renderXml(meta, if (artworkPng != null) "artwork.png" else null).toByteArray(Charsets.UTF_8))
            put("Programs/$programStem.xpm", programXml.toByteArray(Charsets.UTF_8))
            preview?.let {
                val tmp = File.createTempFile("preview", ".wav")
                try {
                    com.snipsnap.audio.WavWriter.write(tmp, it)
                    // Real packs: [Previews]/<program name>.xpm.wav.
                    put("[Previews]/$programStem.xpm.wav", tmp.readBytes())
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
