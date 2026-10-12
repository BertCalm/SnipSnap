package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Saved full profiles and the deliberately small, dry first-listen bank. */
class LacquerPresetsTest {
    @Test
    fun `eight audition recipes cover six voices and preserve every complete profile`() {
        val names = setOf("Glass Coat", "Warm Underlay", "Three Arrivals", "Amber Brass", "Soft Surface", "Delayed Tine", "Silk Block", "Close Finish")
        val presets = LacquerPresets.all()
        assertEquals(names, presets.map { it.name }.toSet())
        assertEquals(8, presets.size, "preset expansion follows the owner's dry sonic approval")
        assertEquals(LacquerVoice.entries.toSet(), presets.map { it.voice }.toSet())
        assertTrue(Presets.all().containsAll(presets))
        for (voice in LacquerVoice.entries) {
            assertEquals(LacquerPresets.forVoice(voice), Presets.forVoice("LACQUER", voice.name))
        }
        assertTrue(Presets.forVoice("LACQUER", "NOT_A_VOICE").isEmpty())
        for (patch in presets) {
            assertEquals(Lacquer.macrosFor(patch.voice).map { it.name }.toSet(), patch.macros.keys, patch.name)
            assertTrue(patch.name.length <= 18)
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(patch.name))
            assertEquals(patch, Presets.byName("LACQUER", patch.voiceName, patch.name))
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            assertEquals(patch.toJsonText(), LacquerPatch.fromJsonText(patch.toJsonText()).toJsonText())
            val encoded = patch.toJsonValue().entries
            assertEquals(1, encoded.getValue("model").int())
            assertEquals(setOf("layers", "processing", "loop"), encoded.getValue("profile").obj().keys)
            for (layer in encoded.getValue("profile").obj().getValue("layers").arr()) {
                assertEquals(setOf("role", "source", "ratio", "gain", "cutoffHz", "filterEnvelope", "envelope", "timing"), layer.obj().keys)
                assertEquals(setOf("attackSeconds", "decaySeconds", "sustain", "releaseSeconds"), layer.obj().getValue("envelope").obj().keys)
                assertEquals(setOf("onsetSeconds", "reliefSeconds", "attackRelief", "decayRelief", "holdPhase"), layer.obj().getValue("timing").obj().keys)
            }
        }
    }

    @Test
    fun `custom source timing processing and loop data survive editing saving and regeneration`() {
        val initial = Lacquer.profileFor(LacquerVoice.KEY)
        val custom = initial.copy(
            layers = initial.layers.map { layer -> layer.copy(
                source = if (layer.role == LacquerRole.EDGE) LacquerSource.GLASS else layer.source,
                ratio = if (layer.role == LacquerRole.EDGE) 2f else layer.ratio,
                gain = layer.gain * .9f,
                cutoffHz = layer.cutoffHz * .95f,
                filterEnvelope = layer.filterEnvelope * .8f,
                envelope = layer.envelope.copy(
                    attackSeconds = layer.envelope.attackSeconds * .9f,
                    decaySeconds = layer.envelope.decaySeconds * .9f,
                    sustain = layer.envelope.sustain * .9f,
                    releaseSeconds = layer.envelope.releaseSeconds * .9f,
                ),
                timing = layer.timing.copy(onsetSeconds = .002f, reliefSeconds = layer.timing.reliefSeconds * .9f,
                    attackRelief = layer.timing.attackRelief * .9f, decayRelief = layer.timing.decayRelief * .9f,
                    holdPhase = (layer.timing.holdPhase + .03f).coerceAtMost(.98f)),
            ) },
            processing = initial.processing.copy(drive = initial.processing.drive * .9f,
                compression = initial.processing.compression * .8f, colorHz = initial.processing.colorHz * .95f),
            loop = initial.loop.copy(seconds = initial.loop.seconds * .9f,
                articulationDepth = initial.loop.articulationDepth * .8f, spectralDepth = initial.loop.spectralDepth * .8f),
        )
        val patch = LacquerPatch("Saved Finish", LacquerVoice.KEY, Lacquer.defaults(LacquerVoice.KEY), midi = 36, velocity = .35f, profile = custom)
        val restored = assertIs<LacquerPatch>(Patches.fromJsonText(patch.toJsonText()))
        assertEquals(patch, restored)
        assertEquals(custom, restored.profile)
        assertContentEquals(patch.render().samples, restored.render().samples)
        val changed = patch.macros + ("RELIEF" to .81f)
        val edited = Patches.edited(patch, "New Finish", changed)
        assertEquals(patch.copy(name = "New Finish", macros = changed), edited)
        assertEquals(patch.copy(macros = changed), patch.withMacros(changed))
        val fx = FxChain(crunch = mapOf("BITS" to .4f, "RATE" to .25f))
        val recipe = PadRecipe(patch, fx)
        val savedRecipe = PadRecipe.fromJsonText(recipe.toJsonText())
        assertEquals(recipe, savedRecipe)
        assertTrue(savedRecipe.alias)
        assertContentEquals(fx.process(patch.render()).samples, savedRecipe.render().samples)
    }

    @Test
    fun `invalid versions missing full model fields and unsafe layers are rejected`() {
        val patch = LacquerPresets.all().first()
        fun changed(field: String, value: JsonValue) = JsonValue.Obj(LinkedHashMap(patch.toJsonValue().entries).also { it[field] = value })
        for (field in listOf("profile", "midi", "velocity", "model")) {
            val omitted = JsonValue.Obj(LinkedHashMap(patch.toJsonValue().entries).also { it.remove(field) })
            assertFailsWith<JsonException>("omitted $field silently invents a different saved recipe") { LacquerPatch.fromJsonValue(omitted) }
        }
        assertFailsWith<JsonException> { LacquerPatch.fromJsonValue(changed("model", JsonValue.Num(2.0))) }
        assertFailsWith<JsonException> { LacquerPatch.fromJsonValue(changed("version", JsonValue.Num(2.0))) }
        assertFailsWith<JsonException> { LacquerPatch.fromJsonValue(changed("voice", JsonValue.Str("KICK"))) }
        assertFailsWith<JsonException> { LacquerPatch.fromJsonValue(changed("midi", JsonValue.Num(48.5))) }
        assertFailsWith<JsonException> { LacquerPatch.fromJsonValue(changed("velocity", JsonValue.Num(1.01))) }
        val profileJson = patch.toJsonValue().entries.getValue("profile").obj()
        val unsafeLayer = JsonValue.Obj(LinkedHashMap(profileJson.getValue("layers").arr().first().obj()).also { it["ratio"] = JsonValue.Num(1.25) })
        val unsafeProfile = JsonValue.Obj(LinkedHashMap(profileJson).also {
            it["layers"] = JsonValue.Arr(listOf(unsafeLayer) + profileJson.getValue("layers").arr().drop(1))
        })
        assertFailsWith<JsonException> { LacquerPatch.fromJsonValue(changed("profile", unsafeProfile)) }
        for (macros in listOf(mapOf("REVERB" to .5f), mapOf("BODY" to 1.1f), mapOf("GLUE" to Float.NaN))) {
            assertFailsWith<IllegalArgumentException> { patch.copy(macros = macros) }
        }
        for (velocity in listOf(-.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(velocity = velocity) }
        }
    }

    @Test
    fun `normal bass and bright glass pluck attacks stay in pitched routing`() {
        val probes = LacquerVoice.entries.map { voice ->
            LacquerPatch("Probe", voice, Lacquer.defaults(voice), midi = if (voice == LacquerVoice.BASS) 24 else 48)
        } + listOf(LacquerVoice.GLASS, LacquerVoice.PLUCK).map { voice ->
            LacquerPatch("Bright Probe", voice, Lacquer.defaults(voice) + mapOf("SHEEN" to 1f, "CONTOUR" to 0f), midi = 72)
        }
        for (patch in probes) {
            val routed = Lacquer.drumClassFor(patch.voice, patch.macros)
            val measured = Classifier.classify(patch.render()).drumClass
            assertTrue(routed !in DRUMS, "${patch.voice}: routed $routed")
            assertTrue(measured !in DRUMS, "${patch.voice} MIDI ${patch.midi}: classifier $measured")
            println("LACQUER pitched classifier: ${patch.voice} MIDI ${patch.midi} -> $measured")
        }
    }

    @Test
    fun `dry kit stores every layer recipe and routes its pentatonic notes and held pad`() {
        val kit = SynthKits.lacquer()
        assertEquals(16, kit.size)
        val patches = kit.mapIndexed { i, pad ->
            val actual = requireNotNull(pad)
            val recipe = PadRecipe.fromJsonValue(requireNotNull(actual.recipe))
            val patch = assertIs<LacquerPatch>(recipe.patch)
            assertEquals(null, recipe.fx, "A${i + 1} stays dry for first acceptance")
            assertFalse(recipe.alias)
            assertEquals(Lacquer.drumClassFor(patch.voice, patch.macros), actual.drumClass)
            assertTrue(actual.drumClass !in DRUMS)
            assertContentEquals(actual.snip.samples, recipe.render().samples, "A${i + 1} saved full profile regenerates")
            patch
        }
        assertEquals(LacquerVoice.entries.toSet(), patches.map { it.voice }.toSet())
        assertEquals(listOf(48, 51, 53, 55, 58, 60, 63, 65), patches.take(8).map { it.midi })
        assertTrue(patches.take(8).all { it.voice == LacquerVoice.KEY })
        assertEquals(1, kit.count { it!!.drumClass == DrumClass.LOOP })
        assertEquals(DrumClass.LOOP, kit[14]!!.drumClass)
    }

    @Test
    fun `event velocity reaches the engine without changing saved macros or layer profile`() {
        val patch = LacquerPresets.forVoice(LacquerVoice.KEY).first().copy(midi = 57, velocity = .7f)
        val original = patch.copy()
        val soft = Velocity.atVelocity(patch, .25f)
        assertContentEquals(patch.copy(velocity = .25f).render().samples, soft.samples)
        assertFalse(soft.samples.contentEquals(Velocity.atVelocity(patch, 1f).samples))
        assertEquals(original, patch)
    }

    private companion object {
        val DRUMS = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
    }
}
