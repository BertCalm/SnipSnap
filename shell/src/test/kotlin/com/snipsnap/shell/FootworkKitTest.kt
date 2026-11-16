package com.snipsnap.shell

import com.snipsnap.kit.KitAssembler
import com.snipsnap.synth.FootworkKits
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class FootworkKitTest {
    @Test fun `all 648 cells retain native and SFZ cycling while MPC3 retains audio and cycle fields`() {
        val root=createTempDirectory("footwork-proof").toFile()
        try {
            for(genre in FootworkKits.Kit.entries) {
                val starter=assertNotNull(StarterKits.byId(genre.id))
                assertEquals(genre.bpm.toFloat(),starter.tempoBpm)
                assertEquals(1,StarterShelf.GROUPS.count { genre.id in it.ids })
                val pads=FootworkKits.build(genre); FootworkKitProof.sources(genre,pads)
                val dir=root.resolve("${genre.id}/native")
                val kit=KitAssembler.assembleArranged("Footwork ${genre.title}",pads,dir,tempoBpm=genre.bpm.toFloat())
                FootworkKitProof.roundTrip(pads,kit,dir,dir.parentFile)
            }
        } finally { root.deleteRecursively() }
    }
}
