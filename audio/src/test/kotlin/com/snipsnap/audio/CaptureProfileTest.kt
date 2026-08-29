package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaptureProfileTest {

    private val rate = 44_100

    /**
     * A phone mic's rolloff: four cascaded one-pole highpasses at 150 Hz
     * (~24 dB/oct — the acoustic port and the codec highpass stack up
     * steep in real phones; the reference capture kept 0.09% of its
     * sub). A pure-sub synth kick sails through gentler filters with
     * its *shape* intact, which is why the realistic kick below has the
     * knock and click a real drum carries.
     */
    private fun phoneSim(snip: Snip): Snip {
        var out = snip.samples.copyOf()
        repeat(4) {
            val a = Math.exp(-2.0 * Math.PI * 150.0 / snip.sampleRate).toFloat()
            val res = FloatArray(out.size)
            var yPrev = 0f
            var xPrev = 0f
            for (i in out.indices) {
                val y = a * (yPrev + out[i] - xPrev)
                res[i] = y
                yPrev = y
                xPrev = out[i]
            }
            out = res
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }

    /** A kick like the real ones: sub fundamental + mid knock + click. */
    private fun realisticKick(): Snip {
        val n = (0.4f * rate).toInt()
        return Snip(
            FloatArray(n) { i ->
                val t = i.toDouble() / rate
                (
                    0.9 * Math.sin(2.0 * Math.PI * 55.0 * t) * Math.exp(-9.0 * t) +
                        0.2 * Math.sin(2.0 * Math.PI * 220.0 * t) * Math.exp(-25.0 * t) +
                        (if (i < 30) 0.08 * (1.0 - i / 30.0) else 0.0)
                    ).toFloat()
            },
            1, rate,
        )
    }

    private fun beat(): Snip {
        val total = FloatArray(3 * rate)
        for ((at, hit) in listOf(0.05f to realisticKick(), 0.55f to DrumSynth.snare(), 1.05f to DrumSynth.closedHat())) {
            val start = (at * rate).toInt()
            for (i in hit.samples.indices) {
                val idx = start + i
                if (idx < total.size) total[idx] += hit.samples[i] * 0.8f
            }
        }
        return Snip(total, 1, rate)
    }

    @Test
    fun `the profile tells a full-range capture from a rolled-off one`() {
        val full = CaptureProfile.measure(beat())
        assertTrue(!full.rolledOff, "a full-range beat keeps its sub: ${full.subShare}")
        val phone = CaptureProfile.measure(phoneSim(beat()))
        assertTrue(phone.rolledOff, "the phone-sim provably lost it: ${phone.subShare}")

        // The real reference capture is the fixture that taught the rule.
        val ref = WavReader.read(java.io.File("../reference/live3 room take.wav"))
        val real = CaptureProfile.measure(ref)
        assertTrue(real.rolledOff, "the phone capture reads rolled off: ${real.subShare}")
        assertTrue(real.subShare < 0.01f, "spectacularly so: ${real.subShare}")
    }

    @Test
    fun `a gutless kick is a kick again - and only under a rolled-off profile`() {
        val kick = realisticKick()
        val phoneKick = phoneSim(kick)
        val rolled = CaptureProfile.measure(phoneSim(beat()))
        val full = CaptureProfile.measure(beat())

        // Full range, any profile: the normal rule already knows it.
        assertEquals(DrumClass.KICK, Classifier.classify(kick).drumClass)
        assertEquals(DrumClass.KICK, Classifier.classify(kick, full).drumClass)

        // Phone-simmed, no context: the sub evidence is gone and the
        // rules honestly shelve it.
        val blind = Classifier.classify(phoneKick)
        assertTrue(blind.drumClass != DrumClass.KICK, "without context it files as ${blind.drumClass}")

        // Phone-simmed WITH the rolled-off profile: judged by what
        // survived, at honest sub-certain confidence.
        val seen = Classifier.classify(phoneKick, rolled)
        assertEquals(DrumClass.KICK, seen.drumClass)
        assertTrue(seen.confidence in 0.5f..0.7f, "sub-certain, and says so: ${seen.confidence}")
    }

    @Test
    fun `the profile never touches what earned its name`() {
        val rolled = CaptureProfile.measure(phoneSim(beat()))
        for ((name, hit) in listOf(
            "snare" to DrumSynth.snare(), "closedHat" to DrumSynth.closedHat(),
            "openHat" to DrumSynth.openHat(), "clap" to DrumSynth.clap(),
            "tom" to DrumSynth.tom(), "tonal" to DrumSynth.tonal(),
        )) {
            val ph = phoneSim(hit)
            val without = Classifier.classify(ph)
            val with = Classifier.classify(ph, rolled)
            assertEquals(without.drumClass, with.drumClass, "$name unchanged by the profile")
        }
        // And a full-range profile changes nothing at all.
        val full = CaptureProfile.measure(beat())
        for (hit in listOf(realisticKick(), DrumSynth.snare(), DrumSynth.tom())) {
            assertEquals(
                Classifier.classify(hit).drumClass,
                Classifier.classify(hit, full).drumClass,
                "full-range context is a no-op",
            )
        }
    }
}
