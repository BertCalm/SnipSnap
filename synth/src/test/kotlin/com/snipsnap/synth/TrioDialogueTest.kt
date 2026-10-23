package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Timing and energy-envelope contracts; instrumental identity still requires listening. */
class TrioDialogueTest {
    private val wives = setOf(TrioActor.TRES, TrioActor.BERIMBAU, TrioActor.CHARANGO)
    private val husbands = listOf(TrioActor.OUD, TrioActor.BOUZOUKI, TrioActor.PHIN)
    private val baseline = mapOf("TOUCH" to .55f, "SIDES" to (1f / 6f), "MOTION" to 0f,
        "DRAMA" to 1f, "SPACE" to .35f, "DAMP" to .48f)
    private val published = TrioConfig(seed = 47010, model = 4)
    private val dialogue = published.copy(model = 5)

    private fun scene(drama: Float, config: TrioConfig = dialogue,
        macros: Map<String, Float> = baseline, midi: Int = 60, velocity: Float = 1f) =
        Trio.renderProbe(TrioVoice.ENSEMBLE, macros + ("DRAMA" to drama), midi, velocity, config)

    private fun hash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(bytes::putFloat)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** Sum per-source window energy before the square root, avoiding inter-player phase cancellation. */
    private fun husbandEnvelope(probe: TrioProbe): DoubleArray {
        val block = (Dsp.RATE * .02f).toInt()
        return DoubleArray((probe.samples.size + block - 1) / block) { window ->
            val from = window * block
            val to = minOf(from + block, probe.samples.size)
            var sum = 0.0
            for (actor in husbands) {
                val tap = probe.actors.getValue(actor)
                for (i in from until to) sum += tap[i].toDouble() * tap[i]
            }
            sqrt(sum / (to - from))
        }
    }

    private fun envelopeDistance(a: DoubleArray, b: DoubleArray): Double {
        val an = sqrt(a.sumOf { it * it }); val bn = sqrt(b.sumOf { it * it })
        return sqrt(a.indices.sumOf { val d = a[it] / an - b[it] / bn; d * d })
    }

    private fun centroid(envelope: DoubleArray): Double =
        envelope.indices.sumOf { (it + .5) * .02 * envelope[it] * envelope[it] } /
            envelope.sumOf { it * it }

    private fun firstAttack(samples: FloatArray): Float {
        val peak = samples.maxOf { abs(it) }
        assertTrue(peak > 0f && peak.isFinite(), "source has no finite attack")
        return samples.indexOfFirst { abs(it) > peak * .001f }.toFloat() / Dsp.RATE
    }

    private fun meanSquare(samples: FloatArray, fromSeconds: Float, toSeconds: Float): Double {
        val from = (fromSeconds * Dsp.RATE).toInt().coerceIn(0, samples.lastIndex)
        val to = (toSeconds * Dsp.RATE).toInt().coerceIn(from + 1, samples.size)
        var sum = 0.0
        for (i in from until to) sum += samples[i].toDouble() * samples[i]
        return sum / (to - from)
    }

    private fun retainedTail(probe: TrioProbe, actor: TrioActor): Double {
        val onset = probe.events.single { it.actor == actor }.timeSeconds
        val tap = probe.actors.getValue(actor)
        val attack = meanSquare(tap, onset + .03f, onset + .18f)
        assertTrue(attack > 0.0)
        return meanSquare(tap, onset + .7f, onset + .95f) / attack
    }

