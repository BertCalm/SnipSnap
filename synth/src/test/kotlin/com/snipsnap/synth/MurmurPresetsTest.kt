package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Factory curation evidence. Accepted sounds are fixtures; numerical variety does not approve new sounds. */
class MurmurPresetsTest {
    @Test
    fun `thirty six original named dry recipes have no renamed duplicates`() {
        assertEquals(36, bank.size)
        assertEquals(36, bank.map { it.name }.toSet().size)
        assertEquals(36, bank.map { it.copy(name = "Identity") }.toSet().size,
            "a different name is not a different dry recipe")
        assertEquals(accepted.map { it.name }, bank.take(12).map { it.name }, "retain the accepted listening order")
        for (preset in bank) {
            assertTrue(preset.name.matches(Regex("[A-Z][A-Z0-9 ]{0,13}")), preset.name)
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(preset.name), preset.name)
            assertEquals(Murmur.macrosFor(preset.voice).map { it.name }.toSet(), preset.macros.keys, preset.name)
            assertTrue(preset.macros.values.all { it.isFinite() && it in 0f..1f }, preset.name)
            assertEquals(2, preset.config.model, preset.name)
            assertTrue(preset.midi in Murmur.MIN_MIDI..Murmur.MAX_MIDI, preset.name)
            assertTrue(preset.velocity > 0f && preset.velocity <= 1f, preset.name)
            assertNull(Presets.landingFor(MurmurPatch.ENGINE, preset.voice.name, preset.macros),
                "${preset.name} must be a useful dry sound")
        }
    }

    @Test
    fun `every dry pad recipe retains its complete authored model root and excitation`() {
        for (source in bank) {
            val recipe = PadRecipe(source)
            val saved = PadRecipe.fromJsonText(recipe.toJsonText())
            assertEquals(recipe, saved, source.name)
            val restored = assertIs<MurmurPatch>(saved.patch)
            assertEquals(source, restored, source.name)
            assertEquals(source.config, restored.config, source.name)
            assertEquals(source.midi, restored.midi, source.name)
            assertEquals(source.velocity, restored.velocity, source.name)
            assertNull(saved.fx, source.name)
            assertFalse(saved.alias, source.name)
            assertEquals(source.copy(name = "Edited", macros = source.macros + ("EDGE" to .31f)),
                Patches.edited(source, "Edited", source.macros + ("EDGE" to .31f)), source.name)
        }
    }

    @Test
    fun `the twelve accepted definitions and native PCM remain exactly the approved publication`() {
        // Independently retained from bb2b9c9a's owner-approved publication, including the soft CHOIR.
        // Canonical JSON sorts object keys and records Double bits, so whitespace is not identity.
        for (fixture in accepted) {
            val source = bank.single { it.name == fixture.name }
            assertEquals(fixture.recipeHash, sha256(canonical(source.toJsonValue()).toByteArray()),
                "${source.name}'s accepted saved definition changed")
            val encoded = ByteArrayOutputStream().also {
                WavWriter.write(it, Snip(native.getValue(source.name), 1, Dsp.RATE), WavWriter.BitDepth.PCM_24)
            }.toByteArray()
            assertEquals(fixture.rawHash, sha256(encoded), "${source.name}'s accepted native audio changed")
        }
    }

    @Test
    fun `all factory sounds have bounded audible native sources pitched roots and non-drum heads`() {
        val failures = mutableListOf<String>()
        for (preset in bank) {
            val samples = native.getValue(preset.name)
            val peak = samples.maxOf { abs(it) }
            val rms = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
            val dc = samples.sumOf { it.toDouble() } / samples.size
            val head = Snip(samples.copyOfRange(0, minOf(samples.size, Dsp.RATE)), 1, Dsp.RATE)
            val classification = Classifier.classify(head).drumClass
            val pitch = Pitch.detect(Snip(samples, 1, Dsp.RATE), fromSec = .10f, windowSec = .32f)
            val cents = pitch?.let { 1200 * ln(it.hz / Keys.midiHz(preset.midi).toDouble()) / ln(2.0) }
            println("MURMUR BANK ${preset.name}: MIDI ${preset.midi} velocity ${preset.velocity}, peak $peak RMS $rms DC $dc, head $classification pitch $cents cents confidence ${pitch?.confidence}")
            if (samples.isEmpty() || samples.size > 8 * Dsp.RATE || samples.any { !it.isFinite() }) failures += "${preset.name}: invalid native buffer"
            if (peak >= 1f || rms <= .0002 || abs(dc) >= .003) failures += "${preset.name}: native level/DC $peak/$rms/$dc"
            if (classification in drums) failures += "${preset.name}: drum head $classification"
            if (cents == null || abs(cents) >= 20 || (pitch?.confidence ?: 0f) < .35f) failures += "${preset.name}: weak/wrong root $cents (${pitch?.confidence})"
            if (!Murmur.isLoop(preset.macros.getValue("HOLD")) && abs(samples.last()) >= 1e-5f) failures += "${preset.name}: unfinished finite tail"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `new saved pad recipes reproduce a representative sound from every role`() {
        for (voice in MurmurVoice.entries) {
            val source = bank.last { it.voice == voice && it.name !in acceptedNames }
            val saved = PadRecipe.fromJsonText(PadRecipe(source).toJsonText())
            val restored = assertIs<MurmurPatch>(saved.patch)
            assertContentEquals(native.getValue(source.name),
                Murmur.renderRaw(restored.voice, restored.macros, restored.midi, restored.velocity, restored.config),
                "${source.name}'s stored root/config/velocity does not reproduce native audio")
            assertContentEquals(source.render().samples, saved.render().samples, "${source.name}'s dry pad replay differs")
        }
    }

    private data class Analysis(val average: DoubleArray, val frames: List<DoubleArray>, val energy: DoubleArray,
        val frameRms: List<Double>, val t50: Double, val t90: Double)

    private fun analyze(samples: FloatArray): Analysis {
        val times = listOf(.10, .22, .38, .55, .75, 1.05)
        val frameMass = mutableListOf<DoubleArray>()
        val frameRms = mutableListOf<Double>()
        val average = DoubleArray(edges.size - 1)
        for (time in times) {
            val start = (time * Dsp.RATE).roundToInt()
            val real = FloatArray(8192) { i -> if (start + i < samples.size)
                (samples[start + i] * (.5 - .5 * cos(2 * PI * i / 8192))).toFloat() else 0f }
            frameRms += sqrt(real.sumOf { it.toDouble() * it } / real.size)
            val imaginary = FloatArray(real.size)
            Fft.forward(real, imaginary)
            val mass = DoubleArray(edges.size - 1)
            for (bin in 1 until real.size / 2) {
                val band = edges.indexOfFirst { it > bin.toDouble() * Dsp.RATE / real.size } - 1
                if (band >= 0) mass[band] += real[bin].toDouble() * real[bin] + imaginary[bin].toDouble() * imaginary[bin]
            }
            if (time >= .22) for (band in mass.indices) average[band] += mass[band]
            val sum = mass.sum().coerceAtLeast(1e-30)
            frameMass += DoubleArray(mass.size) { mass[it] / sum }
        }
        val totalMass = average.sum().coerceAtLeast(1e-30)
        val boundaries = listOf(0.0, .08, .25, .60, 1.20, 2.50, 4.0, 8.0)
            .map { (it * Dsp.RATE).roundToInt().coerceAtMost(samples.size) }
        val energy = DoubleArray(boundaries.size - 1) { band ->
            (boundaries[band] until boundaries[band + 1]).sumOf { samples[it].toDouble() * samples[it] }
        }
        val totalEnergy = energy.sum().coerceAtLeast(1e-30)
        var cumulative = 0.0
        var t50 = samples.size / Dsp.RATE.toDouble()
        var t90 = t50
        for (i in samples.indices) {
            cumulative += samples[i].toDouble() * samples[i]
            if (cumulative < totalEnergy * .5) t50 = i / Dsp.RATE.toDouble()
            if (cumulative < totalEnergy * .9) t90 = i / Dsp.RATE.toDouble()
        }
        return Analysis(DoubleArray(average.size) { average[it] / totalMass }, frameMass,
            DoubleArray(energy.size) { energy[it] / totalEnergy }, frameRms, t50, t90)
    }

    private fun distance(first: DoubleArray, second: DoubleArray) = first.indices.sumOf { abs(first[it] - second[it]) }

    @Test
    fun `new curation has audible dimensions beyond a different name pitch or playing level`() {
        assertTrue(bank.map { it.name }.containsAll(contrastedFamilies.flatten()), "a named contrast family is missing")
        val profiles = common.mapValues { (_, samples) -> analyze(samples) }
        val failures = mutableListOf<String>()
        for (voice in MurmurVoice.entries) {
            val sounds = bank.filter { it.voice == voice }
            for (i in sounds.indices) for (j in i + 1 until sounds.size) {
                val first = sounds[i]; val second = sounds[j]
                val a = profiles.getValue(first.name); val b = profiles.getValue(second.name)
                val spectral = distance(a.average, b.average)
                val active = a.frames.indices.filter { a.frameRms[it] > a.frameRms.max() * .03 && b.frameRms[it] > b.frameRms.max() * .03 }
                val contour = if (active.isEmpty()) 0.0 else active.map { distance(a.frames[it], b.frames[it]) }.average()
                val articulation = distance(a.energy, b.energy)
                println("MURMUR CURATION $voice ${first.name}/${second.name}: C4 spectral $spectral contour $contour articulation $articulation, t50/t90 ${a.t50}/${a.t90} vs ${b.t50}/${b.t90}")
                val newAgainstAccepted = (first.name in acceptedNames) != (second.name in acceptedNames)
                val expectedContrast = setOf(first.name, second.name) in contrastedFamilies
                // Accepted siblings are not re-approved by an arbitrary all-pairs threshold.
                // New sounds must differ from both accepted references; deliberately opposed
                // new families also need real spectrum, travel or envelope contrast at C4/v1.
                if ((newAgainstAccepted || expectedContrast) && spectral <= .04 && contour <= .06 && articulation <= .10) {
                    failures += "${first.name}/${second.name} are near duplicates at a common root and excitation"
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun canonical(value: JsonValue): String = when (value) {
        is JsonValue.Obj -> value.entries.toSortedMap().entries.joinToString(",", "{", "}") { (key, item) -> Json.write(JsonValue.Str(key)) + ":" + canonical(item) }
        is JsonValue.Arr -> value.items.joinToString(",", "[", "]") { canonical(it) }
        is JsonValue.Num -> "#" + java.lang.Long.toUnsignedString(java.lang.Double.doubleToLongBits(value.value), 16)
        else -> Json.write(value)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private val bank by lazy { MurmurPresets.all() }
        private val drums = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
            DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
        private val edges = doubleArrayOf(0.0, 150.0, 250.0, 400.0, 650.0, 1000.0, 1500.0,
            2200.0, 3500.0, 6000.0, 10000.0, 16000.0, 22051.0)
        private val native by lazy { bank.associate { p -> p.name to Murmur.renderRaw(p.voice, p.macros, p.midi, p.velocity, p.config) } }
        private val common by lazy { bank.associate { p -> p.name to if (p.midi == 60 && p.velocity == 1f) native.getValue(p.name)
            else Murmur.renderRaw(p.voice, p.macros, 60, 1f, p.config) } }
        private val contrastedFamilies = listOf(
            "STILL CURRENT" to "TALKBACK", "CHEST VOICE" to "WIRE THROAT", "UNDERCURRENT" to "DRY SYLLABLE",
            "STILL TINE" to "FELT REPLY", "BONE CLICK" to "OPEN STRING", "REED LINE" to "SOFT PALATE",
            "VOWEL RUN" to "LONG WORD", "HOLLOW SIGNAL" to "BREATH MARK", "CLOSE HARMONY" to "OPEN ENSEMBLE",
            "VOWEL HALO" to "THIN UNISON", "BELL CORE" to "NEEDLE RING", "TIN LANGUAGE" to "SLOW ALLOY",
        ).map { setOf(it.first, it.second) }.toSet()
        private data class Fixture(val name: String, val recipeHash: String, val rawHash: String)
        private val accepted = listOf(
            Fixture("CLOSED CIRCUIT", "84c4fb3979b1919852c37237155beb20657637fc78128b7e96059108758ccda7", "7a5c64fdf54badff2e359b802d2d395f39c943205a0428871a97e39c964baebd"),
            Fixture("ROUND HUM", "1f319e263f8d62d697c52066a3900128a7abd65fd3d4779fe98a59bbb29472d0", "f3b72d9508753e8293816210046873cee9facc1d24977a8da87ad9a6dcea3b02"),
            Fixture("TINE WHISPER", "479f0351a91e02c448651a51b6365bf4e183f81922b8d48711dba365441434c1", "b5b10a96343bedde9a218c436ccd1d27363311001aea433e090db65f80d91278"),
            Fixture("DUST VOWEL", "bb3116d7c0715c2b1fced6a785c56cc05ddde8486b6ba5391330e1cd1ddfabfc", "da8b7c1f440476f5c843c7beb93ab2074071fbebb3c614e51a4664054153d729"),
            Fixture("SHORT ANSWER", "495a71730eef1638a37363e9d77986989115810b6c23f8dcb4eeded61943dfe3", "d5af121b3fe29659ad680bcadfbc6ef2f482fde796b582ce6445f4e9e5f70048"),
            Fixture("POCKET VOICE", "99e575d0a2c360d0911366d48b119395c242c9418a324b95ba8f3fc8b4bc92b8", "dd0d509349bf693f0b11e9c9183d814d290fe100ac3a419bd9ea6cd2e121f761"),
            Fixture("OPEN THROAT", "33010d25ce46e29f4ecfbc4e7ec21260196903e01845aa0c7c37e34b30f9927e", "8f4ddd4dbef3570ee24889fb9e8afeb31a2f523edf83ca7d4429c22cfef8301a"),
            Fixture("NARROW CALL", "e1c47ef8d1b7186d744c551fcebdef28584e00f07b3f41ec576135fe5d2d5f88", "9ef559e5166d42cffb9929d1118cdecc1eb7e53b801a7a5e79180f3222adb677"),
            Fixture("SMALL CHOIR", "25f5a174b37d4cc6de5333507fb487598cdffcca6806ed7dbcebb07bab69dc18", "3f6b689a9ccf65c755f4b286564bff907c0732ac785fb70ae6cfa10b52ad9bfe"),
            Fixture("HELD HUM", "8696c686df01bb3ac38b3a5d0884cadb4395f33f9bf507766fa161485e400ebc", "3c895a707bb916f4801787f44bf1aa1c97445131dfd1834c860f4be0c6aee58b"),
            Fixture("COPPER HUM", "f1748105598229af36690091ddc025021bd3a7c4b828f8c3cd858bb28ff0cc41", "c4cdd832d6fa353b014848e1c6db4744391fc53c82104ba4f0ceaecaa5f24725"),
            Fixture("GLASS MOUTH", "28c65059c373e1eb246546d7452b8468ad6586a91347b96c0f483a95d389b23a", "6e6c87d0196cf2e4c82810915aa3753baf33a37bca624d8cc162d3c5ba15500b"),
        )
        private val acceptedNames = accepted.map { it.name }.toSet()
    }
}
