package com.snipsnap.shell

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Obbligato
import com.snipsnap.synth.ObbligatoConversation
import com.snipsnap.synth.ObbligatoHeld
import com.snipsnap.synth.ObbligatoPatch
import com.snipsnap.synth.ObbligatoPlayback
import com.snipsnap.synth.ObbligatoPresets
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/** Native range, repeating sample-player performances, and complete portable held instruments. */
object ObbligatoPerformanceAuditionGenerator {
    private const val VELOCITY = .8f
    private const val SEED = 0
    private const val TARGET = .12f
    private const val HEADROOM = .95f
    private const val MAX_ASSET_BYTES = 25L * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 256L * 1024 * 1024
    private val notes = listOf(48 to "C3", 60 to "C4", 72 to "C5", 84 to "C6")
    private val relationships = listOf("Harmony", "Play", "Fight", "Protect")

    private data class Take(
        val id: String, val relationship: String, val note: String, val variant: String,
        val patch: ObbligatoPatch, val source: Snip, val held: ObbligatoHeld.Held? = null,
        val noteOffFrame: Int? = null,
    )
    private data class Clip(val id: String, val take: Take, val role: String, val source: Snip, val levelGain: Float)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Usage: ObbligatoPerformanceAuditionGenerator [output-directory]" }
        require(Obbligato.PERFORMANCE_MODEL == 11 && ObbligatoHeld.VERSION == 1)
        val root = File(args.firstOrNull() ?: "../testkit/obbligato-performance-audition").absoluteFile
        require(root.isDirectory || root.mkdirs()) { "Could not create $root" }
        val repository = repositoryRoot()
        val takes = ArrayList<Take>()
        val clips = ArrayList<Clip>()
        val parity = linkedMapOf<String, Boolean>()
        val bases = ObbligatoPresets.all().associateBy { it.name }
        for (relationship in relationships) {
            val base = bases.getValue(relationship).copy(model = Obbligato.PERFORMANCE_MODEL,
                velocity = VELOCITY, seed = SEED, playback = ObbligatoPlayback.FINITE)
            for ((midi, note) in notes) {
                val prefix = "${relationship.lowercase()}_${note.lowercase()}"
                val phrasePatch = base.copy(name = "$relationship $note phrase", midi = midi,
                    macros = base.macros + ("HOLD" to .45f))
                val phrase = Take("${prefix}_phrase", relationship, note, "phrase", phrasePatch, phrasePatch.render())
                add(takes, clips, phrase)

                val heldPatch = base.copy(name = "$relationship $note held", midi = midi,
                    macros = base.macros + ("HOLD" to 1f), playback = ObbligatoPlayback.HELD)
                val held = ObbligatoHeld.render(heldPatch)
                val spec = ObbligatoPadMaker.Spec(heldPatch)
                val performed = ObbligatoPadMaker.preview(spec)
                val fullReplay = player(spec, held, held.snip.samples)
                check(sameBits(performed.samples, fullReplay.samples) && performed.channels == fullReplay.channels) {
                    "${heldPatch.name}: audition sampler does not reproduce the production preview"
                }
                val sustained = Take("${prefix}_held", relationship, note, "held", heldPatch, performed,
                    held, ObbligatoPadMaker.previewNoteOffFrame(held))
                add(takes, clips, sustained)
                if (midi == 60) {
                    val gain = clips.last().levelGain
                    val air = player(spec, held, held.air)
                    val reed = player(spec, held, held.reed)
                    check(air.samples.indices.all {
                        abs(air.samples[it] + reed.samples[it] - performed.samples[it]) < 2e-6f
                    }) { "$relationship: actual player taps no longer reconstruct the performed duet" }
                    clips += Clip("${prefix}_held_air", sustained, "air", air, gain)
                    clips += Clip("${prefix}_held_reed", sustained, "reed", reed, gain)
                    val previous = phrasePatch.copy(name = "$relationship approved C4 reference",
                        model = Obbligato.MORE_OVERLAP_MODEL)
                    val approved = Take("${prefix}_approved", relationship, note, "approved", previous, previous.render())
                    parity[relationship] = sameBits(phrase.source.samples, approved.source.samples)
                    check(parity.getValue(relationship)) { "$relationship: the approved finite C4 sound changed" }
                    add(takes, clips, approved)
                }
                println("OBBLIGATO sustain/range: $relationship $note")
            }
        }
        check(clips.size == 44 && takes.size == 36)
        clips.forEach { validate(it.source) }
        val common = minOf(1f, HEADROOM / clips.maxOf { it.source.peak() * it.levelGain })
        val rows = clips.map { clip ->
            val gain = clip.levelGain * common
            val output = clip.source.copy(samples = FloatArray(clip.source.samples.size) { clip.source.samples[it] * gain })
            check(output.peak() <= HEADROOM + 1e-5f)
            val path = "audio/${clip.id}.wav"
            val file = WavWriter.write(File(root, path), output, WavWriter.BitDepth.PCM_24)
            linkedMapOf<String, Any?>("id" to clip.id, "take" to clip.take.id,
                "relationship" to clip.take.relationship, "note" to clip.take.note,
                "midi" to clip.take.patch.midi, "variant" to clip.take.variant, "role" to clip.role,
                "path" to path, "gain" to gain, "levelMatchGain" to clip.levelGain,
                "channels" to output.channels, "frames" to output.frameCount,
                "bytes" to file.length(), "sha256" to hash(file),
                "sourceSamplesSha256" to samplesHash(clip.source.samples),
                "sourceMetrics" to metrics(clip.source), "exportMetrics" to metrics(output))
        }
        val takeRows = takes.map { take ->
            val path = "patches/${take.id}.patch"
            val recipe = File(root, path).also { it.parentFile.mkdirs(); it.writeText(take.patch.toJsonText() + "\n") }
            val diagnostic = take.held?.let { held ->
                linkedMapOf<String, Any?>("loopStartFrame" to held.loopStartFrame,
                    "loopEndExclusive" to held.loopEndExclusive, "noteOffFrame" to take.noteOffFrame,
                    "releaseFrames" to ObbligatoPadMaker.previewReleaseFrames(ObbligatoPadMaker.Spec(take.patch), take.source.sampleRate),
                    "previewPolicy" to "Original opening and first sustain pass, one further complete sustain pass, then actual InstrumentEngine note-off and release.",
                    "loopMarkerPolicy" to "Loop bounds refer to the source keygroup sample. The listening WAV unfolds the performance and is not itself marked as a sustain loop.",
                    "metrics" to heldMetrics(held), "conversationEvents" to events(held.conversationEvents))
            }
            val diagnosticPath = "diagnostics/${take.id}.json"
            writeJson(File(root, diagnosticPath), linkedMapOf("patch" to take.patch.toJsonValue(),
                "variant" to take.variant, "held" to diagnostic,
                "finiteSamples" to true, "sourceSamplesSha256" to samplesHash(take.source.samples)))
            linkedMapOf<String, Any?>("id" to take.id, "relationship" to take.relationship,
                "note" to take.note, "variant" to take.variant, "patch" to take.patch.toJsonValue(),
                "patchPath" to path, "patchSha256" to hash(recipe), "diagnosticPath" to diagnosticPath,
                "held" to diagnostic)
        }
        val instruments = relationships.map { relationship ->
            val patch = bases.getValue(relationship).copy(name = "$relationship held instrument", model = Obbligato.PERFORMANCE_MODEL,
                midi = 60, velocity = VELOCITY, seed = SEED,
                macros = bases.getValue(relationship).macros + ("HOLD" to 1f), playback = ObbligatoPlayback.HELD)
            instrument(root, relationship, patch)
        }
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "engine" to "OBBLIGATO", "audition" to "Sustain and range",
            "renderer" to Obbligato.PERFORMANCE_MODEL, "heldVersion" to ObbligatoHeld.VERSION,
            "nativeMidiRange" to listOf(48, 84), "nativeRangeLabel" to "C3–C6",
            "relationships" to relationships, "notes" to notes.map { linkedMapOf("midi" to it.first, "name" to it.second) },
            "sampleRate" to WavWriter.MPC_SAMPLE_RATE, "pcmBits" to 24,
            "velocity" to VELOCITY, "seed" to SEED, "releaseSeconds" to .6f,
            "targetLoudnessBeforeHeadroom" to TARGET, "commonHeadroomAttenuation" to common,
            "effectiveTargetLoudness" to TARGET * common,
            "gainPolicy" to "Complete finite and performed held notes share one loudness target. Each actual C4 source tap retains its own held duet's exact gain. One common final attenuation preserves pack-wide headroom. Instrument packages retain native source levels.",
            "finiteC4MatchesApproved" to parity,
            "heldPolicy" to "The opening plays once. A measured capture of the duet's exchange repeats while the key is held. The sample player owns note-off and release. This is a captured sustain articulation, not a claim that the physical duet state recurs exactly.",
            "effects" to emptyList<String>(), "takes" to takeRows, "clips" to rows, "instruments" to instruments,
            "sourceFiles" to rendererSources(repository),
            "ledger" to linkedMapOf(
                "verified" to "Complete saved patches, source loop markers, exact production-preview replay, additive actual taps, instrument sample references and written recording hashes.",
                "measured" to "Kotlin renders: level, finite output, headroom, DC, one-window pitch estimates and held-capture diagnostics.",
                "proposed" to "The extended register and captured sustain are new listening choices. Numerical checks alone cannot establish musical preference.",
                "heard" to "The owner approved the preceding finite overlap and requested both sustained HOLD and a wider register. This performance audition has no listening verdict."))
        writeJson(File(root, "manifest.json"), manifest)
        File(root, "index.html").writeText(html(Json.write(json(manifest)), rows, instruments))
        File(root, "README.txt").writeText(readme())
        val files = root.walkTopDown().filter { it.isFile }.toList()
        check(files.all { it.length() < MAX_ASSET_BYTES }) { "An audition asset exceeds 25 MiB" }
        val expanded = files.sumOf { it.length() }
        check(expanded < MAX_EXPANDED_BYTES) { "The expanded audition exceeds 256 MiB: $expanded bytes" }
        println("Wrote ${rows.size} PCM24 performances/taps, four complete held-instrument ZIPs, recipes and comparison page; $expanded expanded bytes")
    }

    private fun add(takes: MutableList<Take>, clips: MutableList<Clip>, take: Take) {
        validate(take.source)
        takes += take
        clips += Clip(take.id, take, "duet", take.source, TARGET / Loudness.of(take.source))
    }

    /** Actual reference sample playback, also used to expose taps on the complete duet's schedule. */
    private fun player(spec: ObbligatoPadMaker.Spec, held: ObbligatoHeld.Held, samples: FloatArray): Snip {
        require(samples.size == held.snip.frameCount)
        val midi = spec.patch.midi
        val sample = "audition-source.wav"
        val instrument = InstrumentStore.Instrument(spec.patch.name, spec.releaseSeconds,
            listOf(InstrumentStore.Zone(midi, midi, midi, sample, samples.size.toLong(), held.loopStartFrame.toLong())),
            explicitNoteOn = true)
        val rate = held.snip.sampleRate
        val engine = InstrumentEngine(InstrumentEngine.Loaded(instrument, rate, mapOf(sample to samples)), rate)
        val noteOff = ObbligatoPadMaker.previewNoteOffFrame(held)
        val release = ObbligatoPadMaker.previewReleaseFrames(spec, rate)
        val output = FloatArray((noteOff + release) * 2)
        var frame = 0
        fun append(count: Int) {
            var remaining = count
            while (remaining > 0) {
                val n = minOf(remaining, 512)
                val block = FloatArray(n * 2)
                engine.render(block, n)
                block.copyInto(output, frame * 2)
                frame += n; remaining -= n
            }
        }
        check(engine.noteOn(midi)) { "No source zone for $midi" }
        append(noteOff); engine.noteOff(midi); append(release)
        return Snip(output, 2, rate)
    }

    private fun instrument(root: File, relationship: String, patch: ObbligatoPatch): Map<String, Any?> {
        val id = relationship.lowercase()
        val work = File(root, ".instrument-work/$id")
        check(!work.exists() || work.deleteRecursively()) { "Could not clear the generated instrument work folder" }
        check(work.mkdirs())
        try {
            val name = "Obbligato $relationship"
            val spec = ObbligatoPadMaker.Spec(patch)
            val midis = ObbligatoPadMaker.zoneMidis(spec)
            check(midis == (48..84 step 3).toList())
            val notes = midis.mapIndexed { index, midi ->
                println("OBBLIGATO $relationship instrument zone ${index + 1}/${midis.size}: $midi")
                ObbligatoPadMaker.renderZone(spec, midi)
            }
            val program = ObbligatoPadMaker.export(name, spec, notes, work)
            check(program.keygroups.first().lowNote == 48 && program.keygroups.last().highNote == 84)
            val (sidecar, saved) = InstrumentStore.list(work).single()
            check(saved.zones.size == midis.size && saved.zones.map { it.rootNote } == midis)
            check(saved.zones.first().lowNote == 48 && saved.zones.last().highNote == 84)
            val zoneRows = saved.zones.map { zone ->
                val file = File(sidecar.parentFile, zone.sample)
                check(file.isFile && WavReader.readSmplStrict(file)?.loop?.startFrame == zone.loopStartFrame)
                linkedMapOf("rootMidi" to zone.rootNote, "lowMidi" to zone.lowNote, "highMidi" to zone.highNote,
                    "pathInArchive" to "$name/${file.relativeTo(work).invariantSeparatorsPath}",
                    "frames" to zone.frameCount, "loopStartFrame" to zone.loopStartFrame,
                    "sha256" to hash(file))
            }
            File(work, "README.txt").writeText("""
                $name

                Thirteen native mono PCM24 zones cover C3 through C6. The original opening
                plays once and the marked exchange repeats while a key is held. Note-off
                uses the sample player's release. The package retains native source levels;
                the online comparison applies a separate listening gain.

                Extract this entire folder. MPC 3: open $name.xty with its TrackData folder
                beside it. MPC 2: open the .xpm inside that TrackData folder. The instrument
                sidecar and complete .patch.json retain the phone's playback recipe.
                The numeric release field is not a physical MPC timing measurement.
            """.trimIndent() + "\n")
            val archive = File(root, "instruments/obbligato-$id-held.zip")
            archive.parentFile.mkdirs()
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                work.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(work).invariantSeparatorsPath }.forEach { file ->
                    zip.putNextEntry(ZipEntry("$name/${file.relativeTo(work).invariantSeparatorsPath}").also { it.time = 0L })
                    file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
            check(archive.length() < MAX_ASSET_BYTES) { "$relationship instrument ZIP exceeds 25 MiB" }
            return linkedMapOf("id" to id, "relationship" to relationship, "name" to name,
                "path" to archive.relativeTo(root).invariantSeparatorsPath, "bytes" to archive.length(),
                "sha256" to hash(archive), "patch" to patch.toJsonValue(), "nativeRoots" to midis,
                "lowMidi" to 48, "highMidi" to 84, "releaseSeconds" to spec.releaseSeconds,
                "explicitNoteOn" to program.explicitNoteOn, "zones" to zoneRows)
        } finally {
            check(work.deleteRecursively()) { "Could not remove generated instrument work files" }
            work.parentFile.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
        }
    }

    private fun heldMetrics(held: ObbligatoHeld.Held): Map<String, Any?> = held.diagnostics.let { d -> linkedMapOf(
        "sourceSeconds" to d.sourceSeconds, "loopSeconds" to d.loopSeconds, "blendSeconds" to d.blendSeconds,
        "correlation" to d.correlation, "blendDipDb" to d.blendDipDb, "levelStepDb" to d.levelStepDb,
        "wrapStep" to d.wrapStep, "blendPeakStep" to d.blendPeakStep, "seamError" to d.seamError,
        "airLeadSeconds" to d.airLeadSeconds, "reedLeadSeconds" to d.reedLeadSeconds,
        "actorChanges" to d.actorChanges, "searchCandidates" to d.searchCandidates,
        "gain" to d.gain, "outputPeak" to d.outputPeak, "sourceDiagnostics" to d.sourceDiagnostics,
        "silent" to d.silent) }

    private fun events(events: List<ObbligatoConversation.Event>): List<Map<String, Any?>> = events.map { e -> linkedMapOf(
        "seconds" to e.seconds, "actor" to e.actor.name, "kind" to e.kind,
        "rememberedDuration" to e.rememberedDuration, "rememberedIntensity" to e.rememberedIntensity,
        "rememberedSlope" to e.rememberedSlope, "strength" to e.strength, "duration" to e.duration,
        "heardAir" to e.heardAir, "heardReed" to e.heardReed) }

    private fun validate(snip: Snip) {
        check(snip.sampleRate == WavWriter.MPC_SAMPLE_RATE && snip.channels in 1..2 && snip.samples.isNotEmpty())
        check(snip.samples.all { it.isFinite() } && snip.peak() > 1e-7f && Loudness.of(snip) > 1e-8f)
    }
    private fun metrics(snip: Snip): Map<String, Any?> {
        val pitch = Pitch.detect(snip, fromSec = .8f, windowSec = .3f)
        return linkedMapOf("frames" to snip.frameCount, "seconds" to snip.durationSeconds,
            "channels" to snip.channels, "peak" to snip.peak(), "loudness" to Loudness.of(snip),
            "rms" to sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size),
            "mean" to snip.samples.sumOf { it.toDouble() } / snip.samples.size,
            "pitchWindowSeconds" to listOf(.8, 1.1), "pitchHz" to pitch?.hz, "pitchConfidence" to pitch?.confidence)
    }
    private fun repositoryRoot(): File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private fun rendererSources(repository: File): Map<String, String> = listOf(
        "synth/src/main/kotlin/com/snipsnap/synth/Obbligato.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/ObbligatoPatch.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/ObbligatoHeld.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/ObbligatoConversation.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Bore.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Strings.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Keys.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Tide.kt",
        "shell/src/main/kotlin/com/snipsnap/shell/ObbligatoPadMaker.kt",
        "shell/src/main/kotlin/com/snipsnap/shell/InstrumentEngine.kt",
        "shell/src/main/kotlin/com/snipsnap/shell/KeyHit.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/Fft.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/Pitch.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/WavReader.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/SmplChunk.kt",
        "kit/src/main/kotlin/com/snipsnap/kit/InstrumentStore.kt",
        "kit/src/main/kotlin/com/snipsnap/kit/OneNote.kt",
        "xpm/src/main/kotlin/com/snipsnap/xpm/KeygroupWriter.kt",
        "mpc3/src/main/kotlin/com/snipsnap/mpc3/Mpc3TrackWriter.kt",
        "shell/src/test/kotlin/com/snipsnap/shell/ObbligatoPerformanceAuditionGenerator.kt",
    ).associateWith { hash(File(repository, it)) }
    private fun writeJson(file: File, value: Any?) { file.parentFile.mkdirs(); file.writeText(Json.write(json(value)) + "\n") }
    private fun hash(file: File) = digest(file.readBytes())
    private fun samplesHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putInt(it.toRawBits()) }
        return digest(bytes.array())
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun sameBits(a: FloatArray, b: FloatArray) = a.size == b.size && a.indices.all { a[it].toRawBits() == b[it].toRawBits() }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> { require(value.toDouble().isFinite()); JsonValue.Num(value.toDouble()) }
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported performance metadata ${value.javaClass.name}")
    }

    private fun html(manifest: String, clips: List<Map<String, Any?>>, instruments: List<Map<String, Any?>>): String {
        val rows = clips.associateBy { it.getValue("id") as String }
        fun player(id: String, label: String): String {
            val path = rows.getValue(id).getValue("path")
            return """<div class="listen"><div class="label">$label <a href="$path" download="$id.wav">WAV ↓</a></div><audio controls preload="none" aria-label="$label" data-path="$path" src="$path"></audio></div>"""
        }
        val sections = relationships.joinToString("\n") { relationship ->
            val id = relationship.lowercase()
            val instrument = instruments.single { it["id"] == id }
            val notesHtml = notes.joinToString("\n") { (_, note) ->
                val prefix = "${id}_${note.lowercase()}"
                """<div class="note-row"><h3>$note</h3><div class="grid"><div>${player("${prefix}_phrase", "$relationship $note · PHRASE")}<a class="patch" href="patches/${prefix}_phrase.patch" download>Phrase patch ↓</a></div><div>${player("${prefix}_held", "$relationship $note · HELD")}<a class="patch" href="patches/${prefix}_held.patch" download>Held patch ↓</a></div></div></div>"""
            }
            """<section class="relationship" id="$id"><h2>$relationship</h2><p><a class="instrument" href="${instrument["path"]}" download>Download $relationship held instrument ↓</a> <span class="small">MPC 2 + MPC 3 · thirteen native roots · C3–C6</span></p>$notesHtml<details><summary>Approved reference and the players inside C4</summary><p class="small">The original approved C4 phrase is byte-identical before listening gain. Source taps use the same sample-player events and gain as the held duet.</p>${player("${id}_c4_approved", "$relationship C4 · ORIGINAL APPROVED PHRASE")}<div class="grid"><div>${player("${id}_c4_held_air", "$relationship C4 · HELD AIR")}</div><div>${player("${id}_c4_held_reed", "$relationship C4 · HELD REED")}</div></div></details></section>"""
        }
        return """
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>OBBLIGATO · sustain and range</title><style>
:root{color-scheme:dark;--bg:#101511;--panel:#19221b;--ink:#eaf2e9;--muted:#acbbaa;--accent:#ff8a7a;--line:#354738}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.5 system-ui,sans-serif}main{max-width:1060px;margin:auto;padding:40px 22px 64px}h1{font-size:clamp(36px,6vw,64px);letter-spacing:-.04em;margin:8px 0}h2{font-size:28px;margin:0}h3{font:14px ui-monospace,monospace;margin:0 0 10px;color:var(--accent)}p{color:var(--muted);margin:10px 0 18px}a{color:var(--accent);text-underline-offset:3px}.eyebrow,.label{font:12px/1.4 ui-monospace,monospace;letter-spacing:.08em}.eyebrow{color:var(--accent)}.intro{max-width:760px}.tools{display:flex;flex-wrap:wrap;gap:14px 22px;margin:24px 0}.tools a{display:inline-block;padding:10px 0;min-height:44px}.relationship{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:24px;margin:28px 0}.note-row{border-top:1px solid var(--line);padding:18px 0}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:20px}.label{display:flex;justify-content:space-between;gap:8px;color:var(--muted);margin:8px 0}.label a{white-space:nowrap}audio{display:block;width:100%;height:40px}.patch,.small{font-size:13px}.instrument{display:inline-block;padding:10px 0;min-height:44px;font-weight:650}details{border-top:1px solid var(--line);margin-top:14px;padding-top:16px}summary{cursor:pointer;min-height:36px}button,select,textarea{font:inherit;border:1px solid var(--line);background:#0f1711;color:var(--ink);border-radius:5px;padding:10px}select,textarea{display:block;width:100%;margin:8px 0 18px}button{cursor:pointer;min-height:44px}label{display:block;margin-top:18px;color:var(--accent);font-size:12px;letter-spacing:.06em}.review{border:1px solid var(--line);border-radius:10px;padding:24px;background:var(--panel)}.status{min-height:24px}a:focus-visible,button:focus-visible,summary:focus-visible{outline:2px solid var(--ink);outline-offset:4px}footer{border-top:1px solid var(--line);padding-top:20px;margin-top:30px}@media(max-width:710px){main{padding:24px 14px}.relationship,.review{padding:18px}.grid{grid-template-columns:1fr;gap:16px}.label{letter-spacing:.03em}}
</style></head><body><main><div class="eyebrow">SNIPSNAP / OBBLIGATO / PERFORMANCE AUDITION</div><h1>Sustain and range.</h1><p class="intro">The flute and reed can keep sharing a held note, then release when the key lets go. Compare a complete phrase with a held performance across C3, C4, C5 and C6.</p><div class="tools"><a href="#harmony">Harmony</a><a href="#play">Play</a><a href="#fight">Fight</a><a href="#protect">Protect</a><a href="manifest.json" download id="manifest-download">Settings and measurements ↓</a><a href="https://obbligato-snipsnap-audition.bertcalm.chatgpt.site/">Earlier auditions and pad kit</a></div><p class="small">PHRASE keeps the finite-note behavior; its C4 recording matches the original approved reference. C3 and C6 demonstrate the new native range. Each HELD recording plays its opening once, repeats the captured exchange, then uses the sample player's note-off and release. Complete performances share one listening level. The held-instrument downloads retain native levels and their loop markers.</p><p class="small">Start with Play and Fight at C4, then try the lowest and highest notes. Protect should expose the flute at the opening and sustain the supporting pair afterward. These new performances await a listening verdict.</p>
$sections
<section class="review"><h2>Your listening notes</h2><label for="verdict">SUSTAIN AND RANGE</label><select id="verdict"><option value="">Choose a verdict</option><option>Keep this direction</option><option>Revise the sustain</option><option>Revise the low notes</option><option>Revise the high notes</option></select><label for="notes">WHAT DID YOU HEAR?</label><textarea id="notes" rows="5" placeholder="Do the held replies stay musical? Is there a repeating seam? Do the lowest and highest notes keep both players clear?"></textarea><button type="button" id="export-notes">Export listening notes ↓</button><p class="status small" id="status" aria-live="polite"></p></section><footer><p class="small">The earlier finite sound remains available. Held notes repeat a measured capture of the duet's middle, with the opening outside the loop. The live player owns release; the numeric MPC envelope field has not been measured as a hardware duration.</p></footer>
<script id="manifest" type="application/json">${manifest.replace("<", "\\u003c")}</script><script>
const manifest=JSON.parse(document.getElementById('manifest').textContent);
document.querySelectorAll('audio').forEach(a=>a.addEventListener('play',()=>document.querySelectorAll('audio').forEach(other=>{if(other!==a)other.pause();})));
const storageKey='snipsnap-obbligato-performance-review-v1',verdict=document.getElementById('verdict'),notes=document.getElementById('notes'),status=document.getElementById('status');
try{const saved=JSON.parse(localStorage.getItem(storageKey)||'{}');verdict.value=saved.verdict||'';notes.value=saved.notes||'';}catch(_){status.textContent='Browser storage is unavailable. Export your notes to keep them.';}
function save(){try{localStorage.setItem(storageKey,JSON.stringify({verdict:verdict.value,notes:notes.value}));status.textContent='Notes saved in this browser.';}catch(_){status.textContent='Export your notes to keep them.';}}
verdict.addEventListener('change',save);notes.addEventListener('input',save);
document.getElementById('export-notes').addEventListener('click',()=>{const record={engine:'OBBLIGATO',renderer:manifest.renderer,heldVersion:manifest.heldVersion,nativeMidiRange:manifest.nativeMidiRange,relationships:manifest.relationships,verdict:verdict.value,notes:notes.value,exportedAt:new Date().toISOString()},url=URL.createObjectURL(new Blob([JSON.stringify(record,null,2)+'\n'],{type:'application/json'})),a=document.createElement('a');a.href=url;a.download='obbligato-performance-listening-notes.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);status.textContent='Listening notes exported.';});
</script></main></body></html>
""".trimIndent() + "\n"
    }

    private fun readme() = """
        OBBLIGATO: sustain and range

        Compare PHRASE and HELD in Harmony, Play, Fight and Protect at C3, C4, C5 and C6.
        Every native source uses velocity 0.8 and seed 0. PHRASE uses HOLD 0.45. HELD
        uses HOLD 1, one original opening, a captured exchange that repeats while held,
        then the actual reference sample player's note-off and 0.6-second release.

        Forty-four recordings comprise 32 main comparisons, four approved finite C4
        references and eight actual C4 source taps. All are PCM24/44.1 kHz. The source
        engine is mono; the reference sample-player performances have two identical
        output channels. Complete recordings share a house listening level of 0.12
        before common pack headroom attenuation. The taps inherit their own held
        duet's exact gain. No tap is independently normalized.

        Open index.html with audio/, patches/, diagnostics/, instruments/ and manifest.json
        beside it. Each WAV and complete patch can be downloaded beside its player.
        The four separate instrument ZIPs contain thirteen native source zones each,
        from C3 to C6, MPC 2 and MPC 3 programs, sampler metadata, the phone sidecar and
        a complete saved patch. Their native source levels do not use audition gain.
        Extract each whole ZIP and keep its program with its TrackData folder.

        Manifest.json records every patch, exact note-off schedule, loop source bounds,
        diagnostics, original/rendered gains and output hashes. The HELD listening WAVs
        unfold the performance; loop markers apply to the exported instrument's source
        WAVs. The captured loop is not a claim of exact physical-state recurrence.

        Earlier finite renderer recipes remain available. The four C4 finite references
        are checked against the approved preceding renderer before any listening gain.
        Numerical checks do not establish a listening verdict or physical MPC timing.
    """.trimIndent() + "\n"
}
