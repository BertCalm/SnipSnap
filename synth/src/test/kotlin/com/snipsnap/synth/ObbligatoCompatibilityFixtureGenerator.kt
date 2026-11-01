package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Explicit baseline capture, never called by tests. Refuses changed original DSP sources. */
object ObbligatoCompatibilityFixtureGenerator {
    const val ORIGINAL = "91384fa189a68c4993c8e359af6b748412bf95c3"
    const val RESOURCE = "/obbligato/models1-5-sha256.json"
    private val sourceFiles = listOf(
        "synth/src/main/kotlin/com/snipsnap/synth/Obbligato.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/ObbligatoPatch.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Bore.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Strings.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
    )

    fun floatHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(samples)
        return hash(bytes.array())
    }

    fun pcmHash(patch: ObbligatoPatch): String = ByteArrayOutputStream().use { out ->
        WavWriter.write(out, patch.render(), WavWriter.BitDepth.PCM_24)
        hash(out.toByteArray())
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")}: $output" }
        return output
    }

    fun recipes(): List<ObbligatoPatch> = (1..5).flatMap { model ->
        val base = linkedMapOf("BREATH" to .65f, "BOND" to 0f, "MISCHIEF" to .2f, "HOLD" to .45f)
        val factory = listOf("Harmony" to 0f, "Play" to (1f / 7f), "Fight" to (3f / 7f), "Protect" to (6f / 7f))
            .map { (name, bond) -> ObbligatoPatch("Model $model $name C4", base + ("BOND" to bond), model = model) }
        factory + listOf(
            ObbligatoPatch("Model $model C5 soft interpolated", base + mapOf("BREATH" to .1f, "BOND" to .5f, "MISCHIEF" to 1f, "HOLD" to 1f),
                midi = 72, velocity = .3f, seed = 17, model = model),
            ObbligatoPatch("Model $model G4 seeded short", base + mapOf("BREATH" to 1f, "BOND" to .61f, "MISCHIEF" to .75f, "HOLD" to 0f),
                midi = 67, velocity = .6f, seed = -23, model = model),
        )
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1)
        val root = File(git("rev-parse", "--show-toplevel"))
        val blobs = sourceFiles.associateWith { path ->
            val original = git("rev-parse", "$ORIGINAL:$path")
            check(git("hash-object", File(root, path).absolutePath) == original) {
                "Baseline capture refused: $path differs from original $ORIGINAL"
            }
            JsonValue.Str(original)
        }
        val cases = recipes().map { patch ->
            val raw = Obbligato.probe(patch.macros, patch.midi, patch.velocity, patch.seed, model = patch.model)
            check(raw.snip.samples.all { it.isFinite() }) { "Non-finite baseline: ${patch.name}" }
            JsonValue.Obj(linkedMapOf(
                "recipe" to patch.toJsonValue(),
                "sampleRate" to JsonValue.Num(raw.snip.sampleRate.toDouble()),
                "channels" to JsonValue.Num(raw.snip.channels.toDouble()),
                "frames" to JsonValue.Num(raw.snip.frameCount.toDouble()),
                "rawFloat32LittleEndianSha256" to JsonValue.Str(floatHash(raw.snip.samples)),
                "airFloat32LittleEndianSha256" to JsonValue.Str(floatHash(raw.air)),
                "reedFloat32LittleEndianSha256" to JsonValue.Str(floatHash(raw.reed)),
                "finalPcm24WavSha256" to JsonValue.Str(pcmHash(patch)),
            ))
        }
        val fixture = JsonValue.Obj(linkedMapOf(
            "schemaVersion" to JsonValue.Num(1.0), "originalCommit" to JsonValue.Str(ORIGINAL),
            "sourceGitBlobs" to JsonValue.Obj(blobs), "javaVersion" to JsonValue.Str(System.getProperty("java.version")),
            "osArch" to JsonValue.Str(System.getProperty("os.arch")),
            "floatFormat" to JsonValue.Str("IEEE754 binary32 little endian, exact bits"),
            "exportFormat" to JsonValue.Str("WavWriter PCM24 complete RIFF bytes, mono 44100Hz"),
            "cases" to JsonValue.Arr(cases),
        ))
        val destination = File(args.firstOrNull() ?: File(root, "synth/src/test/resources$RESOURCE").path)
        destination.parentFile.mkdirs()
        val text = Json.write(fixture) + "\n"
        Json.parse(text)
        destination.writeText(text)
        println("Captured ${cases.size} original model1–5 fixtures at ${destination.absolutePath}")
    }
}
