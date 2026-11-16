package com.snipsnap.shell

import kotlin.io.path.createTempDirectory
import kotlin.test.Test

class SplinterDeliveryTest {
    @Test fun `the shelf kit keeps every velocity and take through source replay, playback and exports`() {
        val root = createTempDirectory("splinter").toFile()
        try { SplinterDelivery.generate(root) } finally { root.deleteRecursively() }
    }
}
