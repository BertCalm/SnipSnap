package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Corolla's mechanical claims are measured before the output limiter and loudness process. */
class CorollaTest {
    private val acousticRate = Dsp.RATE * 4
    private val names = listOf("TUNE", "PULL", "BLOOM", "FIELD", "CONTACT", "CHAMBER", "HOLD")

    private fun rms(x: FloatArray, from: Int = 0, until: Int = x.size): Double {
        var energy = 0.0
        for (i in from until until) energy += x[i].toDouble() * x[i]
        return sqrt(energy / (until - from).coerceAtLeast(1))
    }

    private fun mean(x: FloatArray, from: Int, until: Int): Double {
        var sum = 0.0
        for (i in from until until) sum += x[i]
        return sum / (until - from)
    }

    /** Shape distance after matching RMS: a macro cannot pass by changing output gain alone. */
    private fun shapeDistance(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        val ra = rms(a, until = n).coerceAtLeast(1e-12)
        val rb = rms(b, until = n).coerceAtLeast(1e-12)
        var sum = 0.0
        for (i in 0 until n) {
            val d = a[i] / ra - b[i] / rb
            sum += d * d
        }
        return sqrt(sum / n)
    }

    @Test
    fun `six voices share the documented macro and pitch contracts`() {
        assertEquals(listOf("TONGUE", "BLOSSOM", "CHOIR", "CHATTER", "ORBIT", "HUSK"), CorollaVoice.entries.map { it.name })
        val defaults = listOf(
            listOf(.45f, .25f, .10f, .10f, .30f),
            listOf(.60f, .70f, .25f, .20f, .55f),
            listOf(.30f, .55f, .55f, .10f, .65f),
            listOf(.60f, .35f, .45f, .75f, .40f),
            listOf(.35f, .60f, .80f, .10f, .40f),
            listOf(.40f, .20f, .30f, .30f, .85f),
        )
        for ((i, voice) in CorollaVoice.entries.withIndex()) {
            assertEquals(names, Corolla.macrosFor(voice).map { it.name }, "$voice macro contract")
            assertEquals(defaults[i], names.drop(1).dropLast(1).map { Corolla.defaults(voice).getValue(it) }, "$voice defaults")
            assertEquals(0f, Corolla.defaults(voice).getValue("HOLD"))
            assertEquals(48, Corolla.rootMidi(voice))
            assertEquals(25, (0..100).map { Corolla.midiFor(voice, it / 100f) }.toSet().size)
            assertEquals(48, Corolla.midiFor(voice, 0f))
            assertEquals(72, Corolla.midiFor(voice, 1f))
        }
    }

    @Test
    fun `all voices and supported corners produce finite bounded levelled audio`() {
        for (voice in CorollaVoice.entries) {
            for (macros in listOf(
                emptyMap(),
                names.associateWith { 0f },
                names.associateWith { 1f } + ("HOLD" to 0f),
            )) {
                val snip = Corolla.render(voice, macros)
                assertEquals(Dsp.RATE, snip.sampleRate)
                assertEquals(1, snip.channels)
                assertTrue(snip.durationSeconds in 2.2f..14.01f, "$voice duration ${snip.durationSeconds} at $macros")
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice invalid samples at $macros")
                assertTrue(abs(snip.samples.average()) < .02, "$voice DC offset at $macros")
                val loudness = Loudness.of(snip)
                assertTrue(loudness >= Dsp.MELODIC_LOUDNESS_TARGET * .9f || snip.peak() >= .95f,
                    "$voice under level: $loudness, peak ${snip.peak()}, $macros")
            }
        }
    }

    @Test
    fun `render inputs and scramble seeds reproduce the same samples and parameters`() {
        for (voice in CorollaVoice.entries) {
            val macros = mapOf("CONTACT" to .62f, "BLOOM" to .42f)
            assertContentEquals(Corolla.render(voice, macros, velocity = .7f).samples,
                Corolla.render(voice, macros, velocity = .7f).samples, "$voice determinism")
            val roll = Corolla.scramble(voice, Random(52))
            assertEquals(roll, Corolla.scramble(voice, Random(52)))
            assertEquals(Corolla.defaults(voice).keys, roll.keys)
            assertTrue(roll.values.all { it.isFinite() && it in 0f..1f })
        }
    }

