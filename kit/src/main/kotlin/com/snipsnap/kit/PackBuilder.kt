package com.snipsnap.kit

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * N kits, one expansion — the commercial-pack shape: a whole catalog of
 * programs under one tile, browsable on the hardware. Turns a crate of
 * chopped kits into a shippable product.
 *
 * Layout follows the corpus rules the single-kit writers settled:
 * `Expansion.xml` at the root (the one structural invariant), programs
 * under `Programs/`, each kit's WAVs under `Samples/<Kit>/` (samples are
 * referenced by bare name — every harvested pack does it that way, so
 * subfolders keep two kits' `A01_Kick_01`s apart), per-kit previews under
 * `[Previews]/` the way real packs pair them, and the on-card `manifest`
 * beside the XML. A kit preflight refuses to pack is **skipped and named
 * with the reason**, backup-style — packs never pretend.
 */
object PackBuilder {

    data class Result(
        /** The on-card expansion folder. */
        val directory: File,
        /** The one-file archive, when asked for. */
        val xpn: File?,
        val packed: List<String>,
        val skipped: List<Pair<String, String>>,
    )

    fun build(
        kitDirs: List<File>,
        destRoot: File,
        meta: ExpansionMeta,
        artworkPng: ByteArray? = null,
        withPreviews: Boolean = true,
        asXpn: Boolean = false,
        overwrite: Boolean = false,
    ): Result {
        require(kitDirs.isNotEmpty()) { "a pack needs at least one kit" }

        val dest = File(File(destRoot, ExpansionWriter.EXPANSIONS_DIR), meta.title)
        if (dest.exists() && !overwrite) {
            throw IOException("destination already exists: $dest (pass overwrite=true to replace same-named files)")
        }
        dest.deleteRecursively()
        dest.mkdirs()

        val packed = mutableListOf<String>()
        val skipped = mutableListOf<Pair<String, String>>()
        for (kitDir in kitDirs) {
            val kit = KitStore.load(kitDir)
            val findings = Preflight.check(kit, kitDir)
            if (findings.blocked()) {
                skipped += kit.name to findings.first { it.severity == Severity.FAIL }.message
                continue
            }
            val (program, _) = KitExporter.writeProgramAndSamples(
                kit, kitDir,
                programDir = File(dest, "Programs"),
                samplesDir = File(dest, "Samples/${kit.name}"),
            )
            if (withPreviews) {
                val previews = File(dest, "[Previews]").apply { mkdirs() }
                com.snipsnap.audio.WavWriter.write(
                    File(previews, program.name + ".wav"),
                    KitPreview.render(kit, kitDir),
                )
            }
            packed += kit.name
        }
        if (packed.isEmpty()) {
            dest.deleteRecursively()
            throw IOException(
                "nothing packable: " + skipped.joinToString("; ") { "'${it.first}' - ${it.second}" },
            )
        }

        artworkPng?.let { File(dest, meta.artworkFileName).writeBytes(it) }
        File(dest, ExpansionWriter.XML_NAME)
            .writeText(ExpansionWriter.renderXml(meta, artworkPng?.let { meta.artworkFileName }), Charsets.UTF_8)
        File(dest, ExpansionWriter.MANIFEST_NAME)
            .writeText(ExpansionWriter.renderManifest(meta), Charsets.UTF_8)

        // The .xpn twin is the same tree zipped, minus the on-card manifest
        // (no real archive carries one), timestamps fixed for stable bytes.
        val xpn = if (asXpn) {
            val file = File(destRoot, "${meta.title}.xpn")
            if (file.exists() && !overwrite) {
                throw IOException("destination already exists: $file (pass overwrite=true to replace same-named files)")
            }
            ZipOutputStream(file.outputStream().buffered()).use { zip ->
                val entries = dest.walkTopDown()
                    .filter { it.isFile && it.name != ExpansionWriter.MANIFEST_NAME }
                    .map { it.relativeTo(dest).invariantSeparatorsPath to it }
                    .sortedBy { it.first }
                for ((name, source) in entries) {
                    val entry = ZipEntry(name)
                    entry.time = FIXED_TIME
                    zip.putNextEntry(entry)
                    zip.write(source.readBytes())
                    zip.closeEntry()
                }
            }
            file
        } else {
            null
        }

        return Result(dest, xpn, packed, skipped)
    }

    /** 2020-01-01T00:00:00 UTC — the same stamp `XpnPackager` uses. */
    private const val FIXED_TIME = 1_577_836_800_000L
}
