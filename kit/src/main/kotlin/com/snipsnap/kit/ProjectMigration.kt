package com.snipsnap.kit

import java.io.File

/**
 * The one migration rule (`docs/DIRECTION_2026_10.md` §2): **every
 * existing kit becomes a project of the same name holding that kit**, with
 * the kit's own grooves as the project's beat. Nothing is lost and nothing
 * asks.
 *
 * Run on every shelf open; it is idempotent. A kit some project already
 * holds is left alone — including a kit that was deliberately added to a
 * project of a different name — so this never manufactures a second
 * project for a kit a person has already placed. A kit nothing holds gets
 * one, named after its folder, or `<folder> 2` when a project of that
 * name exists and holds something else.
 *
 * **The beat is copied, not moved, in this step.** The kit's `groove.json`
 * is still what every exporter embeds (`SessionBuilder`, the native
 * writers) and what `GrooveStore`'s callers read; moving it would silence
 * every export until those read projects instead. So the project gets its
 * own copy under `beat/`, and the kit keeps its file. Once the exporters
 * take a [Project] as input, the kit-side file retires and this comment
 * goes with it. Until then the two can drift only if something writes to
 * one and not the other, and today nothing writes a project's beat at all.
 */
object ProjectMigration {

    data class Result(
        /** Projects this run created, by name, in shelf order. */
        val created: List<String>,
        /** Kits that were already held by a project and so left alone. */
        val alreadyHeld: List<String>,
    )

    fun adopt(shelfRoot: File): Result {
        val projects = ProjectStore.list(shelfRoot).map { ProjectStore.load(it) }
        val held = projects.flatMap { it.kits }.toSet()
        val taken = projects.map { it.name }.toMutableSet()
        val created = mutableListOf<String>()
        val alreadyHeld = mutableListOf<String>()

        for (kitDir in KitStore.list(shelfRoot)) {
            val folder = kitDir.name
            if (folder in held) {
                alreadyHeld += folder
                continue
            }
            val kit = KitStore.load(kitDir)
            val name = freshName(folder, taken)
            val project = Project(
                name = name,
                kits = listOf(folder),
                activeKit = folder,
                tempoBpm = kit.tempoBpm,
                key = kit.key,
            )
            val dir = ProjectStore.dir(shelfRoot, name)
            ProjectStore.save(shelfRoot, project)
            val grooves = GrooveStore.load(kitDir)
            if (grooves.isNotEmpty()) ProjectStore.saveBeat(dir, grooves)
            taken += name
            created += name
        }
        return Result(created, alreadyHeld)
    }

    /**
     * [base], or `<base> 2`, `<base> 3`… — the first name not in [taken].
     * [ProjectStore.freshName] is the same rule over the shelf; this one
     * takes the set because one run creates several projects before any
     * of them is listed.
     */
    private fun freshName(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var n = 2
        while ("$base $n" in taken) n++
        return "$base $n"
    }
}
