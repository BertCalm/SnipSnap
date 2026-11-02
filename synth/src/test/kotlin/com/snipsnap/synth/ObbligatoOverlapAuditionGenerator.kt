package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
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
import kotlin.math.sqrt

/**
 * A focused comparison of previous and overlapping handoffs. Complete duets and melodies
 * are level matched; both actual player taps retain their own duet's exact gain.
 */
object ObbligatoOverlapAuditionGenerator {
    private const val MIDI = 60
    private const val VELOCITY = .8f
    private const val SEED = 0
    private const val TARGET_LEVEL = .12f
    private const val HEADROOM = .95f
    private const val ONSET_SECONDS = 2.0
    private const val ARCHIVE = "obbligato-overlap-audition.zip"
    private const val MAX_ASSET_BYTES = 25L * 1024 * 1024
    private val phraseMidi = listOf(60, 63, 67, 65)
    private val relationships = listOf("Play", "Fight", "Protect")

    private data class Take(val id: String, val title: String, val relationship: String,
        val patch: ObbligatoPatch, val report: Obbligato.Report,
        val phrase: Snip, val phrasePatches: List<ObbligatoPatch>)
    private data class Clip(val id: String, val title: String, val take: Take, val role: String,
        val source: Snip, val levelGain: Float)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Usage: ObbligatoOverlapAuditionGenerator [output-directory]" }
        require(Obbligato.LEGACY_MODEL == 5 && Obbligato.CONVERSATION_MODEL == 8 && Obbligato.OVERLAP_MODEL == 9)
        val root = File(args.firstOrNull() ?: "../testkit/obbligato-overlap-audition").absoluteFile
        check(root.isDirectory || root.mkdirs()) { "Could not create $root" }
        val takes = relationships.flatMap { relationship ->
            val base = ObbligatoPresets.all().single { it.name == relationship }
                .copy(midi = MIDI, velocity = VELOCITY, seed = SEED)
            listOf(Obbligato.CONVERSATION_MODEL, Obbligato.OVERLAP_MODEL).map { model ->
                val candidate = model == Obbligato.OVERLAP_MODEL
                val id = "${relationship.lowercase()}${if (candidate) "9" else "8"}"
                val title = "$relationship · ${if (candidate) "overlapping handoff" else "previous handoff"}"
                val patch = base.copy(name = title, model = model)
                val report = probe(patch)
                val phrasePatches = phraseMidi.mapIndexed { index, midi ->
                    patch.copy(name = "$title phrase note ${index + 1}", midi = midi,
                        macros = patch.macros + ("HOLD" to 0f))
                }
                Take(id, title, relationship, patch, report, phrase(phrasePatches), phrasePatches)
            }
        }
        val clips = takes.flatMap { take ->
            val gain = TARGET_LEVEL / Loudness.of(take.report.snip)
            listOf(Clip("${take.id}_duet", take.title, take, "duet", take.report.snip, gain),
                Clip("${take.id}_phrase", "${take.title} · melody", take, "phrase", take.phrase,
                    TARGET_LEVEL / Loudness.of(take.phrase)),
                Clip("${take.id}_air", "${take.title} · air", take, "air", mono(take.report.air), gain),
                Clip("${take.id}_reed", "${take.title} · reed", take, "reed", mono(take.report.reed), gain))
        }
        require(clips.size == 24)
        clips.forEach { validate(it.source); require(it.levelGain.isFinite() && it.levelGain > 0f) }
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
            writeJson("recipes/${take.id}_phrase.json", linkedMapOf("notes" to phraseRows,
                "mixPolicy" to "Sum complete raw finite notes at the listed onsets, preserving every release tail; level the whole melody once."))
            val diagnosticPath = "diagnostics/${take.id}.json"
            writeJson(diagnosticPath, linkedMapOf("schemaVersion" to 1, "patch" to take.patch.toJsonValue(),
                "responses" to true, "sourceA" to true, "sourceB" to true,
                "probeApi" to "Obbligato.probe", "conversationEvents" to events(take.report),
                "handoffEvents" to handoffs(take.report), "diagnostics" to take.report.diagnostics))
            linkedMapOf<String, Any?>("id" to take.id, "title" to take.title, "relationship" to take.relationship,
                "patch" to take.patch.toJsonValue(), "patchPath" to patchPath,
                "patchSha256" to hash(patchFile.readBytes()), "phraseNotes" to phraseRows,
                "diagnosticPath" to diagnosticPath, "conversationEvents" to events(take.report),
                "handoffEvents" to handoffs(take.report), "diagnostics" to take.report.diagnostics)
        }
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "engine" to "OBBLIGATO", "audition" to "Overlapping handoffs",
            "status" to "Historical comparison: model 5 was accepted when overlapping model 9 was first compared with model 8.",
            "acceptedModel" to Obbligato.LEGACY_MODEL, "previousModel" to Obbligato.CONVERSATION_MODEL,
            "candidateModel" to Obbligato.OVERLAP_MODEL, "relationships" to relationships,
            "sampleRate" to Dsp.RATE, "channels" to 1, "pcmBits" to 24,
            "midi" to MIDI, "velocity" to VELOCITY, "seed" to SEED,
            "targetLoudnessBeforeHeadroom" to TARGET_LEVEL, "commonHeadroomAttenuation" to commonAttenuation,
            "effectiveTargetLoudness" to TARGET_LEVEL * commonAttenuation,
            "gainPolicy" to "Match complete duets and complete melodies to the same house loudness; actual taps inherit their own duet's exact gain. One common final attenuation protects pack-wide headroom. No isolated tap is independently normalized.",
            "handoffDesign" to linkedMapOf("status" to "Proposed listening shape, not a listening verdict or an acoustic measurement.",
                "previousSeconds" to ObbligatoConversation.CROSSOVER,
                "incomingPreludeSeconds" to ObbligatoConversation.OVERLAP_PRELUDE,
                "middleCrossoverSeconds" to ObbligatoConversation.CROSSOVER,
                "outgoingTailSeconds" to ObbligatoConversation.OVERLAP_TAIL,
                "totalSeconds" to ObbligatoConversation.OVERLAP_SECONDS,
                "supportPresence" to ObbligatoConversation.OVERLAP_SUPPORT,
                "appliesTo" to listOf("ANSWER", "COUNTER", "SUPPORT"),
                "policy" to "Only actual actor changes overlap. Opening, renewed assertions and settling retain the preceding design. A common power budget controls the two envelopes; it is not a final mix loudness claim."),
            "phraseMidi" to phraseMidi, "phraseOnsetSeconds" to ONSET_SECONDS,
            "phrasePolicy" to "C4, E-flat4, G4, F4; HOLD 0, two-second onset spacing. Each note begins fresh, with the complete 0.75-second release overlapping the following note.",
            "effects" to emptyList<String>(), "looping" to false,
            "checks" to linkedMapOf("finiteSamples" to true, "headroomPassed" to true,
                "additiveSourceTaps" to true, "stemGainMatchesDuet" to true, "wavFiles" to clips.size),
            "rendererSources" to rendererSources(), "takes" to takeRows, "clips" to recordings,
            "ledger" to linkedMapOf(
                "verified" to "Complete instrument and phrase recipes, actual additive source taps, recorded event summaries and written PCM24 hashes.",
                "measured" to "Kotlin renders: level, finite output, headroom, DC and one-window pitch estimates.",
                "proposed" to "The entrance prelude and outgoing tail are listening choices; measurements alone cannot establish a more connected musical phrase.",
                "heard" to "The owner described the conversation as a better direction and asked for some overlap when the players pass. No listening verdict is claimed for this candidate."))
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
        println("Wrote ${clips.size} PCM24 recordings, full handoff and melody recipes, comparison pages and ${archive.absolutePath}")
    }

    private fun probe(patch: ObbligatoPatch): Obbligato.Report {
        val report = Obbligato.probe(patch.macros, patch.midi, patch.velocity, patch.seed, model = patch.model)
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

    private fun handoffs(report: Obbligato.Report): List<Map<String, Any?>> {
        var previous = ObbligatoConversation.Player.AIR
        return report.conversationEvents.mapNotNull { event ->
            val changes = event.actor != previous && event.kind in setOf("ANSWER", "COUNTER", "SUPPORT")
            val prior = previous
            previous = event.actor
            if (changes) linkedMapOf("seconds" to event.seconds, "previousActor" to prior.name,
                "actor" to event.actor.name, "kind" to event.kind) else null
        }
    }

    private fun events(report: Obbligato.Report): List<Map<String, Any?>> = report.conversationEvents.map {
        linkedMapOf("seconds" to it.seconds, "actor" to it.actor.name, "kind" to it.kind,
            "rememberedDuration" to it.rememberedDuration, "rememberedIntensity" to it.rememberedIntensity,
            "rememberedSlope" to it.rememberedSlope, "strength" to it.strength, "duration" to it.duration,
            "airReserve" to it.airReserve, "reedReserve" to it.reedReserve,
            "heardAir" to it.heardAir, "heardReed" to it.heardReed)
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
            "src/test/kotlin/com/snipsnap/synth/ObbligatoOverlapAuditionGenerator.kt")
        return paths.associateWith { hash(File(it).readBytes()) }
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
        val descriptions = mapOf("Play" to "Hear the answer enter while the other player's phrase is still present.",
            "Fight" to "Hear a challenge arrive before the previous assertion has fully yielded.",
            "Protect" to "Hear the reed offer support beneath the flute, then carry the ending.")
        val sections = relationships.joinToString("\n") { name ->
            val id = name.lowercase()
            """<section class="relationship" id="$id"><h2>$name</h2><p>${descriptions.getValue(name)}</p><div class="grid"><section class="card"><h3>Previous handoff</h3>${player("${id}8_duet", "$name · PREVIOUS NOTE")}${player("${id}8_phrase", "$name · PREVIOUS MELODY")}${patch("${id}8")}</section><section class="card"><h3>Overlapping handoff</h3>${player("${id}9_duet", "$name · OVERLAPPING NOTE")}${player("${id}9_phrase", "$name · OVERLAPPING MELODY")}${patch("${id}9")}</section></div><details><summary>Hear the players inside $name</summary><p>Actual player taps, each retaining its own complete duet's gain.</p><div class="grid"><section class="card"><h3>Previous</h3>${player("${id}8_air", "$name · PREVIOUS AIR")}${player("${id}8_reed", "$name · PREVIOUS REED")}</section><section class="card"><h3>Overlapping</h3>${player("${id}9_air", "$name · OVERLAPPING AIR")}${player("${id}9_reed", "$name · OVERLAPPING REED")}</section></div></details></section>"""
        }
        return """
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>OBBLIGATO · overlapping handoffs</title><style>
:root{color-scheme:dark;--bg:#101511;--panel:#19221b;--ink:#eaf2e9;--muted:#acbbaa;--accent:#ff8a7a;--line:#354738}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.5 system-ui,sans-serif}main{max-width:1060px;margin:auto;padding:40px 22px 64px}.eyebrow,.take-label,label,summary{font:12px/1.5 ui-monospace,monospace;letter-spacing:.05em}.eyebrow,.take-label{color:var(--accent)}h1{font-size:clamp(34px,6vw,60px);letter-spacing:-.04em;margin:8px 0}h2{font-size:32px;margin:0}h3{font-size:22px;margin:0}p{color:var(--muted);margin:10px 0 18px}.intro{max-width:790px}a{color:var(--accent);text-underline-offset:3px}.tools{display:flex;gap:14px 22px;flex-wrap:wrap;margin:24px 0}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:20px;margin:22px 0}.card,.note{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:22px}.relationship{margin:36px 0;padding-top:22px;border-top:1px solid var(--line)}.listen{margin:18px 0}.take-label{display:flex;gap:12px;justify-content:space-between;align-items:center}audio{display:block;width:100%;margin-top:8px}details{border-top:1px solid var(--line);margin-top:18px;padding-top:14px}summary{cursor:pointer;min-height:36px}button,select,textarea{font:inherit;border:1px solid var(--line);border-radius:5px;padding:10px;background:#0f1711;color:var(--ink)}button{cursor:pointer;min-height:44px}select,textarea{width:100%;margin-top:8px}textarea{resize:vertical}label{display:block;color:var(--accent);margin-top:16px}button:focus-visible,a:focus-visible,summary:focus-visible{outline:2px solid var(--accent);outline-offset:4px}.small{font-size:14px}.status{min-height:1.5em;margin:8px 0}footer{margin-top:32px}@media(max-width:680px){main{padding:25px 16px 45px}.grid{grid-template-columns:1fr}.card,.note{padding:18px}.take-label{font-size:11px}}
</style></head><body><main>
<div class="eyebrow">SNIPSNAP / OBBLIGATO / HISTORICAL COMPARISON</div><h1>A little overlap</h1>
<p class="intro">The incoming player begins while the outgoing phrase is still present. Compare the previous and overlapping handoffs in Play, Fight and Protect, on one note and in the same short melody.</p>
<div class="tools"><a href="#play">Play</a><a href="#fight">Fight</a><a href="#protect">Protect</a><a href="$ARCHIVE" id="pack-link" download>Download all 24 WAVs and recipes ↓</a><a href="standalone.html" id="standalone-link" download>Single-file page ↓</a><a href="manifest.json" id="manifest-download" download="manifest.json">Settings and measurements ↓</a></div>
<p class="small">Complete duets and melodies are matched to the same loudness. The separate players keep their own duet's gain. Dry mono throughout. This archived comparison retains its original listening settings.</p>
<p class="small">The melody is C4, E-flat4, G4, F4. Each new note begins as the previous one releases, preserving its whole tail. Each note starts a fresh duet.</p><p class="small" id="embedded-note" hidden>This file embeds all 24 recordings and the main instrument patches. The complete recipe ZIP is available from the published page.</p>
$sections
<section class="note" style="margin-top:30px"><h2>Your listening notes</h2><label for="verdict">THE OVERLAPPING HANDOFFS</label><select id="verdict"><option value="">Choose a verdict</option><option>More connected and musical</option><option>Needs a little more overlap</option><option>Too much overlap</option><option>Prefer the previous handoff</option></select><label for="notes">WHAT DID YOU HEAR?</label><textarea id="notes" rows="5" placeholder="Do the phrases connect more naturally? Can you still hear who is leading? Does a handoff dip or become crowded?"></textarea><p><button type="button" id="export-notes">Export listening notes ↓</button></p><p class="status small" id="status" aria-live="polite"></p></section>
<footer><p class="small">The overlapping version is a separate saved instrument revision. Full patches, melody onsets, event summaries and recording hashes are included in the download. This archive preserves the first overlap comparison.</p></footer>
<script id="manifest" type="application/json">${manifest.replace("<", "\\u003c")}</script><script>
const AUDIO = {};
const manifest = JSON.parse(document.getElementById('manifest').textContent);
document.querySelectorAll('audio[data-path]').forEach(a=>{if(AUDIO[a.dataset.path])a.src=AUDIO[a.dataset.path];});
document.querySelectorAll('a[data-audio-download]').forEach(a=>{if(AUDIO[a.dataset.audioDownload])a.href=AUDIO[a.dataset.audioDownload];});
if(Object.keys(AUDIO).length){document.getElementById('pack-link').hidden=true;document.getElementById('standalone-link').hidden=true;document.getElementById('embedded-note').hidden=false;document.getElementById('manifest-download').href=URL.createObjectURL(new Blob([JSON.stringify(manifest,null,2)+'\n'],{type:'application/json'}));document.querySelectorAll('a[data-patch]').forEach(a=>{const take=manifest.takes.find(t=>t.id===a.dataset.patch);a.href=URL.createObjectURL(new Blob([JSON.stringify(take.patch,null,2)+'\n'],{type:'application/json'}));});}
document.querySelectorAll('audio').forEach(a=>a.addEventListener('play',()=>document.querySelectorAll('audio').forEach(other=>{if(other!==a)other.pause();})));
const key='snipsnap-obbligato-overlap-review-v1',verdict=document.getElementById('verdict'),notes=document.getElementById('notes'),status=document.getElementById('status');
try{const saved=JSON.parse(localStorage.getItem(key)||'{}');verdict.value=saved.verdict||'';notes.value=saved.notes||'';}catch(_){status.textContent='Browser storage is unavailable. Export your notes to keep them.';}
function save(){try{localStorage.setItem(key,JSON.stringify({verdict:verdict.value,notes:notes.value}));status.textContent='Notes saved in this browser.';}catch(_){status.textContent='Export your notes to keep them.';}}
verdict.addEventListener('change',save);notes.addEventListener('input',save);
document.getElementById('export-notes').addEventListener('click',()=>{const record={engine:'OBBLIGATO',acceptedModel:5,previousModel:8,candidateModel:9,relationships:manifest.relationships,verdict:verdict.value,notes:notes.value,exportedAt:new Date().toISOString()};const url=URL.createObjectURL(new Blob([JSON.stringify(record,null,2)+'\n'],{type:'application/json'})),a=document.createElement('a');a.href=url;a.download='obbligato-overlap-listening-notes.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);status.textContent='Listening notes exported.';});
</script></main></body></html>
""".trimIndent() + "\n"
    }

    private fun readme() = """
        OBBLIGATO: overlapping handoffs

        Compare the previous and overlapping handoffs in Play, Fight and Protect.
        Single notes use C4, velocity 0.8, seed 0, BREATH 0.65, MISCHIEF 0.2 and HOLD
        0.45. This is the original comparison snapshot. All 24 recordings are dry mono
        PCM24 at 44.1 kHz. No rack effects or sustain loops are used.

        Open index.html beside audio/, patches/, recipes/, diagnostics/ and manifest.json.
        If provided, standalone.html embeds all recordings and main patches for offline
        use. It is excluded from the ZIP to avoid duplicating audio.

        Complete duets and complete melodies match the same house loudness of 0.12 before
        one common pack-wide headroom attenuation. Each actual player tap inherits its own
        complete duet's gain. No isolated source is independently leveled.

        The melody plays C4, E-flat4, G4, F4 with HOLD 0 and two-second onset spacing.
        Every complete release tail is preserved and overlaps the next onset. Each note
        begins fresh; this is separate from the overlap between players within a note.

        The new handoff adds a quiet incoming prelude, the preceding middle crossover and
        a short outgoing tail. Only actual changes of player use that shape. Full patches,
        phrase note recipes and onsets retain every render setting. Diagnostics retain
        conversation events, remembered contours and actual handoff entries. Manifest.json
        records proposed timing shapes, exact gains, source fingerprints and PCM24 hashes.

        Measurements describe the written audio. A one-window pitch estimate of a duet is
        not an estimate of both players, and numbers do not establish musical preference.
    """.trimIndent() + "\n"
}
