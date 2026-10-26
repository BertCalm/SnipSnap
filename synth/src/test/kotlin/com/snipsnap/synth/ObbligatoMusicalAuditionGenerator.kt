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
 * A focused listening comparison after the owner requested less harshness and more musicality.
 * Match each complete duet and complete phrase; actual stems inherit their duet's gain.
 * A final common attenuation protects the complete pack's headroom without changing that match.
 */
object ObbligatoMusicalAuditionGenerator {
    private const val MIDI = 60
    private const val VELOCITY = .8f
    private const val SEED = 0
    private const val TARGET_LEVEL = .12f
    private const val HEADROOM = .95f
    private const val NOTE_ONSET_SECONDS = 2.0
    private const val ARCHIVE = "obbligato-musical-audition.zip"
    private const val MAX_ASSET_BYTES = 25L * 1024 * 1024
    private val phraseMidi = listOf(60, 63, 67, 65)

    private data class Take(val id: String, val title: String, val patch: ObbligatoPatch,
        val report: Obbligato.Report, val phrase: Snip, val phrasePatches: List<ObbligatoPatch>)
    private data class Clip(val id: String, val title: String, val take: Take, val role: String,
        val source: Snip, val levelGain: Float)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Usage: ObbligatoMusicalAuditionGenerator [output-directory]" }
        require(Obbligato.MODEL == 5 && Obbligato.EXPERIMENTAL_MODEL == 6 && Obbligato.MUSICAL_MODEL == 7)
        val root = File(args.firstOrNull() ?: "../testkit/obbligato-musical-audition").absoluteFile
        check(root.isDirectory || root.mkdirs()) { "Could not create $root" }
        val base = ObbligatoPresets.all().single { it.name == "Fight" }
            .copy(midi = MIDI, velocity = VELOCITY, seed = SEED)
        val takes = listOf(
            Triple("responsive6", "Previous responsive Fight", Obbligato.EXPERIMENTAL_MODEL),
            Triple("gentler7", "Gentler Fight", Obbligato.MUSICAL_MODEL),
            Triple("accepted5", "Accepted Fight reference", Obbligato.MODEL),
        ).map { (id, title, model) ->
            val patch = base.copy(name = title, model = model)
            val report = Obbligato.probe(patch.macros, patch.midi, patch.velocity, patch.seed, model = model)
            validate(report.snip)
            require(report.air.size == report.snip.samples.size && report.reed.size == report.air.size)
            require(report.air.indices.maxOf {
                abs(report.air[it].toDouble() + report.reed[it] - report.snip.samples[it])
            } < 1e-6) { "$id: source taps do not reconstruct the duet" }
            val patches = phraseMidi.mapIndexed { index, midi ->
                patch.copy(name = "$title phrase note ${index + 1}", midi = midi,
                    macros = patch.macros + ("HOLD" to 0f))
            }
            Take(id, title, patch, report, phrase(patches), patches)
        }
        val clips = takes.flatMap { take ->
            val duetGain = TARGET_LEVEL / Loudness.of(take.report.snip)
            val phraseGain = TARGET_LEVEL / Loudness.of(take.phrase)
            listOf(Clip("${take.id}_duet", take.title, take, "duet", take.report.snip, duetGain),
                Clip("${take.id}_phrase", "${take.title} · four-note phrase", take, "phrase", take.phrase, phraseGain)) +
                if (take.id != "accepted5") listOf(
                    Clip("${take.id}_air", "Air inside ${take.title}", take, "air", mono(take.report.air), duetGain),
                    Clip("${take.id}_reed", "Reed inside ${take.title}", take, "reed", mono(take.report.reed), duetGain))
                else emptyList()
        }
        require(clips.size == 10)
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
                "role" to clip.role, "model" to clip.take.patch.model, "path" to path, "gain" to gain,
                "levelMatchGain" to clip.levelGain, "bytes" to file.length(), "sha256" to sha256(file.readBytes()),
                "sourceMetrics" to metrics(clip.source), "exportMetrics" to metrics(output))
        }
        val takeRows = takes.map { take ->
            val patchPath = "patches/${take.id}.patch"
            val patchFile = writeText(patchPath, take.patch.toJsonText() + "\n")
            val phraseRows = take.phrasePatches.mapIndexed { index, patch ->
                val path = "patches/${take.id}_phrase_${index + 1}.patch"
                val file = writeText(path, patch.toJsonText() + "\n")
                linkedMapOf("onsetSeconds" to index * NOTE_ONSET_SECONDS, "patch" to patch.toJsonValue(),
                    "patchPath" to path, "sha256" to sha256(file.readBytes()))
            }
            writeJson("recipes/${take.id}_phrase.json", linkedMapOf("notes" to phraseRows,
                "mixPolicy" to "Sum complete raw finite notes at the stated onset times, preserving all release tails; level the complete phrase once."))
            linkedMapOf<String, Any?>("id" to take.id, "title" to take.title, "patch" to take.patch.toJsonValue(),
                "patchPath" to patchPath, "patchSha256" to sha256(patchFile.readBytes()), "phraseNotes" to phraseRows,
                "diagnostics" to take.report.diagnostics, "interactionEvents" to take.report.interactionEvents.map {
                    linkedMapOf("seconds" to it.seconds, "previousLeader" to it.previousLeader.name,
                        "leader" to it.leader.name, "readinessAtEntry" to it.readinessAtEntry,
                        "heardAir" to it.heardAir, "heardReed" to it.heardReed)
                })
        }
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "engine" to "OBBLIGATO", "audition" to "Gentler Fight comparison",
            "status" to "Gentler Fight is unapproved. The accepted instrument and kit remain model 5.",
            "acceptedModel" to Obbligato.MODEL, "previousModel" to Obbligato.EXPERIMENTAL_MODEL,
            "candidateModel" to Obbligato.MUSICAL_MODEL, "sampleRate" to Dsp.RATE, "channels" to 1,
            "pcmBits" to 24, "midi" to MIDI, "velocity" to VELOCITY, "seed" to SEED,
            "targetLoudnessBeforeHeadroom" to TARGET_LEVEL, "commonHeadroomAttenuation" to commonAttenuation,
            "effectiveTargetLoudness" to TARGET_LEVEL * commonAttenuation,
            "gainPolicy" to "Level-match each complete duet and each complete phrase to the same house loudness; actual stems inherit their duet's gain. Apply one final common attenuation to every recording for pack-wide headroom. No stem is independently normalized.",
            "phraseMidi" to phraseMidi, "phraseOnsetSeconds" to NOTE_ONSET_SECONDS,
            "phrasePolicy" to "C4, E-flat4, G4, F4 at two-second onset spacing, HOLD 0. Each note begins fresh. Sum each complete finite note, preserving its 0.75-second release and overlapping it with the next onset.",
            "effects" to emptyList<String>(), "looping" to false,
            "checks" to linkedMapOf("finiteSamples" to true, "headroomPassed" to true,
                "additiveSourceTaps" to true, "stemGainMatchesDuet" to true, "wavFiles" to clips.size),
            "rendererSources" to rendererSources(), "takes" to takeRows, "clips" to recordings,
            "ledger" to linkedMapOf(
                "verified" to "Exact instrument and phrase recipes, additive source taps, controller entries and PCM24 hashes.",
                "measured" to "Kotlin renders: finite samples, headroom, level, DC and one-note pitch estimates. A duet estimate is not a separate estimate of each source.",
                "proposed" to "The gentler reed voicing and closer tuning are listening choices, not a claim of improved musicality.",
                "heard" to "The owner reported harshness and requested more musicality in the preceding comparison. No listening verdict is claimed for this candidate."),
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
        require(standalone.length() < MAX_ASSET_BYTES) { "Standalone page exceeds 25 MiB" }
        val archive = File(root, ARCHIVE)
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            archiveFiles.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath).also { it.time = 0L })
                file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        require(archive.length() < MAX_ASSET_BYTES) { "Audition ZIP exceeds 25 MiB" }
        println("Wrote ${clips.size} level-matched PCM24 recordings, complete note and phrase recipes, listening pages and ${archive.absolutePath}")
    }

    /** Preserve whole release tails; only the onsets overlap, never a hard audio cut. */
    private fun phrase(patches: List<ObbligatoPatch>): Snip {
        val notes = patches.map { Obbligato.probe(it.macros, it.midi, it.velocity, it.seed, model = it.model).snip }
        notes.forEach(::validate)
        val spacing = (NOTE_ONSET_SECONDS * Dsp.RATE).toInt()
        val output = FloatArray(notes.indices.maxOf { it * spacing + notes[it].frameCount })
        notes.forEachIndexed { index, note ->
            val offset = index * spacing
            for (i in note.samples.indices) output[offset + i] += note.samples[i]
        }
        return mono(output)
    }

    private fun mono(samples: FloatArray) = Snip(samples, 1, Dsp.RATE)
    private fun validate(snip: Snip) {
        require(snip.channels == 1 && snip.sampleRate == Dsp.RATE && snip.samples.isNotEmpty())
        require(snip.samples.all { it.isFinite() }) { "Non-finite audition samples" }
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
            "src/main/kotlin/com/snipsnap/synth/ObbligatoInteraction.kt", "src/main/kotlin/com/snipsnap/synth/ObbligatoPatch.kt",
            "src/main/kotlin/com/snipsnap/synth/Bore.kt", "src/main/kotlin/com/snipsnap/synth/Dsp.kt",
            "src/main/kotlin/com/snipsnap/synth/Strings.kt", "src/main/kotlin/com/snipsnap/synth/Keys.kt",
            "src/main/kotlin/com/snipsnap/synth/Tide.kt",
            "../audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt", "../audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt",
            "../audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
            "../audio/src/main/kotlin/com/snipsnap/audio/Pitch.kt", "../audio/src/main/kotlin/com/snipsnap/audio/Fft.kt",
            "src/test/kotlin/com/snipsnap/synth/ObbligatoMusicalAuditionGenerator.kt")
        return paths.associateWith { sha256(File(it).readBytes()) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
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
        return """
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>OBBLIGATO · gentler Fight comparison</title><style>
:root{color-scheme:dark;--bg:#101511;--panel:#19221b;--ink:#eaf2e9;--muted:#acbbaa;--accent:#ff8a7a;--line:#354738}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.5 system-ui,sans-serif}main{max-width:1060px;margin:auto;padding:40px 22px 64px}.eyebrow,.take-label,label,summary{font:12px/1.5 ui-monospace,monospace;letter-spacing:.06em}.eyebrow,.take-label{color:var(--accent)}h1{font-size:clamp(34px,6vw,60px);letter-spacing:-.04em;margin:8px 0}h2{margin:0;font-size:25px}h3{margin:0;font-size:21px}p{color:var(--muted);margin:10px 0 18px}.intro{max-width:760px}a{color:var(--accent);text-underline-offset:3px}.tools{display:flex;gap:14px 22px;flex-wrap:wrap;margin:24px 0}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:20px;margin:22px 0 34px}.card,.note{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:22px}.listen{margin:18px 0}.take-label{display:flex;gap:12px;justify-content:space-between;align-items:center}audio{display:block;width:100%;margin-top:8px}details{border-top:1px solid var(--line);margin-top:18px;padding-top:14px}summary{cursor:pointer;min-height:36px}button,select,textarea{font:inherit;border:1px solid var(--line);border-radius:5px;padding:10px;background:#0f1711;color:var(--ink)}button{cursor:pointer;min-height:44px}select,textarea{width:100%;margin-top:8px}textarea{resize:vertical}label{display:block;color:var(--accent);margin-top:16px}button:focus-visible,a:focus-visible,summary:focus-visible{outline:2px solid var(--accent);outline-offset:4px}.small{font-size:14px}.status{min-height:1.5em;margin:8px 0}footer{margin-top:32px} @media(max-width:680px){main{padding:25px 16px 45px}.grid{grid-template-columns:1fr}.card,.note{padding:18px}.take-label{font-size:11px}}
</style></head><body><main>
<div class="eyebrow">SNIPSNAP / OBBLIGATO / LISTENING COMPARISON</div>
<h1>A gentler conversation</h1>
<p class="intro">A response to the harshness you heard: a softer reed edge and closer tuning, with the same two players answering one another. Start with the single note, then hear both versions in a short melody.</p>
<div class="tools"><a href="$ARCHIVE" id="pack-link" download>Download all 10 WAVs and recipes ↓</a><a href="standalone.html" id="standalone-link" download>Single-file listening page ↓</a><a href="manifest.json" id="manifest-download" download="manifest.json">Settings and measurements ↓</a></div>
<p class="small" id="embedded-note" hidden>This single file embeds all ten recordings and the instrument patches. The complete recipe ZIP is available from the published page.</p>
<p class="small">Complete duets and phrases are matched to the same loudness. The source players below keep their duet's gain. Dry mono throughout, without rack effects. This variation awaits your listening verdict; the accepted instrument and kit remain unchanged.</p>
<div class="grid">
<section class="card"><h2>Previous responsive Fight</h2><p>The preceding comparison, kept as the direct reference.</p>${player("responsive6_duet", "PREVIOUS · SINGLE NOTE")}<a href="patches/responsive6.patch" data-patch="responsive6" download="responsive6.patch">Previous patch ↓</a></section>
<section class="card"><h2>Gentler Fight</h2><p>Less reed rasp and high-frequency emphasis, with less pitch friction between the players.</p>${player("gentler7_duet", "GENTLER · SINGLE NOTE")}<a href="patches/gentler7.patch" data-patch="gentler7" download="gentler7.patch">Candidate patch ↓</a></section>
</div>
<section class="note"><h2>A short melody</h2><p>C4, E-flat4, G4, F4. The next note begins as the previous note releases, preserving its whole tail. Each note starts a fresh duet.</p>${player("responsive6_phrase", "PREVIOUS · MELODY")}${player("gentler7_phrase", "GENTLER · MELODY")}</section>
<details><summary>Hear the two players separately</summary><p>Actual sources from the single-note duets, at their original duet balance. These are not independently leveled solos.</p><div class="grid"><section class="card"><h3>Previous</h3>${player("responsive6_air", "PREVIOUS · AIR")}${player("responsive6_reed", "PREVIOUS · REED")}</section><section class="card"><h3>Gentler</h3>${player("gentler7_air", "GENTLER · AIR")}${player("gentler7_reed", "GENTLER · REED")}</section></div></details>
<details><summary>Return to the accepted Fight</summary><p>The earlier accepted version remains available, at the same comparison loudness.</p>${player("accepted5_duet", "ACCEPTED · SINGLE NOTE")}${player("accepted5_phrase", "ACCEPTED · MELODY")}<a href="patches/accepted5.patch" data-patch="accepted5" download="accepted5.patch">Accepted patch ↓</a></details>
<section class="note" style="margin-top:28px"><h2>Your listening notes</h2><label for="verdict">GENTLER FIGHT</label><select id="verdict"><option value="">Choose a verdict</option><option>Closer to what I want</option><option>Still too harsh</option><option>Too soft or lacking character</option><option>Prefer the previous version</option></select><label for="notes">WHAT DID YOU HEAR?</label><textarea id="notes" rows="4" placeholder="Does the melody feel easier to play? Does the reed still have enough character?"></textarea><p><button type="button" id="export-notes">Export listening notes ↓</button></p><p class="status small" id="status" aria-live="polite"></p></section>
<footer><p class="small">The candidate is a separate saved instrument revision. Musicality is for you to judge. Instrument patches and complete phrase recipes are in the download, alongside exact recording hashes and measurements.</p></footer>
<script id="manifest" type="application/json">${manifest.replace("<", "\\u003c")}</script>
<script>
const AUDIO = {};
document.querySelectorAll('audio[data-path]').forEach(a => { if (AUDIO[a.dataset.path]) a.src = AUDIO[a.dataset.path]; });
document.querySelectorAll('a[data-audio-download]').forEach(a => { if (AUDIO[a.dataset.audioDownload]) a.href = AUDIO[a.dataset.audioDownload]; });
if (Object.keys(AUDIO).length) { const m = JSON.parse(document.getElementById('manifest').textContent); document.getElementById('pack-link').hidden = true; document.getElementById('standalone-link').hidden = true; document.getElementById('embedded-note').hidden = false; document.getElementById('manifest-download').href = URL.createObjectURL(new Blob([JSON.stringify(m,null,2)+'\n'],{type:'application/json'})); document.querySelectorAll('a[data-patch]').forEach(a => { const take = m.takes.find(t => t.id === a.dataset.patch); a.href = URL.createObjectURL(new Blob([JSON.stringify(take.patch,null,2)+'\n'],{type:'application/json'})); }); }
document.querySelectorAll('audio').forEach(a => a.addEventListener('play', () => document.querySelectorAll('audio').forEach(other => { if (other !== a) other.pause(); })));
const key = 'snipsnap-obbligato-gentler-fight-review-v1';
const verdict = document.getElementById('verdict'), notes = document.getElementById('notes'), status = document.getElementById('status');
try { const saved = JSON.parse(localStorage.getItem(key) || '{}'); verdict.value = saved.verdict || ''; notes.value = saved.notes || ''; } catch (_) { status.textContent = 'Browser storage is unavailable. Export your notes to keep them.'; }
function save() { try { localStorage.setItem(key, JSON.stringify({verdict:verdict.value,notes:notes.value})); status.textContent = 'Notes saved in this browser.'; } catch (_) { status.textContent = 'Export your notes to keep them.'; } }
verdict.addEventListener('change', save); notes.addEventListener('input', save);
document.getElementById('export-notes').addEventListener('click', () => { const record = {engine:'OBBLIGATO',acceptedModel:5,previousModel:6,candidateModel:7,verdict:verdict.value,notes:notes.value,exportedAt:new Date().toISOString()}; const url = URL.createObjectURL(new Blob([JSON.stringify(record,null,2)+'\n'],{type:'application/json'})), a = document.createElement('a'); a.href = url; a.download = 'obbligato-gentler-listening-notes.json'; a.click(); setTimeout(() => URL.revokeObjectURL(url),1000); status.textContent = 'Listening notes exported.'; });
</script></main></body></html>
""".trimIndent() + "\n"
    }

    private fun readme() = """
        OBBLIGATO gentler Fight comparison

        Compare Previous responsive Fight with Gentler Fight. The latter is an unapproved
        candidate, responding to the owner's request for less harshness and more musicality.
        The accepted instrument and kit remain unchanged and are included as a reference.

        Open index.html with audio/, patches/, recipes/ and manifest.json beside it.
        The separate standalone.html embeds the ten WAVs and main patches; it is excluded
        from the ZIP to avoid duplicating the recordings. All audio is dry mono PCM24,
        44.1 kHz. No rack effects or loops are used.

        Each COMPLETE single-note duet and COMPLETE melody is matched to the same house
        loudness of 0.12 before a shared headroom attenuation. The actual air and reed
        stems inherit their duet's exact gain; they are never independently normalized.
        The final common attenuation covers every audio file and preserves these matches.
        Manifest.json records every linear gain, the effective target and measured output.

        Single-note comparisons share C4, velocity 0.8, seed 0 and all four macros.
        Melodies use C4, E-flat4, G4 and F4, HOLD 0 and two-second onset spacing. The full
        0.75-second release tail is preserved and overlaps the following onset. Each note
        begins fresh; there is no persistent relationship across notes. The phrase JSON
        files and individual patches retain every setting and onset for reconstruction.

        Manifest.json records source fingerprints, exact PCM24 WAV hashes, measurements,
        additive source tap checks, controller entries and the verified/proposed/heard
        ledger. Pitch estimates read one window beginning at 0.8 seconds; a duet estimate
        is not a separate estimate of each player and is not proof of musicality.
    """.trimIndent() + "\n"
}