    @Test
    fun `saved model four audio remains exact for the published positive range`() {
        // Native float hashes from archived round trio-284b4973adfd-eae7cad1f3fe.
        val canaries = mapOf(
            .25f to "531aeef2d03f9b70b4787c79620e4ecfddf569a781c5aeb623db8f92e2dd1f90",
            .5f to "644386f76fd6dc8f535d2369b1ae328b20a6b53ec7429e0b71f1b4fe5394a847",
            .7f to "b1f0223e7d0fcbd9f59bff55d805091913ec60d508023e99c2b069d14fc587bc",
            .85f to "f826a2a84ecc1accc422660150d3d14266e71f989b76d6862c2646f3d45f9356",
            1f to "e3391a097e6279decd005948e0cb66ca5f2cb425085001091e0d1c37a3588c7d",
        )
        for ((drama, expected) in canaries) {
            val patch = TrioPatch("Earlier Entrance", TrioVoice.ENSEMBLE,
                baseline + ("DRAMA" to drama), config = published)
            val restored = assertIs<TrioPatch>(PadRecipe.fromJsonText(PadRecipe(patch).toJsonText()).patch)
            assertEquals(published, restored.config)
            assertEquals(expected, hash(scene(drama, restored.config, restored.macros).samples),
                "saved model 4 at DRAMA $drama changed")
        }
    }

    @Test
    fun `wife sources and gestures remain exact with fixed and moving alliances`() {
        val rich = baseline + mapOf("TOUCH" to .72f, "SIDES" to .42f,
            "MOTION" to .7f, "SPACE" to .8f, "DAMP" to .3f)
        val moving = published.copy(seed = -91827, tempoBpm = 140f, beats = 3,
            press = .8f, tremoloHz = 17f, strumMs = 23f)
        for ((macros, config) in listOf(baseline to published, rich to moving)) {
            for (drama in listOf(0f, .25f, .5f, .75f, 1f)) {
                val old = scene(drama, config, macros, 57, .7f)
                val revised = scene(drama, config.copy(model = 5), macros, 57, .7f)
                for (actor in wives) {
                    assertContentEquals(old.actors.getValue(actor), revised.actors.getValue(actor),
                        "$actor source changed at DRAMA $drama, config $config")
                    assertEquals(old.events.filter { it.actor == actor }, revised.events.filter { it.actor == actor },
                        "$actor gestures changed at DRAMA $drama, config $config")
                }
            }
        }
    }

    @Test
    fun `zero baseline and the accepted full scene remain exact`() {
        val rich = baseline + mapOf("SIDES" to .42f, "MOTION" to .6f)
        for (macros in listOf(baseline, rich)) for (drama in listOf(0f, 1f)) {
            val old = scene(drama, published, macros)
            val revised = scene(drama, dialogue, macros)
            assertContentEquals(old.samples, revised.samples, "DRAMA $drama endpoint mix changed")
            assertEquals(old.events, revised.events, "DRAMA $drama endpoint score changed")
            for (actor in old.actors.keys) {
                assertContentEquals(old.actors.getValue(actor), revised.actors.getValue(actor))
            }
            val controls = macros + ("DRAMA" to drama)
            assertContentEquals(Trio.render(TrioVoice.ENSEMBLE, controls, config = published).samples,
                Trio.render(TrioVoice.ENSEMBLE, controls, config = dialogue).samples,
                "DRAMA $drama endpoint final leveling changed")
        }
    }

    @Test
    fun `one strike per husband moves earlier and closer across short default and long phrases`() {
        val configs = listOf(dialogue.copy(tempoBpm = 200f, beats = 1), dialogue,
            dialogue.copy(tempoBpm = 60f, beats = 2), dialogue.copy(tempoBpm = 200f, beats = 4))
        for (config in configs) {
            var previousTimes: List<Float>? = null
            for (drama in listOf(.001f, .01f, .1f, .25f, .5f, .75f, 1f)) {
                val probe = scene(drama, config)
                val times = husbands.map { actor ->
                    val event = probe.events.single { it.actor == actor }
                    assertTrue(event.energy.isFinite() && event.energy > 0f)
                    // Two shared decimation kernels span less than .3 ms; allow
                    // 2 ms for their pre-ring and the exciter's initial rise.
                    assertTrue(abs(firstAttack(probe.actors.getValue(actor)) - event.timeSeconds) < .002f,
                        "$actor audio attack did not follow its trace at DRAMA $drama")
                    event.timeSeconds
                }
                assertTrue(times.zipWithNext().all { (a, b) -> a < b }, "husband answer order changed")
                assertTrue(times.last() <= 1.90001f && times.last() + 1f < probe.samples.size.toFloat() / Dsp.RATE,
                    "late answer leaves less than one second to ring")
                previousTimes?.let { before ->
                    assertTrue(times.indices.all { times[it] < before[it] }, "an entrance stalls or moves later")
                    assertTrue(times.zipWithNext().zip(before.zipWithNext()).all { (a, b) ->
                        a.second - a.first < b.second - b.first
                    }, "answer gaps grow at DRAMA $drama in config $config")
                    assertTrue(times.last() - times.first() < before.last() - before.first(),
                        "total answer spread grows at DRAMA $drama in config $config")
                }
                previousTimes = times
            }
        }
    }

