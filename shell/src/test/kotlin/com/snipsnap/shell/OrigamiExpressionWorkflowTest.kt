package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.DestinationExists
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.OrigamiExpression
import com.snipsnap.synth.OrigamiGesture
import com.snipsnap.synth.OrigamiKeys
import com.snipsnap.synth.OrigamiPatch
import com.snipsnap.synth.OrigamiPresets
import com.snipsnap.synth.OrigamiVoice
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Velocity
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real C4 captures prove the expression/gain contract; cheap fixtures cover the 27-file map. */
class OrigamiExpressionWorkflowTest {
    private val rate = WavWriter.MPC_SAMPLE_RATE

    companion object {
        private fun authored(voice: OrigamiVoice, expression: OrigamiExpression) =
            OrigamiPresets.forVoice(voice).first().copy(
                name = "TOUCH PAPER", midi = 72, velocity = .61f, seed = -9007199254740993L,
                macros = mapOf("TUNE" to 0f, "CREASE" to .5f, "FOLD" to .9f,
                    "TURN" to .5f, "WARP" to .5f, "MOTION" to .4f),
                expression = expression,
            )

        // A test class is instantiated per method. Retain only these C4 banks,
        // rather than re-rendering a nine-root instrument for each workflow check.
        private val banks = mutableMapOf<OrigamiPatch, List<KeyNote>>()
        private fun actual(spec: OrigamiPadMaker.Spec): List<KeyNote> = synchronized(banks) {
            banks.getOrPut(spec.patch) { OrigamiPadMaker.renderVelocityZone(spec, 60) }
        }
    }

    private fun fixtures(spec: OrigamiPadMaker.Spec): List<List<KeyNote>> =
        OrigamiPadMaker.zoneMidis(spec).mapIndexed { zone, _ ->
            List(spec.layerCount) { layer ->
                KeyNote(Snip(FloatArray(512 + zone * 32 + layer * 64) { frame ->
                    (.12 * sin(2 * PI * frame / (32 + layer * 8))).toFloat()
                }, 1, rate), (64 + zone * 8 + layer * 4).toLong())
            }
        }

    private fun loaded(spec: OrigamiPadMaker.Spec, notes: List<KeyNote>): InstrumentEngine.Loaded {
        val layers = notes.mapIndexed { index, note ->
            val window = if (spec.layerCount == 1) 0..127 else OrigamiPadMaker.VELOCITY_WINDOWS[index]
            InstrumentStore.Layer("layer$index.wav", note.snip.frameCount.toLong(), note.loopStartFrame,
                window.first, window.last)
        }
        val hard = layers.last()
        val zone = InstrumentStore.Zone(60, 60, 60, hard.sample, hard.frameCount, hard.loopStartFrame, layers)
        val instrument = InstrumentStore.Instrument("TOUCH PAPER", spec.releaseSeconds, listOf(zone))
        return InstrumentEngine.Loaded(instrument, rate,
            layers.mapIndexed { index, layer -> layer.sample to notes[index].snip.samples }.toMap())
    }

