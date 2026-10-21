package com.snipsnap.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class BellowsKeysCommandTest {
    @Test
    fun `invalid release and positional arguments refuse before creating output`() {
        val parent = Files.createTempDirectory("bellows-keys-refusal").toFile()
        try {
            val output = parent.resolve("new-output")
            for (release in listOf("NaN", "Infinity", "0.09", "1.6", "nope")) {
                assertFailsWith<CliError> {
                    BellowsKeysCommand.run(listOf("--out", output.path, "--release", release), PrintStream(ByteArrayOutputStream()))
                }
                assertFalse(output.exists())
            }
            assertFailsWith<CliError> {
                BellowsKeysCommand.run(listOf("CHAMBER", "--out", output.path), PrintStream(ByteArrayOutputStream()))
            }
            assertFalse(output.exists())
        } finally { parent.deleteRecursively() }
    }

    @Test
    fun `an existing instrument package is preserved without explicit overwrite`() {
        val parent = Files.createTempDirectory("bellows-keys-existing").toFile()
        try {
            val marker = parent.resolve("playable/keep.txt").apply { parentFile.mkdirs(); writeText("saved instrument") }
            assertFailsWith<CliError> {
                BellowsKeysCommand.run(listOf("--out", parent.path), PrintStream(ByteArrayOutputStream()))
            }
            assertEquals("saved instrument", marker.readText())
        } finally { parent.deleteRecursively() }
    }
}
