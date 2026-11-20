package com.snipsnap.shell

import com.snipsnap.kit.Project

/**
 * Opening the app on the pads (`docs/UX_PASS_2026_11.md`, the owner's answer of 2026-11-15:
 * "yes, when a kit is open"). The app used to start on LIBRARY with nothing open, so a user
 * who closed it mid-kit had to find the project again before a pad would play. Now it
 * remembers the project that was open with a kit on its grid, and a cold start opens that
 * project on PADS, the same landing a tap on its row gives.
 *
 * Only the project's name is remembered. The kit is the project's own active kit, which is
 * what opening a project puts on the grid ([Project.activeKit]); opening a kit row activates
 * it first, so the two agree.
 */
object LastOpen {
    /** The app's preferences key. Its value is a project name, or absent. */
    const val PREFS_KEY = "last-open-project"

    /**
     * What to remember now: the open project's name while one of its kits is on the grid, and
     * nothing otherwise. A closed project, or an open one with no kit (NEW PROJECT before a kit
     * is added), forgets, so the next start is the library as before.
     */
    fun toSave(project: Project?, openKit: String?): String? =
        project?.takeIf { openKit != null && openKit in it.kits }?.name

    /**
     * RESTORE from the trash (UAT 2026-11 user states, D-3): whether the kit that came back as
     * [restored] goes back on the open project's grid. Deleting a project's active kit leaves
     * the project open with no kit, and RESTORE used to leave it that way until the project was
     * reopened, and [toSave] forgot it meanwhile. It goes back when the project is open with no
     * kit and [restored] is its active kit, which is what the delete took away. A kit already on
     * the grid, another project's kit, or a restore that landed under a fresh name leaves the
     * grid alone.
     */
    fun reopensOnRestore(project: Project?, openKit: String?, restored: String): Boolean =
        project != null && openKit == null && project.activeKit == restored

    /**
     * The project a cold start opens on PADS, or null for the library. Null when nothing was
     * remembered, when the project is gone (renamed, binned), or when its active kit is no
     * longer on the shelf: a project that would open on an empty grid is not "on the pads".
     */
    fun pick(saved: String?, projects: List<Project>, kitsOnShelf: Set<String>): Project? {
        if (saved == null) return null
        val project = projects.firstOrNull { it.name == saved } ?: return null
        val active = project.activeKit ?: return null
        return project.takeIf { active in kitsOnShelf }
    }
}
