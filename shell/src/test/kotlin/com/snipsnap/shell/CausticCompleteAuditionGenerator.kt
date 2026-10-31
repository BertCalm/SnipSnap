package com.snipsnap.shell

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.synth.CausticInstrument
import com.snipsnap.synth.CausticPatch
import com.snipsnap.synth.CausticVoice
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import com.snipsnap.synth.Patches
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.WavInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** Model-two held and moving examples, using the actual production instrument exporter.
 * Every earlier listening pack is retained. Eight-return demonstrations copy one measured
 * native period exactly eight times; they never fade, crossfade or synthesize a seam.
 */
object CausticCompleteAuditionGenerator {
    private const val MODEL = 2
    private const val GLOBAL = "CAUSTIC_COMPLETE_MANIFEST"
    private const val REPEATS = 8
    private const val MOVING = 1f
    private const val RATE = WavWriter.MPC_SAMPLE_RATE
    private const val MAX_ASSET = 25L * 1024 * 1024
    private const val MAX_BUNDLE = 95L * 1024 * 1024
    private data class Case(val id: String, val title: String, val description: String,
        val section: String, val patch: CausticPatch, val comparison: String? = null,
        val body: String? = null)
    private data class Held(val loop: Snip, val note: KeyNote, val preceding: FloatArray,
        val following: FloatArray, val evidence: Map<String, Any?>)
    private data class Capture(val case: Case, val snip: Snip, val evidence: Map<String, Any?>)
    private val voices = CausticVoice.entries.toList()
    private fun title(voice: CausticVoice) = voice.name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    private fun slug(voice: CausticVoice) = voice.name.lowercase().replace('_', '-')

    @JvmStatic fun main(args: Array<String>) {
        require(args.size <= 1) { "supply at most one dedicated CAUSTIC complete audition directory" }
        val repo = repository()
        val output = File(args.firstOrNull() ?: File(repo, "testkit/caustic-complete-audition").path).canonicalFile
        val protected = listOf("caustic-audition", "caustic-instrument-audition", "caustic-character-study", "caustic-native-voices-audition")
            .map { File(repo, "testkit/$it").canonicalFile }
        require(!repo.toPath().startsWith(output.toPath()) && !output.toPath().startsWith(File(repo, "shell").toPath()) &&
            !output.toPath().startsWith(File(repo, "synth").toPath())) { "choose a dedicated generated-output directory" }
        require(protected.none { output.toPath().startsWith(it.toPath()) || it.toPath().startsWith(output.toPath()) }) { "earlier CAUSTIC packs must stay separate" }
        if (output.exists()) require(File(output, "manifest.js").takeIf(File::isFile)?.readText()?.contains(GLOBAL) == true) { "refusing to replace unrelated files" }
        val before = protected.associateWith(::snapshot)
        output.parentFile.mkdirs()
        val stage = Files.createTempDirectory(output.parentFile.toPath(), ".caustic-complete-").toFile()
        try {
            generate(repo, stage, before.mapKeys { it.key.name }.mapValues { it.value.size })
            for ((directory, hashes) in before) check(snapshot(directory) == hashes) { "earlier listening artifacts changed: $directory" }
            val backup = if (output.exists()) Files.createTempDirectory(output.parentFile.toPath(), ".caustic-complete-backup-").toFile().also {
                check(it.delete()); Files.move(output.toPath(), it.toPath())
            } else null
            try { Files.move(stage.toPath(), output.toPath()) }
            catch (error: Exception) { if (backup != null) Files.move(backup.toPath(), output.toPath()); throw error }
            backup?.deleteRecursively()
            println("CAUSTIC complete: ${File(output, "manifest.json").absolutePath}; previous artifacts unchanged")
        } finally { if (stage.exists()) stage.deleteRecursively() }
    }

