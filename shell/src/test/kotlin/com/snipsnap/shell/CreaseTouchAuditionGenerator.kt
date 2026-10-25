package com.snipsnap.shell

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Scales
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Acvs
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.Crease
import com.snipsnap.synth.CreaseKeyProbe
import com.snipsnap.synth.CreaseKeys
import com.snipsnap.synth.CreasePatch
import com.snipsnap.synth.CreasePresets
import com.snipsnap.synth.CreaseTouchKeys
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import com.snipsnap.synth.Patches
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Reproducible touch-layer proof, separate from every historical Crease audition.
 * The production bank supplies all samples and gains, and the production maker
 * supplies complete MPC/phone metadata. Performances reload its actual PCM24
 * files into InstrumentEngine. Only explicitly matched comparison WAVs receive
 * an extra scalar. No claim here substitutes for a phone, MPC or listening test. */
object CreaseTouchAuditionGenerator {
    private const val RATE = 44_100
    private const val RELEASE = .6f
    private const val ASSET_LIMIT = 25L * 1024 * 1024
    private const val PART_BUDGET = 24L * 1024 * 1024
    private val LIVE_TOUCHES = listOf(32, 64, 127)
    private val ONSET_WINDOWS = listOf(.06, .2, .45, .8, 1.2, 1.8, 2.5)

