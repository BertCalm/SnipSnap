package com.snipsnap.synth

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.OneNote
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import com.snipsnap.xpm.WavInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A hardware trial of the approved steady bodies, not a new saved OVERLAP model.
 *
 * A short ramp in the first period gives a sampler onset; the second period is
 * untouched and loops whole. Note-off uses the sampler's amp envelope, not the
 * prototype's physical withdrawal of drive and subsequent passive ringing.
 * OneNote writes both MPC generations and their WAV sampler sheets together.
 */
object OverlapHeldKeygroupGenerator {
    const val ARCHIVE_NAME = "overlap-held-keygroups.zip"
    const val RELEASE_VALUE = .6f
    const val VELOCITY = .8f
    const val PERIOD_SECONDS = 1.5
    const val ONSET_SECONDS = .020
    val ROOT_MIDIS: List<Int> = listOf(36, 48, 60)
    private const val RATE = WavWriter.MPC_SAMPLE_RATE
    private const val CEILING = .90

    data class Zone(val root: Int, val low: Int, val high: Int)
    private data class Rendered(val topology: OverlapTopology, val zone: Zone,
        val result: OverlapHeldPrototype.LoopResult)

    /** A bounded keyboard, with midpoint splits and at most six semitones of resampling. */
    fun zones(roots: List<Int> = ROOT_MIDIS): List<Zone> {
        require(roots.isNotEmpty() && roots == roots.distinct().sorted()) { "Roots must be distinct and ascending" }
        require(roots.all { it in ROOT_MIDIS }) { "This trial only samples MIDI 36, 48 and 60" }
        require(roots == ROOT_MIDIS || roots.size == 1) { "Use the full trial or one reference; do not widen the transposition spans" }
        return roots.mapIndexed { i, midi -> Zone(midi,
            if (i == 0) midi - 6 else (roots[i - 1] + midi) / 2 + 1,
            if (i == roots.lastIndex) midi + 6 else (midi + roots[i + 1]) / 2) }
    }

    fun controls(topology: OverlapTopology): Map<String, Float> = linkedMapOf(
        "KNOT" to if (topology == OverlapTopology.SELF) 0f else 1f,
        "PULSE" to 0f, "LOCK" to 0f, "GLOW" to 0f, "RING" to .6f, "HOLD" to 1f,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Use [dedicated-output-directory]" }
        val output = File(args.firstOrNull() ?: File(repository(), "testkit/overlap-held-keygroup").path)
        println("OVERLAP steady keygroups: ${generate(output).absolutePath}")
    }

    fun generate(output: File): File = generate(output, ROOT_MIDIS)

