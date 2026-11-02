package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.AtomicFile
import com.snipsnap.kit.KitIds
import java.io.File

/**
 * The phone's expansions inside a shelf BACKUP: one file at the archive's
 * root beside the player's presets. Kits travel as `.xpn` files and come
 * home as new kits with no id, so members are recorded by kit name and
 * matched again, on restore, to the kits that just landed.
 */
object ExpansionBackup {
    const val FILE_NAME = "expansions.json"
    private const val VERSION = 1

    /** Writes every expansion to [out], members by the names of the kits now on the shelf; false (and no file) when there are none. */
    fun write(store: ExpansionStore, shelfRoot: File, out: File): Boolean {
        val all = store.list()
        if (all.isEmpty()) return false
        val index = ExpansionMembers.index(shelfRoot)
        val items = all.map { e ->
            val names = ExpansionMembers.present(e, index).map { it.kit.name }
            val seed = e.seedKitId?.let { index[it] }?.kit?.name
            JsonValue.Obj(
                linkedMapOf(
                    "title" to JsonValue.Str(e.title),
                    "identifier" to JsonValue.Str(e.identifier),
                    "members" to JsonValue.Arr(names.map { JsonValue.Str(it) }),
                    "seed" to (seed?.let { JsonValue.Str(it) } ?: JsonValue.Null),
                    "art" to JsonValue.Obj(ArtSidecar.knobsFields(e.knobs)),
                ),
            )
        }
        val root = linkedMapOf<String, JsonValue>(
            "version" to JsonValue.Num(VERSION.toDouble()),
            "expansions" to JsonValue.Arr(items),
        )
        AtomicFile.writeText(out, Json.write(JsonValue.Obj(root)))
        return true
    }

    /** What a restore did: how many expansions it rebuilt, and a line for each it left alone. */
    data class Restored(val added: Int, val skipped: List<String>)

    /**
     * Rebuilds the expansions in [text] in [store] around [landed] (each
     * kit's name inside the backup → its folder on the shelf now). A member
     * that did not land is left out; an expansion already here, by its
     * identifier, is left as it is.
     */
    fun restore(text: String, store: ExpansionStore, landed: Map<String, File>): Restored {
        val root = runCatching { Json.parse(text) as? JsonValue.Obj }.getOrNull()
            ?: return Restored(0, listOf("$FILE_NAME: not readable"))
        if ((root.entries["version"] as? JsonValue.Num)?.value?.toInt() != VERSION) return Restored(0, listOf("$FILE_NAME: a later version"))
        var added = 0
        val skipped = mutableListOf<String>()
        val items = (root.entries["expansions"] as? JsonValue.Arr)?.items.orEmpty()
        for (item in items) {
            val e = (item as? JsonValue.Obj)?.entries ?: continue
            val title = (e["title"] as? JsonValue.Str)?.value ?: continue
            val identifier = (e["identifier"] as? JsonValue.Str)?.value ?: continue
            val knobs = (e["art"] as? JsonValue.Obj)?.let { ArtSidecar.knobsFrom(it.entries) }
            if (knobs == null) {
                skipped += "$title: its art is not one this build knows"
                continue
            }
            if (store.list().any { it.identifier == identifier }) {
                skipped += "$title: already on this phone"
                continue
            }
            val names = (e["members"] as? JsonValue.Arr)?.items?.mapNotNull { (it as? JsonValue.Str)?.value }.orEmpty()
            val ids = names.mapNotNull { name -> landed[name]?.let { KitIds.ensure(it) } }
            val seed = (e["seed"] as? JsonValue.Str)?.value?.let { landed[it] }?.let { KitIds.read(it) }
            runCatching { store.adopt(title, identifier, ids, seed, knobs) }
                .onSuccess { added++ }
                .onFailure { skipped += "$title: ${it.message ?: "could not be restored"}" }
        }
        return Restored(added, skipped)
    }
}