    @Test
    fun `passive coupled petals and chamber lose stored energy after release`() {
        for (voice in CorollaVoice.entries) {
            val played = Corolla.play(voice, mapOf("FIELD" to 0f, "CONTACT" to 0f, "CHAMBER" to .9f),
                probe = Corolla.Probe(record = true, powered = false), seconds = 1.8f)
            val taps = requireNotNull(played.taps)
            assertEquals(played.raw.size, taps.energy.size)
            assertTrue(taps.energy.all { it.isFinite() && it >= 0f }, "$voice invalid stored energy")
            assertTrue(taps.poweredInput.all { it == 0f }, "$voice passive input supplies power")
            val win = (acousticRate * .05f).toInt()
            val start = (acousticRate * .12f).toInt()
            var previous = mean(taps.energy, start, start + win)
            val initial = previous
            assertTrue(initial > 1e-10, "$voice passive test has no energy")
            var at = start + win
            while (at + win <= taps.energy.size) {
                val next = mean(taps.energy, at, at + win)
                assertTrue(next <= previous * 1.002 + 1e-10, "$voice passive energy grows at ${at / acousticRate.toFloat()}s: $previous -> $next")
                previous = next
                at += win
            }
            assertTrue(previous < initial * .75, "$voice passive tail does not decay: $initial -> $previous")
            println("COROLLA PASSIVE $voice: raw peak ${played.raw.maxOf { abs(it) }}, energy $initial -> $previous")
        }
    }

    @Test
    fun `neighbors respond to coupling and do not receive a duplicate initial pull`() {
        val macros = mapOf("FIELD" to 0f, "CONTACT" to 0f, "PULL" to .7f)
        val isolated = requireNotNull(Corolla.play(CorollaVoice.TONGUE, macros,
            probe = Corolla.Probe(record = true, coupling = false, chamber = false, powered = false), seconds = .8f).taps)
        val linked = requireNotNull(Corolla.play(CorollaVoice.TONGUE, macros,
            probe = Corolla.Probe(record = true, chamber = false, powered = false), seconds = .8f).taps)
        assertTrue(rms(isolated.direct) > 1e-5, "isolated direct petal is silent")
        assertTrue(isolated.neighbors.all { it == 0f }, "an unlinked neighbor was struck directly")
        assertTrue(rms(linked.neighbors) > rms(linked.direct) * 1e-4, "coupling transfers no audible energy")
        assertTrue(isolated.chamber.all { it == 0f }, "disabled chamber still sounds")
    }

    @Test
    fun `a stronger passive gesture changes bounded lagging blossom motion`() {
        fun opening(pull: Float): FloatArray = requireNotNull(Corolla.play(CorollaVoice.BLOSSOM,
            mapOf("PULL" to pull, "BLOOM" to .4f, "FIELD" to 0f, "CONTACT" to 0f),
            probe = Corolla.Probe(record = true, powered = false), seconds = 3f).taps).opening
        val soft = opening(.1f)
        val firm = opening(.9f)
        assertTrue((soft.asSequence() + firm.asSequence()).all { it.isFinite() && it in 0f..1f })
        val softTravel = soft.max() - soft.first()
        val firmTravel = firm.max() - firm.first()
        assertTrue(firmTravel > .005f && firmTravel > softTravel * 1.15f,
            "opening ignores event energy: soft $softTravel, firm $firmTravel")
        val attackEnd = (acousticRate * .02f).toInt()
        assertTrue(firm.take(attackEnd).max() < firm.max() - .001f, "blossom has no lag behind the pull")
        assertTrue(firm.last() < firm.max() - .001f, "blossom does not fold as passive energy fades")
    }

