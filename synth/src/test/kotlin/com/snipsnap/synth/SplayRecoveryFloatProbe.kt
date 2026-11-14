package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Explicit replay tool, never called by fixture preparation or static review.
 * Run the same source over the archived base and recovered base in separate JVMs,
 * then compare raw/native Float bits before any new control law is considered.
 */
object SplayRecoveryFloatProbe {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1) { "new-output-directory" }
        val output = File(args.single()).absoluteFile
        require(!output.exists()) { "Recovery probe refuses to overwrite $output" }
        check(output.mkdirs()) { "Could not create $output" }
        val cases = listOf("model1-first-seams" to SplayPatch(
            "First Seams", SplayVoice.TONGUE, Splay.defaults(SplayVoice.TONGUE, 1), model = 1)) +
            SplayAcceptedBank.entries.map { it.id to it.patch }
        val rows = cases.map { (id, patch) ->
            val result = Splay.renderDetailed(patch.voice, patch.macros, patch.midi,
                patch.velocity, patch.seed, patch.model)
            File(output, "$id.patch.json").writeText(patch.toJsonText())
            val raw = floats(result.raw)
            val native = floats(result.snip.samples)
            File(output, "$id.raw.f32le").writeBytes(raw)
            File(output, "$id.native.f32le").writeBytes(native)
            WavWriter.write(File(output, "$id.raw.wav"), Snip(result.raw, 1, Dsp.RATE), WavWriter.BitDepth.PCM_16)
            val matchedHash = if (patch.model == 2) {
                val matched = FloatArray(result.raw.size) { (result.raw[it] * SplayAcceptedBank.MATCHED_GAIN).toFloat() }
                val file = File(output, "$id.matched.wav")
                WavWriter.write(file, Snip(matched, 1, Dsp.RATE), WavWriter.BitDepth.PCM_16)
                JsonValue.Str(sha(file.readBytes()))
            } else JsonValue.Null // The model-1 anchor is its original raw PCM, not a new match group.
            JsonValue.Obj(linkedMapOf(
                "id" to JsonValue.Str(id), "patch" to patch.toJsonValue(),
                "frames" to JsonValue.Num(result.raw.size.toDouble()),
                "rawFloatSha256" to JsonValue.Str(sha(raw)),
                "nativeFloatSha256" to JsonValue.Str(sha(native)),
                "rawPcmWavSha256" to JsonValue.Str(sha(File(output, "$id.raw.wav").readBytes())),
                "matchedPcmWavSha256" to matchedHash,
            ))
        }
        File(output, "manifest.json").writeText(Json.write(JsonValue.Obj(linkedMapOf(
            "schema" to JsonValue.Num(1.0),
            "javaVersion" to JsonValue.Str(System.getProperty("java.version")),
            "workingDirectory" to JsonValue.Str(System.getProperty("user.dir")),
            "cases" to JsonValue.Arr(rows),
        ))))
    }

    private fun floats(samples: FloatArray): ByteArray = ByteBuffer.allocate(samples.size * 4)
        .order(ByteOrder.LITTLE_ENDIAN).also { buffer -> samples.forEach { buffer.putFloat(it) } }.array()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
