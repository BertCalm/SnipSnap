package com.snipsnap.xpm

import java.io.File

/**
 * Rewrites the golden file from the current writer.
 *
 * Run via `gradle :xpm:regenerateGolden`. Only do this when a format change is
 * deliberate — the golden file exists to make accidental drift loud, and
 * regenerating it to make a red test go green defeats the point. Any
 * regeneration needs re-verifying on hardware before it ships.
 */
object GoldenGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val target = File(args.firstOrNull() ?: "src/test/resources/golden/SnipSnapRef16.xpm")
        target.parentFile.mkdirs()
        target.writeText(XpmWriter().write(XpmWriterTest.goldenProgram()), Charsets.UTF_8)
        println("wrote ${target.absolutePath} (${target.length()} bytes)")
    }
}