    @Test
    fun `real C4 layers retain the authored velocity ceiling and apply key gain once`() {
        for (voice in listOf(OrigamiVoice.FAN, OrigamiVoice.STILL)) {
            val level = OrigamiPadMaker.Spec(authored(voice, OrigamiExpression.LEVEL), .2f)
            assertEquals(1, level.layerCount)
            val single = actual(level).single()
            val old = OrigamiPadMaker.renderZone(level, 60)
            assertEquals(old.loopStartFrame, single.loopStartFrame)
            assertContentEquals(old.snip.samples, single.snip.samples)

            val spec = level.copy(patch = level.patch.copy(expression = OrigamiExpression.FOLD))
            val notes = actual(spec)
            assertEquals(3, spec.layerCount)
            assertEquals(3, notes.size)
            val hard = notes.last()
            assertTrue(peak(hard.snip.samples) > .01f)
            for ((index, note) in notes.withIndex()) {
                assertTrue(note.loopStartFrame in 1 until note.snip.frameCount.toLong())
                assertEquals(peak(hard.snip.samples), peak(note.snip.samples), 1e-6f,
                    "$voice layer $index bakes a second velocity gain into the PCM")
                val touch = OrigamiPadMaker.RELATIVE_VELOCITIES[index]
                val bank = loaded(spec, notes)
                val hit = assertNotNull(KeyHit.resolve(bank.instrument, 60, touch) { file ->
                    bank.layerSamples[file]?.size?.toLong()
                })
                assertEquals("layer$index.wav", hit.sampleFile)
                assertEquals(touch * InstrumentEngine.VOICE_LEVEL, hit.gain)
                val player = InstrumentEngine(bank, rate)
                assertTrue(player.noteOn(60, touch))
                val frames = minOf(8192, note.snip.frameCount)
                val out = FloatArray(frames * 2)
                player.render(out, frames)
                assertTrue((0 until frames).all { frame ->
                    abs(out[frame * 2] - note.snip.samples[frame] * hit.gain) < 1e-6f &&
                        out[frame * 2] == out[frame * 2 + 1]
                }, "$voice layer $index must receive only the player's one linear key gain")
            }
            assertTrue(normalizedDifference(notes.first().snip.samples, hard.snip.samples) > 1e-4,
                "$voice soft and hard captures should differ in folded share after peak matching")
            assertEquals(.61f, spec.patch.velocity)
            assertEquals(level.patch.macros, spec.patch.macros)

            // Verify the actual excitation ceiling and one expression application,
            // not just that three peak-matched samples were returned.
            if (voice == OrigamiVoice.FAN) for (index in 0..2) {
                val relative = listOf(.3f, .6f, 1f)[index]
                val raw = OrigamiKeys.note(spec.patch.copy(velocity = spec.patch.velocity * relative), 60)
                val expected = if (index == 2) raw.snip else Velocity.peakMatch(hard.snip, raw.snip)
                assertEquals(raw.loopStartFrame, notes[index].loopStartFrame)
                assertContentEquals(expected.samples, notes[index].snip.samples)
            }
        }
    }

