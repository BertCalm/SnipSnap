package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Public saved-source compatibility for two kept contact settings, not new voice/device approval. */
class NacreKeptContactCompatibilityTest {
    private data class Witness(val name: String, val loss: Float, val raw: String, val final: String, val wav: String)
    // Independently captured at source9b/profile1 and bound to the owner's two KEEP decisions.
    private val witnesses = listOf(
        Witness("Pearl Moderate Contact", .35f,
            "81f8f40885e9a0dc9fa9c71546addebf026cc50fbe67f81f6febe808b8ac74a7",
            "65403955e4250f620f05a2b33ea56093a58e685743da3680001bd8ae38ccb018",
            "7f9f1bcd12b0e7ab6e9139fd32fd203d06ff5f63e5f6098c0a0da9ca4b1a7d81"),
        Witness("Pearl Strong Contact", .8f,
            "dbe95db6f0261f6b274b07405c03de3d9d96be57fcbef763d9f51973e3f4dc6f",
            "48304c74c17949b3ea375803d2cec3bd11220f7edc93ed9ed167554ca01ef73c",
            "6d6f561a3213f049b261a67f2433f74b2bbf4465c9ce4712bdd1c983b3a384df"),
    )
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
    private fun hash(samples: FloatArray): String {
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putInt(it.toRawBits()) }
        return digest(buffer.array())
    }
    private fun kept() = NacrePresets.all().filter { it.model == NacreModels.CONTACT_MODEL_VERSION }

    @Test
    fun `kept model four recipes reproduce independent full raw final and PCM24 witnesses`() {
        for (w in witnesses) {
            val patch = kept().single { it.name == w.name }
            assertEquals(w.loss, patch.macros.getValue("CONTACT"))
            val restored = Patches.fromJsonText(patch.toJsonText()) as NacrePatch
            assertEquals(patch, restored)
            val probe = NacreModels.inspect(restored.voice, restored.macros, restored.midi,
                restored.velocity, restored.seed, restored.model)
            assertEquals(w.raw, hash(probe.raw), "kept direct source changed")
            assertEquals(w.final, hash(probe.snip.samples), "kept conditioned source changed")
            val ordinary = restored.render()
            assertEquals(w.final, hash(ordinary.samples), "public saved patch diverged from the heard profile")
            assertEquals(264_600, ordinary.frameCount)
            assertEquals(44_100, ordinary.sampleRate)
            assertEquals(1, ordinary.channels)
            val wav = ByteArrayOutputStream().also { WavWriter.write(it, ordinary) }.toByteArray()
            assertEquals(w.wav, digest(wav), "native PCM24 bytes changed")
            assertEquals(4.0, probe.diagnostics.getValue("modelVersion"))
            assertEquals(1.0, probe.diagnostics.getValue("savedContactProfileVersion"))
            assertFalse("contactStudyRegisteredOrSaved" in probe.diagnostics,
                "a saved source must not claim to be an unregistered diagnostic")
        }
    }

    @Test
    fun `contact zero is the independent accepted model three source without changing defaults`() {
        val old = NacrePresets.all().first()
        assertEquals(3, Nacre.MODEL_VERSION)
        assertEquals(3, old.model)
        assertFalse("CONTACT" in old.macros)
        assertFalse("CONTACT" in old.toJsonValue().entries.getValue("macros").obj())
        val zero = old.copy(model = 4, macros = old.macros + ("CONTACT" to -0f))
        val p = NacreModels.inspect(zero.voice, zero.macros, zero.midi, zero.velocity, zero.seed, zero.model)
        assertEquals("324aa5ee3648c4ed1253154fb654f0a3198104fe9acd8fad70b38fdc56644579", hash(p.raw))
        assertEquals("de822099817c5da3de3dc65c52b6f03e48fc5e9a3e7ca18233dad18804bf66b3", hash(zero.render().samples))
        assertContentEquals(old.render().samples, zero.render().samples)
        assertEquals(Nacre.defaults() + ("CONTACT" to 0f), NacreModels.defaults(NacreVoice.PEARL, 4))
        for (model in 1..3) assertFalse(NacreModels.defaults(NacreVoice.PEARL, model).containsKey("CONTACT"))
    }

    @Test
    fun `patch recipe edits and native velocity retain explicit model four contact state`() {
        for (patch in kept()) {
            val signed = patch.copy(seed = Long.MIN_VALUE + 17, midi = 57, velocity = .65f)
            val restored = Patches.fromJsonText(signed.toJsonText()) as NacrePatch
            assertEquals(signed, restored)
            val recipe = PadRecipe.fromJsonText(PadRecipe(signed).toJsonText())
            assertEquals(signed, recipe.patch)
            val macros = patch.macros + ("DUET" to .45f)
            assertEquals(patch.copy(name = "Contact edit", macros = macros),
                Patches.edited(patch, "Contact edit", macros))
            assertEquals(patch.copy(macros = macros), patch.withMacros(macros))
            assertEquals(6, Velocity.macroSpecsFor(patch).count { it.name != "HOLD" })
        }
        val patch = kept().first()
        assertContentEquals(patch.copy(velocity = .25f).render().samples,
            Velocity.atVelocity(patch, .25f).samples)
    }

    @Test
    fun `contact rejects unversioned unknown and nonfinite controls before rendering`() {
        val patch = kept().first()
        assertFailsWith<IllegalArgumentException> { patch.copy(model = 3) }
        for (loss in listOf(-.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(macros = patch.macros + ("CONTACT" to loss)) }
        }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = patch.macros + ("UNKNOWN" to .5f)) }
        for (loss in listOf(-1e-12, 1.0 + 1e-12, Double.NaN)) {
            val fields = patch.toJsonValue().entries + ("macros" to JsonValue.Obj(
                patch.toJsonValue().entries.getValue("macros").obj() + ("CONTACT" to JsonValue.Num(loss))))
            assertFailsWith<JsonException> { NacrePatch.fromJsonValue(JsonValue.Obj(fields)) }
        }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = patch.macros + ("HOLD" to .1f)) }
        assertFailsWith<IllegalArgumentException> { patch.copy(model = 5) }
    }

    @Test
    fun `contact cache isolates source loss and cancellation cannot publish a partial source`() {
        Nacre.clearCoreCache()
        val moderate = kept().first()
        val first = moderate.render()
        val stable = hash(first.samples)
        assertEquals(1L, Nacre.coreCacheStats().builds)
        moderate.copy(macros = moderate.macros + ("BLOOM" to .8f)).render()
        assertEquals(1L, Nacre.coreCacheStats().builds, "return edit rebuilt the played-string source")
        first.samples.fill(0f)
        assertEquals(stable, hash(moderate.render().samples), "caller mutated cache-owned audio")
        kept().last().render()
        assertEquals(2L, Nacre.coreCacheStats().builds, "different CONTACT values collided")
        val before = Nacre.coreCacheStats()
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<CancellationException> { moderate.render() }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        assertEquals(before, Nacre.coreCacheStats(), "cancelled call changed completed cache ownership")
    }

    @Test
    fun `legacy velocity policy refuses contact models while factory discovery keeps five settings one voice`() {
        val presets = NacrePresets.all()
        assertEquals(listOf("Pearl Close", "Coral Reply", "Kelp Passage", "Pearl Moderate Contact", "Pearl Strong Contact"), presets.map { it.name })
        assertEquals(1, presets.map { it.voice }.toSet().size)
        assertEquals(presets, Presets.forVoice("NACRE", "PEARL"))
        for (patch in kept()) {
            assertFailsWith<IllegalArgumentException> { NacreVelocityKits.recipeFor(patch) }
            assertEquals(patch, Presets.byName("NACRE", "PEARL", patch.name))
            assertNull(Presets.landingFor("NACRE", "PEARL", patch.macros))
        }
        assertEquals("native-velocity-and-touch-effort-v1", NacreVelocityRecipe.POLICY)
        assertTrue(NacreVelocityKits.patches().all { it.model == 3 && "CONTACT" !in it.macros })
    }
}
