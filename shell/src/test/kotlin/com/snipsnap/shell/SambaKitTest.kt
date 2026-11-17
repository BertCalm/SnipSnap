package com.snipsnap.shell

import java.nio.file.Files
import kotlin.test.*

class SambaKitTest {
    @Test fun `native Samba patches regenerate through saving playback and export`() {
        val root=Files.createTempDirectory("native-samba-proof").toFile()
        try { assertEquals(16,SambaKitProof.build(root).pads.size) } finally { root.deleteRecursively() }
    }
}
