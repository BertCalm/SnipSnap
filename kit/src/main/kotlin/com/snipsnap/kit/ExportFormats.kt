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
    /**
     * One line on when you would pick this one over its neighbours.
     *
     * The September UAT's finding 8: [cyclerLabel] was the only copy a
     * format ever got, so nothing in the app said when EXPANSION beats XPN
     * or SFZ beats DECENTSAMPLER — the user was choosing between eight
     * names and no reasons.
     *
     * Deliberately not defaulted: a ninth format cannot be added without
     * answering "why would someone pick this one", because the compiler
     * will not let it.
     */
    val why: String,
    /** Whether this format nests the kit's own name inside `destRoot` itself, so a caller must not add another per-kit subfolder on top. */
    val selfNesting: Boolean = false,
) {
    // Writes to `File(destRoot, kit.name)` — see KitExporter.kt:44.
    PROGRAM_FOLDER(
        "folder", "MPC 2 FOLDER (EVERY GENERATION)",
        "THE SAFE ONE. A FOLDER OF WAVS ANY MPC CAN OPEN.",
        selfNesting = true,
    ),
    // Writes under `File(File(driveRoot, "Expansions"), title)` — see ExpansionWriter.kt:109.
    EXPANSION(
        "expansion", "EXPANSION (BROWSER TILE)",
        "LANDS AS A TILE IN THE MPC'S OWN BROWSER.",
        selfNesting = true,
    ),
    XPN(
        "xpn", "XPN ARCHIVE (ONE FILE)",
        "ONE FILE TO SEND SOMEONE. THE MPC UNPACKS IT.",
    ),
    MPC3_TRACK(
        "xtd", "MPC 3 NATIVE (.XTD)",
        "MPC 3 FIRMWARE ONLY. KEEPS WHAT MPC 2 CANNOT HOLD.",
    ),
    MPC3_PROJECT(
        "xpj", "MPC SESSION (.XPJ) — KITS + GROOVES",
        "A WHOLE SESSION AT ONCE, NOT ONE KIT.",
    ),
    MIDI(
        "mid", "MIDI GROOVES (EVERY DAW)",
        "THE GROOVES ONLY, NO SOUNDS. FOR A DAW.",
    ),
    SFZ(
        "sfz", "SFZ (EVERY SAMPLER)",
        "PLAIN TEXT. ALMOST ANY SAMPLER READS IT.",
    ),
    DECENT_SAMPLER(
        "ds", "DECENTSAMPLER (FREE, EVERYWHERE)",
        "FOR THE FREE DECENTSAMPLER PLUGIN, ON ANY DESK.",
    ),
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
    /**
     * READ BACK ([ExportReadBack.verify]) — the written file re-read and
     * diffed against the kit. Empty until a caller runs it (the export
     * wizard does; the drivers themselves only write).
     */
    val readBack: List<Finding> = emptyList(),
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
            // up — all of them, up to the container's four slots. Selection
            // order here (NOT GrooveStore's own stored order, which six
            // other call sites' firstOrNull() depend on staying base-first
            // — see GrooveEdit.kt's KDoc) puts the base first and PROG E
            // right behind it: GrooveEdit.save() appends E last in the
            // sidecar, so a plain .take(MAX_CLIPS) on a kit with >=4 prior
            // clips would silently drop the user's own edited program. E
            // now survives the cap, displacing a derived variant instead.
            val effectiveClips = clip?.let { listOf(it) } ?: run {
                val stored = GrooveStore.load(kitDir)
                val base = stored.firstOrNull()
                val e = stored.firstOrNull { GrooveEdit.isProgE(it) }
                val prioritized = listOfNotNull(base) +
                    listOfNotNull(e.takeIf { it != base }) +
                    stored.filter { it != base && it != e }
                prioritized.take(Mpc3TrackWriter.MAX_CLIPS)
            }
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
        ExportFormat.SFZ -> {
            val f = SfzWriter.write(kit, kitDir, destRoot, overwrite)
            ExportOutcome(format, f, File(f.parentFile, "Samples"), findings(kit, kitDir))
        }
        ExportFormat.DECENT_SAMPLER -> {
            val f = DecentSamplerWriter.write(kit, kitDir, destRoot, overwrite)
            ExportOutcome(format, f, File(f.parentFile, "Samples"), findings(kit, kitDir))
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
            // Song slot 1 wears the kit's name (GG3.1 plumbing; steps wait
            // on the bench capture).
            val song = com.snipsnap.mpc3.Mpc3Song(kit.name)
            val file = if (effectiveTempo != null) {
                writer.writeTo(destRoot, kit.name, tracks, effectiveTempo, song = song)
            } else {
                writer.writeTo(destRoot, kit.name, tracks, song = song)
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