    /** A single-reference run lets the export test exercise every real topology at bounded cost. */
    internal fun generate(output: File, roots: List<Int>): File {
        val repo = repository()
        val destination = output.canonicalFile
        require(!Files.isSymbolicLink(output.absoluteFile.toPath())) { "Output must not be a symbolic link" }
        require(destination.parentFile != null && !repo.toPath().startsWith(destination.toPath()) && destination != File(repo, "synth")) {
            "Choose a dedicated output outside the repository's ancestor paths"
        }
        requireReplaceable(destination)
        check(destination.parentFile.isDirectory || destination.parentFile.mkdirs()) { "Cannot create output parent" }
        val work = Files.createTempDirectory(destination.parentFile.toPath(), ".overlap-held-keygroups-").toFile()
        try {
            val layout = zones(roots)
            val rendered = OverlapTopology.entries.flatMap { topology -> layout.map { zone ->
                val result = OverlapHeldPrototype.renderLoopMeasured(topology, controls(topology),
                    zone.root, VELOCITY, PERIOD_SECONDS)
                require(result.diagnostics.converged && result.diagnostics.maxLoopGain < 1.0)
                println("OVERLAP steady ${topology.name} MIDI ${zone.root}: ${result.diagnostics.periodFrames} frames")
                Rendered(topology, zone, result)
            } }
            val gain = min(1.0, CEILING / rendered.maxOf { it.result.snip.peak().toDouble() })
            require(gain.isFinite() && gain > 0.0)
            val programRows = mutableListOf<Map<String, Any?>>()
            val metricRows = mutableListOf<Map<String, Any?>>()
            for (topology in OverlapTopology.entries) {
                val notes = rendered.filter { it.topology == topology }
                val name = "Overlap Steady ${topology.name.lowercase().replaceFirstChar { it.uppercase() }}"
                val stems = notes.associateWith { "Overlap_${topology.name}_${it.zone.root}" }
                val samples = notes.associate { note -> stems.getValue(note) to sample(note.result, gain) }
                val program = KeygroupProgram(name, notes.map { note ->
                    val frames = note.result.diagnostics.periodFrames.toLong()
                    Keygroup(note.zone.low, note.zone.high, note.zone.root,
                        listOf(VelocityLayer(stems.getValue(note), 2 * frames, 0, 127, frames)))
                }, volumeRelease = RELEASE_VALUE)
                OneNote.writePackage(name, program, samples, work, overwrite = false)
                val data = File(work, "${name}_[TrackData]")
                val xpm = File(data, "$name.xpm")
                val xty = File(work, "$name.xty")
                verifyPrograms(program, xpm, xty, InstrumentStore.sidecar(work, name))
                val rows = notes.map { note ->
                    val stem = stems.getValue(note)
                    val wav = File(data, "$stem.wav")
                    val metrics = verifySample(note.result, note.zone.root, gain, wav)
                    val recipePath = "recipes/${topology.name.lowercase(Locale.ROOT)}-${note.zone.root}.json"
                    writeJson(File(work, recipePath), linkedMapOf(
                        "overlapHeldKeygroupZone" to 1, "prototype" to "OVERLAP steady held proof 1",
                        "topology" to topology.name, "referenceMidi" to note.zone.root, "velocity" to VELOCITY,
                        "macros" to controls(topology), "requestedPeriodSeconds" to PERIOD_SECONDS,
                        "collectionGain" to gain, "onsetRampSeconds" to ONSET_SECONDS,
                        "samplePeriods" to 2, "loopStartPeriod" to 1, "samplerReleaseValue" to RELEASE_VALUE,
                    ))
                    val d = note.result.diagnostics
                    linkedMapOf<String, Any?>(
                        "referenceMidi" to note.zone.root, "lowNote" to note.zone.low, "highNote" to note.zone.high,
                        "sample" to wav.relativeTo(work).invariantSeparatorsPath,
                        "wavSha256" to sha(wav), "recipe" to recipePath, "recipeSha256" to sha(File(work, recipePath)),
                        "frames" to 2 * d.periodFrames, "loopStartFrame" to d.periodFrames,
                        "loopEndExclusive" to 2 * d.periodFrames, "periodSeconds" to d.periodSeconds,
                        "referenceHz" to d.referenceHz, "sourceRootHz" to d.sourceRootHz,
                        "stateError" to d.stateError, "audioError" to d.audioError,
                        "attempts" to d.attempts, "maxAttempts" to d.maxAttempts,
                        "maxLoopGain" to d.maxLoopGain, "maxBufferAbs" to d.maxBufferAbs,
                        "metrics" to metrics,
                    ).also { metricRows += linkedMapOf("topology" to topology.name, "referenceMidi" to note.zone.root) + metrics }
                }
                programRows += linkedMapOf("name" to name, "topology" to topology.name,
                    "mpc2Program" to xpm.relativeTo(work).invariantSeparatorsPath,
                    "mpc3Track" to xty.relativeTo(work).invariantSeparatorsPath,
                    "instrumentSidecar" to InstrumentStore.sidecar(work, name).name,
                    "programSha256" to sha(xpm), "trackSha256" to sha(xty), "zones" to rows)
            }
            writeJson(File(work, "manifest.json"), linkedMapOf(
                "overlapHeldKeygroupTrial" to 1, "source" to source(repo), "programCount" to programRows.size,
                "sampleCount" to rendered.size, "referenceMidis" to roots,
                "sampleRate" to RATE, "channels" to 1, "bitsPerSample" to 24,
                "collectionGain" to gain, "onsetRampSeconds" to ONSET_SECONDS,
                "samplerReleaseValue" to RELEASE_VALUE, "programs" to programRows,
                "notes" to listOf(
                    "Steady prototype settings heard at MIDI 48; MIDI 36/60 and the sampler articulation still await listening and hardware checks. Accepted finite recipes are unchanged.",
                    "One ramped period precedes one untouched whole-period sustain loop; this sampler onset differs from physical drive buildup.",
                    "The sampler release envelope over a repeating body differs from the prototype's source-off passive tail. Value 0.6 is a raw program setting, not measured hardware seconds.",
                    "Reference roots label geometry, not guaranteed perceived pitch. Resampling between reference notes also changes texture timing.",
                    "PULSE and GLOW are off; LOCK is inactive. No tempo synchronization or new phone integration is provided.",
                    "Readback checks file agreement; physical MPC sustain, note-off and zone transitions require the included hardware checklist.",
                ),
            ))
            File(work, "metrics.csv").writeText(csv(metricRows))
            File(work, "README.txt").writeText(readme(layout))
            val files = work.walkTopDown().filter(File::isFile).sortedBy { it.relativeTo(work).invariantSeparatorsPath }.toList()
            File(work, "checksums.sha256").writeText(files.joinToString("\n", postfix = "\n") {
                "${sha(it)}  ${it.relativeTo(work).invariantSeparatorsPath}"
            })
            writeArchive(work, File(work, ARCHIVE_NAME))
            // Timings vary by workstation. Keep them outside the reproducible archive.
            writeJson(File(work, "runtime.json"), rendered.map { note ->
                val d = note.result.diagnostics
                linkedMapOf("topology" to note.topology.name, "referenceMidi" to note.zone.root,
                    "setupMillis" to d.setupMillis, "solveMillis" to d.solveMillis,
                    "conditioningMillis" to d.conditioningMillis, "totalMillis" to d.totalMillis)
            })
            requireReplaceable(destination)
            check(!destination.exists() || destination.deleteRecursively()) { "Cannot replace generated output $destination" }
            check(work.renameTo(destination)) { "Cannot move generated output to $destination" }
            return File(destination, ARCHIVE_NAME)
        } finally {
            work.deleteRecursively()
        }
    }

