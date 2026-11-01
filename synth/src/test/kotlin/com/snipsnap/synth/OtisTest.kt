package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.*

/** The frozen audition implementations are independent references for the production port. */
class OtisTest {
    private fun references(): Map<String, OtisPrototypeRequest> {
        val character = OtisCharacterStudy.cases().associate { it.id to it.request }
        val draw = OtisSqueezeDrawStudy.cases().associate { it.id to it.request }
        return mapOf(
            "ORGAN NOTE" to OtisPrototypeRequest(OtisPrototypeVoice.ORGAN, model = 4),
            "ORGAN CHORD" to OtisPrototypeRequest(OtisPrototypeVoice.ORGAN,
                listOf(60, 63, 67, 70).map { OtisPrototypeNote(it, velocity = .68f) }, model = 4),
            "STEAM NOTE" to character.getValue("steam_c4"), "LOW STEAM" to character.getValue("steam_c3"),
            "STEAM CHORD" to character.getValue("steam_chord"), "PUSHED STEAM" to character.getValue("steam_pushed"),
            "HARP NOTE" to character.getValue("harp_single"), "HARP CHORD" to character.getValue("harp_blow_chord"),
            "HARP DRAW" to character.getValue("harp_draw_chord"), "HARP STABS" to character.getValue("harp_stabs"),
            "HARP BLUES" to character.getValue("harp_blues"),
            "SQUEEZE NOTE" to draw.getValue("single"), "DRY SQUEEZE" to character.getValue("squeeze_dry").copy(model = 6),
            "SQUEEZE CHORD" to draw.getValue("chord"), "SQUEEZE DRAW" to draw.getValue("long_chord"),
            "SHARED KEYS" to draw.getValue("joining_key"))
    }

    @Test fun `all sixteen production sources retain the accepted audition samples`() {
        val expected = references()
        assertEquals(16, OtisPresets.all().size)
        for (patch in OtisPresets.all()) {
            val reference = expected.getValue(patch.name).render()
            val source = Otis.renderSource(patch)
            assertEquals(reference.frameCount, source.frameCount, patch.name)
            assertContentEquals(reference.samples, source.samples, "${patch.name} changed its accepted source")
        }
    }

    @Test fun `complete recipes survive editing renaming and a cold replay`() {
        for (patch in OtisPresets.all()) {
            val original = patch.copy(midi = 59, velocity = .65f, seed = -917,
                macros = patch.macros + mapOf("TUNE" to .6f, "LENGTH" to .4f))
            val text = original.toJsonText()
            val saved = assertIs<OtisPatch>(Patches.fromJsonText(text))
            assertEquals(original, saved)
            assertEquals(text, saved.toJsonText())
            Otis.clearCache()
            val a = original.render()
            Otis.clearCache()
            assertContentEquals(a.samples, PadRecipe.fromJsonText(PadRecipe(saved).toJsonText()).render().samples)
            val renamed = assertIs<OtisPatch>(Patches.edited(saved, "Renamed", saved.macros + ("LENGTH" to .3f)))
            assertEquals(saved.profile, renamed.profile)
            assertEquals(saved.seed, renamed.seed)
            assertEquals(saved.midi, renamed.midi)
            assertEquals(saved.velocity, renamed.velocity)
            assertEquals(.3f, renamed.macros["LENGTH"])
        }
    }

    @Test fun `finite duration and classification agree with the actual rendered frames`() {
        for (voice in OtisVoice.entries) for (length in listOf(0f, .5f, 1f)) {
            val patch = OtisPatch("Duration", voice, mapOf("LENGTH" to length))
            val audio = patch.render()
            assertEquals(1, audio.channels)
            assertEquals(44100, audio.sampleRate)
            assertTrue(audio.samples.all { it.isFinite() })
            assertTrue(audio.peak() in .01f.. .99001f)
            assertTrue(abs(audio.samples.sumOf { it.toDouble() } / audio.samples.size) < .005)
            assertTrue(audio.samples.first() == 0f && audio.samples.last() == 0f, "guards must be acoustically zero")
            assertEquals(Classifier.classify(audio).drumClass, Otis.drumClassFor(patch), "$voice length $length")
            assertTrue(Otis.drumClassFor(patch) in setOf(DrumClass.PERC, DrumClass.TONAL, DrumClass.LOOP))
        }
        val chord = OtisPresets.forVoice(OtisVoice.SQUEEZE).first { it.name == "SQUEEZE DRAW" }
        assertTrue(chord.render().durationSeconds > 3.6f)
        assertEquals(DrumClass.LOOP, Otis.drumClassFor(chord))
        assertTrue(OtisVoice.entries.all { Otis.macrosFor(it).size == 6 && Otis.macrosFor(it).none { s -> s.name == "HOLD" } })
    }