    @Test
    fun `powered field excites the resonator network without an initial pull`() {
        val macros = mapOf("FIELD" to .8f, "CONTACT" to 0f, "HOLD" to 0f)
        val active = Corolla.play(CorollaVoice.ORBIT, macros,
            probe = Corolla.Probe(record = true, pull = false), seconds = 1.3f)
        val passive = Corolla.play(CorollaVoice.ORBIT, macros,
            probe = Corolla.Probe(record = true, pull = false, powered = false), seconds = 1.3f)
        val taps = requireNotNull(active.taps)
        assertTrue(rms(taps.poweredInput) > 1e-8, "field supplies no measured input")
        assertTrue(rms(taps.direct) > 1e-5, "field does not excite pitched modes")
        assertTrue(rms(active.raw) > 1e-5)
        assertTrue(passive.raw.all { it == 0f }, "passive zero-state object generates sound")
        assertTrue(taps.energy.all { it.isFinite() && it >= 0f }, "powered resonator energy is invalid")
    }

    @Test
    fun `event velocity changes stored gesture energy before loudness matching`() {
        fun energy(velocity: Float): Double {
            val taps = requireNotNull(Corolla.play(CorollaVoice.TONGUE,
                mapOf("FIELD" to 0f, "CONTACT" to 0f), velocity = velocity,
                probe = Corolla.Probe(record = true, powered = false), seconds = .4f).taps)
            return mean(taps.energy, (acousticRate * .1f).toInt(), (acousticRate * .2f).toInt())
        }
        val soft = energy(.25f)
        val firm = energy(.9f)
        assertTrue(soft > 0 && firm > soft * 1.5, "velocity ignores gesture energy: $soft -> $firm")
    }

    @Test
    fun `zero velocity produces exact silence for finite gestures and held buffers`() {
        for (hold in listOf(0f, 1f)) {
            val snip = Corolla.render(CorollaVoice.CHATTER,
                mapOf("FIELD" to 1f, "CONTACT" to 1f, "HOLD" to hold), velocity = 0f)
            assertTrue(snip.frameCount > 0)
            assertTrue(snip.samples.all { it == 0f }, "zero velocity at HOLD $hold generates sound")
        }
    }

    @Test
    fun `long powered contact extremes remain bounded before output processing`() {
        for (voice in listOf(CorollaVoice.CHATTER, CorollaVoice.ORBIT, CorollaVoice.HUSK)) {
            val begin = System.nanoTime()
            val played = Corolla.play(voice,
                mapOf("PULL" to 1f, "FIELD" to 1f, "CONTACT" to 1f, "BLOOM" to 0f, "CHAMBER" to 1f),
                probe = Corolla.Probe(record = true), seconds = 6f)
            assertTrue(played.raw.all { it.isFinite() && abs(it) < 10f }, "$voice unprocessed runaway")
            val taps = requireNotNull(played.taps)
            assertTrue(taps.energy.all { it.isFinite() && it >= 0f })
            assertTrue(taps.opening.all { it.isFinite() && it in 0f..1f })
            println("COROLLA POWERED $voice: raw peak ${played.raw.maxOf { abs(it) }}, RMS ${rms(played.raw)}, energy peak ${taps.energy.max()}, cost ${(System.nanoTime() - begin) / 1e9}s for 6s audio")
        }
    }

    @Test
    fun `contact zero removes collisions while firm contact produces measured chatter`() {
        fun play(contact: Float) = Corolla.play(CorollaVoice.CHATTER,
            mapOf("PULL" to .85f, "BLOOM" to .15f, "FIELD" to .6f, "CONTACT" to contact),
            probe = Corolla.Probe(record = true), seconds = 1f)
        val clear = play(0f)
        val touching = play(.9f)
        assertTrue(requireNotNull(clear.taps).contact.all { it == 0f }, "CONTACT 0 collides")
        assertTrue(rms(requireNotNull(touching.taps).contact) > 1e-7, "CONTACT .9 generates no collision activity")
        assertTrue(shapeDistance(clear.raw, touching.raw) > .02, "contact changes no audible resonator behavior")
    }

