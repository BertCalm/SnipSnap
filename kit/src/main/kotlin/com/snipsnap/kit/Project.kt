package com.snipsnap.kit

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scale
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.mpc3.Mpc3Clip
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * The unit of work (`docs/DIRECTION_2026_10.md` §2): what a person opens,
 * works in, and takes things out of. A kit is one of its outputs, and kits
 * are not *in* a project — they are on the shelf, shared by every project,
 * and a project holds them **by folder name**. Make a kit in project A,
 * open project B, add it there: the same folder, the same WAVs, the same
 * `kit.json`. Edit it in either and both see the edit. A project that
 * wants its own copy duplicates the kit on purpose, the way VERSIONS
 * takes a take.
 *
 * The reference is the folder name rather than [Kit.name] because the
 * folder is how everything else addresses a kit — the shelf, the CLI,
 * `SessionBuilder.build`'s `kitDirs` — and a project is, at export, that
 * builder's input: N kit folders become one `.xpj` with each kit on a
 * track. This class is the thing the exporter already assembled, made
 * into the thing the user opens.
 *
 * The project's **beat** — its patterns, and in time its song — lives in
 * the project's folder, not in any kit's (see [ProjectStore.beatDir]).
 * Everything here is pure data; [ProjectStore] does the IO.
 */
data class Project(
    /** The folder name under `Projects/`; also what the shelf shows. */
    val name: String,
    /** Kit folder names on the shelf, in the order they were added. */
    val kits: List<String> = emptyList(),
    /** Which held kit the PADS stage shows; null only while [kits] is empty. */
    val activeKit: String? = kits.firstOrNull(),
    val tempoBpm: Float? = null,
    val key: KeySpec? = null,
) {
    init {
        require(name.isNotBlank()) { "project name must not be blank" }
        require(SafePath.isSafe(name)) { "project name cannot be a folder name: '$name'" }
        require(kits.toSet().size == kits.size) {
            "a project holds each kit once: ${kits.groupBy { it }.filterValues { it.size > 1 }.keys}"
        }
        for (k in kits) require(SafePath.isSafe(k)) { "kit reference is not a folder name: '$k'" }
        if (activeKit != null) require(activeKit in kits) { "active kit '$activeKit' is not held by this project" }
        if (kits.isNotEmpty()) require(activeKit != null) { "a project that holds kits has an active one" }
        tempoBpm?.let { require(it > 0f && it < 1000f) { "tempoBpm out of range: $it" } }
    }

    /** True when this project holds the kit in [kitFolderName]. */
    fun holds(kitFolderName: String): Boolean = kitFolderName in kits

    /**
     * `+ FROM SHELF`: this project also holding [kitFolderName]. The first
     * kit added becomes active; a kit already held changes nothing.
     */
    fun withKit(kitFolderName: String): Project =
        if (holds(kitFolderName)) this
        else copy(kits = kits + kitFolderName, activeKit = activeKit ?: kitFolderName)

    /**
     * The project without [kitFolderName]. The kit stays on the shelf —
     * dropping a kit from a project deletes nothing. If it was the active
     * kit, the first remaining one takes over.
     */
    fun withoutKit(kitFolderName: String): Project {
        if (!holds(kitFolderName)) return this
        val rest = kits - kitFolderName
        return copy(kits = rest, activeKit = if (activeKit == kitFolderName) rest.firstOrNull() else activeKit)
    }

    /**
     * The project after the kit in [from] was renamed to [to] on the shelf:
     * the same place in [kits], and still the active kit if it was. A
     * project not holding [from] is unchanged; one already holding [to]
     * keeps that entry and drops [from], so it never holds a kit twice.
     */
    fun withKitRenamed(from: String, to: String): Project {
        if (from == to || !holds(from)) return this
        val renamed = if (holds(to)) kits - from else kits.map { if (it == from) to else it }
        return copy(kits = renamed, activeKit = if (activeKit == from) to else activeKit)
    }

    /** The kit switcher above the grid: [kitFolderName] becomes the active kit. Must be held. */
    fun activated(kitFolderName: String): Project {
        require(holds(kitFolderName)) { "cannot activate '$kitFolderName': not held by '$name'" }
        return copy(activeKit = kitFolderName)
    }

    /**
     * BEAT's BPM control (the 2026-11-06 ruling, `docs/UAT_2026_11.md`: one
     * project tempo, set from BEAT): the project's tempo is [bpm], held to
     * [MIN_BPM]..[MAX_BPM] — the range ORBIT's rings and LOOP's grid
     * require — so every screen that follows it can take it as it is.
     * Non-finite input is refused rather than clamped into a tempo nobody
     * chose.
     */
    fun withTempo(bpm: Float): Project {
        require(bpm.isFinite()) { "tempo must be finite: $bpm" }
        return copy(tempoBpm = bpm.coerceIn(MIN_BPM, MAX_BPM))
    }

    companion object {
        /** The slowest project tempo BEAT sets; ORBIT's and LOOP's own lower bound. */
        const val MIN_BPM = 40f

        /** The fastest project tempo BEAT sets; ORBIT's and LOOP's own upper bound. */
        const val MAX_BPM = 220f
    }
}