    private fun generate(repo: File, root: File, preserved: Map<String, Int>) {
        check(voices.size == 4 && CausticInstrument.supportsModel(MODEL))
        val revision = provenance(repo)
        val cases = cases()
        check(cases.size == 28 && cases.map { it.id }.distinct().size == 28)
        val held = linkedMapOf<String, Held>()
        val captures = cases.mapIndexed { index, case ->
            println("CAUSTIC complete ${index + 1}/${cases.size}: ${case.id}")
            val restored = CausticPatch.fromJsonText(case.patch.toJsonText())
            check(restored == case.patch && Patches.fromJsonText(case.patch.toJsonText()) == case.patch) { "${case.id}: native patch round trip failed" }
            val start = System.nanoTime()
            val native: Snip
            val evidence: Map<String, Any?>
            when (case.section) {
                "held" -> {
                    val proof = captureHeld(case.patch)
                    held[case.id] = proof; native = proof.loop; evidence = proof.evidence
                }
                "returns" -> {
                    val proof = held.getValue(requireNotNull(case.body))
                    native = Snip(repeat(proof.loop.samples, REPEATS), 1, RATE)
                    check((0 until REPEATS).all { n -> native.samples.copyOfRange(n * proof.loop.frameCount, (n + 1) * proof.loop.frameCount).contentEquals(proof.loop.samples) })
                    evidence = proof.evidence + mapOf("repeatCount" to REPEATS, "bodyId" to case.body,
                        "assembly" to "Eight exact native periods, no end fade, crossfade or browser looping")
                }
                else -> { native = case.patch.render(); evidence = emptyMap() }
            }
            valid(case.id, native)
            if (case.section == "held" && case.patch.midi == 60 && case.patch.macros.getValue("MOTION") == 0f)
                check(native.samples.contentEquals(CausticInstrument.renderLoop(restored.voice, restored.macros,
                    restored.midi, restored.velocity, restored.model).samples)) { "${case.id}: held replay changed samples" }
            if (case.section == "finite" && case.patch.macros.getValue("MOTION") == 0f)
                check(native.samples.contentEquals(restored.render().samples)) { "${case.id}: finite replay changed samples" }
            Capture(case, native, metrics(case.patch, native) + evidence + ("renderMillis" to (System.nanoTime() - start) / 1e6))
        }
        val byId = captures.associateBy { it.case.id }
        val records = captures.map { capture ->
            val case = capture.case
            val matched = AuditionLevel.level(capture.snip)
            valid("${case.id} matched", matched)
            val nativePath = "audio/${case.id}-native.wav"; val matchedPath = "audio/${case.id}-matched.wav"
            val recipe = "patches/${case.id}.json"; val diagnostic = "diagnostics/${case.id}.json"
            WavWriter.write(File(root, nativePath), capture.snip, WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_24)
            File(root, recipe).apply { parentFile.mkdirs(); writeText(case.patch.toJsonText() + "\n") }
            val measured = LinkedHashMap(capture.evidence)
            case.comparison?.let { reference ->
                val other = byId.getValue(reference)
                check(!capture.snip.samples.contentEquals(other.snip.samples)) { "${case.id}: MOTION comparison did not change samples" }
                measured["comparison"] = shapeDifference(capture.snip.samples, other.snip.samples)
            }
            if (case.section == "returns") {
                val proof = held.getValue(requireNotNull(case.body))
                val contextPath = "evidence/${slug(case.patch.voice)}-moving-real-neighbours.wav"
                val context = Snip(proof.preceding + proof.loop.samples + proof.following, 1, RATE)
                WavWriter.write(File(root, contextPath), context, WavWriter.BitDepth.PCM_24)
                measured["realNeighbourContext"] = contextPath; measured["realNeighbourContextSha256"] = sha(File(root, contextPath))
            }
            writeJson(File(root, diagnostic), linkedMapOf("id" to case.id, "sourceRevision" to revision, "metrics" to measured))
            linkedMapOf<String, Any?>("id" to case.id, "title" to case.title, "description" to case.description,
                "section" to case.section, "voice" to case.patch.voice.name, "midi" to case.patch.midi,
                "velocity" to case.patch.velocity, "model" to MODEL, "macros" to case.patch.macros,
                "seconds" to capture.snip.durationSeconds, "comparison" to case.comparison,
                "bodyId" to case.body, "repeatCount" to if (case.section == "returns") REPEATS else 1,
                "native" to nativePath, "matched" to matchedPath, "recipe" to recipe, "diagnostics" to diagnostic,
                "nativeSha256" to sha(File(root, nativePath)), "matchedSha256" to sha(File(root, matchedPath)),
                "recipeSha256" to sha(File(root, recipe)), "diagnosticsSha256" to sha(File(root, diagnostic)), "metrics" to measured)
        }
        val keygroups = voices.map { voice -> exportKeygroup(root, voice,
            cases.first { it.id == "${slug(voice)}-held-moving-c4" }.patch, held.getValue("${slug(voice)}-held-moving-c4"), revision) }
        val maximumPeak = captures.maxOf { peak(it.snip.samples) }
        val summary = linkedMapOf<String, Any?>("clipCount" to records.size, "voiceCount" to voices.size,
            "heldCount" to 16, "eightReturnCount" to 4, "finiteCount" to 8,
            "sampleRate" to RATE, "channels" to 1, "bitDepth" to 24, "nativeExportGain" to 1,
            "maximumNativePeak" to maximumPeak, "sampleHeadroomDb" to -20 * log10(maximumPeak),
            "maximumSeamError" to held.values.maxOf { it.evidence.getValue("maximumNeighbourSeam") as Double },
            "maximumContinuationError" to held.values.maxOf { it.evidence.getValue("continuationError") as Double },
            "earlierFilesPreserved" to preserved, "maximumNewExpandedBytes" to MAX_BUNDLE,
            "leveling" to "Native is actual finished renderer output. Matched copies use the watched shell AuditionLevel once per complete preview.",
            "limits" to listOf("Example patches for listening, not additions to factory presets", "One held-cycle capture ends after one period; four separate previews repeat exactly eight periods",
                "Keygroups preserve their actual note-on prefix and native loop; key-up uses the host amplitude release", "MPC file readback is not a hardware listening check"))
        val manifest = linkedMapOf<String, Any?>("schema" to 1, "model" to MODEL, "title" to "CAUSTIC / Hold and move",
            "description" to "Held bodies, moving paths and ordinary playable keygroups for all four native voices.",
            "generatedAt" to Instant.now().toString(), "sourceRevision" to revision, "summary" to summary,
            "voices" to voices.map { linkedMapOf("id" to it.name, "slug" to slug(it), "title" to title(it)) },
            "history" to linkedMapOf("native" to "https://caustic-snipsnap-voices.bertcalm.chatgpt.site/", "earlier" to "https://caustic-snipsnap-audition.bertcalm.chatgpt.site/contrast/"),
            "downloads" to linkedMapOf("comparison" to "caustic-complete-comparison.zip", "keygroups" to keygroups), "clips" to records)
        val assets = File(repo, "shell/src/test/resources/caustic-complete-audition")
        for (name in listOf("index.html", "app.js", "style.css")) File(assets, name).copyTo(File(root, name))
        File(root, "README.txt").writeText("""
            CAUSTIC / Hold and move, native model 2

            16 genuine held-cycle captures; four eight-return moving demonstrations;
            eight finite MOTION comparisons. Example JSON files are ordinary native
            CausticPatch recipes, not new factory presets. Native WAVs retain renderer
            level; matched WAVs use the watched audition loudness rule once.

            An eight-return WAV copies a measured body exactly eight times. It has no
            repaired seam, crossfade or imposed end fade. The evidence WAV contains the
            actual preceding, kept and following native periods from advancing state.
            The browser plays fixed captures. It does not provide a held-note engine.

            Four keygroup ZIPs use the production CausticPadMaker and OneNote path.
            Keep the XTY beside its TrackData folder. MPC 2 uses the XPM inside that
            folder. Native patches and the phone instrument sidecar are included.
            Each program covers its recorded key range only; intermediate keys transpose
            their nearest root and also change motion speed. Saved source velocity
            shapes excitation; live key velocity changes sample level. Key-up uses the
            sampler amplitude release, not an engine source-off simulation.
            File agreement is checked; hardware hold/release remains for listening.

            Reproduce from the source revision with :shell:generateCausticCompleteAudition.
            Open index.html with manifest.js, audio, patches and diagnostics beside it.
            Earlier finite model-one recordings and all earlier archives remain intact.

            Why it is built this way
            1. Held bodies at C2/C4/C6 expose pitch and register without huge previews.
            2. Still/moving pairs isolate MOTION in the held and finite versions.
            3. Four exact eight-return demonstrations expose genuine periodic seams.
            4. Production keygroup exports keep the real arrival and measured sustain.
        """.trimIndent() + "\n")
        writeManifest(root, manifest)
        writeJson(File(root, "diagnostics.json"), linkedMapOf("sourceRevision" to revision, "summary" to summary, "clips" to records))
        comparisonArchive(root, manifest, records)
        check(provenance(repo)["sourceFingerprintSha256"] == revision["sourceFingerprintSha256"]) { "source changed during CAUSTIC complete generation" }
        val files = root.walkTopDown().filter(File::isFile).toList()
        files.forEach { check(it.length() < MAX_ASSET) { "asset exceeds 25 MiB: ${it.name}" } }
        writeJson(File(root, "size-report.json"), linkedMapOf("files" to files.size, "expandedBytesBeforeThisReport" to files.sumOf(File::length),
            "maximumExpandedBytes" to MAX_BUNDLE, "archives" to files.filter { it.extension == "zip" }.associate { it.name to it.length() }))
        check(root.walkTopDown().filter(File::isFile).sumOf(File::length) <= MAX_BUNDLE) { "new audition exceeds its 95 MiB expanded budget" }
    }

