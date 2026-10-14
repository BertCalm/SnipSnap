package com.snipsnap.kit

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scale
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.mpc3.Mpc3Clip
import java.io.File
import java.io.IOException

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

    /** The kit switcher above the grid: [kitFolderName] becomes the active kit. Must be held. */
    fun activated(kitFolderName: String): Project {
        require(holds(kitFolderName)) { "cannot activate '$kitFolderName': not held by '$name'" }
        return copy(activeKit = kitFolderName)
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

    fun saveBeat(projectDir: File, clips: List<Mpc3Clip>): File = GrooveStore.save(beatDir(projectDir), clips)

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
