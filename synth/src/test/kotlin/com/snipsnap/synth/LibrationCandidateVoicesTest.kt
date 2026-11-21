package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The voices built for listening round libration-voices-1 and kept in it (20 Nov 2026, all four KEEP,
 * range and touch). Each reads a part of the solved trajectories the cloud discards: CHIME when a body
 * crosses its axis, STRUM when it leaves, ROTOR the frame's own rotation; HOLLOW puts odd-only partial
 * tables on the cloud's mapping. The cloud voices' renders are pinned below against the build before
 * this round, and the heard renders of the four in `libration/kept-voices-v1.sha256`.
 */
class LibrationCandidateVoicesTest {
    private val M = CandidateVoiceMeasures
    private val candidates = LibrationVoice.entries.filter { it !in Libration.CLOUD_VOICES }
    private val notes = listOf(36, 48, 60, 72, 84)
    private val short = Libration.Options(seconds = 2.0)

    private fun hz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
    private fun rms(x: FloatArray, from: Double, to: Double): Double {
        val a = (from * Dsp.RATE).toInt()
        val b = minOf((to * Dsp.RATE).toInt(), x.size)
        return sqrt((a until b).sumOf { x[it].toDouble() * x[it] } / (b - a))
    }
    private fun render(voice: LibrationVoice, macros: Map<String, Float> = emptyMap(), midi: Int = 60,
        velocity: Float = .8f, options: Libration.Options = short) =
        Libration.probe(voice, macros, midi, velocity, options).samples.samples

