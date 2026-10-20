package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** PCM16 hashes from the first published pack, before the control-authority revision. */
class OhmLegacyTest {
    private val base = mapOf("TOUCH" to .5f, "BODY" to .5f, "LENGTH" to .65f)

    @Test
    fun `model one reproduces published raw audition bytes`() {
        data class Golden(val meddling: Float, val commune: Float, val gesture: OhmGesture, val hash: String)
        val goldens = listOf(
            Golden(0f, 0f, OhmGesture.STEADY, "b76e42b82c9405ccd489909e6c4916ade596bd043da210dabe4b116a1bb23a6c"),
            Golden(.9f, 0f, OhmGesture.STEADY, "c184adf1339850d451e31482a3fd9c61be219b4b84791cad971335bf8036d222"),
            Golden(.9f, 1f, OhmGesture.STEADY, "4cde810328293599996041e5fffba520aaf04630045d5814bb36c59338e10bbc"),
            Golden(.9f, 1f, OhmGesture.GATHER, "cc1175eab7b55d0cfe92264bb8a906609f0f4006663db06da2d9cdf32744277d"),
        )
        for (g in goldens) {
            val snip = Ohm.renderRaw(base + mapOf("MEDDLING" to g.meddling, "COMMUNE" to g.commune),
                velocity = .8f, seed = 731, gesture = g.gesture, model = 1)
            val bytes = ByteArrayOutputStream().also { WavWriter.write(it, snip, WavWriter.BitDepth.PCM_16) }.toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(g.hash, hash, "published model-1 ${g.gesture}/${g.commune} changed")
        }
    }

    @Test
    fun `missing model remains one while new patches default to two`() {
        val current = OhmPatch("Current", base)
        assertEquals(2, current.model)
        for (entries in listOf(current.toJsonValue().entries - "model",
            current.toJsonValue().entries + ("model" to JsonValue.Null))) {
            val old = OhmPatch.fromJsonValue(JsonValue.Obj(entries)) as OhmPatch
            assertEquals(1, old.model)
            assertEquals(old, Patches.fromJsonText(old.toJsonText()))
            assertContentEquals(Ohm.render(old.macros, old.midi, old.velocity, old.seed, old.gesture, model = 1).samples,
                old.render().samples)
        }
        assertFailsWith<IllegalArgumentException> { Ohm.renderRaw(model = 0) }
        assertFailsWith<IllegalArgumentException> { Ohm.renderRaw(model = 3) }
    }

    @Test
    fun `old recipe edits pad replay and velocity keep model one`() {
        val old = OhmPatch("Original", base + ("LENGTH" to .1f), midi = 57, velocity = .7f,
            seed = -905, gesture = OhmGesture.GATHER, model = 1)
        val edited = Patches.edited(old, "Edited", old.macros + ("COMMUNE" to .73f)) as OhmPatch
        assertEquals(1, edited.model)
        assertContentEquals(old.render().samples, PadRecipe.fromJsonText(PadRecipe(old).toJsonText()).render().samples)
        assertContentEquals(old.copy(velocity = .35f).render().samples, Velocity.atVelocity(old, .35f).samples)
    }
}
