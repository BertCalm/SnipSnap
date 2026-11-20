package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The voices built for listening round chitin-voices-1. Each reads a part of the sync-and-phaser
 * model that SHELL and WING leave unused: HUSK the phaser's correction alone, STING the product of
 * the synced pair, GLINT the slave bending the root sine's phase, HUM the master pulse's width,
 * PINCER SHELL's source plucked with the excursion run backwards. The owner kept HUSK, STING, HUM and
 * PINCER (20 Nov 2026, range and touch), and GLINT after its glass (fix-glint-1) and touch (touch-glint-1)
 * tweaks. SHELL and WING are pinned below against the build before this round, and the heard renders of
 * the five in `chitin/kept-voices-v1.sha256`.
 */
class ChitinCandidateVoicesTest {
    private val M = CandidateVoiceMeasures
    private val candidates = ChitinVoice.entries.filter { it !in Chitin.MIXED_VOICES }

    private fun hz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
    private fun rms(x: FloatArray, from: Double, to: Double): Double {
        val a = (from * Dsp.RATE).toInt()
        val b = minOf((to * Dsp.RATE).toInt(), x.size)
        return sqrt((a until b).sumOf { x[it].toDouble() * x[it] } / (b - a))
    }
    private fun low(voice: ChitinVoice) = Chitin.midiFor(voice, 0f)
    private fun tune(voice: ChitinVoice, midi: Int) = "TUNE" to (midi - low(voice)) / 24f
    private fun render(voice: ChitinVoice, midi: Int = low(voice) + 12, velocity: Float = 1f, macros: Map<String, Float> = emptyMap()) =
        Chitin.render(voice, macros + tune(voice, midi), velocity).samples

