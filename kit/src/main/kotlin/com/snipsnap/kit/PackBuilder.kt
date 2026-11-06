package com.snipsnap.kit

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.mpc3.Mpc3TrackWriter
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
 *
 * Instruments ride the same tile: a keygroup package the shop made (MAKE
 * INSTRUMENT, the `keys` verb), named by its `.instrument.json` sidecar,
 * lands as `Programs/<Name>.xpm` with its looped WAVs under
 * `Samples/<Name>/`, the kits' own layout. The program is the package's
 * own `.xpm`, copied as it is, and every WAV keeps the root and sustain
 * loop it was written with.
 */
object PackBuilder {

    data class Result(
        /** The on-card expansion folder. */
        val directory: File,
        /** The one-file archive, when asked for. */
        val xpn: File?,
        /** The kits packed, by name. */
        val packed: List<String>,
        /** Kits and instruments left out, by name, with the reason. */
        val skipped: List<Pair<String, String>>,
        /** The instruments packed, by name. */
        val instruments: List<String> = emptyList(),
    )

    fun build(
        kitDirs: List<File>,
        destRoot: File,
        meta: ExpansionMeta,
        artworkPng: ByteArray? = null,
        withPreviews: Boolean = true,
        asXpn: Boolean = false,
        overwrite: Boolean = false,
        /**
         * Extra files to land inside the pack (relative path → bytes),
         * written before the `.xpn` twin zips — how per-kit J-cards ride
         * along without this module knowing how to draw one.
         */
        extraFiles: Map<String, ByteArray> = emptyMap(),
        /** Instrument packages to pack beside the kits, each named by its `.instrument.json` sidecar. */
        instruments: List<File> = emptyList(),
    ): Result {
        require(kitDirs.isNotEmpty() || instruments.isNotEmpty()) { "a pack needs at least one kit or instrument" }

        val dest = File(File(destRoot, ExpansionWriter.EXPANSIONS_DIR), meta.title)
        if (dest.exists() && !overwrite) {
            throw DestinationExists(dest)
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
            val samplesDir = File(dest, "Samples/${kit.name}")
            val (program, _) = KitExporter.writeProgramAndSamples(
                kit, kitDir,
                programDir = File(dest, "Programs"),
                samplesDir = samplesDir,
            )
            // Robin pads shipped as take files: bind the sidecar to those, not the chain.
            val takes = kit.pads.filter { it.isRobinChain && it.velocityLayers.isEmpty() }
                .associate { it.slot to RobinSplit.written(it, samplesDir) }
            val programEntry = "Programs/${program.name}"
            XpnSourceMetadata.render(kit, kitDir, programEntry, program.readBytes(), "Samples/${kit.name}", takes)?.let { bytes ->
                File(dest, XpnSourceMetadata.entryName(programEntry)).writeBytes(bytes)
            }
            if (withPreviews) {
                val previews = File(dest, "[Previews]").apply { mkdirs() }
                com.snipsnap.audio.WavWriter.write(
                    File(previews, program.name + ".wav"),
                    KitPreview.render(kit, kitDir),
                )
            }
            // The kit's feel rides the pack as a tradeable pocket - one
            // small file per kit that has a groove to bottle.
            GrooveStore.load(kitDir).firstOrNull()?.let { groove ->
                PocketStore.save(
                    PocketStore.Pocket(groove.name, GrooveFeel.extract(groove)),
                    File(File(dest, "[Pockets]"), "${kit.name}.${PocketStore.EXTENSION}"),
                )
            }
            packed += kit.name
        }
        val programNames = File(dest, "Programs").list().orEmpty().map { it.removeSuffix(".xpm").lowercase() }.toHashSet()
        val packedInstruments = mutableListOf<String>()
        for (sidecar in instruments) {
            val (name, refusal) = packInstrument(sidecar, dest, programNames, withPreviews)
            if (refusal == null) packedInstruments += name else skipped += name to refusal
        }
        if (packed.isEmpty() && packedInstruments.isEmpty()) {
            dest.deleteRecursively()
            throw IOException(
                "nothing packable: " + skipped.joinToString("; ") { "'${it.first}' - ${it.second}" },
            )
        }

        for ((rel, bytes) in extraFiles) {
            require(!rel.startsWith("/") && "\\" !in rel && ".." !in rel.split('/')) {
                "extra file path must stay inside the pack: '$rel'"
            }
            val f = File(dest, rel)
            f.parentFile.mkdirs()
            f.writeBytes(bytes)
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
                throw DestinationExists(file)
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

        return Result(dest, xpn, packed, skipped, packedInstruments)
    }

    /**
     * One instrument package into the pack at [dest]: its name, and the reason it was left
     * out or null when it was packed. [InstrumentStore.read] has already proved every sample is there and stays in
     * its folder. Program names are compared without case, the way the MPC's browser
     * shows them.
     */
    private fun packInstrument(sidecar: File, dest: File, programNames: MutableSet<String>, withPreviews: Boolean): Pair<String, String?> {
        val fallback = sidecar.name.removeSuffix(InstrumentStore.SUFFIX)
        val instrument = try {
            InstrumentStore.read(sidecar)
        } catch (e: Exception) {
            return fallback to "the instrument could not be read: ${e.message}"
        }
        val name = instrument.name
        if (!Names.isMpcSafe(name)) return name to "the name is not MPC-safe"
        val folder = sidecar.parentFile
        val xpm = File(File(folder, Mpc3TrackWriter.trackDataDirName(name)), "$name.xpm")
        if (!xpm.isFile) return name to "its keygroup program (${xpm.name}) is missing"
        if (!programNames.add(name.lowercase())) return name to "a program called '$name' is already in this pack"
        val samplesDir = File(dest, "Samples/$name").apply { mkdirs() }
        for (rel in instrument.sampleFiles) {
            val source = File(folder, rel)
            source.copyTo(File(samplesDir, source.name), overwrite = true)
        }
        xpm.copyTo(File(File(dest, "Programs").apply { mkdirs() }, xpm.name), overwrite = true)
        if (withPreviews) {
            val previews = File(dest, "[Previews]").apply { mkdirs() }
            WavWriter.write(File(previews, xpm.name + ".wav"), instrumentPreview(instrument, folder))
        }
        return name to null
    }

    /**
     * The middle zone held for a few seconds: its headline sample, wrapped at its
     * sustain loop until [PREVIEW_SECONDS], the way a held key sounds.
     */
    internal fun instrumentPreview(instrument: InstrumentStore.Instrument, folder: File): Snip {
        val zone = instrument.zones.sortedBy { it.rootNote }.let { it[it.size / 2] }
        val layer = zone.playableLayers.last()
        val source = WavReader.read(File(folder, layer.sample))
        val channels = source.channels
        val frames = source.samples.size / channels
        val want = (PREVIEW_SECONDS * source.sampleRate).toInt()
        val loop = layer.loopStartFrame.toInt()
        if (loop <= 0 || loop >= frames || frames >= want) {
            val keep = minOf(frames, want)
            return Snip(source.samples.copyOf(keep * channels), channels, source.sampleRate)
        }
        val out = FloatArray(want * channels)
        var frame = 0
        while (frame < want) {
            val from = if (frame < frames) frame else loop + (frame - frames) % (frames - loop)
            for (c in 0 until channels) out[frame * channels + c] = source.samples[from * channels + c]
            frame++
        }
        return Snip(out, channels, source.sampleRate)
    }

    private const val PREVIEW_SECONDS = 4f

    /** 2020-01-01T00:00:00 UTC — the same stamp `XpnPackager` uses. */
    private const val FIXED_TIME = 1_577_836_800_000L
}
