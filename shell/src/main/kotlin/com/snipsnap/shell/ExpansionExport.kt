package com.snipsnap.shell

import com.snipsnap.kit.DestinationExists
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.ExpansionMeta
import com.snipsnap.kit.Finding
import com.snipsnap.kit.Kit
import com.snipsnap.kit.PackBuilder
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.Severity
import java.io.File
import java.io.IOException

/**
 * EXPORT's expansion subject: every present member packed as one MPC
 * expansion named after the expansion's title, as an on-card folder or one
 * `.xpn`. Each member is preflighted on its own, and one that fails is
 * skipped and named rather than sinking the rest; each packed member gets
 * its J-Card and is read back. The tile is the seed kit drawn with the
 * expansion's knobs and captioned with its title. The single-kit
 * [ExportWizardModel] is untouched; this sits beside it.
 *
 * [tileRenderer] and [jCardRenderer] default to the desktop renderers; the
 * phone passes its own, as it does for a kit.
 */
class ExpansionExportModel(
    private val expansion: Expansion,
    private val members: List<Member>,
    private val seed: Member?,
    private val tileRenderer: (Kit, File, ArtKnobs, String) -> ByteArray = { k, d, knobs, label -> KitArt.png(k, d, knobs, label = label) },
    private val jCardRenderer: (Kit, File, String?) -> ByteArray = JCard::awtPng,
) {
    enum class Form { FOLDER, XPN }

    var form: Form = Form.FOLDER

    /** The knobs the tile is drawn with: the expansion's own, until CHANGE ART edits them. */
    var knobs: ArtKnobs = expansion.knobs

    /** No member is on the shelf: nothing to draw, nothing to write. */
    val empty: Boolean get() = members.isEmpty()

    /** Each member's preflight findings; a member with any FAIL is skipped by [write]. */
    fun runPreflight(): Map<Member, List<Finding>> = members.associateWith { Preflight.check(it.kit, it.dir) }

    /** The tile as it will be written; null when the expansion is empty. */
    fun tile(): ByteArray? {
        val s = seed ?: return null
        return tileRenderer(s.kit, s.dir, knobs, expansion.title)
    }

    sealed interface WriteResult {
        /** Written. [readBack] is each packed member's read-back by kit name; [skipped] names each member preflight refused and why. */
        data class Done(val result: PackBuilder.Result, val readBack: Map<String, List<Finding>>, val skipped: List<Pair<String, String>>) : WriteResult

        /** [path] already holds an expansion of this title; nothing was written. */
        data class WouldOverwrite(val path: File) : WriteResult

        /** Nothing was written: the expansion is empty, or every member was refused ([skipped] says which and why). */
        data class Nothing(val skipped: List<Pair<String, String>>) : WriteResult
    }

    fun write(destRoot: File, overwrite: Boolean): WriteResult {
        if (empty) return WriteResult.Nothing(emptyList())
        val preflight = runPreflight()
        val passing = members.filter { m -> preflight.getValue(m).none { it.severity == Severity.FAIL } }
        val meta = ExpansionMeta(expansion.title, manufacturer = expansion.manufacturer, identifier = expansion.identifier)
        val jCards = passing.associate { m ->
            "[J-Cards]/${m.kit.name}.png" to jCardRenderer(m.kit, m.dir, Label.forKit(m.dir))
        }
        val result = try {
            PackBuilder.build(
                members.map { it.dir }, destRoot, meta,
                artworkPng = tile(),
                asXpn = form == Form.XPN,
                overwrite = overwrite,
                extraFiles = jCards,
            )
        } catch (e: DestinationExists) {
            return WriteResult.WouldOverwrite(e.path)
        } catch (e: IOException) {
            // PackBuilder refuses when no member could be packed; say which and why.
            val skipped = members.mapNotNull { m ->
                preflight.getValue(m).firstOrNull { it.severity == Severity.FAIL }?.let { m.kit.name to it.message }
            }
            if (skipped.size == members.size) return WriteResult.Nothing(skipped)
            throw e
        }
        val programs = File(result.directory, "Programs")
        val readBack = members.filter { it.kit.name in result.packed }.associate { m ->
            val program = programs.listFiles { f -> f.isFile && f.nameWithoutExtension == m.kit.name && f.extension.equals("xpm", true) }
                ?.firstOrNull()
            m.kit.name to (
                program?.let { ExportReadBack.verifyProgram(m.kit, it, ExportFormat.EXPANSION) }
                    ?: listOf(Finding(Severity.FAIL, "read back: no program for ${m.kit.name} in ${programs.path}"))
                )
        }
        return WriteResult.Done(result, readBack, result.skipped)
    }
}