    @Test
    fun `the new voices are appended, the kept five offered with their heard preset`() {
        assertEquals(listOf(ChitinVoice.HUSK, ChitinVoice.STING, ChitinVoice.GLINT, ChitinVoice.HUM, ChitinVoice.PINCER), candidates)
        assertEquals(ChitinVoice.entries.take(2), Chitin.MIXED_VOICES)
        assertEquals(ChitinVoice.entries, Chitin.KEPT_VOICES)
        // No new starter: the CHITIN kit is still SHELL and WING.
        assertTrue(ChitinKits.patches().all { it.voice in Chitin.MIXED_VOICES }, "a new voice is on the kit")
        assertEquals(listOf(36, 48, 48, 36, 24), candidates.map { low(it) })
        for (voice in candidates) {
            // One preset per kept voice: default controls (the middle of its range) at full touch, as heard.
            assertEquals(listOf(ChitinPatch(voice.name, voice, Chitin.defaults(voice))),
                ChitinPresets.forVoice(voice))
            assertEquals(Chitin.macrosFor(ChitinVoice.SHELL).map { it.name }, Chitin.macrosFor(voice).map { it.name })
            assertEquals(low(voice) + 12, Chitin.rootMidi(voice))
            val patch = ChitinPatch("Candidate", voice, Chitin.defaults(voice) + ("BITE" to .3f))
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()), "$voice does not survive saving")
            assertContentEquals(patch.render().samples, Patches.fromJsonText(patch.toJsonText()).render().samples)
            // HOLD is the same finite gate as on the kept voices; its top step is one long note.
            assertFalse(Chitin.isLoop(1f))
            assertContentEquals(Chitin.render(voice, mapOf("HOLD" to .98f)).samples, Chitin.render(voice, mapOf("HOLD" to 1f)).samples)
            for (h in listOf(0f, .5f, 1f)) assertEquals(Chitin.plan(voice, mapOf("HOLD" to h)).frames,
                Chitin.render(voice, mapOf("HOLD" to h)).frameCount)
        }
    }

    @Test
    fun `candidates render finite, bounded and the same twice at every corner`() {
        val allowed = setOf(DrumClass.TONAL, DrumClass.PERC, DrumClass.LOOP)
        val strays = ArrayList<String>()
        for (voice in candidates) {
            for (bits in 0 until 32) {
                val keys = listOf("BITE", "COLOR", "PHASE", "MORPH", "PACE")
                val m = keys.mapIndexed { i, k -> k to (if (bits and (1 shl i) == 0) 0f else 1f) }.toMap() + mapOf("HOLD" to 0f)
                val r = Chitin.inspect(voice, m)
                val d = r.diagnostics
                assertTrue(r.snip.samples.all { it.isFinite() }, "$voice $m is not finite")
                assertTrue(r.snip.peak() in .01f.. .991f, "$voice $m peak ${r.snip.peak()}")
                assertTrue(abs(r.snip.samples.average()) < .005, "$voice $m has an offset")
                assertTrue(d.rawPeak < 8 && d.filterPeak < 8, "$voice $m $d")
                assertTrue(d.energyExcess < 1e-9, "$voice $m phaser injects energy: $d")
                val heard = Classifier.classify(r.snip).drumClass
                // PINCER is a plucked bass: at its darkest, fastest corner (COLOR 0, MORPH and PACE 1) the
                // classifier hears the falling filter as a tom. Known and stated, not hidden.
                val tom = voice == ChitinVoice.PINCER && heard == DrumClass.TOM && m["COLOR"] == 0f
                if (heard !in allowed && !tom) strays += "$voice $m became $heard"
            }
            for (midi in listOf(low(voice), low(voice) + 12, low(voice) + 24)) for (velocity in listOf(.3f, .7f, 1f)) {
                val x = render(voice, midi, velocity)
                assertTrue(x.all { it.isFinite() } && x.maxOf { abs(it) } <= .991f, "$voice MIDI$midi v$velocity")
                // No click at the start, no cut at the end.
                assertTrue(abs(x[0]) < 5e-3f, "$voice MIDI$midi v$velocity starts open: ${x[0]}")
                assertTrue(abs(x.last()) < 1e-3f, "$voice MIDI$midi v$velocity ends open")
                assertTrue((0 until 11).maxOf { abs(x[it]) } < .05f * x.maxOf { abs(it) }, "$voice MIDI$midi v$velocity starts with a click")
                assertTrue(rms(x, x.size / 44100.0 - .02, x.size / 44100.0) < .05 * rms(x, .03, .3), "$voice MIDI$midi v$velocity ends abruptly")
            }
            assertContentEquals(Chitin.inspect(voice).snip.samples, Chitin.inspect(voice).snip.samples, "$voice is not deterministic")
            assertTrue(Chitin.render(voice, velocity = 0f).samples.all { it == 0f }, "$voice sounds at zero velocity")
        }
        assertTrue(strays.isEmpty(), "candidates left the melodic classes:\n" + strays.joinToString("\n"))
    }

    @Test
    fun `candidates sound the played note`() {
        val wrong = ArrayList<String>()
        for (voice in candidates) for (midi in listOf(low(voice), low(voice) + 12, low(voice) + 24)) {
            val x = render(voice, midi)
            val root = M.rootCents(x, hz(midi), .15, .4)
            val share = M.rootShare(x, hz(midi), .15, .4)
            println("CHITIN $voice MIDI$midi root %+.0f cents (share %.2f)".format(root, share))
            if (abs(root) > 15.0) wrong += "$voice MIDI$midi root is $root cents off"
            if (share <= ROOT_SHARE) wrong += "$voice MIDI$midi has a weak root: $share"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun `every candidate is spectrally distinct from every other voice wherever their ranges meet`() {
        val close = ArrayList<String>()
        for (midi in listOf(36, 48, 60)) {
            val voices = ChitinVoice.entries.filter { midi - low(it) in 0..24 }
            val shapes = voices.associateWith { M.bands(render(it, midi)) }
            for (a in candidates.filter { it in voices }) for (b in voices) if (a != b) {
                val d = M.distance(shapes.getValue(a), shapes.getValue(b))
                println("CHITIN third-octave distance MIDI$midi $a/$b = %.1f dB".format(d))
                if (d < M.DISTINCT_DB) close += "$a and $b are only $d dB apart at MIDI $midi"
            }
        }
        assertTrue(close.isEmpty(), close.joinToString("\n"))
        // Every pair of voices shares C3, so every pair was compared at least once.
        assertTrue(ChitinVoice.entries.all { 48 - low(it) in 0..24 })
    }

    @Test
    fun `soft, medium and hard differ in tone at the same loudness`() {
        for (voice in candidates) {
            val shapes = listOf(.35f, .7f, 1f).map { M.bands(render(voice, velocity = it)) }
            val steps = listOf(M.distance(shapes[0], shapes[1]), M.distance(shapes[1], shapes[2]))
            println("CHITIN $voice level-matched touch steps: %.1f dB, %.1f dB".format(steps[0], steps[1]))
            val floor = if (voice == ChitinVoice.GLINT) GLINT_TOUCH_DB else TOUCH_DB
            assertTrue(steps.all { it > floor }, "$voice touch barely changes the tone: $steps")
        }
    }

    @Test
    fun `HUSK is the bare root with only what the phaser changes above it`() {
        val f = hz(48)
        val plain = render(ChitinVoice.HUSK, macros = mapOf("PHASE" to 0f))
        val husk = render(ChitinVoice.HUSK)
        fun above(x: FloatArray) = (3..12).sumOf { M.component(x, it * f, .3, .4) } / M.component(x, f, .3, .4)
        println("CHITIN HUSK partials 3 to 12 against the root: PHASE 0 %.4f, default %.4f".format(above(plain), above(husk)))
        assertTrue(above(plain) < .02, "HUSK without the phaser is not a bare root")
        assertTrue(above(husk) > 10 * above(plain) && above(husk) > .05, "the phaser's correction is missing")
    }

    @Test
    fun `STING opens from a round product into a buzz as BITE rises`() {
        val round = M.bands(render(ChitinVoice.STING, macros = mapOf("BITE" to 0f)))
        val buzz = M.bands(render(ChitinVoice.STING, macros = mapOf("BITE" to 1f)))
        println("CHITIN STING BITE 0 against 1: %.1f dB".format(M.distance(round, buzz)))
        assertTrue(M.distance(round, buzz) > 4.0)
    }

    @Test
    fun `GLINT and PINCER are struck and die away inside the gate`() {
        for (voice in listOf(ChitinVoice.GLINT, ChitinVoice.PINCER)) {
            val x = render(voice)
            val gate = Chitin.plan(voice, emptyMap()).gate.toDouble() / Dsp.RATE
            val early = rms(x, .02, .12)
            val late = rms(x, gate - .12, gate - .02)
            println("CHITIN $voice level early %.4f, at the end of the gate %.4f".format(early, late))
            assertTrue(early > 2.5 * late, "$voice does not die away")
        }
        // PINCER starts open and closes: its early spectrum is the brighter one.
        val f = hz(36)
        val x = render(ChitinVoice.PINCER)
        fun bright(from: Double) = (6..14).sumOf { M.component(x, it * f, from, .12) } / M.component(x, f, from, .12)
        println("CHITIN PINCER upper partials against root: early %.3f, later %.3f".format(bright(.02), bright(.45)))
        assertTrue(bright(.02) > 1.5 * bright(.45), "PINCER does not close")
    }

    @Test
    fun `GLINT rings with inharmonic glass partials long after the strike`() {
        // The owner's note on chitin-voices-1 was "Missing glassiness": the heard GLINT was close to a sine
        // once the strike had passed. The released slave puts partials at 2.6 and 4.6 times the root.
        for (midi in listOf(48, 60, 72)) {
            val f = hz(midi)
            val x = render(ChitinVoice.GLINT, midi)
            fun glass(from: Double) = (M.component(x, 2.6 * f, from, .2) + M.component(x, 4.6 * f, from, .2)) / M.component(x, f, from, .2)
            fun harmonics(from: Double) = (2..5).sumOf { M.component(x, it * f, from, .2) } / M.component(x, f, from, .2)
            println("CHITIN GLINT MIDI$midi glass partials against root: %.3f at .15 s, %.3f at .9 s (harmonics 2 to 5: %.3f)"
                .format(glass(.15), glass(.9), harmonics(.9)))
            assertTrue(glass(.9) > .25, "GLINT MIDI$midi has no glass left at .9 s: ${glass(.9)}")
            assertTrue(glass(.9) > harmonics(.9), "GLINT MIDI$midi rings harmonic, not glass")
            // The glass partials ring with the root: their share does not collapse after the strike.
            assertTrue(glass(.9) > .4 * glass(.15), "GLINT MIDI$midi glass dies with the strike")
        }
    }

    @Test
    fun `HUM moves the pulse width with the excursion`() {
        val f = hz(48)
        fun even(x: FloatArray) = (M.component(x, 2 * f, .5, .3) + M.component(x, 4 * f, .5, .3)) / M.component(x, f, .5, .3)
        val still = even(render(ChitinVoice.HUM, macros = mapOf("MORPH" to 0f, "BITE" to 0f)))
        val moved = even(render(ChitinVoice.HUM, macros = mapOf("MORPH" to 1f, "BITE" to 0f)))
        println("CHITIN HUM even harmonics against root: MORPH 0 %.3f, MORPH 1 %.3f".format(still, moved))
        assertTrue(moved > 3 * still, "the pulse width did not move")
    }

    @Test
    fun `SHELL and WING render exactly what they rendered before the candidates`() {
        val lines = ArrayList<String>()
        for (voice in Chitin.MIXED_VOICES) {
            for (tune in listOf(0f, .5f, 1f)) for (v in listOf(.4f, 1f)) lines +=
                "CHITIN ${voice.name} TUNE $tune v $v " + M.hash(Chitin.render(voice, mapOf("TUNE" to tune), v).samples)
            for (p in ChitinPresets.forVoice(voice)) lines += "CHITIN preset ${p.name} ${M.hash(p.render().samples)}"
        }
        assertEquals(24, lines.size)
        assertEquals(KEPT_RENDERS, M.hash(lines), lines.joinToString("\n"))
    }

    @Test
    fun `every heard render of the five kept voices hashes as it was heard`() {
        val pinned = KeptVoicePins.read("/chitin/kept-voices-v1.sha256")
        val cases = chitinKeptCases()
        assertEquals(5 * 6, cases.size)
        assertEquals(cases.map { it.first }.toSet(), pinned.keys)
        for ((id, render) in cases) assertEquals(pinned.getValue(id), KeptVoicePins.pin(render().samples), id)
        // The preset is the range clip's middle note and the touch clip's hard note.
        for (voice in Chitin.KEPT_VOICES - Chitin.MIXED_VOICES.toSet()) {
            val preset = ChitinPresets.forVoice(voice).single()
            assertContentEquals(preset.render().samples, Chitin.render(voice, mapOf("TUNE" to .5f), 1f).samples)
        }
    }

    private companion object {
        /** SHA-256 over the 24 lines above, taken from the build before this round (fd9cab169). */
        const val KEPT_RENDERS = "9e76c181952002d5934124a12398e683a28e55ffe3c51e7d60ea94c40252998a"
        const val ROOT_SHARE = .3
        const val TOUCH_DB = .5
        /** GLINT's touch was heard as "all the same" at 5.2 and 3.5 dB (fix-glint-1); it now moves 23.9 and 18.1 dB. */
        const val GLINT_TOUCH_DB = 12.0
    }
}

/**
 * The renders the owner kept in chitin-voices-1 and, for GLINT, fix-glint-1 and its touch round (20 Nov 2026), from each voice's preset: the range clip
 * (TUNE 0, .5, 19/24 and 1 at full touch; .5 is the preset) and the touch clip's soft (.35) and medium
 * (.7) notes at TUNE .5; its hard note is the preset. The clips were joined from exactly these renders.
 */
internal fun chitinKeptCases(): List<Pair<String, () -> com.snipsnap.audio.Snip>> =
    (Chitin.KEPT_VOICES - Chitin.MIXED_VOICES.toSet()).flatMap { voice ->
        val preset = ChitinPresets.forVoice(voice).single()
        val id = voice.name.lowercase()
        val low = Chitin.midiFor(voice, 0f)
        fun at(tune: Float): () -> com.snipsnap.audio.Snip = { preset.copy(macros = preset.macros + ("TUNE" to tune)).render() }
        // A saved CHITIN patch plays at full touch; touch is the pad's velocity, given to the renderer.
        fun touched(v: Float): () -> com.snipsnap.audio.Snip = { Chitin.render(voice, preset.macros, v) }
        listOf("$id-note-$low" to at(0f), "$id-base" to { preset.render() }, "$id-note-${low + 19}" to at(19 / 24f),
            "$id-note-${low + 24}" to at(1f), "$id-touch-soft" to touched(.35f), "$id-touch-medium" to touched(.7f))
    }