    @Test
    fun `all timbral macros change the sound of every voice over a useful sweep`() {
        for (voice in CorollaVoice.entries) for (macro in names.drop(1).dropLast(1)) {
            val base = mapOf("PULL" to .6f, "BLOOM" to .4f, "FIELD" to .45f, "CONTACT" to .5f, "CHAMBER" to .5f)
            val low = Corolla.play(voice, base + (macro to .1f), seconds = .8f).raw
            val high = Corolla.play(voice, base + (macro to .9f), seconds = .8f).raw
            val distance = shapeDistance(low, high)
            assertTrue(distance > .02, "$voice $macro changes only gain or is inactive: shape distance $distance")
        }
    }

    @Test
    fun `moderate coupled voices preserve the requested root within ten cents`() {
        for (voice in CorollaVoice.entries) for (tune in listOf(0f, .5f, 1f)) {
            val macros = mapOf("TUNE" to tune, "PULL" to .45f, "BLOOM" to .5f,
                "FIELD" to .3f, "CONTACT" to .2f, "CHAMBER" to .45f)
            val snip = Corolla.render(voice, macros)
            val wanted = Corolla.frequencyFor(voice, tune)
            val measured = FineTuning.measuredHz(snip, wanted, fromSec = .12f, bodySeconds = .5f)
            val cents = FineTuning.cents(measured, wanted.toDouble())
            println("COROLLA ROOT $voice TUNE $tune: $cents cents")
            assertTrue(abs(cents) <= 10.0, "$voice TUNE $tune: root $measured Hz, expected $wanted, $cents cents")
            assertTrue(PluckSpectra.fundamentalShare(snip, wanted) > .3,
                "$voice TUNE $tune: upper petals conceal the requested root")
        }
    }

    @Test
    fun `held loops retain pitched sound and pass both state seam and audible wrap checks`() {
        val cases = CorollaVoice.entries.map { it to emptyMap<String, Float>() } + listOf(
            CorollaVoice.CHOIR to mapOf("FIELD" to 0f),
            CorollaVoice.TONGUE to mapOf("TUNE" to 0f, "FIELD" to 0f),
            CorollaVoice.TONGUE to mapOf("TUNE" to 1f, "FIELD" to 0f),
            CorollaVoice.ORBIT to mapOf("TUNE" to 1f, "FIELD" to 1f, "CONTACT" to .9f),
            CorollaVoice.HUSK to mapOf("TUNE" to 0f, "BLOOM" to 0f, "CHAMBER" to 1f),
        )
        for ((voice, extra) in cases) {
            val macros = extra + ("HOLD" to 1f)
            val (buffer, start) = Corolla.loopBuffer(voice, macros)
            assertTrue(Keys.seamError(buffer, start) < Keys.MAX_SEAM_ERROR, "$voice $extra: sustain state does not repeat")
            val loop = Corolla.renderLoop(voice, macros)
            assertTrue(loop.size > 256 && loop.all { it.isFinite() && it in -1f..1f })
            assertTrue(rms(loop) > .03, "$voice $extra: silent held buffer")
            assertTrue(abs(loop.average()) < .02, "$voice $extra: held DC offset")
            var steepest = 0.0
            var largestKink = 0.0
            for (i in 1 until loop.size) steepest = maxOf(steepest, abs(loop[i] - loop[i - 1]).toDouble())
            for (i in 2 until loop.size) largestKink = maxOf(largestKink,
                abs((loop[i] - loop[i - 1]) - (loop[i - 1] - loop[i - 2])).toDouble())
            val wrap = (loop.first() - loop.last()).toDouble()
            val before = (loop.last() - loop[loop.lastIndex - 1]).toDouble()
            val after = (loop[1] - loop.first()).toDouble()
            assertTrue(abs(wrap) <= steepest * 1.01 + 1e-7, "$voice $extra: wrap jumps beyond the signal's own steps")
            assertTrue(maxOf(abs(wrap - before), abs(after - wrap)) <= largestKink * 1.05 + 1e-7,
                "$voice $extra: wrap has a sudden change in slope")
            if (extra.isEmpty() || extra.keys.all { it == "FIELD" || it == "TUNE" }) {
                val wanted = Corolla.frequencyFor(voice, extra["TUNE"] ?: Corolla.defaults(voice).getValue("TUNE"))
                val snip = Snip(loop, channels = 1, sampleRate = Dsp.RATE)
                val rootEnergy = PluckSpectra.toneEnergy(snip, wanted)
                val upperEnergy = listOf(2f, 2.7f, 3f, 4f, 5f, 5.4f, 6f)
                    .sumOf { PluckSpectra.toneEnergy(snip, wanted * it) }
                assertTrue(rootEnergy > upperEnergy * .3,
                    "$voice $extra: held upper petals conceal the requested root ($rootEnergy against $upperEnergy)")
                val hz = FineTuning.measuredHz(loop, Dsp.RATE, wanted, fromSec = .1f, bodySeconds = .5f)
                println("COROLLA LOOP $voice $extra: seam ${Keys.seamError(buffer, start)}, cents ${FineTuning.cents(hz, wanted.toDouble())}, root share ${rootEnergy / upperEnergy}")
                assertTrue(abs(FineTuning.cents(hz, wanted.toDouble())) <= 10.0,
                    "$voice $extra: settled loop root $hz Hz, expected $wanted")
            }
            assertContentEquals(loop, Corolla.render(voice, macros).samples, "$voice HOLD dispatch")
        }
    }