    @Test fun `every control changes a level matched finite performance at middle settings`() {
        for (voice in OtisVoice.entries) {
            val base = OtisPresets.forVoice(voice).first { "CHORD" in it.name }
            val patch = base.copy(profile = base.profile.copy(events = base.profile.events.map { it.copy(gateSeconds = .45) }))
            for (spec in Otis.macrosFor(voice)) {
                val a = patch.copy(macros = patch.macros + (spec.name to .2f)).render()
                val b = patch.copy(macros = patch.macros + (spec.name to .6f)).render()
                if (spec.name == "LENGTH") assertTrue(a.frameCount < b.frameCount)
                else {
                    // CLICK is an onset gesture; a long released tail must not dilute its probe.
                    val count = minOf(a.frameCount, b.frameCount).let {
                        if (spec.name == "CLICK") minOf(it, (Dsp.RATE * .04).toInt()) else it
                    }
                    val ra = sqrt(a.samples.take(count).sumOf { it.toDouble() * it } / count)
                    val rb = sqrt(b.samples.take(count).sumOf { it.toDouble() * it } / count)
                    val error = sqrt((0 until count).sumOf { (a.samples[it] / ra - b.samples[it] / rb).pow(2) } / count)
                    assertTrue(error > .005, "$voice ${spec.name} only changes level: normalized difference=$error")
                }
            }
        }
    }

    @Test fun `velocity changes source work without changing the saved score or controls`() {
        for (voice in OtisVoice.entries) {
            val patch = OtisPresets.forVoice(voice).first { "CHORD" in it.name }
            val hard = Velocity.atVelocity(patch, 1f)
            val soft = Velocity.atVelocity(patch, .25f)
            assertContentEquals(patch.render().samples, hard.samples)
            assertContentEquals(patch.copy(velocity = .25f).render().samples, soft.samples)
            assertFalse(hard.samples.contentEquals(soft.samples), "$voice ignored playing strength")
            val authored = patch.copy(velocity = .4f)
            assertContentEquals(authored.render().samples, Velocity.atVelocity(authored, 1f).samples)
            assertContentEquals(authored.copy(velocity = .1f).render().samples, Velocity.atVelocity(authored, .25f).samples)
            assertEquals(1f, patch.velocity)
            assertTrue(patch.profile.events.size >= 3)
        }
    }

    @Test fun `cache hits cannot expose stored samples or miss a recipe field`() {
        val patch = OtisPresets.forVoice(OtisVoice.SQUEEZE).first { it.name == "SHARED KEYS" }
        Otis.clearCache()
        val expected = patch.render().samples
        patch.render().samples.fill(0f)
        assertContentEquals(expected, patch.copy(name = "Other name").render().samples)
        for (other in listOf(patch.copy(seed = patch.seed + 1), patch.copy(midi = 59), patch.copy(velocity = .5f),
            patch.copy(macros = patch.macros + ("PULSE" to 0f)),
            patch.copy(profile = patch.profile.copy(events = patch.profile.events.map { it.copy(gateSeconds = .3) })))) {
            assertFalse(expected.contentEquals(other.render().samples), "cache ignored a saved recipe field")
        }
    }

    @Test fun `caller owned maps and lists cannot change an existing recipe`() {
        val events = mutableListOf(OtisEvent())
        val bars = OtisOrganProfile().drawbars.toMutableList()
        val controls = mutableMapOf("CLICK" to .45f)
        val patch = OtisPatch("Snapshot", OtisVoice.ORGAN, controls, profile = OtisProfile(events, organ = OtisOrganProfile(drawbars = bars)))
        val before = Otis.renderSource(patch).samples
        events.clear(); bars.fill(0f); controls["CLICK"] = 1f
        assertEquals(1, patch.profile.events.size)
        assertEquals(1f, patch.profile.organ.drawbars.first())
        assertEquals(.45f, patch.macros["CLICK"])
        assertContentEquals(before, Otis.renderSource(patch).samples)
    }

    @Test fun `strict recipes reject unsupported state before render and scramble stays playable`() {
        val patch = OtisPresets.all().first()
        val root = patch.toJsonValue().entries
        for ((key, value, message) in listOf(
            Triple("model", JsonValue.Num(2.0), "unsupported Otis model"),
            Triple("velocity", JsonValue.Num(1.00000001), "velocity"),
            Triple("profile", JsonValue.Obj(emptyMap()), "profile fields mismatch"),
            Triple("seed", JsonValue.Num(1.5), "integer"))) {
            val error = assertFailsWith<JsonException> { Patches.fromJsonValue(JsonValue.Obj(root + (key to value))) }
            assertTrue(error.message.orEmpty().contains(message, ignoreCase = true), error.message)
        }
        for (voice in OtisVoice.entries) repeat(5) { index ->
            val controls = Otis.scramble(voice, Random(index), near = OtisPresets.forVoice(voice).first())
            assertTrue(controls.values.all { it.isFinite() && it in 0f..1f })
            val audio = Otis.render(voice, controls)
            assertTrue(audio.samples.all { it.isFinite() } && audio.peak() > .01f)
        }
    }
}
