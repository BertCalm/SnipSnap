package com.snipsnap.shell

import kotlin.io.path.createTempDirectory
import kotlin.test.Test

class BreakcoreDeliveryTest {
    private fun run(name: String) {
        val root = createTempDirectory(name).toFile()
        try { BreakcoreDelivery.generate(BreakcoreFamilyDelivery.specs.getValue(name), root) } finally { root.deleteRecursively() }
    }
    @Test fun `SHATTER keeps every velocity and take through source replay, playback and exports`() = run("shatter")
    @Test fun `SOUNDCLASH keeps every velocity and take through source replay, playback and exports`() = run("soundclash")
    @Test fun `REDLINE keeps every velocity and take through source replay, playback and exports`() = run("redline")
}
