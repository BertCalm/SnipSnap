package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The note as the accepted goldens hash it: everything [Ferry.render] does except the onset ease. */
internal fun FerryPatch.acceptedBody(): FloatArray = Ferry.body(voice, macros, midi, velocity, seed, model)

/**
 * The owner heard a click at the start of every FERRY note (20 Nov 2026). Every mode
 * began at its largest displacement, so the output stepped from silence to the note's
 * peak in two samples. These tests bound the start and prove the rest is untouched.
 */
class FerryOnsetTest {
    private val cases = listOf(36, 48, 60, 72).flatMap { midi -> listOf(.3f, 1f).map { v -> FerryPatch("onset", midi = midi, velocity = v) } } +
        listOf(0f, 1f).map { FerryPatch("onset", macros = Ferry.defaults() + ("LOSS" to it)) } +
        (1..3).map { FerryPatch("onset", macros = Ferry.defaults(model = it), model = it) }

    @Test
    fun `no step at the start - the note opens from zero and never moves faster than it does later`() {
        for (patch in cases) {
            val x = patch.render().samples
            val body = patch.acceptedBody()
            val peak = x.maxOf { abs(it) }
            fun jump(a: FloatArray, from: Int, to: Int) = (from until to).maxOf { abs(a[it] - (if (it == 0) 0f else a[it - 1])) } / peak
            val early = (.005f * Dsp.RATE).toInt()
            val opening = (0..3).maxOf { abs(x[it]) } / peak
            val now = jump(x, 0, early)
            val later = jump(x, early, Dsp.RATE / 10)
            val was = jump(body, 0, early)
            println("FERRY onset midi=${patch.midi} v=${patch.velocity} model=${patch.model}: first four samples ${"%.5f".format(opening)} of peak, " +
                "largest jump in 5 ms ${"%.4f".format(now)} (was ${"%.3f".format(was)}), largest later jump ${"%.4f".format(later)}")
            assertEquals(0f, x[0], "$patch must start from silence")
            assertTrue(opening < MAX_OPENING, "$patch opens with a step: $opening of peak")
            assertTrue(now <= later * 1.05f, "$patch moves faster in its first 5 ms ($now) than anywhere in the next 95 ms ($later)")
            assertTrue(was > .3f && abs(body[2]) / peak > .3f, "the un-eased body is expected to step, or this bound tests nothing: $was")
        }
    }

    @Test
    fun `the ease is the only change - every sample after three milliseconds is the accepted note bit for bit`() {
        val n = (Ferry.ONSET_SECONDS * Dsp.RATE).toInt()
        assertEquals(132, n)
        for (patch in cases) {
            val x = patch.render().samples
            val body = patch.acceptedBody()
            assertEquals(body.size, x.size)
            for (i in n until x.size) assertEquals(body[i].toRawBits(), x[i].toRawBits(), "$patch changed after the onset at $i")
            for (i in 0 until n) assertEquals((body[i] * (.5 - .5 * cos(PI * i / n)).toFloat()).toRawBits(), x[i].toRawBits(), "$patch onset contour at $i")
        }
    }

    @Test
    fun `the ease costs the loudest window a fraction of a decibel and nothing else`() {
        var worst = 1f
        for (midi in listOf(36, 48, 60, 72)) for (handoff in listOf(.25f, .5f, 1f)) for (loss in listOf(0f, .5f, 1f)) {
            val macros = mapOf("HANDOFF" to handoff, "LOSS" to loss)
            val ratio = Loudness.of(Ferry.render(macros = macros, midi = midi)) / Loudness.of(Ferry.body(macros = macros, midi = midi), 1, Dsp.RATE)
            if (ratio < worst) { worst = ratio; println("FERRY onset loudness midi=$midi handoff=$handoff loss=$loss: eased/body $ratio (${"%.2f".format(20 * kotlin.math.log10(ratio))} dB)") }
            assertTrue(ratio <= 1.0001f, "the ease must not add level: $ratio")
        }
        println("FERRY onset loudness worst eased/body $worst")
        assertTrue(worst >= MIN_EASED_LOUDNESS, "the ease removed more than the click: $worst")
    }

    companion object {
        /** The eased note's loudest-window loudness as a share of the accepted body's: the click's energy and no more. */
        const val MIN_EASED_LOUDNESS = .95f

        /** The first four samples (91 microseconds) stay under 0.3 percent of the note's peak; they were up to 100 percent. */
        private const val MAX_OPENING = .003f
    }
}
