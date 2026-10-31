package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class OhmSourceRevisionTest {
    @Test
    fun `fifty published model three recipes retain raw final PCM and held markers`() {
        val input = requireNotNull(javaClass.getResourceAsStream("/ohm-model3-compatibility.json"))
        val cases = input.bufferedReader().use { Json.parse(it.readText()) }.obj().getValue("cases").arr()
        assertEquals(50, cases.size)
        for (entry in cases) {
            val row = entry.obj()
            val id = row.getValue("id").str()
            val patch = OhmPatch.fromJsonValue(row.getValue("patch")) as OhmPatch
            val raw: Snip
            val final: Snip
            if (row.getValue("held").bool()) {
                val beforeLevel = Ohm.renderHeldRaw(patch)
                val note = Ohm.renderHeld(patch)
                assertEquals(row.getValue("loopStartFrame").long(), note.loopStartFrame, id)
                assertEquals(note.loopStartFrame, beforeLevel.loopStartFrame, id)
                raw = beforeLevel.snip
                final = note.snip
            } else {
                raw = Ohm.renderRaw(patch.macros, patch.midi, patch.velocity, patch.seed,
                    patch.gesture, patch.model, patch.options)
                final = patch.render()
            }
            assertEquals(row.getValue("rawFloatSha256").str(), floatHash(raw), "$id raw Float")
            assertEquals(row.getValue("finalFloatSha256").str(), floatHash(final), "$id final Float")
            assertEquals(row.getValue("rawWavSha256").str(), wavHash(raw), "$id raw PCM16")
            assertEquals(row.getValue("finalWavSha256").str(), wavHash(final), "$id final PCM16")
        }
    }

    @Test
    fun `new source revision leaves memory-free finite pads and held instruments unchanged`() {
        val capture = OhmCapture(FloatArray(2205) { i -> if (i == 0) .5f else 0f }, "Impulse")
        for (exciter in OhmExciter.entries) for (commune in listOf(0f, 1f)) {
            val options = OhmOptions(exciter = exciter, elder = .6f,
                capture = capture.takeIf { exciter == OhmExciter.CAPTURED })
            val old = OhmPatch("Memory-free $exciter", Ohm.defaults(3) +
                mapOf("MEDDLING" to .9f, "COMMUNE" to commune), velocity = .8f,
                seed = 731, model = 3, options = options)
            assertContentEquals(old.render().samples, old.copy(model = 4).render().samples,
                "$exciter C$commune changed without grains or freeze")
            assertContentEquals(raw(old).samples, raw(old.copy(model = 4)).samples,
                "$exciter C$commune raw finite changed")
            val held = old.copy(macros = old.macros + ("HOLD" to 1f))
            val updated = held.copy(model = 4)
            for (beforeLevel in listOf(false, true)) {
                val before = if (beforeLevel) Ohm.renderHeldRaw(held) else Ohm.renderHeld(held)
                val after = if (beforeLevel) Ohm.renderHeldRaw(updated) else Ohm.renderHeld(updated)
                assertEquals(before.loopStartFrame, after.loopStartFrame, "$exciter raw=$beforeLevel marker")
                assertContentEquals(before.snip.samples, after.snip.samples, "$exciter raw=$beforeLevel held")
            }
            assertContentEquals(held.render().samples, updated.render().samples, "$exciter final loop-only pad")
            assertContentEquals(raw(held).samples, raw(updated).samples, "$exciter raw loop-only pad")
        }
    }

    @Test
    fun `complete source recipes keep their selected revision through editing and JSON`() {
        for (model in listOf(3, 4)) {
            val patch = OhmPatch("Saved sources", Ohm.defaults(model) + ("HOLD" to 1f),
                midi = 57, velocity = .65f, seed = -91, gesture = OhmGesture.GATHER,
                model = model, options = OhmOptions(grains = .6f, freeze = .75f), releaseSeconds = .72f)
            assertEquals(patch, OhmPatch.fromJsonText(patch.toJsonText()))
            assertEquals(patch, PadRecipe.fromJsonText(PadRecipe(patch).toJsonText()).patch)
            val edited = patch.macros + ("COMMUNE" to .8f)
            assertEquals(patch.copy(name = "Edited", macros = edited), Patches.edited(patch, "Edited", edited))
        }
    }

    private fun floatHash(snip: Snip): String {
        val bytes = ByteBuffer.allocate(snip.samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in snip.samples) bytes.putInt(sample.toRawBits())
        return hash(bytes.array())
    }

    private fun raw(patch: OhmPatch) = Ohm.renderRaw(patch.macros, patch.midi, patch.velocity,
        patch.seed, patch.gesture, patch.model, patch.options)

    private fun wavHash(snip: Snip): String = ByteArrayOutputStream().use { out ->
        WavWriter.write(out, snip, WavWriter.BitDepth.PCM_16)
        hash(out.toByteArray())
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
