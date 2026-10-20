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
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/** Four dry relationship candidates, actual model taps, and an explicitly named response ablation. */
object ObbligatoAuditionGenerator {
    private const val MIDI = 60
    private const val VELOCITY = .8f
    private const val SEED = 0
    private const val HEADROOM = .95f
    private const val REQUESTED_MATCHED_LEVEL = .12f
    private val phraseMidi = listOf(60, 63, 65, 67)
    private const val PHRASE_GAP_SECONDS = .12f

    private data class Take(
        val patch: ObbligatoPatch,
        val full: Obbligato.Report,
        val responseOff: Obbligato.Report,
        val phrase: Snip,
    )

    private data class Clip(
        val id: String,
        val preset: String,
        val role: String,
        val source: Snip,
        val rawGain: Float,
        val matchedGain: Float,
        val description: String,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Usage: ObbligatoAuditionGenerator [output-directory]" }
        val root = File(args.firstOrNull() ?: "../testkit/obbligato-audition")
        root.mkdirs()
        val takes = ObbligatoPresets.all().map { original ->
            val patch = original.copy(midi = MIDI, velocity = VELOCITY, seed = SEED)
            val full = Obbligato.probe(patch.macros, MIDI, VELOCITY, SEED)
            val off = Obbligato.probe(patch.macros, MIDI, VELOCITY, SEED, responses = false)
            validate(full.snip)
            validate(off.snip)
            require(full.air.size == full.snip.samples.size && full.reed.size == full.air.size)
            require(full.air.indices.maxOf { abs(full.air[it] + full.reed[it] - full.snip.samples[it]) } < 1e-5f) {
                "${patch.name}: model taps must reconstruct the complete duet"
            }
            Take(patch, full, off, phrase(patch))
        }
        require(takes.size == 4) { "First listen expects four relationship presets" }
        require(takes.map { it.patch.name } == listOf("Harmony", "Play", "Fight", "Protect"))
        val companions = takes.first().patch.macros.filterKeys { it != "BOND" }
        require(takes.all { it.patch.macros.filterKeys { name -> name != "BOND" } == companions }) {
            "First-listen presets must differ only in BOND"
        }

        // One raw export gain preserves every raw comparison's relative energy. All matched
        // complete sounds reach one target; stems inherit their duet's gain, never their own.
        val allSources = takes.flatMap { listOf(it.full.snip, mono(it.full.air), mono(it.full.reed), it.responseOff.snip, it.phrase) }
        allSources.forEach(::validate)
        val rawGain = minOf(1f, HEADROOM / allSources.maxOf { it.peak() })
        val matchedLevel = takes.fold(REQUESTED_MATCHED_LEVEL) { target, take ->
            minOf(target,
                HEADROOM * Loudness.of(take.full.snip) / maxOf(take.full.snip.peak(), mono(take.full.air).peak(), mono(take.full.reed).peak()),
                HEADROOM * Loudness.of(take.responseOff.snip) / take.responseOff.snip.peak(),
                HEADROOM * Loudness.of(take.phrase) / take.phrase.peak())
        }
        require(matchedLevel.isFinite() && matchedLevel > 0f)
        val clips = takes.flatMap { take ->
            val p = take.patch
            val slug = slug(p.name)
            val duetGain = matchedLevel / Loudness.of(take.full.snip)
            listOf(
                Clip("${slug}_duet", p.name, "duet", take.full.snip, rawGain, duetGain,
                    "Complete two-player model; no rack effects."),
                Clip("${slug}_air", p.name, "air", mono(take.full.air), rawGain, duetGain,
                    "Flute stem from that complete duet, at exactly the duet's export gain."),
                Clip("${slug}_reed", p.name, "reed", mono(take.full.reed), rawGain, duetGain,
                    "Reed stem from that complete duet, at exactly the duet's export gain."),
                Clip("${slug}_responses_off", p.name, "responsesOff", take.responseOff.snip, rawGain,
                    matchedLevel / Loudness.of(take.responseOff.snip),
                    "Relationship responses disabled; stage gestures, note, velocity and seed remain the same."),
                Clip("${slug}_phrase", p.name, "phrase", take.phrase, rawGain,
                    matchedLevel / Loudness.of(take.phrase),
                    "Four independently started notes (C4, E-flat4, F4, G4), separated by 120 ms; player state does not continue between notes."),
            )
        }
        val measurements = clips.map { clip ->
            val variants = linkedMapOf<String, Any?>()
            listOf("raw" to clip.rawGain, "matched" to clip.matchedGain).forEach { (namespace, gain) ->
                val snip = scale(clip.source, gain)
                validate(snip)
                require(snip.peak() <= HEADROOM + 1e-5f) { "${clip.id}: PCM headroom exceeded" }
                val path = "$namespace/${clip.id}.wav"
                val file = WavWriter.write(File(root, path), snip, WavWriter.BitDepth.PCM_24)
                variants[namespace] = linkedMapOf("path" to path, "gain" to gain,
                    "sha256" to sha256(file.readBytes()), "bytes" to file.length(), "metrics" to metrics(snip))
            }
            linkedMapOf("id" to clip.id, "preset" to clip.preset, "role" to clip.role,
                "description" to clip.description, "sourceMetrics" to metrics(clip.source),
                "pitchProbe" to Pitch.detect(clip.source, fromSec = .35f)?.let {
                    linkedMapOf("hz" to it.hz, "confidence" to it.confidence, "fromSeconds" to .35f, "windowSeconds" to .25f)
                }, "files" to variants)
        }
        val settings = takes.map { take ->
            val path = "patches/${slug(take.patch.name)}.json"
            val file = File(root, path).also { it.parentFile.mkdirs(); it.writeText(Json.write(take.patch.toJsonValue()) + "\n") }
            linkedMapOf("name" to take.patch.name, "id" to slug(take.patch.name), "patch" to take.patch.toJsonValue(),
                "patchPath" to path, "patchSha256" to sha256(file.readBytes()),
                "diagnostics" to take.full.diagnostics, "responsesOffDiagnostics" to take.responseOff.diagnostics,
                "effortTrace" to effortTrace(take.full),
                "responseComparison" to comparison(take.full.snip, take.responseOff.snip),
                "stemReconstructionMaxError" to take.full.air.indices.maxOf {
                    abs(take.full.air[it] + take.full.reed[it] - take.full.snip.samples[it])
                })
        }
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "engine" to "OBBLIGATO", "auditionRevision" to 5,
            "status" to "Revision 5 prototype; Fight's unequal challenges await a listening verdict",
            "revisionNotes" to listOf("Fight now uses irregular, unequal challenges and interruptions with brief, smooth crossovers, contrasting prominence and small independent pitch pushes.",
                "Harmony, Play and Protect are unchanged from revision 4."),
            "sampleRate" to Dsp.RATE, "channels" to 1, "pcmBits" to 24,
            "midi" to MIDI, "note" to "C4", "velocity" to VELOCITY, "seed" to SEED,
            "effects" to emptyList<String>(), "looping" to false,
            "rawExportGain" to rawGain, "matchedLoudnessTarget" to matchedLevel,
            "levelMeasure" to "Loudest 200 ms RMS with gentle 120 Hz low cut (SnipSnap Loudness.of)",
            "gainPolicy" to "Raw: one common headroom gain. Matched: one common loudness target for duets, response-off and phrases; air/reed stems use their duet's exact gain.",
            "phrasePolicy" to "Four independent notes (MIDI 60, 63, 65, 67), separated by 120 ms; each starts fresh. Not a stateful shared-performance phrase.",
            "phraseMidi" to phraseMidi, "phraseGapSeconds" to PHRASE_GAP_SECONDS,
            "checks" to linkedMapOf("finiteSamples" to true, "nonSilentSources" to true,
                "headroomPassed" to true, "additiveModelTapsPassed" to true,
                "sameMidiVelocitySeed" to true, "onlyBondDiffers" to true,
                "presets" to takes.size, "wavFiles" to clips.size * 2),
            "rendererSources" to rendererSources(), "presets" to settings, "clips" to measurements,
            "caveats" to listOf("A flute-like air jet and a single-reed source; instrument names do not establish acoustic realism.",
                "Relationship labels are listening candidates, not measured emotional states.",
                "No stereo-width claim, rack effects, settled loops, or persistent cross-note relationship state.",
                "Response-disabled audio retains authored stage gestures; it is not an entirely uncoupled instrument."),
        )
        val manifestText = Json.write(json(manifest))
        File(root, "manifest.json").writeText(manifestText + "\n")
        val page = html(manifestText, settings, measurements)
        File(root, "index.html").writeText(page)
        val embeddedAudio = measurements.associate { row ->
            val files = row.getValue("files") as Map<*, *>
            val path = (files["matched"] as Map<*, *>)["path"] as String
            path to "data:audio/wav;base64,${Base64.getEncoder().encodeToString(File(root, path).readBytes())}"
        }
        File(root, "standalone.html").writeText(page.replace("const AUDIO = {};", "const AUDIO = ${Json.write(json(embeddedAudio))};"))
        bundle(root, measurements, settings)
        println("Wrote ${takes.size} dry presets, ${clips.size * 2} WAVs, local and portable pages, patches, manifest and ZIP to ${root.absolutePath}")
    }

    /** A declared independent-event phrase until a persistent phrase API is part of the engine. */
    private fun phrase(patch: ObbligatoPatch): Snip {
        val notes = phraseMidi.map { Obbligato.probe(patch.macros, it, VELOCITY, SEED).snip }
        val gap = (PHRASE_GAP_SECONDS * Dsp.RATE).toInt()
        val samples = FloatArray(notes.sumOf { it.frameCount } + gap * (notes.size - 1))
        var offset = 0
        notes.forEach { note ->
            note.samples.copyInto(samples, offset)
            offset += note.frameCount + gap
        }
        return mono(samples)
    }

    private fun validate(snip: Snip) {
        require(snip.channels == 1 && snip.sampleRate == Dsp.RATE && snip.samples.isNotEmpty())
        require(snip.samples.all { it.isFinite() }) { "non-finite model output" }
        require(snip.peak() > 1e-7f && Loudness.of(snip) > 1e-8f) { "silent model output" }
    }

    private fun mono(samples: FloatArray) = Snip(samples, 1, Dsp.RATE)
    private fun scale(snip: Snip, gain: Float) = snip.copy(samples = FloatArray(snip.samples.size) { snip.samples[it] * gain })
    private fun slug(name: String) = name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_')
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun metrics(snip: Snip): Map<String, Any> {
        val count = snip.samples.size
        val sum = snip.samples.sumOf { it.toDouble() }
        val square = snip.samples.sumOf { it.toDouble() * it }
        return linkedMapOf("frames" to snip.frameCount, "seconds" to snip.durationSeconds,
            "peak" to snip.peak(), "rms" to sqrt(square / count), "mean" to sum / count, "loudness" to Loudness.of(snip))
    }

    private fun comparison(full: Snip, disabled: Snip): Map<String, Double> {
        require(full.frameCount == disabled.frameCount)
        var fullEnergy = 0.0
        var disabledEnergy = 0.0
        var differenceEnergy = 0.0
        var dot = 0.0
        for (i in full.samples.indices) {
            val a = full.samples[i].toDouble()
            val b = disabled.samples[i].toDouble()
            fullEnergy += a * a
            disabledEnergy += b * b
            differenceEnergy += (a - b) * (a - b)
            dot += a * b
        }
        return linkedMapOf("rawDifferenceRms" to sqrt(differenceEnergy / full.frameCount),
            "differenceToDuetRmsRatio" to sqrt(differenceEnergy / fullEnergy),
            "waveformCosine" to dot / sqrt(fullEnergy * disabledEnergy))
    }

    private fun rendererSources(): Map<String, String> = listOf("Obbligato.kt", "ObbligatoPatch.kt", "ObbligatoPresets.kt", "Bore.kt", "Strings.kt", "Dsp.kt")
        .associateWith { name -> sha256(File("src/main/kotlin/com/snipsnap/synth/$name").readBytes()) }

    /** Uniform diagnostic samples of the slow effort controller; full audio remains unmodified. */
    private fun effortTrace(report: Obbligato.Report): Map<String, Any> {
        val sourceRate = report.diagnostics.getValue("effortTraceRate")
        val stride = ceil(sourceRate / 50.0).toInt().coerceAtLeast(1)
        return linkedMapOf("sourceTraceRate" to sourceRate, "sampleStride" to stride,
            "traceRate" to sourceRate / stride, "air" to report.airEffort.indices.step(stride).map { report.airEffort[it] },
            "reed" to report.reedEffort.indices.step(stride).map { report.reedEffort[it] })
    }

    private fun bundle(root: File, clips: List<Map<String, Any?>>, presets: List<Map<String, Any?>>) {
        val paths = mutableListOf("index.html", "manifest.json")
        presets.forEach { paths += it.getValue("patchPath") as String }
        clips.forEach { clip -> (clip.getValue("files") as Map<*, *>).values.forEach {
            paths += (it as Map<*, *>)["path"] as String
        } }
        ZipOutputStream(File(root, "obbligato-audition.zip").outputStream().buffered()).use { zip ->
            paths.sorted().forEach { path ->
                zip.putNextEntry(ZipEntry(path).apply { time = 0L })
                if (path == "index.html") {
                    // An unpacked folder contains the pack already; its archive cannot include
                    // itself. Keep the adjacent-ZIP link only on the generated folder's page.
                    zip.write(File(root, path).readText().replace("id=\"pack-link\"", "id=\"pack-link\" hidden").toByteArray())
                } else File(root, path).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> value.toDouble().let { require(it.isFinite()); JsonValue.Num(it) }
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { (key, item) -> key.toString() to json(item) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported manifest value ${value::class}")
    }

    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun html(manifest: String, presets: List<Map<String, Any?>>, clips: List<Map<String, Any?>>): String {
        val roles = linkedMapOf("duet" to "THE DUET", "air" to "FLUTE IN THE DUET", "reed" to "REED IN THE DUET",
            "responsesOff" to "RESPONSES DISABLED", "phrase" to "FOUR NOTES · C4 / E♭4 / F4 / G4")
        val descriptions = mapOf("harmony" to "Reference: shared effort, with two colors inside one played note.",
            "play" to "Call, answer, return. Listen for distinct handoffs between the flute and reed.",
            "fight" to "Unequal challenges and interruptions. Listen for one player pushing forward while the other gives ground, with both still sounding.",
            "protect" to "Flute first, then reed. Listen for the flute-led opening before the reed joins smoothly and carries the ending.")
        val cards = presets.joinToString("\n") { preset ->
            val name = preset.getValue("name") as String
            val id = preset.getValue("id") as String
            val players = roles.entries.joinToString("\n") { (role, title) ->
                val clip = clips.first { it["preset"] == name && it["role"] == role }
                val files = clip.getValue("files") as Map<*, *>
                val raw = (files["raw"] as Map<*, *>)["path"] as String
                val matched = (files["matched"] as Map<*, *>)["path"] as String
                """<div class="listen ${if (role == "duet") "main-take" else ""}"><div class="take-label">$title <a class="wav" data-raw="$raw" data-matched="$matched" href="$matched" download>WAV ↓</a></div><audio controls preload="none" data-raw="$raw" data-matched="$matched" src="$matched"></audio></div>"""
            }
            """<section class="card" id="$id"><div class="card-title"><span class="number">${presets.indexOf(preset) + 1}</span><h2>${escape(name)}</h2><a href="${preset.getValue("patchPath")}" class="patch" data-preset="$id" download>PATCH ↓</a></div><p>${descriptions[id] ?: "A dry relationship candidate."}</p>$players<details><summary>WHAT THESE COMPARISONS MEAN</summary><p>The stems come from the complete model and use exactly its gain. They are not independently generated solos. Responses disabled keeps the same relationship-stage gestures but removes the players' responses to one another. The phrase combines four fresh notes (C4, E-flat4, F4, G4), separated by 120 ms; player state does not continue between notes.</p></details><label class="review-label" for="note-$id">WHAT DID YOU HEAR?</label><select id="verdict-$id" aria-label="Verdict for ${escape(name)}" data-review="$id"><option value="">Choose a verdict</option><option>Keep</option><option>Revise</option><option>Drop</option></select><textarea id="note-$id" data-note="$id" placeholder="Which player is audible? Does the relationship change the sound?" rows="3"></textarea></section>"""
        }
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>OBBLIGATO · first listen</title>
<style>
:root{color-scheme:dark;--bg:#101511;--panel:#19221b;--ink:#eaf2e9;--muted:#acbbaa;--accent:#ff8a7a;--line:#354738}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.5 system-ui,sans-serif}main{max-width:1060px;margin:auto;padding:40px 22px 64px}.eyebrow,.take-label,.review-label,.number{font:12px/1.4 ui-monospace,monospace;letter-spacing:.1em}.eyebrow{color:var(--accent)}h1{font-size:clamp(36px,6vw,66px);letter-spacing:-.04em;margin:8px 0}h2{font-size:24px;margin:0}p{color:var(--muted);margin:10px 0 18px}.intro{max-width:680px}a{color:var(--accent);text-underline-offset:3px}button,select,textarea{font:inherit;border:1px solid var(--line);background:#0f1711;color:var(--ink);border-radius:5px;padding:10px}button{cursor:pointer;min-height:44px}button:disabled{cursor:default;opacity:.55}button.active{background:var(--accent);color:#172017;border-color:var(--accent)}.tools{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin:24px 0 14px}.tools a{font-size:14px}.mode-hint{font-size:14px;max-width:750px}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:20px;margin-top:28px}.card{border:1px solid var(--line);border-radius:10px;padding:22px;background:var(--panel)}.card-title{display:flex;align-items:center;gap:12px}.number{color:var(--accent);border:1px solid var(--line);padding:7px 10px}.patch{margin-left:auto;font-size:12px;white-space:nowrap}.listen{margin:16px 0}.main-take{background:#0f1711;border:1px solid var(--line);padding:12px;border-radius:6px}.take-label{display:flex;align-items:center;justify-content:space-between;gap:8px;color:var(--muted);margin-bottom:6px}.main-take .take-label{color:var(--accent)}.wav{font-size:11px}audio{display:block;width:100%;height:40px}details{font-size:13px;border-top:1px solid var(--line);padding-top:14px;margin:18px 0}summary{cursor:pointer;color:var(--muted);min-height:30px}.review-label{display:block;color:var(--accent);margin:22px 0 8px}select,textarea{width:100%;margin-bottom:8px}textarea{resize:vertical}footer{border-top:1px solid var(--line);margin-top:32px;padding-top:20px;font-size:14px}.status{color:var(--accent);font-size:13px;min-height:22px}@media(max-width:710px){main{padding:24px 14px}.grid{grid-template-columns:1fr}.card{padding:18px}}
</style></head><body><main><div class="eyebrow">SNIPSNAP / DRY AUDITION / REVISION 5</div><h1>OBBLIGATO</h1><p class="intro">A flute and a reed, sharing one played note. Revision 5 gives Fight unequal challenges and interruptions; Harmony, Play and Protect remain unchanged. Start with the duet, then hear the players inside it.</p><p class="eyebrow">MONO · C4 · VELOCITY 0.80 · NO RACK EFFECTS</p><div class="tools"><button type="button" class="active" data-mode="matched">Matched level</button><button type="button" data-mode="raw">Raw energy</button><a href="obbligato-audition.zip" id="pack-link" download>Full listening pack ↓</a><a href="manifest.json" id="manifest-link" download>Settings & measurements ↓</a></div><p class="mode-hint" id="mode-hint">Duets, responses-disabled comparisons and phrases share one measured loudness target. Each stem uses its duet's exact gain, so its balance remains intact.</p><div class="grid">$cards</div><footer><button type="button" id="export-review">Export listening notes ↓</button><p class="status" id="review-status" aria-live="polite"></p><p>These are listening candidates. The labels do not establish acoustic realism or emotional meaning. Each note begins fresh. There are no rack effects, stereo-width gestures or sustain loops.</p><p>Notes are kept in this browser when local storage is available. Export them to share your verdict.</p><p id="portable-note">The separate portable <strong>standalone.html</strong> embeds the 20 matched WAVs. The full ZIP contains the local page, settings, patches and all 40 raw/matched WAVs.</p></footer></main>
<script id="manifest-data" type="application/json">${manifest.replace("<", "\\u003c")}</script>
<script>
const AUDIO = {};
const portable = Object.keys(AUDIO).length > 0;
const manifest = JSON.parse(document.getElementById('manifest-data').textContent);
const presetById = Object.fromEntries(manifest.presets.map(p => [p.id, p]));
let mode = 'matched';
const storageKey = 'snipsnap-obbligato-first-listen-v5';
const resolveAudio = path => AUDIO[path] || path;
function applyMode(next) {
  if (portable && next === 'raw') return;
  mode = next;
  document.querySelectorAll('audio').forEach(el => { el.pause(); el.src = resolveAudio(el.dataset[mode]); });
  document.querySelectorAll('.wav').forEach(el => { el.href = resolveAudio(el.dataset[mode]); el.download = el.dataset[mode].split('/').pop(); });
  document.querySelectorAll('[data-mode]').forEach(el => { el.classList.toggle('active', el.dataset.mode === mode); el.setAttribute('aria-pressed', String(el.dataset.mode === mode)); });
  document.getElementById('mode-hint').textContent = mode === 'matched' ? "Duets, responses-disabled comparisons and phrases share one measured loudness target. Each stem uses its duet's exact gain, so its balance remains intact." : "Every raw file uses the same headroom gain. Differences in player and preset energy remain audible; this mode is not loudness matched.";
}
if (portable) {
  const rawButton = document.querySelector('[data-mode="raw"]'); rawButton.disabled = true; rawButton.textContent = 'Raw WAVs in full pack';
  document.getElementById('pack-link').hidden = true;
  document.getElementById('portable-note').textContent = 'This portable page embeds all 20 matched WAVs. Raw-energy WAVs and the folder page are in the separate full ZIP; no adjacent files are needed for the players, patches or measurements here.';
}
document.querySelectorAll('[data-mode]').forEach(el => el.addEventListener('click', () => applyMode(el.dataset.mode)));
document.querySelectorAll('audio').forEach(el => el.addEventListener('play', () => document.querySelectorAll('audio').forEach(other => { if (other !== el) other.pause(); })));
function downloadJson(value, filename) {
  const url = URL.createObjectURL(new Blob([JSON.stringify(value, null, 2)], {type:'application/json'}));
  const a = document.createElement('a'); a.href = url; a.download = filename; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
}
document.querySelectorAll('.patch').forEach(el => el.addEventListener('click', event => { event.preventDefault(); downloadJson(presetById[el.dataset.preset].patch, el.dataset.preset + '.json'); }));
document.getElementById('manifest-link').addEventListener('click', event => { event.preventDefault(); downloadJson(manifest, 'manifest.json'); });
function notes() { return manifest.presets.map(p => ({preset:p.name, verdict:document.getElementById('verdict-' + p.id).value, notes:document.getElementById('note-' + p.id).value})); }
try { const saved = JSON.parse(localStorage.getItem(storageKey) || '[]'); saved.forEach(note => { const p = manifest.presets.find(p => p.name === note.preset); if (p) { document.getElementById('verdict-' + p.id).value = note.verdict || ''; document.getElementById('note-' + p.id).value = note.notes || ''; } }); } catch (_) {}
document.querySelectorAll('[data-review],[data-note]').forEach(el => el.addEventListener('input', () => { try { localStorage.setItem(storageKey, JSON.stringify(notes())); } catch (_) { document.getElementById('review-status').textContent = 'This browser cannot save notes locally. Use Export listening notes to keep them.'; } }));
document.getElementById('export-review').addEventListener('click', () => { downloadJson({engine:'OBBLIGATO', audition:'first-listen-v5', midi:manifest.midi, velocity:manifest.velocity, reviews:notes()}, 'obbligato-listening-notes.json'); document.getElementById('review-status').textContent = 'Listening notes exported.'; });
applyMode('matched');
</script></body></html>
"""
    }
}