    @Test
    fun `the four kept voices are appended and offered with their heard preset, HOLLOW also held`() {
        assertEquals(listOf(LibrationVoice.CHIME, LibrationVoice.STRUM, LibrationVoice.ROTOR, LibrationVoice.HOLLOW), candidates)
        assertEquals(LibrationVoice.entries.take(3), Libration.CLOUD_VOICES)
        assertEquals(LibrationVoice.entries, Libration.KEPT_VOICES)
        // No new starter: the LIBRATION starter is still the cloud bank.
        assertTrue(LibrationKits.patches().all { it.voice in Libration.CLOUD_VOICES }, "a new voice is on the starter kit")
        for (voice in candidates) {
            // One factory preset: default controls at C3, the default touch and the six-second note, as heard.
            val preset = LibrationPatch(voice.name, voice, Libration.defaults(voice), midi = 48)
            assertEquals(listOf(preset), LibrationPresets.forVoice(voice))
            assertEquals(listOf<Patch>(preset), Presets.forVoice("LIBRATION", voice.name))
            assertTrue(preset in Presets.all())
            assertEquals(.8f, preset.velocity)
            assertEquals(6.0, preset.sourceSeconds)
            assertEquals(Libration.macrosFor(LibrationVoice.GATHER).map { it.name }, Libration.macrosFor(voice).map { it.name })
            val patch = LibrationPatch("Candidate", voice, Libration.defaults(voice) + ("WANDER" to .3f), midi = 55,
                velocity = .6f, sourceSeconds = .5)
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()), "$voice does not survive saving")
            assertContentEquals(patch.render().samples, LibrationRenderSession().render(patch).samples, "$voice preview differs")
            // HOLLOW has a held key since 21 Nov 2026 (LibrationCandidateHeldTest); ROTOR's awaits a verdict; CHIME and STRUM are finite.
            if (voice == LibrationVoice.HOLLOW) assertTrue(LibrationKeys.supports(patch))
            else {
                assertFalse(LibrationKeys.supports(patch))
                assertFailsWith<IllegalArgumentException> { patch.copy(macros = patch.macros + ("HOLD" to 1f),
                    heldModel = LibrationKeys.MODEL_VERSION) }
            }
        }
    }

    @Test
    fun `candidates render finite, bounded and the same twice across the range`() {
        val quick = Libration.Options(seconds = .8)
        for (voice in candidates) {
            for (midi in notes) for (velocity in listOf(.3f, .8f, 1f)) {
                val probe = Libration.probe(voice, midi = midi, velocity = velocity, options = quick)
                val x = probe.samples.samples
                assertTrue(x.all { it.isFinite() }, "$voice MIDI$midi v$velocity is not finite")
                assertTrue(x.maxOf { abs(it) } <= .941f, "$voice MIDI$midi v$velocity exceeds the engine's ceiling")
                assertTrue(probe.raw.samples.maxOf { abs(it) } < .95f, "$voice MIDI$midi v$velocity raw ${probe.diagnostics.rawPeak}")
                assertTrue(rms(x, 0.0, .8) > 1e-3, "$voice MIDI$midi v$velocity is silent")
                // No click at the start, no cut at the end.
                assertTrue(abs(x[0]) < 2e-3f, "$voice MIDI$midi v$velocity starts open: ${x[0]}")
                assertTrue(abs(x.last()) < 1e-3f, "$voice MIDI$midi v$velocity ends open: ${x.last()}")
                assertTrue(rms(x, x.size / 44100.0 - .05, x.size / 44100.0) < .02 * rms(x, 0.0, .8), "$voice MIDI$midi v$velocity ends abruptly")
            }
            val macros = mapOf("REGION" to .31f, "WANDER" to .42f, "NUDGE" to .7f, "SWARM" to .4f, "RESONANCE" to .25f)
            assertContentEquals(render(voice, macros, 50, .6f, quick), render(voice, macros, 50, .6f, quick), "$voice is not deterministic")
            assertTrue(render(voice, velocity = 0f, options = quick).all { it == 0f }, "$voice sounds at zero velocity")
            // Every family, a lone body or the full swarm, dry or ringing: still a bounded note.
            for (region in listOf(0f, .25f, .5f, .75f, 1f)) for (swarm in listOf(0f, 1f)) for (resonance in listOf(0f, 1f)) {
                val x = render(voice, mapOf("REGION" to region, "SWARM" to swarm, "RESONANCE" to resonance), 43, options = quick)
                assertTrue(x.all { it.isFinite() } && x.maxOf { abs(it) } <= .941f, "$voice corner $region/$swarm/$resonance")
                assertTrue(rms(x, 0.0, .5) > 1e-3, "$voice corner $region/$swarm/$resonance is silent")
            }
        }
    }

    @Test
    fun `candidates sound the played note`() {
        for (voice in candidates) for (midi in notes) {
            val x = render(voice, midi = midi)
            val root = M.rootCents(x, hz(midi), .15, .6)
            val share = M.rootShare(x, hz(midi), .15, .6)
            println("LIBRATION $voice MIDI$midi root %+.0f cents (share %.2f)".format(root, share))
            assertTrue(abs(root) <= 15.0, "$voice MIDI$midi root is $root cents off")
            assertTrue(share > .3, "$voice MIDI$midi has a weak root: $share")
        }
    }

    @Test
    fun `every candidate is spectrally distinct from every other voice`() {
        for (midi in listOf(48, 60)) {
            val shapes = LibrationVoice.entries.associateWith { M.bands(render(it, midi = midi)) }
            for (a in candidates) for (b in LibrationVoice.entries) if (a != b) {
                val d = M.distance(shapes.getValue(a), shapes.getValue(b))
                println("LIBRATION third-octave distance MIDI$midi $a/$b = %.1f dB".format(d))
                assertTrue(d >= M.DISTINCT_DB, "$a and $b are only $d dB apart at MIDI $midi")
            }
        }
    }

    @Test
    fun `soft, medium and hard differ in tone and not only in level`() {
        for (voice in candidates) {
            val shapes = listOf(.35f, .7f, 1f).map { M.bands(render(voice, velocity = it)) }
            val steps = listOf(M.distance(shapes[0], shapes[1]), M.distance(shapes[1], shapes[2]))
            println("LIBRATION $voice level-matched touch steps: %.1f dB, %.1f dB".format(steps[0], steps[1]))
            assertTrue(steps.all { it > TOUCH_DB }, "$voice touch barely changes the tone: $steps")
        }
    }

    @Test
    fun `CHIME strikes again whenever a body crosses its axis`() {
        val options = Libration.Options()
        fun times(macros: Map<String, Float>) = Libration.population(LibrationVoice.CHIME, macros, options).particles
            .flatMap { p -> LibrationCandidates.crossings(p, options.rate, options.seconds).map { it.first } }.sorted()
        val default = times(emptyMap())
        println("LIBRATION CHIME afterstrikes at ${default.map { "%.2f".format(it) }}")
        assertTrue(default.size >= 6, "CHIME has too few afterstrikes: ${default.size}")
        assertTrue(default.all { it >= .06 && it < options.seconds })
        // The swarm decides how many bars there are, and so how many strikes.
        assertTrue(times(mapOf("SWARM" to 0f)).size < default.size)
        // A struck bar alone only decays. Here the level climbs again after the first second.
        val x = render(LibrationVoice.CHIME, options = options)
        val levels = (4 until 24).map { rms(x, it * .25, it * .25 + .25) }
        val climbs = levels.zipWithNext().count { (a, b) -> b > 1.2 * a }
        println("LIBRATION CHIME quarter-second levels from 1 s: ${levels.map { "%.4f".format(it) }} ($climbs climbs)")
        assertTrue(climbs >= 2, "the afterstrikes are not audible")
    }

    @Test
    fun `STRUM plucks each string as its body gets clear, and NUDGE gathers the roll`() {
        val options = Libration.Options()
        fun leavings(nudge: Float) = Libration.population(LibrationVoice.STRUM, mapOf("NUDGE" to nudge), options)
            .let { LibrationCandidates.leavings(it.particles, it.rate, options.seconds) }.filterNotNull().sorted()
        val default = leavings(Libration.defaults(LibrationVoice.STRUM).getValue("NUDGE"))
        println("LIBRATION STRUM plucks at ${default.map { "%.3f".format(it) }}")
        assertTrue(default.size >= 5, "STRUM has too few leaving strings: ${default.size}")
        assertTrue(default.last() - default.first() > .03, "STRUM is one block chord")
        assertEquals(0.0, default.first(), "the first string does not sound with the note")
        assertTrue(default.last() < .5, "the roll is too long to be one chord: ${default.last()}")
        val loose = leavings(.2f)
        val tight = leavings(1f)
        println("LIBRATION STRUM roll length: NUDGE .2 %.3f s, NUDGE 1 %.3f s".format(loose.last(), tight.last()))
        assertTrue(tight.size >= 5 && loose.size >= 5 && tight.last() < .6 * loose.last(), "NUDGE did not gather the roll")
    }

    @Test
    fun `ROTOR turns at the frame's own rate`() {
        val f = hz(60)
        // The level of the sixth partial (the tall pipes) in 80 ms windows every 20 ms, from .4 s to 2 s.
        val x = render(LibrationVoice.ROTOR, options = Libration.Options(seconds = 3.0))
        val levels = (0 until 80).map { M.component(x, 6 * f, .4 + it * .02, .08) }
        val mean = levels.average()
        val turns = levels.zipWithNext().count { (a, b) -> a < mean && b >= mean }
        val swing = levels.max() / levels.min()
        println("LIBRATION ROTOR sixth partial: %d swells in 1.6 s, loudest over quietest %.2f".format(turns, swing))
        // The triangle frame turns 18 radians a second: 2.9 swells a second, 4 or 5 in this window.
        assertTrue(turns in 3..6, "ROTOR does not turn at the frame's rate: $turns")
        assertTrue(swing > 1.25, "ROTOR's turn is too shallow to hear: $swing")
    }

    @Test
    fun `HOLLOW has odd harmonics only`() {
        val f = hz(48)
        val x = render(LibrationVoice.HOLLOW, midi = 48)
        val odd = M.component(x, 3 * f, .3, .5)
        val even = M.component(x, 2 * f, .3, .5) + M.component(x, 4 * f, .3, .5)
        println("LIBRATION HOLLOW third harmonic %.4f against second plus fourth %.4f".format(odd, even))
        assertTrue(odd > 8 * even, "HOLLOW is not hollow")
    }

    @Test
    fun `the cloud voices render exactly what they rendered before the candidates`() {
        val lines = ArrayList<String>()
        for (voice in Libration.CLOUD_VOICES) {
            for (midi in listOf(43, 60)) for (v in listOf(.4f, .8f)) lines +=
                "LIBRATION ${voice.name} model 3 seconds .8 midi $midi v $v " +
                    M.hash(Libration.render(voice, emptyMap(), midi, v, 3, .8).samples)
            for (model in listOf(1, 2)) lines += "LIBRATION ${voice.name} model $model six seconds " +
                M.hash(Libration.render(voice, emptyMap(), 55, .6f, model, 6.0).samples)
            lines += "LIBRATION ${voice.name} held " + M.hash(LibrationPatch("Held", voice,
                Libration.defaults(voice) + mapOf("SWARM" to .1f, "RESONANCE" to .35f, "HOLD" to 1f),
                midi = 55, velocity = .35f, heldModel = LibrationKeys.MODEL_VERSION).render().samples)
            for (p in LibrationPresets.forVoice(voice).take(2)) lines += "LIBRATION preset ${p.name} ${M.hash(p.render().samples)}"
        }
        assertEquals(27, lines.size)
        assertEquals(KEPT_RENDERS, M.hash(lines), lines.joinToString("\n"))
    }

    @Test
    fun `every heard render of the four kept voices hashes as it was heard`() {
        val pinned = KeptVoicePins.read("/libration/kept-voices-v1.sha256")
        val cases = librationKeptCases()
        assertEquals(4 * 7, cases.size)
        assertEquals(cases.map { it.first }.toSet(), pinned.keys)
        for ((id, patch) in cases) assertEquals(pinned.getValue(id), KeptVoicePins.pin(patch.render().samples), id)
    }

    private companion object {
        /** SHA-256 over the 27 lines above, taken from the build before this round (fd9cab169). */
        const val KEPT_RENDERS = "f484e0dec95d8418883a0abc0d9a100cbbd336899c189ada70aa2a64fdea6226"
        /** Level-matched third-octave change a touch step must make to count as a change of tone. */
        const val TOUCH_DB = .5
    }
}

