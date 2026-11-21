package com.snipsnap.synth

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The owner's 20 Nov 2026 verdict on WARM and PLUCK: "Holds are unnatural, they need more
 * humanization". Measured, the held part was static: level, pitch and brightness fixed, and the
 * top-HOLD loop repeated every two seconds. These tests hold the fix to numbers: the held part
 * now moves, does not repeat inside its loop, stays in tune and still closes. HOLLOW, TRACE and
 * every note with HOLD 0 were kept as heard and must not move at all.
 */
class LatticeHoldDriftTest {
    private val humanized = listOf(LatticeVoice.WARM, LatticeVoice.PLUCK)
    private fun presets() = humanized.flatMap { LatticePresets.forVoice(it) }.filter { it.macros.getValue("HOLD") > 0f }

    @Test
    fun `a held WARM or PLUCK loop drifts without repeating inside itself, in tune, and closes`() {
        for (voice in humanized) for (midi in listOf(36, 60, 84)) {
            val macros = mapOf("HOLD" to 1f, "REJECT" to .4f)
            val still = Lattice.heldProbe(Lattice.settingsFor(voice, macros, midi, .75f, human = null))
            val stillLevel = HoldDriftProbes.levelDb(HoldDriftProbes.tile(still.snip.samples, 3), still.fittedHz)
            assertTrue(HoldDriftProbes.range(stillLevel) < .4 && HoldDriftProbes.recurrence(stillLevel) < .02,
                "$voice MIDI$midi: the still hold is the static one that was judged")
            for ((label, human) in listOf("modest" to Lattice.HUMAN_MODEST, "stronger" to Lattice.HUMAN_STRONGER)) {
                val held = Lattice.heldProbe(Lattice.settingsFor(voice, macros, midi, .75f, human))
                val loop = held.snip.samples
                val name = "$voice MIDI$midi $label"
                assertEquals(Lattice.HUMAN_SPAN_SECONDS, loop.size.toDouble() / Dsp.RATE, .02, "$name loop length")
                assertTrue(held.seamError < Keys.MAX_SEAM_ERROR, "$name seam ${held.seamError}")
                val cycles = loop.indices.sumOf { val d = loop[it] - held.previous[it].toDouble(); d * d } /
                    held.previous.sumOf { it.toDouble() * it }
                assertTrue(cycles < 1e-5, "$name: two separately generated cycles differ by $cycles")
                assertTrue(loop.all { it.isFinite() && abs(it) < .99f }, "$name headroom")
                val level = HoldDriftProbes.levelDb(loop, held.fittedHz)
                val range = HoldDriftProbes.range(level)
                val recurrence = HoldDriftProbes.recurrence(level)
                val brightness = HoldDriftProbes.brightnessDb(loop, held.fittedHz)
                val pitch = HoldDriftProbes.pitch(loop, held.requestedHz)
                println("LATTICE HOLD $name level range %.2f dB recurrence %.3f dB brightness range %.2f dB recurrence %.3f pitch mean %+.2f c range %.2f c"
                    .format(range, recurrence, HoldDriftProbes.range(brightness), HoldDriftProbes.recurrence(brightness), pitch.mean, pitch.rangeWhereLoud()))
                assertTrue(range > .7 * human.levelDb && range < 3 * human.levelDb + .5, "$name level range $range dB")
                assertTrue(recurrence > .05 * human.levelDb, "$name repeats inside its loop: $recurrence dB")
                assertTrue(HoldDriftProbes.range(brightness) > human.tilt, "$name brightness does not move")
                assertTrue(abs(pitch.mean) < 10.0, "$name mean pitch ${pitch.mean} cents")
                assertTrue(pitch.rangeWhereLoud() > .8 * human.cents, "$name pitch does not move: ${pitch.rangeWhereLoud()}")
                // Below C4 the 50 ms pitch reading ripples by more than the drift, so only the upper notes bound it.
                if (midi >= 60) assertTrue(pitch.rangeWhereLoud() < 2.5 * human.cents + 4, "$name pitch range ${pitch.rangeWhereLoud()}")
                assertContentEquals(loop, Lattice.heldProbe(Lattice.settingsFor(voice, macros, midi, .75f, human)).snip.samples, "$name determinism")
            }
        }
    }

