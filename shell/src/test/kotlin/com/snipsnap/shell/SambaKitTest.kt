package com.snipsnap.shell

import java.nio.file.Files
import kotlin.test.*

class SambaKitTest {
    @Test fun `Samba starter is reachable once at its own tempo`() {
        val starter=assertNotNull(StarterKits.byId("samba-bateria"))
        assertEquals(140f,starter.tempoBpm)
        assertEquals(1,StarterShelf.GROUPS.count { "samba-bateria" in it.ids })
        assertTrue(EngineCategory.DRUMS in assertNotNull(EngineCatalog.entry("SAMBA")).categories)
        assertEquals(com.snipsnap.synth.SambaKits.patches(),com.snipsnap.synth.SambaPresets.all())
        for(voice in com.snipsnap.synth.ParadeSamba.Voice.entries) {
            assertEquals(com.snipsnap.synth.SambaPresets.forVoice(voice),com.snipsnap.synth.Presets.forVoice("SAMBA",voice.name))
        }
    }

    @Test fun `native Samba patches regenerate through saving playback and export`() {
        val root=Files.createTempDirectory("native-samba-proof").toFile()
        try { assertEquals(16,SambaKitProof.build(root).pads.size) } finally { root.deleteRecursively() }
    }
}