    private fun cases(): List<Case> {
        val cases = mutableListOf<Case>()
        for (voice in voices) {
            val name = title(voice); val id = slug(voice)
            val defaults = CausticInstrument.defaults(voice, MODEL)
            fun patch(label: String, midi: Int = 60, motion: Float = 0f, hold: Float = 1f) =
                CausticPatch("$name $label", voice, defaults + mapOf("MOTION" to motion, "HOLD" to hold), midi, .75f, MODEL)
            for ((tag, midi) in listOf("c4" to 60, "c2" to 36, "c6" to 84)) cases += Case("$id-held-$tag", "$name / Held ${tag.uppercase()}",
                "One actual steady period at the requested note. Use the eight-return demonstration or the keygroup to judge continued holding.", "held", patch("Held ${tag.uppercase()}", midi))
            cases += Case("$id-held-moving-c4", "$name / Moving held C4", "MOTION 1 changes the native paths and pickup over a full cycle. Compare with the same held body at MOTION 0.",
                "held", patch("Moving held C4", motion = MOVING), "$id-held-c4")
        }
        for (voice in voices) { val id = slug(voice); val body = cases.first { it.id == "$id-held-moving-c4" }
            cases += Case("$id-eight-returns", "${title(voice)} / Eight moving returns", "Eight exact copies of the measured moving body. No browser loop, crossfade or end fade.", "returns", body.patch, body = body.id)
        }
        for (voice in voices) {
            val id = slug(voice); val defaults = CausticInstrument.defaults(voice, MODEL)
            for ((tag, motion) in listOf("still" to 0f, "moving" to MOVING)) cases += Case("$id-finite-$tag", "${title(voice)} / ${if (motion == 0f) "Still" else "Moving"} finite C4",
                "HOLD 0, LENGTH .50 and the same note/effort. Compare MOTION 0 with MOTION 1.", "finite",
                CausticPatch("${title(voice)} finite $tag", voice, defaults + mapOf("HOLD" to 0f, "MOTION" to motion, "LENGTH" to .5f), 60, .75f, MODEL),
                if (motion == 0f) null else "$id-finite-still")
        }
        return cases
    }

