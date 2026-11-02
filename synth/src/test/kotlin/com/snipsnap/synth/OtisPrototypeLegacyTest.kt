package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/** PCM fixtures captured from the published first audition, before any model-2 source edits. */
class OtisPrototypeLegacyTest {
    @Test
    fun `all forty published model three source files replay byte for byte`() {
        val text = javaClass.getResource("/otis-model3-replay.json")!!.readText()
        val fixtures = Json.parse(text).obj().getValue("fixtures").arr()
        assertEquals(40, fixtures.size)
        for (raw in fixtures) {
            val fixture = raw.obj()
            val request = OtisPrototypeRecipe.fromJsonValue(fixture.getValue("recipe"))
            assertEquals(3, request.model)
            val source = request.render()
            val gain = fixture.getValue("rawPcmGain").num().toFloat()
            val exported = Snip(FloatArray(source.samples.size) { source.samples[it] * gain }, 1, Dsp.RATE)
            val stream = ByteArrayOutputStream()
            WavWriter.write(stream, exported)
            val hash = MessageDigest.getInstance("SHA-256").digest(stream.toByteArray())
                .joinToString("") { "%02x".format(it) }
            assertEquals(fixture.getValue("wavSha256").str(), hash,
                "${fixture.getValue("id").str()} changed a published model3 sound")
        }
    }

    private val firstOrgan = OtisOrganSettings(
        drawbars = listOf(1f, 1f, 1f, 0f, 0f, 0f, 0f, 0f, 0f),
        click = .35f, percussionLevel = .25f, percussionHarmonic = OtisOrganPercussion.THIRD,
        percussionDecaySeconds = .30, leakage = .25f, drive = .15f,
        attackSeconds = .004, releaseSeconds = .15, cabinetHz = 4800.0,
        rotary = false, rotaryStart = OtisOrganRotary.SLOW, rotaryTarget = OtisOrganRotary.FAST,
        rotaryChangeSeconds = .75, rotaryDepth = .7f,
    )
    private val firstAir = OtisAirSettings(.55f, .65f, .35f, .30f, .60f, .50f, .60f, .45f)
    private val hashes = mapOf(
        OtisPrototypeVoice.ORGAN to "95748da3a359c2c6e2576b7e4bdbb5f9d3068a03d65e7349201a4fce3f071079",
        OtisPrototypeVoice.STEAM to "10831f3decee2b97a40d243210ea43ef9c77883af0f7cad67460f98754bf9151",
        OtisPrototypeVoice.HARP to "349c0639fc3d995a3a24675765eab87de3905cbc1647b4cf50229b43715fe606",
        OtisPrototypeVoice.SQUEEZE to "7355456416b52ec96cda5a22ec47d54a0b7cfa4a205012507e13cd15e6e17070",
    )

    @Test
    fun `model one recipes reproduce the published original WAVs byte for byte`() {
        for ((voice, expected) in hashes) {
            val request = OtisPrototypeRequest(voice, listOf(OtisPrototypeNote(60, 0.0, 1.5, .8f)),
                seed = 20261014, organ = firstOrgan, air = firstAir, model = 1)
            val restored = OtisPrototypeRecipe.fromJsonText(OtisPrototypeRecipe.toJsonText(request))
            val source = restored.render()
            // The original pack's single common PCM headroom gain, recorded in its manifest.
            val rawGain = .54657835f
            val exported = Snip(FloatArray(source.samples.size) { source.samples[it] * rawGain }, 1, 44_100)
            val stream = ByteArrayOutputStream()
            WavWriter.write(stream, exported)
            val actual = MessageDigest.getInstance("SHA-256").digest(stream.toByteArray())
                .joinToString("") { "%02x".format(it) }
            assertEquals(expected, actual, "$voice changed the original published sound")
        }
    }

    @Test
    fun `model two recipes reproduce the second published default WAVs byte for byte`() {
        val published = mapOf(
            OtisPrototypeVoice.ORGAN to "2b648aefa74e1352c82dee8f8dde29282c2412fd48c7d2ccc24b29f256f24191",
            OtisPrototypeVoice.STEAM to "185617d6e3fa79af669482674b91e7ca454eb6872a34b314af28c29e1d51e744",
            OtisPrototypeVoice.HARP to "99c9cdd1f4f27188ebd12b3a62d3a3d4fa65449da7d62c2e7cdbb38e4d9d374e",
            OtisPrototypeVoice.SQUEEZE to "b05ebdbeffef8d2b1f152ca829927a288defaf8ea34c6abb580a67dd6739743b",
        )
        for ((voice, expected) in published) {
            val request = OtisPrototypeRequest(voice, model = 2)
            val restored = OtisPrototypeRecipe.fromJsonText(OtisPrototypeRecipe.toJsonText(request))
            val source = restored.render()
            val exported = Snip(FloatArray(source.samples.size) { source.samples[it] * .54657835f }, 1, 44_100)
            val stream = ByteArrayOutputStream()
            WavWriter.write(stream, exported)
            val actual = MessageDigest.getInstance("SHA-256").digest(stream.toByteArray())
                .joinToString("") { "%02x".format(it) }
            assertEquals(expected, actual, "$voice changed its second published sound")
        }
    }
}
