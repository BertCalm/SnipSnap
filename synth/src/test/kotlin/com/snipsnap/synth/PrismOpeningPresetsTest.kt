package com.snipsnap.synth

import com.snipsnap.json.Json
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The accepted audition is the authority; changing a donor engine must not change these presets. */
class PrismOpeningPresetsTest {
    private fun resource(name: String): ByteArray = requireNotNull(javaClass
        .getResourceAsStream("/prism-opening-accepted/$name")) { "missing accepted PRISM fixture $name" }
        .use { it.readBytes() }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @Test
    fun `factory body openings preserve every accepted recipe field except their public names`() {
        val manifest = Json.parse(resource("manifest.json").toString(Charsets.UTF_8)).obj()
        assertEquals("a1c23e726201dbcae1e621e95e287a7227f95f4d", manifest.getValue("sourceRevision").str())
        val rows = manifest.getValue("clips").arr()
        val presets = PrismOpeningPresets.all()
        assertEquals(listOf("Soft Arrival", "Wire Bloom", "Bowed Bloom", "Hollow Rise"), presets.map { it.name })
        assertEquals(rows.size, presets.size)
        for ((rowValue, preset) in rows.zip(presets)) {
            val row = rowValue.obj()
            val bytes = resource(row.getValue("recipe").str())
            assertEquals(row.getValue("recipeSha256").str(), digest(bytes), "accepted fixture bytes must stay untouched")
            val accepted = PadRecipe.fromJsonText(bytes.toString(Charsets.UTF_8))
            assertNull(accepted.fx)
            val patch = assertIs<PrismPatch>(accepted.patch)
            assertEquals(row.getValue("factoryName").str(), preset.name)
            assertEquals(patch, preset.copy(name = patch.name), "${preset.name}: retain pitch, velocity, carrier, phase template, A, B and timing")
            assertEquals(bytes.toString(Charsets.UTF_8), PadRecipe(preset.copy(name = patch.name)).toJsonText(),
                "${preset.name}: the complete accepted recipe, including field ordering, stays reproducible")
            val motion = assertNotNull(preset.motion)
            assertEquals(Prism.PARTIALS, assertNotNull(motion.amplitudesA).size)
            assertEquals(.8f, motion.seconds)
            assertEquals(0f, motion.morph)
            assertEquals(1f, motion.evolve)
            assertEquals(0f, motion.groupLag)
            // Compare on this runtime: Math.sin may differ by an ULP across JDKs or CPUs.
            // Captured audio hashes stay in the manifest for artifact provenance, not a JVM golden.
            assertContentEquals(accepted.render().samples, preset.render().samples,
                "${preset.name}: its new public name must not change the accepted recipe's sound")
        }
    }

    @Test
    fun `body presets append after all thirty six original selection slots`() {
        val names = listOf(
            "Soft Facets", "Wooden Spectrum", "Partial Tines", "Hollow Register", "Root Glass", "Slow Separation",
            "Amber Veil", "Small Prism", "Felt Harmonics", "Reed Cabinet", "Porcelain Key", "Mute Wire", "Cedar Pin",
            "Hollow Plectrum", "Bright Bead", "Chapel Core", "Open Ranks", "Odd Register", "Soft Diapason", "Turning Ranks",
            "Cellar Sine", "Round Relay", "Narrow Reed", "Thumb Anchor", "Copper Step", "Linen Bloom", "Paper Halo",
            "Woven Fifths", "Quiet Bands", "Rising Threads", "Night Lattice", "Rounded Chime", "Pearl Drop", "Distant Bell",
            "Clear Mantle", "Slow Crystal",
        )
        val all = PrismPresets.all()
        assertEquals(names, all.take(36).map { it.name })
        assertEquals(PrismOpeningPresets.all(), all.drop(36))
        for (legacy in all.take(36)) assertNull(legacy.motion, "${legacy.name}: existing instruments remain motion-free")
        for (voice in PrismVoice.entries) {
            assertEquals(all.take(36).filter { it.voice == voice } + PrismOpeningPresets.all().filter { it.voice == voice },
                PrismPresets.forVoice(voice), "$voice keeps every original preset index")
        }
    }
}
