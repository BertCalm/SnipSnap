package com.snipsnap.cli

import com.snipsnap.kit.ExpansionMeta
import com.snipsnap.kit.ExpansionWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitExporter
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.XpnPackager
import com.snipsnap.mpc3.Mpc3ProjectTrack
import com.snipsnap.mpc3.Mpc3ProjectWriter
import com.snipsnap.mpc3.Mpc3TrackWriter
import java.io.File
import java.io.IOException
import java.io.PrintStream

/**
 * The format fan-out both `chop --export` and `export` share.
 *
 * Every driver already exists in `:kit`/`:mpc3`; this maps a format word to
 * the right one and reports what landed where. Output goes under a `card/`
 * directory whose contents copy onto the MPC's drive as-is — the CLI's
 * stand-in for the app's SAF export.
 */
object Exports {

    /** In the order docs/MPC_EXPORT.md tells the story: MPC 2 tiers, then native. */
    val FORMATS = listOf("folder", "expansion", "xpn", "xtd", "xpj")

    fun parseFormats(list: String): List<String> {
        val formats = list.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (formats.isEmpty()) throw CliError("--export wants formats: ${FORMATS.joinToString(",")}")
        formats.firstOrNull { it !in FORMATS }?.let {
            throw CliError("unknown export format '$it' - formats: ${FORMATS.joinToString(",")}")
        }
        return formats.distinct()
    }

    fun write(
        kit: Kit,
        kitDir: File,
        cardDir: File,
        formats: List<String>,
        overwrite: Boolean,
        out: PrintStream,
    ) {
        out.println("exports (copy the contents of ${cardDir.path} onto the card):")
        for (format in formats) when (format) {
            "folder" -> {
                val r = KitExporter.exportProgramFolder(kit, kitDir, cardDir, overwrite)
                out.println("  folder     ${r.program.path}  (program folder, MPC 2 + 3)")
            }
            "expansion" -> {
                val r = ExpansionWriter.write(kit, kitDir, cardDir, meta(kit), overwrite = overwrite)
                out.println("  expansion  ${r.directory.path}  (browsable in the Expansion tab)")
            }
            "xpn" -> {
                val file = XpnPackager.write(kit, kitDir, File(cardDir, "${kit.name}.xpn"), meta(kit), overwrite = overwrite)
                out.println("  xpn        ${file.path}  (one-file archive)")
            }
            "xtd" -> {
                val r = Mpc3Exporter.exportTrack(kit, kitDir, cardDir, overwrite)
                out.println("  xtd        ${r.program.path}  (+ ${Mpc3TrackWriter.trackDataDirName(kit.name)}/, MPC 3 native)")
            }
            "xpj" -> {
                val dataDir = File(cardDir, Mpc3ProjectWriter.projectDataDirName(kit.name))
                val xpj = File(cardDir, "${kit.name}.xpj")
                if ((xpj.exists() || dataDir.exists()) && !overwrite) {
                    throw IOException("destination already exists: $xpj (pass --overwrite to replace same-named files)")
                }
                dataDir.deleteRecursively()
                val program = Mpc3Exporter.stageTrack(kit, kitDir, dataDir)
                val file = Mpc3ProjectWriter().writeTo(cardDir, kit.name, listOf(Mpc3ProjectTrack.Drum(program)))
                out.println("  xpj        ${file.path}  (+ ${dataDir.name}/, whole MPC 3 project)")
            }
            else -> throw CliError("unknown export format '$format' - formats: ${FORMATS.joinToString(",")}")
        }
    }

    private fun meta(kit: Kit) = ExpansionMeta(
        title = kit.name,
        identifier = "app.snipsnap." +
            kit.name.filter { it.isLetterOrDigit() }.lowercase().ifBlank { "kit" },
        description = "Chopped with the SnipSnap CLI.",
    )
}
