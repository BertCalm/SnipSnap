package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The owner's 20 Nov 2026 verdicts on CAUSTIC. Held keys: "Holds are unnatural, they need more
 * humanization"; measured, every repeated strike, catch and tick was identical and the hold
 * repeated every second. ALLOY's six newer presets: "First 3 sound the same, second to last is
 * abrupt". These tests hold both fixes to numbers. The four native voices and the two heard
 * ALLOY anchors were kept and are pinned elsewhere (CausticCandidateVoicesTest).
 */
class CausticHoldDriftTest {
    private fun held(voice: CausticVoice) = CausticInstrument.defaults(voice) + ("HOLD" to 1f)

    @Test
    fun `a held key varies from strike to strike without repeating inside its loop, in tune, and closes`() {
        for (voice in CausticInstrument.KEPT_VOICES) for (midi in listOf(36, 60, 84)) {
            val base = CausticSustain.loopFrameCount(midi, 1f)
            val still = CausticHeld.probe(voice, held(voice), midi, .75f, 2, 4, true, { false }, record = false, human = null)
            assertEquals(base, still.loop.frameCount, "$voice MIDI$midi still loop")
            // A hundred level frames to one original period, so "one period later" is an exact lag.
            val hop = base / 100
            val hz = HoldDriftProbes.hz(midi)
            val stillLevel = HoldDriftProbes.levelDb(HoldDriftProbes.tile(still.loop.samples.copyOf(hop * 100), CausticSustain.HUMAN_REPEATS), hz, hop = hop)
            assertTrue(HoldDriftProbes.recurrence(stillLevel) < .01, "$voice MIDI$midi: the still hold repeats every period, as judged")
            val humans = if (midi == 60) listOf("modest" to CausticSustain.HUMAN_MODEST, "stronger" to CausticSustain.HUMAN_STRONGER)
                else listOf("modest" to CausticSustain.HUMAN_MODEST)
            for ((label, human) in humans) {
                val p = CausticHeld.probe(voice, held(voice), midi, .75f, 2, 4, true, { false }, record = false, human = human)
                val loop = p.loop.samples
                val name = "$voice MIDI$midi $label"
                assertEquals(base * CausticSustain.HUMAN_REPEATS, loop.size, "$name loop length")
                assertTrue(p.seamError < Keys.MAX_SEAM_ERROR, "$name seam ${p.seamError}")
                assertTrue(p.continuationError < CausticHeld.MAX_CONTINUATION_ERROR, "$name neighbours ${p.continuationError}")
                assertTrue(p.diagnostics.stateResidual < 1e-4, "$name state ${p.diagnostics.stateResidual}")
                assertTrue(p.note.snip.samples.all { it.isFinite() && abs(it) <= .991f }, "$name headroom")
                val level = HoldDriftProbes.levelDb(loop, hz, hop = hop)
                val recurrence = HoldDriftProbes.recurrence(level)
                val pitch = HoldDriftProbes.pitch(loop, hz)
                println("CAUSTIC HOLD $name loop %.2f s prefix %.2f s level range %.2f dB recurrence %.3f dB (still %.3f) pitch mean %+.2f c"
                    .format(loop.size.toDouble() / Dsp.RATE, p.note.loopStartFrame.toDouble() / Dsp.RATE, HoldDriftProbes.range(level),
                        recurrence, HoldDriftProbes.recurrence(stillLevel), pitch.mean))
                assertTrue(recurrence > MIN_RECURRENCE_DB, "$name repeats inside its loop: $recurrence dB")
                // The body is untouched, so the hold sits where the still hold sat; at C4 that is the note.
                val stillPitch = HoldDriftProbes.pitch(HoldDriftProbes.tile(still.loop.samples, CausticSustain.HUMAN_REPEATS), hz)
                assertTrue(abs(pitch.mean - stillPitch.mean) < 2.0, "$name moved the pitch: ${pitch.mean} against ${stillPitch.mean} cents")
                if (midi == 60) assertTrue(abs(pitch.mean) < 10.0, "$name mean pitch ${pitch.mean} cents")
                // The variation is in the excitation, so the loudness of the hold stays where it was.
                val stillRms = sqrt(still.loop.samples.sumOf { it.toDouble() * it } / still.loop.samples.size)
                val rms = sqrt(loop.sumOf { it.toDouble() * it } / loop.size)
                assertTrue(abs(20 * log10(rms / stillRms)) < 1.5, "$name level moved from the still hold: ${20 * log10(rms / stillRms)} dB")
            }
        }
    }