/**
 * An export refused because the project holds kits that are not on the
 * shelf any more (deleted, or asleep in the bin). Named rather than
 * silently dropped: a session missing a track is a different session,
 * and the person should hear which kit it is before anything is written.
 */
class ProjectKitsMissingException(val project: String, val missing: List<String>) :
    IOException("project '$project' holds kits that are not on the shelf: ${missing.joinToString()}")

/**
 * Reads and writes projects under `<shelf>/Projects/<name>/project.json`,
 * the way [KitStore] reads and writes `kit.json`.
 *
 * The shelf root is the library: kits are its direct children, with
 * `snips/`, `Instruments/` and `Rooms/` beside them, exactly as before.
 * Projects live one folder down, so `KitStore.list` — which only sees a
 * direct child holding a `kit.json` — never mistakes a project for a kit,
 * by the same mechanism that keeps `Rooms/` and `.bin/` off the shelf.
 * Nothing about where a kit lives changes; a project is a new folder that
 * points at old ones.
 *
 * Forward compatibility is [KitStore]'s: unknown fields are ignored on
 * load, an unknown version is refused.
 */
object ProjectStore {

    /** The projects' folder, beside the kits. */
    const val DIR = "Projects"
    const val FILE_NAME = "project.json"

    /**
     * The project's beat: a `groove.json` in [GrooveStore]'s own format,
     * holding the project's patterns. The format is shared on purpose —
     * a pattern is a clip whether a kit or a project holds it — so the
     * exporters that read clips from a kit will read a project's beat with
     * no new codec.
     */
    const val BEAT_DIR = "beat"

    const val VERSION = 1

    fun projectsDir(shelfRoot: File): File = File(shelfRoot, DIR)

    /** Where the project called [name] lives, whether or not it exists yet. */
    fun dir(shelfRoot: File, name: String): File {
        require(SafePath.isSafe(name)) { "project name cannot be a folder name: '$name'" }
        return File(projectsDir(shelfRoot), name)
    }

    fun beatDir(projectDir: File): File = File(projectDir, BEAT_DIR)

    fun exists(shelfRoot: File, name: String): Boolean =
        SafePath.isSafe(name) && File(dir(shelfRoot, name), FILE_NAME).isFile

    /** [base], or `<base> 2`, `<base> 3`… — the first name no project on the shelf holds. */
    fun freshName(shelfRoot: File, base: String): String {
        val taken = list(shelfRoot).map { it.name }.toSet()
        if (base !in taken) return base
        var n = 2
        while ("$base $n" in taken) n++
        return "$base $n"
    }

    // ---- Rename, bin, restore (the project row's ⋮ menu) ----

