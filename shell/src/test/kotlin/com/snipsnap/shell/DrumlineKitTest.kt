package com.snipsnap.shell

import com.snipsnap.synth.*
import java.nio.file.Files
import kotlin.test.*

class DrumlineKitTest {
    @Test fun `both complete grids survive save playback and exports`() {
        for(which in DrumlineKits.Kit.entries) {
            val dir=Files.createTempDirectory("drumline-${which.id}").toFile()
            try { assertEquals(16,DrumlineKitProof.build(which,dir).pads.size) } finally { dir.deleteRecursively() }
        }
    }
    @Test fun `both authored batteries are reachable once at their own tempo`() {
        for(which in DrumlineKits.Kit.entries) {
            val starter=assertNotNull(StarterKits.byId(which.id))
            assertEquals(which.bpm,starter.tempoBpm)
            assertEquals(1,StarterShelf.GROUPS.count { which.id in it.ids })
        }
        val field=DrumlineKits.patches(DrumlineKits.Kit.FIELD);val street=DrumlineKits.patches(DrumlineKits.Kit.STREET)
        assertEquals(5,field.indices.count { field[it].voice!=street[it].voice })
        assertEquals(DrumlineVoice.entries.toSet(), (field + street).map { it.voice }.toSet())
        assertTrue(street.all { it.macros.getValue("TUNE")<.5f && it.macros.getValue("DAMP")<.5f })
    }
}
