package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Rebuildable accepted bank, complete production recipes and a cold/warm JVM timing ledger. */
object OtisIntegrationGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val out = File(args.firstOrNull() ?: "../testkit/otis-integration").canonicalFile.also { it.mkdirs() }
        val records = OtisPresets.all().map { patch ->
            println("OTIS integration ${patch.name}")
            Otis.clearCache()
            val start = System.nanoTime()
            val source = patch.render()
            val coldMs = (System.nanoTime() - start) / 1e6
            val warmStart = System.nanoTime()
            val cached = patch.render()
            val warmMs = (System.nanoTime() - warmStart) / 1e6
            check(source.samples.contentEquals(cached.samples))
            val stem = patch.name.lowercase().replace(' ', '_')
            val recipe = File(out, "$stem.patch.json").also { it.writeText(patch.toJsonText() + "\n") }
            Otis.clearCache()
            check(source.samples.contentEquals(Patches.fromJsonText(recipe.readText()).render().samples))
            val wav = WavWriter.write(File(out, "$stem.wav"), source)
            WavWriter.write(File(out, "$stem.matched.wav"), AuditionLevel.level(source))
            mapOf("name" to patch.name, "voice" to patch.voiceName, "midi" to patch.midi, "velocity" to patch.velocity,
                "scoreEvents" to patch.profile.events.size, "recipe" to recipe.name, "wav" to wav.name,
                "wavSha256" to sha256(wav), "frames" to source.frameCount, "peak" to source.peak(),
                "coldMs" to coldMs, "cacheHitMs" to warmMs)
        }
        val stage = Files.createTempDirectory(out.toPath(), ".otis-kit-").toFile()
        try {
            val kit = KitAssembler.assembleArranged("SnipSnap Otis Kit", SynthKits.otis(), stage)
            val result = KitExporter.exportProgramFolder(kit, stage, out, overwrite = true)
            println("OTIS kit ${result.directory}")
        } finally { stage.deleteRecursively() }
        val report = mapOf("format" to "snipsnap-otis-integration", "model" to Otis.MODEL_VERSION,
            "records" to records, "timingScope" to "One cold and one cache-hit render after clearing the cache per recipe; JVM, not phone latency",
            "output" to "Mono44100 PCM24, house level, finite one-shot pads", "preservation" to "Production sources compare against independent frozen audition sources",
            "limits" to listOf("No held keyboard or periodic HOLD", "No on-device listening or hardware validation"))
        File(out, "manifest.json").writeText(Json.write(value(report)) + "\n")
    }
    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun value(v: Any?): JsonValue = when (v) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(v)
        is Number -> JsonValue.Num(v.toDouble())
        is Map<*, *> -> JsonValue.Obj(v.entries.associateTo(linkedMapOf()) { it.key.toString() to value(it.value) })
        is Iterable<*> -> JsonValue.Arr(v.map(::value))
        else -> error("unsupported report value $v")
    }
}