    private fun captureHeld(patch: CausticPatch): Held {
        val probe = CausticInstrument.heldProbe(patch.voice, patch.macros, patch.midi, patch.velocity, MODEL)
        val frames = probe.loop.frameCount; val start = probe.note.loopStartFrame.toInt()
        check(frames >= 256 && start >= frames && probe.preceding.size == frames && probe.following.size == frames)
        check(probe.note.snip.samples.copyOfRange(start, probe.note.snip.frameCount).contentEquals(probe.loop.samples)) { "held keygroup body disagrees with the native period" }
        val before = Keys.seamError(probe.preceding + probe.loop.samples, frames)
        val after = Keys.seamError(probe.loop.samples + probe.following, frames)
        check(listOf(before, after, probe.seamError).all { it.isFinite() && it < Keys.MAX_SEAM_ERROR }) { "real-neighbour seam did not close" }
        check(probe.continuationError.isFinite() && probe.continuationError < 1e-3) { "conditioned period did not continue" }
        val d = probe.diagnostics
        val evidence = linkedMapOf<String, Any?>("loopFrames" to frames, "periodSeconds" to frames.toDouble()/RATE,
            "nativePrefixFrames" to start, "prefixCycles" to d.prefixCycles,
            "precedingSeam" to before, "followingSeam" to after, "maximumNeighbourSeam" to maxOf(before, after, probe.seamError),
            "keyNoteSeam" to probe.seamError, "continuationError" to probe.continuationError,
            "stateResidual" to d.stateResidual, "rawContinuationError" to d.continuationError,
            "fittedHz" to d.fittedHz, "plannedPitchCents" to d.plannedPitchCents,
            "periodicStateResidual" to d.periodicStateResidual, "rawSeamError" to d.rawSeamError,
            "rawPeak" to d.rawPeak, "sourceMin" to d.sourceMin, "sourceMax" to d.sourceMax,
            "focusMin" to d.focusMin, "focusMax" to d.focusMax,
            "collectionMin" to d.collectionMin, "collectionMax" to d.collectionMax,
            "maxDelayR" to d.maxDelayR, "geometrySnapshots" to d.geometrySnapshots,
            "firL1Bound" to d.firL1Bound, "prepareMillis" to d.prepareNanos / 1e6,
            "precedingFloatSha256" to floatSha(probe.preceding), "keptFloatSha256" to floatSha(probe.loop.samples),
            "followingFloatSha256" to floatSha(probe.following),
            "seamContext" to "Whole actual preceding/kept/following periods, conditioned together before crop")
        return Held(probe.loop, probe.note, probe.preceding, probe.following, evidence)
    }

