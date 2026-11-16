package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.*
import kotlin.test.*

class BristleEffectsTest {
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun hash(x: FloatArray): String {
        val bytes = ByteBuffer.allocate(x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        x.forEach(bytes::putFloat)
        return hash(bytes.array())
    }
    private fun rms(x: FloatArray, from: Int, to: Int) = sqrt((from until to).sumOf { x[it].toDouble().pow(2) } / (to-from))

    @Test fun `effects off preserves all eight approved finite held and saved recipes bit for bit`() {
        val rows = javaClass.getResourceAsStream("/bristle/approved-off-sha256.tsv")!!.bufferedReader().readLines()
        assertEquals(8, rows.size)
        for (row in rows) {
            val parts = row.split('\t')
            val patch = BristlePresets.forVoice(Bristle.Route.valueOf(parts[0])).single()
            assertEquals(parts[1], hash(patch.render().samples), "${patch.voice} finite")
            assertEquals(parts[2], hash(BristleHeld.render(patch).note.snip.samples), "${patch.voice} held")
            assertEquals(parts[3], hash(patch.toJsonText().toByteArray()), "${patch.voice} recipe")
            assertFalse("effects" in patch.toJsonText())
        }
    }

    @Test fun `compression changes the envelope rather than merely changing gain`() {
        val x = FloatArray(44100) { i ->
            (sin(2*PI*65.406*i/44100) * if (i < 4410) .8 else if (i < 33075) .2 else 0.0).toFloat()
        }
        val dry = Snip(x, 1, 44100)
        assertSame(dry, BristleEffects.compress(dry, 0f))
        val wet = BristleEffects.compress(dry, 1f).samples
        val before = rms(x, 10000, 30000) / rms(x, 1000, 4000)
        val after = rms(wet, 10000, 30000) / rms(wet, 1000, 4000)
        assertTrue(after > before * 1.3, "body/attack before=$before after=$after")
        assertTrue(wet.maxOf { abs(it) } <= .80001f)
        assertTrue(wet.takeLast(1000).all { abs(it) < .0001f })
        assertTrue(BristleEffects.compress(Snip(FloatArray(44100),1,44100),1f).samples.all { it==0f })
    }

    @Test fun `both effects are stored completely and invalid effects are refused`() {
        for (voice in Bristle.Route.entries) {
            val patch = BristlePresets.forVoice(voice).single().copy(midi=32, velocity=.65f, take=1,
                effects=BristleEffects.Settings(.63f,.81f))
            val saved = BristlePatch.fromJsonText(patch.toJsonText())
            assertEquals(patch, saved)
            assertContentEquals(patch.render().samples, saved.render().samples)
            assertEquals(patch.effects, (Patches.edited(patch,"EDITED",patch.macros+("GRIT" to .7f)) as BristlePatch).effects)
            val badEffects = com.snipsnap.json.JsonValue.Obj(patch.effects.toJsonValue().entries +
                ("compress" to com.snipsnap.json.JsonValue.Num(2.0)))
            val badPatch = com.snipsnap.json.JsonValue.Obj(patch.toJsonValue().entries + ("effects" to badEffects))
            assertFails { BristlePatch.fromJsonValue(badPatch) }
        }
        assertFailsWith<IllegalArgumentException> { BristleEffects.Settings(Float.NaN,0f) }
        assertFailsWith<IllegalArgumentException> { BristleEffects.Settings(0f,1.01f) }
        val settings = BristleEffects.Settings(.5f,.5f).toJsonValue()
        val bad = com.snipsnap.json.JsonValue.Obj(settings.entries + ("unknown" to com.snipsnap.json.JsonValue.Num(1.0)))
        assertFails { BristleEffects.Settings.fromJsonValue(bad) }
    }

    @Test fun `effects keep roots touch take differences and real sustain seams`() {
        for (voice in Bristle.Route.entries) for (midi in listOf(24,36,48)) {
            for (effects in listOf(BristleEffects.Settings(1f,0f), BristleEffects.Settings(0f,1f), BristleEffects.Settings(1f,1f))) {
                val base = BristlePresets.forVoice(voice).single().copy(midi=midi,effects=effects)
                val finite = base.render().samples
                assertTrue(finite.all { it.isFinite() && abs(it) <= .991f })
                assertTrue(finite.last() == 0f, "Finite tail must reach silence (either sign of zero)")
                assertTrue(abs(finite.average()) < .005, "$voice $midi $effects DC")
                val held = BristleHeld.render(base)
                assertTrue(held.seamError < Keys.MAX_SEAM_ERROR)
                val x=held.note.snip.samples
                assertTrue(x.all { it.isFinite() && abs(it) <= .991f })
                if (midi>=28) {
                    val steady = Snip(x.copyOfRange(44100,x.size),1,44100)
                    val pitch = assertNotNull(com.snipsnap.audio.Pitch.detect(steady,.05f,.2f))
                    assertTrue(abs(1200*log2(pitch.hz/held.actualHz)) < 15, "$voice $midi $effects pitch=${pitch.hz}")
                }
                val soft=base.copy(velocity=.35f).render().samples
                assertTrue(soft.maxOf { abs(it) } < finite.maxOf { abs(it) }, "$voice $effects touch")
                assertFalse(finite.contentEquals(base.copy(take=1).render().samples))
            }
        }
    }

    @Test fun `fuzz changes harmonic shape after level matching and held extremes still settle`() {
        for (voice in Bristle.Route.entries) {
            val base=BristlePresets.forVoice(voice).single()
            val dry=base.render().samples
            val wet=base.copy(effects=BristleEffects.Settings(fuzz=1f)).render().samples
            val gain=dry.indices.sumOf { dry[it].toDouble()*wet[it] } / dry.sumOf { it.toDouble()*it }
            val error=dry.indices.sumOf { (wet[it]-gain*dry[it]).pow(2) } / wet.sumOf { it.toDouble()*it }
            assertTrue(error>.001,"$voice fuzz only changed gain: $error")
            for (midi in listOf(24,48)) for (hold in listOf(0f,1f)) for (dark in listOf(0f,1f)) {
                val patch=base.copy(midi=midi,effects=BristleEffects.Settings(1f,1f), macros=base.macros+
                    mapOf("SLIDE" to 1f,"BITE" to 1f,"GRIT" to 1f,"DARK" to dark,"HOLD" to hold))
                assertTrue(BristleHeld.render(patch).seamError < Keys.MAX_SEAM_ERROR)
            }
        }
    }
}
