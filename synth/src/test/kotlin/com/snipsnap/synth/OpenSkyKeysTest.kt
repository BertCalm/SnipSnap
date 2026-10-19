package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenSkyKeysTest {
    private val soft = OpenSkyTimbre(sineMix = .8f, detuneScale = .5f, toneScale = .35f,
        attackScale = 1.7f, releaseScale = 2f)
    private val bright = OpenSkyTimbre(pulseMix = .25f, pulseWidth = .42f, sineMix = .25f,
        octaveMix = .45f, detuneScale = 1.3f, toneScale = 1.6f, attackScale = .65f, releaseScale = 1.3f)

    private fun rms(s: FloatArray, from: Int = 0, until: Int = s.size): Double {
        var energy = 0.0
        for (i in from until until) energy += s[i].toDouble() * s[i]
        return sqrt(energy / (until - from))
    }

    private fun channelSeam(note: KeyNote, channel: Int): Double {
        val start = note.loopStartFrame.toInt()
        val frames = note.snip.frameCount
        val period = frames - start
        var difference = 0.0; var energy = 0.0
        for (f in start - 256 until start) {
            val a = note.snip.samples[f * 2 + channel]
            val b = note.snip.samples[(f + period) * 2 + channel]
            difference += (a - b.toDouble()) * (a - b.toDouble())
            energy += b.toDouble() * b
        }
        return difference / energy.coerceAtLeast(1e-20)
    }

    @Test
    fun `production facade preserves exactly four controls and pitched routing`() {
        for (voice in OpenSkyVoice.entries) {
            val specs = OpenSky.macrosFor(voice)
            assertEquals(listOf("RISE", "WIDTH", "GLOW", "AIR"), specs.map { it.name })
            assertEquals(OpenSky.defaults(voice), specs.associate { it.name to it.default })
            assertEquals(DrumClass.TONAL, OpenSky.drumClassFor(voice))
            val a = OpenSky.scramble(voice, Random(58))
            val b = OpenSky.scramble(voice, Random(58))
            assertEquals(a, b)
            assertEquals(OpenSky.defaults(voice).keys, a.keys)
            assertTrue(a.values.all { it.isFinite() && it in 0f..1f })
        }
    }

    @Test
    fun `host integration preserves accepted R3 finite and held wet PCM`() {
        // Captured from the accepted R3 renderer before adding the key-prefix mode.
        val hashes = mapOf(OpenSkyVoice.ANTHEM to -311736040, OpenSkyVoice.SOAR to -1200874191,
            OpenSkyVoice.HALO to 1817748721, OpenSkyVoice.CRYSTAL to -1133306367)
        for ((voice, hash) in hashes) {
            assertEquals(hash, OpenSky.render(voice, gateSeconds = .4f).samples.contentHashCode(), "$voice R3 PCM moved")
        }
        assertEquals(2131361308, OpenSky.renderHeld(OpenSkyVoice.HALO, loopSeconds = 1.5f).samples.contentHashCode())
    }

    @Test
    fun `halo zone roots stay native and unsupported key voices fail explicitly`() {
        assertEquals((48..72 step 3).toList(), Keys.openSkyPadMidis(OpenSkyVoice.HALO))
        assertEquals((24..48 step 3).toList(), Keys.openSkyPadMidis(OpenSkyVoice.HALO, 36))
        assertEquals((84..108 step 3).toList(), Keys.openSkyPadMidis(OpenSkyVoice.HALO, 96))
        assertFailsWith<IllegalArgumentException> { Keys.openSkyPadMidis(OpenSkyVoice.HALO, 35) }
        assertFailsWith<IllegalArgumentException> { Keys.openSkyPadMidis(OpenSkyVoice.HALO, 97) }
        for (voice in OpenSkyVoice.entries.filter { it != OpenSkyVoice.HALO }) {
            assertFailsWith<IllegalArgumentException> { Keys.openSkyPadMidis(voice) }
            assertFailsWith<IllegalArgumentException> { Keys.openSkyPad(voice, emptyMap(), 60) }
        }
    }

    @Test
    fun `dry halo key has a genuine onset native pitch and preserved authoring velocity`() {
        val macros = mapOf("RISE" to .25f, "WIDTH" to 0f, "GLOW" to .75f, "AIR" to .15f)
        val loud = Keys.openSkyPad(OpenSkyVoice.HALO, macros, 60, timbre = soft, wet = false, loopSeconds = 1.5f)
        val quiet = Keys.openSkyPad(OpenSkyVoice.HALO, macros, 60, velocity = .4f, timbre = soft, wet = false, loopSeconds = 1.5f)
        assertEquals(2, loud.snip.channels)
        assertEquals(44_100, loud.snip.sampleRate)
        assertTrue(loud.loopStartFrame > 0)
        assertTrue(loud.snip.durationSeconds <= OpenSkyKeys.MAX_SAMPLE_SECONDS)
        val start = loud.loopStartFrame.toInt() * 2
        val loopRms = rms(loud.snip.samples, start)
        assertTrue(rms(loud.snip.samples, 0, (OpenSky.RATE * .02).toInt() * 2) < loopRms * .02,
            "key sample starts at settled amplitude instead of the authored onset")
        assertTrue(abs(rms(quiet.snip.samples) / rms(loud.snip.samples) - .4) < .0001,
            "key authoring velocity was replaced by a normalization gain")
        val held = Snip(loud.snip.samples.copyOfRange(start, loud.snip.samples.size), 2, OpenSky.RATE)
        val pitch = requireNotNull(Pitch.detect(held, fromSec = .05f, windowSec = .5f))
        val cents = 1200 * ln(pitch.hz / Keys.midiHz(60).toDouble()) / ln(2.0)
        assertTrue(abs(cents) < 2.0, "native C4 key pitch drifted $cents cents")
        assertTrue(channelSeam(loud, 0) < 1e-6 && channelSeam(loud, 1) < 1e-6)
    }

    @Test
    fun `bright wet halo retains actual attack and validates the complete stereo loop state`() {
        val macros = mapOf("RISE" to .85f, "WIDTH" to .95f, "GLOW" to .9f, "AIR" to .85f)
        val probe = OpenSky.keyProbe(OpenSkyVoice.HALO, macros, 60, .7f, bright, true, 1.5f, { false })
        val note = probe.note
        assertTrue(probe.loopError < 1e-10)
        assertTrue(probe.rawPeak <= 1f)
        assertTrue(note.snip.samples.all { it.isFinite() })
        assertTrue(note.snip.durationSeconds <= OpenSkyKeys.MAX_SAMPLE_SECONDS)
        assertTrue(note.loopStartFrame >= OpenSky.RATE * 6L)
        assertTrue(channelSeam(note, 0) < 1e-6 && channelSeam(note, 1) < 1e-6)
        val start = note.loopStartFrame.toInt()
        val side = FloatArray(note.snip.frameCount - start) { f ->
            note.snip.samples[(f + start) * 2] - note.snip.samples[(f + start) * 2 + 1]
        }
        assertTrue(rms(side) > .01, "native wet key lost WIDTH")
        assertTrue(rms(note.snip.samples, 0, (OpenSky.RATE * .01).toInt() * 2) < .001,
            "wet key starts with a prefilled room instead of a fresh attack")
    }

    @Test
    fun `keys honor cancellation and reject unsafe input before allocating audio`() {
        assertFailsWith<CancellationException> {
            Keys.openSkyPad(OpenSkyVoice.HALO, emptyMap(), 60, cancelled = { true })
        }
        assertFailsWith<IllegalArgumentException> { Keys.openSkyPad(OpenSkyVoice.HALO, emptyMap(), 109) }
        assertFailsWith<IllegalArgumentException> { Keys.openSkyPad(OpenSkyVoice.HALO, emptyMap(), 60, velocity = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { Keys.openSkyPad(OpenSkyVoice.HALO, emptyMap(), 60, loopSeconds = 5f) }
    }
}