    private data class Event(val frame: Int, val midi: Int, val velocity: Int?)
    private data class Rendered(val snip: Snip, val events: List<Event>)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Supply only the output directory." }
        val repository = repositoryRoot()
        val output = File(args.firstOrNull() ?: "../testkit/crease-touch-audition").canonicalFile
        val protected = listOf("crease-audition", "crease-audition-r2", "crease-audition-r3", "crease-keys-audition")
            .map { File(repository, "testkit/$it").canonicalFile.toPath() }
        require(protected.none { output.toPath().startsWith(it) || it.startsWith(output.toPath()) }) {
            "Touch output must be separate from every historical Crease audition."
        }
        require(git(repository, "status", "--porcelain").isEmpty()) { "Commit tested source before generating touch evidence." }
        val commit = git(repository, "rev-parse", "HEAD")
        val started = System.nanoTime()
        val clips = ArrayList<Map<String, Any?>>()
        val instruments = ArrayList<Map<String, Any?>>()
        val rawRows = ArrayList<Map<String, Any?>>()
        val costs = ArrayList<Long>()
        File(output, "audio").mkdirs()
        File(output, "downloads").mkdirs()
        for (id in listOf("paper-keys", "ink-ribbon", "reed-mark")) {
            val patch = CreasePresets.all().single { it.id == id }.toPatch()
            val full = id != "reed-mark"
            val spec = CreasePadMaker.Spec(patch, releaseSeconds = RELEASE, touchLayers = true)
            val midis = if (full) CreasePadMaker.zoneMidis(spec) else listOf(patch.midi)
            val bundle = File(output, "instruments/$id").apply { mkdirs() }
            val noteBanks = ArrayList<List<KeyNote>>()
            val evidence = ArrayList<Map<String, Any?>>()
            val bankCosts = ArrayList<Double>()
            var root: CreaseTouchKeys.Bank? = null
            for (midi in midis) {
                println("$id MIDI $midi: three excitation layers")
                val before = System.nanoTime()
                val bank = CreaseTouchKeys.probe(patch, midi)
                bankCosts += (System.nanoTime() - before) / 1e9
                require(bank.layers.map { it.velocityWindow } == CreaseTouchKeys.VELOCITY_WINDOWS)
                noteBanks += bank.layers.map { it.probe.note }
                val rows = bank.layers.mapIndexed { index, layer ->
                    costs += layer.probe.diagnostics.renderNanos
                    probeEvidence(id, midi, index, layer, bank.sharedHeadroomGain)
                }
                evidence += linkedMapOf("midi" to midi, "sharedHeadroomGain" to bank.sharedHeadroomGain,
                    "layers" to rows, "harmonicContrast" to contrast(bank), "bankRenderSeconds" to bankCosts.last())
                rawRows += rows
                if (midi == patch.midi) root = bank
            }
            val rootBank = requireNotNull(root)
            // Check one complete app-facing render per instrument, including all layer gains.
            val makerStarted = System.nanoTime()
            val maker = CreasePadMaker.renderLayers(spec, patch.midi)
            require(maker.size == rootBank.layers.size)
            maker.zip(rootBank.layers).forEach { (note, layer) ->
                require(note.loopStartFrame == layer.probe.note.loopStartFrame &&
                    note.snip.samples.contentEquals(layer.probe.note.snip.samples)) { "$id: maker/probe audio mismatch" }
            }
            val makerSeconds = (System.nanoTime() - makerStarted) / 1e9
            val recipe = File(bundle, "source.patch.json").apply { writeText(patch.toJsonText() + "\n") }
            require(Patches.fromJsonText(recipe.readText()) == patch)
            writeJson(File(bundle, "instrument.recipe.json"), linkedMapOf("schemaVersion" to 1,
                "kind" to "crease-touch-instrument", "patch" to patch.toJsonValue(),
                "touchLayers" to true, "releaseSeconds" to RELEASE,
                "touchRenderVersion" to CreaseTouchKeys.RENDER_VERSION, "sourceCommit" to commit))
            val name = "Crease ${patch.name} Touch"
            val loaded = if (full) {
                CreasePadMaker.exportLayers(name, spec, noteBanks, bundle, overwrite = true)
                reloadAndVerify(bundle, name, midis, noteBanks, evidence, output)
            } else rootStudy(bundle, name, patch.midi, rootBank, output, evidence)
            val formatReadback = if (full) verifyMpc(bundle, name, loaded.instrument) else null
            val prefixMax = rootBank.layers.maxOf { it.probe.prefixSeconds }
            val loopMax = rootBank.layers.maxOf {
                (it.probe.note.snip.frameCount - it.probe.prefixFrames).toDouble() / RATE
            }
            val takes = LIVE_TOUCHES.map { velocity -> single(loaded, patch.midi, velocity, 3.0) }
            val matchedTarget = matchedTarget(takes.map { it.snip })
            takes.forEachIndexed { index, take ->
                val velocity = LIVE_TOUCHES[index]
                val label = listOf("Soft", "Middle", "Hard")[index]
                val layer = rootBank.layers[index]
                val common = linkedMapOf<String, Any?>("midiVelocity" to velocity,
                    "excitationVelocity" to layer.excitationVelocity, "layerTouch" to layer.touch,
                    "layer" to index, "pair" to "$id-touch")
                clips += writeClip(output, "$id-touch-$velocity", "$label touch", id, "touch", take,
                    "MIDI velocity $velocity selects excitation ${layer.excitationVelocity}; native player gain.", common)
                clips += writeClip(output, "$id-matched-$velocity", "$label touch, RMS matched", id, "matched", take,
                    "Same take with one RMS matching scalar, so harmonic movement can be judged at equal level.",
                    common + mapOf("matched" to true), matchedTarget / rms(take.snip.samples))
            }
            val boundaries = listOf(42 to 43, 84 to 85).map { (low, high) ->
                val pair = "$id-boundary-$low-$high"
                val results = listOf(low, high).map { velocity ->
                    val take = single(loaded, patch.midi, velocity, 3.0)
                    val hit = requireNotNull(KeyHit.resolve(loaded.instrument, patch.midi, velocity / 127f) {
                        loaded.layerSamples[it]?.size?.toLong()
                    })
                    val row = writeClip(output, "$id-threshold-$velocity", "MIDI $velocity", id, "thresholds", take,
                        "Adjacent velocities $low/$high straddle one layer boundary. No comparison leveling.",
                        mapOf("midiVelocity" to velocity, "pair" to pair, "selectedSample" to hit.sampleFile))
                    clips += row
                    row
                }
                val lowRms = (results[0].getValue("metrics") as Map<*, *>)["rms"] as Number
                val highRms = (results[1].getValue("metrics") as Map<*, *>)["rms"] as Number
                linkedMapOf("low" to low, "high" to high,
                    "rmsStepDb" to db(highRms.toDouble() / lowRms.toDouble()),
                    "playerGainStepDb" to db(high.toDouble() / low), "clips" to results.map { it.getValue("id") })
            }
            val ramp = listOf(16, 32, 48, 64, 80, 96, 127).flatMapIndexed { index, velocity ->
                val time = index * .8
                listOf(Event(frames(time), patch.midi, velocity), Event(frames(time + .55), patch.midi, null))
            }
            clips += writeClip(output, "$id-ramp", "Rising touch phrase", id, "phrase", play(loaded, ramp, frames(6.8)),
                "Repeated C4 key presses rise from MIDI velocity 16 to 127. Releases overlap naturally.")
            val longHold = prefixMax + 1.25 * loopMax
            clips += writeClip(output, "$id-held", "Hard touch, actual sustain wrap and release", id, "held",
                single(loaded, patch.midi, 127, longHold),
                "The hard layer plays beyond the longest layer prefix, wraps its sustain, then releases.",
                mapOf("midiVelocity" to 127, "prefixSeconds" to prefixMax, "loopSeconds" to loopMax))
            // The prior builder uses one saved-velocity waveform. These controls
            // demonstrate what playback gain alone can change under the same player.
            val baseline = CreaseKeys.probe(patch)
            val baselineLoaded = monoStudy(patch.name, patch.midi, baseline.note)
            val controls = listOf(32, 127).map { single(baselineLoaded, patch.midi, it, 3.0) }
            val controlTarget = matchedTarget(controls.map { it.snip })
            controls.forEachIndexed { index, take ->
                val velocity = listOf(32, 127)[index]
                val common = mapOf("midiVelocity" to velocity, "reference" to "gain-only", "pair" to "$id-gain-only")
                clips += writeClip(output, "$id-gain-only-$velocity", "Earlier single layer, MIDI $velocity", id, "gain-only", take,
                    "The unchanged single-layer builder scales the same sample at both touches.", common)
                clips += writeClip(output, "$id-gain-only-matched-$velocity", "Single-layer control, RMS matched $velocity", id,
                    "matched", take, "RMS matching removes this control's playback-gain difference.",
                    common + mapOf("matched" to true), controlTarget / rms(take.snip.samples))
            }
            val controlSoft = controls[0].snip.samples
            val controlHard = controls[1].snip.samples
            val controlError = controlSoft.indices.maxOf { abs(controlSoft[it] - controlHard[it] * (32 / 127f)).toDouble() }
            require(controlError < 2e-7) { "$id: gain-only control changed shape: $controlError" }
            writeJson(File(bundle, "render-evidence.json"), linkedMapOf("sourceCommit" to commit,
                "touchRenderVersion" to CreaseTouchKeys.RENDER_VERSION, "zones" to evidence,
                "thresholds" to boundaries, "gainOnlyMaxError" to controlError,
                "bankRenderSeconds" to bankCosts.sum(), "makerVerificationSeconds" to makerSeconds,
                "formatReadback" to formatReadback, "baselineRenderSeconds" to baseline.diagnostics.renderNanos / 1e9))
            File(bundle, "README.txt").writeText(readme(name, commit, full, midis))
            val packs = if (full) archives(output, bundle, id, name) else emptyList()
            instruments += linkedMapOf("id" to id, "name" to patch.name, "voice" to patch.voice.name,
                "midi" to patch.midi, "model" to patch.model, "savedVelocity" to patch.velocity,
                "macros" to patch.macros, "packageAvailable" to full, "rootStudy" to !full,
                "touches" to CreaseTouchKeys.TOUCHES,
                "velocityWindows" to CreaseTouchKeys.VELOCITY_WINDOWS.map { listOf(it.first, it.last) },
                "liveRepresentatives" to LIVE_TOUCHES, "releaseSeconds" to RELEASE,
                "recipe" to relative(output, recipe), "zones" to evidence,
                "harmonicContrast" to contrast(rootBank), "thresholds" to boundaries,
                "gainOnlyMaxError" to controlError, "bankRenderSeconds" to bankCosts.sum(),
                "makerVerificationSeconds" to makerSeconds, "formatReadback" to formatReadback,
                "prefixSeconds" to prefixMax, "loopSeconds" to loopMax, "packs" to packs)
        }
        val metrics = rawRows.map { it.getValue("metrics") as Map<*, *> }
        val summary = linkedMapOf("instrumentCount" to instruments.count { it["packageAvailable"] == true },
            "rootStudyCount" to instruments.count { it["rootStudy"] == true },
            "zoneCount" to rawRows.groupBy { it["instrument"].toString() + "/" + it["midi"] }.size,
            "layerSampleCount" to rawRows.size, "clipCount" to clips.size,
            "renderSeconds" to costs.sum() / 1e9, "generationSeconds" to (System.nanoTime() - started) / 1e9,
            "maxStateError" to metrics.maxOf { (it["independentStateError"] as Number).toDouble() },
            "maxPcm24SeamError" to metrics.maxOf { (it["pcm24SeamError"] as Number).toDouble() },
            "maxPitchCents" to metrics.mapNotNull { (it["pitchCents"] as? Number)?.toDouble()?.let(::abs) }.maxOrNull(),
            "allConverged" to true, "allPerformancesReleased" to true)
        writeJson(File(output, "raw-metrics.json"), linkedMapOf("sourceCommit" to commit,
            "measurement" to "Kotlin production probes and exported PCM24 read-back", "layers" to rawRows,
            "summary" to summary))
        writeJson(File(output, "manifest.json"), linkedMapOf("schemaVersion" to 1, "engine" to "CREASE",
            "kind" to "touch-instruments", "title" to "CREASE touch studies", "sourceCommit" to commit,
            "workingTreeDirty" to false, "generatedAt" to Instant.now().toString(),
            "touchRenderVersion" to CreaseTouchKeys.RENDER_VERSION, "sampleRate" to RATE,
            "player" to "InstrumentEngine JVM reference", "instruments" to instruments, "clips" to clips,
            "summary" to summary, "metrics" to "raw-metrics.json", "limitations" to listOf(
                "Three sampled excitation strengths; no realtime synthesis or aftertouch.",
                "Note-off fades the continuing baked trajectory over 0.6 seconds; it does not simulate a new deformation release.",
                "MPC/desktop ZIPs must all be extracted together. Phone creation uses MAKE INSTRUMENT in the build; Shelf Share/Open does not import these keyboard packages.",
                "JVM playback and package data are measured. Phone/MPC hardware playback and listening judgments remain unverified.",
                "Historical single-layer builders, finite notes and held recipes retain their original audio.",
            )))
        File(output, "index.html").writeText(PAGE)
        val sources = git(repository, "ls-files").lines().filter {
            it.endsWith(".kt") && (it.contains("/src/main/") || it.endsWith("/CreaseTouchAuditionGenerator.kt")) || it == "shell/build.gradle.kts"
        }.sorted().associateWith { sha(File(repository, it)) }
        writeJson(File(output, "source-provenance.json"), linkedMapOf("sourceCommit" to commit, "sha256" to sources))
        require(output.walkTopDown().filter { it.isFile }.all { it.length() < ASSET_LIMIT }) { "An asset exceeds 25 MiB" }
        require(git(repository, "rev-parse", "HEAD") == commit && git(repository, "status", "--porcelain").isEmpty()) {
            "Source changed during rendering; regenerate from a clean commit"
        }
        println("Wrote ${rawRows.size} layer samples, ${clips.size} performances, two packages and a PINCH root study under $output")
    }

    private fun probeEvidence(id: String, midi: Int, index: Int, layer: CreaseTouchKeys.Layer,
        sharedGain: Float): Map<String, Any?> {
        val p = layer.probe
        val note = p.note
        val x = note.snip.samples
        val start = p.prefixFrames
        val loopFrames = x.size - start
        require(note.snip.channels == 1 && note.snip.sampleRate == RATE && x.all { it.isFinite() } && note.snip.peak() < 1f)
        require(start >= loopFrames && p.previousCycle.size == loopFrames && p.prelevel.size == x.size)
        val priorError = p.previousCycle.indices.maxOf { abs(p.previousCycle[it] - x[start - loopFrames + it]).toDouble() }
        require(priorError <= 2e-7) { "$id/$midi/$index preceding cycle mismatch $priorError" }
        require(p.loopStateStart.isNotEmpty() && p.loopStateStart.size == p.loopStateEnd.size)
        val state = p.loopStateStart.indices.maxOf { abs(p.loopStateStart[it] - p.loopStateEnd[it]) }
        require(p.diagnostics.converged && state <= Crease.LOOP_STATE_TOLERANCE)
        val nativeSeam = Keys.seamError(x, start)
        val pcm = WavReader.read(encode(note.snip)).samples
        val pcmSeam = Keys.seamError(pcm, start)
        require(nativeSeam.isFinite() && pcmSeam.isFinite() && nativeSeam < Keys.MAX_SEAM_ERROR && pcmSeam < Keys.MAX_SEAM_ERROR)
        val independentCycle = p.previousCycle.indices.sumOf {
            val d = p.previousCycle[it] - x[start + it].toDouble(); d * d
        } / p.previousCycle.sumOf { it.toDouble() * it }.coerceAtLeast(1e-30)
        val loop = Snip(pcm.copyOfRange(start, pcm.size), 1, RATE)
        val pitch = Pitch.detect(loop, fromSec = .1f)
        val d = p.diagnostics
        return linkedMapOf("instrument" to id, "midi" to midi, "layer" to index, "touch" to layer.touch,
            "velocityWindow" to listOf(layer.velocityWindow.first, layer.velocityWindow.last),
            "excitationVelocity" to layer.excitationVelocity, "levelGain" to layer.levelGain,
            "sharedHeadroomGain" to sharedGain, "loopStartFrame" to start, "loopFrames" to loopFrames,
            "nativeFloat32Sha256" to floatHash(x), "prelevelFloat32Sha256" to floatHash(p.prelevel),
            "metrics" to (audioMetrics(note.snip) + linkedMapOf(
                "prefixSeconds" to p.prefixSeconds, "transitionEndSeconds" to p.transitionEndSeconds,
                "nativeSeamError" to nativeSeam, "pcm24SeamError" to pcmSeam,
                "independentStateError" to state, "independentCycleRelativeError" to independentCycle,
                "precedingPrefixMaxError" to priorError, "converged" to d.converged,
                "renderSeconds" to d.renderNanos / 1e9, "prerollCycles" to d.prerollCycles,
                "requestedHz" to d.requestedHz, "renderedHz" to d.renderedHz,
                "pitchHz" to pitch?.hz, "pitchConfidence" to pitch?.confidence,
                "pitchCents" to pitch?.let { 1200 * ln(it.hz / Scales.midiToHz(midi).toDouble()) / ln(2.0) },
                "rawMin" to p.prelevel.minOrNull(), "rawMax" to p.prelevel.maxOrNull(),
                "rawPeak" to d.rawPeak, "rawRms" to d.rawRms, "rawDc" to d.rawDc,
                "minMapDerivative" to d.minMapDerivative, "maxMapDerivative" to d.maxMapDerivative,
                "maxAbsState" to d.maxAbsState)),
            "loopStateStart" to p.loopStateStart.toList(), "loopStateEnd" to p.loopStateEnd.toList(),
            "onsetHarmonicProfiles" to ONSET_WINDOWS.map { time -> mapOf("time" to time,
                "powers" to profile(x, d.renderedHz, frames(time)).toList()) }, "trace" to trace(p))
    }

    private fun reloadAndVerify(bundle: File, name: String, midis: List<Int>, notes: List<List<KeyNote>>,
        evidence: List<Map<String, Any?>>, output: File): InstrumentEngine.Loaded {
        val instrument = InstrumentStore.read(InstrumentStore.sidecar(bundle, name))
        require(instrument.release == RELEASE && instrument.zones.map { it.rootNote } == midis)
        val samples = LinkedHashMap<String, FloatArray>()
        instrument.zones.forEachIndexed { zoneIndex, zone ->
            require(zone.playableLayers.size == 3)
            val rows = evidence[zoneIndex].getValue("layers") as List<*>
            zone.playableLayers.forEachIndexed { layerIndex, layer ->
                require(layer.velStart..layer.velEnd == CreaseTouchKeys.VELOCITY_WINDOWS[layerIndex])
                val file = File(bundle, layer.sample)
                val saved = WavReader.read(file)
                val note = notes[zoneIndex][layerIndex]
                require(file.readBytes().contentEquals(encode(note.snip,
                    SmplChunk(zone.rootNote, SmplChunk.Loop(note.loopStartFrame, note.snip.frameCount.toLong())))))
                require(layer.loopStartFrame == note.loopStartFrame && layer.frameCount == note.snip.frameCount.toLong())
                require(WavReader.readSmpl(file) == SmplChunk(zone.rootNote, SmplChunk.Loop(layer.loopStartFrame, layer.frameCount)))
                require(Keys.seamError(saved.samples, layer.loopStartFrame.toInt()) < Keys.MAX_SEAM_ERROR)
                @Suppress("UNCHECKED_CAST")
                (rows[layerIndex] as MutableMap<String, Any?>).apply {
                    put("audio", relative(output, file)); put("pcm24Sha256", sha(file))
                }
                samples[layer.sample] = saved.samples
            }
        }
        return InstrumentEngine.Loaded(instrument, RATE, samples)
    }

    private fun rootStudy(bundle: File, name: String, midi: Int, bank: CreaseTouchKeys.Bank,
        output: File, evidence: List<Map<String, Any?>>): InstrumentEngine.Loaded {
        val samples = LinkedHashMap<String, FloatArray>()
        val layers = bank.layers.mapIndexed { index, layer ->
            val file = File(bundle, "root-layer-${index + 1}.wav")
            val note = layer.probe.note
            WavWriter.write(file, note.snip, smpl = SmplChunk(midi,
                SmplChunk.Loop(note.loopStartFrame, note.snip.frameCount.toLong())))
            samples[file.name] = WavReader.read(file).samples
            @Suppress("UNCHECKED_CAST")
            val row = (evidence.single().getValue("layers") as List<*>)[index] as MutableMap<String, Any?>
            row["audio"] = relative(output, file); row["pcm24Sha256"] = sha(file)
            InstrumentStore.Layer(file.name, note.snip.frameCount.toLong(), note.loopStartFrame,
                layer.velocityWindow.first, layer.velocityWindow.last)
        }
        val hard = layers.last()
        val instrument = InstrumentStore.Instrument(name, RELEASE, listOf(InstrumentStore.Zone(midi, midi, midi,
            hard.sample, hard.frameCount, hard.loopStartFrame, layers)))
        return InstrumentEngine.Loaded(instrument, RATE, samples)
    }

    private fun monoStudy(name: String, midi: Int, note: KeyNote): InstrumentEngine.Loaded {
        val pcm = WavReader.read(encode(note.snip)).samples
        val zone = InstrumentStore.Zone(midi, midi, midi, "single-layer.wav", pcm.size.toLong(), note.loopStartFrame)
        return InstrumentEngine.Loaded(InstrumentStore.Instrument(name, RELEASE, listOf(zone)), RATE, mapOf(zone.sample to pcm))
    }

    /** Decode the files themselves; the two writers may order their layer slots differently. */
    private fun verifyMpc(bundle: File, name: String, instrument: InstrumentStore.Instrument): Map<String, Any?> {
        val data = File(bundle, Mpc3TrackWriter.trackDataDirName(name))
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(data, "$name.xpm"))
        val nodes = xml.getElementsByTagName("Layer")
        val xmlLayers = (0 until nodes.length).map { nodes.item(it) as Element }.filter {
            it.getElementsByTagName("SampleName").item(0).textContent.isNotEmpty()
        }.associateBy { it.getElementsByTagName("SampleName").item(0).textContent }
        val native = Json.parse(Acvs.read(File(bundle, "$name.xty")).payloadText)
        val nativeLayers = objects(native).filter {
            "sliceInfo" in it && it["sampleName"]?.str()?.isNotEmpty() == true
        }.associateBy { it.getValue("sampleName").str() }
        val expected = instrument.zones.sumOf { it.playableLayers.size }
        require(xmlLayers.size == expected && nativeLayers.size == expected)
        for (zone in instrument.zones) for (layer in zone.playableLayers) {
            val stem = File(layer.sample).nameWithoutExtension
            val element = xmlLayers.getValue(stem)
            fun field(name: String) = element.getElementsByTagName(name).item(0).textContent
            require(field("VelStart").toInt() == layer.velStart && field("VelEnd").toInt() == layer.velEnd)
            require(field("RootNote").toInt() == zone.rootNote && field("SliceLoopStart").toLong() == layer.loopStartFrame)
            require(field("SliceEnd").toLong() == layer.frameCount && field("SliceLoop").toInt() == 1)
            val actual = nativeLayers.getValue(stem)
            require(actual.getValue("velocityStart").int() == layer.velStart && actual.getValue("velocityEnd").int() == layer.velEnd)
            require(actual.getValue("rootNote").int() == zone.rootNote)
            val slice = actual.getValue("sliceInfo").obj()
            require(slice.getValue("LoopStart").long() == layer.loopStartFrame && slice.getValue("End").long() == layer.frameCount)
            require(slice.getValue("LoopMode").int() == 1)
        }
        return linkedMapOf("nativeLayerCount" to nativeLayers.size, "compatibilityLayerCount" to xmlLayers.size,
            "allRootsWindowsLoopStartsAndEndsAgree" to true)
    }

    private fun objects(value: JsonValue): Sequence<Map<String, JsonValue>> = sequence {
        when (value) {
            is JsonValue.Obj -> { yield(value.entries); value.entries.values.forEach { yieldAll(objects(it)) } }
            is JsonValue.Arr -> value.items.forEach { yieldAll(objects(it)) }
            else -> Unit
        }
    }

    private fun single(loaded: InstrumentEngine.Loaded, midi: Int, velocity: Int, held: Double): Rendered =
        play(loaded, listOf(Event(0, midi, velocity), Event(frames(held), midi, null)), frames(held + RELEASE + .2))

    private fun play(loaded: InstrumentEngine.Loaded, events: List<Event>, totalFrames: Int): Rendered {
        val player = InstrumentEngine(loaded, RATE)
        val out = FloatArray(totalFrames * 2)
        val block = FloatArray(1024)
        var at = 0
        fun until(end: Int) {
            require(end in at..totalFrames)
            while (at < end) {
                val count = minOf(512, end - at)
                player.render(block, count); block.copyInto(out, at * 2, 0, count * 2); at += count
            }
        }
        for (event in events.sortedBy { it.frame }) {
            until(event.frame)
            if (event.velocity == null) player.noteOff(event.midi)
            else require(player.noteOn(event.midi, event.velocity / 127f))
        }
        until(totalFrames)
        require(player.activeNotes.isEmpty() && out.takeLast(RATE / 25).all { it == 0f })
        require(out.all { it.isFinite() } && out.maxOf { abs(it) } < .9f) { "Reference playback reached its nonlinear bus stage" }
        return Rendered(Snip(out, 2, RATE), events)
    }

    private fun writeClip(output: File, id: String, title: String, instrument: String, section: String,
        take: Rendered, description: String, extra: Map<String, Any?> = emptyMap(), gain: Double = 1.0): Map<String, Any?> {
        val audio = if (gain == 1.0) take.snip else Snip(FloatArray(take.snip.samples.size) {
            (take.snip.samples[it] * gain).toFloat()
        }, 2, RATE)
        require(gain.isFinite() && audio.peak() < .9f)
        val file = File(output, "audio/$id.wav")
        WavWriter.write(file, audio, WavWriter.BitDepth.PCM_16)
        return linkedMapOf<String, Any?>("id" to id, "title" to title, "instrument" to instrument,
            "section" to section, "audio" to relative(output, file), "midi" to 60,
            "matched" to false, "description" to description, "durationSeconds" to audio.durationSeconds,
            "noteOffSeconds" to take.events.firstOrNull { it.velocity == null }?.frame?.toDouble()?.div(RATE),
            "events" to take.events.map { mapOf("time" to it.frame.toDouble() / RATE, "midi" to it.midi,
                "action" to if (it.velocity == null) "off" else "on", "midiVelocity" to it.velocity) },
            "metrics" to (audioMetrics(audio) + mapOf("scalarGain" to gain, "activeNotesAtEnd" to 0, "tail20msRms" to 0.0)),
            "sha256" to sha(file), "nativeFloat32Sha256" to floatHash(audio.samples)).apply { putAll(extra) }
    }

    /** First 16 harmonic powers, measured by a 100 ms Hann-windowed projection.
     * Normalizing powers removes scalar gain and oscillator phase from this diagnostic. */
    private fun profile(samples: FloatArray, hz: Double, from: Int): DoubleArray {
        val n = minOf(RATE / 10, samples.size - from)
        require(n > 1024)
        val powers = DoubleArray(16)
        val mean = (from until from + n).sumOf { samples[it].toDouble() } / n
        val windowed = DoubleArray(n) { (samples[from + it] - mean) * (.5 - .5 * cos(2 * PI * it / (n - 1))) }
        for (h in powers.indices) {
            val angle = 2 * PI * hz * (h + 1) / RATE
            val cr = cos(angle); val ci = sin(angle)
            var re = 1.0; var im = 0.0; var a = 0.0; var b = 0.0
            for (sample in windowed) {
                a += sample * re; b += sample * im
                val next = re * cr - im * ci; im = re * ci + im * cr; re = next
            }
            powers[h] = a * a + b * b
        }
        val total = powers.sum().coerceAtLeast(1e-30)
        return DoubleArray(powers.size) { powers[it] / total }
    }

    private fun contrast(bank: CreaseTouchKeys.Bank): List<Map<String, Any?>> = listOf(0 to 1, 1 to 2, 0 to 2).map { (a, b) ->
        val x = bank.layers[a].probe; val y = bank.layers[b].probe
        fun tv(first: DoubleArray, second: DoubleArray) = first.indices.sumOf { abs(first[it] - second[it]) } / 2
        val onset = ONSET_WINDOWS.map { tv(profile(x.note.snip.samples, x.diagnostics.renderedHz, frames(it)),
            profile(y.note.snip.samples, y.diagnostics.renderedHz, frames(it))) }
        val held = listOf(.05, .25, .45, .65).map { fraction ->
            tv(profile(x.note.snip.samples, x.diagnostics.renderedHz,
                x.prefixFrames + ((x.note.snip.frameCount - x.prefixFrames) * fraction).toInt()),
                profile(y.note.snip.samples, y.diagnostics.renderedHz,
                    y.prefixFrames + ((y.note.snip.frameCount - y.prefixFrames) * fraction).toInt()))
        }
        linkedMapOf("layers" to listOf(a, b), "touches" to listOf(bank.layers[a].touch, bank.layers[b].touch),
            "onsetMeanTv" to onset.average(), "onsetMaxTv" to onset.max(),
            "heldMeanTv" to held.average(), "heldMaxTv" to held.max())
    }

    private fun trace(probe: CreaseKeyProbe): List<Map<String, Any?>> {
        val t = probe.trace; val count = minOf(128, t.timeSeconds.size)
        return (0 until count).map { n ->
            val i = if (count == 1) 0 else n * (t.timeSeconds.size - 1) / (count - 1)
            mapOf("time" to t.timeSeconds[i], "pressure" to t.pressure[i], "deformationA" to t.deformationA[i],
                "deformationB" to t.deformationB[i], "loadA" to t.loadA[i], "loadB" to t.loadB[i])
        }
    }

    private fun archives(output: File, bundle: File, id: String, name: String): List<Map<String, Any?>> {
        val files = bundle.walkTopDown().filter { it.isFile }.sortedBy { relative(bundle, it) }.toList()
        val metadata = files.filterNot { it.extension.equals("wav", true) }
        val groups = ArrayList<List<File>>()
        var current = metadata.toMutableList(); var size = current.sumOf { it.length() }
        require(size < PART_BUDGET)
        for (file in files.filter { it.extension.equals("wav", true) }) {
            require(file.length() < PART_BUDGET)
            if (size + file.length() >= PART_BUDGET) { groups += current; current = ArrayList(); size = 0L }
            current += file; size += file.length()
        }
        if (current.isNotEmpty()) groups += current
        val expected = files.map { "${bundle.name}/${relative(bundle, it)}" }.toSet()
        val covered = ArrayList<String>()
        val retained = ArrayList<File>()
        val result = groups.mapIndexed { index, group ->
            val suffix = if (groups.size == 1) "" else "-part-${index + 1}"
            val archive = File(output, "downloads/$id-touch$suffix.zip")
            retained += archive.canonicalFile
            ZipOutputStream(archive.outputStream().buffered()).use { out ->
                out.setLevel(Deflater.BEST_COMPRESSION)
                group.sortedBy { relative(bundle, it) }.forEach { file ->
                    out.putNextEntry(ZipEntry("${bundle.name}/${relative(bundle, file)}").apply { time = 0 })
                    file.inputStream().use { it.copyTo(out) }; out.closeEntry()
                }
            }
            val entries = ZipFile(archive).use { zip -> zip.entries().asSequence().map { entry ->
                val file = File(bundle.parentFile, entry.name)
                require(file.canonicalFile.toPath().startsWith(bundle.canonicalFile.toPath()))
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                require(sha(bytes) == sha(file) && CRC32().apply { update(bytes) }.value == entry.crc)
                entry.name
            }.toList() }
            covered += entries
            require(archive.length() < ASSET_LIMIT)
            linkedMapOf("file" to relative(output, archive),
                "label" to if (groups.size == 1) "$name instrument ZIP" else "$name: Part ${index + 1} of ${groups.size}",
                "part" to index + 1, "parts" to groups.size, "extractTogether" to (groups.size > 1),
                "bytes" to archive.length(), "sha256" to sha(archive), "entries" to entries,
                "zipCrcVerified" to true, "zipRoundTripByteExact" to true, "packageFilesCoveredExactlyOnce" to true)
        }
        require(covered.size == expected.size && covered.toSet() == expected)
        File(output, "downloads").listFiles().orEmpty().filter {
            it.name.matches(Regex("${Regex.escape(id)}-touch(-part-[0-9]+)?\\.zip")) && it.canonicalFile !in retained
        }.forEach { require(it.delete()) }
        return result
    }

    private fun readme(name: String, commit: String, full: Boolean, midis: List<Int>) = """
        $name. Source $commit. Touch-render revision ${CreaseTouchKeys.RENDER_VERSION}.
        ${if (full) "Complete instrument package." else "Root-only study; not a distributed instrument package."}
        Saved patch root, velocity, model and six controls are retained in source.patch.json.
        Touch layers render excitation at saved velocity times .35, .65 and 1.
        MIDI windows 1..42, 43..84 and 85..127 choose those samples. The player also
        applies its ordinary velocity gain. Sustain targets are equal before that
        gain, with one shared headroom scalar per root protecting all attacks.
        Roots: ${midis.joinToString()}. Key-up fades for $RELEASE seconds.

        Download every part and extract all ordinary ZIPs into the SAME directory.
        Metadata is in part 1; each WAV occurs exactly once. Keep the folder intact.
        MPC 3 loads the .xty; MPC 2 loads the .xpm inside _[TrackData]. These are
        MPC/desktop archives. On the phone use MAKE INSTRUMENT in the build.
        Shelf Share/Open does not import these keyboard packages. The instrument
        sidecar is included as the generated phone-shelf source, not an import claim.

        This is sampled touch, not realtime synthesis or aftertouch. Note-off fades
        the baked trajectory, rather than simulating a new deformation release.
        Package data and JVM reference playback are checked. Phone/MPC hardware
        playback and listening judgments have not been verified.
    """.trimIndent() + "\n"

    private fun matchedTarget(takes: List<Snip>) = minOf(.07, takes.minOf { rms(it.samples) * .85 / it.peak() })
    private fun rms(x: FloatArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size)
    private fun db(ratio: Double) = 20 * ln(ratio.coerceAtLeast(1e-30)) / ln(10.0)
    private fun audioMetrics(audio: Snip): Map<String, Any?> = linkedMapOf("durationSeconds" to audio.durationSeconds,
        "frames" to audio.frameCount, "channels" to audio.channels, "peak" to audio.peak(), "rms" to rms(audio.samples),
        "dc" to audio.samples.sumOf { it.toDouble() } / audio.samples.size)
    private fun encode(audio: Snip, smpl: SmplChunk? = null): ByteArray = ByteArrayOutputStream().also {
        WavWriter.write(it, audio, WavWriter.BitDepth.PCM_24, smpl = smpl)
    }.toByteArray()
    private fun frames(seconds: Double) = (seconds * RATE).roundToInt()
    private fun relative(root: File, file: File) = file.relativeTo(root).invariantSeparatorsPath
    private fun floatHash(x: FloatArray) = sha(ByteBuffer.allocate(x.size * 4).order(ByteOrder.LITTLE_ENDIAN).also {
        for (sample in x) it.putFloat(sample)
    }.array())
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it) }
    private fun sha(file: File): String = file.inputStream().buffered().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(65536)
        while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
        digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }
    private fun writeJson(file: File, value: Any?) = file.writeText(Json.write(json(value)) + "\n")
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associateTo(LinkedHashMap()) { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported JSON metric ${value.javaClass.name}")
    }
    private fun repositoryRoot(): File = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, ".git").exists() }
    private fun git(repository: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git", "-C", repository.path) + args).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().use { it.readText() }
        require(p.waitFor() == 0) { text }; return text.trim()
    }

    private val PAGE = """
<!doctype html>
<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>CREASE / touch studies</title>
<style>
:root{color-scheme:dark;font:16px/1.5 system-ui,sans-serif;background:#111b1c;color:#eaf1eb}*{box-sizing:border-box}
body{margin:0}main{max-width:1120px;margin:auto;padding:36px 24px 80px}a{color:#b9e7ca}h1{font-size:clamp(36px,7vw,72px);line-height:1.05;letter-spacing:-.05em;margin:12px 0 20px}h2{font-size:30px;margin:0}h3{margin:0 0 12px;font-size:18px}.eyebrow{font:12px ui-monospace,monospace;letter-spacing:.14em;color:#a8c4b3}.intro{max-width:780px;color:#bfd0c6}section{margin-top:36px;border-top:1px solid #3b514b;padding-top:28px}.grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px}.clip{padding:18px;background:#1b2a28;border:1px solid #324a41;border-radius:12px}.clip p{font-size:13px;color:#bfd0c6}.clip small{display:block;color:#a9bdaf;font:12px ui-monospace,monospace}.clip audio{width:100%;margin-top:14px}.links{display:flex;flex-wrap:wrap;gap:10px;margin:18px 0}.links a{padding:10px 14px;border:1px solid #526e5e;border-radius:7px;text-decoration:none}.note{color:#b8cbbe;font-size:14px}.group{margin-top:24px}summary{cursor:pointer;padding:18px 0;font-weight:600}footer{margin-top:40px;color:#a3b8aa;font-size:13px}@media(max-width:760px){.grid{grid-template-columns:1fr}main{padding:28px 16px 60px}}
</style><main><div class="eyebrow">SNIPSNAP / CREASE / SAMPLED TOUCH</div><h1>Three ways to press.</h1>
<p class="intro">One saved sound, three independently rendered excitation strengths. Start with native levels, then compare the same takes at matched RMS. Adjacent-velocity probes expose the layer boundaries.</p>
<p class="note">This is sampled touch, without aftertouch. Note-off applies a 0.6 second fade to the continuing sample. JVM reference playback is measured; phone and MPC hardware playback remain unverified.</p>
<div class="links"><a href="manifest.json" download>Manifest</a><a href="raw-metrics.json" download>Measurements</a><a href="source-provenance.json" download>Source hashes</a></div><div id="status">Loading measurements…</div><div id="instruments"></div>
<footer id="footer"></footer></main><script>
const root=document.getElementById('instruments');
function text(tag,value,parent){const node=document.createElement(tag);node.textContent=value;parent.append(node);return node}
function group(parent,title,clips){const box=document.createElement('div');box.className='group';text('h3',title,box);const grid=document.createElement('div');grid.className='grid';box.append(grid);for(const c of clips){const card=document.createElement('article');card.className='clip';text('h3',c.title,card);text('p',c.description,card);text('small',Number(c.durationSeconds).toFixed(2)+' s · peak '+Number(c.metrics.peak).toFixed(3)+' · RMS '+Number(c.metrics.rms).toFixed(3),card);const audio=document.createElement('audio');audio.controls=true;audio.preload='none';audio.src=c.audio;audio.addEventListener('play',()=>document.querySelectorAll('audio').forEach(other=>{if(other!==audio)other.pause()}));card.append(audio);grid.append(card)}parent.append(box)}
fetch('manifest.json').then(r=>{if(!r.ok)throw Error('manifest unavailable');return r.json()}).then(m=>{
document.getElementById('status').textContent=m.summary.layerSampleCount+' measured layer samples · '+m.summary.clipCount+' performances · maximum PCM24 seam '+Number(m.summary.maxPcm24SeamError).toExponential(2);
for(const instrument of m.instruments){const section=document.createElement('section');text('div',instrument.voice+' / '+(instrument.rootStudy?'ROOT STUDY':'TWO-OCTAVE INSTRUMENT'),section).className='eyebrow';text('h2',instrument.name,section);text('p','Saved velocity '+instrument.savedVelocity+'. Excitation multipliers 0.35 / 0.65 / 1; live MIDI windows 1–42 / 43–84 / 85–127.',section).className='note';const links=document.createElement('div');links.className='links';for(const pack of instrument.packs){const a=document.createElement('a');a.href=pack.file;a.download='';a.textContent=pack.label+' ('+(pack.bytes/1048576).toFixed(1)+' MiB)';links.append(a)}section.append(links);if(instrument.packs.length)text('p','Download every part and extract all ordinary ZIPs into the same directory. MPC 3: .xty; MPC 2: .xpm in _[TrackData]. Phone creation uses MAKE INSTRUMENT in the build. Shelf Share/Open does not import these packages.',section).className='note';const clips=m.clips.filter(c=>c.instrument===instrument.id);group(section,'Native player levels',clips.filter(c=>c.section==='touch'));group(section,'RMS-matched touch comparison',clips.filter(c=>c.section==='matched'&&!c.reference));const details=document.createElement('details');text('summary','Boundaries, phrase, held loop and gain-only controls',details);for(const pair of [['thresholds','MIDI layer boundaries'],['phrase','Rising touch'],['held','Actual sustain wrap and key-up'],['gain-only','Earlier gain-only builder'],['matched','RMS-matched gain-only controls']]){const rows=clips.filter(c=>c.section===pair[0]&&(pair[0]!=='matched'||c.reference));if(rows.length)group(details,pair[1],rows)}section.append(details);root.append(section)}document.getElementById('footer').textContent='Source '+m.sourceCommit+'. Measurements use the production renderer and exported PCM24 samples. No listening or device claim.';
}).catch(error=>document.getElementById('status').textContent='Could not load audition: '+error.message);
</script></html>
""".trimIndent()
}
