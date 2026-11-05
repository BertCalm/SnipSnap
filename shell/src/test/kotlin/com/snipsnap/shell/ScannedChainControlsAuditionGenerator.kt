package com.snipsnap.shell

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.ScannedChain
import com.snipsnap.synth.ScannedChainConfig
import com.snipsnap.synth.ScannedChainPatch
import com.snipsnap.synth.ScannedChainVoice
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Actual model-two renders at five editor positions, with complete reproducible recipes. */
object ScannedChainControlsAuditionGenerator {
    private const val TARGET_RMS = .12
    private const val MATCHED_PEAK_CEILING = .9
    private const val BASE_COMMIT_READ = "1a03d6c0846f5950599cd95dac5180a01d6d6b4b"
    private val travels = listOf(0f, .25f, .5f, .75f, 1f)

    private data class Group(
        val control: String, val voice: ScannedChainVoice, val description: String,
    ) {
        val id get() = control.lowercase(Locale.ROOT)
        val referenceId get() = "$id-${if (control == "BLOOM") "000" else "050"}"
    }

    private val groups = listOf(
        Group("MOTION", ScannedChainVoice.PLUCK, "Frozen shape to full-speed physical evolution; scanner note stays C4."),
        Group("DAMP", ScannedChainVoice.PLUCK, "Increasing mechanical damping, with more slider travel for gentle values."),
        Group("BEND", ScannedChainVoice.METAL, "Increasing source-relative spring hardening in the existing model-two law."),
        Group("BLOOM", ScannedChainVoice.BLOOM, "Increasing delayed source-relative force; compare the later body with the strike."),
        Group("ATTACK", ScannedChainVoice.PLUCK, "Increasing the smooth onset time while preserving all physical controls."),
        Group("RELEASE", ScannedChainVoice.PLUCK, "Increasing the ending fade time; the finite note remains three seconds."),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Supply at most one output directory" }
        val output = File(args.firstOrNull() ?: "../testkit/scanned-chain-controls").absoluteFile
        require(output.isDirectory || output.mkdirs()) { "Cannot create $output" }
        val checkout = File("..").canonicalFile
        val sourcePaths = listOf(
            "shell/src/main/kotlin/com/snipsnap/shell/ScannedChainEditor.kt",
            "shell/src/test/kotlin/com/snipsnap/shell/ScannedChainControlsAuditionGenerator.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/ScannedChain.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/ScannedChainPatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/ScannedChainShape.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/ScannedChainModelV2.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/ScannedChainHeadroom.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/ScannedChainRenderCache.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/PadRecipe.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Pitch.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
        )
        val sourceHashes = sourcePaths.associateWith { sha(File(checkout, it).readBytes()) }
        val sourceHash = sha(Json.write(json(sourceHashes)).toByteArray())
        val started = System.nanoTime()
        val exportedGroups = groups.map { group ->
            val baseline = context(group)
            val steps = travels.map { travel ->
                val id = "${group.id}-${(travel * 100).roundToInt().toString().padStart(3, '0')}"
                val patch = edited(baseline, group.control, travel).copy(name = "${group.control} ${travel * 100}%")
                val recipe = PadRecipe(patch = patch)
                val recipeText = recipe.toJsonText() + "\n"
                val recipePath = "recipes/$id.json"
                File(output, recipePath).apply { parentFile.mkdirs(); writeText(recipeText) }
                evictCompletedAudio()
                val native = recipe.render()
                check(native.channels == 1 && native.sampleRate == 44_100 && native.frameCount == 132_300)
                check(native.samples.all { it.isFinite() } && native.peak() <= 1f && rms(native) > 1e-8)
                // A different complete render replaces the one-entry production cache.
                // The decoded recipe must then rebuild its trajectory, rather than reuse this clip.
                evictCompletedAudio()
                val decoded = PadRecipe.fromJsonText(File(output, recipePath).readText())
                check(decoded.patch == patch) { "Saved settings changed for $id" }
                val replay = decoded.render()
                check(native.samples.contentEquals(replay.samples)) { "Fresh recipe replay differs for $id" }
                val matchGain = min(TARGET_RMS / rms(native), MATCHED_PEAK_CEILING / native.peak())
                val matched = native.copy(samples = FloatArray(native.samples.size) { (native.samples[it] * matchGain).toFloat() })
                val nativePath = "native/$id.wav"
                val matchedPath = "matched/$id.wav"
                WavWriter.write(File(output, nativePath), native, WavWriter.BitDepth.PCM_24)
                WavWriter.write(File(output, matchedPath), matched, WavWriter.BitDepth.PCM_24)
                val nativeQuantization = verifyWav(File(output, nativePath), native)
                val matchedQuantization = verifyWav(File(output, matchedPath), matched)
                println("Wrote $id; fresh recipe replay exact")
                linkedMapOf<String, Any?>(
                    "id" to id, "control" to group.control, "travel" to travel, "travelPercent" to (travel * 100).roundToInt(),
                    "physicalValue" to physicalValue(patch, group.control),
                    "physicalUnit" to if (group.control in listOf("ATTACK", "RELEASE")) "seconds" else "macro",
                    "model" to patch.model, "voice" to patch.voice.name, "midi" to patch.midi,
                    "note" to "C4", "rootHz" to 440.0 * 2.0.pow((patch.midi - 69) / 12.0), "velocity" to patch.velocity,
                    "macros" to patch.macros, "config" to patch.toJsonValue().obj().getValue("config"),
                    "patch" to patch.toJsonValue(), "recipePath" to recipePath,
                    "recipeSha256" to sha(File(output, recipePath).readBytes()), "recipeReplayExact" to true,
                    "nativePath" to nativePath, "matchedPath" to matchedPath,
                    "nativeFileSha256" to sha(File(output, nativePath).readBytes()),
                    "matchedFileSha256" to sha(File(output, matchedPath).readBytes()),
                    "nativeFloatSha256" to floatSha(native.samples), "matchedFloatSha256" to floatSha(matched.samples),
                    "nativeMetrics" to metrics(native), "matchedMetrics" to metrics(matched),
                    "matchedGain" to matchGain, "matchedPeakCapActive" to (TARGET_RMS / rms(native) > matchGain),
                    "nativeWavMaximumQuantizationError" to nativeQuantization,
                    "matchedWavMaximumQuantizationError" to matchedQuantization,
                    "bloomForceWindowSeconds" to (patch.config.seconds - patch.config.releaseSeconds).coerceIn(.05f, 2f),
                    "pitchWindows" to listOf(.4f, 1.4f).map { pitchWindow(native, it, patch.midi) },
                )
            }
            linkedMapOf<String, Any?>(
                "id" to group.id, "control" to group.control, "title" to group.control,
                "description" to group.description, "voice" to group.voice.name,
                "referenceId" to group.referenceId, "contextPatch" to baseline.toJsonValue(), "steps" to steps,
            )
        }
        check(sourceHashes.all { (path, hash) -> sha(File(checkout, path).readBytes()) == hash }) {
            "Sources changed during rendering; rerun against a stable tree"
        }
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "title" to "Scanned Chain control study", "createdUtc" to Instant.now().toString(),
            "baseCommitRead" to BASE_COMMIT_READ, "sourceSha256" to sourceHash, "sourceFiles" to sourceHashes,
            "sampleRate" to 44_100, "channels" to 1, "bitDepth" to 24, "clipCount" to 30,
            "nativePolicy" to "Actual model-two production audio including its ordinary finishing gain; no extra normalization or effects. Native is not pre-finishing solver audio.",
            "matching" to mapOf("meter" to "whole-clip unweighted RMS", "targetRms" to TARGET_RMS,
                "peakCeiling" to MATCHED_PEAK_CEILING,
                "policy" to "One constant gain per complete clip, capped by sample peak. RMS matching does not establish equal perceived loudness."),
            "editorCurves" to mapOf("DAMP" to "macro = travel^2", "ATTACK" to "seconds = travel^2",
                "RELEASE" to "seconds = 2 * travel^2", "MOTION_BEND_BLOOM" to "macro = travel"),
            "comparisonPolicy" to "Five slider positions per control. Only the named field changes within each group. Reference position is 50%, except BLOOM uses 0%. Contexts differ between groups.",
            "recipeReplayPolicy" to "Decode the exported complete PadRecipe, evict the one-entry cache with a distinct frozen 0.25-second render, then require bit-identical Float PCM from the fresh replay.",
            "pitchPolicy" to "Two 250 ms body windows at 0.4 and 1.4 seconds. Detector estimates and confidence are recorded, including missing or weak evidence; perceived pitch remains a listening judgement.",
            "ledger" to mapOf("verified" to "Production output, exact fresh recipe replay, finite bounded native/matched samples, PCM24 quantization and stable source hashes.",
                "measured" to "Per-clip sample peak, whole-clip RMS, DC, hashes, quantization error and two pitch windows.",
                "proposed" to "Squared slider travel is an ergonomic choice. The study lets the owner judge its usefulness.",
                "heard" to "No new listening verdict recorded by this generator. Owner reports existing hardware works."),
            "groups" to exportedGroups, "generationMilliseconds" to (System.nanoTime() - started) / 1e6,
        )
        val manifestText = Json.write(json(manifest)) + "\n"
        File(output, "manifest.json").writeText(manifestText)
        File(output, "manifest.sha256").writeText(sha(manifestText.toByteArray()) + "  manifest.json\n")
        File(output, "README.txt").writeText("""
            Scanned Chain control study

            30 native production clips and 30 RMS-matched copies, mono 44.1 kHz PCM24.
            Every step has a complete editable pad recipe. Only the named control changes
            within its five-step group. Groups use different fixed source contexts.

            Native retains the production render's ordinary finish. Matched uses one
            constant gain for the whole clip: target RMS 0.12, sample-peak cap 0.90.
            This is numerical RMS matching, not a claim of equal perceived loudness.
            Full settings, gains, file and Float hashes, numerical checks and detector
            estimates are in manifest.json. Fresh recipe replays are bit-identical.

            DAMP = travel squared; ATTACK = travel squared seconds;
            RELEASE = 2 times travel squared seconds. Other macros retain linear travel.
            Existing recipes, physical ranges, presets and renderer model are unchanged.
            Compare native files for the delivered gesture, matched files for a second
            view of color and envelope with overall RMS differences reduced.
            No listening verdict has been supplied for this study.
        """.trimIndent() + "\n")
        println("Wrote 30 native clips, 30 matched clips and 30 complete recipes to $output")
    }

    private fun context(group: Group): ScannedChainPatch {
        val macros = ScannedChain.defaults(group.voice) + mapOf(
            "MOTION" to .85f, "DAMP" to .25f, "BEND" to .5f, "BLOOM" to 0f,
        ) + if (group.control == "BLOOM") mapOf("MOTION" to 1f, "DAMP" to .18f, "BEND" to .25f) else emptyMap()
        return ScannedChainPatch("${group.control} fixed context", group.voice, macros,
            config = ScannedChainConfig.forVoice(group.voice).copy(seconds = 3f, releaseSeconds = .4f),
            midi = 60, velocity = .8f, model = 2)
    }

    private fun edited(patch: ScannedChainPatch, control: String, travel: Float): ScannedChainPatch = when (control) {
        "ATTACK" -> patch.copy(config = patch.config.copy(attackSeconds = ScannedChainEditor.attackSeconds(travel)))
        "RELEASE" -> patch.copy(config = patch.config.copy(releaseSeconds = ScannedChainEditor.releaseSeconds(travel)))
        else -> patch.copy(macros = patch.macros + (control to ScannedChainEditor.macroValue(control, travel)))
    }

    private fun physicalValue(patch: ScannedChainPatch, control: String): Float = when (control) {
        "ATTACK" -> patch.config.attackSeconds
        "RELEASE" -> patch.config.releaseSeconds
        else -> patch.macros.getValue(control)
    }

    private fun evictCompletedAudio() {
        ScannedChainPatch("Distinct cache sentinel", ScannedChainVoice.PLUCK,
            ScannedChain.defaults(ScannedChainVoice.PLUCK) + ("MOTION" to 0f),
            config = ScannedChainConfig.forVoice(ScannedChainVoice.PLUCK).copy(seconds = .25f),
            midi = 36, velocity = .123f, model = 2).render()
    }

    private fun rms(snip: Snip): Double = sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size)

    private fun metrics(snip: Snip): Map<String, Any?> = mapOf(
        "frames" to snip.frameCount, "finite" to snip.samples.all { it.isFinite() },
        "peak" to snip.peak(), "dc" to snip.samples.sumOf { it.toDouble() } / snip.samples.size, "rms" to rms(snip),
    )

    private fun verifyWav(file: File, original: Snip): Double {
        val decoded = WavReader.read(file)
        check(decoded.channels == original.channels && decoded.sampleRate == original.sampleRate &&
            decoded.samples.size == original.samples.size && decoded.samples.all { it.isFinite() })
        val error = original.samples.indices.maxOf { abs(original.samples[it].toDouble() - decoded.samples[it]) }
        check(error <= 1.3e-7) { "PCM24 quantization error $error for ${file.name}" }
        return error
    }

    private fun pitchWindow(snip: Snip, from: Float, midi: Int): Map<String, Any?> {
        val estimate = Pitch.detect(snip, fromSec = from, windowSec = .25f)
        val root = 440.0 * 2.0.pow((midi - 69) / 12.0)
        return mapOf("fromSeconds" to from, "windowSeconds" to .25,
            "hz" to estimate?.hz, "confidence" to estimate?.confidence,
            "centsFromC4" to estimate?.let { 1200 * ln(it.hz / root) / ln(2.0) },
            "status" to when { estimate == null -> "no reliable estimate"; estimate.confidence < .8f -> "weak evidence"; else -> "detected" })
    }

    private fun floatSha(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putInt(it.toRawBits()) }
        return sha(bytes.array())
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble())
        is Map<*, *> -> JsonValue.Obj(LinkedHashMap(value.entries.associate { it.key.toString() to json(it.value) }))
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported manifest value ${value.javaClass.name}")
    }
}
