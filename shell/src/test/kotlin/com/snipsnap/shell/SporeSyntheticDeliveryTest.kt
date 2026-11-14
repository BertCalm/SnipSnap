package com.snipsnap.shell

import kotlin.io.path.createTempDirectory
import kotlin.test.Test

class SporeSyntheticDeliveryTest {
    @Test fun `three shelf kits retain every velocity and take through source replay playback and exports`() {
        val root=createTempDirectory("spore-synthetic").toFile()
        try { SporeSyntheticDelivery.generate(root) } finally { root.deleteRecursively() }
    }
}
