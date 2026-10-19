package com.snipsnap.shell

import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Project
import com.snipsnap.kit.ProjectMigration
import com.snipsnap.kit.ProjectStore
import java.io.File
import java.util.Locale

/**
 * The SHELF with projects on it (`docs/SCREEN_PLAN_2026_10.md` §3.1):
 * the projects list above the kit list, each kit saying which projects
 * hold it, the migration run on every open, and the three moves the
 * screen makes — open a project, start one, put a kit in one.
 *
 * Over the shelf root as a folder, the way [Rooms] and [SnipStore] are,
 * so it tests on a temp dir. The app's `KitShelf` keeps everything it
 * does today (instruments, rooms, bins, sorting); this is the project
 * half beside it, not a replacement.
 *
 * All methods do file IO — call them off the main thread.
 */
class ShelfModel(val root: File) {

    /** One projects-list row. */
    data class ProjectRow(
        val dir: File,
        val project: Project,
        /** `project.json`'s mtime: RECENT sort, the same order the kit list defaults to. */
        val editedAtMillis: Long,
    ) {
        val name: String get() = project.name

        /** `2 KITS · 94 BPM`, `1 KIT`, or `EMPTY`. */
        val subtitle: String
            get() {
                val n = project.kits.size
                if (n == 0) return EMPTY
                val parts = mutableListOf("$n ${if (n == 1) "KIT" else "KITS"}")
                project.tempoBpm?.let { parts += "%.0f BPM".format(Locale.ROOT, it) }
                return parts.joinToString(" · ")
            }
    }

    /** One kit-list row: the folder, the kit, and the projects that hold it. */
    data class KitRow(val dir: File, val kit: Kit, val heldBy: List<String>) {
        val folder: String get() = dir.name

        /** `IN LATE SET, KITCHEN`, or [UNHELD]. */
        val subtitle: String
            get() = if (heldBy.isEmpty()) UNHELD else "IN " + heldBy.joinToString(", ") { it.uppercase(Locale.ROOT) }
    }

    /** Opening the shelf: every unheld kit gets a project, idempotently. */
    fun open(): ProjectMigration.Result = ProjectMigration.adopt(root)

    /** The projects list, most recently edited first. */
    fun projects(): List<ProjectRow> =
        ProjectStore.list(root)
            .mapNotNull { dir ->
                runCatching { ProjectStore.load(dir) }.getOrNull()?.let { p ->
                    ProjectRow(dir, p, File(dir, ProjectStore.FILE_NAME).lastModified())
                }
            }
            .sortedByDescending { it.editedAtMillis }

    /** The kit list in the shelf's A–Z order, each with the projects that hold it. */
    fun kits(): List<KitRow> {
        val held = projects().flatMap { row -> row.project.kits.map { it to row.name } }
            .groupBy({ it.first }, { it.second })
        return KitStore.list(root).mapNotNull { dir ->
            runCatching { KitStore.load(dir) }.getOrNull()?.let { KitRow(dir, it, held[dir.name].orEmpty()) }
        }
    }

    /** The projects that hold the kit in [kitFolder], by name. */
    fun heldBy(kitFolder: String): List<String> =
        projects().filter { it.project.holds(kitFolder) }.map { it.name }

    fun openProject(name: String): Project = ProjectStore.load(ProjectStore.dir(root, name))

    /** NEW PROJECT ▸: an empty project under a name nothing holds, saved. */
    fun newProject(base: String = NEW_PROJECT_NAME): Project {
        val name = ProjectStore.freshName(root, base)
        val project = Project(name)
        ProjectStore.save(root, project)
        return project
    }

    /**
     * Opening a kit row: the first project that holds it, or a new project
     * named after it that does — the migration's own rule, for a kit that
     * arrived since the shelf was opened.
     */
    fun projectForKit(kitFolder: String): Project {
        heldBy(kitFolder).firstOrNull()?.let { return openProject(it) }
        val project = Project(ProjectStore.freshName(root, kitFolder), listOf(kitFolder))
        ProjectStore.save(root, project)
        return project
    }

    /** `+ FROM SHELF`: the kit joins the project; the first kit added becomes active. */
    fun addKit(projectName: String, kitFolder: String): Project {
        require(File(File(root, kitFolder), KitStore.FILE_NAME).isFile) { "no kit on the shelf at '$kitFolder'" }
        val updated = openProject(projectName).withKit(kitFolder)
        ProjectStore.save(root, updated)
        return updated
    }

    /** Dropping a kit from a project. The kit stays on the shelf. */
    fun dropKit(projectName: String, kitFolder: String): Project {
        val updated = openProject(projectName).withoutKit(kitFolder)
        ProjectStore.save(root, updated)
        return updated
    }

    /** The kit switcher: [kitFolder] becomes the project's active kit. */
    fun activate(projectName: String, kitFolder: String): Project {
        val updated = openProject(projectName).activated(kitFolder)
        ProjectStore.save(root, updated)
        return updated
    }

    companion object {
        const val EMPTY = "EMPTY"
        const val UNHELD = "IN NO PROJECT"
        const val NEW_PROJECT_NAME = "New Project"
    }
}