    @Test
    fun `finite SOFT HITS preserve the saved BLOOM recipe and stay beneath its quiet velocity ceiling`() {
        val temp = createTempDirectory("origami-expression-soft-hits").toFile()
        try {
            val patch = authored(OrigamiVoice.FAN, OrigamiExpression.FOLD).copy(
                name = "SOFT PAPER", midi = 60, velocity = .35f, gesture = OrigamiGesture.BLOOM,
            )
            val shelf = File(temp, "shelf")
            UserPresets.save(shelf, patch, nowMillis = 1000L)
            val restored = assertIs<OrigamiPatch>(UserPresets.read(shelf).single().patch)
            assertEquals(patch, restored, "preset disk recall must preserve the entire expression recipe")
            val line = UserPresets.rosterLine(restored)
            val prefix = "OrigamiPatch.fromJsonText("
            assertTrue(line.startsWith(prefix) && line.endsWith("),"), line)
            val promoted = Json.parse(line.removePrefix(prefix).removeSuffix("),")).str()
            assertEquals(patch, OrigamiPatch.fromJsonText(promoted),
                "promotion must retain the sparse-independent macros, 64-bit seed, MIDI, model, gesture and expression")

            val recipe = PadRecipe.fromJsonText(PadRecipe(restored).toJsonText())
            assertEquals(patch, recipe.patch)
            val dir = File(temp, "kit")
            val model = KitBuilderModel.create("Soft Paper", dir)
            model.assign(1, recipe.render(), DrumClass.TONAL, restored.name, knownRootMidi = 60)
            model.update(1) { it.copy(recipe = recipe.toJsonValue()) }
            model.save()
            val reopened = KitBuilderModel.open(dir)
            val original = assertNotNull(reopened.pad(1))
            assertEquals(recipe, PadRecipe.fromJsonValue(assertNotNull(original.recipe)))
            val mainFile = File(dir, original.sampleFile)
            val mainBytes = mainFile.readBytes()
            val main = WavReader.read(mainFile)
            val layered = reopened.addGhostLayers(1, softZones = 2)
            reopened.save()
            val ready = KitBuilderModel.open(dir)
            val pad = assertNotNull(ready.pad(1))
            assertEquals(layered.velocityLayers, pad.velocityLayers)
            assertEquals(recipe, PadRecipe.fromJsonValue(assertNotNull(pad.recipe)))
            assertEquals(mapOf("rootMidi" to "60", "rootNote" to "C4", "rootOrigin" to "authored"), pad.source)
            assertEquals(listOf(1..41, 42..84, 85..127), pad.velocityLayers.map { it.velStart..it.velEnd })
            assertEquals((1..127).toList(), pad.velocityLayers.flatMap { (it.velStart..it.velEnd).toList() })
            assertEquals(original.sampleFile, pad.velocityLayers.last().sampleFile)
            assertContentEquals(mainBytes, mainFile.readBytes(), "SOFT HITS must not rewrite its authored anchor")

            for ((index, relative) in listOf(.3f, .6f).withIndex()) {
                // Independent expected law: an absolute .6 strike would exceed
                // this recipe's .35 ceiling, and applying the depth law twice
                // would also produce different PCM. Do not call the new helpers.
                val velocity = patch.velocity * relative
                val depth = (.25 + .75 * velocity.toDouble().pow(1.25)).toFloat()
                val expectedPatch = patch.copy(
                    expression = OrigamiExpression.LEVEL,
                    velocity = velocity,
                    macros = patch.macros + ("FOLD" to patch.macros.getValue("FOLD") * depth),
                )
                val expected = Velocity.peakMatch(main, expectedPatch.render())
                val encoded = ByteArrayOutputStream().also {
                    WavWriter.write(it, expected, depth = WavWriter.BitDepth.PCM_24)
                }.toByteArray()
                assertContentEquals(encoded, File(dir, pad.velocityLayers[index].sampleFile).readBytes(),
                    "soft layer $index must use .35 × $relative, one folded-share law and main-reference peak matching before PCM24")
            }

            // PadHit supplies the native pad command. The selected recording is
            // independent of its one live gain, and these finite hits never loop.
            val recordings = pad.velocityLayers.associate { layer ->
                layer.sampleFile to WavReader.read(File(dir, layer.sampleFile))
            }
            val full = assertNotNull(PadHit.resolve(pad, 1f, 0) { recordings[it]?.frameCount?.toLong() })
            for ((index, touch) in listOf(.3f, .6f, 1f).withIndex()) {
                val hit = assertNotNull(PadHit.resolve(pad, touch, 0) { recordings[it]?.frameCount?.toLong() })
                assertEquals(pad.velocityLayers[index].sampleFile, hit.sampleFile)
                assertEquals(recordings.getValue(hit.sampleFile).frameCount.toLong(), hit.endFrameExclusive)
                assertEquals(full.gainLeft * touch, hit.gainLeft, 1e-6f)
                assertEquals(full.gainRight * touch, hit.gainRight, 1e-6f)
                assertEquals(1.0, hit.pitchRatio)
                assertEquals(-1L, hit.loopStartFrame)
            }
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun `twenty seven exported layers keep independent sampler sheets windows and version one recipes`() {
        val dir = createTempDirectory("origami-expression-export").toFile()
        try {
            val spec = OrigamiPadMaker.Spec(authored(OrigamiVoice.STILL, OrigamiExpression.FOLD)
                .copy(gesture = OrigamiGesture.UNFOLD), .73f)
            val notes = fixtures(spec)
            val program = OrigamiPadMaker.exportLayers("TOUCH PAPER", spec, notes, dir)
            assertEquals(9, program.keygroups.size)
            assertTrue(program.keygroups.all { it.layers.size == 3 })
            val data = File(dir, Mpc3TrackWriter.trackDataDirName(program.name))
            assertEquals(27, data.listFiles()!!.count { it.extension == "wav" })
            assertTrue(File(dir, "${program.name}.xty").isFile)
            val compatibility = File(data, "${program.name}.xpm").readText()
            val native = Mpc3TrackWriter().keygroupPayloadText(program)
            val (sidecar, instrument) = InstrumentStore.list(dir).single()
            assertEquals(.73f, instrument.release)
            assertEquals(27, instrument.sampleFiles.size)
            for ((zoneIndex, zone) in instrument.zones.withIndex()) {
                val midi = OrigamiPadMaker.zoneMidis(spec)[zoneIndex]
                assertEquals(midi, zone.rootNote)
                assertEquals(listOf(1..42, 43..84, 85..127), zone.layers.map { it.velStart..it.velEnd })
                assertEquals(zone.layers.last().sample, zone.sample)
                assertEquals(zone.layers.last().loopStartFrame, zone.loopStartFrame)
                assertEquals(zone.layers.last().frameCount, zone.frameCount)
                for ((layerIndex, layer) in zone.layers.withIndex()) {
                    val note = notes[zoneIndex][layerIndex]
                    val wav = File(sidecar.parentFile, layer.sample)
                    val stem = "TOUCHPAPER_${midi}_v${layerIndex + 1}"
                    assertEquals("$stem.wav", wav.name)
                    assertEquals(note.loopStartFrame, layer.loopStartFrame)
                    assertEquals(note.snip.frameCount.toLong(), layer.frameCount)
                    val sheet = SmplChunk(midi, SmplChunk.Loop(layer.loopStartFrame, layer.frameCount))
                    assertEquals(sheet, WavReader.readSmpl(wav))
                    val expected = ByteArrayOutputStream().also { WavWriter.write(it, note.snip, smpl = sheet) }
                    assertContentEquals(expected.toByteArray(), wav.readBytes())
                    assertTrue("\"sampleName\": \"$stem\"" in native)
                    assertTrue("<SampleName>$stem</SampleName>" in compatibility)
                    assertTrue("\"LoopStart\": ${layer.loopStartFrame}" in native)
                    assertTrue("<SliceLoopStart>${layer.loopStartFrame}</SliceLoopStart>" in compatibility)
                }
            }
            val recipe = OrigamiPadMaker.recipeFile(dir, program.name).readText()
            assertEquals(spec, OrigamiPadMaker.Spec.fromJsonText(recipe))
            val fields = Json.parse(recipe).obj()
            assertEquals(1, fields.getValue("version").int())
            assertEquals(1, fields.getValue("heldModel").int())
            val saved = fields.getValue("patch").obj()
            assertEquals(spec.patch.toJsonValue(), fields.getValue("patch"))
            val expression = saved.getValue("expression").obj()
            assertEquals(1, expression.getValue("model").int())
            assertEquals("FOLD", expression.getValue("mode").str())
            assertEquals("UNFOLD", saved.getValue("gesture").obj().getValue("name").str())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `loaded exported windows select their own frame lengths loops and gain at every boundary`() {
        val dir = createTempDirectory("origami-expression-windows").toFile()
        try {
            val spec = OrigamiPadMaker.Spec(authored(OrigamiVoice.FAN, OrigamiExpression.FOLD), .2f)
            OrigamiPadMaker.exportLayers("TOUCH PAPER", spec, fixtures(spec), dir)
            val (sidecar, instrument) = InstrumentStore.list(dir).single()
            val samples = instrument.sampleFiles.associateWith { WavReader.read(File(sidecar.parentFile, it)).samples }
            val loaded = InstrumentEngine.Loaded(instrument, rate, samples)
            val zone = assertNotNull(instrument.zoneFor(60))
            for ((midiVelocity, layerIndex) in listOf(1 to 0, 42 to 0, 43 to 1, 84 to 1, 85 to 2, 127 to 2)) {
                val velocity = midiVelocity / 127f
                val layer = zone.layers[layerIndex]
                val hit = assertNotNull(KeyHit.resolve(instrument, 60, velocity) { samples[it]?.size?.toLong() })
                assertEquals(layer.sample, hit.sampleFile)
                assertEquals(layer.frameCount, hit.frames)
                assertEquals(layer.loopStartFrame, hit.loopStartFrame)
                assertEquals(velocity * InstrumentEngine.VOICE_LEVEL, hit.gain)
                val player = InstrumentEngine(loaded, rate)
                assertTrue(player.noteOn(60, velocity))
                val out = FloatArray(128)
                player.render(out, 64)
                assertTrue((0 until 64).all { abs(out[it * 2] - samples.getValue(layer.sample)[it] * hit.gain) < 1e-6f })
            }
            val soft = zone.layers.first().sample
            val missing = InstrumentEngine(InstrumentEngine.Loaded(instrument, rate, samples - soft), rate)
            assertFalse(missing.noteOn(60, .3f), "a missing soft file must not silently play the hard layer")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `LEVEL old and layer APIs write byte identical packages with original stems`() {
        val dir = createTempDirectory("origami-level-identity").toFile()
        try {
            val spec = OrigamiPadMaker.Spec(authored(OrigamiVoice.FAN, OrigamiExpression.LEVEL), .73f)
            val layered = fixtures(spec)
            val single = layered.map { it.single() }
            val old = OrigamiPadMaker.assemble("TOUCH PAPER", spec, single)
            val adapted = OrigamiPadMaker.assembleLayers("TOUCH PAPER", spec, layered)
            assertEquals(old.first, adapted.first)
            assertEquals((48..72 step 3).map { "TOUCHPAPER_$it" }, old.second.keys.toList())
            assertEquals(old.second.keys, adapted.second.keys)
            old.second.forEach { (name, note) -> assertContentEquals(note.samples, adapted.second.getValue(name).samples) }
            assertTrue(old.first.keygroups.all { it.layers.single().let { l -> l.velStart == 0 && l.velEnd == 127 } })
            val oldDir = File(dir, "old")
            val layerDir = File(dir, "layers")
            OrigamiPadMaker.export("TOUCH PAPER", spec, single, oldDir)
            OrigamiPadMaker.exportLayers("TOUCH PAPER", spec, layered, layerDir)
            val oldFiles = oldDir.walkTopDown().filter { it.isFile }.associateBy { it.relativeTo(oldDir).path }
            val newFiles = layerDir.walkTopDown().filter { it.isFile }.associateBy { it.relativeTo(layerDir).path }
            assertEquals(oldFiles.keys, newFiles.keys)
            oldFiles.forEach { (path, file) -> assertContentEquals(file.readBytes(), newFiles.getValue(path).readBytes(), path) }
            assertFalse("expression" in Json.parse(spec.toJsonText()).obj().getValue("patch").obj())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `FOLD preview uses the real three layer selector for three full holds and key up fades`() {
        val spec = OrigamiPadMaker.Spec(authored(OrigamiVoice.FAN, OrigamiExpression.FOLD), .2f)
        val notes = actual(spec)
        val preview = OrigamiPadMaker.preview(spec)
        val heldFrames = rate * 3
        val performanceFrames = heldFrames + ceil((spec.releaseSeconds * rate).toDouble()).toInt() + rate / 20
        assertEquals(1, preview.channels)
        assertEquals(rate, preview.sampleRate)
        assertEquals(performanceFrames * 3, preview.frameCount)
        val player = InstrumentEngine(loaded(spec, notes), rate)
        val expected = FloatArray(preview.frameCount)
        for ((index, velocity) in listOf(.3f, .6f, 1f).withIndex()) {
            assertTrue(player.noteOn(60, velocity))
            val held = renderMono(player, heldFrames)
            held.copyInto(expected, index * performanceFrames)
            player.noteOff(60)
            val released = renderMono(player, performanceFrames - heldFrames)
            released.copyInto(expected, index * performanceFrames + heldFrames)
            assertTrue(released.takeLast(rate / 30).all { it == 0f })
        }
        assertContentEquals(expected, preview.samples)
    }

    @Test
    fun `old expression omission stays LEVEL while unsupported recipes versions and cancellation refuse`() {
        val patch = authored(OrigamiVoice.FAN, OrigamiExpression.FOLD)
        val spec = OrigamiPadMaker.Spec(patch)
        val fields = Json.parse(spec.toJsonText()).obj()
        val savedPatch = fields.getValue("patch").obj()
        val old = JsonValue.Obj(fields + ("patch" to JsonValue.Obj(savedPatch - "expression")))
        val restored = OrigamiPadMaker.Spec.fromJsonText(Json.write(old))
        assertEquals(spec.copy(patch = patch.copy(expression = OrigamiExpression.LEVEL)), restored)
        assertEquals(1, restored.layerCount)
        val expression = savedPatch.getValue("expression").obj()
        val unsupported = JsonValue.Obj(savedPatch + ("expression" to JsonValue.Obj(expression + ("model" to JsonValue.Num(99.0)))))
        assertFailsWith<JsonException> {
            OrigamiPadMaker.Spec.fromJsonText(Json.write(JsonValue.Obj(fields + ("patch" to unsupported))))
        }
        for (model in 1..3) assertFailsWith<IllegalArgumentException> { OrigamiPadMaker.Spec(patch.copy(model = model)) }
        assertFailsWith<IllegalArgumentException> { OrigamiPadMaker.Spec(patch.copy(voice = OrigamiVoice.CRANE)) }
        for (midi in listOf(47, 49, 73)) assertFailsWith<IllegalArgumentException> {
            OrigamiPadMaker.renderVelocityZone(spec, midi)
        }
        assertFailsWith<CancellationException> { OrigamiPadMaker.renderVelocityZone(spec, 60) { true } }
        assertFailsWith<CancellationException> { OrigamiPadMaker.preview(spec) { true } }
        val notes = fixtures(spec)
        assertFailsWith<IllegalArgumentException> { OrigamiPadMaker.assemble("TOUCH PAPER", spec, notes.map { it.last() }) }
        assertFailsWith<IllegalArgumentException> { OrigamiPadMaker.assembleLayers("TOUCH PAPER", spec, notes.dropLast(1)) }
        assertFailsWith<IllegalArgumentException> {
            OrigamiPadMaker.assembleLayers("TOUCH PAPER", spec, listOf(notes.first().dropLast(1)) + notes.drop(1))
        }
        val dir = createTempDirectory("origami-expression-occupied").toFile()
        try {
            val recipe = OrigamiPadMaker.recipeFile(dir, "TOUCH PAPER")
            recipe.writeText("saved before this attempt")
            assertFailsWith<DestinationExists> { OrigamiPadMaker.exportLayers("TOUCH PAPER", spec, notes, dir) }
            assertEquals("saved before this attempt", recipe.readText())
            assertEquals(listOf(recipe.name), dir.listFiles()!!.map { it.name })
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun renderMono(player: InstrumentEngine, frames: Int): FloatArray {
        val out = FloatArray(frames)
        val stereo = FloatArray(1024)
        var at = 0
        while (at < frames) {
            val count = minOf(512, frames - at)
            player.render(stereo, count)
            for (i in 0 until count) out[at + i] = stereo[i * 2]
            at += count
        }
        return out
    }

    private fun peak(samples: FloatArray): Float = samples.maxOf { abs(it) }

    private fun normalizedDifference(a: FloatArray, b: FloatArray): Double =
        (0 until minOf(a.size, b.size)).sumOf { abs(a[it].toDouble() - b[it]) } / minOf(a.size, b.size)
}
