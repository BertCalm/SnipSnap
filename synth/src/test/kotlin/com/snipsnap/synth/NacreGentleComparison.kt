package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Three listening candidates built around the owner's preferred, frozen model-1 gesture.
 * This is testkit postmixing, not another NACRE model, preset or production rendering mode.
 * The reference is untouched. Each alternative replaces only its conditioned venue stem with
 * the original model's coral/seaweed endpoint at 1.8x (+5.1 dB). CURRENT remains .20, including
 * seaweed's existing current-gated apertures. No new string excitation, motion or tail is added.
 *
 * [Clip.raw] is the audition input. [Clip.native] is the ordinary, UNLIFTED model-1 patch render,
 * retained only to identify/replay its source recipe; it is not variant playback audio. A generator
 * must apply one shared playback gain to the three raw arrays. It must never level them separately.
 */
internal object NacreGentleComparison {
    const val REFERENCE_RESOURCE = "/nacre-model1-reference.json"
    const val VENUE_LIFT = 1.8

    /** Review guardrails, not claims that these numbers establish musical acceptance. */
    const val MAX_ENERGY_RATIO = 1.15
    const val MAX_ENERGY99_EXTENSION_SECONDS = .12
    const val MAX_AFTER_ONE_SECOND_ENERGY_FRACTION = .006
    const val MAX_UPPER_MID_ENERGY_ADDITION_FRACTION = .001

    data class Clip(
        val id: String,
        val title: String,
        val patch: NacrePatch,
        val raw: FloatArray,
        val native: Snip,
        val stems: Map<String, FloatArray>,
        val events: List<Nacre.Event>,
        val diagnostics: Map<String, Double>,
        val venueLift: Double,
        val venueGeometry: Float,
        val renderMilliseconds: Double,
    )

    fun render(): List<Clip> {
        val referenceText = checkNotNull(javaClass.getResourceAsStream(REFERENCE_RESOURCE)) {
            "Missing published NACRE model-1 reference resource"
        }.bufferedReader().use { it.readText() }
        val reference = Json.parse(referenceText).obj()
        val patch = NacrePatch.fromJsonValue(reference.getValue("recipe")) as NacrePatch
        check(patch.model == 1 && patch.midi == 48 && patch.velocity == 1f && patch.seed == 7L)
        check(patch.macros.getValue("CURRENT") == .20f)
        val started = System.nanoTime()
        val original = probe(patch)
        val originalMilliseconds = (System.nanoTime() - started) / 1e6
        check(samplesHash(original.raw) == reference.getValue("prelevelSamplesSha256").str()) {
            "The gentle comparison's source differs from the published model-1 prelevel gesture"
        }
        check(samplesHash(original.snip.samples) == reference.getValue("nativeSamplesSha256").str()) {
            "The gentle comparison's native reference differs from the published model-1 gesture"
        }
        val originalEnergy = energy(original.raw)
        check(originalEnergy > 0.0)
        val baselineVenue = original.stems.getValue("venue")
        val unchanged = original.stems.filterKeys { it != "venue" }

        fun candidate(id: String, title: String, geometry: Float): Clip {
            val began = System.nanoTime()
            val sourcePatch = patch.copy(name = title, macros = patch.macros + ("VENUE" to geometry))
            val source = probe(sourcePatch)
            check(source.events == original.events) { "$id changed the paired-strum events" }
            for ((name, samples) in unchanged) check(source.stems.getValue(name).contentEquals(samples)) {
                "$id changed the original $name contribution"
            }
            val liftedVenue = FloatArray(baselineVenue.size) { i ->
                (VENUE_LIFT * source.stems.getValue("venue")[i]).toFloat()
            }
            // Retain the published full prelevel bus and change only the conditioned return.
            // Double arithmetic avoids unnecessary cancellation rounding during replacement.
            val samples = FloatArray(original.raw.size) { i ->
                (original.raw[i].toDouble() - baselineVenue[i] + liftedVenue[i]).toFloat()
            }
            check(samples.all { it.isFinite() }) { "$id contains nonfinite audition samples" }
            val stems = LinkedHashMap(unchanged).also { it["venue"] = liftedVenue }
            val diagnostics = LinkedHashMap(source.diagnostics).also {
                it["testkitVenueLift"] = VENUE_LIFT
                it["testkitPostmixOnly"] = 1.0
                it["testkitEnergyRatioToOriginal"] = energy(samples) / originalEnergy
                it["testkitRawPeak"] = samples.maxOf { sample -> abs(sample).toDouble() }
            }
            return Clip(id, title, sourcePatch, samples, source.snip, stems, source.events,
                diagnostics, VENUE_LIFT, geometry, (System.nanoTime() - began) / 1e6)
        }

        return listOf(
            Clip("original", "Original / model 1", patch, original.raw, original.snip, original.stems,
                original.events, original.diagnostics + mapOf("testkitVenueLift" to 1.0,
                    "testkitPostmixOnly" to 0.0, "testkitEnergyRatioToOriginal" to 1.0),
                1.0, patch.macros.getValue("VENUE"), originalMilliseconds),
            candidate("coral", "Coral / return +5.1 dB", 0f),
            candidate("seaweed", "Seaweed / return +5.1 dB", 1f),
        )
    }

    private fun probe(patch: NacrePatch): Nacre.Probe = Nacre.inspect(
        patch.voice, patch.macros, patch.midi, patch.velocity, patch.seed,
    )

    private fun energy(samples: FloatArray): Double = samples.sumOf { it.toDouble() * it }

    private fun samplesHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) bytes.putInt(sample.toRawBits())
        return MessageDigest.getInstance("SHA-256").digest(bytes.array())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