    /**
     * The bin beside the kits' own: a project sleeps at
     * `.bin/<name>-<millis>/project.json`, the folder moved whole, so its
     * beat, its kept song and everything else in it come back with it.
     * Same folder name as `KitShelf.BIN_DIR` (a test in `:app` pins the
     * two), so the one sweep that empties kits past their days empties
     * projects with them, and `KitStore.list` never sees a project there
     * (it has no `kit.json`).
     */
    const val BIN_DIR = ".bin"

    /** A hidden file in [DIR] naming kits a person took out of every project; see [release]. */
    private const val RELEASED_FILE = ".released"

    /** One project asleep in the bin: where it lies, what it holds, when it went. */
    data class Binned(val dir: File, val project: Project, val binnedAtMillis: Long)

    /** A rename refused because a project already answers to that name. */
    class NameTakenException(val name: String) : IOException("a project called '$name' already exists")

    /**
     * Kits no project holds *on purpose*. [ProjectMigration.adopt] gives every
     * unheld kit a project of its own name each time the shelf opens, which
     * is right for a kit that just arrived and wrong for one a person has
     * just deleted a project over, or taken out of its last project: the
     * shelf would answer by making the project again. [trash] and
     * [releaseKit] write a kit here when no project holds it any more;
     * `adopt` skips what is here; holding the kit again (`unrelease`) puts
     * it back under the old rule. A kit named here that has since left the
     * shelf costs nothing.
     */
    fun released(shelfRoot: File): Set<String> {
        val file = File(projectsDir(shelfRoot), RELEASED_FILE)
        if (!file.isFile) return emptySet()
        return file.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    /** Adds [kits] to [released]. */
    fun release(shelfRoot: File, kits: Collection<String>) = writeReleased(shelfRoot, released(shelfRoot) + kits)

    /** Takes [kits] out of [released]: a project holds them again, or a person opened them. */
    fun unrelease(shelfRoot: File, kits: Collection<String>) {
        val now = released(shelfRoot)
        if (now.any { it in kits }) writeReleased(shelfRoot, now - kits.toSet())
    }

    private fun writeReleased(shelfRoot: File, kits: Set<String>) {
        projectsDir(shelfRoot).mkdirs()
        val file = File(projectsDir(shelfRoot), RELEASED_FILE)
        if (kits.isEmpty()) file.delete() else AtomicFile.writeText(file, kits.sorted().joinToString("\n") + "\n")
    }

    /** Call after a kit is taken out of a project: if no project holds it now, [release] it so the shelf leaves it unheld. */
    fun releaseIfUnheld(shelfRoot: File, kitFolder: String) {
        val held = list(shelfRoot).mapNotNull { runCatching { load(it) }.getOrNull() }.any { it.holds(kitFolder) }
        if (!held) release(shelfRoot, listOf(kitFolder))
    }

    /** Every name under `Projects/`, lower-cased: a card's FAT folders do not tell `Late` from `late`. */
    private fun occupied(shelfRoot: File): Set<String> =
        projectsDir(shelfRoot).list()?.map { it.lowercase(Locale.ROOT) }?.toSet() ?: emptySet()

    /**
     * RENAME: the folder `Projects/<oldName>/` becomes `Projects/<newName>/`
     * and `name` in its `project.json` follows. Everything keyed by the
     * folder rides along because it lives inside it (the beat, the kept
     * song). [newName] is trimmed, and must be a card-safe name
     * ([Names.isMpcSafe], no leading dot); it is refused when any other
     * project already answers to it, ignoring case ([NameTakenException]).
     * Changing only the case of the project's own name is allowed.
     * Renaming to the name it has is a no-op. Throws [IllegalArgumentException]
     * for a name that cannot be one and [IOException] when the project
     * cannot be read or moved; on a failed write the folder moves back, so
     * a refused rename leaves the project as it was.
     */
    fun rename(shelfRoot: File, oldName: String, newName: String): Project {
        val target = newName.trim()
        require(Names.isMpcSafe(target) && SafePath.isSafe(target) && !target.startsWith(".")) {
            "project name cannot be a folder name: '$newName'"
        }
        val oldDir = dir(shelfRoot, oldName)
        val project = load(oldDir)
        if (target == oldName) return project
        if (target.lowercase(Locale.ROOT) in occupied(shelfRoot) - oldName.lowercase(Locale.ROOT)) {
            throw NameTakenException(target)
        }
        val newDir = dir(shelfRoot, target)
        if (!moveDir(oldDir, newDir)) throw IOException("could not move $oldDir to $newDir")
        val renamed = project.copy(name = target)
        try {
            save(shelfRoot, renamed)
        } catch (e: Exception) {
            moveDir(newDir, oldDir)
            throw e
        }
        return renamed
    }

    /**
     * DELETE: the project's folder moves, whole, to `.bin/<name>-<millis>/`
     * (a counter goes in before the stamp when that exact name is taken) and
     * the bin keeps it as long as it keeps a kit. The kits it held are not
     * touched; any that no other project holds are [release]d first, so the
     * shelf does not re-adopt them into projects of their own the next time
     * it opens. Returns where the project now lies. Throws [IOException]
     * when it cannot be read or moved, having changed nothing that matters
     * (a kit released for a project that stayed is still held by it).
     */
    fun trash(shelfRoot: File, name: String, nowMillis: Long = System.currentTimeMillis()): File {
        val projectDir = dir(shelfRoot, name)
        val project = load(projectDir)
        val bin = File(shelfRoot, BIN_DIR).apply { mkdirs() }
        var binName = "$name-$nowMillis"
        var n = 2
        while (File(bin, binName).exists()) binName = "$name-${n++}-$nowMillis"
        val dest = File(bin, binName)
        val others = list(shelfRoot).filter { it.name != projectDir.name }
            .mapNotNull { runCatching { load(it) }.getOrNull() }.flatMap { it.kits }.toSet()
        release(shelfRoot, project.kits.filter { it !in others })
        if (!moveDir(projectDir, dest)) throw IOException("could not move $projectDir to $dest")
        return dest
    }

    /** What [followKitRename] changed: the live projects it rewrote, and the project folders it could not. */
    class KitRenameFollowed(val updated: List<Project>, val failed: List<File>)

    /**
     * A kit's folder renamed on the shelf from [from] to [to]: every project
     * that held it holds it under the new name ([Project.withKitRenamed]),
     * in the same place and still active if it was, so the kit does not
     * read as gone (left out of [kitDirs], named by [missingKits], refused
     * by an export). The song kept for it in each project moves too
     * ([SongPlanStore.rename]). Projects asleep in the bin are rewritten
     * where they lie, so a restore finds the kit; a released kit stays
     * released under its new name.
     *
     * One project that cannot be read or written does not stop the rest;
     * its folder comes back in [KitRenameFollowed.failed].
     */
    fun followKitRename(shelfRoot: File, from: String, to: String): KitRenameFollowed {
        if (from == to) return KitRenameFollowed(emptyList(), emptyList())
        val updated = mutableListOf<Project>()
        val failed = mutableListOf<File>()
        val binDirs = File(shelfRoot, BIN_DIR).listFiles { f: File -> f.isDirectory && File(f, FILE_NAME).isFile }?.toList() ?: emptyList()
        for ((dir, live) in list(shelfRoot).map { it to true } + binDirs.map { it to false }) {
            try {
                val project = load(dir)
                if (project.holds(from)) {
                    val renamed = project.withKitRenamed(from, to)
                    AtomicFile.writeText(File(dir, FILE_NAME), Json.write(toJson(renamed)) + "\n")
                    if (live) updated += renamed
                }
                SongPlanStore.rename(dir, from, to)
            } catch (e: Exception) {
                failed += dir
            }
        }
        val released = released(shelfRoot)
        if (from in released) writeReleased(shelfRoot, released - from + to)
        return KitRenameFollowed(updated, failed)
    }

    /** The projects asleep in the bin, most recently binned first; a folder with no readable `project.json` is skipped. */
    fun binned(shelfRoot: File): List<Binned> =
        File(shelfRoot, BIN_DIR).listFiles { f: File -> f.isDirectory && File(f, FILE_NAME).isFile }
            ?.mapNotNull { d -> runCatching { Binned(d, load(d), binnedAt(d)) }.getOrNull() }
            ?.sortedByDescending { it.binnedAtMillis }
            ?: emptyList()

    /** The wall-clock millis [trash] stamped last in a bin folder's name; the folder's mtime when it does not parse. */
    fun binnedAt(binDir: File): Long = binDir.name.substringAfterLast('-').toLongOrNull() ?: binDir.lastModified()

    /**
     * RESTORE: [binned] back under `Projects/`, under its own name when no
     * project answers to it (ignoring case), else `<name> 2`, `<name> 3`…
     * — the kits' own rule (`KitShelf.restoreKit`): a restore never
     * overwrites and never refuses over a name, and the name in
     * `project.json` is rewritten to the one that landed. The kits it holds
     * are [unrelease]d, being held again. Throws [IOException] when the bin
     * folder is gone or unreadable or the move fails.
     */
    fun restore(shelfRoot: File, binned: File): Project {
        if (!binned.isDirectory) throw IOException("not in the bin any more: $binned")
        val project = load(binned)
        val taken = occupied(shelfRoot)
        var name = project.name
        var n = 2
        while (name.lowercase(Locale.ROOT) in taken || name.startsWith(".")) name = "${project.name.trimStart('.')} ${n++}"
        projectsDir(shelfRoot).mkdirs()
        val dest = dir(shelfRoot, name)
        if (!moveDir(binned, dest)) throw IOException("could not move $binned to $dest")
        val restored = project.copy(name = name)
        save(shelfRoot, restored)
        unrelease(shelfRoot, project.kits)
        return restored
    }

    /** [from] to [to]: a rename, else copy-then-delete where the rename cannot cross filesystems. */
    private fun moveDir(from: File, to: File): Boolean {
        if (from.renameTo(to)) return true
        if (!from.copyRecursively(to, overwrite = false)) {
            to.deleteRecursively()
            return false
        }
        return from.deleteRecursively()
    }

    /** Writes `project.json` into the project's folder, creating it. */
    fun save(shelfRoot: File, project: Project): File {
        val dir = dir(shelfRoot, project.name)
        dir.mkdirs()
        require(dir.isDirectory) { "not a directory: $dir" }
        val file = File(dir, FILE_NAME)
        AtomicFile.writeText(file, Json.write(toJson(project)) + "\n")
        return file
    }

    @Throws(IOException::class)
    fun load(dir: File): Project {
        val file = File(dir, FILE_NAME)
        if (!file.isFile) throw IOException("no $FILE_NAME in $dir")
        return fromJson(Json.parse(file.readText(Charsets.UTF_8)))
    }

    /** Every project folder under the shelf, sorted by name; none when the folder is absent. */
    fun list(shelfRoot: File): List<File> =
        projectsDir(shelfRoot).listFiles { f: File -> f.isDirectory && File(f, FILE_NAME).isFile }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    /**
     * The held kits that are on the shelf, as folders, in the project's
     * order — `SessionBuilder.build`'s `kitDirs`. A held kit whose folder is
     * gone (deleted, or asleep in the bin) is left out here and named by
     * [missingKits]; a project never fails to open because one of its kits
     * went away.
     */
    fun kitDirs(shelfRoot: File, project: Project): List<File> =
        project.kits.map { File(shelfRoot, it) }.filter { File(it, KitStore.FILE_NAME).isFile }

    /** The held kits whose folder has no `kit.json` any more. */
    fun missingKits(shelfRoot: File, project: Project): List<String> =
        project.kits.filterNot { File(File(shelfRoot, it), KitStore.FILE_NAME).isFile }

    /**
     * Where the active kit sits in [kitDirs] — the list's own order — or
     * -1 when the project has no active kit or its folder is gone. The
     * exporters route the project's beat by this index rather than by
     * folder, so a caller that stages its kits elsewhere (the CLI's worn
     * twins) can still say which track is the active one.
     */
    fun activeIndex(shelfRoot: File, project: Project): Int {
        val present = project.kits.filter { File(File(shelfRoot, it), KitStore.FILE_NAME).isFile }
        return project.activeKit?.let { present.indexOf(it) } ?: -1
    }

    /** The project's patterns, oldest first; empty when it has none. */
    fun beat(projectDir: File): List<Mpc3Clip> = GrooveStore.load(beatDir(projectDir))

    /**
     * Writes the project's patterns. [lanePads] (a kit's [GrooveStore.loadLanePads])
     * travels with the clips, so a pattern whose lanes play other pads draws and
     * plays the same from the project; null keeps what the file already holds.
     * [names] (its [GrooveStore.loadNames]) and [nextProg] (its [GrooveStore.loadNextProg])
     * travel the same way: a pattern keeps the name it was given, and a number a
     * discarded pattern held is not handed to a new one in the project's copy either.
     * Null names keep the file's; [nextProg] only raises the stored mark.
     */
    fun saveBeat(
        projectDir: File,
        clips: List<Mpc3Clip>,
        lanePads: Map<String, List<Int>>? = null,
        names: Map<String, String>? = null,
        nextProg: Int? = null,
    ): File = GrooveStore.save(beatDir(projectDir), clips, lanePads, names, nextProg)

    /** The project's pattern display names, by clip name; empty when it has none. */
    fun beatNames(projectDir: File): Map<String, String> = GrooveStore.loadNames(beatDir(projectDir))

    /** The project's high-water mark for pattern numbers, 0 when it has none. */
    fun beatNextProg(projectDir: File): Int = GrooveStore.loadNextProg(beatDir(projectDir))

    /** The project's per-pattern lane maps, by clip name; empty when it has none. */
    fun beatLanePads(projectDir: File): Map<String, List<Int>> = GrooveStore.loadLanePads(beatDir(projectDir))

    fun clearBeat(projectDir: File): Boolean = GrooveStore.delete(beatDir(projectDir))

    private fun toJson(project: Project): JsonValue = JsonValue.Obj(
        linkedMapOf<String, JsonValue>(
            "version" to JsonValue.Num(VERSION.toDouble()),
            "name" to JsonValue.Str(project.name),
            "kits" to JsonValue.Arr(project.kits.map { JsonValue.Str(it) }),
        ).also { root ->
            project.activeKit?.let { root["activeKit"] = JsonValue.Str(it) }
            project.tempoBpm?.let { root["tempoBpm"] = JsonValue.Num(it.toDouble()) }
            project.key?.let {
                root["key"] = JsonValue.Obj(
                    linkedMapOf(
                        "root" to JsonValue.Num(it.rootSemitone.toDouble()),
                        "scale" to JsonValue.Str(it.scale.name),
                    ),
                )
            }
        },
    )

    private fun fromJson(root: JsonValue): Project {
        val obj = root.obj()
        val version = obj["version"]?.int() ?: throw JsonException("$FILE_NAME has no version")
        if (version != VERSION) {
            throw JsonException("$FILE_NAME version $version is not supported (this build reads $VERSION)")
        }
        val name = obj["name"]?.str() ?: throw JsonException("$FILE_NAME has no name")
        val kits = obj["kits"]?.arr().orEmpty().map { it.str() }
        val key = (obj["key"] as? JsonValue.Obj)?.let { k ->
            KeySpec(
                rootSemitone = k.entries["root"]?.int() ?: throw JsonException("key has no root"),
                scale = k.entries["scale"]?.str()?.let { s ->
                    Scale.entries.firstOrNull { it.name == s }
                        ?: throw JsonException("unknown scale '$s' in $FILE_NAME key")
                } ?: throw JsonException("key has no scale"),
            )
        }
        return Project(
            name = name,
            kits = kits,
            // A file that names no active kit opens on the first held one,
            // the model's own default, rather than refusing.
            activeKit = obj["activeKit"]?.str() ?: kits.firstOrNull(),
            tempoBpm = obj["tempoBpm"]?.num()?.toFloat(),
            key = key,
        )
    }
}
