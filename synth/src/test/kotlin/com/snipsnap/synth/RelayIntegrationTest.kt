package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavReader
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.Exporters
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Importer
import com.snipsnap.kit.Severity
import com.snipsnap.mpc3.SafeXml
import java.io.File
import org.w3c.dom.Element
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Persisted phrases and exported pads must retain the sound the player chose. */
class RelayIntegrationTest {
    private val temp = java.nio.file.Files.createTempDirectory("relay-integration").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun customPhrase(voice: RelayVoice): RelayPhrase {
        val phrase = Relay.phraseFor(voice)
        return phrase.copy(
            sources = phrase.sources.mapIndexed { index, source ->
                source.copy(gain = source.gain * 0.8f, brightness = 0.2f + index * 0.15f, decaySeconds = source.decaySeconds + 0.2f)
            },
            slots = phrase.slots.mapIndexed { index, slot ->
                slot.copy(beats = slot.beats + index * 0.1f, transitionBeats = 0.125f)
            },
            orderRoster = phrase.orderRoster.reversed(),
            accents = listOf(RelayAccent(0f, 0.8f), RelayAccent(0.5f, 0.3f), RelayAccent(1f, 0.6f)),
            tempoBpm = 137f,
            loop = RelayLoop(prerollCycles = 3, wrapFrames = 512),
        )
    }