    /** Only this generator's marked directory may replace existing nonempty data. */
    private fun requireReplaceable(destination: File) {
        require(!destination.exists() || destination.isDirectory) { "Output is not a directory: $destination" }
        if (!destination.exists() || destination.listFiles()?.isEmpty() == true) return
        val marker = File(destination, "manifest.json")
        val owned = runCatching { Json.parse(marker.readText()).obj()["overlapHeldKeygroupTrial"]?.int() == 1 }.getOrDefault(false)
        require(owned) { "Refusing to replace nonempty output without an OVERLAP keygroup-trial manifest: $destination" }
    }

    internal fun sample(loop: OverlapHeldPrototype.LoopResult, gain: Double): Snip {
        val body = scaled(loop.snip.samples, gain)
        val samples = body + body
        val ramp = min((ONSET_SECONDS * RATE).toInt(), body.size)
        for (i in 0 until ramp) {
            val t = i.toDouble() / (ramp - 1)
            samples[i] = (samples[i] * t * t * (3.0 - 2.0 * t)).toFloat()
        }
        return Snip(samples, 1, RATE)
    }

    private fun verifySample(loop: OverlapHeldPrototype.LoopResult, midi: Int, gain: Double, file: File): Map<String, Any?> {
        val length = loop.diagnostics.periodFrames
        val info = WavInfo.read(file)
        check(info.sampleRate == RATE && info.channels == 1 && info.bitsPerSample == 24 && info.frameCount == 2L * length)
        val sheet = requireNotNull(WavReader.readSmplStrict(file))
        check(sheet == SmplChunk(midi, SmplChunk.Loop(length.toLong(), 2L * length)))
        val exported = WavReader.read(file).samples
        check(exported.all { it.isFinite() } && exported[0] == 0f && exported.maxOf { abs(it) } <= CEILING + 1e-6)
        val context = quantized(scaled(loop.seamContext, gain))
        val kept = context.copyOfRange(length, 2 * length)
        check(exported.copyOfRange(length, 2 * length).contentEquals(kept)) { "Export altered the approved loop body" }
        val before = Keys.seamError(context.copyOfRange(0, 2 * length), length)
        val after = Keys.seamError(context.copyOfRange(length, 3 * length), length)
        val onsetToLoop = Keys.seamError(exported, length)
        check(listOf(before, after, onsetToLoop).all { it.isFinite() && it < Keys.MAX_SEAM_ERROR }) {
            "PCM24 physical-neighbour or onset/body seam failed"
        }
        return linkedMapOf("peak" to exported.maxOf { abs(it) },
            "rms" to sqrt(exported.sumOf { it.toDouble() * it } / exported.size),
            "dc" to exported.sumOf { it.toDouble() } / exported.size,
            "pcm24PrecedingSeam" to before, "pcm24FollowingSeam" to after,
            "pcm24OnsetToLoopSeam" to onsetToLoop)
    }

