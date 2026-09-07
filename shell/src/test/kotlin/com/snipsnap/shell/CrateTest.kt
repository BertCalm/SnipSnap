package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.Severity
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrateTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("crate").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun library(): File {
        val root = File(temp, "library").apply { mkdirs() }
        val a = KitBuilderModel.create("Crate A", File(root, "Crate A"))
        a.assign(1, DrumSynth.kick(), DrumClass.KICK)
        a.assign(2, DrumSynth.snare(seed = 2), DrumClass.SNARE)
        a.assign(3, DrumSynth.closedHat(), DrumClass.HAT_CLOSED)
        a.save()
        val b = KitBuilderModel.create("Crate B", File(root, "Crate B"))
        b.assign(1, DrumSynth.kick(), DrumClass.KICK) // the planted duplicate: same synth, same bytes
        b.assign(2, DrumSynth.snare(seed = 9, noiseMix = 0.7f), DrumClass.SNARE)
        b.assign(3, DrumSynth.clap(), DrumClass.CLAP)
        b.save()
        return root
    }

    @Test
    fun `the index measures once and the second pass runs on cache alone`() {
        val root = library()
        val first = Crate.index(root)
        assertEquals(6, first.entries.size)
        assertEquals(6, first.extracted)
        assertEquals(0, first.fromCache)
        assertTrue(File(root, Crate.INDEX_NAME).isFile, "the index persists beside the kits")

        val second = Crate.index(root)
        assertEquals(6, second.entries.size)
        assertEquals(0, second.extracted, "unchanged audio is never re-measured")
        assertEquals(6, second.fromCache)
        assertEquals(
            first.entries.map { it.file to it.vector }, second.entries.map { it.file to it.vector },
            "the cache carries the same measurements",
        )
    }

    @Test
    fun `dupes finds the planted twin and picks rank a class`() {
        val root = library()
        val index = Crate.index(root)

        val dupes = Crate.dupes(index)
        assertEquals(1, dupes.size, "exactly the planted duplicate: $dupes")
        val (x, y, d) = dupes.single()
        assertEquals(
            setOf("Crate A" to 1, "Crate B" to 1), setOf(x.kitName to x.slot, y.kitName to y.slot),
            "the two kicks are the same sound",
        )
        assertTrue(d < 1e-4f, "identical audio sits at distance ~0")

        val snares = Crate.pick(index, DrumClass.SNARE, top = 8)
        assertEquals(2, snares.size)
        assertTrue(snares.all { it.storedClass == DrumClass.SNARE })
        assertTrue(snares[0].confidence >= snares[1].confidence, "confidence ranks the picks")
        assertTrue(Crate.pick(index, DrumClass.TOM).isEmpty(), "no toms, no picks")
    }

    @Test
    fun `the best-of kit assembles one strongest pad per class and passes preflight`() {
        val root = library()
        val index = Crate.index(root)
        val model = Crate.build(root, index, "Best Of", File(temp, "Best Of"))

        val classes = model.kit.pads.map { it.drumClass }.toSet()
        assertEquals(
            setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.HAT_CLOSED, DrumClass.CLAP), classes,
            "one pad per class the crate holds",
        )
        val findings = Preflight.check(model.kit, model.kitDir)
        assertTrue(findings.none { it.severity == Severity.FAIL }, "the built kit is exportable: $findings")
        // The pads are real copies, playable without the source library.
        for (pad in model.kit.pads) {
            assertTrue(WavReader.read(File(model.kitDir, pad.sampleFile)).frameCount > 0)
        }
    }
}
