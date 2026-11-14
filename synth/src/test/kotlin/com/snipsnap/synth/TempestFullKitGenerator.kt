package com.snipsnap.synth

import java.io.File

object TempestFullKitGenerator {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size <= 1)
        val folder = File(args.firstOrNull() ?: "testkit/tempest-full-kit").canonicalFile
        val archive = TempestKitGenerator.generate(folder, fullKit = true)
        println("TEMPEST accepted full kit: ${archive.absolutePath} (${archive.length()} bytes)")
    }
}
