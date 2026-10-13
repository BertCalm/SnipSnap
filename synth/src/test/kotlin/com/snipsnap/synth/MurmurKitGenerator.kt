package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File

/** Sixteen dry review pads: a key scale, voice contrasts, and one settled held buffer.
 * MPC drum programs play each sample once; the audition page repeats HOLD seamlessly.
 */
object MurmurKitGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit")
        val work = File(root, ".murmur-work")
        check(!work.exists() || work.deleteRecursively()) { "Could not clear $work" }
        try {
            val kit = KitAssembler.assembleArranged("SnipSnap Murmur Kit", SynthKits.murmur(), work)
            val result = KitExporter.exportProgramFolder(kit, work, root, overwrite = true)
            File(work, "kit.json").copyTo(File(result.directory, "kit.json"), overwrite = true)
            println("wrote ${result.directory.absolutePath} (${result.samples.size} samples + program + recipes)")
        } finally {
            work.deleteRecursively()
        }
    }
}
