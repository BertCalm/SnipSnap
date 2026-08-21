package com.snipsnap.kit

import java.io.File
import java.io.IOException

/**
 * Metadata for an expansion pack.
 *
 * Field set and shapes follow the XO_OX XPN toolchain (the sibling project's
 * shipping packager, `Tools/xpn_packager.py` in BertCalm/XO_OX-XOmnibus),
 * which is the closest thing to a live spec we have seen: lowercase
 * `<expansion>` schema, four-part version, and a plain-text `manifest`
 * alongside the XML for older firmware.
 */
data class ExpansionMeta(
    /** Display name in the Expansion browser; also the folder name. */
    val title: String,
    val manufacturer: String = "SnipSnap",
    /** Dotted version, e.g. "1.0.0". Serialized four-part ("1.0.0.0"). */
    val version: String = "1.0.0",
    /** Reverse-domain identity, e.g. `app.snipsnap.factory`. Dots, not spaces. */
    val identifier: String,
    val description: String = "",
    /** Akai's pack taxonomy; "instrument" is the one drum/keygroup packs use. */
    val type: String = "instrument",
) {
    init {
        require(Names.isMpcSafe(title)) { "title must be MPC-safe: $title" }
        require(Regex("^\\d+(\\.\\d+)*$").matches(version)) { "version must be dotted digits: $version" }
        require(Regex("^[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$").matches(identifier)) {
            "identifier must be reverse-domain style (dots, no spaces): $identifier"
        }
        require(type.isNotBlank()) { "type must not be blank" }
    }

    /** Version padded to Akai's four-part form: "1.0" → "1.0.0.0". */
    val versionFourPart: String
        get() {
            val parts = version.split('.').toMutableList()
            while (parts.size < 4) parts.add("0")
            return parts.take(4).joinToString(".")
        }

    /** Artwork filename: the title with everything but letters and digits dropped. */
    val artworkFileName: String
        get() = title.filter { it.isLetterOrDigit() } + ".png"
}

data class ExpansionResult(
    val directory: File,
    val xml: File,
    val manifest: File,
    val program: File,
    val samples: List<File>,
    val artwork: File?,
)

/**
 * Writes a kit as a browsable expansion — the tier-2 export from
 * docs/MPC_EXPORT.md. The result lands under `Expansions/` on the drive and
 * shows up in the MPC's Expansion tab with a tile:
 *
 * ```
 * Expansions/<Title>/
 * ├── Expansion.xml        ← lowercase <expansion> schema
 * ├── manifest             ← plain-text Name=/Version=/Author= fallback
 * ├── <Title>.png          ← square tile, supplied as bytes
 * ├── Programs/<Title>.xpm
 * └── Samples/ (the WAVs)
 * ```
 *
 * **Provenance:** the XML schema, the dual-manifest convention, and the
 * layout are lifted from the XO_OX XPN toolchain (BertCalm/XO_OX-XOmnibus,
 * `Tools/xpn_packager.py` + `Tools/xpn_validator.py`) — running code that
 * builds MPC-loadable packs, replacing this writer's earlier guess from
 * prose walkthroughs. Both manifests are emitted because firmware support
 * differs: plain-text is the widest-compat form, the XML carries artwork.
 * Still worth one diff against an Akai-authored pack when one lands in
 * `reference/`.
 *
 * Artwork arrives as PNG bytes rather than being rendered here: this module
 * stays free of JVM-only imaging APIs so the Android app can feed it a
 * bitmap from its own canvas.
 */
object ExpansionWriter {

    const val EXPANSIONS_DIR = "Expansions"
    const val XML_NAME = "Expansion.xml"
    const val MANIFEST_NAME = "manifest"

    fun write(
        kit: Kit,
        kitDir: File,
        driveRoot: File,
        meta: ExpansionMeta,
        artworkPng: ByteArray? = null,
        /**
         * Preview audio, written as `[Previews]/<program>.wav`. The base
         * name must match the program's (Rex Rule #4); this writer
         * guarantees it. MP3 encoding is the app layer's MediaCodec job.
         */
        preview: com.snipsnap.audio.Snip? = null,
        overwrite: Boolean = false,
    ): ExpansionResult {
        val findings = Preflight.check(kit, kitDir)
        if (findings.blocked()) throw ExportBlockedException(findings)

        val dest = File(File(driveRoot, EXPANSIONS_DIR), meta.title)
        if (dest.exists() && !overwrite) {
            throw IOException("destination already exists: $dest (pass overwrite=true to replace same-named files)")
        }
        dest.mkdirs()
        if (!dest.isDirectory) throw IOException("could not create $dest")

        val (program, samples) = KitExporter.writeProgramAndSamples(
            kit, kitDir,
            programDir = File(dest, "Programs"),
            samplesDir = File(dest, "Samples"),
        )

        val artwork = artworkPng?.let {
            val f = File(dest, meta.artworkFileName)
            f.writeBytes(it)
            f
        }

        preview?.let {
            val previews = File(dest, "[Previews]").apply { mkdirs() }
            com.snipsnap.audio.WavWriter.write(
                File(previews, program.name.removeSuffix(".xpm") + ".wav"), it,
            )
        }

        val xml = File(dest, XML_NAME)
        xml.writeText(renderXml(meta, artwork?.name), Charsets.UTF_8)

        val manifest = File(dest, MANIFEST_NAME)
        manifest.writeText(renderManifest(meta), Charsets.UTF_8)

        return ExpansionResult(dest, xml, manifest, program, samples, artwork)
    }

    /** The lowercase schema, verbatim shape from the XO_OX packager. */
    internal fun renderXml(meta: ExpansionMeta, artworkName: String?): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<expansion version=\"2.0.0.0\" buildVersion=\"2.10.0.0\">\n")
        append("  <local/>\n")
        append("  <identifier>").append(escape(meta.identifier)).append("</identifier>\n")
        append("  <title>").append(escape(meta.title)).append("</title>\n")
        append("  <manufacturer>").append(escape(meta.manufacturer)).append("</manufacturer>\n")
        append("  <version>").append(meta.versionFourPart).append("</version>\n")
        append("  <type>").append(escape(meta.type)).append("</type>\n")
        append("  <priority>50</priority>\n")
        artworkName?.let { append("  <img>").append(escape(it)).append("</img>\n") }
        append("  <description>").append(escape(meta.description)).append("</description>\n")
        append("  <separator>-</separator>\n")
        append("</expansion>\n")
    }

    /** The plain-text key=value manifest — the widest-compatibility form. */
    internal fun renderManifest(meta: ExpansionMeta): String {
        fun clean(s: String) = s.replace("\r", "").replace("\n", " ").trim()
        return buildString {
            append("Name=").append(clean(meta.title)).append('\n')
            append("Version=").append(clean(meta.version)).append('\n')
            append("Author=").append(clean(meta.manufacturer)).append('\n')
            append("Description=").append(clean(meta.description)).append('\n')
        }
    }

    private fun escape(value: String): String = buildString(value.length) {
        for (c in value) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}