    @Test
    fun `a short held note keeps its attack, stays in tune and is no longer level`() {
        for (p in presets().filterNot { Lattice.isLoop(it.voice, it.macros.getValue("HOLD")) }) {
            val still = Lattice.renderPlayed(p.voice, p.macros, p.midi, p.velocity, human = null).samples
            for ((label, human) in listOf("modest" to Lattice.HUMAN_MODEST, "stronger" to Lattice.HUMAN_STRONGER)) {
                val played = Lattice.renderPlayed(p.voice, p.macros, p.midi, p.velocity, human).samples
                val name = "${p.name} $label"
                assertEquals(still.size, played.size)
                assertTrue(played.all { it.isFinite() && abs(it) < .99f })
                val onset = (.01 * Dsp.RATE).toInt()
                val early = (0 until onset).maxOf { abs(played[it] - still[it]) } / (0 until onset).maxOf { abs(still[it]) }
                assertTrue(early < 1e-3, "$name changed the attack: $early")
                val settings = Lattice.settingsFor(p.voice, p.macros, p.midi, p.velocity)
                val from = (.1 * Dsp.RATE).toInt()
                // The stretch of level effort only: at the release the passing tone and the outline
                // briefly cancel, and a drifted phase fills that dip by several dB for 50 ms.
                val until = ((settings.excitationSeconds - .3) * Dsp.RATE).toInt()
                val hz = HoldDriftProbes.hz(settings.midi)
                val pitch = HoldDriftProbes.pitch(played.copyOfRange(from, until), hz)
                val a = HoldDriftProbes.levelDb(still.copyOfRange(from, until), hz, circular = false)
                val b = HoldDriftProbes.levelDb(played.copyOfRange(from, until), hz, circular = false)
                val moved = a.indices.maxOf { abs((b[it] - b[0]) - (a[it] - a[0])) }
                println("LATTICE SHORT HOLD $name held ${settings.excitationSeconds} s mean pitch %+.2f c level moved %.2f dB".format(pitch.mean, moved))
                assertTrue(abs(pitch.mean) < 10.0, "$name mean pitch ${pitch.mean} cents")
                // The collector is narrow, so a drifting pitch also moves how much outline rings.
                assertTrue(moved < 3 * human.levelDb + .5, "$name level moved $moved dB")
                if (until - from > .4 * Dsp.RATE) assertTrue(moved > .05 * human.levelDb, "$name held stretch is still level: $moved dB")
            }
        }
    }

    @Test
    fun `only held WARM and PLUCK notes drift`() {
        for (voice in listOf(LatticeVoice.WARM, LatticeVoice.PLUCK, LatticeVoice.HOLLOW, LatticeVoice.BOW)) for (hold in listOf(0f, .3f, 1f)) {
            val human = Lattice.settingsFor(voice, mapOf("HOLD" to hold)).human
            if (voice in humanized && hold > 0f) assertEquals(Lattice.HELD_HUMAN, assertNotNull(human)) else assertNull(human, "$voice HOLD $hold")
        }
        assertNull(Lattice.settingsFor(LatticeVoice.TRACE, mapOf("HOLD" to .5f)).human)
        for (hold in listOf(.3f, 1f)) assertContentEquals(
            Lattice.renderPlayed(LatticeVoice.HOLLOW, mapOf("HOLD" to hold), human = null).samples,
            Lattice.renderPlayed(LatticeVoice.HOLLOW, mapOf("HOLD" to hold), human = Lattice.HUMAN_STRONGER).samples)
        assertContentEquals(Lattice.renderPlayed(LatticeVoice.WARM, human = null).samples, Lattice.render(LatticeVoice.WARM).samples)
        assertFalse(Lattice.renderPlayed(LatticeVoice.WARM, mapOf("HOLD" to .3f), human = null).samples
            .contentEquals(Lattice.render(LatticeVoice.WARM, mapOf("HOLD" to .3f)).samples))
    }

    @Test
    fun `the six humanized presets are pinned, and with the drift off they are the bytes that were judged`() {
        for (p in presets()) {
            assertEquals(JUDGED.getValue(p.name), CandidateVoiceProbes.hash(
                Lattice.renderPlayed(p.voice, p.macros, p.midi, p.velocity, human = null).samples), "${p.name} with the drift off")
        }
        val now = presets().associate { it.name to CandidateVoiceProbes.hash(it.render().samples) }
        for ((name, hash) in now) println("LATTICE HUMANIZED PIN \"$name\" to \"$hash\",")
        assertEquals(HUMANIZED, now)
        assertEquals(6, now.size)
    }

    private companion object {
        /** Before the 20 Nov 2026 verdict (build fbfda867d): what the owner heard and asked to be humanized. */
        val JUDGED = mapOf(
            "Close Weave" to "a8a6037bd490b7b5f30327b66afbdc18d2b7da7e3af90305a36832a902073934",
            "Soft Outline" to "f0cf2479c93ef72d705f50a3193cdc24d97d350becfa800f4ae9aaec2da738de",
            "Warm Thread" to "6c2774c5b0db9da3fb0da83bd6f1be13e2db86417a1202f4983b797ce2a9ec2f",
            "Plucked Outline" to "f466e8ebfada074f356bc733428d2b5dc8a8f3aa9a16555f6008bf4f4019728b",
            "Split Pluck" to "04a9327393bb144a57c4ce6351f1e99cd7808f7aacc0db9b00ec00a20a306db9",
            "Pluck Thread" to "59812c91d254b34b4d092d848a39cf9d40abc99ab1dd59f77eb8eaffe26f7e5f",
        )
        /** With [Lattice.HELD_HUMAN]: unheard until the owner judges clips CJ1 to CJ3. */
        val HUMANIZED = mapOf(
            "Close Weave" to "c8ab0904b89c67d43c4cd4c0ba09903843333ba9e28fde7efc94ca2774ae6e09",
            "Soft Outline" to "931ef35cf632124c7cd5b66a6e73a385d9e7dafebfc5a2738d8fa1cb5fe21d6e",
            "Warm Thread" to "5e3dcc1b2618f2d63cd2b652bf596f1e3de266241fcabeb9457f50e01e1b2900",
            "Plucked Outline" to "99a1b9bee54afb3af06948587dcc5ec5b1afe00c6979bc12225504c3068500e3",
            "Split Pluck" to "0cb42e927d74ff3041a289ab95e1ff93b324684ab1b426e021dec95552c03e87",
            "Pluck Thread" to "d99fdd7ecb5b8e9cb82e67a6860fa2ccaafb5f666e96c4529cdfa1356e6312a8",
        )
    }
}
