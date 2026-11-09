package com.snipsnap.kit

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.io.IOException

/**
 * The songs a project keeps: `song.json`, a sibling of `project.json`,
 * holding one [SongPlan] per kit keyed by the kit's FOLDER name (the
 * project's own way to name a kit, see [Project]). A sibling file rather
 * than a field of `project.json`: the app holds a [Project] in memory and
 * rewrites `project.json` from it, which would clobber a plan written
 * behind its back, and nothing that builds a [Project] has to learn about
 * songs.
 *
 * Forward compatibility is the sidecars' ladder: unknown fields are
 * ignored on load, and a file that cannot be read (corrupt, or a VERSION
 * this build does not know) is simply no plan: SONG then plans fresh, and
 * never crashes. A save over a file from a newer build refuses rather
 * than flatten what it cannot read; every other kit's plan in the file is
 * carried through untouched.
 */
object SongPlanStore {

    const val FILE_NAME = "song.json"

    const val VERSION = 1

    private val lock = Any()

    fun file(projectDir: File): File = File(projectDir, FILE_NAME)

    /** The plan kept for [kitFolder], or null when there is none or it cannot be read. */
    fun load(projectDir: File, kitFolder: String): SongPlan? = synchronized(lock) {
        val plan = readPlans(projectDir)?.get(kitFolder) ?: return null
        runCatching { planFrom(plan) }.getOrNull()
    }

    /** Keeps [plan] for [kitFolder]. @throws IOException when it cannot be written, or the file is from a newer build. */
    @Throws(IOException::class)
    fun save(projectDir: File, kitFolder: String, plan: SongPlan) = synchronized(lock) {
        val others = existingForWrite(projectDir)
        others[kitFolder] = planJson(plan)
        write(projectDir, others)
    }

    /** The plan kept under [from] now kept under [to] (a kit renamed). No plan under [from] is no change. */
    @Throws(IOException::class)
    fun rename(projectDir: File, from: String, to: String) = synchronized(lock) {
        if (from == to || !file(projectDir).isFile) return@synchronized
        val all = existingForWrite(projectDir)
        val moved = all.remove(from) ?: return@synchronized
        all[to] = moved
        write(projectDir, all)
    }

    /** Whether [load] would find a plan for [kitFolder]. */
    fun has(projectDir: File, kitFolder: String): Boolean = load(projectDir, kitFolder) != null

    /** The raw plans of a readable file of this version; null for none, a corrupt file or another version. */
    private fun readPlans(projectDir: File): Map<String, JsonValue>? {
        val f = file(projectDir)
        if (!f.isFile) return null
        return try {
            val root = Json.parse(f.readText(Charsets.UTF_8)).obj()
            if (root["version"]?.int() != VERSION) null else root["plans"]?.obj()
        } catch (e: Exception) {
            null
        }
    }

    private fun existingForWrite(projectDir: File): LinkedHashMap<String, JsonValue> {
        val f = file(projectDir)
        if (f.isFile) {
            val version = try {
                Json.parse(f.readText(Charsets.UTF_8)).obj()["version"]?.int()
            } catch (e: Exception) {
                null
            }
            if (version != null && version > VERSION) {
                throw IOException("$FILE_NAME is from a newer version of this app (v$version); not overwriting it")
            }
        }
        return LinkedHashMap(readPlans(projectDir) ?: emptyMap())
    }

    private fun write(projectDir: File, plans: Map<String, JsonValue>) {
        projectDir.mkdirs()
        require(projectDir.isDirectory) { "not a directory: $projectDir" }
        val root = JsonValue.Obj(
            linkedMapOf(
                "version" to JsonValue.Num(VERSION.toDouble()),
                "plans" to JsonValue.Obj(plans),
            ),
        )
        AtomicFile.writeText(file(projectDir), Json.write(root) + "\n")
    }

    private fun planJson(plan: SongPlan): JsonValue = JsonValue.Obj(
        linkedMapOf(
            "seed" to JsonValue.Num(plan.seed.toDouble()),
            "sections" to JsonValue.Arr(
                plan.sections.map { s ->
                    JsonValue.Obj(
                        linkedMapOf(
                            "name" to JsonValue.Str(s.name),
                            "clip" to JsonValue.Str(s.clip),
                            "repeats" to JsonValue.Num(s.repeats.toDouble()),
                            "locked" to JsonValue.Bool(s.locked),
                            "seed" to JsonValue.Num(s.seed.toDouble()),
                            "bass" to JsonValue.Bool(s.bass),
                            "pullUp" to JsonValue.Bool(s.pullUp),
                        ),
                    )
                },
            ),
        ),
    )

    private fun planFrom(value: JsonValue): SongPlan {
        val obj = value.obj()
        val sections = obj["sections"]!!.arr().map { v ->
            val s = v.obj()
            val name = s["name"]!!.str()
            PlanSection(
                name = name,
                clip = s["clip"]!!.str(),
                repeats = s["repeats"]?.int() ?: 1,
                locked = (s["locked"] as? JsonValue.Bool)?.value ?: false,
                seed = s["seed"]?.int() ?: 0,
                // A plan kept before these fields existed: what its names meant then. Same VERSION, so an older build reads this file too.
                bass = (s["bass"] as? JsonValue.Bool)?.value ?: SectionNames.bassUnder(name),
                pullUp = (s["pullUp"] as? JsonValue.Bool)?.value ?: SectionNames.pullsUpInto(name),
            )
        }
        return SongPlan(seed = obj["seed"]?.int() ?: 0, sections = sections)
    }
}
