package com.snipsnap.kit

import com.snipsnap.audio.Snip
import com.snipsnap.audio.SongBus
import com.snipsnap.mpc3.Mpc3Clip
import java.io.File

/**
 * The whole session as one WAV — every kit's groove rendered by
 * [KitPreview] at the session tempo and summed: the demo beside every
 * `.xpj`, and the "share the beat, not just the kit" story. Deterministic
 * like everything else in the render path. The sum shares [SongBus.PEAK]
 * with [KitPreview], so a mix must never clip harshly.
 */
object SessionMixdown {

    /**
     * A [Project]'s mixdown: the beat's first pattern on the active kit,
     * every other kit playing its own first groove, at the project's
     * tempo — the same routing [SessionBuilder.build] gives the session.
     * [kitDirs] is for a caller that staged the kits elsewhere, as there.
     */
    fun render(
        project: Project,
        shelfRoot: File,
        tempoBpm: Float? = project.tempoBpm,
        kitDirs: List<File> = ProjectStore.kitDirs(shelfRoot, project),
    ): Snip {
        val missing = ProjectStore.missingKits(shelfRoot, project)
        if (missing.isNotEmpty()) throw ProjectKitsMissingException(project.name, missing)
        val lead = ProjectStore.beat(ProjectStore.dir(shelfRoot, project.name)).firstOrNull()
        val active = ProjectStore.activeIndex(shelfRoot, project)
        return render(kitDirs, tempoBpm, clipFor = { index, _ -> if (index == active) lead else null })
    }

    fun render(
        kitDirs: List<File>,
        tempoBpm: Float? = null,
        /** The clip each track plays, by index; null means the kit's own first groove. */
        clipFor: (Int, File) -> Mpc3Clip? = { _, _ -> null },
    ): Snip {
        require(kitDirs.isNotEmpty()) { "a mixdown needs at least one kit" }
        val renders = kitDirs.mapIndexed { index, dir ->
            KitPreview.render(KitStore.load(dir), dir, clip = clipFor(index, dir), tempoBpm = tempoBpm)
        }
        if (renders.size == 1) return renders[0]

        // Every render is stereo at KitPreview.RATE; the mix runs as long
        // as the longest one.
        val frames = renders.maxOf { it.frameCount }
        val out = FloatArray(frames * 2)
        for (r in renders) {
            for (i in r.samples.indices) out[i] += r.samples[i]
        }
        SongBus.ceiling(out)
        return Snip(out, 2, KitPreview.RATE)
    }
}
