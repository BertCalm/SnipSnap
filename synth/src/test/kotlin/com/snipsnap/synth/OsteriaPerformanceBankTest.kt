package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.kit.KitStore
import com.snipsnap.mpc3.MpcXRay
import com.snipsnap.xpm.WavInfo
import java.security.MessageDigest
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OsteriaPerformanceBankTest {
    @Test
    fun `sixteen bank definitions preserve twelve candidates across six families and three touches`() {
        val candidates = OsteriaSixVoiceCandidates.candidates()
        val pads = OsteriaPerformanceBank.pads()
        assertEquals(12, candidates.size)
        assertEquals(12, candidates.map { it.id }.distinct().size)
        assertEquals(12, candidates.map { it.patch.name }.distinct().size)
        assertEquals(OsteriaPresetCandidates.candidates(), candidates.take(8), "the earlier eight proposals must be retained exactly")
        assertEquals(OsteriaVoice.entries.toSet(), candidates.map { it.patch.voice }.toSet())
        assertTrue(candidates.groupBy { it.patch.voice }.values.all { it.size == 2 })
        assertEquals(16, pads.size)
        assertEquals((1..16).toList(), pads.map { it.slotNumber })
        assertEquals(16, pads.map { it.id }.distinct().size)
        assertEquals(listOf(
            "anchor-bass" to 36, "anchor-bass" to 43,
            "roaming-bass" to 36, "roaming-bass" to 43,
            "rounded-pluck" to 48, "bright-pluck" to 48,
            "still-reed" to 48, "remembered-reed" to 48,
            "warm-bow" to 48, "rosin-bow" to 48,
            "anchored-note" to 48, "lingering-color" to 48,
            "warm-swell" to 48, "warm-swell" to 55,
            "memory-drift" to 48, "memory-drift" to 55,
        ), pads.map { it.candidate.id to it.midi })
        assertEquals(listOf(1 to 41, 42 to 84, 85 to 127), OsteriaPerformanceBank.touches.map { it.velocityStart to it.velocityEnd })
        assertEquals(listOf(.25f, .60f, 1f), OsteriaPerformanceBank.touches.map { it.velocity })
        for (candidate in candidates) {
            val base = candidate.patch
            assertEquals(48, base.midi)
            assertEquals(.8f, base.velocity)
            assertEquals(173, base.config.seed)
            assertEquals(Osteria.defaults(base.voice).keys, base.macros.keys)
            assertTrue(candidate.description.isNotBlank())
            val savedName = OsteriaSixVoiceCandidates.userPresetName(candidate)
            assertTrue(Presets.forVoice(base.engine, base.voiceName).none {
                it.name.trim().replace(Regex("\\s+"), " ").uppercase(Locale.ROOT) == savedName
            }, "$savedName collides with a factory preset and cannot be edited in YOURS")
            val roots = when (base.voice) {
                OsteriaVoice.BASS -> listOf(36, 43)
                OsteriaVoice.PAD -> listOf(48, 55)
                else -> listOf(48)
            }
            val definitions = pads.filter { it.candidate == candidate }
            assertEquals(roots, definitions.map { it.midi })
            for (pad in definitions) for (touch in OsteriaPerformanceBank.touches) {
                val patch = pad.patch(touch)
                assertEquals(candidate.patch.macros, patch.macros)
                assertEquals(candidate.patch.config, patch.config)
                assertEquals(touch.velocity, patch.velocity)
                assertEquals(pad.midi, patch.midi)
                assertEquals(pad.displayName, patch.name)
                assertEquals(2, patch.config.model)
                assertTrue(abs(patch.config.seconds - patch.config.releaseSeconds - 5.2) < 1e-9)
                assertEquals(0f, patch.macros.getValue("ROOM"))
                assertEquals(0f, patch.macros.getValue("HOLD"))
                val recipe = PadRecipe(patch = patch)
                assertEquals(recipe, PadRecipe.fromJsonText(recipe.toJsonText()))
            }
        }
        for (candidate in candidates.filter { it.patch.voice == OsteriaVoice.QUARTET }) {
            val original = OsteriaPresets.all().single { it.name == candidate.patch.name }
            assertEquals(original, candidate.patch, "Note audition recipes must preserve the original source")
        }
        assertEquals("ANCHOR NOTE", OsteriaSixVoiceCandidates.userPresetName(candidates.single { it.id == "anchored-note" }))
        assertEquals("REMEMBER REED", OsteriaSixVoiceCandidates.userPresetName(candidates.single { it.id == "remembered-reed" }))
        assertEquals("LINGER COLOR", OsteriaSixVoiceCandidates.userPresetName(candidates.single { it.id == "lingering-color" }))
        assertEquals(OsteriaVoice.entries.toSet(), OsteriaPresets.all().map { it.voice }.toSet())
        assertEquals(OsteriaSixVoiceCandidates.all(), OsteriaPresets.selected())
    }

    @Test
    fun `one real pad per family preserves three prepared velocity zones and both MPC formats`() {
        val temp = Files.createTempDirectory("osteria-six-voice-bank").toFile()
        try {
            val slots = listOf(1, 5, 7, 9, 11, 13)
            val specs = OsteriaPerformanceBank.pads().filter { it.slotNumber in slots }
            val rendered = OsteriaPerformanceBank.render(specs)
            val exported = OsteriaPerformanceBankGenerator.export(temp, rendered)
            val pins = checkNotNull(javaClass.getResourceAsStream("/osteria-accepted-bank.tsv"))
                .bufferedReader().use { it.readLines() }.filterNot { it.startsWith("#") || it.isBlank() }
                .associate { line -> val fields = line.split('\t'); fields[0] to fields.drop(1) }
            val dataDir = exported.result.samples.first().parentFile
            val kit = KitStore.load(dataDir)
            assertEquals(slots, kit.pads.map { it.slot })
            assertEquals(18, exported.result.samples.size)
            assertEquals(6, exported.manifest.entries.getValue("voiceCount").int())
            assertEquals(12, exported.manifest.entries.getValue("presetCount").int())
            assertTrue(kit.pads.all { it.oneShot && it.muteGroup == 0 && it.level == 1f })
            for (program in listOf(exported.result.program, File(dataDir, "${kit.name}.xpm"))) {
                val reading = MpcXRay.read(program)
                assertEquals(null, reading.unreadable)
                val sounding = reading.programs.single().pads.filter { it.hasSample }
                assertEquals(6, sounding.size)
                assertTrue(sounding.all { it.level == 1f && it.muteGroup == 0 })
                for (pad in sounding) {
                    val layers = pad.layers.filter { it.sampleName != null }.sortedBy { it.velocityStart }
                    assertEquals(listOf(1 to 41, 42 to 84, 85 to 127), layers.map { it.velocityStart to it.velocityEnd })
                    assertTrue(layers.all { File(dataDir, "${it.sampleName!!.removeSuffix(".wav")}.wav").isFile })
                }
            }
            for (entry in rendered) {
                val pad = kit.pads.single { it.slot == entry.pad.slotNumber }
                assertEquals(3, pad.velocityLayers.size)
                val stem = "${entry.pad.slot}_${entry.pad.displayName}".replace(' ', '_')
                assertEquals("$stem.wav", pad.sampleFile)
                assertEquals(listOf("${stem}_S.wav", "${stem}_M.wav", "$stem.wav"),
                    pad.velocityLayers.map { it.sampleFile }, "imported pad labels must retain instrument and root")
                val mainRecipe = PadRecipe.fromJsonValue(assertNotNull(pad.recipe))
                assertEquals(entry.pad.patch(OsteriaPerformanceBank.touches.last()), mainRecipe.patch)
                for ((index, touch) in OsteriaPerformanceBank.touches.withIndex()) {
                    val file = File(dataDir, pad.velocityLayers[index].sampleFile)
                    val info = WavInfo.read(file)
                    assertEquals(44100, info.sampleRate)
                    assertEquals(2, info.channels)
                    assertEquals(24, info.bitsPerSample)
                    assertEquals(entry.samples[index].frameCount.toLong(), info.frameCount)
                    val pcm = WavReader.read(file)
                    // Prepared soft/medium WAVs regain their genuine production
                    // output when the player applies the nominal touch once.
                    val expected = entry.productionSamples[index].samples
                    assertEquals(expected.size, pcm.samples.size)
                    assertTrue(entry.samples[index].samples.all { it.isFinite() && abs(it) <= .990001f },
                        "${entry.pad.slot} ${touch.id} lacks prepared-sample headroom")
                    assertTrue(expected.indices.all { abs(pcm.samples[it] * touch.velocity - expected[it]) < 2e-7f },
                        "${entry.pad.slot} ${touch.id} applies velocity twice or changes production PCM")
                    val recipeFile = File(temp, "recipes/${entry.pad.clipId(touch)}.json")
                    val accepted = pins.getValue(entry.pad.clipId(touch))
                    assertEquals(accepted[0], sha256(file), "accepted bank WAV ${entry.pad.clipId(touch)}")
                    assertEquals(accepted[1], sha256(recipeFile), "accepted saved recipe ${entry.pad.clipId(touch)}")
                    val obj = Json.parse(recipeFile.readText()).obj()
                    val recipe = PadRecipe.fromJsonText(recipeFile.readText())
                    val patch = assertIs<OsteriaPatch>(recipe.patch)
                    assertEquals(entry.pad.patch(touch), patch)
                    assertEquals((1f / touch.velocity).toDouble(), obj.getValue("bankPreparation").obj().getValue("samplePreparationGain").num())
                    assertEquals(touch.velocity.toDouble(), obj.getValue("bankPreparation").obj().getValue("previewVelocityGain").num())
                    assertContentEquals(recipeFile.readBytes(), File(dataDir, "recipes/${recipeFile.name}").readBytes())
                }
            }
            // Replaying an actual supplied soft recipe catches replacing true
            // excitation rendering with a scaled hard-zone sample in the builder.
            val first = rendered.first()
            val savedSoft = PadRecipe.fromJsonText(File(temp, "recipes/${first.pad.clipId(OsteriaPerformanceBank.touches.first())}.json").readText())
            assertContentEquals(first.productionSamples.first().samples, savedSoft.render().samples)
            val presets = Json.parse(File(temp, "presets.json").readText()).obj()
            assertEquals(1, presets.getValue("version").int())
            val entries = presets.getValue("presets").arr()
            assertEquals(12, entries.size)
            for ((index, saved) in entries.withIndex()) {
                val savedPatch = Patches.fromJsonValue(saved.obj().getValue("patch")) as OsteriaPatch
                val candidate = OsteriaSixVoiceCandidates.candidates()[index]
                val source = candidate.patch
                assertEquals(source.copy(name = savedPatch.name), savedPatch)
                assertEquals(OsteriaSixVoiceCandidates.userPresetName(candidate), savedPatch.name)
                assertTrue(savedPatch.name == savedPatch.name.uppercase() && savedPatch.name.length <= 14)
                assertEquals(1_793_923_200_000L, saved.obj().getValue("savedAt").long())
            }
            ZipFile(exported.archive).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()
                assertEquals(18, names.count { it.endsWith(".wav") }, "both programs must share one WAV set")
                assertEquals(18, names.count { "/recipes/" in it && it.endsWith(".json") })
                assertEquals(1, names.count { it.endsWith(".xtd") })
                assertEquals(1, names.count { it.endsWith(".xpm") })
                assertEquals(1, names.count { it.endsWith("/kit.json") })
                assertEquals(1, names.count { it.endsWith("/presets.json") })
                assertTrue(names.any { it.endsWith("/osteria-bank-recipes.json") })
                assertTrue(names.none { it.startsWith("/") || "../" in it })
            }
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun `bowed proposals retain different normalized body throughout the held note`() {
        val pair = OsteriaSixVoiceCandidates.candidates().filter { it.patch.voice == OsteriaVoice.BOWED }
        assertEquals(2, pair.size)
        val renders = pair.map { candidate ->
            val patch = candidate.patch
            Osteria.probe(patch.voice, patch.macros, patch.midi, patch.velocity, patch.config).samples
        }
        // Both windows follow the soft catch and precede the common 5.2-second
        // release. Normalize each frame so gain and articulation cannot pass.
        for ((start, end) in listOf(.65 to 1.45, 4.2 to 4.9)) {
            val spectra = renders.map { normalizedSpectrum(it, start, end) }
            val distance = spectra[0].indices.sumOf { abs(spectra[0][it] - spectra[1][it]) } / 2.0
            assertTrue(distance > .055, "Bowed pair converges at $start seconds: $distance")
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(Locale.ROOT, it) }

    private fun normalizedSpectrum(stereo: FloatArray, startSeconds: Double, endSeconds: Double): DoubleArray {
        val size = 8192
        val root = Keys.midiHz(48).toDouble()
        val result = DoubleArray(16)
        val start = (startSeconds * Dsp.RATE).toInt()
        val end = minOf(stereo.size / 2, (endSeconds * Dsp.RATE).toInt())
        var count = 0
        for (frame in start..end - size step size / 2) {
            val real = FloatArray(size) { offset ->
                ((stereo[(frame + offset) * 2] + stereo[(frame + offset) * 2 + 1]) * .5 *
                    (.5 - .5 * cos(2 * PI * offset / (size - 1)))).toFloat()
            }
            val imaginary = FloatArray(size)
            Fft.forward(real, imaginary)
            val bands = DoubleArray(result.size)
            for (bin in 1..size / 2) {
                val harmonic = (bin * Dsp.RATE / (size * root) + .5).toInt()
                if (harmonic in 1..bands.size) {
                    bands[harmonic - 1] += real[bin].toDouble() * real[bin] + imaginary[bin].toDouble() * imaginary[bin]
                }
            }
            val total = bands.sum()
            if (total > 1e-24) {
                for (i in result.indices) result[i] += bands[i] / total
                count++
            }
        }
        check(count > 0)
        return DoubleArray(result.size) { result[it] / count }
    }
}