    @Test
    fun `complete custom phrases survive patch recipe and macro editing formats`() {
        for (voice in RelayVoice.entries) {
            val patch = RelayPatch(
                "Saved Handoff", voice,
                Relay.defaults(voice) + mapOf("TUNE" to 0.25f, "HOLD" to 1f),
                phrase = customPhrase(voice), velocity = 0.65f,
            )
            assertEquals(patch, RelayPatch.fromJsonText(patch.toJsonText()), voice.name)
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()), voice.name)
            val recipe = PadRecipe(patch, FxChain(tape = mapOf("WOBBLE" to 0.1f)))
            assertEquals(recipe, PadRecipe.fromJsonText(recipe.toJsonText()), voice.name)
            val edited = assertIs<RelayPatch>(Patches.edited(patch, "Edited Handoff", mapOf("ORDER" to 0f)))
            assertEquals(patch.copy(name = "Edited Handoff", macros = mapOf("ORDER" to 0f)), edited)
            assertEquals(patch.phrase, edited.phrase, "macro edits retain the source definitions and clock")
            assertEquals(patch.velocity, edited.velocity)
        }
        val sparse = RelayPatch("Sparse", RelayVoice.KEY, mapOf("PULSE" to 0.1f))
        assertEquals(sparse, Patches.fromJsonText(sparse.toJsonText()), "omitted macros retain implicit defaults")

        val patch = RelayPatch("Replay", RelayVoice.KEY, Relay.defaults(RelayVoice.KEY), customPhrase(RelayVoice.KEY), 0.7f)
        val recipe = PadRecipe(patch, FxChain(tape = mapOf("WOBBLE" to 0.1f)))
        assertContentEquals(recipe.render().samples, PadRecipe.fromJsonText(recipe.toJsonText()).render().samples,
            "a saved custom clock and its effects regenerate exactly")
    }

    @Test
    fun `invalid saved phrases fail as file format errors instead of changing the performance`() {
        val patch = RelayPatch("Strict", RelayVoice.KEY, Relay.defaults(RelayVoice.KEY))
        val root = patch.toJsonValue()
        val phrase = assertIs<JsonValue.Obj>(root.entries.getValue("phrase"))
        fun alteredPhrase(key: String, value: JsonValue): JsonValue.Obj =
            JsonValue.Obj(root.entries + ("phrase" to JsonValue.Obj(phrase.entries + (key to value))))
        fun firstObjectChanged(key: String, field: String, value: JsonValue): JsonValue.Obj {
            val items = assertIs<JsonValue.Arr>(phrase.entries.getValue(key)).items.toMutableList()
            val first = assertIs<JsonValue.Obj>(items[0])
            items[0] = JsonValue.Obj(first.entries + (field to value))
            return alteredPhrase(key, JsonValue.Arr(items))
        }
        // Each document carries the words of the one guard it is meant to reach. A type-only check passed whenever
        // anything in the decode or constructor chain refused the document, so deleting the intended guard while
        // another still tripped went unseen; a row now fails on its own guard by name.
        val malformed = listOf(
            JsonValue.Obj(root.entries + ("version" to JsonValue.Num(99.0))) to "unsupported patch version 99",
            JsonValue.Obj(root.entries + ("model" to JsonValue.Num(99.0))) to "unsupported relay model 99",
            JsonValue.Obj(root.entries + ("velocity" to JsonValue.Num(1.1))) to "RELAY velocity out of 0..1: 1.1",
            JsonValue.Obj(root.entries + ("voice" to JsonValue.Str("NOT_A_VOICE"))) to "unknown voice NOT_A_VOICE",
            JsonValue.Obj(root.entries + ("phrase" to JsonValue.Obj(phrase.entries - "slots"))) to "relay phrase has no slots",
            alteredPhrase("tempoBpm", JsonValue.Num(0.0)) to "RELAY tempo must be in 60..180 BPM",
            firstObjectChanged("slots", "source", JsonValue.Num(99.0)) to "RELAY source ID must be in 0..3",
            firstObjectChanged("slots", "source", JsonValue.Num(0.5)) to "expected an integer, got 0.5",
            firstObjectChanged("slots", "beats", JsonValue.Num(0.0)) to "RELAY dwell must be positive (.1..4 beats)",
            firstObjectChanged("slots", "transitionBeats", JsonValue.Num(-0.1)) to "RELAY transition must be in .01..2 beats",
            firstObjectChanged("sources", "role", JsonValue.Str("FACTORY_PCM")) to "unknown relay source role FACTORY_PCM",
            firstObjectChanged("sources", "gain", JsonValue.Num(-0.1)) to "RELAY source gain must be in 0..1",
            firstObjectChanged("accents", "beat", JsonValue.Num(99.0)) to "RELAY accents must lie within the phrase",
            alteredPhrase("orderRoster", JsonValue.Arr(listOf(JsonValue.Arr(List(patch.phrase.slots.size) { JsonValue.Num(0.0) })))) to
                "RELAY orders must preserve the slot source IDs and event count",
            alteredPhrase("loop", JsonValue.Obj(mapOf("prerollCycles" to JsonValue.Num(3.0), "wrapFrames" to JsonValue.Num(0.0)))) to
                "RELAY wrap must be in 256..2048 frames",
        )
        for ((index, row) in malformed.withIndex()) {
            val (value, guard) = row
            val refused = assertFailsWith<JsonException>("malformed phrase $index") { Patches.fromJsonValue(value) }
            assertEquals(guard, refused.message, "malformed phrase $index was refused by the wrong guard")
        }
        val engine = assertFailsWith<JsonException> { RelayPatch.fromJsonText(patch.toJsonText().replace("RELAY", "TREMOR")) }
        assertEquals("not a RELAY patch: TREMOR", engine.message)
        val blank = assertFailsWith<IllegalArgumentException> { RelayPatch(" ", RelayVoice.KEY, emptyMap()) }
        assertEquals("patch name must not be blank", blank.message)
        val unknown = assertFailsWith<IllegalArgumentException> { RelayPatch("x", RelayVoice.KEY, mapOf("TEMPO" to 0.5f)) }
        assertTrue(unknown.message.orEmpty().startsWith("unknown macro TEMPO for KEY"), "unknown macro refused as: ${unknown.message}")
        for (value in listOf(-0.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val macro = assertFailsWith<IllegalArgumentException> { RelayPatch("x", RelayVoice.KEY, mapOf("ORDER" to value)) }
            assertEquals("macro ORDER out of 0..1: $value", macro.message)
            val velocity = assertFailsWith<IllegalArgumentException> { RelayPatch("x", RelayVoice.KEY, emptyMap(), velocity = value) }
            assertEquals("RELAY velocity out of 0..1: $value", velocity.message)
        }
    }

    @Test
    fun `the curated dry bank is reachable through the common preset roster`() {
        val presets = RelayPresets.all()
        assertEquals(RelayVoice.entries.toSet(), presets.map { it.voice }.toSet())
        assertEquals(presets.size, presets.map { it.name }.toSet().size)
        val expected = mapOf(RelayVoice.KEY to 8, RelayVoice.BASS to 6, RelayVoice.PLUCK to 6,
            RelayVoice.ORGAN to 6, RelayVoice.CHIME to 4, RelayVoice.FLOW to 6)
        assertEquals(36, presets.size)
        for (voice in RelayVoice.entries) {
            val roster = RelayPresets.forVoice(voice)
            assertEquals(expected.getValue(voice), roster.size, "curated bank for $voice")
            assertEquals(1, roster.count { Relay.isLoop(it.macros.getValue("HOLD")) }, "$voice has one held recipe")
            assertEquals(roster, Presets.forVoice(RelayPatch.ENGINE, voice.name))
            for (patch in roster) {
                assertEquals(Relay.macrosFor(voice).map { it.name }.toSet(), patch.macros.keys)
                assertTrue(patch.macros.getValue("HOLD") == 0f || patch.macros.getValue("HOLD") == 1f)
                assertEquals(patch, Presets.byName(RelayPatch.ENGINE, voice.name, patch.name))
                assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            }
        }
        assertTrue(Presets.forVoice(RelayPatch.ENGINE, "NOT_A_VOICE").isEmpty())
        assertEquals(presets, Presets.all().filter { it.engine == RelayPatch.ENGINE })
    }

    @Test
    fun `the sixteen pad starter keeps dry recipes root notes and held region routing`() {
        val pads = SynthKits.relay()
        assertEquals(16, pads.size)
        val patches = pads.mapIndexed { index, nullable ->
            val pad = assertNotNull(nullable)
            val recipe = PadRecipe.fromJsonValue(assertNotNull(pad.recipe))
            val patch = assertIs<RelayPatch>(recipe.patch)
            assertEquals(null, recipe.fx, "pad ${index + 1} is dry")
            assertEquals(Relay.drumClassFor(patch.voice, patch.macros), pad.drumClass)
            assertEquals(if (Relay.isLoop(patch.macros.getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL, pad.drumClass)
            assertTrue(pad.oneShot, "drum pads play the selected finite or repeating region once")
            assertTrue(pad.snip.peak() > 0f)
            patch
        }
        assertEquals(RelayVoice.entries.toSet(), patches.map { it.voice }.toSet())
        assertTrue(patches.take(8).all { it.voice == RelayVoice.KEY })
        assertEquals(listOf(0, 3, 5, 7, 10, 12, 15, 17), patches.take(8).map { (it.macros.getValue("TUNE") * Relay.TUNE_SEMITONES).toInt() })
        assertEquals(listOf("AFTERIMAGE KEY", "POCKET RELAY", "MUTED PASSAGE", "ORGAN AFTERGLOW",
            "BELL EXIT", "SOFT EXCHANGE"), patches.drop(8).take(6).map { it.name })
        for (a in pads.indices) for (b in a + 1 until pads.size) {
            assertFalse(pads[a]!!.snip.samples.contentEquals(pads[b]!!.snip.samples),
                "pads ${a + 1} and ${b + 1} duplicate the same rendered audio")
        }
        assertEquals(listOf(RelayVoice.ORGAN, RelayVoice.FLOW), patches.takeLast(2).map { it.voice })
        assertTrue(patches.takeLast(2).all { Relay.isLoop(it.macros.getValue("HOLD")) })
        for (index in listOf(0, 12, 15)) {
            val pad = assertNotNull(pads[index])
            assertContentEquals(pad.snip.samples, PadRecipe.fromJsonValue(assertNotNull(pad.recipe)).render().samples,
                "pad ${index + 1} recipe replay")
        }
        val dir = File(temp, "starter")
        val kit = KitAssembler.assembleArranged("Relay Starter", pads, dir)
        assertEquals(kit, KitStore.load(dir), "opaque complete recipes survive the kit sidecar")
    }

    @Test
    fun `native velocity keeps a custom phrase and its macros intact`() {
        val patch = RelayPatch("Touch", RelayVoice.CHIME, Relay.defaults(RelayVoice.CHIME), customPhrase(RelayVoice.CHIME), 0.8f)
        val macros = patch.macros.toMap()
        val phrase = patch.phrase.copy()
        val soft = Velocity.atVelocity(patch, 0.25f)
        assertContentEquals(Relay.render(patch.voice, patch.macros, velocity = 0.25f, phrase = phrase).samples, soft.samples,
            "native velocity must reach excitation without substituting the voice's default clock")
        assertEquals(macros, patch.macros)
        assertEquals(phrase, patch.phrase)
        assertEquals(0.8f, patch.velocity)
        assertFalse(soft.samples.contentEquals(patch.render().samples), "native articulation survives shared leveling")
        assertTrue(Velocity.atVelocity(patch, 0f).samples.all { it == 0f })
    }

    @Test
    fun `strong pulses and bright bell handoffs stay out of drum classifier families`() {
        val drums = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
        for (voice in RelayVoice.entries) {
            val classified = Classifier.classify(Relay.render(voice))
            assertTrue(classified.drumClass !in drums, "$voice default classified as $classified")
            assertEquals(DrumClass.TONAL, Relay.drumClassFor(voice, Relay.defaults(voice)))
        }
        for (voice in RelayVoice.entries) for (tune in listOf(0f, 1f)) {
            val macros = Relay.defaults(voice) + mapOf("PULSE" to 1f, "COLOR" to 1f, "LENGTH" to 0f, "TUNE" to tune)
            // A supported short clock exercises the spectrum/decay rules instead of relying
            // on Classifier's unconditional LOOP decision for files over 1.5 seconds.
            val base = Relay.phraseFor(voice)
            val compact = base.copy(
                tempoBpm = 180f,
                slots = base.slots.map { it.copy(beats = 0.5f, transitionBeats = 0.12f) },
                accents = base.accents.map { it.copy(beat = it.beat * 0.5f) },
            )
            val snip = Relay.render(voice, macros, phrase = compact)
            assertTrue(snip.durationSeconds <= 1.5f, "short classifier fixture bypassed the spectrum rules")
            val classified = Classifier.classify(snip)
            assertTrue(classified.drumClass !in drums, "$voice strong pulse / bright / short TUNE $tune classified as $classified")
            assertEquals(DrumClass.TONAL, Relay.drumClassFor(voice, macros))
        }
    }

    @Test
    fun `finite velocity layers and held regions export through both MPC generations`() {
        val finite = RelayPatch("Export Key", RelayVoice.KEY, Relay.defaults(RelayVoice.KEY), customPhrase(RelayVoice.KEY))
        val held = RelayPatch("Export Hold", RelayVoice.ORGAN, Relay.defaults(RelayVoice.ORGAN) + ("HOLD" to 1f))
        val arranged = listOf(
            ArrangedPad(finite.render(), DrumClass.TONAL, PadRecipe(finite).toJsonValue(),
                softVariants = listOf(Velocity.atVelocity(finite, 0.3f))),
            ArrangedPad(held.render(), DrumClass.LOOP, PadRecipe(held).toJsonValue()),
        )
        val source = File(temp, "export-source")
        val kit = KitAssembler.assembleArranged("Relay Export", arranged, source)
        for (format in listOf(ExportFormat.PROGRAM_FOLDER, ExportFormat.MPC3_TRACK)) {
            val outcome = Exporters.export(format, kit, source, File(temp, "card-${format.id}"), dualGeneration = true)
            val failures = ExportReadBack.verify(kit, outcome).filter { it.severity == Severity.FAIL }
            assertTrue(failures.isEmpty(), "$format: ${failures.joinToString { it.message }}")
            val sampleDir = outcome.companion ?: outcome.primary.parentFile
            for (pad in kit.pads) {
                val filenames = pad.velocityLayers.map { it.sampleFile }.ifEmpty { listOf(pad.sampleFile) }
                for (filename in filenames) {
                    val original = WavReader.read(File(source, filename))
                    val exported = WavReader.read(File(sampleDir, filename))
                    assertEquals(Dsp.RATE, exported.sampleRate)
                    assertEquals(1, exported.channels)
                    assertEquals(original.frameCount, exported.frameCount)
                    assertContentEquals(original.samples, exported.samples, "$format preserves the selected sample region")
                }
            }
            val xpm = if (format == ExportFormat.PROGRAM_FOLDER) outcome.primary else File(sampleDir, "${kit.name}.xpm")
            val document = SafeXml.newFactory().newDocumentBuilder().parse(xpm)
            val instruments = document.getElementsByTagName("Instrument")
            for (slot in 1..2) {
                val instrument = (0 until instruments.length).map { instruments.item(it) as Element }
                    .single { it.getAttribute("number").toInt() == slot - 1 }
                assertEquals("True", instrument.getElementsByTagName("OneShot").item(0).textContent,
                    "the drum-program export plays the selected region once")
                val layers = instrument.getElementsByTagName("Layer")
                for (i in 0 until layers.length) {
                    val layer = layers.item(i) as Element
                    assertEquals("0", layer.getElementsByTagName("SliceLoop").item(0).textContent)
                }
            }
            if (format == ExportFormat.MPC3_TRACK) {
                val imported = Mpc3Importer.import(outcome.primary, File(temp, "read-native"))
                assertEquals(2, imported.kit.pads.size)
                assertTrue(imported.kit.pads.all { it.oneShot })
                assertEquals(listOf(1 to 63, 64 to 127), imported.kit.pads.first().velocityLayers.map { it.velStart to it.velEnd })
                assertEquals(arranged[1].snip.frameCount, WavReader.read(File(imported.directory, imported.kit.pads.last().sampleFile)).frameCount,
                    "HOLD exports only its repeating region, with no attack prepended")
            }
        }
        val loop = arranged[1].snip
        assertTrue(loop.samples.all { it.isFinite() && abs(it) <= 1f })
    }
}
