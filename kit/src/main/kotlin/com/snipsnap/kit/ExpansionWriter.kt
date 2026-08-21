package com.snipsnap.kit

import java.io.File
import java.io.IOException

/**
 * Metadata for an expansion pack — the fields Akai's documentation and
 * community walkthroughs agree `Expansion.xml` carries.
 */
data class ExpansionMeta(
    /** Display name in the Expansion browser; also the folder name. */
    val title: String,
    val manufacturer: String = "SnipSnap",
    /** Single digit — the format is documented as wanting exactly that. */
    val version: Int = 1,
    /** Reverse-domain identity, e.g. `app.snipsnap.thumpkit`. Dots, not spaces. */
    val identifier: String,
    val description: String = "",
) {
    init {
        require(Names.isMpcSafe(title)) { "title must be MPC-safe: $title" }
        require(version in 1..9) { "version is a single digit, got $version" }
        require(Regex("^[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$").matches(identifier)) {
            "identifier must be reverse-domain style (dots, no spaces): $identifier"
        }
    }

    /** Artwork filename: the title with everything but letters and digits dropped. */
    val artworkFileName: String
        get() = title.filter { it.isLetterOrDigit() } + ".png"
}

data class ExpansionResult(
    val directory: File,
    val xml: File,
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
 * ├── Expansion.xml
 * ├── <Title>.png          ← 1000×1000 tile, supplied as bytes
 * ├── Programs/<Title>.xpm
 * └── Samples/ (the WAVs)
 * ```
 *
 * **Verification status: the folder layout and field list are
 * community-documented; the exact `Expansion.xml` element names have not
 * yet been checked against a real pack.** Every Akai domain and every MPC
 * community site that posts real files is unreachable from this
 * environment, so this writer is built the way the MPC 3 reader was: the
 * best-documented shape, emitted table-driven from [xmlElements] so one
 * real `Expansion.xml` (see `reference/README.md`) corrects it in minutes.
 * The acceptance check is the pack in `testkit/`: if it tiles up in the
 * Expansion browser on real hardware, the shape is right.
 *
 * Artwork arrives as PNG bytes rather than being rendered here: this module
 * stays free of JVM-only imaging APIs so the Android app can feed it a
 * bitmap from its own canvas.
 */
object ExpansionWriter {

    const val EXPANSIONS_DIR = "Expansions"
    const val XML_NAME = "Expansion.xml"

    fun write(
        kit: Kit,
        kitDir: File,
        driveRoot: File,
        meta: ExpansionMeta,
        artworkPng: ByteArray? = null,
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

        val xml = File(dest, XML_NAME)
        xml.writeText(renderXml(meta, artwork?.name), Charsets.UTF_8)

        return ExpansionResult(dest, xml, program, samples, artwork)
    }

    /** Element name → value, in emission order. The whole format, swappable. */
    private fun xmlElements(meta: ExpansionMeta, artworkName: String?): List<Pair<String, String>> =
        buildList {
            add("Title" to meta.title)
            add("Manufacturer" to meta.manufacturer)
            add("Version" to meta.version.toString())
            add("Identifier" to meta.identifier)
            add("Description" to meta.description)
            artworkName?.let { add("Img" to it) }
        }

    private fun renderXml(meta: ExpansionMeta, artworkName: String?): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<Expansion>\n")
        for ((element, value) in xmlElements(meta, artworkName)) {
            append("  <").append(element).append('>')
            append(escape(value))
            append("</").append(element).append(">\n")
        }
        append("</Expansion>\n")
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
