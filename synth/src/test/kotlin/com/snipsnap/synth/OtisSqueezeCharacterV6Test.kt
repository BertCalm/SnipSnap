package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OtisSqueezeCharacterV6Test {
    private val rate = Dsp.RATE * Dsp.OVERSAMPLE
    private val settings = OtisAirSettings()
    private val note = OtisPrototypeNote(60, gateSeconds = 3.6)

    private fun flat(value: Double, clock: Int = rate) = OtisPhrasePressureTrace(
        doubleArrayOf(value, value), DoubleArray(2), doubleArrayOf(value, value), clock * 30, clock)
    private fun raw(model: Int, notes: List<OtisPrototypeNote>, s: OtisAirSettings, p: Double, clock: Int = rate): FloatArray =
        if (model == 5) OtisSqueezeCharacterV5.synthesize(notes, s, 20261014, clock, flat(p, clock))
        else OtisSqueezeCharacterV6.synthesize(notes, s, 20261014, clock, flat(p, clock))
    private fun finished(x: FloatArray) = OtisPrototypeV4.condition(x).samples

    @Test
    fun `all sixteen published model five source variants replay their exact PCM`() {
        val fixtures = Json.parse(javaClass.getResource("/otis-model5-replay.json")!!.readText()).obj().getValue("fixtures").arr()
        assertEquals(16, fixtures.size)
        for (fixture in fixtures.map { it.obj() }) {
            val request = OtisPrototypeRecipe.fromJsonValue(fixture.getValue("recipe"))
            val source = request.render()
            val gain = fixture.getValue("rawPcmGain").num().toFloat()
            val export = Snip(FloatArray(source.samples.size) { source.samples[it] * gain }, 1, Dsp.RATE)
            val bytes = ByteArrayOutputStream().also { WavWriter.write(it, export) }.toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(fixture.getValue("wavSha256").str(), hash, fixture.getValue("id").str())
        }
    }

    @Test
    fun `other families and unloaded Squeeze stay sample identical to model five`() {
        for (voice in listOf(OtisPrototypeVoice.ORGAN, OtisPrototypeVoice.STEAM, OtisPrototypeVoice.HARP)) {
            val request = OtisPrototypeRequest(voice, listOf(60, 63, 67, 70).map { OtisPrototypeNote(it, gateSeconds = .5) }, model = 6)
            assertContentEquals(request.copy(model = 5).render().samples, request.render().samples, voice.name)
        }
        val request = OtisPrototypeRequest(OtisPrototypeVoice.SQUEEZE, listOf(note.copy(gateSeconds = .7)), model = 6)
        assertContentEquals(request.copy(model = 5).render().samples, request.render().samples, "single reference key changed")
        val chord = request.copy(notes = listOf(60, 63, 67, 70).map { note.copy(midi = it, gateSeconds = .5) }, sharedPressure = 0f)
        assertContentEquals(chord.copy(model = 5).render().samples, chord.render().samples, "independent bypass changed")
    }

    @Test
    fun `a future joining key leaves the preceding note exactly intact`() {
        val held = OtisPrototypeNote(60, gateSeconds = 1.8)
        val solo = OtisPrototypeRequest(OtisPrototypeVoice.SQUEEZE, listOf(held), model = 6).render()
        val phrase = OtisPrototypeRequest(OtisPrototypeVoice.SQUEEZE,
            listOf(held, OtisPrototypeNote(63, .45, .65)), model = 6).render()
        assertContentEquals(solo.samples.copyOf((.44 * Dsp.RATE).toInt()), phrase.samples.copyOf((.44 * Dsp.RATE).toInt()))
    }

    @Test
    fun `a loaded reed retains upper body while Shared still darkens and softens it`() {
        val plain = settings.copy(musette = 0f, bellowsPulse = 0f)
        val before = finished(raw(5, listOf(note), plain, .4))
        val loaded = finished(raw(6, listOf(note), plain, .4))
        val full = finished(raw(6, listOf(note), plain, 1.0))
        val root = hz(60)
        fun upper(x: FloatArray) = listOf(5, 7, 9, 11).sumOf { power(x, root * it, .6, 3.2) } / power(x, root, .6, 3.2)
        val oldShare = upper(before)
        val share = upper(loaded)
        val baseline = upper(full)
        assertTrue(share > oldShare * 100, "chord load still strips upper reeds: $oldShare -> $share")
        assertTrue(share / baseline in .10.. .30, "Shared no longer darkens the source, or still closes it too far: $share / $baseline")
        val rootRatio = sqrt(power(loaded, root, .6, 3.2) / power(full, root, .6, 3.2))
        assertTrue(abs(rootRatio - .4) < .005, "the pressure envelope was bypassed: $rootRatio")
        println("SQUEEZE6 loaded upper/root: model5=$oldShare model6=$share unloaded=$baseline rootAmplitudeRatio=$rootRatio")
    }

    @Test
    fun `common motion survives a loaded chord with unequal note velocities`() {
        // Disable detuning to isolate one player's tremolo/FM from accidental inter-rank beats.
        val plain = settings.copy(musette = 0f)
        val notes = listOf(60, 63, 67, 70).mapIndexed { i, midi -> note.copy(midi = midi, velocity = .62f + .06f * i) }
        val old = finished(raw(5, notes, plain, .4))
        val next = finished(raw(6, notes, plain, .4))
        val drawHz = 2.5 + 1.5 * OtisAirCharacterV5.REFERENCE_VELOCITY.toDouble() * (.5 + .5 * plain.pressure)
        val records = notes.map { n ->
            val root = hz(n.midi)
            fun side(x: FloatArray): List<Double> = listOf(power(x, root - drawHz, .6, 3.2), power(x, root + drawHz, .6, 3.2))
            val a = side(old)
            val b = side(next)
            val oldShare = a.sum() / power(old, root, .6, 3.2)
            val newShare = b.sum() / power(next, root, .6, 3.2)
            val asymmetry = abs(b[1] - b[0]) / b.sum()
            assertTrue(newShare > oldShare * 3 && newShare > .01,
                "MIDI${n.midi} lost the common chord motion: $oldShare -> $newShare")
            // Coherent FM plus AM produces unequal sidebands; a gain-only pulse is symmetric.
            assertTrue(asymmetry > .30, "MIDI${n.midi} has tremolo but no measurable pitch motion: $b")
            "${n.midi}:$oldShare->$newShare asymmetry=$asymmetry"
        }
        println("SQUEEZE6 chord motion at $drawHz Hz: ${records.joinToString()}")
    }

    @Test
    fun `motion control remains optional and pulse off keeps a centered pitched chord`() {
        val plain = settings.copy(musette = 0f, bellowsPulse = 0f)
        for (midi in listOf(48, 60, 72)) {
            val x = finished(raw(6, listOf(note.copy(midi = midi)), plain, .4))
            val root = hz(midi)
            val center = power(x, root, .6, 3.2)
            val cents = (-8..8).maxBy { power(x, root * 2.0.pow(it / 1200.0), .6, 3.2) }
            assertTrue(abs(cents) <= 1, "PULSE=0 moved MIDI$midi by $cents cents")
            val side = power(x, root + 3.46, .6, 3.2) + power(x, root - 3.46, .6, 3.2)
            assertTrue(side / center < 1e-5, "PULSE=0 retained a compulsory wobble: ${side / center}")
        }
    }

    @Test
    fun `low clock high notes prune folded harmonics while all corners stay finite and repeatable`() {
        val notes = listOf(36, 60, 84).map { OtisPrototypeNote(it, .08, .4, 1f) }
        val extreme = settings.copy(musette = 1f, bellowsPulse = 1f, pressure = 1f, release = 1f)
        val first = raw(6, notes, extreme, .4)
        assertContentEquals(first, raw(6, notes, extreme, .4))
        assertTrue(first.all { it.isFinite() && abs(it) < 6f })
        assertTrue(first.take((.08 * rate).toInt()).all { it == 0f })
        assertTrue(first.takeLast(256).all { abs(it) < 1e-6 })
        assertTrue(raw(6, notes.map { it.copy(velocity = 0f) }, extreme, .4).all { it == 0f })
        assertTrue(raw(6, notes, extreme, 0.0).all { it == 0f })
        val low = raw(6, listOf(note.copy(midi = 84)), extreme, .4, 8000)
        assertTrue(low.all { it.isFinite() && abs(it) < 2f })
        val alias = abs(8000 - hz(84) * 4)
        val aliasPower = (-20..20 step 4).sumOf { power(low, alias + it, 1.0, 3.0, 8000) }
        val rootPower = (-30..30 step 3).sumOf { power(low, hz(84) + it, 1.0, 3.0, 8000) }
        assertTrue(aliasPower / rootPower < 1e-5, "a pruned fourth rank folded into the low-rate band: ${aliasPower / rootPower}")
        assertFailsWith<IllegalArgumentException> {
            OtisSqueezeCharacterV6.synthesize(notes, extreme, 1, rate, flat(Double.NaN))
        }
        println("SQUEEZE6 selected low-rate folded-band/root power=${aliasPower / rootPower}")
    }

    private fun hz(midi: Int) = 440 * 2.0.pow((midi - 69) / 12.0)
    private fun power(x: FloatArray, frequency: Double, from: Double, to: Double, clock: Int = Dsp.RATE): Double {
        val start = (from * clock).toInt()
        val count = ((to - from) * clock).toInt()
        var re = 0.0
        var im = 0.0
        for (i in 0 until count) {
            val v = x[start + i] * (.5 - .5 * cos(2 * PI * i / (count - 1)))
            val phase = 2 * PI * frequency * i / clock
            re += v * cos(phase)
            im += v * sin(phase)
        }
        return re * re + im * im
    }
}
