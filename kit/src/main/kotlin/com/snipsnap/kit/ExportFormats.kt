package com.snipsnap.kit

import com.snipsnap.mpc3.Mpc3ProjectTrack
import com.snipsnap.mpc3.Mpc3ProjectWriter
import com.snipsnap.mpc3.Mpc3TrackWriter
import java.io.File
import java.io.IOException

/**
 * Every way a kit leaves the building, as one enum — the single list the
 * CLI's `--export`, the app's export wizard, and the docs all mean when
 * they say "the formats".
 */
enum class ExportFormat(
    /** Stable machine id — the CLI's `--export` words. */
    val id: String,
    /** The export wizard's format-cycler line. */
    val cyclerLabel: String,
) {
    PROGRAM_FOLDER("folder", "MPC 2 FOLDER (EVERY GENERATION)"),
    EXPANSION("expansion", "EXPANSION (BROWSER TILE)"),
    XPN("xpn", "XPN ARCHIVE (ONE FILE)"),
    MPC3_TRACK("xtd", "MPC 3 NATIVE (.XTD)"),
    MPC3_PROJECT("xpj", "MPC 3 PROJECT (.XPJ)"),
    MIDI("mid", "MIDI GROOVES (EVERY DAW)"),
    ;

    companion object {
        fun byId(id: String): ExportFormat? = entries.firstOrNull { it.id == id }
    }
}

/** What one export produced, normalized across the five drivers. */
data class ExportOutcome(
    val format: ExportFormat,
    /** The thing to point the user at: the program, archive, or folder. */
    val primary: File,
    /** The side-by-side data folder, where the format has one. */
    val companion: File?,
    val findings: List<Finding>,
)

/**
 * The format fan-out every export surface shares. Preflight runs inside
 * each driver and FAILs throw [ExportBlockedException] — callers that want
 * the checklist first run [Preflight.check] themselves.
 */
object Exporters {

    fun export(
        format: ExportFormat,
        kit: Kit,
        kitDir: File,
        destRoot: File,
        overwrite: Boolean = false,
        meta: ExpansionMeta = defaultMeta(kit),
        /** MPC3_TRACK only: also write the MPC 2 `.xpm` twin inside `_[TrackData]/`. */
        dualGeneration: Boolean = false,
        /** Native formats only: an embedded pattern (see `CapturedGroove`). */
        clip: com.snipsnap.mpc3.Mpc3Clip? = null,
        /** MPC3_PROJECT only: the project tempo; null keeps the writer's default. */
        tempoBpm: Float? = null,
        /** EXPANSION/XPN only: preview audio (see `KitPreview.render`). */
        preview: com.snipsnap.audio.Snip? = null,
        /** EXPANSION/XPN only: the browser tile (see `KitArt` in `:shell`). */
        artworkPng: ByteArray? = null,
    ): ExportOutcome = when (format) {
        ExportFormat.PROGRAM_FOLDER -> {
            val r = KitExporter.exportProgramFolder(kit, kitDir, destRoot, overwrite)
            ExportOutcome(format, r.program, null, r.findings)
        }
        ExportFormat.EXPANSION -> {
            val r = ExpansionWriter.write(
                kit, kitDir, destRoot, meta,
                artworkPng = artworkPng, preview = preview, overwrite = overwrite,
            )
            ExportOutcome(format, r.directory, null, findings(kit, kitDir))
        }
        ExportFormat.XPN -> {
            val f = XpnPackager.write(
                kit, kitDir, File(destRoot, "${kit.name}.xpn"), meta,
                artworkPng = artworkPng, preview = preview, overwrite = overwrite,
            )
            ExportOutcome(format, f, null, findings(kit, kitDir))
        }
        ExportFormat.MPC3_TRACK -> {
            // The call-site clip wins; the kit's remembered grooves back it
            // up — all of them, up to the container's four slots.
            val effectiveClips = clip?.let { listOf(it) }
                ?: GrooveStore.load(kitDir).take(Mpc3TrackWriter.MAX_CLIPS)
            val r = Mpc3Exporter.exportTrack(kit, kitDir, destRoot, overwrite, clips = effectiveClips, mpc2Twin = dualGeneration)
            ExportOutcome(
                format, r.program,
                File(destRoot, Mpc3TrackWriter.trackDataDirName(kit.name)), r.findings,
            )
        }
        ExportFormat.MIDI -> {
            // The kit's grooves as .mid files, one per pattern; a kit with
            // no groove exports its honest default beat, same as preview.
            val clips = clip?.let { listOf(it) }
                ?: GrooveStore.load(kitDir).ifEmpty { listOf(KitPreview.defaultPattern(kit)) }
            val bpm = tempoBpm ?: kit.tempoBpm ?: KitPreview.DEFAULT_BPM
            val files = clips.map { c ->
                MidiGroove.writeTo(
                    File(destRoot, "${Names.sanitizeStem(c.name)}.mid"), c, bpm, overwrite,
                )
            }
            ExportOutcome(format, files.first(), null, findings(kit, kitDir))
        }
        ExportFormat.MPC3_PROJECT -> {
            val dataDir = File(destRoot, Mpc3ProjectWriter.projectDataDirName(kit.name))
            val xpj = File(destRoot, "${kit.name}.xpj")
            if ((xpj.exists() || dataDir.exists()) && !overwrite) {
                throw IOException(
                    "destination already exists: $xpj (pass overwrite=true to replace same-named files)",
                )
            }
            dataDir.deleteRecursively()
            val program = Mpc3Exporter.stageTrack(kit, kitDir, dataDir)
            val writer = Mpc3ProjectWriter()
            // The call-site clip wins; otherwise every stored groove becomes
            // its own sequence — the pattern flip on the hardware's switcher.
            val effectiveClips = clip?.let { listOf(it) }
                ?: GrooveStore.load(kitDir).take(Mpc3ProjectWriter.MAX_SEQUENCES)
            val tracks = listOf(Mpc3ProjectTrack.Drum(program, clips = effectiveClips))
            // The call-site tempo wins; the kit's remembered tempo backs it up.
            val effectiveTempo = tempoBpm ?: kit.tempoBpm
            val file = if (effectiveTempo != null) {
                writer.writeTo(destRoot, kit.name, tracks, effectiveTempo)
            } else {
                writer.writeTo(destRoot, kit.name, tracks)
            }
            ExportOutcome(format, file, dataDir, findings(kit, kitDir))
        }
    }

    /** The stand-in metadata for kits exported without a pack identity. */
    fun defaultMeta(kit: Kit) = ExpansionMeta(
        title = kit.name,
        identifier = "app.snipsnap." +
            kit.name.filter { it.isLetterOrDigit() }.lowercase().ifBlank { "kit" },
        description = "Made with SnipSnap.",
    )

    private fun findings(kit: Kit, kitDir: File) = Preflight.check(kit, kitDir)
}
