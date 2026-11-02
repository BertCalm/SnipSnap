package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.AtomicFile
import com.snipsnap.kit.Names
import java.io.File
import kotlin.random.Random

/**
 * An expansion on the phone: a named collection of shelf kits with one
 * tile. Members are kit ids ([com.snipsnap.kit.KitIds]) in the order they
 * joined, so a kit's rename, bin and restore never touch membership.
 * [identifier] is fixed at birth and never recomputed, so the MPC's cache
 * sees one pack across re-exports and renames.
 */
data class Expansion(
    val id: String,
    val title: String,
    val identifier: String,
    val memberKitIds: List<String>,
    val seedKitId: String?,
    val knobs: ArtKnobs,
) {
    val manufacturer: String get() = MANUFACTURER

    companion object {
        const val MANUFACTURER = "SnipSnap"
    }
}

/**
 * `expansions/<id>/expansion.json` under the app's files dir, one per
 * expansion. Every change is written through at once ([AtomicFile]); a file
 * this build cannot read is skipped by [list], never fatal.
 */
class ExpansionStore(val root: File, private val random: Random = Random.Default) {

    sealed interface NameCheck {
        data object Ok : NameCheck
        data object Unsafe : NameCheck
        data object Taken : NameCheck
    }

    /** Every readable expansion, by title, case aside. */
    fun list(): List<Expansion> =
        (root.listFiles { f -> f.isDirectory } ?: emptyArray())
            .mapNotNull { read(it) }
            .sortedWith(compareBy({ it.title.lowercase() }, { it.id }))

    fun get(id: String): Expansion? = read(File(root, id))

    /** Whether [title] can name an expansion: MPC-safe, and no other expansion (but [exceptId]) has it, whatever its case. */
    fun checkTitle(title: String, exceptId: String? = null): NameCheck = when {
        !Names.isMpcSafe(title) -> NameCheck.Unsafe
        list().any { it.id != exceptId && it.title.equals(title, ignoreCase = true) } -> NameCheck.Taken
        else -> NameCheck.Ok
    }

    fun create(title: String, firstKitId: String, knobs: ArtKnobs): Expansion {
        require(checkTitle(title) == NameCheck.Ok) { "expansion title refused: '$title'" }
        var id: String
        do {
            id = freshId()
        } while (File(root, id).exists())
        val e = Expansion(id, title, identifier(title, id), listOf(firstKitId), firstKitId, knobs)
        write(e)
        return e
    }

    fun add(id: String, kitId: String): Expansion = update(id) {
        if (kitId in it.memberKitIds) it else it.copy(memberKitIds = it.memberKitIds + kitId, seedKitId = it.seedKitId ?: kitId)
    }

    fun remove(id: String, kitId: String): Expansion = update(id) {
        it.copy(memberKitIds = it.memberKitIds - kitId, seedKitId = it.seedKitId.takeIf { s -> s != kitId })
    }

    fun rename(id: String, title: String): Expansion {
        require(checkTitle(title, exceptId = id) == NameCheck.Ok) { "expansion title refused: '$title'" }
        return update(id) { it.copy(title = title) }
    }

    fun setArt(id: String, knobs: ArtKnobs, seedKitId: String?): Expansion = update(id) { it.copy(knobs = knobs, seedKitId = seedKitId) }

    fun delete(id: String): Boolean {
        val dir = File(root, id)
        return dir.isDirectory && dir.deleteRecursively()
    }

    /** Drops every member not in [liveKitIds] (kits on the shelf or in its bin) from every expansion; how many memberships went. */
    fun prune(liveKitIds: Set<String>): Int {
        var gone = 0
        for (e in list()) {
            val kept = e.memberKitIds.filter { it in liveKitIds }
            if (kept.size != e.memberKitIds.size) {
                gone += e.memberKitIds.size - kept.size
                write(e.copy(memberKitIds = kept, seedKitId = e.seedKitId.takeIf { it in kept }))
            }
        }
        return gone
    }

    fun containing(kitId: String): List<Expansion> = list().filter { kitId in it.memberKitIds }

    private fun update(id: String, change: (Expansion) -> Expansion): Expansion {
        val e = get(id) ?: throw IllegalArgumentException("no expansion '$id'")
        return change(e).also { write(it) }
    }

    private fun freshId(): String = String(CharArray(6) { ID_CHARS[random.nextInt(ID_CHARS.length)] })

    private fun write(e: Expansion) {
        val m = linkedMapOf<String, JsonValue>(
            "version" to JsonValue.Num(VERSION.toDouble()),
            "title" to JsonValue.Str(e.title),
            "manufacturer" to JsonValue.Str(e.manufacturer),
            "identifier" to JsonValue.Str(e.identifier),
            "members" to JsonValue.Arr(e.memberKitIds.map { JsonValue.Str(it) }),
            "seed" to (e.seedKitId?.let { JsonValue.Str(it) } ?: JsonValue.Null),
            "art" to JsonValue.Obj(ArtSidecar.knobsFields(e.knobs)),
        )
        AtomicFile.writeText(File(File(root, e.id).apply { mkdirs() }, FILE), Json.write(JsonValue.Obj(m)))
    }

    private fun read(dir: File): Expansion? {
        val file = File(dir, FILE)
        if (!file.isFile) return null
        val obj = runCatching { Json.parse(file.readText(Charsets.UTF_8)) as? JsonValue.Obj }.getOrNull() ?: return null
        val e = obj.entries
        if ((e["version"] as? JsonValue.Num)?.value?.toInt() != VERSION) return null
        val title = (e["title"] as? JsonValue.Str)?.value?.takeIf { Names.isMpcSafe(it) } ?: return null
        val identifier = (e["identifier"] as? JsonValue.Str)?.value ?: return null
        val members = (e["members"] as? JsonValue.Arr)?.items?.mapNotNull { (it as? JsonValue.Str)?.value }?.distinct() ?: return null
        val seed = (e["seed"] as? JsonValue.Str)?.value
        val knobs = (e["art"] as? JsonValue.Obj)?.let { ArtSidecar.knobsFrom(it.entries) } ?: return null
        return Expansion(dir.name, title, identifier, members, seed, knobs)
    }

    companion object {
        const val FILE = "expansion.json"
        private const val VERSION = 1
        private const val ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

        /** `app.snipsnap.<slug>.<id>`: the slug is the title's ASCII letters and digits, lowercased, or `pack` when none. */
        fun identifier(title: String, id: String): String {
            val slug = title.filter { it.code < 128 && it.isLetterOrDigit() }.lowercase().ifBlank { "pack" }
            return "app.snipsnap.$slug.$id"
        }
    }
}
