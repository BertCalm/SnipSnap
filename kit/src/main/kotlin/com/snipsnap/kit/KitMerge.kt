package com.snipsnap.kit

import com.snipsnap.xpm.PadNoteMap
import java.io.File

/**
 * Bank B, earned not invented: kit B's bank A lands on the target's pads
 * 17–32 with everything carried — colours, mute groups, tuning, velocity
 * layers, recipes, provenance — and every sample copied byte-identical
 * under a re-prefixed stem (`A03_Snare_01` arrives as `B03_Snare_01`).
 * The complement of `remixBankB`, which invents its twins.
 *
 * The merge writes a **new** kit folder; both sources stay untouched.
 * Kit A brings its identity: name aside, its key, tempo and `groove.json`
 * ride along. A target whose bank B is already occupied refuses unless
 * [replace] says to swap it out.
 */
object KitMerge {

    /** Bank A is 1..16; the merged pads land at slot + 16. */
    const val BANK_OFFSET = 16

    fun merge(
        aDir: File,
        bDir: File,
        destDir: File,
        name: String = destDir.name,
        replace: Boolean = false,
    ): Kit {
        require(Names.isMpcSafe(name)) { "kit name isn't MPC-safe: '$name'" }
        require(!File(destDir, KitStore.FILE_NAME).exists()) {
            "destination is already a kit: $destDir"
        }
        val a = KitStore.load(aDir)
        val b = KitStore.load(bDir)

        val occupied = a.pads.filter { it.slot in (BANK_OFFSET + 1)..(2 * BANK_OFFSET) }
        require(occupied.isEmpty() || replace) {
            "bank B of '${a.name}' is occupied (${occupied.size} pads) - pass replace to swap it out"
        }
        val bBank = b.pads.filter { it.slot in 1..BANK_OFFSET }
        require(bBank.isNotEmpty()) { "'${b.name}' has nothing on bank A to merge" }

        destDir.mkdirs()

        // Kit A's pads (minus a replaced bank B) come over verbatim - plus
        // a provenance stamp naming the parent, so the lineage can walk it.
        val kept = a.pads.filter { it.slot !in (BANK_OFFSET + 1)..(2 * BANK_OFFSET) }
            .map { it.copy(source = it.source + mapOf("mergedFrom" to a.name)) }
        for (pad in kept) {
            copySample(aDir, destDir, pad.sampleFile)
            pad.velocityLayers.forEach { copySample(aDir, destDir, it.sampleFile) }
        }

        // Kit B's bank arrives re-prefixed for its new slots.
        val moved = bBank.map { pad ->
            val slot = pad.slot + BANK_OFFSET
            val newFile = renamed(destDir, pad.sampleFile, slot)
            File(bDir, pad.sampleFile).copyTo(File(destDir, newFile))
            val layers = pad.velocityLayers.map { layer ->
                if (layer.sampleFile == pad.sampleFile) {
                    layer.copy(sampleFile = newFile)
                } else {
                    val f = renamed(destDir, layer.sampleFile, slot)
                    File(bDir, layer.sampleFile).copyTo(File(destDir, f))
                    layer.copy(sampleFile = f)
                }
            }
            pad.copy(
                slot = slot,
                sampleFile = newFile,
                displayName = newFile.substringBeforeLast('.'),
                velocityLayers = layers,
                source = pad.source + mapOf("mergedFrom" to b.name),
            )
        }

        val merged = Kit(
            name = name,
            pads = (kept + moved).sortedBy { it.slot },
            key = a.key,
            tempoBpm = a.tempoBpm,
        )
        KitStore.save(merged, destDir)
        // A's grooves are the kit's grooves; they follow the folder.
        File(aDir, GrooveStore.FILE_NAME).takeIf { it.isFile }
            ?.copyTo(File(destDir, GrooveStore.FILE_NAME), overwrite = true)
        return merged
    }

    private fun copySample(from: File, dest: File, fileName: String) {
        val target = File(dest, fileName)
        if (!target.exists()) File(from, fileName).copyTo(target)
    }

    /**
     * `A03_Snare_01.wav` at slot 19 becomes `B03_Snare_01.wav`; a stem with
     * no pad prefix just gains one. Collisions count upward — merging a kit
     * with itself still lands every file.
     */
    private fun renamed(destDir: File, fileName: String, slot: Int): String {
        val stem = fileName.substringBeforeLast('.')
            .replace(Regex("^[A-H]\\d{2}_"), "")
        val label = PadNoteMap.labelForPad(slot)
        var candidate = "${label}_$stem.wav"
        var n = 2
        while (File(destDir, candidate).exists()) {
            candidate = "${label}_${stem}_$n.wav"
            n++
        }
        return candidate
    }
}
