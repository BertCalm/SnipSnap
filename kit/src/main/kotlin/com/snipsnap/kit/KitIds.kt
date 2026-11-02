package com.snipsnap.kit

import java.io.File
import kotlin.random.Random

/**
 * A kit's stable id: the name an expansion uses for its members, the same
 * across rename, bin and restore because it lives inside the kit's own
 * kit.json. Written the first time a kit joins an expansion and never on
 * an ordinary read, so a kit nobody gathers keeps its bytes.
 */
object KitIds {
    private const val HEX = "0123456789abcdef"

    /** The kit's id, writing a fresh one into its kit.json first when it has none. */
    fun ensure(kitDir: File, random: Random = Random.Default): String {
        val kit = KitStore.load(kitDir)
        kit.id?.let { return it }
        val id = fresh(random)
        KitStore.save(kit.copy(id = id), kitDir)
        return id
    }

    /** The id already in the kit's kit.json, or null when there is none or the kit will not read; never writes. */
    fun read(kitDir: File): String? = runCatching { KitStore.load(kitDir).id }.getOrNull()

    /** 12 lowercase hex characters. */
    fun fresh(random: Random): String = String(CharArray(12) { HEX[random.nextInt(HEX.length)] })
}
