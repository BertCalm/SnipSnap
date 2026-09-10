package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DoublesTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("doubles").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    /** A hand-built entry: only the vector, class and identity matter to the grouping. */
    private fun entry(kit: String, slot: Int, dc: DrumClass, vararg v: Float) = Crate.Entry(
        kitDir = kit, kitName = kit, slot = slot, padName = "${dc.name} $slot", file = "$kit/p$slot.wav",
        mtime = 0L, size = 0L, storedClass = dc, heardClass = dc, confidence = 1f, vector = v.toList(),
    )

    private fun index(vararg es: Crate.Entry) = Crate.Index(es.toList(), extracted = 0, fromCache = es.size)

    @Test
    fun `single linkage chains through a middle pad, and the cluster shows its own spread`() {
        // A—B and B—C are each 0.010 apart; A—C is 0.020. At 0.015 no pair
        // A—C qualifies, yet all three belong together through B.
        val a = entry("K1", 1, DrumClass.KICK, 0f, 0f)
        val b = entry("K1", 2, DrumClass.KICK, 0.010f, 0f)
        val c = entry("K2", 1, DrumClass.KICK, 0.020f, 0f)
        val lone = entry("K2", 2, DrumClass.SNARE, 5f, 5f)
        val clusters = Doubles.clusters(index(a, b, c, lone), within = 0.015f)
        assertEquals(1, clusters.size, "$clusters")
        val k = clusters.single()
        assertEquals(listOf(a, b, c), k.entries, "kit then slot")
        assertEquals(0.020f, k.within, 1e-6f)
        assertEquals(DrumClass.KICK, k.label)
        assertEquals(2, k.kitCount)
        assertEquals("KICK · 3 PADS IN 2 KITS · WITHIN 0.02", Doubles.headline(k))
        assertEquals("K2 · A01 · KICK 1", Doubles.memberLine(c))
    }

    @Test
    fun `tightest first, and the majority class names a mixed cluster`() {
        val loose1 = entry("K1", 1, DrumClass.HAT_CLOSED, 0f, 0f)
        val loose2 = entry("K1", 2, DrumClass.HAT_OPEN, 0.04f, 0f)
        val tight1 = entry("K2", 1, DrumClass.SNARE, 10f, 0f)
        val tight2 = entry("K2", 2, DrumClass.SNARE, 10.001f, 0f)
        val tight3 = entry("K3", 1, DrumClass.CLAP, 10.002f, 0f)
        val clusters = Doubles.clusters(index(loose1, loose2, tight1, tight2, tight3), within = 0.05f)
        assertEquals(2, clusters.size)
        assertEquals(DrumClass.SNARE, clusters[0].label, "the tight trio comes first, named by its two snares")
        assertTrue(clusters[0].within < clusters[1].within)
        assertEquals(DrumClass.HAT_CLOSED, clusters[1].label, "a 1-1 tie goes to the class that comes first")
    }

    @Test
    fun `nothing this close is an empty list, never a verdict`() {
        val a = entry("K1", 1, DrumClass.KICK, 0f, 0f)
        val b = entry("K1", 2, DrumClass.KICK, 1f, 0f)
        assertEquals(emptyList(), Doubles.clusters(index(a, b), within = 0.05f))
        assertEquals(emptyList(), Doubles.clusters(index(a), within = 0.05f))
        assertEquals("NO DOUBLES WITHIN 0.05. NOT A CLEAN BILL - JUST NONE THIS CLOSE.", Copy.noDoubles(0.05f))
    }

    @Test
    fun `a real shelf - the planted twin is one cluster, and a binned copy of a kit is not a double`() {
        val root = File(temp, "shelf").apply { mkdirs() }
        val a = KitBuilderModel.create("Crate A", File(root, "Crate A"))
        a.assign(1, DrumSynth.kick(), DrumClass.KICK)
        a.assign(2, DrumSynth.snare(seed = 2), DrumClass.SNARE)
        a.save()
        val b = KitBuilderModel.create("Crate B", File(root, "Crate B"))
        b.assign(1, DrumSynth.kick(), DrumClass.KICK) // the planted twin
        b.assign(2, DrumSynth.clap(), DrumClass.CLAP)
        b.save()
        // DELETED KITS keeps a whole copy under .bin/ — the likeliest double
        // of its live self, and exactly what DOUBLES must not count.
        File(root, "Crate A").copyRecursively(File(root, ".bin/Crate A-123"))

        val clusters = Doubles.clusters(Crate.index(root), within = Doubles.DEFAULT_WITHIN)
        assertEquals(1, clusters.size, "$clusters")
        val twin = clusters.single()
        assertEquals(listOf("Crate A", "Crate B"), twin.entries.map { it.kitDir })
        assertEquals(DrumClass.KICK, twin.label)
        assertTrue(twin.within < 1e-4f, "byte-identical twins sit at ~0: ${twin.within}")
    }
}
