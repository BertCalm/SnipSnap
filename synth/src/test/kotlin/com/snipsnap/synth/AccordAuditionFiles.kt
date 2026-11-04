package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/** Delivery helpers shared by the native audition and editable kit exporters. */
internal object AccordAuditionFiles {
    fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> value.toDouble().let { if (it.isFinite()) JsonValue.Num(it) else JsonValue.Null }
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        is DoubleArray -> JsonValue.Arr(value.map(::json))
        is FloatArray -> JsonValue.Arr(value.map(::json))
        is IntArray -> JsonValue.Arr(value.map(::json))
        else -> error("unsupported Accord manifest value ${value.javaClass.name}")
    }

    fun writeJson(file: File, value: Any?) {
        file.parentFile?.mkdirs()
        file.writeText(Json.write(json(value)) + "\n")
    }

    fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }

    fun metrics(snip: Snip): Map<String, Any> {
        require(snip.channels == 1 && snip.sampleRate == 44_100 && snip.samples.isNotEmpty())
        require(snip.samples.all { it.isFinite() }) { "nonfinite native Accord audio" }
        val samples = snip.samples
        return linkedMapOf("frames" to snip.frameCount, "seconds" to samples.size.toDouble() / snip.sampleRate,
            "peak" to snip.peak(), "rms" to sqrt(samples.sumOf { it.toDouble() * it } / samples.size),
            "dc" to samples.average(), "loudness" to Loudness.of(snip),
            "classified" to Classifier.classify(snip).drumClass.name)
    }

    /** Verify actual PCM24 delivery, rather than only the in-memory render. */
    fun writeAudio(file: File, snip: Snip): Double {
        require(snip.samples.all { it.isFinite() } && snip.peak() <= 1f)
        WavWriter.write(file, snip, WavWriter.BitDepth.PCM_24)
        val playback = WavReader.read(file)
        require(playback.channels == snip.channels && playback.sampleRate == snip.sampleRate &&
            playback.frameCount == snip.frameCount)
        var maximum = 0.0
        for (i in playback.samples.indices) maximum = maxOf(maximum, abs(playback.samples[i].toDouble() - snip.samples[i]))
        require(maximum <= 3.0 / 8_388_608.0) { "PCM24 changed Accord audio: $maximum" }
        return maximum
    }

    fun archive(directory: File, destination: File) {
        destination.parentFile?.mkdirs()
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            directory.walkTopDown().filter { it.isFile && it.canonicalFile != destination.canonicalFile }
                .sortedBy { it.relativeTo(directory).invariantSeparatorsPath }.forEach { file ->
                val relative = file.relativeTo(directory).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry(relative).also { it.time = 0L })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
