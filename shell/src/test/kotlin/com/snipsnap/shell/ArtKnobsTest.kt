package com.snipsnap.shell

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ArtKnobsTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("artknobs").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    @Test
    fun `every scheme has a palette and every palette an id`() {
        for (scheme in Schemes.ALL) {
            assertEquals(scheme.id.name.lowercase(), Palettes.forScheme(scheme).id, "${scheme.id} has its own palette")
        }
        assertEquals(8, Palettes.ALL.size)
        assertEquals(Palettes.ALL.size, Palettes.ALL.map { it.id }.toSet().size, "ids are distinct")
        assertSame(Palettes.RISO, Palettes.byId("riso"))
        assertNull(Palettes.byId("mauve"))
        for (p in Palettes.ALL) assertTrue(p.name == p.name.uppercase(), "${p.id} shouts its name")
    }

    @Test
    fun `the sidecar round trips`() {
        val dir = File(temp, "kit").apply { mkdirs() }
        val knobs = ArtKnobs(KitArt.Style.RINGS, Palettes.TIDE, 7, Mark.Variant.SYMBOL)
        ArtSidecar.write(dir, knobs)
        assertEquals(knobs, ArtSidecar.read(dir))
        val text = File(dir, ArtSidecar.FILE).readText()
        assertTrue(text.contains("\"seedSlot\": 7"), text)
        val auto = knobs.copy(seedSlot = null)
        ArtSidecar.write(dir, auto)
        assertEquals(auto, ArtSidecar.read(dir), "AUTO survives the trip as null")
    }

    @Test
    fun `an absent sidecar resolves to the scheme's default`() {
        val dir = File(temp, "empty").apply { mkdirs() }
        assertNull(ArtSidecar.read(dir))
        assertEquals(ArtKnobs.default(Schemes.MEMO), ArtSidecar.resolve(dir, Schemes.MEMO))
        val d = ArtKnobs.default(Schemes.MEMO)
        assertSame(Palettes.MEMO, d.palette)
        assertNull(d.seedSlot)
        assertEquals(Mark.Variant.STACKED, d.mark)
    }

    @Test
    fun `the default style is bloom`() {
        assertEquals(KitArt.Style.BLOOM, ArtKnobs.default(Schemes.VENT).style)
    }

    @Test
    fun `a stale sidecar falls back to defaults`() {
        val dir = File(temp, "stale").apply { mkdirs() }
        val file = File(dir, ArtSidecar.FILE)
        file.writeText("""{"version": 1, "style": "vortex", "palette": "vent", "seedSlot": null, "mark": "stacked"}""")
        assertNull(ArtSidecar.read(dir), "an unknown style")
        file.writeText("""{"version": 2, "style": "rings", "palette": "vent", "seedSlot": null, "mark": "stacked"}""")
        assertNull(ArtSidecar.read(dir), "a later version")
        file.writeText("""{"version": 1, "style": "rings", "palette": "vent", "seedSlot": 2, "mark": "huge"}""")
        assertNull(ArtSidecar.read(dir), "an unknown mark")
        file.writeText("not json")
        assertNull(ArtSidecar.read(dir), "not json")
        assertEquals(ArtKnobs.default(Schemes.VENT), ArtSidecar.resolve(dir, Schemes.VENT))
    }
}