    private fun exportKeygroup(root: File, voice: CausticVoice, patch: CausticPatch, reference: Held,
        revision: Map<String, Any?>): Map<String, Any?> {
        val work = Files.createTempDirectory(root.parentFile.toPath(), ".caustic-held-keys-").toFile()
        try {
            val name = "Caustic ${title(voice)} Held"
            val spec = CausticPadMaker.Spec(patch, releaseSeconds = .6f)
            val roots = CausticPadMaker.zoneMidis(spec)
            val notes = roots.map { midi -> if (midi == patch.midi) reference.note else CausticPadMaker.renderZone(spec, midi) }
            val program = CausticPadMaker.export(name, spec, notes, work)
            val sidecar = InstrumentStore.sidecar(work, name); val instrument = InstrumentStore.read(sidecar)
            val data = File(work, "${name}_[TrackData]")
            check(CausticPatch.fromJsonText(File(data, CausticPadMaker.PATCH_FILE).readText()) == patch)
            check(instrument.zones.map { it.rootNote } == roots && instrument.release == spec.releaseSeconds)
            verifyPrograms(program, File(data, "$name.xpm"), File(work, "$name.xty"), sidecar)
            val zones = instrument.zones.mapIndexed { index, zone ->
                val file = File(work, zone.sample); val wav = WavReader.read(file); val info = WavInfo.read(file)
                val original = notes[index]
                check(info.sampleRate == RATE && info.channels == 1 && info.bitsPerSample == 24)
                check(wav.frameCount == original.snip.frameCount && zone.loopStartFrame == original.loopStartFrame)
                check(WavReader.readSmplStrict(file) == SmplChunk(zone.rootNote, SmplChunk.Loop(zone.loopStartFrame, zone.frameCount)))
                val error = wav.samples.indices.maxOf { abs(wav.samples[it] - original.snip.samples[it]) }
                check(error <= 2e-7f && wav.samples.all { it.isFinite() && abs(it) < 1f })
                val seam = Keys.seamError(wav.samples, zone.loopStartFrame.toInt())
                check(seam.isFinite() && seam < Keys.MAX_SEAM_ERROR) { "PCM24 keygroup seam exceeds its bound" }
                linkedMapOf("rootMidi" to zone.rootNote, "lowMidi" to zone.lowNote, "highMidi" to zone.highNote,
                    "file" to zone.sample, "frames" to zone.frameCount, "loopStartFrame" to zone.loopStartFrame,
                    "wavSha256" to sha(file), "pcm24Error" to error, "pcm24Seam" to seam)
            }
            writeJson(File(work, "manifest.json"), linkedMapOf("nativeModel" to MODEL, "voice" to voice.name, "sourcePatch" to patch.toJsonValue(), "sourceRevision" to revision,
                "rootMidis" to roots, "releaseSeconds" to spec.releaseSeconds, "zones" to zones,
                "readback" to "Production OneNote package; MPC 3, MPC 2, phone sidecar and WAV smpl agree", "hardwareListening" to "not performed"))
            File(work, "README.txt").writeText("$name\n\nMPC 3: load $name.xty beside its TrackData folder.\nMPC 2: load $name.xpm inside that folder.\nKeep the complete extracted directory together.\nKeys ${instrument.zones.first().lowNote}..${instrument.zones.last().highNote}, native roots ${roots.joinToString()}.\nOne source velocity (.75); live key velocity changes playback gain. Key-up uses the sampler amplitude release.\nThe WAV owns the actual note-on prefix and verified native sustain. File readback passed; hardware listening remains.\n")
            File(work, "read-back.txt").writeText("$name\nMPC 3 keygroup type, NoteOn, root/range, loop bounds and amp release agree.\nMPC 2 NoteOn, root/range, loop bounds and amp release agree.\nPhone sidecar and all ${zones.size} PCM24 mono WAV sampler sheets agree.\nEvery exported PCM24 loop passes the house seam bound ${Keys.MAX_SEAM_ERROR}.\nHardware listening was not performed.\n")
            checksums(work)
            val filename = "caustic-${slug(voice)}-held-keygroup.zip"
            zip(work, File(root, filename), prefix = name + "/")
            return linkedMapOf("voice" to voice.name, "title" to title(voice), "path" to filename,
                "roots" to roots, "lowMidi" to instrument.zones.first().lowNote, "highMidi" to instrument.zones.last().highNote,
                "releaseSeconds" to spec.releaseSeconds, "bytes" to File(root, filename).length(), "sha256" to sha(File(root, filename)))
        } finally { work.deleteRecursively() }
    }