    internal fun verifyPrograms(program: KeygroupProgram, xpm: File, xty: File, sidecar: File) {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val xml = factory.newDocumentBuilder().parse(xpm)
        val instruments = xml.getElementsByTagName("Instrument")
        check(instruments.length == program.keygroups.size)
        fun text(element: org.w3c.dom.Element, name: String) = element.getElementsByTagName(name).item(0).textContent.trim()
        val track = Mpc3Project.read(xty)
        val trackProgram = track.tracks.single().getValue("program").obj()
        check(trackProgram.getValue("type").int() == 1)
        val acvs = (trackProgram.getValue("drum").obj().getValue("instruments") as JsonValue.Arr).items
        val phone = InstrumentStore.read(sidecar)
        check(phone.name == program.name && phone.release == program.volumeRelease && phone.zones.size == program.keygroups.size)
        program.keygroups.forEachIndexed { index, zone ->
            val layer = zone.layers.single()
            val instrument = instruments.item(index) as org.w3c.dom.Element
            check(text(instrument, "LowNote").toInt() == zone.lowNote && text(instrument, "HighNote").toInt() == zone.highNote)
            check(text(instrument, "TriggerMode") == "2" && text(instrument, "VolumeAttack").toFloat() == 0f)
            check(text(instrument, "VolumeRelease").toFloat() == program.volumeRelease)
            val layers = instrument.getElementsByTagName("Layer")
            check(layers.length == 8)
            val filled = layers.item(0) as org.w3c.dom.Element
            check(text(filled, "Active") == "True" && text(filled, "SampleName") == layer.sampleName)
            check(text(filled, "RootNote").toInt() == zone.rootNote && text(filled, "KeyTrack") == "False")
            check(text(filled, "VelStart") == "0" && text(filled, "VelEnd") == "127")
            check(text(filled, "SliceStart") == "0" && text(filled, "SliceEnd").toLong() == layer.frameCount)
            check(text(filled, "SliceLoop") == "1" && text(filled, "SliceLoopStart").toLong() == layer.loopStartFrame)
            for (empty in 1 until 8) check(text(layers.item(empty) as org.w3c.dom.Element, "Active") == "False")
            val modern = acvs[index].obj()
            check(modern.getValue("triggerMode").int() == 2 && modern.getValue("lowNote").int() == zone.lowNote &&
                modern.getValue("highNote").int() == zone.highNote)
            val envelope = modern.getValue("synthSection").obj().getValue("ampEnvelope").obj()
            check(!envelope.getValue("OneShot").obj().getValue("value0").bool())
            check(envelope.getValue("Release").obj().getValue("value0").num().toFloat() == program.volumeRelease)
            check(envelope.getValue("Attack").obj().getValue("value0").num() == 0.0)
            val modernLayer = (modern.getValue("layersv") as JsonValue.Arr).items.first().obj()
            check(modernLayer.getValue("rootNote").int() == zone.rootNote && modernLayer.getValue("sampleName").str() == layer.sampleName)
            check(modernLayer.getValue("velocityStart").int() == 0 && modernLayer.getValue("velocityEnd").int() == 127)
            val slice = modernLayer.getValue("sliceInfo").obj()
            check(slice.getValue("LoopMode").int() == 1 && slice.getValue("LoopStart").long() == layer.loopStartFrame &&
                slice.getValue("End").long() == layer.frameCount && slice.getValue("Start").long() == 0L)
            val stored = phone.zones[index]
            check(stored.lowNote == zone.lowNote && stored.highNote == zone.highNote && stored.rootNote == zone.rootNote &&
                stored.frameCount == layer.frameCount && stored.loopStartFrame == layer.loopStartFrame)
            check(File(sidecar.parentFile, stored.sample).isFile)
        }
    }

    internal fun writeArchive(directory: File, archive: File) {
        val files = directory.walkTopDown().filter { it.isFile && it != archive && it.name != "runtime.json" }
            .sortedBy { it.relativeTo(directory).invariantSeparatorsPath }.toList()
        ZipOutputStream(archive.outputStream().buffered()).use { zip -> files.forEach { file ->
            val entry = ZipEntry("Overlap Held Keygroups/${file.relativeTo(directory).invariantSeparatorsPath}")
            entry.time = 0L
            zip.putNextEntry(entry)
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        } }
    }

