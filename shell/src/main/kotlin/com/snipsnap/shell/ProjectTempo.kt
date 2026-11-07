package com.snipsnap.shell

import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPreview
import com.snipsnap.kit.Project

/**
 * The one project tempo (the 2026-11-06 ruling: set from BEAT, followed live
 * by ORBIT and LOOP).
 *
 * [Project.tempoBpm] is the single store. Until a project has one, its
 * kit's tempo stands in, and a kit with none reads as
 * [KitPreview.DEFAULT_BPM]: [of] is that order, in one place, so no screen
 * works it out for itself.
 *
 * Most readers in the app take the tempo from `entry.kit.tempoBpm`, so the
 * app hands screens an *effective* kit ([effective]): the kit with this
 * tempo on it, **in memory only**. It must never reach `kit.json` — a kit
 * is shared by every project that holds it, and the tempo that belongs to
 * one project would otherwise change under another. The app's open kit only
 * ever comes back from a model that loaded the kit from disk, so what is
 * saved is the file's own tempo; [stored] is the way back if a caller ever
 * holds the effective kit and must save it.
 */
object ProjectTempo {

    /** What a project resolves to: its own tempo, else its kit's, else [KitPreview.DEFAULT_BPM]. Not clamped: see [clamped]. */
    fun of(project: Project?, kit: Kit?): Float =
        project?.tempoBpm ?: kit?.tempoBpm ?: KitPreview.DEFAULT_BPM

    /** [of], held to [Project.MIN_BPM]..[Project.MAX_BPM]: the range ORBIT's `OrbitSet` and LOOP's `Session` require. */
    fun clamped(project: Project?, kit: Kit?): Float = of(project, kit).coerceIn(Project.MIN_BPM, Project.MAX_BPM)

    /**
     * [kit] as the screens see it: the project's tempo on it when the project
     * has one. A project with none leaves the kit as it is, so a kit with no
     * tempo of its own stays "no tempo" (exports that omit one still omit it)
     * and reads as the default where it is played. Same instance when there is
     * nothing to change, so identity-keyed effects do not restart.
     */
    fun effective(kit: Kit, project: Project?): Kit {
        val tempo = project?.tempoBpm ?: return kit
        return if (kit.tempoBpm == tempo) kit else kit.copy(tempoBpm = tempo)
    }

    /** The kit as it should be saved: [effective] undone, back to [onDisk]'s own tempo (null when the file has none). */
    fun stored(effective: Kit, onDisk: Kit?): Kit =
        if (effective.tempoBpm == onDisk?.tempoBpm) effective else effective.copy(tempoBpm = onDisk?.tempoBpm)

    /**
     * The one-time migration from ORBIT's own tempo: a project with no tempo of
     * its own, whose ORBIT set was fitted at a different one than the kit
     * reads as, adopts the ORBIT set's ([orbitBpm], held to
     * [Project.MIN_BPM]..[Project.MAX_BPM]) so the set does not change under
     * the person. Null when there is nothing to adopt: the project already has
     * a tempo (it is the authority, and this never overrides it), or the two
     * agree to the whole BPM. Once adopted the project has a tempo, so it
     * cannot happen twice.
     */
    fun adoptable(project: Project?, kit: Kit, orbitBpm: Float): Float? {
        if (project == null || project.tempoBpm != null || !orbitBpm.isFinite()) return null
        val kitBpm = kit.tempoBpm ?: KitPreview.DEFAULT_BPM
        if (Math.round(orbitBpm) == Math.round(kitBpm)) return null
        return orbitBpm.coerceIn(Project.MIN_BPM, Project.MAX_BPM)
    }
}