    /** Same file agreements as the existing held export suite, exercised on production output. */
    private fun verifyPrograms(program: KeygroupProgram, xpm: File, xty: File, sidecar: File) {
        val xml = DocumentBuilderFactory.newInstance().apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }.newDocumentBuilder().parse(xpm)
        val instruments = xml.getElementsByTagName("Instrument")
        check(instruments.length == program.keygroups.size)
        fun text(e: org.w3c.dom.Element, name: String) = e.getElementsByTagName(name).item(0).textContent.trim()
        val track = Mpc3Project.read(xty)
        check(track.isTrack)
        val trackProgram = track.tracks.single().getValue("program").obj()
        check(trackProgram.getValue("type").int() == 1)
        val modern = (trackProgram.getValue("drum").obj().getValue("instruments") as JsonValue.Arr).items
        val phone = InstrumentStore.read(sidecar)
        program.keygroups.forEachIndexed { index, zone ->
            val layer = zone.layers.single(); val element = instruments.item(index) as org.w3c.dom.Element
            val filled = element.getElementsByTagName("Layer").item(0) as org.w3c.dom.Element
            check(text(element, "LowNote").toInt() == zone.lowNote && text(element, "HighNote").toInt() == zone.highNote)
            check(text(element, "TriggerMode") == "2" && text(element, "VolumeAttack").toFloat() == 0f && text(element, "VolumeRelease").toFloat() == program.volumeRelease)
            check(text(filled, "SampleName") == layer.sampleName && text(filled, "RootNote").toInt() == zone.rootNote)
            check(text(filled, "SliceLoop") == "1" && text(filled, "SliceLoopStart").toLong() == layer.loopStartFrame && text(filled, "SliceEnd").toLong() == layer.frameCount)
            val item = modern[index].obj(); val envelope = item.getValue("synthSection").obj().getValue("ampEnvelope").obj()
            check(item.getValue("triggerMode").int() == 2 && item.getValue("lowNote").int() == zone.lowNote && item.getValue("highNote").int() == zone.highNote)
            check(!envelope.getValue("OneShot").obj().getValue("value0").bool() && envelope.getValue("Release").obj().getValue("value0").num().toFloat() == program.volumeRelease)
            check(envelope.getValue("Attack").obj().getValue("value0").num() == 0.0)
            val ml = (item.getValue("layersv") as JsonValue.Arr).items.first().obj(); val slice = ml.getValue("sliceInfo").obj()
            check(ml.getValue("rootNote").int() == zone.rootNote && ml.getValue("sampleName").str() == layer.sampleName)
            check(slice.getValue("LoopMode").int() == 1 && slice.getValue("LoopStart").long() == layer.loopStartFrame && slice.getValue("End").long() == layer.frameCount)
            check(phone.zones[index].rootNote == zone.rootNote && phone.zones[index].loopStartFrame == layer.loopStartFrame)
        }
    }

    private fun comparisonArchive(root: File, manifest: Map<String, Any?>, records: List<Map<String, Any?>>) {
        val work = Files.createTempDirectory(root.parentFile.toPath(), ".caustic-complete-comparison-").toFile()
        try {
            val chosen = records.filter { it["midi"] == 60 }
            for (name in listOf("index.html", "app.js", "style.css", "README.txt")) File(root, name).copyTo(File(work, name))
            for (record in chosen) for (field in listOf("native", "matched", "recipe", "diagnostics")) {
                val path = record.getValue(field) as String; File(work, path).parentFile.mkdirs(); File(root, path).copyTo(File(work, path))
            }
            for (record in chosen) {
                val path = (record.getValue("metrics") as Map<*, *>)["realNeighbourContext"] as? String ?: continue
                File(work, path).parentFile.mkdirs(); File(root, path).copyTo(File(work, path), overwrite = true)
            }
            val selectedMetrics = chosen.map { it.getValue("metrics") as Map<*, *> }
            val selectedPeak = selectedMetrics.maxOf { (it["nativePeak"] as Number).toDouble() }
            val selectedSummary = (manifest["summary"] as Map<*, *>) + mapOf(
                "clipCount" to chosen.size, "heldCount" to chosen.count { it["section"] == "held" },
                "maximumNativePeak" to selectedPeak, "sampleHeadroomDb" to -20 * log10(selectedPeak),
                "maximumSeamError" to selectedMetrics.mapNotNull { it["maximumNeighbourSeam"] as? Double }.maxOrNull(),
                "maximumContinuationError" to selectedMetrics.mapNotNull { it["continuationError"] as? Double }.maxOrNull(),
                "portableSubset" to true)
            val subset = LinkedHashMap(manifest).also { it["clips"] = chosen; it["downloads"] = emptyMap<String, String>(); it["summary"] = selectedSummary }
            File(work, "README.txt").appendText("\nPORTABLE C4 SUBSET\nThis archive contains ${chosen.size} C4 captures, including eight held bodies, four eight-return demonstrations and eight finite notes. Keygroup archives and C2/C6 bodies are in the full listening pack.\n")
            writeManifest(work, subset); writeJson(File(work, "diagnostics.json"), linkedMapOf("clips" to chosen, "sourceRevision" to manifest["sourceRevision"]))
            zip(work, File(root, "caustic-complete-comparison.zip"))
        } finally { work.deleteRecursively() }
    }

    private fun metrics(patch: CausticPatch, snip: Snip): Map<String, Any?> {
        val hz = 440.0 * 2.0.pow((patch.midi - 69) / 12.0)
        val from = minOf(.025, snip.durationSeconds.toDouble() / 10).toFloat()
        val window = minOf(.35, snip.durationSeconds.toDouble() * .7).toFloat()
        val pitch = Pitch.detect(snip, fromSec = from, windowSec = window)
        return linkedMapOf("nativePeak" to peak(snip.samples), "nativeRms" to sqrt(snip.samples.sumOf { it.toDouble()*it } / snip.samples.size),
            "nativeDc" to snip.samples.sumOf { it.toDouble() } / snip.samples.size, "requestedHz" to hz,
            "pitchHz" to pitch?.hz, "pitchConfidence" to pitch?.confidence, "pitchCents" to pitch?.let { 1200*ln(it.hz/hz)/ln(2.0) },
            "drumClass" to Classifier.classify(snip).drumClass.name,
            "engineIsLoop" to CausticInstrument.isLoop(patch.voice, patch.macros, patch.model),
            "numericallyFinite" to true, "clippedSamples" to 0)
    }
    private fun shapeDifference(a: FloatArray, b: FloatArray): Map<String, Any?> {
        val end = minOf(a.size,b.size); var aa=0.0; var bb=0.0; var ab=0.0
        for(i in 0 until end){val x=a[i].toDouble();val y=b[i].toDouble();aa+=x*x;bb+=y*y;ab+=x*y}
        val gain=ab/bb.coerceAtLeast(1e-30);var error=0.0
        for(i in 0 until end){val d=a[i]-gain*b[i];error+=d*d}
        return linkedMapOf("gainFitResidual" to sqrt(error/aa.coerceAtLeast(1e-30)),"cosineSimilarity" to ab/sqrt((aa*bb).coerceAtLeast(1e-30)),"note" to "PCM shape after scalar gain fit, not an audibility verdict")
    }
    private fun valid(label:String,snip:Snip){check(snip.channels==1&&snip.sampleRate==RATE&&snip.samples.isNotEmpty());check(snip.samples.all{it.isFinite()&&abs(it)<1f}){"$label: nonfinite/clipped PCM"};check(peak(snip.samples)>1e-9){"$label: silent PCM"}}
    private fun repeat(body:FloatArray,count:Int)=FloatArray(body.size*count){body[it%body.size]}
    private fun peak(samples:FloatArray)=samples.maxOf{abs(it.toDouble())}
    private fun floatSha(samples:FloatArray):String{val bytes=ByteBuffer.allocate(samples.size*4).order(ByteOrder.LITTLE_ENDIAN);samples.forEach{bytes.putFloat(it)};return sha(bytes.array())}
    private fun writeManifest(root:File,manifest:Map<String,Any?>){val text=Json.write(json(manifest));File(root,"manifest.json").writeText(text+"\n");File(root,"manifest.js").writeText("window.$GLOBAL = $text;\n")}
    private fun writeJson(file:File,value:Any?){file.parentFile.mkdirs();file.writeText(Json.write(json(value))+"\n")}
    private fun checksums(root:File){val files=root.walkTopDown().filter(File::isFile).sortedBy{it.relativeTo(root).invariantSeparatorsPath}.toList();File(root,"SHA256SUMS.txt").writeText(files.joinToString("\n",postfix="\n"){"${sha(it)}  ${it.relativeTo(root).invariantSeparatorsPath}"})}
    private fun zip(root:File,target:File,prefix:String=""){ZipOutputStream(target.outputStream().buffered()).use{zip->for(file in root.walkTopDown().filter(File::isFile).sortedBy{it.relativeTo(root).invariantSeparatorsPath}){zip.putNextEntry(ZipEntry(prefix+file.relativeTo(root).invariantSeparatorsPath).also{it.time=0L});file.inputStream().use{it.copyTo(zip)};zip.closeEntry()}};check(target.length()<MAX_ASSET){"${target.name} exceeds 25 MiB"}}
    private fun snapshot(root:File)=if(root.isDirectory)root.walkTopDown().filter(File::isFile).associate{it.relativeTo(root).invariantSeparatorsPath to sha(it)}else emptyMap()
    private fun repository():File=generateSequence(File(System.getProperty("user.dir")).canonicalFile){it.parentFile}.first{File(it,".git").exists()}
    private fun provenance(repo:File):Map<String,Any?>{
        fun git(vararg args:String):String{val p=ProcessBuilder(listOf("git","-C",repo.path)+args).redirectErrorStream(true).start();val result=p.inputStream.readBytes().toString(Charsets.UTF_8);check(p.waitFor()==0);return result}
        val roots=arrayOf("synth/src/main","shell/src/main","audio/src/main","kit/src/main","mpc3/src/main","xpm/src/main","json/src/main","shell/src/test/kotlin/com/snipsnap/shell/CausticCompleteAuditionGenerator.kt","shell/src/test/kotlin/com/snipsnap/shell/AuditionLevel.kt","synth/src/test/kotlin/com/snipsnap/synth/AuditionLevel.kt","shell/src/test/resources/caustic-complete-audition","shell/build.gradle.kts")
        val tracked=git("ls-files","-z","--",*roots).split('\u0000').filter(String::isNotBlank).toSet();val untracked=git("ls-files","--others","--exclude-standard","-z","--",*roots).split('\u0000').filter(String::isNotBlank).toSet()
        val files=(tracked+untracked).sorted().mapNotNull{path->File(repo,path).takeIf(File::isFile)?.let{linkedMapOf("path" to path,"sha256" to sha(it),"tracked" to(path in tracked))}}
        return linkedMapOf("commit" to git("rev-parse","HEAD").trim(),"branch" to git("rev-parse","--abbrev-ref","HEAD").trim(),"dirty" to git("status","--porcelain","--",*roots).isNotBlank(),"sourceFingerprintSha256" to sha(files.joinToString("\n"){"${it["path"]}\u0000${it["sha256"]}"}.toByteArray()),"files" to files,"runtime" to "${System.getProperty("java.runtime.version")} ${System.getProperty("os.arch")}")
    }
    private fun sha(file:File)=sha(file.readBytes())
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt()and 255)}
    private fun json(value:Any?):JsonValue=when(value){null->JsonValue.Null;is JsonValue->value;is String->JsonValue.Str(value);is Boolean->JsonValue.Bool(value);is Number->JsonValue.Num(value.toDouble().also{check(it.isFinite())});is Map<*,*>->JsonValue.Obj(value.entries.associateTo(LinkedHashMap()){it.key.toString() to json(it.value)});is Iterable<*>->JsonValue.Arr(value.map(::json));else->error("unsupported CAUSTIC complete evidence ${value::class.simpleName}")}
}
