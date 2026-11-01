package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * A historical Fight experiment beside the then-accepted model-five gesture.
 * One gain serves every recording, including stems and causal controls. The control-off
 * condition disables activity-driven entries as well as pressure response; it does not
 * promise the same phrase with a different timbre. Each phrase note starts fresh.
 */
object ObbligatoResponsiveAuditionGenerator {
    private const val MIDI = 60
    private const val VELOCITY = .8f
    private const val SEED = 0
    private const val HEADROOM = .95f
    private const val REFERENCE_LEVEL = .12f
    private const val WINDOW_SECONDS = .05
    private const val HOP_SECONDS = .02
    private const val PHRASE_GAP_SECONDS = .12f
    private const val ARCHIVE = "obbligato-responsive-audition.zip"
    private const val MAX_ASSET_BYTES = 25L * 1024 * 1024
    private val phraseMidi = listOf(60, 63, 65, 67)

    private data class Take(
        val id: String,
        val title: String,
        val patch: ObbligatoPatch,
        val responses: Boolean,
        val weakening: Obbligato.SourceWeakening?,
        val report: Obbligato.Report,
    )

    private data class Clip(
        val id: String,
        val title: String,
        val take: String,
        val role: String,
        val source: Snip,
        val description: String,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Usage: ObbligatoResponsiveAuditionGenerator [output-directory]" }
        require(Obbligato.LEGACY_MODEL == 5 && Obbligato.EXPERIMENTAL_MODEL == 6) {
            "This audition compares accepted model five with experimental model six"
        }
        val root = File(args.firstOrNull() ?: "../testkit/obbligato-responsive-audition").absoluteFile
        check(root.isDirectory || root.mkdirs()) { "Could not create $root" }
        val base = ObbligatoPresets.all().single { it.name == "Fight" }
            .copy(name = "Fight accepted model five", midi = MIDI, velocity = VELOCITY, seed = SEED, model = Obbligato.LEGACY_MODEL)
        val responsive = base.copy(name = "Fight responsive experiment", model = Obbligato.EXPERIMENTAL_MODEL)
        val airWeakening = Obbligato.SourceWeakening(air = true, startSeconds = .45, endSeconds = .85, strength = .1f)
        val reedWeakening = Obbligato.SourceWeakening(air = false, startSeconds = 1.3, endSeconds = 1.7, strength = .1f)
        fun take(id: String, title: String, patch: ObbligatoPatch, responses: Boolean = true,
            weakening: Obbligato.SourceWeakening? = null): Take {
            val report = if (weakening == null) {
                Obbligato.probe(patch.macros, patch.midi, patch.velocity, patch.seed,
                    responses = responses, model = patch.model)
            } else {
                Obbligato.probeWithWeakening(patch.macros, patch.midi, patch.velocity, patch.seed,
                    responses = responses, model = patch.model, weakening = weakening)
            }
            validate(report.snip)
            require(report.air.size == report.snip.samples.size && report.reed.size == report.air.size)
            require(report.air.indices.maxOf {
                abs(report.air[it].toDouble() + report.reed[it] - report.snip.samples[it])
            } < 1e-6) { "$id: stems do not reconstruct the complete duet" }
            return Take(id, title, patch, responses, weakening, report)
        }
        val takes = listOf(
            take("accepted5", "Accepted Fight · model 5", base),
            take("responsive6", "Responsive Fight · model 6 experiment", responsive),
            take("decisions_off6", "Model 6 · activity decisions and pressure response disabled", responsive, responses = false),
            take("air_weakened6", "Air briefly weakened · responses enabled", responsive, weakening = airWeakening),
            take("air_weakened_off6", "Air briefly weakened · responses disabled", responsive, responses = false, weakening = airWeakening),
            take("reed_weakened6", "Reed briefly weakened · responses enabled", responsive, weakening = reedWeakening),
            take("reed_weakened_off6", "Reed briefly weakened · responses disabled", responsive, responses = false, weakening = reedWeakening),
        )
        val clips = takes.flatMapIndexed { index, t ->
            val duet = Clip("${t.id}_duet", t.title, t.id, "duet", t.report.snip,
                "Complete dry duet, with the same export gain as every comparison.")
            if (index < 3) listOf(duet,
                Clip("${t.id}_air", "Air inside ${t.title}", t.id, "air", mono(t.report.air),
                    "The actual air stem from the complete duet, at its unchanged duet gain."),
                Clip("${t.id}_reed", "Reed inside ${t.title}", t.id, "reed", mono(t.report.reed),
                    "The actual reed stem from the complete duet, at its unchanged duet gain."))
            else {
                val survivor = if (requireNotNull(t.weakening).air) "reed" else "air"
                listOf(duet, Clip("${t.id}_survivor", "Unweakened $survivor inside ${t.title}", t.id,
                    "survivor_$survivor", mono(if (survivor == "air") t.report.air else t.report.reed),
                    "Only the other source is weakened. This stem exposes what the unweakened player does in response."))
            }
        } + listOf(
            Clip("accepted5_phrase", "Accepted Fight · four independent notes", "accepted5", "phrase", phrase(base),
                "C4, E-flat4, F4, G4; 120 ms between complete finite notes. Each note starts two fresh players."),
            Clip("responsive6_phrase", "Responsive Fight · four independent notes", "responsive6", "phrase", phrase(responsive),
                "The same independent notes, playing stateful decisions within each note. No relationship carries between notes."),
        )
        require(clips.size == 19)
        clips.forEach { validate(it.source) }
        val gain = minOf(REFERENCE_LEVEL / Loudness.of(takes.first().report.snip),
            HEADROOM / clips.maxOf { it.source.peak() })
        require(gain.isFinite() && gain > 0f)

        // The whitelist makes a rerun's ZIP independent of stale files in its output folder.
        val archiveFiles = linkedSetOf<File>()
        fun writeText(path: String, content: String): File = File(root, path).also {
            it.parentFile.mkdirs(); it.writeText(content); archiveFiles += it
        }
        fun writeJson(path: String, value: Any?): File = writeText(path, Json.write(json(value)) + "\n")
        val recordings = clips.map { clip ->
            val output = scale(clip.source, gain)
            require(output.peak() <= HEADROOM + 1e-5f)
            val path = "audio/${clip.id}.wav"
            val file = WavWriter.write(File(root, path), output, WavWriter.BitDepth.PCM_24)
            archiveFiles += file
            linkedMapOf<String, Any?>("id" to clip.id, "title" to clip.title, "take" to clip.take,
                "role" to clip.role, "description" to clip.description, "path" to path,
                "gain" to gain, "bytes" to file.length(), "sha256" to sha256(file.readBytes()),
                "sourceMetrics" to metrics(clip.source), "exportMetrics" to metrics(output))
        }
        val takeRows = takes.map { t ->
            val patchPath = "patches/${t.id}.patch"
            val patchFile = writeText(patchPath, t.patch.toJsonText() + "\n")
            val diagnosticPath = "diagnostics/${t.id}.json"
            val diagnostic = linkedMapOf<String, Any?>(
                "schemaVersion" to 1, "patch" to t.patch.toJsonValue(), "responses" to t.responses,
                "weakening" to weakening(t.weakening), "interactionEvents" to events(t.report),
                "leadWindows" to leadWindows(t.report), "diagnostics" to t.report.diagnostics,
                "probeApi" to if (t.weakening == null) "Obbligato.probe" else "Obbligato.probeWithWeakening",
                "patchPolicy" to "The .patch stores the normal instrument. Diagnostic response and weakening flags are supplied by this generator, not by the app patch loader.",
            )
            writeJson(diagnosticPath, diagnostic)
            linkedMapOf<String, Any?>("id" to t.id, "title" to t.title, "patch" to t.patch.toJsonValue(),
                "patchPath" to patchPath, "patchSha256" to sha256(patchFile.readBytes()),
                "diagnosticPath" to diagnosticPath, "responses" to t.responses,
                "weakening" to weakening(t.weakening), "interactionEvents" to events(t.report))
        }
        val byId = takes.associateBy { it.id }
        val comparisons = takes.filter { it.weakening != null }.map { t ->
            compare(requireNotNull(byId[if (t.responses) "responsive6" else "decisions_off6"]), t)
        }
        // The negative control is exact before any shared final gain: phase divergence or
        // a softer mix cannot pass as a causal response from the unweakened source.
        require(comparisons.filter { it["responses"] == false }.all { it["survivorStemFloatIdentical"] == true }) {
            "A response-disabled unweakened stem changed; the causal control is not isolated"
        }
        val measurements = linkedMapOf<String, Any?>(
            "windowSeconds" to WINDOW_SECONDS, "hopSeconds" to HOP_SECONDS,
            "leadThresholdDb" to 3, "comparisons" to comparisons,
            "interpretation" to "Lead windows compare both stems: weakening the opponent can change their level ratio even when the unweakened stem is identical. They do not independently prove a change in the survivor. Read entry timelines, the survivor-only stem and the exact response-disabled negative control together. Waveform distance alone does not measure a behavioral change.",
        )
        writeJson("measurements.json", measurements)
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "engine" to "OBBLIGATO", "audition" to "Responsive Fight experiment",
            "status" to "Historical comparison: model 5 was the accepted default when the model 6 experiment was first reviewed.",
            "referenceModel" to Obbligato.LEGACY_MODEL, "experimentalModel" to Obbligato.EXPERIMENTAL_MODEL,
            "sampleRate" to Dsp.RATE, "channels" to 1, "pcmBits" to 24,
            "midi" to MIDI, "velocity" to VELOCITY, "seed" to SEED, "hold" to base.macros.getValue("HOLD"),
            "effects" to emptyList<String>(), "looping" to false, "gain" to gain,
            "requestedReferenceLoudness" to REFERENCE_LEVEL,
            "gainPolicy" to "One linear gain for every recording: match accepted model 5 to 0.12 house loudness unless any recording's peak requires a lower common gain. Stems and weakened controls are never individually leveled.",
            "responseOffPolicy" to "Model 6 responses=false disables activity-driven entries and reciprocal pressure response. The opening air assertion remains; the authored five-turn model-5 gesture is not imposed.",
            "phraseMidi" to phraseMidi, "phraseGapSeconds" to PHRASE_GAP_SECONDS,
            "phrasePolicy" to "Four fresh, independent notes per phrase. No persistent duet state between notes.",
            "patchPolicy" to "Patch downloads store full note, velocity, seed, model and macros for normal full-response rendering. Diagnostic flags are separately saved in diagnostics/ and replayed by this generator.",
            "checks" to linkedMapOf("finiteSamples" to true, "headroomPassed" to true,
                "sameGainThroughout" to true, "additiveSourceTaps" to true,
                "responseOffSurvivorIdentity" to true, "wavFiles" to clips.size),
            "rendererSources" to rendererSources(), "takes" to takeRows, "clips" to recordings,
            "causalComparisons" to comparisons,
            "ledger" to linkedMapOf("verified" to "Exact recipes, actual source taps, control entries and written PCM24 hashes.",
                "measured" to "Level, DC, finite samples, waveform and physical stem lead windows from Kotlin renders.",
                "proposed" to "Audibility thresholds, readiness law, crossover and pitch-push shapes remain audition choices.",
                "heard" to "No listening claim for model 6; the owner previously accepted model 5."),
            "caveats" to listOf("This is a control-rate behavioral analogy, not a simulation of two human players.",
                "No rack effects, stereo-width gesture, sustain loop, or cross-note relationship state.",
                "An entry timestamp marks the start of its crossover; a measured level crossover can occur later."),
        )
        val manifestText = Json.write(json(manifest))
        writeText("manifest.json", manifestText + "\n")
        writeText("README.txt", readme())
        val page = html(manifestText, recordings)
        writeText("index.html", page)
        val embedded = recordings.associate { row ->
            val path = row.getValue("path") as String
            path to "data:audio/wav;base64,${Base64.getEncoder().encodeToString(File(root, path).readBytes())}"
        }
        val standalone = File(root, "standalone.html")
        standalone.writeText(page.replace("const AUDIO = {};", "const AUDIO = ${Json.write(json(embedded))};"))
        require(standalone.length() < MAX_ASSET_BYTES) { "Single-file listening page exceeds 25 MiB" }
        val archive = File(root, ARCHIVE)
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            archiveFiles.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath).also { it.time = 0L })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        require(archive.length() < MAX_ASSET_BYTES) { "Portable audition ZIP exceeds 25 MiB" }
        println("Wrote ${clips.size} common-gain PCM24 recordings, ${takes.size} complete recipes, causal measurements, listening pages and ${archive.absolutePath}")
    }

    private fun phrase(patch: ObbligatoPatch): Snip {
        val notes = phraseMidi.map {
            Obbligato.probe(patch.macros, it, patch.velocity, patch.seed, model = patch.model).snip
        }
        val gap = (PHRASE_GAP_SECONDS * Dsp.RATE).toInt()
        val samples = FloatArray(notes.sumOf { it.frameCount } + gap * (notes.size - 1))
        var offset = 0
        for (note in notes) { note.samples.copyInto(samples, offset); offset += note.frameCount + gap }
        return mono(samples)
    }

    private fun events(report: Obbligato.Report): List<Map<String, Any>> = report.interactionEvents.map {
        linkedMapOf("seconds" to it.seconds, "previousLeader" to it.previousLeader.name,
            "leader" to it.leader.name, "readinessAtEntry" to it.readinessAtEntry,
            "heardAir" to it.heardAir, "heardReed" to it.heardReed)
    }

    private fun weakening(w: Obbligato.SourceWeakening?): Map<String, Any>? = w?.let {
        linkedMapOf("air" to it.air, "startSeconds" to it.startSeconds,
            "endSeconds" to it.endSeconds, "strength" to it.strength)
    }

    private fun leadWindows(report: Obbligato.Report): List<Map<String, Any>> {
        val result = ArrayList<Map<String, Any>>()
        val held = report.snip.durationSeconds.toDouble() - .75
        var start = .25
        while (start + WINDOW_SECONDS <= held - .1) {
            val air = windowRms(report.air, start, start + WINDOW_SECONDS)
            val reed = windowRms(report.reed, start, start + WINDOW_SECONDS)
            val balance = 20.0 * log10((air + 1e-20) / (reed + 1e-20))
            result += linkedMapOf("startSeconds" to start, "endSeconds" to start + WINDOW_SECONDS,
                "airRms" to air, "reedRms" to reed, "airOverReedDb" to balance,
                "leader" to if (balance > 3) "AIR" else if (balance < -3) "REED" else "BALANCED")
            start += HOP_SECONDS
        }
        return result
    }

    private fun compare(base: Take, changed: Take): Map<String, Any?> {
        val weak = requireNotNull(changed.weakening)
        val before = if (weak.air) base.report.reed else base.report.air
        val after = if (weak.air) changed.report.reed else changed.report.air
        val survivor = if (weak.air) "REED" else "AIR"
        val baseEntries = base.report.interactionEvents.filter { it.leader.name == survivor }.map { it.seconds }
        val changedEntries = changed.report.interactionEvents.filter { it.leader.name == survivor }.map { it.seconds }
        val timelineBefore = base.report.interactionEvents.map { it.seconds to it.leader }
        val timelineAfter = changed.report.interactionEvents.map { it.seconds to it.leader }
        val windowsBefore = leadWindows(base.report)
        val windowsAfter = leadWindows(changed.report)
        val changedLeadWindows = windowsBefore.indices.count {
            (windowsBefore[it].getValue("startSeconds") as Double) >= weak.startSeconds &&
                windowsBefore[it].getValue("leader") != windowsAfter[it].getValue("leader")
        }
        val startBefore = baseEntries.firstOrNull { it >= weak.startSeconds }
        val startAfter = changedEntries.firstOrNull { it >= weak.startSeconds }
        val waveformDifference = sqrt(after.indices.sumOf {
            val d = after[it].toDouble() - before[it]; d * d
        } / before.sumOf { it.toDouble() * it }.coerceAtLeast(1e-20))
        return linkedMapOf("take" to changed.id, "referenceTake" to base.id, "responses" to changed.responses,
            "weakenedPlayer" to if (weak.air) "AIR" else "REED", "survivor" to survivor,
            "weakening" to weakening(weak), "survivorStemFloatIdentical" to before.contentEquals(after),
            "survivorRelativeRmsDifference" to waveformDifference,
            "referenceSurvivorEntrySeconds" to baseEntries, "changedSurvivorEntrySeconds" to changedEntries,
            "firstReferenceSurvivorEntryAfterWeakeningStart" to startBefore,
            "firstChangedSurvivorEntryAfterWeakeningStart" to startAfter,
            "firstSurvivorEntryShiftSeconds" to if (startBefore != null && startAfter != null) startAfter - startBefore else null,
            "entryTimelineChanged" to (timelineBefore != timelineAfter),
            "physicalLeadWindowsChangedAfterWeakening" to changedLeadWindows,
            "metricPolicy" to "Physical lead windows compare both stems, so a quieter weakened opponent can change the ratio without any change in the survivor. A changed lead-window count alone does not prove survivor behavior. Use entry timelines and the survivor-only stem together with the response-disabled exact-identity control. Waveform RMS difference includes phase changes and is not a percentage of behavioral interaction.")
    }

    private fun windowRms(samples: FloatArray, from: Double, until: Double): Double {
        val a = (from * Dsp.RATE).toInt().coerceAtLeast(0)
        val b = minOf(samples.size, (until * Dsp.RATE).toInt())
        require(b > a)
        var energy = 0.0
        for (i in a until b) energy += samples[i].toDouble() * samples[i]
        return sqrt(energy / (b - a))
    }

    private fun mono(samples: FloatArray) = Snip(samples, 1, Dsp.RATE)
    private fun scale(source: Snip, gain: Float) = mono(FloatArray(source.samples.size) { source.samples[it] * gain })
    private fun validate(snip: Snip) {
        require(snip.channels == 1 && snip.sampleRate == Dsp.RATE && snip.samples.isNotEmpty())
        require(snip.samples.all { it.isFinite() }) { "Non-finite model output" }
        require(snip.peak() > 1e-7f && Loudness.of(snip) > 1e-8f) { "Silent model output" }
    }

    private fun metrics(snip: Snip): Map<String, Any> = linkedMapOf(
        "frames" to snip.frameCount, "seconds" to snip.durationSeconds, "peak" to snip.peak(),
        "rms" to sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size),
        "mean" to snip.samples.sumOf { it.toDouble() } / snip.samples.size, "loudness" to Loudness.of(snip),
    )

    private fun rendererSources(): Map<String, String> {
        val source = File("src/main/kotlin/com/snipsnap/synth")
        return listOf("Obbligato.kt", "ObbligatoInteraction.kt", "ObbligatoPatch.kt", "Bore.kt", "Dsp.kt", "Strings.kt", "Keys.kt")
            .associateWith { name -> sha256(File(source, name).readBytes()) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> {
            require(value.toDouble().isFinite()) { "Non-finite audition metadata" }
            JsonValue.Num(value.toDouble())
        }
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported audition value: ${value.javaClass.name}")
    }

    private fun html(manifest: String, recordings: List<Map<String, Any?>>): String {
        val clips = recordings.associateBy { it.getValue("id") as String }
        fun player(id: String, label: String): String {
            val path = clips.getValue(id).getValue("path") as String
            return """<div class="listen"><span class="take-label">$label <a href="$path" data-audio-download="$path" download="$id.wav">WAV ↓</a></span><audio controls preload="none" aria-label="$label" data-path="$path" src="$path"></audio></div>"""
        }
        fun stems(id: String) = """<details><summary>Hear the players inside this duet</summary>${player("${id}_air", "AIR IN THE DUET")}${player("${id}_reed", "REED IN THE DUET")}<p>These are actual additive stems with the duet's exact gain, not independently normalized solos.</p></details>"""
        fun causal(id: String, title: String, playerName: String, window: String) = """
            <section class="card"><h3>$title</h3><p>$playerName is weakened during $window. Compare what the other player does.</p>
            ${player("${id}6_duet", "RESPONSES ENABLED · DUET")}
            ${player("${id}6_survivor", "RESPONSES ENABLED · UNWEAKENED PLAYER")}
            ${player("${id}_off6_duet", "RESPONSES DISABLED · DUET")}
            ${player("${id}_off6_survivor", "RESPONSES DISABLED · UNWEAKENED PLAYER")}
            </section>""".trimIndent()
        return """
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>OBBLIGATO · responsive Fight experiment</title><style>
:root{color-scheme:dark;--bg:#101511;--panel:#19221b;--ink:#eaf2e9;--muted:#acbbaa;--accent:#ff8a7a;--line:#354738}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.5 system-ui,sans-serif}main{max-width:1060px;margin:auto;padding:40px 22px 64px}.eyebrow,.take-label,label,summary{font:12px/1.5 ui-monospace,monospace;letter-spacing:.06em}.eyebrow,.take-label{color:var(--accent)}h1{font-size:clamp(34px,6vw,60px);letter-spacing:-.04em;margin:8px 0}h2{margin:0;font-size:25px}h3{margin:0;font-size:21px}p{color:var(--muted);margin:10px 0 18px}.intro{max-width:760px}a{color:var(--accent);text-underline-offset:3px}.tools{display:flex;gap:14px 22px;flex-wrap:wrap;margin:24px 0}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:20px;margin:22px 0 34px}.card,.note{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:22px}.listen{margin:18px 0}.take-label{display:flex;gap:12px;justify-content:space-between;align-items:center}audio{display:block;width:100%;margin-top:8px}details{border-top:1px solid var(--line);margin-top:18px;padding-top:14px}summary{cursor:pointer;min-height:36px}button,select,textarea{font:inherit;border:1px solid var(--line);border-radius:5px;padding:10px;background:#0f1711;color:var(--ink)}button{cursor:pointer;min-height:44px}select,textarea{width:100%;margin-top:8px}textarea{resize:vertical}label{display:block;color:var(--accent);margin-top:16px}button:focus-visible,a:focus-visible,summary:focus-visible{outline:2px solid var(--accent);outline-offset:4px}.small{font-size:14px}.status{min-height:1.5em;margin:8px 0}footer{margin-top:32px}code{overflow-wrap:anywhere} @media(max-width:680px){main{padding:25px 16px 45px}.grid{grid-template-columns:1fr}.card,.note{padding:18px}.take-label{font-size:11px}}
</style></head><body><main>
<div class="eyebrow">SNIPSNAP / OBBLIGATO / HISTORICAL EXPERIMENT</div>
<h1>Fight, with a response</h1>
<p class="intro">Compare the accepted model 5 with a separate model 6 experiment. In model 6, a player readies its next entry from what the other pipe is actually doing. This snapshot keeps the original model-5 reference.</p>
<div class="tools"><a href="$ARCHIVE" id="pack-link" download>Download all 19 WAVs and recipes ↓</a><a href="standalone.html" id="standalone-link" download>Single-file listening page ↓</a><a href="manifest.json" id="manifest-download" download="manifest.json">Settings and measurements ↓</a></div>
<p class="small" id="embedded-note" hidden>This single file embeds the audio and main patches. The full ZIP is a separate download from the published page.</p>
<p class="small">All recordings use one shared gain. The one-note comparisons use C4, velocity 0.8, seed 0 and the same BREATH, BOND, MISCHIEF and HOLD. Stem controls retain their complete duet's gain. There are no rack effects or sustain loops.</p>
<div class="grid">
<section class="card"><h2>Accepted model 5</h2><p>The existing Fight: unequal, scheduled challenges with reciprocal pressure response.</p>${player("accepted5_duet", "THE ACCEPTED DUET")}<a href="patches/accepted5.patch" data-patch="accepted5" download="accepted5.patch">Accepted patch ↓</a>${stems("accepted5")}</section>
<section class="card"><h2>Model 6 experiment</h2><p>Air asserts, reed readies and interrupts, then air prepares a counter. Listen for clear exchanges, continuous overlap and a complete ending.</p>${player("responsive6_duet", "THE RESPONSIVE DUET")}<a href="patches/responsive6.patch" data-patch="responsive6" download="responsive6.patch">Experimental patch ↓</a>${stems("responsive6")}</section>
</div>
<section class="card"><h2>Disable the decisions</h2><p>This removes activity-driven entries and reciprocal pressure response. The opening air assertion remains. It does not preserve the five scheduled turns of model 5.</p>${player("decisions_off6_duet", "MODEL 6 · RESPONSES DISABLED")}${stems("decisions_off6")}</section>
<h2 style="margin-top:34px">What happens when one player weakens?</h2>
<p>Both players still exist. Only one pipe receives the brief weakening. Its partner's isolated stem and the saved entry times let you examine the consequence. The response-disabled partner is an exact negative control before export. A change in which player is louder can happen simply because the weakened opponent gets quieter. To judge whether its partner reacts, compare the partner's isolated stem and entry times with that disabled-response control.</p>
<div class="grid">${causal("air_weakened", "Weaken the air", "The air source", "0.45–0.85 seconds")}${causal("reed_weakened", "Weaken the reed", "The reed source", "1.30–1.70 seconds")}</div>
<div class="note"><h2>Across four notes</h2><p>C4, E-flat4, F4 and G4, with a 120 ms gap after each complete gesture. Each note begins fresh; the relationship does not carry between notes.</p>${player("accepted5_phrase", "MODEL 5 · FOUR NOTES")}${player("responsive6_phrase", "MODEL 6 · FOUR NOTES")}</div>
<section class="note" style="margin-top:24px"><h2>Your listening verdict</h2><p>Does the new duet react more clearly while keeping the continuous exchange you accepted?</p><label for="verdict">EXPERIMENTAL MODEL 6</label><select id="verdict"><option value="">Choose a verdict</option><option>Keep exploring</option><option>Prefer model 5</option><option>Revise the interaction</option></select><label for="notes">WHAT DID YOU HEAR?</label><textarea id="notes" rows="4" placeholder="Which player leads, how does the other answer, and where does it feel too regular or disconnected?"></textarea><p><button type="button" id="export-notes">Export listening notes ↓</button></p><p class="status small" id="status" aria-live="polite"></p></section>
<footer><p class="small">Model 6 was proposed as a behavioral analogy, not a claim about human performance or acoustic realism. Readiness thresholds, crossover and pitch shapes await listening. Patch downloads store the normal full-response instrument; diagnostic disabling and weakening flags live in <code>diagnostics/</code> and are replayed by the generator. The ZIP contains the relative-file page and every recording; the separate single-file page embeds its audio.</p></footer>
<script id="manifest" type="application/json">${manifest.replace("<", "\\u003c")}</script>
<script>
const AUDIO = {};
document.querySelectorAll('audio[data-path]').forEach(a => { if (AUDIO[a.dataset.path]) a.src = AUDIO[a.dataset.path]; });
document.querySelectorAll('a[data-audio-download]').forEach(a => { if (AUDIO[a.dataset.audioDownload]) a.href = AUDIO[a.dataset.audioDownload]; });
if (Object.keys(AUDIO).length) { const manifest = JSON.parse(document.getElementById('manifest').textContent); document.getElementById('pack-link').hidden = true; document.getElementById('standalone-link').hidden = true; document.getElementById('embedded-note').hidden = false; document.getElementById('manifest-download').href = URL.createObjectURL(new Blob([JSON.stringify(manifest,null,2)+'\n'],{type:'application/json'})); document.querySelectorAll('a[data-patch]').forEach(a => { const take = manifest.takes.find(t => t.id === a.dataset.patch); a.href = URL.createObjectURL(new Blob([JSON.stringify(take.patch,null,2)+'\n'],{type:'application/json'})); }); }
const key = 'snipsnap-obbligato-responsive-model6-review-v1';
const verdict = document.getElementById('verdict'), notes = document.getElementById('notes'), status = document.getElementById('status');
try { const saved = JSON.parse(localStorage.getItem(key) || '{}'); verdict.value = saved.verdict || ''; notes.value = saved.notes || ''; } catch (_) { status.textContent = 'Browser storage is unavailable. Export your notes to keep them.'; }
function save() { try { localStorage.setItem(key, JSON.stringify({verdict: verdict.value, notes: notes.value})); status.textContent = 'Notes saved in this browser.'; } catch (_) { status.textContent = 'Export your notes to keep them.'; } }
verdict.addEventListener('change', save); notes.addEventListener('input', save);
document.getElementById('export-notes').addEventListener('click', () => { const record = {engine:'OBBLIGATO', referenceModel:5, experimentalModel:6, verdict:verdict.value, notes:notes.value, exportedAt:new Date().toISOString()}; const blob = new Blob([JSON.stringify(record,null,2)+'\n'],{type:'application/json'}); const url = URL.createObjectURL(blob), a = document.createElement('a'); a.href = url; a.download = 'obbligato-responsive-listening-notes.json'; a.click(); setTimeout(() => URL.revokeObjectURL(url),1000); status.textContent = 'Listening notes exported.'; });
</script></main></body></html>
""".trimIndent() + "\n"
    }

    private fun readme() = """
        OBBLIGATO responsive Fight audition

        This historical snapshot keeps model 5 as its original accepted reference and
        model 6 as the separate experiment. It does not follow the current factory default.

        Open index.html with audio/, patches/ and diagnostics/ beside it. The separately
        generated standalone.html embeds all audio; it is omitted from this ZIP to avoid
        duplicating every recording. Every WAV is dry mono PCM24 at 44.1 kHz.

        All nineteen WAVs use ONE linear gain, chosen from the accepted model-five duet
        and capped for the complete pack's headroom. Stems, disabled decisions and source
        weakening controls are never leveled independently. Stems reconstruct their duet
        within PCM quantization. A phase-sensitive waveform difference alone does not prove
        a different decision. Lead windows compare both stems, so their level ratio can change
        just because the weakened opponent gets quieter while the survivor stays identical.
        Their changed-window count alone does not prove survivor behavior. Read entry timelines
        and the survivor-only stem together with the exact response-disabled negative control;
        measurements.json retains all three observations.

        In model 6, responses=false disables activity-driven entries and reciprocal mouth
        pressure response. The opening air assertion remains. It does not replay the model-five
        scheduled phrase. The two weakening conditions briefly change one physical source;
        the other stem remains exact with responses disabled before common export gain.

        patches/ retains full macros, MIDI note, velocity, source seed and renderer model.
        Opening a .patch renders its ordinary full-response instrument. The extra diagnostic
        response/weakening flags are in diagnostics/ and replay through this generator, not
        the app's ordinary patch loader. The phrase clips use MIDI 60, 63, 65, 67 with 120 ms
        gaps after complete finite notes; each note starts two new source states.

        Manifest, diagnostic recipes, entry ledgers, stem lead windows, exact source hashes,
        written WAV hashes and numeric measurements accompany the recordings. No rack effects,
        stereo-width gesture, endless sustain or persistent relationship between pads is claimed.
    """.trimIndent() + "\n"
}