/**
 * The renders the owner kept in libration-voices-1 (20 Nov 2026), from each voice's preset: the range
 * clip (C2, C3, C5 and C6 at the default touch .8; C3 is the preset) and the touch clip (C4 at .35, .7
 * and 1). The clips were joined from exactly these renders.
 */
internal fun librationKeptCases(): List<Pair<String, LibrationPatch>> = LibrationVoice.entries
    .filter { it !in Libration.CLOUD_VOICES }.flatMap { voice ->
        val preset = LibrationPresets.forVoice(voice).single()
        val id = voice.name.lowercase()
        listOf("$id-note-36" to preset.copy(midi = 36), "$id-base" to preset,
            "$id-note-72" to preset.copy(midi = 72), "$id-note-84" to preset.copy(midi = 84)) +
            listOf("soft" to .35f, "medium" to .7f, "hard" to 1f).map { (touch, v) -> "$id-touch-$touch" to preset.copy(midi = 60, velocity = v) }
    }

/** Per-render pins: id, frames and the SHA-256 of the float samples (little-endian). */
internal object KeptVoicePins {
    fun pin(x: FloatArray): String {
        val b = java.nio.ByteBuffer.allocate(x.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        x.forEach { b.putFloat(it) }
        return "${x.size} " + java.security.MessageDigest.getInstance("SHA-256").digest(b.array()).joinToString("") { "%02x".format(it) }
    }
    fun read(resource: String): Map<String, String> = requireNotNull(KeptVoicePins::class.java.getResourceAsStream(resource)) { resource }
        .bufferedReader().readLines().filterNot { it.startsWith("#") || it.isBlank() }
        .associate { it.substringBefore(' ') to it.substringAfter(' ') }
}
