package com.snipsnap.shell

import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import java.io.File

/** A member kit present on the shelf: its id, its folder and its kit. */
data class Member(val kitId: String, val dir: File, val kit: Kit)

/**
 * An expansion's members, looked up on the shelf by id at the moment they
 * are wanted. A renamed kit is found under its new folder, a binned one is
 * simply absent until it is restored, and only permanent deletion needs
 * [ExpansionStore.prune] — the shelf itself carries no expansion bookkeeping.
 */
object ExpansionMembers {

    /** Every kit folder directly under [shelfRoot] that has an id, by id. The bin and the shelf's other folders hold no kit.json of their own, so they never appear. */
    fun index(shelfRoot: File): Map<String, Member> =
        KitStore.list(shelfRoot).mapNotNull { dir ->
            val kit = runCatching { KitStore.load(dir) }.getOrNull() ?: return@mapNotNull null
            kit.id?.let { it to Member(it, dir, kit) }
        }.toMap()

    /** [expansion]'s members present in [index], in the order they joined. */
    fun present(expansion: Expansion, index: Map<String, Member>): List<Member> =
        expansion.memberKitIds.mapNotNull { index[it] }

    /** The seed: the seed kit when present, else the first present member, else null. */
    fun seed(expansion: Expansion, index: Map<String, Member>): Member? =
        expansion.seedKitId?.let { index[it] }?.takeIf { it.kitId in expansion.memberKitIds }
            ?: present(expansion, index).firstOrNull()

    /**
     * Ids of kits on the shelf or in its bin (a binned kit can come back, so
     * prune keeps it); null when any kit folder there would not read, because
     * a shelf that did not read whole cannot say what is gone for good.
     */
    fun liveIds(shelfRoot: File, binDir: File): Set<String>? {
        val ids = HashSet<String>()
        for (dir in KitStore.list(shelfRoot) + KitStore.list(binDir)) {
            val kit = runCatching { KitStore.load(dir) }.getOrNull() ?: return null
            kit.id?.let { ids += it }
        }
        return ids
    }

    /** [ExpansionStore.prune] against [liveIds], or nothing at all (null) when the shelf did not read whole. */
    fun pruneSafely(store: ExpansionStore, shelfRoot: File, binDir: File): Int? =
        liveIds(shelfRoot, binDir)?.let { store.prune(it) }
}