    private fun readme(layout: List<Zone>) = """
        OVERLAP steady held keygroup trial 1

        Four independently selectable instruments: Self, Trefoil, Mirror and Torus.
        MPC 3: load a .xty beside its matching _[TrackData] folder.
        MPC 2: load the .xpm inside that data folder, beside the WAVs.
        Keep the complete extracted folder together. Instrument sidecars describe
        the same samples, but this trial adds no phone picker or import workflow.

        Each program covers MIDI ${layout.first().low}..${layout.last().high} only:
        ${layout.joinToString("\n        ") { "reference MIDI ${it.root}: keys ${it.low}..${it.high}" }}
        Roots are geometry references; another resonance may sound strongest.
        Every reference is generated independently at velocity 0.8. Intermediate
        keys transpose their nearest zone and change its repeating texture speed.
        There is one velocity layer; soft/hard response is a hardware check.

        PCM24 mono 44.1 kHz. One collection-wide only-down safety gain, no audition
        matching, FX, crossfade, loop repair or changes to the approved renderer.
        The first period has a 20 ms sampler onset ramp; the second, unmodified
        period loops while a key is held. This is not the physical drive buildup.
        Program release is raw value 0.6, not measured hardware seconds. The MPC
        fades its repeating sample on release; this differs from the prototype's
        excitation switching off and its resonator ringing freely afterward.
        PULSE/GLOW are off, LOCK inactive. The accepted finite bank is unchanged.
        The owner heard the steady proof at MIDI 48. The new MIDI 36/60 registers
        and this sampler articulation still await listening and hardware checks.

        HARDWARE CHECKLIST
        1. Record MPC model, OS version, load path and keygroup selected.
        2. Hold each reference key for at least ten seconds: it must keep playing.
        3. Release it: note any stuck note, click, cut or unwanted loudness step.
        4. Tap and release before the onset ramp ends; check for clicks or a stuck note.
        5. Play a small chord, then retrigger quickly; note voice stealing or cutoffs.
           Try soft and hard velocity, and check that releasing the final key stops it.
        6. Play across every zone boundary and the keyboard's lowest/highest keys.
        7. Confirm keys outside the declared range are silent; check sample paths
           survive copying the complete folder to removable storage.
        8. Where supported, compare the .xty and .xpm, recording differences. File readback does
           not establish hardware sustain or release behavior.

        manifest.json preserves settings, source hashes, file hashes and measured
        recurrence/export seams. recipes/ holds experimental generation settings,
        not ordinary finite PadRecipes. metrics.csv repeats export measurements.
        checksums.sha256 covers the deterministic files except itself and the ZIP.
        runtime.json records workstation costs separately and is excluded from
        the ZIP and checksums so repeat exports can be byte-identical.
    """.trimIndent() + "\n"

    private fun scaled(samples: FloatArray, gain: Double) = FloatArray(samples.size) { (samples[it] * gain).toFloat() }
    private fun quantized(samples: FloatArray): FloatArray = ByteArrayOutputStream().let { bytes ->
        WavWriter.write(bytes, Snip(samples, 1, RATE), WavWriter.BitDepth.PCM_24)
        WavReader.read(bytes.toByteArray()).samples
    }
    private fun writeJson(file: File, value: Any?) {
        file.parentFile.mkdirs()
        file.writeText(Json.write(json(value)) + "\n")
    }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> { require(value.toDouble().isFinite()); JsonValue.Num(value.toDouble()) }
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported evidence value ${value::class}")
    }
    private fun csv(rows: List<Map<String, Any?>>): String {
        val columns = rows.flatMap { it.keys }.distinct()
        return columns.joinToString(",") + "\n" + rows.joinToString("\n", postfix = "\n") { row ->
            columns.joinToString(",") { row[it]?.toString() ?: "" }
        }
    }
    private fun repository(): File = generateSequence(File(".").canonicalFile) { it.parentFile }.firstOrNull {
        File(it, "synth/src/main/kotlin/com/snipsnap/synth/OverlapHeldPrototype.kt").isFile
    } ?: error("Run from the SnipSnap checkout or a module directory")
    private fun source(repo: File): Map<String, Any?> {
        val paths = listOf("synth/src/main/kotlin/com/snipsnap/synth/OverlapHeldPrototype.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/OverlapV2.kt", "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Tide.kt", "audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt", "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
            "xpm/src/main/kotlin/com/snipsnap/xpm/KeygroupWriter.kt", "mpc3/src/main/kotlin/com/snipsnap/mpc3/Mpc3TrackWriter.kt",
            "kit/src/main/kotlin/com/snipsnap/kit/OneNote.kt", "kit/src/main/kotlin/com/snipsnap/kit/InstrumentStore.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/OverlapHeldKeygroupGenerator.kt")
        return linkedMapOf("revision" to git(repo, "rev-parse", "HEAD"),
            "treeDirty" to git(repo, "status", "--porcelain").isNotEmpty(),
            "files" to paths.associateWith { sha(File(repo, it)) })
    }
    private fun git(repo: File, vararg args: String): String = ProcessBuilder(listOf("git", "-C", repo.path) + args)
        .redirectErrorStream(true).start().let { p ->
            val text = p.inputStream.bufferedReader().readText().trim()
            check(p.waitFor() == 0) { "Source provenance failed" }; text
        }
    internal fun sha(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(Locale.ROOT, it) }
}
