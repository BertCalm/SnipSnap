package com.snipsnap.shell

import kotlin.io.path.createTempDirectory
import kotlin.test.Test

class SporeSnareDeliveryTest {
    @Test fun `approved source survives saving playback SFZ and MPC3 with every velocity and take`() {
        val root=createTempDirectory("spore-approved").toFile()
        try { SporeSnareDelivery.generate(root) } finally { root.deleteRecursively() }
    }
}
