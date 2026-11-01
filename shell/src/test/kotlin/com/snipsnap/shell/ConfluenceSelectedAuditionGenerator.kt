package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.synth.Confluence
import com.snipsnap.synth.ConfluenceRecipe
import com.snipsnap.synth.ConfluenceSelectedBank
import com.snipsnap.synth.ConfluenceSelectedVoice
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Reproducible listening files from the same passage preparation used by the phone bench.
 * SOURCE copies the approved WAV bytes; no audition matching or extra output processing is added.
 * Run with output directory, optional source revision, and optional ISO-8601 generation time.
 */
object ConfluenceSelectedAuditionGenerator {
    private const val FORMAT = "snipsnap-confluence-cultural-audition/v1"
    private const val BANK_URL = "assets/cultural-voice-bank-v1.zip"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 1..3) {
            "Usage: ConfluenceSelectedAuditionGenerator <output-directory> [source-revision] [generated-at]"
        }
        val output = File(args[0]).absoluteFile
        require(output.isDirectory || output.mkdirs()) { "Cannot create audition output directory" }
        val revision = args.getOrNull(1) ?: sourceRevision()
        require(revision.matches(Regex("[0-9a-f]{40,64}"))) {
            "Supply the complete source revision hash"
        }
        // A supplied timestamp (or SOURCE_DATE_EPOCH) lets a repeat produce identical metadata too.
        val generatedAt = args.getOrNull(2)?.let(Instant::parse)
            ?: System.getenv("SOURCE_DATE_EPOCH")?.let { Instant.ofEpochSecond(it.toLong()) }
            ?: Instant.now()
        val bank = ConfluenceSelectedWorkshop.bank
        check(bank.voices.size == 9) { "The cultural audition requires all nine approved passages" }
        val manifest = Json.parse(bank.manifestText).obj()
        val sources = manifest.getValue("sources").arr().map { value ->
            val source = value.obj()
            linkedMapOf<String, Any?>(
                "id" to source.getValue("id"), "title" to source.getValue("title"),
                "tradition" to source.getValue("tradition"), "performer" to source.getValue("performerCredit"),
                "sourceUrl" to source.getValue("sourceUrl"), "license" to source.getValue("licenseId"),
                "licenseUrl" to source.getValue("licenseUrl"),
                "acquisitionKind" to source.getValue("sourceAcquisitionKind"),
                "originalDownloaded" to source.getValue("originalDownloaded"),
                "modifications" to source.getValue("modifications"),
                "styleClaimNote" to source.getValue("styleClaimNote"),
            )
        }
        val passages = bank.voices.mapIndexed { index, voice ->
            println("Rendering passage ${index + 1}/${bank.voices.size}: ${voice.id}")
            val provenance = voice.provenance.obj()
            val approved = provenance.getValue("approvedSelection").obj()
            val variants = ConfluenceSelectedWorkshop.Mode.entries.associateTo(LinkedHashMap()) { mode ->
                mode.name.lowercase(Locale.ROOT) to variant(output, bank, voice, mode)
            }
            linkedMapOf<String, Any?>(
                "id" to voice.id, "title" to voice.title, "sourceId" to voice.sourceId,
                "selectionId" to provenance.getValue("selectionId"),
                "approvedSelection" to linkedMapOf(
                    "startSeconds" to approved.getValue("startSeconds"),
                    "endSeconds" to approved.getValue("endSeconds"),
                ),
                "boundaryNote" to if (voice.id == "yaman-04")
                    "The approved raw excerpt ends while the voice is active. SOURCE keeps that boundary unchanged; the separate REFERENCE adds a 100 ms release fade."
                    else null,
                "conditioning" to linkedMapOf(
                    "gain" to voice.referenceGain,
                    "attackMs" to voice.fadeInFrames * 1000.0 / Confluence.RATE,
                    "releaseMs" to voice.fadeOutFrames * 1000.0 / Confluence.RATE,
                ),
                "textContent" to voice.textContent, "referenceMidi" to null,
                "provenance" to voice.provenance, "variants" to variants,
            )
        }
        val archive = File(output, BANK_URL)
        writeBankArchive(bank, archive)
        val audition = linkedMapOf<String, Any?>(
            "format" to FORMAT, "schemaVersion" to 1, "generatedAt" to generatedAt.toString(),
            "sourceRevision" to revision,
            "bank" to linkedMapOf(
                "id" to bank.id, "version" to bank.version, "url" to BANK_URL,
                "bytes" to archive.length(), "sha256" to sha256(archive.readBytes()),
                "manifestSha256" to bank.manifestSha256,
            ),
            "processing" to linkedMapOf(
                "appPath" to "ConfluenceSelectedWorkshop.prepare",
                "source" to "Exact approved canonical WAV bytes, copied unchanged from the portable bank. No audition gain or fades.",
                "reference" to "The app's separate constant-gain, cosine-edge playback variant, encoded as PCM24.",
                "arrangements" to "The app's finite 30-second native-register TEXTURE output at default variation and room. Repeated recorded material with small varispeed and entrance differences, shared room and output leveling; no independently recorded singers or confirmed note transposition.",
                "additionalAuditionProcessing" to false,
                "previewLevel" to ConfluenceSelectedWorkshop.previewLevel,
                "previewLevelAppliedToFiles" to false,
                "performerCountMeaning" to "Rendered part count, not independent human performer identities.",
                "textContent" to "UNKNOWN", "holdSupported" to false,
                "preparationVersion" to ConfluenceSelectedBank.PREPARATION_VERSION,
                "model" to Confluence.MODEL, "analysis" to Confluence.ANALYSIS_VERSION,
                "metrics" to "Measured from each emitted WAV after PCM readback; no listening judgment is inferred.",
            ),
            "sources" to sources, "passages" to passages,
        )
        val target = File(output, "assets/audition.json")
        target.writeText(Json.write(json(audition)) + "\n")
        println("Wrote 45 WAVs, assets/audition.json and $BANK_URL")
    }

    private fun variant(
        output: File, bank: ConfluenceSelectedBank, voice: ConfluenceSelectedVoice,
        mode: ConfluenceSelectedWorkshop.Mode,
    ): Map<String, Any?> {
        val settings = ConfluenceSelectedWorkshop.Settings(voice.id, mode)
        val prepared = ConfluenceSelectedWorkshop.prepare(settings)
        val snip = prepared.snip
        check(snip.frameCount > 0 && snip.samples.all { it.isFinite() } && snip.peak() <= 1f) {
            "${voice.id} $mode has invalid or clipping output"
        }
        val parts = when (mode) {
            ConfluenceSelectedWorkshop.Mode.PAIR -> 2
            ConfluenceSelectedWorkshop.Mode.GROUP -> 4
            else -> 1
        }
        val arrangement = mode != ConfluenceSelectedWorkshop.Mode.SOURCE && mode != ConfluenceSelectedWorkshop.Mode.REFERENCE
        check(snip.frameCount == if (arrangement) 30 * Confluence.RATE else voice.frames) {
            "${voice.id} $mode duration differs from the app contract"
        }
        val url = "assets/audio/${voice.id}--${mode.name.lowercase(Locale.ROOT)}.wav"
        val file = File(output, url)
        val source = prepared.source
        if (mode == ConfluenceSelectedWorkshop.Mode.SOURCE) {
            check(file.parentFile.isDirectory || file.parentFile.mkdirs())
            file.writeBytes(bank.assetBytes(voice.assetPath))
            check(sha256(file.readBytes()) == voice.fileSha256) { "Approved SOURCE WAV bytes changed" }
        } else WavWriter.write(file, snip, WavWriter.BitDepth.PCM_24)
        val readback = WavReader.read(file)
        check(readback.channels == snip.channels && readback.sampleRate == snip.sampleRate &&
            readback.frameCount == snip.frameCount) { "${voice.id} $mode WAV format changed" }
        val diagnostics = if (arrangement) {
            // prepare just cached this exact recipe; use its report, never a second audition renderer.
            val report = bank.render(ConfluenceRecipe.fromJsonText(source.getValue("bankRecipe")))
            check(report.snip.samples.contentEquals(snip.samples)) { "Audition diagnostics differ from app output" }
            check(report.performers.size == parts) { "Unexpected rendered part count" }
            report.diagnosticsJson()
        } else json(linkedMapOf(
            "processor" to if (mode == ConfluenceSelectedWorkshop.Mode.SOURCE)
                "Exact approved source WAV" else "Constant gain and cosine source-boundary envelopes",
            "activePerformers" to 1, "rosterPerformers" to 1, "holdSupported" to false,
        ))
        return linkedMapOf(
            "url" to url, "seconds" to readback.frameCount.toDouble() / readback.sampleRate,
            "performers" to parts, "sha256" to sha256(file.readBytes()), "bytes" to file.length(),
            "frames" to readback.frameCount, "sampleRate" to readback.sampleRate, "channels" to readback.channels,
            "metrics" to metrics(readback), "diagnostics" to diagnostics,
            "processing" to linkedMapOf(
                "description" to source.getValue("conditioning"),
                "modifications" to source.getValue("modifications"),
                "settings" to Json.parse(source.getValue("bankSettings")),
                "recipe" to Json.parse(source.getValue("bankRecipe")),
                "pitchMode" to source.getValue("pitchMode"),
                "sourceWavBytesUnchanged" to (mode == ConfluenceSelectedWorkshop.Mode.SOURCE),
            ),
        )
    }

    private fun writeBankArchive(bank: ConfluenceSelectedBank, target: File) {
        check(target.parentFile.isDirectory || target.parentFile.mkdirs())
        val paths = bank.assetPaths.sorted()
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.setLevel(9)
            for (path in paths) {
                val entry = ZipEntry(path)
                entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0))
                zip.putNextEntry(entry)
                zip.write(bank.assetBytes(path))
                zip.closeEntry()
            }
        }
        ZipFile(target).use { zip ->
            check(zip.entries().asSequence().map { it.name }.toList() == paths) { "Portable bank ZIP is incomplete" }
            for (path in paths) check(zip.getInputStream(zip.getEntry(path)).use { it.readBytes() }
                .contentEquals(bank.assetBytes(path))) { "Portable bank ZIP changed $path" }
        }
    }

    private fun metrics(snip: Snip): Map<String, Any?> = linkedMapOf(
        "peak" to snip.peak(),
        "rms" to sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size),
        "dc" to snip.samples.sumOf { it.toDouble() } / snip.samples.size,
        "firstSample" to snip.samples.first(), "lastSample" to snip.samples.last(),
        "nonFiniteSamples" to snip.samples.count { !it.isFinite() },
        "fullScaleSamples" to snip.samples.count { abs(it) >= 1f },
    )

    private fun sourceRevision(): String {
        val process = ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        require(process.waitFor() == 0 && output.matches(Regex("[0-9a-f]{40,64}"))) {
            "Cannot read checkout revision; supply the source revision as the second argument"
        }
        return output
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble())
        is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associateTo(LinkedHashMap()) { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported cultural audition manifest value ${value.javaClass.name}")
    }
}