    @Test
    fun `nonzero drama changes normalized husband envelope structure beyond the previous clustered arc`() {
        val values = listOf(.25f, .5f, .75f, 1f)
        val old = values.map { husbandEnvelope(scene(it, published)) }
        val revised = values.map { husbandEnvelope(scene(it)) }
        for (i in 0 until values.lastIndex) {
            val previousDistance = envelopeDistance(old[i], old[i + 1])
            val distance = envelopeDistance(revised[i], revised[i + 1])
            val earlier = centroid(revised[i]); val later = centroid(revised[i + 1])
            println("TRIO dialogue ${values[i]}..${values[i+1]} envelope=$distance previous=$previousDistance centroids=$earlier->$later")
            // Model 4's fixed-scene distances are .409/.350/.314. Require added
            // temporal structure after removing gain, rather than a waveform phase change.
            assertTrue(distance > previousDistance + .2,
                "DRAMA ${values[i]}..${values[i+1]} retains the previous clustered envelope: $distance")
            assertTrue(later < earlier, "the rendered husband energy does not move earlier")
        }
    }

    @Test
    fun `late answers retain a natural DAMP controlled ring after their single strike`() {
        val normal = scene(.25f)
        val open = scene(.25f, macros = baseline + ("DAMP" to 0f))
        val damped = scene(.25f, macros = baseline + ("DAMP" to 1f))
        for (actor in husbands) {
            val retained = retainedTail(normal, actor)
            assertTrue(retained.isFinite() && retained > 0.0 && retained < 1.0,
                "$actor does not ring and decay after its late strike: $retained")
            assertEquals(open.events.filter { it.actor == actor }, damped.events.filter { it.actor == actor })
            val openTail = retainedTail(open, actor); val dampedTail = retainedTail(damped, actor)
            assertTrue(openTail.isFinite() && dampedTail.isFinite() && openTail > dampedTail,
                "$actor DAMP does not shorten the late ring: $openTail versus $dampedTail")
        }
    }

    @Test
    fun `lower and middle dialogues remain bounded and settle at both register limits`() {
        val dense = dialogue.copy(tempoBpm = 200f, beats = 4, press = 1f,
            tremoloHz = 25f, strumMs = 30f)
        val controls = baseline + mapOf("TOUCH" to 1f, "SIDES" to .42f,
            "MOTION" to 1f, "SPACE" to 1f, "DAMP" to 0f)
        for (midi in listOf(Trio.MIN_MIDI, Trio.MAX_MIDI)) for (drama in listOf(.25f, .5f)) {
            val probe = scene(drama, dense, controls, midi)
            assertTrue(probe.samples.all { it.isFinite() } && probe.samples.maxOf { abs(it) } < .99f,
                "dialogue at MIDI $midi / DRAMA $drama exceeds raw headroom")
            val lastOnset = husbands.maxOf { actor -> probe.events.single { it.actor == actor }.timeSeconds }
            for (actor in husbands) assertTrue(probe.actors.getValue(actor).any { it != 0f })
            val wanted = Keys.midiHz(midi)
            val measured = FineTuning.measuredHz(probe.samples, Dsp.RATE, wanted,
                fromSec = lastOnset + .25f, bodySeconds = .45f)
            assertTrue(abs(FineTuning.cents(measured, wanted.toDouble())) < 25,
                "dialogue at MIDI $midi / DRAMA $drama loses its settled root")
        }
    }
}
