package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Similar
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
    fun `a kit under a hidden folder is not in the library`() {
        val root = library()
        File(root, "Crate A").copyRecursively(File(root, ".bin/Crate A-1"))
        File(root, "Crate B").copyRecursively(File(root, ".landing-42/Crate B"))
        val index = Crate.index(root)
        assertEquals(6, index.entries.size, "the two live kits only: ${index.entries.map { it.file }}")
        assertTrue(index.entries.none { it.file.startsWith(".") }, "nothing under .bin/ or .landing-*")
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

    @Test
    fun `a cached vector the extractor could not have written is re-measured, not trusted`() {
        val root = File(temp, "trust").apply { mkdirs() }
        val m = KitBuilderModel.create("One", File(root, "One"))
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.save()
        assertEquals(Similar.DIMENSIONS, Similar.vector(FeatureExtractor.extract(DrumSynth.kick())).size, "the named dimension is the real one")

        val first = Crate.index(root)
        assertEquals(2, first.extracted)
        assertTrue(first.entries.all { plausible(it.vector) })

        // Hand-edit the cache the way another tool or a stray editor might:
        // one vector cut to three numbers, one with a value past the moon.
        // Neither is a measurement this code makes, and a distance to
        // either would be a made-up number - so both pads measure again.
        val indexFile = File(root, Crate.INDEX_NAME)
        val vectors = Regex("\"vector\":\\s*\\[[^\\]]*\\]").findAll(indexFile.readText()).toList()
        assertEquals(2, vectors.size)
        val doctored = StringBuilder(indexFile.readText())
        doctored.replace(vectors[1].range.first, vectors[1].range.last + 1, "\"vector\":[1e300,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1]")
        doctored.replace(vectors[0].range.first, vectors[0].range.last + 1, "\"vector\":[0.1,0.2,0.3]")
        indexFile.writeText(doctored.toString())

        val second = Crate.index(root)
        assertEquals(2, second.extracted, "both doctored entries are measured again, not believed")
        assertTrue(second.entries.all { plausible(it.vector) })

        // An untouched cache is still a cache.
        val third = Crate.index(root)
        assertEquals(0, third.extracted)
        assertEquals(2, third.fromCache)
    }

    private fun plausible(v: List<Float>) = v.size == Similar.DIMENSIONS && v.all { it in 0f..1f }

    @Test
    fun `distance refuses to compare vectors of different lengths, and the label is an identifier`() {
        assertEquals(Float.POSITIVE_INFINITY, Crate.distance(listOf(0.5f), listOf(0.5f, 0.5f)))
        assertEquals(Float.POSITIVE_INFINITY, Crate.distance(listOf(0.5f, 0.5f), listOf(0.5f)), "the same answer both ways round")
        assertEquals(Float.POSITIVE_INFINITY, Crate.distance(emptyList(), listOf(0.1f)), "an empty vector is not close to anything")
        assertEquals(0f, Crate.distance(listOf(0.3f, 0.4f), listOf(0.3f, 0.4f)))
        val e = Crate.Entry("K", "K", 17, "p", "K/p.wav", 0L, 0L, DrumClass.KICK, DrumClass.KICK, 1f, List(Similar.DIMENSIONS) { 0.5f })
        assertEquals("B01", e.label)
    }
}
