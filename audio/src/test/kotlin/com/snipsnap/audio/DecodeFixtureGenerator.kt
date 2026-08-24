package com.snipsnap.audio

import java.io.File

/**
 * Writes the canonical decode-contract fixture WAVs under
 * `reference/fixtures/decode/`. Run via
 * `./gradlew :audio:generateDecodeFixtures`. Deterministic — regenerating
 * changes nothing unless [DecodeContract]'s signal definition changes.
 */
object DecodeFixtureGenerator {

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.firstOrNull() ?: "../reference/fixtures/decode")
        val files = DecodeContract.writeFixtures(dir)
        files.forEach { println("wrote ${it.absolutePath}") }
        println("${files.size} fixtures - encode these into compressed twins and verify the decode")
    }
}
