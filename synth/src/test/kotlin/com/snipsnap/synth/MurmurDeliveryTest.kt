package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MurmurDeliveryTest {
    @Test
    fun `terminal fade is optional saved delivery state with bounded durations`() {
        val original = PadRecipe(heldPatch)
        assertNull(original.terminalFadeMs)
        assertFalse("terminalFadeMs" in original.toJsonValue().entries)
        assertEquals(original, PadRecipe.fromJsonText(original.toJsonText()))
        val explicitNull = JsonValue.Obj(original.toJsonValue().entries + ("terminalFadeMs" to JsonValue.Null))
        assertEquals(original, PadRecipe.fromJsonValue(explicitNull))
        val faded = original.copy(terminalFadeMs = 8f)
        assertEquals(8.0, faded.toJsonValue().entries.getValue("terminalFadeMs").num())
        assertEquals(faded, PadRecipe.fromJsonText(faded.toJsonText()))
        assertNull(faded.fx, "a delivery fade must not add a rack effect to the dry starter")
        assertEquals(heldPatch, faded.patch)
        for (ms in listOf(0f, -.1f, .5f, 100.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>("invalid terminal fade $ms") {
                original.copy(terminalFadeMs = ms)
            }
        }
        assertEquals(1f, original.copy(terminalFadeMs = 1f).terminalFadeMs)
        assertEquals(100f, original.copy(terminalFadeMs = 100f).terminalFadeMs)
    }

    @Test
    fun `delivery fade copies audio and preserves stereo alignment and the preceding samples`() {
        val samples = FloatArray(2_000) { if (it % 2 == 0) .25f else -.5f }
        val source = Snip(samples, channels = 2, sampleRate = 44_100)
        val faded = PadRecipe(heldPatch, terminalFadeMs = 8f).process(source)
        assertEquals(source.frameCount, faded.frameCount)
        assertEquals(source.channels, faded.channels)
        assertEquals(source.sampleRate, faded.sampleRate)
        assertTrue(faded.samples !== source.samples)
        assertContentEquals(FloatArray(2_000) { if (it % 2 == 0) .25f else -.5f }, source.samples,
            "delivery processing mutated the source buffer")
        val start = (source.frameCount - 352) * source.channels
        assertContentEquals(source.samples.copyOfRange(0, start), faded.samples.copyOfRange(0, start))
        assertTrue(faded.samples[faded.samples.lastIndex - 1] == 0f)
        assertTrue(faded.samples.last() == 0f)
        for (frame in source.frameCount - 352 until source.frameCount) {
            val left = faded.samples[frame * 2]
            val right = faded.samples[frame * 2 + 1]
            assertEquals(-2f * left, right, "channels received different fade gains at frame $frame")
        }
    }

    @Test
    fun `first fifteen starter pads and the held source retain accepted native PCM`() {
        assertEquals(16, starter.size)
        for (index in 0 until 15) {
            val recipe = PadRecipe.fromJsonValue(assertNotNull(starter[index].recipe))
            assertNull(recipe.terminalFadeMs, "A${index + 1} gained a delivery change")
            assertEquals(acceptedPcm24[index], sha256(wav(starter[index].snip)), "A${index + 1} changed")
        }
        val deliveredRecipe = PadRecipe.fromJsonValue(assertNotNull(starter.last().recipe))
        assertEquals(heldPatch, assertIs<MurmurPatch>(deliveredRecipe.patch))
        assertEquals(acceptedPcm24.last(), sha256(wav(heldSource)), "the accepted seamless source changed")
    }

    @Test
    fun `starter A16 changes only the final eight milliseconds of the one shot delivery`() {
        val last = starter.last()
        val recipe = PadRecipe.fromJsonValue(assertNotNull(last.recipe))
        assertEquals(8f, recipe.terminalFadeMs)
        assertNull(recipe.fx)
        assertTrue(Murmur.isLoop(heldPatch.macros.getValue("HOLD")))
        assertEquals(DrumClass.LOOP, last.drumClass)
        assertTrue(last.oneShot, "the starter's settled buffer must still play once")
        val delivered = last.snip
        assertEquals(heldSource.frameCount, delivered.frameCount)
        assertEquals(heldSource.channels, delivered.channels)
        assertEquals(heldSource.sampleRate, delivered.sampleRate)
        val start = heldSource.samples.size - 352
        assertContentEquals(heldSource.samples.copyOfRange(0, start), delivered.samples.copyOfRange(0, start))
        assertTrue(abs(heldSource.samples.last()) > .01f, "fixture no longer exercises a nonzero periodic endpoint")
        assertEquals(0f, delivered.samples.last())
        assertTrue((start until delivered.samples.size).count { delivered.samples[it] != heldSource.samples[it] } > 300)
        for (i in start until delivered.samples.size) assertTrue(abs(delivered.samples[i]) <= abs(heldSource.samples[i]))
        assertContentEquals(delivered.samples, recipe.render().samples)
    }

    @Test
    fun `disk kit sidecar replays the delivered A16 and retains the unchanged instrument`() {
        val dir = Files.createTempDirectory("murmur-delivery").toFile()
        try {
            val kit = KitAssembler.assembleArranged("Murmur Delivery", starter, dir)
            val restored = KitStore.load(dir)
            assertEquals(kit, restored)
            for (pad in restored.pads.take(15)) {
                assertFalse("terminalFadeMs" in assertNotNull(pad.recipe).entries)
            }
            val last = assertNotNull(restored.pad(16))
            val recipe = PadRecipe.fromJsonValue(assertNotNull(last.recipe))
            assertEquals(8f, recipe.terminalFadeMs)
            assertNull(recipe.fx)
            assertEquals(heldPatch, assertIs<MurmurPatch>(recipe.patch))
            assertContentEquals(starter.last().snip.samples, recipe.render().samples)
            assertContentEquals(File(dir, last.sampleFile).readBytes(), wav(recipe.render()))
            KitStore.save(restored, File(dir, "resaved"))
            assertEquals(restored, KitStore.load(File(dir, "resaved")))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun wav(snip: Snip): ByteArray = ByteArrayOutputStream().also {
        WavWriter.write(it, snip)
    }.toByteArray()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    companion object {
        private val starter by lazy { SynthKits.murmur().map { assertNotNull(it) } }
        private val heldPatch by lazy { MurmurPresets.all().first { it.name == "HELD HUM" } }
        private val heldSource by lazy { heldPatch.render() }

        // Accepted native 24-bit WAVs from commit 1771e692, before a one-shot delivery fade.
        // A16's hash protects the source; the delivered A16 intentionally gains only its end ramp.
        private val acceptedPcm24 = listOf(
            "43b47bffaf24d7b4842ee98ae23e3dd04649bda18b9146ec812c7956118bbd13",
            "8091bed625a87921cb586d6b7075e1c3a4c1a44fbfef56cac71d3df5f592ac50",
            "4af6d39432a3d406c28706ed6eafff258ac86e0b1cea038f131ed8ab14e1de39",
            "528b8995d60b1798cd8298df8ab85e74836512eddf470806790972d19009e54e",
            "11d0f16b4410204a8e4e43342776a1be4e90f9de00a327a56f13323bbc5d3bdc",
            "b84f8d51083e719588fb93da9d3e6575235c09ea0ecad2f91e7ebcde0d495ffd",
            "fc738da6624f18cfde06820d27249dd26e7266265c011960527a3ad22602e45f",
            "56c19e92a9aba72582eb13cf91d5e6e6362d5687b8babb9f2865e13ab936b65f",
            "834957d2c928058cc778f4a7acb04b63bcc133739bc1f5fdf46b7920b0a2e2c2",
            "264799ab09ec11bd55aefe4cc41b3ba8cf3c5240952734860d388c13ffe4bf03",
            "c838dc501bf15f406d092efde79460b1629ae45af4822b822be816c2332d9970",
            "5b973a73d402c240ecd6423ac1b1b2b722d61824ad5a967e2a3312a645210512",
            "4a2842dae7f1c506eb61d5edd58df61d0a2cdf663db19c22c49597d06b10abf9",
            "f7f76f40e3829596fa5532d98aab89a0244b2c74c9df0f05861f322491fb0f80",
            "0fc17bbdce2c74244402b1cb9364bf841a9e2a72d8e61e2bb8b48e98d9d9916e",
            "b462f82218557e97d6b5f04ef6b56840c07378bf13903351282c23342e55d731",
        )
    }
}