    @Test
    fun `the instrument plays the modest hold, tells its true length and leaves a moving path alone`() {
        for (voice in CausticInstrument.KEPT_VOICES) {
            val note = CausticInstrument.keyNote(voice, held(voice))
            val direct = CausticHeld.probe(voice, held(voice), 60, .75f, 2, 4, true, { false }, record = false, human = CausticSustain.HUMAN_MODEST)
            assertContentEquals(direct.note.snip.samples, note.snip.samples, "$voice keyNote is the modest hold")
            assertContentEquals(direct.loop.samples, CausticInstrument.renderLoop(voice, held(voice)).samples, "$voice loop pad")
            assertEquals(direct.loop.frameCount.toDouble() / Dsp.RATE, CausticInstrument.durationSeconds(voice, held(voice)), 1e-9)
            val moving = held(voice) + ("MOTION" to .5f)
            assertEquals(1, CausticSustain.heldRepeats(moving, CausticSustain.HUMAN_STRONGER))
            assertEquals(CausticSustain.loopFrameCount(60, 1f).toDouble() / Dsp.RATE, CausticInstrument.durationSeconds(voice, moving), 1e-9)
        }
    }

    @Test
    fun `the humanized held keys are pinned`() {
        val now = CausticInstrument.KEPT_VOICES.associateWith { CandidateVoiceProbes.hash(CausticInstrument.keyNote(it, held(it)).snip.samples) }
        for ((voice, hash) in now) println("CAUSTIC HELD PIN CausticVoice.${voice.name} to \"$hash\",")
        assertEquals(HELD_KEYS, now)
    }

    @Test
    fun `the first three newer ALLOY presets are distinct and Short Glint rings out`() {
        val shapes = CausticPresets.forVoice(CausticVoice.ALLOY).associate { it.name to CandidateVoiceProbes.bands(it.render().samples) }
        val trio = listOf("Parallel Body", "Rim Vessel", "Wide Shoulder")
        for (a in trio.indices) for (b in a + 1 until trio.size) {
            val d = CandidateVoiceProbes.distance(shapes.getValue(trio[a]), shapes.getValue(trio[b]))
            println("CAUSTIC ALLOY ${trio[a]} to ${trio[b]}: %.2f dB".format(d))
            assertTrue(d > CandidateVoiceProbes.DISTINCT_DB, "${trio[a]} and ${trio[b]} are only $d dB apart")
        }
        for (name in trio) for (anchor in listOf("Fuller Body", "Preferred Alloy")) {
            val d = CandidateVoiceProbes.distance(shapes.getValue(name), shapes.getValue(anchor))
            println("CAUSTIC ALLOY $name to $anchor: %.2f dB".format(d))
            assertTrue(d > MIN_FROM_ANCHOR_DB, "$name is only $d dB from $anchor")
        }
        // "Abrupt" was the decay itself: at LENGTH 0 the note was 40 dB down after 0.22 s.
        val glint = CausticPresets.all().single { it.name == "Short Glint" }.render().samples
        val ring = secondsTo(glint, -40.0)
        println("CAUSTIC ALLOY Short Glint %.2f s long, 40 dB down after %.2f s, ends %.0f dB below its peak".format(
            glint.size.toDouble() / Dsp.RATE, ring, -endLevelDb(glint)))
        assertTrue(ring > .4 && glint.size <= 1.5 * Dsp.RATE, "Short Glint is abrupt or no longer short: $ring s of ${glint.size} frames")
        assertTrue(endLevelDb(glint) < -60.0, "Short Glint is cut off while ringing")
    }

    @Test
    fun `every factory preset is pinned`() {
        val now = CausticPresets.all().associate { it.name to CandidateVoiceProbes.hash(it.render().samples) }
        for ((name, hash) in now) println("CAUSTIC PRESET PIN \"$name\" to \"$hash\",")
        assertEquals(PRESETS, now)
    }

