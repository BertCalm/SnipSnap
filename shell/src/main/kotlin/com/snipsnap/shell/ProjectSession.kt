package com.snipsnap.shell

import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.DestinationExists
import com.snipsnap.kit.ExportBlockedException
import com.snipsnap.kit.Finding
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.Project
import com.snipsnap.kit.ProjectKitsMissingException
import com.snipsnap.kit.ProjectStore
import com.snipsnap.kit.SessionBuilder
import com.snipsnap.kit.SessionMixdown
import com.snipsnap.kit.blocked
import java.io.File
import java.util.Locale

/**
 * OUT's second card (`docs/SCREEN_PLAN_2026_10.md` §3.5): the whole
 * project as one MPC session, over the two project overloads that
 * already exist in `:kit`. The same stage machine as [ExportWizardModel]
 * — READY (read the checklist) → WRITING (do not eject) → COMPLETE — and
 * the same shape of result: blocked in words, never a throw for a
 * preflight, and a destination that exists comes back named.
 *
 * A held kit whose folder is gone is the one refusal the kit wizard does
 * not have: it is reported by name before anything is written, under
 * [missingKits], and blocks the write.
 */
class ProjectSessionModel(
    val project: Project,
    private val shelfRoot: File,
) {

    enum class Stage { READY, WRITING, COMPLETE }

    var stage: Stage = Stage.READY
        private set

    /** Held kits with no folder on the shelf; non-empty blocks the write. */
    val missingKits: List<String> get() = ProjectStore.missingKits(shelfRoot, project)

    /** Each present kit's checklist, by folder, in the project's order. Empty until [runPreflight]. */
    var preflight: List<Pair<String, List<Finding>>> = emptyList()
        private set

    /** PREFLIGHT: every kit's checklist, read fresh. */
    fun runPreflight(): List<Pair<String, List<Finding>>> {
        preflight = ProjectStore.kitDirs(shelfRoot, project).map { dir ->
            dir.name to Preflight.check(KitStore.load(dir), dir)
        }
        return preflight
    }

    val blocked: Boolean
        get() = missingKits.isNotEmpty() || project.kits.isEmpty() || preflight.any { it.second.blocked() }

    /** The card's subtitle: `2 KITS · BEAT ON KITCHEN · 94 BPM`. */
    val subtitle: String
        get() {
            val n = project.kits.size
            val parts = mutableListOf("$n ${if (n == 1) "KIT" else "KITS"}")
            val beat = ProjectStore.beat(ProjectStore.dir(shelfRoot, project.name))
            val active = project.activeKit
            if (beat.isNotEmpty() && active != null) {
                parts += "BEAT ON ${active.uppercase(Locale.ROOT)}"
            }
            project.tempoBpm?.let { parts += "%.0f BPM".format(Locale.ROOT, it) }
            return parts.joinToString(" · ")
        }

    sealed interface WriteResult {
        /** The session landed. [mixdown] is beside it when asked for. */
        data class Done(val result: SessionBuilder.Result, val mixdown: File?) : WriteResult

        /** Nothing written: kits gone, no kits, or a checklist FAIL. */
        data class Blocked(val missingKits: List<String>, val findings: List<Pair<String, List<Finding>>>) : WriteResult

        /** Nothing written: the session already exists at [path]. */
        data class WouldOverwrite(val path: File) : WriteResult
    }

    /**
     * WRITE SESSION. The result says what happened; the stage moves to
     * COMPLETE only when something landed.
     */
    fun write(destRoot: File, overwrite: Boolean = true, withMixdown: Boolean = false): WriteResult {
        check(stage == Stage.READY) { "write() only from READY, stage is $stage" }
        runPreflight()
        if (blocked) return WriteResult.Blocked(missingKits, preflight)

        stage = Stage.WRITING
        return try {
            val result = SessionBuilder.build(project, shelfRoot, destRoot, overwrite = overwrite)
            val mixdown = if (withMixdown) {
                val wav = File(destRoot, "${result.xpj.nameWithoutExtension}.wav")
                WavWriter.write(wav, SessionMixdown.render(project, shelfRoot, result.tempoBpm))
                wav
            } else {
                null
            }
            stage = Stage.COMPLETE
            WriteResult.Done(result, mixdown)
        } catch (e: ProjectKitsMissingException) {
            stage = Stage.READY
            WriteResult.Blocked(e.missing, preflight)
        } catch (e: ExportBlockedException) {
            stage = Stage.READY
            WriteResult.Blocked(emptyList(), preflight)
        } catch (e: DestinationExists) {
            stage = Stage.READY
            WriteResult.WouldOverwrite(e.path)
        } catch (e: Exception) {
            stage = Stage.READY
            throw e
        }
    }

    /** WRITE ANOTHER ✓: back to READY. Nothing is ejected; the session stays where it landed. */
    fun eject() {
        stage = Stage.READY
    }
}