    @Test
    fun `patches preserve every parameter and reject undeclared or invalid macros`() {
        for (voice in CorollaVoice.entries) {
            val macros = names.mapIndexed { i, name -> name to i / (names.size - 1f) }.toMap()
            val patch = CorollaPatch("PETAL TEST", voice, macros)
            val restored = Patches.fromJsonText(patch.toJsonText())
            assertEquals(patch, restored)
            assertContentEquals(patch.render().samples, restored.render().samples)
        }
        for (bad in listOf(mapOf("REVERB" to .5f), mapOf("PULL" to -1f), mapOf("FIELD" to Float.NaN))) {
            assertFailsWith<IllegalArgumentException> { CorollaPatch("BAD", CorollaVoice.TONGUE, bad) }
        }
    }

    @Test
    fun `factory presets obey release names preserve parameters and route away from drum choke groups`() {
        val presets = CorollaPresets.all()
        assertTrue(presets.isNotEmpty())
        val drumClasses = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
            DrumClass.HAT_OPEN, DrumClass.HAT_CLOSED, DrumClass.TOM)
        for (voice in CorollaVoice.entries) {
            val group = CorollaPresets.forVoice(voice)
            assertTrue(group.size >= 2, "$voice has no useful factory choice")
            assertEquals(group.size, group.map { it.name }.toSet().size, "$voice duplicate preset names")
            for ((i, a) in group.withIndex()) for (b in group.drop(i + 1)) {
                assertTrue(PresetTestSupport.rmsDistance(a.macros, b.macros) > .05f, "$voice ${a.name} and ${b.name} are effectively the same preset")
            }
            assertEquals(group, Presets.forVoice("COROLLA", voice.name))
        }
        for (patch in presets) {
            assertEquals(patch.name.uppercase(), patch.name)
            assertTrue(patch.name.length <= 14, "${patch.name}: name exceeds release length")
            assertTrue(!PresetTestSupport.trademarkBlocklist.containsMatchIn(patch.name), "${patch.name}: maker reference")
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            val filed = if (Corolla.isLoop(patch.macros.getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL
            assertEquals(filed, Corolla.drumClassFor(patch.voice, patch.macros))
            val heard = Classifier.classify(patch.render()).drumClass
            assertTrue(heard !in drumClasses, "${patch.name} is classified as a choking drum: $heard")
        }
        assertTrue(Presets.all().containsAll(presets))
    }
}