    /** When the 10 ms level first stays under [db] relative to the loudest 10 ms. */
    private fun secondsTo(x: FloatArray, db: Double): Double {
        val frame = Dsp.RATE / 100
        val level = DoubleArray(x.size / frame) { f -> sqrt((0 until frame).sumOf { x[f * frame + it].toDouble() * x[f * frame + it] } / frame) }
        val peak = level.max()
        val last = level.indexOfLast { 20 * log10(it / peak + 1e-12) >= db }
        return (last + 1) * frame.toDouble() / Dsp.RATE
    }

    /** Level of the stretch from 60 to 20 ms before the end (ahead of the closing fade), dB relative to the peak 10 ms level. */
    private fun endLevelDb(x: FloatArray): Double {
        fun rms(from: Int, until: Int) = sqrt((from until until).sumOf { x[it].toDouble() * x[it] } / (until - from))
        val frame = Dsp.RATE / 100
        val peak = (0 until x.size / frame).maxOf { rms(it * frame, (it + 1) * frame) }
        return 20 * log10(rms(x.size - 6 * frame, x.size - 2 * frame) / peak + 1e-12)
    }

    private companion object {
        /** The quietest mover is held ALLOY, a drone: about .06 dB. The still hold measures 0. */
        const val MIN_RECURRENCE_DB = .03
        const val MIN_FROM_ANCHOR_DB = 2.0
        /** With [CausticInstrument.HELD_HUMAN]: unheard until the owner judges clips CK1 to CK3. */
        val HELD_KEYS = mapOf(
            CausticVoice.ALLOY to "9b65c3d8a6e6cf5f39dbb156ef01e6762c2ae0c2ec39552185eccd554c278a7b",
            CausticVoice.HOLLOW_MALLET to "330f7c9ae56f54b170b04d2eec7fe8db357bdbb50b52d3a05e2d634c0dcbca80",
            CausticVoice.RUBBED_RING to "0d795246e73b0391e464385edc3da49b7d61a57a57406ab7178fc369ba6b53b8",
            CausticVoice.TRAVELLING_CATCHES to "69fa72b7e9945d5b22ee7b8a346e7de1c1c8e9f4c51a9bc77b820a8988a84427",
        )
        /** Every factory preset. Four ALLOY presets were re-voiced on the 20 Nov 2026 verdict (clip CK4); the other seven are the kept bytes. */
        val PRESETS = mapOf(
            "Fuller Body" to "70eac0b66524dfa32ddff9aae2d71803edc9930e8adf62bc36ed986086a383fd",
            "Preferred Alloy" to "30dbdf5bfeb86a02beb41fd33d6a59a36c7fbe7391fed303d837a75bb1be6be1",
            "Parallel Body" to "2ea0ca7c7801988bcccb1f9dbcf9c4e9eaa8fee439291824d4cce9da72314927",
            "Rim Vessel" to "258e36d30d8a3e862b7fcb9e1a4bd84c82f364d33f92122fbcb6ecddf1afc053",
            "Wide Shoulder" to "0cfed42aaa9781eff102d0dfd996e1b17511f314d1e583f8e14b7a9777f6781c",
            "Sparse Alloy" to "90f08338cd1f0d3d803ac224ffea9664764f41fd44fa280990d18d7292ae756b",
            "Short Glint" to "31266b67f3719a85c972a2fe7460171c3c09c2a1956541270ff000b6c6c46645",
            "Low Vessel" to "d761a5328ed57ba733ec8acf9539b28465d852ef917acbb68f2e31559a387235",
            "Hollow Mallet" to "02a95d42d999084859b818cb87a104a5e14034c6e4edf83a0a1e7df774434f9d",
            "Rubbed Ring" to "643a6403aefec52c80480860fa057e9acb75a6ce140b2fec175d4476bce56ebc",
            "Travelling Catches" to "5ca5994d8c35011fa367b430cd50714d4853d0c907dec65f8cab0eb63f267ea8",
        )
    }
}
