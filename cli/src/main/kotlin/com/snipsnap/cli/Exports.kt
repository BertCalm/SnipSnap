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
    ) {
        out.println("exports (copy the contents of ${cardDir.path} onto the card):")
        for (format in formats) {
            val o = Exporters.export(
                format, kit, kitDir, cardDir, overwrite,
                clip = clip, tempoBpm = tempoBpm, preview = preview,
            )
            val extra = o.companion?.let { " (+ ${it.name}/)" } ?: ""
            out.println("  %-10s %s%s  (%s)".format(format.id, o.primary.path, extra, NOTES.getValue(format)))
        }
    }
}
