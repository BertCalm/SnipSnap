package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.PitchEstimate
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Native-note regression through the public renderer and actual delivered PCM16.
 * Pitch.detect chooses a period without being told the desired note: a tiny spectral
 * peak near the requested root must not hide a louder frozen third or octave.
 */
class OhmV4PitchTest {
    private data class Window(val exciter: OhmExciter, val midi: Int, val commune: Float, val from: Float)
    private data class Variant(val name: String, val grains: Float, val freeze: Float)

    private val variants = listOf(
        Variant("grains .6", .6f, 0f), Variant("grains 1", 1f, 0f), Variant("freeze .75", 0f, .75f),
        Variant("freeze 1", 0f, 1f), Variant("combined .6/.75", .6f, .75f),
    )
    private val windows = listOf(.5f, 1.25f, 2.5f)

    // Independently measured on the unchanged model-3/OFF source at fde661771.
    // Track these existing late octave selections separately; do not bless a new
    // off-target window, a lower-confidence note, or a move farther from its key.
    private val inherited = setOf(
        Window(OhmExciter.PLUCK, 72, 1f, 2.5f),
        Window(OhmExciter.CAPTURED, 60, 1f, 2.5f),
        Window(OhmExciter.CAPTURED, 72, 1f, 2.5f),
    )

    @Test
    fun `real source memory does not replace native early or late notes with a new harmonic`() {
        val failures = mutableListOf<String>()
        val disclosed = mutableSetOf<String>()
        val capture = OhmCapture(FloatArray(11_025) { i ->
            (.35 * sin(2 * PI * 330 * i / 44_100) * (1 - i / 11_025.0)).toFloat()
        }, "Independent 330 Hz contact")
        for (exciter in listOf(OhmExciter.PLUCK, OhmExciter.CAPTURED)) {
            for (midi in listOf(48, 60, 72)) for (commune in listOf(0f, 1f)) {
                val options = OhmOptions(exciter = exciter,
                    capture = capture.takeIf { exciter == OhmExciter.CAPTURED })
                val baseline = rendered(options, midi, commune)
                val before = windows.associateWith { Pitch.detect(baseline, it, .4f) }
                for (from in windows) {
                    val key = Window(exciter, midi, commune, from)
                    val pitch = before.getValue(from)
                    if (pitch == null || pitch.confidence < .75f) {
                        failures += "$key OFF no longer supplies a confident period: $pitch"
                    } else if (abs(cents(pitch, midi)) > 20) {
                        if (key !in inherited) failures += "$key has a new OFF pitch failure: ${cents(pitch, midi)} cents"
                        disclosed += "$key OFF ${cents(pitch, midi)} cents, confidence ${pitch.confidence}"
                    }
                }
                for (variant in variants) {
                    val note = rendered(options.copy(grains = variant.grains, freeze = variant.freeze), midi, commune)
                    for (from in windows) {
                        val key = Window(exciter, midi, commune, from)
                        val off = before.getValue(from)
                        val on = Pitch.detect(note, from, .4f)
                        if (on == null || on.confidence < .75f) {
                            failures += "$key ${variant.name}: no confident delivered period ($on), OFF $off"
                            continue
                        }
                        val onCents = cents(on, midi)
                        if (abs(onCents) <= 20) continue
                        val offCents = off?.let { cents(it, midi) }
                        val preservesKnownPeriod = key in inherited && off != null && off.confidence >= .75f &&
                            abs(1200 * ln(on.hz.toDouble() / off.hz) / ln(2.0)) <= 20
                        val improvesKnownError = key in inherited && offCents != null &&
                            abs(onCents) + 20 < abs(offCents)
                        if (!preservesKnownPeriod && !improvesKnownError) {
                            failures += "$key ${variant.name}: ON $onCents cents/confidence ${on.confidence}, OFF $offCents cents"
                        } else {
                            disclosed += "$key ${variant.name} retains an inherited non-native selection: $onCents cents"
                        }
                    }
                }
            }
        }
        println("OHM4 inherited OFF note limitations, not new memory regressions:\n${disclosed.sorted().joinToString("\n")}")
        assertTrue(failures.isEmpty(), "source memory introduced a delivered pitch regression:\n${failures.joinToString("\n")}")
    }

    @Test
    fun `maximum short grains preserve the native period of strong low register captures`() {
        val failures = mutableListOf<String>()
        val reference = 440.0 * 2.0.pow((48 - 69) / 12.0)
        val cases = listOf(Triple("native 250ms", .25f, 1.0),
            Triple("near-native 250ms", .25f, 1.01), Triple("near-native 500ms", .5f, 1.01))
        for ((label, seconds, ratio) in cases) {
            val frames = (44_100 * seconds).toInt()
            val capture = OhmCapture(FloatArray(frames) { i ->
                (.3 * sin(2 * PI * reference * ratio * i / 44_100)).toFloat()
            }, label)
            val options = OhmOptions(exciter = OhmExciter.CAPTURED, grains = 1f,
                grainSeconds = .02f, capture = capture)
            val corners = mapOf("TOUCH" to 1f, "BODY" to 1f, "LENGTH" to 1f, "MEDDLING" to 1f)
            for (model in listOf(3, 4)) {
                val sound = rendered(options, 48, 0f, model, 1f, corners)
                val pitch = Pitch.detect(sound, 2.5f, .4f)
                if (pitch == null || pitch.confidence < .75f || abs(cents(pitch, 48)) > 20) {
                    failures += "$label model$model: ${pitch?.let { cents(it, 48) }} cents, confidence ${pitch?.confidence}"
                }
                println("OHM short-grain $label model$model: ${pitch?.let { cents(it, 48) }} cents, confidence ${pitch?.confidence}")
            }
        }
        assertTrue(failures.isEmpty(), "windowed short grains lost the delivered native period:\n${failures.joinToString("\n")}")
    }

    private fun rendered(
        options: OhmOptions, midi: Int, commune: Float, model: Int = 4, velocity: Float = .8f,
        overrides: Map<String, Float> = emptyMap(),
    ): Snip {
        val macros = Ohm.defaults(model) + mapOf("TOUCH" to .5f, "BODY" to .5f, "LENGTH" to .65f,
            "MEDDLING" to .9f, "COMMUNE" to commune, "HOLD" to 0f) + overrides
        val sound = Ohm.render(macros, midi, velocity, 731, OhmGesture.STEADY, model, options)
        val bytes = ByteArrayOutputStream().also { WavWriter.write(it, sound, WavWriter.BitDepth.PCM_16) }.toByteArray()
        return WavReader.read(bytes)
    }

    private fun cents(pitch: PitchEstimate, midi: Int): Double {
        val reference = 440.0 * 2.0.pow((midi - 69) / 12.0)
        return 1200 * ln(pitch.hz / reference) / ln(2.0)
    }
}
