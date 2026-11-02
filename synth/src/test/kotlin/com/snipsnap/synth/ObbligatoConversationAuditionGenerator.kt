package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Three conversations beside the gentler reference, including the actual player taps.
 * Complete normal duets and melodies are level matched. Taps and physical interventions
 * inherit the corresponding normal duet's gain, so a changed response stays measurable.
 */
object ObbligatoConversationAuditionGenerator {
    private const val MIDI = 60
    private const val VELOCITY = .8f
    private const val SEED = 0
    private const val TARGET_LEVEL = .12f
    private const val HEADROOM = .95f
    private const val ONSET_SECONDS = 2.0
    private const val ARCHIVE = "obbligato-conversation-audition.zip"
    private const val MAX_ASSET_BYTES = 25L * 1024 * 1024
    private val phraseMidi = listOf(60, 63, 67, 65)
    private val relationships = listOf("Play", "Fight", "Protect")

    private data class Take(val id: String, val title: String, val relationship: String,
        val patch: ObbligatoPatch, val report: Obbligato.Report,
        val responses: Boolean = true, val weakening: Obbligato.SourceWeakening? = null,
        val phrase: Snip? = null, val phrasePatches: List<ObbligatoPatch> = emptyList())
    private data class Clip(val id: String, val title: String, val take: Take, val role: String,
        val source: Snip, val levelGain: Float)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Usage: ObbligatoConversationAuditionGenerator [output-directory]" }
        require(Obbligato.LEGACY_MODEL == 5 && Obbligato.MUSICAL_MODEL == 7 && Obbligato.CONVERSATION_MODEL == 8)
        val root = File(args.firstOrNull() ?: "../testkit/obbligato-conversation-audition").absoluteFile
        check(root.isDirectory || root.mkdirs()) { "Could not create $root" }
        val normal = relationships.flatMap { relationship ->
            val base = ObbligatoPresets.all().single { it.name == relationship }
                .copy(midi = MIDI, velocity = VELOCITY, seed = SEED)
            listOf(Obbligato.MUSICAL_MODEL, Obbligato.CONVERSATION_MODEL).map { model ->
                val candidate = model == Obbligato.CONVERSATION_MODEL
                val id = "${relationship.lowercase()}${if (candidate) "8" else "7"}"
                val title = "$relationship · ${if (candidate) "conversation" else "gentler reference"}"
                val patch = base.copy(name = title, model = model)
                val report = probe(patch)
                val phrasePatches = phraseMidi.mapIndexed { index, midi ->
                    patch.copy(name = "$title phrase note ${index + 1}", midi = midi,
                        macros = patch.macros + ("HOLD" to 0f))
                }
                Take(id, title, relationship, patch, report, phrase = phrase(phrasePatches),
                    phrasePatches = phrasePatches)
            }
        }
        val protect = normal.single { it.id == "protect8" }
        val weakening = Obbligato.SourceWeakening(air = true, startSeconds = .95,
            endSeconds = 1.45, strength = .1f)
        fun diagnostic(id: String, title: String, responses: Boolean,
            intervention: Obbligato.SourceWeakening? = null) = Take(id, title, "Protect",
            protect.patch.copy(name = title), probe(protect.patch, responses, intervention), responses, intervention)
        val diagnostics = listOf(
            diagnostic("protect8_off", "Protect · responses disabled", responses = false),
            diagnostic("protect8_weak", "Protect · air briefly weakened", responses = true, intervention = weakening),
            diagnostic("protect8_weak_off", "Protect · air weakened, responses disabled", responses = false,
                intervention = weakening),
        )
        val takes = normal + diagnostics
        val normalGains = normal.associate { it.id to TARGET_LEVEL / Loudness.of(it.report.snip) }
        val clips = normal.flatMap { take ->
            val gain = normalGains.getValue(take.id)
            listOf(Clip("${take.id}_duet", take.title, take, "duet", take.report.snip, gain),
                Clip("${take.id}_phrase", "${take.title} · melody", take, "phrase", requireNotNull(take.phrase),
                    TARGET_LEVEL / Loudness.of(requireNotNull(take.phrase)))) +
                if (take.patch.model == Obbligato.CONVERSATION_MODEL) listOf(
                    Clip("${take.id}_air", "${take.title} · air", take, "air", mono(take.report.air), gain),
                    Clip("${take.id}_reed", "${take.title} · reed", take, "reed", mono(take.report.reed), gain))
                else emptyList()
        } + diagnostics.flatMap { take ->
            val gain = normalGains.getValue("protect8")
            listOf(Clip("${take.id}_duet", take.title, take, "diagnostic_duet", take.report.snip, gain),
                Clip("${take.id}_reed", "${take.title} · unweakened reed", take, "diagnostic_reed",
                    mono(take.report.reed), gain))
        }
        require(clips.size == 24)
        clips.forEach { validate(it.source); require(it.levelGain.isFinite() && it.levelGain > 0f) }
        val off = diagnostics.single { it.id == "protect8_off" }
        val weakenedOff = diagnostics.single { it.id == "protect8_weak_off" }
        require(off.report.reed.contentEquals(weakenedOff.report.reed)) {
            "The response-disabled survivor changed when only the air source was physically weakened"
        }
        require(!off.report.air.contentEquals(weakenedOff.report.air)) { "The physical intervention did nothing" }
        val commonAttenuation = minOf(1f, HEADROOM / clips.maxOf { it.source.peak() * it.levelGain })
        val archiveFiles = linkedSetOf<File>()
        fun writeText(path: String, content: String): File = File(root, path).also {
            it.parentFile.mkdirs(); it.writeText(content); archiveFiles += it
        }
        fun writeJson(path: String, value: Any?) = writeText(path, Json.write(json(value)) + "\n")
        val recordings = clips.map { clip ->
            val gain = clip.levelGain * commonAttenuation
            val output = mono(FloatArray(clip.source.samples.size) { clip.source.samples[it] * gain })
            require(output.peak() <= HEADROOM + 1e-5f)
            val path = "audio/${clip.id}.wav"
            val file = WavWriter.write(File(root, path), output, WavWriter.BitDepth.PCM_24)
            archiveFiles += file
            linkedMapOf<String, Any?>("id" to clip.id, "title" to clip.title, "take" to clip.take.id,
                "relationship" to clip.take.relationship, "role" to clip.role, "model" to clip.take.patch.model,
                "path" to path, "gain" to gain, "levelMatchGain" to clip.levelGain,
                "bytes" to file.length(), "sha256" to hash(file.readBytes()),
                "sourceMetrics" to metrics(clip.source), "exportMetrics" to metrics(output))
        }
        val takeRows = takes.map { take ->
            val patchPath = "patches/${take.id}.patch"
            val patchFile = writeText(patchPath, take.patch.toJsonText() + "\n")
            val phraseRows = take.phrasePatches.mapIndexed { index, patch ->
                val path = "patches/${take.id}_phrase_${index + 1}.patch"
                val file = writeText(path, patch.toJsonText() + "\n")
                linkedMapOf("onsetSeconds" to index * ONSET_SECONDS, "patch" to patch.toJsonValue(),
                    "patchPath" to path, "sha256" to hash(file.readBytes()))
            }
            if (phraseRows.isNotEmpty()) writeJson("recipes/${take.id}_phrase.json", linkedMapOf(
                "notes" to phraseRows, "responses" to true,
                "mixPolicy" to "Sum complete raw finite notes at the listed onsets, preserving every release tail; level the whole melody once."))
            val diagnosticPath = "diagnostics/${take.id}.json"
            val diagnostic = linkedMapOf<String, Any?>("schemaVersion" to 1,
                "patch" to take.patch.toJsonValue(), "responses" to take.responses,
                "weakening" to weakening(take.weakening), "conversationEvents" to events(take.report),
                "diagnostics" to take.report.diagnostics,
                "probeApi" to if (take.weakening == null) "Obbligato.probe" else "Obbligato.probeWithWeakening",
                "patchPolicy" to "The .patch stores the normal instrument. Diagnostic responses and source weakening are separate probe arguments, retained here for replay.")
            writeJson(diagnosticPath, diagnostic)
            linkedMapOf<String, Any?>("id" to take.id, "title" to take.title, "relationship" to take.relationship,
                "patch" to take.patch.toJsonValue(), "patchPath" to patchPath,
                "patchSha256" to hash(patchFile.readBytes()), "phraseNotes" to phraseRows,
                "responses" to take.responses, "weakening" to weakening(take.weakening),
                "diagnosticPath" to diagnosticPath, "conversationEvents" to events(take.report),
                "diagnostics" to take.report.diagnostics)
        }
        val weakened = diagnostics.single { it.id == "protect8_weak" }
        fun consequentialEvents(report: Obbligato.Report) = report.conversationEvents.map {
            listOf(it.seconds, it.actor, it.kind, it.strength, it.duration)
        }
        val causal = linkedMapOf<String, Any?>(
            "weakening" to weakening(weakening),
            "responseOnEventsChanged" to (consequentialEvents(protect.report) != consequentialEvents(weakened.report)),
            "eventComparisonFields" to listOf("seconds", "actor", "kind", "strength", "duration"),
            "responseOnSurvivorChanged" to !protect.report.reed.contentEquals(weakened.report.reed),
            "responseOffSurvivorFloatIdentical" to true,
            "responseOffSurvivorFloat32LittleEndianSha256" to floatHash(off.report.reed),
            "responseOffWeakenedSurvivorFloat32LittleEndianSha256" to floatHash(weakenedOff.report.reed),
            "interpretation" to "The physical intervention lowers only air pressure. Compare actual response events and the unweakened reed with responses enabled; the two response-disabled reed taps must be exactly identical. Their shared gain is the normal Protect conversation gain.")
        writeJson("measurements.json", causal)
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "engine" to "OBBLIGATO", "audition" to "Play, Fight and Protect conversations",
            "status" to "Historical comparison: model 5 was accepted when the model 8 conversation revision was first reviewed.",
            "acceptedModel" to Obbligato.LEGACY_MODEL, "previousModel" to Obbligato.MUSICAL_MODEL,
            "candidateModel" to Obbligato.CONVERSATION_MODEL,
            "relationships" to relationships, "sampleRate" to Dsp.RATE, "channels" to 1, "pcmBits" to 24,
            "midi" to MIDI, "velocity" to VELOCITY, "seed" to SEED,
            "targetLoudnessBeforeHeadroom" to TARGET_LEVEL, "commonHeadroomAttenuation" to commonAttenuation,
            "effectiveTargetLoudness" to TARGET_LEVEL * commonAttenuation,
            "gainPolicy" to "Match complete normal duets and complete melodies to the same house loudness; actual taps inherit their own duet's gain. All Protect intervention recordings inherit the normal Protect conversation gain. One common final attenuation protects pack-wide headroom. No isolated tap or intervention is independently normalized.",
            "phraseMidi" to phraseMidi, "phraseOnsetSeconds" to ONSET_SECONDS,
            "phrasePolicy" to "C4, E-flat4, G4, F4; HOLD 0, two-second onset spacing. Each note begins fresh, with the complete 0.75-second release overlapping the following note.",
            "effects" to emptyList<String>(), "looping" to false,
            "checks" to linkedMapOf("finiteSamples" to true, "headroomPassed" to true,
                "additiveSourceTaps" to true, "stemGainMatchesDuet" to true,
                "responseOffSurvivorIdentity" to true, "wavFiles" to clips.size),
            "rendererSources" to rendererSources(), "takes" to takeRows, "clips" to recordings,
            "causalComparison" to causal,
            "ledger" to linkedMapOf(
                "verified" to "Complete normal and intervention recipes, actual additive source taps, conversation event summaries and written PCM24 hashes.",
                "measured" to "Kotlin renders: level, finite output, headroom, DC, one-window pitch estimates and exact response-disabled survivor identity.",
                "proposed" to "Articulation, remembered answers and shaped endings remain musical design choices for listening.",
                "heard" to "The owner called the gentler revision a better direction and requested greater depth. No listening verdict is claimed for the conversation revision."))
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
        val standalonePage = page.replace("const AUDIO = {};", "const AUDIO = ${Json.write(json(embedded))};")
        if (standalonePage.toByteArray(Charsets.UTF_8).size < MAX_ASSET_BYTES) {
            standalone.writeText(standalonePage)
        } else {
            if (standalone.exists()) check(standalone.delete())
            File(root, "index.html").writeText(page.replace("<a href=\"standalone.html\" id=\"standalone-link\" download>Single-file page ↓</a>", ""))
        }
        val archive = File(root, ARCHIVE)
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            archiveFiles.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath).also { it.time = 0L })
                file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        require(archive.length() < MAX_ASSET_BYTES) { "Audition ZIP exceeds 25 MiB" }
        require(root.walkTopDown().filter { it.isFile }.all { it.length() < MAX_ASSET_BYTES })
        println("Wrote ${clips.size} PCM24 recordings, exact normal/phrase/intervention recipes, comparison pages and ${archive.absolutePath}")
    }

    private fun probe(patch: ObbligatoPatch, responses: Boolean = true,
        intervention: Obbligato.SourceWeakening? = null): Obbligato.Report {
        val report = if (intervention == null) Obbligato.probe(patch.macros, patch.midi,
            patch.velocity, patch.seed, responses = responses, model = patch.model)
        else Obbligato.probeWithWeakening(patch.macros, patch.midi, patch.velocity, patch.seed,
            responses = responses, model = patch.model, weakening = intervention)
        validate(report.snip)
        require(report.air.size == report.snip.samples.size && report.reed.size == report.air.size)
        require(report.air.indices.maxOf { abs(report.air[it].toDouble() + report.reed[it] - report.snip.samples[it]) } < 1e-6) {
            "Actual source taps no longer reconstruct ${patch.name}"
        }
        return report
    }

    private fun phrase(patches: List<ObbligatoPatch>): Snip {
        val notes = patches.map { probe(it).snip }
        val spacing = (ONSET_SECONDS * Dsp.RATE).toInt()
        val output = FloatArray(notes.indices.maxOf { it * spacing + notes[it].frameCount })
        notes.forEachIndexed { index, note ->
            for (i in note.samples.indices) output[index * spacing + i] += note.samples[i]
        }
        return mono(output)
    }

    private fun events(report: Obbligato.Report): List<Map<String, Any?>> = report.conversationEvents.map {
        linkedMapOf("seconds" to it.seconds, "actor" to it.actor.name, "kind" to it.kind,
            "rememberedDuration" to it.rememberedDuration, "rememberedIntensity" to it.rememberedIntensity,
            "rememberedSlope" to it.rememberedSlope, "strength" to it.strength, "duration" to it.duration,
            "airReserve" to it.airReserve, "reedReserve" to it.reedReserve,
            "heardAir" to it.heardAir, "heardReed" to it.heardReed)
    }

    private fun weakening(value: Obbligato.SourceWeakening?): Map<String, Any?>? = value?.let {
        linkedMapOf("air" to it.air, "startSeconds" to it.startSeconds,
            "endSeconds" to it.endSeconds, "strength" to it.strength)
    }
    private fun mono(samples: FloatArray) = Snip(samples, 1, Dsp.RATE)
    private fun validate(snip: Snip) {
        require(snip.channels == 1 && snip.sampleRate == Dsp.RATE && snip.samples.isNotEmpty())
        require(snip.samples.all { it.isFinite() }) { "Non-finite audition audio" }
        require(snip.peak() > 1e-7f && Loudness.of(snip) > 1e-8f) { "Silent audition recording" }
    }
    private fun metrics(snip: Snip): Map<String, Any?> {
        val pitch = Pitch.detect(snip, fromSec = .8f, windowSec = .25f)
        return linkedMapOf("frames" to snip.frameCount, "seconds" to snip.durationSeconds,
            "peak" to snip.peak(), "loudness" to Loudness.of(snip),
            "rms" to sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size),
            "mean" to snip.samples.sumOf { it.toDouble() } / snip.samples.size,
            "pitchWindowSeconds" to listOf(.8, 1.05), "pitchHz" to pitch?.hz, "pitchConfidence" to pitch?.confidence)
    }
    private fun rendererSources(): Map<String, String> {
        val paths = listOf("src/main/kotlin/com/snipsnap/synth/Obbligato.kt",
            "src/main/kotlin/com/snipsnap/synth/ObbligatoConversation.kt",
            "src/main/kotlin/com/snipsnap/synth/ObbligatoInteraction.kt", "src/main/kotlin/com/snipsnap/synth/ObbligatoPatch.kt",
            "src/main/kotlin/com/snipsnap/synth/Bore.kt", "src/main/kotlin/com/snipsnap/synth/Dsp.kt",
            "src/main/kotlin/com/snipsnap/synth/Strings.kt", "src/main/kotlin/com/snipsnap/synth/Keys.kt",
            "src/main/kotlin/com/snipsnap/synth/Tide.kt", "../audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt",
            "../audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt", "../audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
            "../audio/src/main/kotlin/com/snipsnap/audio/Pitch.kt", "../audio/src/main/kotlin/com/snipsnap/audio/Fft.kt",
            "src/test/kotlin/com/snipsnap/synth/ObbligatoConversationAuditionGenerator.kt")
        return paths.associateWith { hash(File(it).readBytes()) }
    }
    private fun floatHash(samples: FloatArray): String {
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putFloat(it) }
        return hash(buffer.array())
    }
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> { require(value.toDouble().isFinite()); JsonValue.Num(value.toDouble()) }
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported audition metadata: ${value.javaClass.name}")
    }

    private fun html(manifest: String, recordings: List<Map<String, Any?>>): String {
        val byId = recordings.associateBy { it.getValue("id") as String }
        fun player(id: String, label: String): String {
            val path = byId.getValue(id).getValue("path") as String
            return """<div class="listen"><span class="take-label">$label <a href="$path" data-audio-download="$path" download="$id.wav">WAV ↓</a></span><audio controls preload="none" aria-label="$label" data-path="$path" src="$path"></audio></div>"""
        }
        fun patch(id: String) = """<a href="patches/$id.patch" data-patch="$id" download="$id.patch">Instrument patch ↓</a>"""
        val descriptions = mapOf("Play" to "Hear a statement picked up and reshaped by the other player.",
            "Fight" to "Hear assertions, interruptions and counters develop toward a shared ending.",
            "Protect" to "Hear the flute open, with the reed offering support and carrying the ending.")
        val sections = relationships.joinToString("\n") { name ->
            val id = name.lowercase()
            """<section class="relationship" id="$id"><h2>$name</h2><p>${descriptions.getValue(name)}</p><div class="grid"><section class="card"><h3>Gentler reference</h3>${player("${id}7_duet", "$name · REFERENCE NOTE")}${player("${id}7_phrase", "$name · REFERENCE MELODY")}${patch("${id}7")}</section><section class="card"><h3>Conversation</h3>${player("${id}8_duet", "$name · CONVERSATION NOTE")}${player("${id}8_phrase", "$name · CONVERSATION MELODY")}${patch("${id}8")}</section></div><details><summary>Hear the players inside $name</summary><p>These actual source taps retain their complete conversation's gain.</p>${player("${id}8_air", "$name · AIR")}${player("${id}8_reed", "$name · REED")}</details></section>"""
        }
        return """
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>OBBLIGATO · three conversations</title><style>
:root{color-scheme:dark;--bg:#101511;--panel:#19221b;--ink:#eaf2e9;--muted:#acbbaa;--accent:#ff8a7a;--line:#354738}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.5 system-ui,sans-serif}main{max-width:1060px;margin:auto;padding:40px 22px 64px}.eyebrow,.take-label,label,summary{font:12px/1.5 ui-monospace,monospace;letter-spacing:.05em}.eyebrow,.take-label{color:var(--accent)}h1{font-size:clamp(34px,6vw,60px);letter-spacing:-.04em;margin:8px 0}h2{font-size:32px;margin:0}h3{font-size:22px;margin:0}p{color:var(--muted);margin:10px 0 18px}.intro{max-width:790px}a{color:var(--accent);text-underline-offset:3px}.tools{display:flex;gap:14px 22px;flex-wrap:wrap;margin:24px 0}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:20px;margin:22px 0}.card,.note{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:22px}.relationship{margin:36px 0;padding-top:22px;border-top:1px solid var(--line)}.listen{margin:18px 0}.take-label{display:flex;gap:12px;justify-content:space-between;align-items:center}audio{display:block;width:100%;margin-top:8px}details{border-top:1px solid var(--line);margin-top:18px;padding-top:14px}summary{cursor:pointer;min-height:36px}button,select,textarea{font:inherit;border:1px solid var(--line);border-radius:5px;padding:10px;background:#0f1711;color:var(--ink)}button{cursor:pointer;min-height:44px}select,textarea{width:100%;margin-top:8px}textarea{resize:vertical}label{display:block;color:var(--accent);margin-top:16px}button:focus-visible,a:focus-visible,summary:focus-visible{outline:2px solid var(--accent);outline-offset:4px}.small{font-size:14px}.status{min-height:1.5em;margin:8px 0}footer{margin-top:32px}@media(max-width:680px){main{padding:25px 16px 45px}.grid{grid-template-columns:1fr}.card,.note{padding:18px}.take-label{font-size:11px}}
</style></head><body><main>
<div class="eyebrow">SNIPSNAP / OBBLIGATO / HISTORICAL COMPARISON</div><h1>Three conversations</h1>
<p class="intro">The gentler duet develops through remembered answers and individual articulations. Compare Play, Fight and Protect on a single note, then in the same short melody. Start with the relationship you know best.</p>
<div class="tools"><a href="#play">Play</a><a href="#fight">Fight</a><a href="#protect">Protect</a><a href="$ARCHIVE" id="pack-link" download>Download all 24 WAVs and recipes ↓</a><a href="standalone.html" id="standalone-link" download>Single-file page ↓</a><a href="manifest.json" id="manifest-download" download="manifest.json">Settings and measurements ↓</a></div>
<p class="small">Complete normal notes and melodies are matched to the same loudness. Actual source taps and the support checks keep their normal duet's gain. Dry mono, without rack effects. This archived comparison retains its original listening settings.</p>
<p class="small">The melody is C4, E-flat4, G4, F4. Each new note starts as the previous one releases, preserving its whole tail. Each note begins a fresh duet.</p><p class="small" id="embedded-note" hidden>This file embeds all 24 recordings and the main instrument patches. The complete recipe ZIP is available from the published page.</p>
$sections
<details id="support-check"><summary>Explore how Protect responds when the flute weakens</summary><p>Only the flute's blowing pressure is briefly lowered, from 0.95 to 1.45 seconds. Hear the full duet and the unweakened reed. Compare the two response-disabled reed recordings: they are exactly identical, showing that the intervention changes only the flute when responses are disabled.</p><div class="grid"><section class="card"><h3>Responses enabled</h3>${player("protect8_weak_duet", "WEAKENED FLUTE · DUET")}${player("protect8_weak_reed", "WEAKENED FLUTE · REED")}</section><section class="card"><h3>Responses disabled</h3>${player("protect8_off_duet", "NO INTERVENTION · DUET")}${player("protect8_off_reed", "NO INTERVENTION · REED")}${player("protect8_weak_off_duet", "WEAKENED FLUTE · DUET")}${player("protect8_weak_off_reed", "WEAKENED FLUTE · REED")}</section></div><p class="small">These supporting recordings use the normal Protect conversation's exact gain. The downloaded diagnostic recipes retain response and intervention flags; ordinary instrument patches retain normal playback.</p></details>
<section class="note" style="margin-top:30px"><h2>Your listening notes</h2><label for="verdict">THE NEW CONVERSATIONS</label><select id="verdict"><option value="">Choose a verdict</option><option>More expressive and musical</option><option>Relationships need more contrast</option><option>Too busy or distracting</option><option>Prefer the gentler reference</option></select><label for="notes">WHAT DID YOU HEAR?</label><textarea id="notes" rows="5" placeholder="Which relationship develops most convincingly? Do the answers feel connected? Does either player disappear?"></textarea><p><button type="button" id="export-notes">Export listening notes ↓</button></p><p class="status small" id="status" aria-live="polite"></p></section>
<footer><p class="small">The conversation revision is a separate saved instrument. Complete patches, melody onsets, physical intervention recipes, event summaries and recording hashes are included in the download. This archive preserves the comparison that led to later revisions.</p></footer>
<script id="manifest" type="application/json">${manifest.replace("<", "\\u003c")}</script><script>
const AUDIO = {};
const manifest = JSON.parse(document.getElementById('manifest').textContent);
document.querySelectorAll('audio[data-path]').forEach(a=>{if(AUDIO[a.dataset.path])a.src=AUDIO[a.dataset.path];});
document.querySelectorAll('a[data-audio-download]').forEach(a=>{if(AUDIO[a.dataset.audioDownload])a.href=AUDIO[a.dataset.audioDownload];});
if(Object.keys(AUDIO).length){document.getElementById('pack-link').hidden=true;document.getElementById('standalone-link').hidden=true;document.getElementById('embedded-note').hidden=false;document.getElementById('manifest-download').href=URL.createObjectURL(new Blob([JSON.stringify(manifest,null,2)+'\n'],{type:'application/json'}));document.querySelectorAll('a[data-patch]').forEach(a=>{const take=manifest.takes.find(t=>t.id===a.dataset.patch);a.href=URL.createObjectURL(new Blob([JSON.stringify(take.patch,null,2)+'\n'],{type:'application/json'}));});}
document.querySelectorAll('audio').forEach(a=>a.addEventListener('play',()=>document.querySelectorAll('audio').forEach(other=>{if(other!==a)other.pause();})));
const key='snipsnap-obbligato-conversations-review-v1',verdict=document.getElementById('verdict'),notes=document.getElementById('notes'),status=document.getElementById('status');
try{const saved=JSON.parse(localStorage.getItem(key)||'{}');verdict.value=saved.verdict||'';notes.value=saved.notes||'';}catch(_){status.textContent='Browser storage is unavailable. Export your notes to keep them.';}
function save(){try{localStorage.setItem(key,JSON.stringify({verdict:verdict.value,notes:notes.value}));status.textContent='Notes saved in this browser.';}catch(_){status.textContent='Export your notes to keep them.';}}
verdict.addEventListener('change',save);notes.addEventListener('input',save);
document.getElementById('export-notes').addEventListener('click',()=>{const record={engine:'OBBLIGATO',acceptedModel:5,previousModel:7,candidateModel:8,relationships:manifest.relationships,verdict:verdict.value,notes:notes.value,exportedAt:new Date().toISOString()};const url=URL.createObjectURL(new Blob([JSON.stringify(record,null,2)+'\n'],{type:'application/json'})),a=document.createElement('a');a.href=url;a.download='obbligato-conversation-listening-notes.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);status.textContent='Listening notes exported.';});
</script></main></body></html>
""".trimIndent() + "\n"
    }

    private fun readme() = """
        OBBLIGATO: Play, Fight and Protect conversations

        Compare each gentler reference with its conversation revision. Both use the same
        C4, velocity 0.8, seed 0, BREATH 0.65, MISCHIEF 0.2 and HOLD 0.45. This historical snapshot
        retains the original recipes. All 24 recordings are dry mono PCM24 at 44.1 kHz.

        Open index.html beside audio/, patches/, recipes/, diagnostics/ and manifest.json.
        If provided, standalone.html embeds the audio and main patches for offline use.
        It is excluded from the ZIP to avoid duplicating audio. Large packs omit that page.

        Normal complete duets and complete melodies are matched to the same house loudness
        of 0.12 before one common pack-wide headroom attenuation. Actual source taps inherit
        their own normal duet's exact gain. Every Protect response/intervention recording
        inherits the normal Protect conversation gain, never its own independent leveling.

        The melody plays C4, E-flat4, G4, F4 with HOLD 0 at two-second onset spacing.
        Every complete finite release is preserved and overlaps the next onset. Every note
        begins fresh; memory belongs within that note, not between pads or melody notes.

        The support check briefly lowers only physical air pressure to 10%, with smooth
        edges, from 0.95 to 1.45 seconds. The unweakened reed's response-disabled taps are
        verified Float32-identical. Ordinary .patch files store the normal instrument.
        diagnostics/*.json retains separate response flags, weakening parameters and probe
        API for exact diagnostic replay. recipes/*.json retains full phrase note recipes
        and onset times. Manifest.json includes recorded conversation events and memory
        summaries, actual metrics, linear gains, source fingerprints and PCM24 WAV hashes.

        A one-window pitch estimate of a duet is not an estimate of both individual players.
        Measurements describe the recordings; musical depth still needs the owner's ears.
    """.trimIndent() + "\n"
}
