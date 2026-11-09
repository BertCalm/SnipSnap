package com.snipsnap.synth

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File

/** Export the playable three-voice native listening kit, including its recipes. */
object HalobarKitGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val root=File(args.firstOrNull() ?: "../testkit")
        val work=File(root,".halobar-work")
        work.deleteRecursively()
        val kit=KitAssembler.assembleArranged("SnipSnap Halobar Kit",SynthKits.halobar(),work)
        val result=KitExporter.exportProgramFolder(kit,work,root,overwrite=true)
        work.deleteRecursively()
        println("wrote ${result.directory.absolutePath} (${result.samples.size} samples + program)")
    }
}
