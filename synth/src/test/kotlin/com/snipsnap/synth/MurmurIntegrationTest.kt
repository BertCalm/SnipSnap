package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.xpm.WavInfo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MurmurIntegrationTest {
    private fun patch(voice: MurmurVoice = MurmurVoice.KEY) =
        MurmurPatch("My Mouth", voice, Murmur.defaults(voice), midi = 57, velocity = .65f)

    @Test
    fun `complete routing ratios mouth targets trajectory envelopes root and velocity survive JSON and editing`() {
        for (voice in MurmurVoice.entries) {
            val original = patch(voice)
            val base = original.config
            val altered = base.copy(
                routing = MurmurRouting.entries.first { it != base.routing },
                ratios = base.ratios.mapIndexed { i, ratio -> if (i == 3) ratio + 1.0 else ratio },
                trajectory = MurmurTrajectory.entries.first { it != base.trajectory },
                direction = -base.direction,
                referenceSeconds = base.referenceSeconds * .85,
                attack = base.attack + .002,
                decay = base.decay * .9,
                sustain = base.sustain * .95,
                release = base.release * 1.1,
                targets = base.targets.map { it.copy(
                    name = "Custom ${it.name}", centers = it.centers.map { hz -> hz * 1.07 },
                    widths = it.widths.map { width -> width * 1.1 }, gains = it.gains.map { gain -> gain * .85 },
                    sourceBalance = (it.sourceBalance + .03).coerceAtMost(1.0),
                ) },
            )
            val source = original.copy(config = altered)
            val restored = assertIs<MurmurPatch>(Patches.fromJsonText(source.toJsonText()))
            assertEquals(source, restored, "$voice loses its custom instrument definition")
            assertEquals(source.toJsonText(), restored.toJsonText())
            val edited = Patches.edited(source, "Edited Mouth", source.macros + ("MOUTH" to .7f))
            assertEquals(source.copy(name = "Edited Mouth", macros = source.macros + ("MOUTH" to .7f)), edited)
            assertEquals(source.copy(macros = mapOf("EDGE" to .3f)), source.withMacros(mapOf("EDGE" to .3f)))
            if (voice == MurmurVoice.KEY) {
                assertContentEquals(source.render().samples, restored.render().samples)
                assertFalse(original.render().samples.contentEquals(source.render().samples),
                    "stored operator and trajectory changes do not reach the renderer")
            }
        }
    }

    @Test
    fun `optional note config fields use voice defaults and partial macros preserve their meaning`() {
        for (optional in listOf("", "\"midi\":null,\"velocity\":null,\"config\":null,")) {
            val text = """{"engine":"MURMUR","version":1,"name":"Partial","voice":"PLUCK",$optional"macros":{"MOUTH":0.6},"future":true}"""
            val restored = MurmurPatch.fromJsonText(text)
            assertEquals(Murmur.DEFAULT_MIDI, restored.midi)
            assertEquals(1f, restored.velocity)
            assertEquals(Murmur.configurationFor(MurmurVoice.PLUCK), restored.config)
            assertEquals(mapOf("MOUTH" to .6f), restored.macros)
        }
        val source = MurmurPatch("Partial", MurmurVoice.PLUCK, mapOf("MOUTH" to .6f))
        assertContentEquals(Murmur.render(source.voice, Murmur.defaults(source.voice) + source.macros).samples,
            source.render().samples, "missing macros do not resolve to voice defaults")
    }

    @Test
    fun `invalid patch and instrument definitions are rejected before rendering`() {
        val source = patch()
        fun changed(name: String, value: JsonValue) = JsonValue.Obj(
            LinkedHashMap(source.toJsonValue().entries).also { it[name] = value })
        // Every refusal names the guard it is meant to reach. A type-only check passed for any require() in the
        // constructor chain, and a fractional midi is refused by the JSON integer reader before MURMUR's range
        // guard is ever consulted, so the range guard now also gets whole numbers outside it.
        fun refuses(expected: String, label: String, block: () -> Unit) {
            val refused = assertFailsWith<IllegalArgumentException>(label) { block() }
            assertEquals(expected, refused.message, "$label was refused by the wrong guard")
        }
        fun refusesJson(expected: String, label: String, block: () -> Unit) {
            val refused = assertFailsWith<JsonException>(label) { block() }
            assertEquals(expected, refused.message, "$label was refused by the wrong guard")
        }
        for ((key, value, expected) in listOf(
            Triple("engine", JsonValue.Str("GYRE"), "not a MURMUR patch: GYRE"),
            Triple("version", JsonValue.Num(2.0), "unsupported patch version 2"),
            Triple("voice", JsonValue.Str("KICK"), "unknown voice KICK"),
            Triple("midi", JsonValue.Num(60.5), "expected an integer, got 60.5"),
            Triple("midi", JsonValue.Num(35.0), "MURMUR midi out of 36..96: 35"),
            Triple("midi", JsonValue.Num(97.0), "MURMUR midi out of 36..96: 97"),
            Triple("velocity", JsonValue.Num(1.5), "MURMUR velocity out of 0..1: 1.5"),
        )) {
            refusesJson(expected, "saved $key $value") { MurmurPatch.fromJsonValue(changed(key, value)) }
        }
        refuses("patch name must not be blank", "blank name") { source.copy(name = " ") }
        for (midi in listOf(Murmur.MIN_MIDI - 1, Murmur.MAX_MIDI + 1)) {
            refuses("MURMUR midi out of 36..96: $midi", "midi $midi") { source.copy(midi = midi) }
        }
        for (velocity in listOf(-.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            refuses("MURMUR velocity out of 0..1: $velocity", "velocity $velocity") { source.copy(velocity = velocity) }
        }
        val unknownMacro = assertFailsWith<IllegalArgumentException> { source.copy(macros = mapOf("TUNE" to .5f)) }
        assertTrue(unknownMacro.message.orEmpty().startsWith("unknown macro TUNE for KEY"), "TUNE refused as: ${unknownMacro.message}")
        for ((name, value) in listOf("MOUTH" to -.1f, "EDGE" to 1.1f, "BREATH" to Float.NaN)) {
            refuses("macro $name out of 0..1: $value", "$name $value") { source.copy(macros = mapOf(name to value)) }
        }
        val config = source.config
        refuses("Murmur needs four bounded operator ratios", "three ratios") { config.copy(ratios = listOf(1.0, 2.0, 3.0)) }
        refuses("Murmur needs four bounded operator ratios", "zero ratios") { config.copy(ratios = config.ratios.map { 0.0 }) }
        refuses("Murmur's principal carrier must bear the requested root", "carrier ratio 2") {
            config.copy(ratios = listOf(2.0) + config.ratios.drop(1))
        }
        refuses("Murmur trajectory direction must be -1 or +1", "direction 0") { config.copy(direction = 0) }
        refuses("invalid Murmur trajectory time", "reference NaN") { config.copy(referenceSeconds = Double.NaN) }
        refuses("invalid Murmur attack", "attack -1") { config.copy(attack = -1.0) }
        refuses("invalid Murmur decay", "decay 0") { config.copy(decay = 0.0) }
        refuses("invalid Murmur sustain", "sustain 0") { config.copy(sustain = 0.0) }
        refuses("invalid Murmur release", "release 0") { config.copy(release = 0.0) }
        refuses("Murmur needs a consistent spectral-target path", "no targets") { config.copy(targets = emptyList()) }
        val target = config.targets.first()
        refuses("invalid Murmur bandwidth", "zero widths") { target.copy(widths = target.widths.map { 0.0 }) }
        refuses("invalid Murmur center frequency", "infinite centers") { target.copy(centers = target.centers.map { Double.POSITIVE_INFINITY }) }
        refuses("invalid Murmur formant gain", "negative gains") { target.copy(gains = target.gains.map { -1.0 }) }
        refuses("invalid Murmur source balance", "balance 1.1") { target.copy(sourceBalance = 1.1) }
        val badConfig = JsonValue.Obj(LinkedHashMap(source.toJsonValue().entries.getValue("config").obj()).also {
            it["routing"] = JsonValue.Str("CYCLIC_UNBOUNDED")
        })
        refusesJson("unknown MURMUR routing", "routing CYCLIC_UNBOUNDED") { MurmurPatch.fromJsonValue(changed("config", badConfig)) }
    }

    @Test
    fun `pad recipes replay effects and production velocity layers excite the saved instrument`() {
        val source = patch()
        val rack = FxChain(reverse = true)
        val recipe = PadRecipe(source, rack)
        val restored = PadRecipe.fromJsonText(recipe.toJsonText())
        assertEquals(recipe, restored)
        val reference = source.render()
        assertContentEquals(rack.process(reference).samples, restored.render().samples)
        assertFalse(reference.samples.contentEquals(restored.render().samples))
        val soft = source.copy(velocity = .25f).render()
        assertContentEquals(soft.samples, Velocity.atVelocity(source, .25f).samples)
        assertContentEquals(Velocity.peakMatch(reference, soft).samples,
            Velocity.layerAt(reference, source, null, .25f, Velocity.brightnessSpec(source)).samples)
        assertFalse(Velocity.soften(reference, .75f).samples.contentEquals(soft.samples),
            "velocity uses a finished-sample filter instead of the saved excitation")
        assertTrue(Velocity.canUseAtVelocity(reference, source, null))
        assertFalse(Velocity.canUseAtVelocity(reference, source, rack))
    }

    @Test
    fun `provisional dry recipes are discoverable for every voice and preserve pitched routing`() {
        val presets = MurmurPresets.all()
        assertTrue(presets.isNotEmpty())
        assertEquals(presets.size, presets.map { it.name }.distinct().size)
        assertTrue(Presets.all().containsAll(presets))
        for (voice in MurmurVoice.entries) {
            val group = MurmurPresets.forVoice(voice)
            assertTrue(group.isNotEmpty(), "$voice has no audition proposal")
            assertEquals(group, Presets.forVoice(MurmurPatch.ENGINE, voice.name))
            for (proposal in group) {
                assertEquals(proposal, Patches.fromJsonText(proposal.toJsonText()))
                val expected = if (Murmur.isLoop(proposal.macros.getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL
                assertEquals(expected, Murmur.drumClassFor(proposal.voice, proposal.macros))
            }
        }
    }

    @Test
    fun `ordinary bass and breath-heavy attacks avoid drum classes even without the length shortcut`() {
        val forbidden = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
            DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
        for (voice in MurmurVoice.entries) for (midi in listOf(Murmur.MIN_MIDI, Murmur.DEFAULT_MIDI, Murmur.MAX_MIDI))
            for (breath in listOf(Murmur.defaults(voice).getValue("BREATH"), .8f)) {
            val sound = Murmur.render(voice, mapOf("BREATH" to breath, "HOLD" to 0f), midi)
            // A >1.5 s sample is automatically LOOP. Keeping just the first second exercises actual spectrum/decay guards.
            val short = Snip(sound.samples.copyOf(minOf(sound.samples.size, Dsp.RATE)), 1, Dsp.RATE)
            val detected = Classifier.classify(short)
            println("MURMUR CLASSIFIER $voice BREATH $breath MIDI $midi: ${detected.drumClass}, " +
                "centroid ${detected.features.centroidHz}, high ratio ${detected.features.highRatio}, decay ${detected.features.decayMs}")
            assertTrue(detected.drumClass !in forbidden, "$voice MIDI $midi BREATH $breath is mistaken for ${detected.drumClass}")
            assertTrue(detected.drumClass != DrumClass.LOOP, "the classifier guard only checked sample length")
        }
        for (voice in MurmurVoice.entries) {
            val macros = Murmur.defaults(voice).mapValues { (name, _) -> if (name == "HOLD") 0f else 1f }
            val sound = Murmur.render(voice, macros, Murmur.MAX_MIDI)
            val head = Snip(sound.samples.copyOf(minOf(sound.samples.size, Dsp.RATE)), 1, Dsp.RATE)
            val detected = Classifier.classify(head)
            println("MURMUR CLASSIFIER $voice all-high MIDI ${Murmur.MAX_MIDI}: ${detected.drumClass}, flatness ${detected.features.flatness}")
            assertTrue(detected.drumClass !in forbidden, "$voice all-high top note is mistaken for ${detected.drumClass}")
            assertTrue(detected.drumClass != DrumClass.LOOP)
        }
    }

    @Test
    fun `the dry sixteen-pad kit retains exact recipes and exports MPC native samples without choke groups`() {
        val temp = java.nio.file.Files.createTempDirectory("murmur-kit").toFile()
        try {
            val arranged = SynthKits.murmur()
            assertEquals(16, arranged.size)
            val patches = arranged.mapIndexed { i, nullable ->
                val pad = assertNotNull(nullable, "pad ${i + 1} is empty")
                val recipe = PadRecipe.fromJsonValue(assertNotNull(pad.recipe))
                assertEquals(null, recipe.fx, "pad ${i + 1} depends on a rack effect for its identity")
                val saved = assertIs<MurmurPatch>(recipe.patch)
                assertEquals(Murmur.drumClassFor(saved.voice, saved.macros), pad.drumClass)
                assertTrue(pad.drumClass in setOf(DrumClass.TONAL, DrumClass.LOOP))
                assertEquals(saved, Patches.fromJsonText(saved.toJsonText()))
                if (i == 0 || i == arranged.lastIndex) assertContentEquals(pad.snip.samples, recipe.render().samples)
                saved
            }
            val root = patches.first().midi
            for ((i, semitones) in listOf(0, 3, 5, 7, 10, 12, 15, 17).withIndex()) {
                assertEquals(MurmurVoice.KEY, patches[i].voice)
                assertEquals(root + semitones, patches[i].midi)
            }
            assertTrue(arranged.any { it!!.drumClass == DrumClass.LOOP }, "the kit omits the settled sustain example")
            val kitDir = File(temp, "kit")
            val kit = KitAssembler.assembleArranged("Murmur Audition", arranged, kitDir)
            assertFalse(Preflight.check(kit, kitDir).blocked())
            assertTrue(kit.pads.all { it.muteGroup == 0 }, "pitched voices inherit drum choking")
            val exported = KitExporter.exportProgramFolder(kit, kitDir, File(temp, "sd"))
            assertTrue(exported.program.isFile)
            assertEquals(16, exported.samples.size)
            for (file in exported.samples) {
                val info = WavInfo.read(file)
                assertEquals(44100, info.sampleRate)
                assertEquals(1, info.channels)
                assertEquals(24, info.bitsPerSample)
                assertTrue(info.frameCount > 0)
            }
        } finally {
            temp.deleteRecursively()
        }
    }
}
