package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OhmExpandedAuditionTest {
    @Test
    fun `every optional feature has an isolated independent and coupled comparison`() {
        val capture = OhmCapture.fromSnip(OhmExpandedAuditionGenerator.authoredHit(), "Authored felt tap")
        val cases = OhmExpandedAuditionGenerator.studies(capture)
        assertEquals(19, cases.size)
        assertEquals(cases.size, cases.map { it.id }.distinct().size)
        val features = listOf("bow", "capture", "elder", "grains", "freeze", "sustain")
        for (feature in features) for (context in listOf("independent", "shared")) {
            val study = cases.single { it.group == "features" && it.feature == feature && it.context == context }
            assertTrue(study.matchParts)
            assertEquals(listOf("OFF", "ON"), study.parts.map { it.label })
            val off = study.parts[0].patch
            val on = study.parts[1].patch
            assertEquals(OhmOptions(), off.options)
            assertEquals(off.macros - "HOLD", on.macros - "HOLD")
            assertEquals(.65f, on.macros["MEDDLING"])
            assertEquals(if (context == "shared") 1f else 0f, on.macros["COMMUNE"])
            assertEquals(60, on.midi)
            assertEquals(.8f, on.velocity)
            assertEquals(731, on.seed)
            assertEquals(3, on.model)
            assertEquals(if (feature == "sustain") 2 else 0, study.parts[1].heldRepeats)
            assertEquals(if (feature == "elder") 1f else 0f, on.options.elder)
            assertEquals(if (feature == "grains") 1f else 0f, on.options.grains)
            assertEquals(if (feature == "freeze") 1f else 0f, on.options.freeze)
            if (feature == "capture") assertEquals(capture, on.options.capture)
            assertEquals(on, OhmPatch.fromJsonText(on.toJsonText()))
        }
        assertEquals(3, cases.count { it.group == "phrases" })
        assertEquals(4, cases.count { it.group == "held" })
        assertTrue(cases.filter { it.group == "held" }.all { it.parts.single().heldRepeats == 8 })
    }

    @Test
    fun `held listening copy keeps the arrival once and only repeats the marked sustain`() {
        val source = FloatArray(768) { i -> if (i < 512) i / 1000f else -(i - 511) / 1000f }
        val original = source.copyOf()
        val note = KeyNote(Snip(source, 1, 44_100), loopStartFrame = 512)
        val copy = OhmExpandedAuditionGenerator.heldPresentation(note, 8, fadeMilliseconds = 0f)
        assertEquals(512 + 256 * 8, copy.frameCount)
        assertContentEquals(source.copyOfRange(0, 512), copy.samples.copyOfRange(0, 512))
        repeat(8) { i -> assertContentEquals(source.copyOfRange(512, 768), copy.samples.copyOfRange(512 + i * 256, 768 + i * 256)) }
        assertContentEquals(original, source, "presentation must not mutate the instrument source")
        val faded = OhmExpandedAuditionGenerator.heldPresentation(note, 8, fadeMilliseconds = 10f)
        assertTrue(faded.samples.last() == 0f)
        assertContentEquals(original, source, "listening fade must remain on the copy")
        assertContentEquals(copy.samples.copyOfRange(0, copy.frameCount - 441), faded.samples.copyOfRange(0, faded.frameCount - 441))
    }

    @Test
    fun `serialized comparison replay preserves independent matching and exact silent gap`() {
        val macros = Ohm.defaults(3) + mapOf("LENGTH" to 0f, "MEDDLING" to .65f, "COMMUNE" to 1f, "HOLD" to 0f)
        val patches = listOf(.3f, .8f).map { velocity -> OhmPatch("Replay $velocity", macros, velocity = velocity, seed = 731, model = 3) }
        val sounds = patches.map { p -> Ohm.renderRaw(p.macros, p.midi, p.velocity, p.seed, p.gesture, p.model, p.options) }
        val start = sounds[0].frameCount + OhmExpandedAuditionGenerator.GAP_FRAMES
        val frames = start + sounds[1].frameCount
        fun event(i: Int) = JsonValue.Obj(mapOf(
            "patch" to patches[i].toJsonValue(), "source" to JsonValue.Str("Ohm.renderRaw"),
            "heldRepeats" to JsonValue.Num(0.0), "frames" to JsonValue.Num(sounds[i].frameCount.toDouble()),
            "onsetFrames" to JsonValue.Num(if (i == 0) 0.0 else start.toDouble()), "gain" to JsonValue.Num(1.0),
        ))
        val score = JsonValue.Obj(mapOf(
            "type" to JsonValue.Str("OHM_EXPANDED_AUDITION"), "sampleRate" to JsonValue.Num(44_100.0),
            "channels" to JsonValue.Num(1.0), "frames" to JsonValue.Num(frames.toDouble()),
            "events" to JsonValue.Arr(listOf(event(0), event(1))), "matchParts" to JsonValue.Bool(true),
            "rawExportGain" to JsonValue.Num(.5),
        ))
        val restored = Json.parse(Json.write(score))
        val raw = OhmExpandedAuditionGenerator.replay(restored, "raw")
        val matched = OhmExpandedAuditionGenerator.replay(restored, "matched")
        assertEquals(frames, raw.frameCount)
        assertContentEquals(FloatArray(sounds[0].frameCount) { sounds[0].samples[it] * .5f }, raw.samples.copyOfRange(0, sounds[0].frameCount))
        assertContentEquals(AuditionLevel.level(sounds[0]).samples, matched.samples.copyOfRange(0, sounds[0].frameCount))
        assertContentEquals(AuditionLevel.level(sounds[1]).samples, matched.samples.copyOfRange(start, frames))
        assertTrue(raw.samples.sliceArray(sounds[0].frameCount until start).all { it == 0f })
        assertTrue(matched.samples.sliceArray(sounds[0].frameCount until start).all { it == 0f })
    }
}
