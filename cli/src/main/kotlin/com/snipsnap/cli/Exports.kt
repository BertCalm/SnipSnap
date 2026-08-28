package com.snipsnap.cli

import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.Exporters
import com.snipsnap.kit.Kit
import java.io.File
import java.io.PrintStream

/**
 * The CLI face of the format fan-out. The dispatch itself is
 * [Exporters] in `:kit` — shared with the app's export wizard — so the
 * CLI's job here is parsing format words and reporting what landed where.
 * Output goes under a `card/` directory whose contents copy onto the
 * MPC's drive as-is.
 */
object Exports {

    val FORMATS: List<String> = ExportFormat.entries.map { it.id }

    private val NOTES = mapOf(
        ExportFormat.PROGRAM_FOLDER to "program folder, MPC 2 + 3",
        ExportFormat.EXPANSION to "browsable in the Expansion tab",
        ExportFormat.XPN to "one-file archive",
        ExportFormat.MPC3_TRACK to "MPC 3 native",
        ExportFormat.MPC3_PROJECT to "whole MPC 3 project",
        ExportFormat.MIDI to "grooves as MIDI files, every DAW",
    )

    /**
     * The prototyping loop's verdict (Z6.2): waveform is the default tile,
     * rings the runner-up — `--art` picks a style, `--no-art` opts out.
     */
    fun parseArtStyle(word: String?): com.snipsnap.shell.KitArt.Style = word?.let {
        com.snipsnap.shell.KitArt.Style.byId(it) ?: throw CliError(
            "unknown art style '$it' - styles: " +
                com.snipsnap.shell.KitArt.Style.entries.joinToString(",") { s -> s.id },
        )
    } ?: com.snipsnap.shell.KitArt.Style.WAVEFORM

    /** The browser tile, when the chosen formats can carry one. */
    fun renderArtwork(
        kit: Kit,
        kitDir: File,
        formats: List<ExportFormat>,
        style: com.snipsnap.shell.KitArt.Style,
        noArt: Boolean,
        out: PrintStream,
    ): ByteArray? {
        if (noArt) return null
        if (formats.none { it == ExportFormat.EXPANSION || it == ExportFormat.XPN }) return null
        val png = com.snipsnap.shell.KitArt.png(kit, kitDir, style)
        out.println("cover art: ${style.id} tile for the expansion browser")
        return png
    }

    fun parseFormats(list: String): List<ExportFormat> {
        val words = list.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) throw CliError("--export wants formats: ${FORMATS.joinToString(",")}")
        return words.distinct().map {
            ExportFormat.byId(it)
                ?: throw CliError("unknown export format '$it' - formats: ${FORMATS.joinToString(",")}")
        }
    }

    fun write(
        kit: Kit,
        kitDir: File,
        cardDir: File,
        formats: List<ExportFormat>,
        overwrite: Boolean,
        out: PrintStream,
        clip: com.snipsnap.mpc3.Mpc3Clip? = null,
        tempoBpm: Float? = null,
        preview: com.snipsnap.audio.Snip? = null,
        artworkPng: ByteArray? = null,
    ) {
        out.println("exports (copy the contents of ${cardDir.path} onto the card):")
        for (format in formats) {
            val o = Exporters.export(
                format, kit, kitDir, cardDir, overwrite,
                clip = clip, tempoBpm = tempoBpm, preview = preview, artworkPng = artworkPng,
            )
            val extra = o.companion?.let { " (+ ${it.name}/)" } ?: ""
            out.println("  %-10s %s%s  (%s)".format(format.id, o.primary.path, extra, NOTES.getValue(format)))
            if (format == ExportFormat.EXPANSION) {
                // The expansion gets its cassette insert beside the artwork,
                // and the liner notes beside the insert - catalog number on
                // both when the kit's crate is a label.
                val catalog = com.snipsnap.shell.Label.forKit(kitDir)
                File(o.primary, "J-Card.png").writeBytes(com.snipsnap.shell.JCard.png(kit, kitDir, catalog = catalog))
                com.snipsnap.shell.LinerNotes.writeTo(
                    kit, kitDir, File(o.primary, com.snipsnap.shell.LinerNotes.FILE_NAME), catalog,
                )
                out.println("             + J-Card.png + ${com.snipsnap.shell.LinerNotes.FILE_NAME} (the kit's inserts)")
            }
        }
    }
}
