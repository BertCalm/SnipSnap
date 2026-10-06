package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Reproducible, dry SUTURE listening evidence. Generated files are local artifacts.
 * Run `./gradlew :synth:generateSutureAudition`, then open index.html directly.
 * `-PsutureFirstListen` selects six neutral voices and the twelve factory patches;
 * `-PsutureSections=DIAGNOSTICS,PASSIVE,HOLD` selects full-roster sections.
 *
 * RAW is band-limited/DC-cleaned audio before loudness targeting. MATCHED uses
 * [AuditionLevel] on that same render. Neither applies a rack or clips an unsafe
 * signal. The manifest records JVM cost/memory observations and mechanical
 * evidence. These measurements cannot approve the evolving-vessel identity:
 * the owner must listen to the supplied comparisons and settled HOLD buffers.
 */
object SutureAuditionGenerator {
    private val timbral = listOf("GAP", "STITCH", "CORD", "SEAM", "CAVITY")
    private val steps = listOf(0f, .25f, .5f, .75f, 1f)
    private val notes = listOf(0f to "C3", .5f to "C4", 1f to "C5")
    private val energies = listOf(.25f to "QUIET", .65f to "MEDIUM", 1f to "STRONG")
    private val descriptions = linkedMapOf(
        "VOICES" to "Every voice at C3/C4/C5 and quiet/medium/strong finite gesture energy, with its own timbral defaults.",
        "PRESETS" to "The twelve proposed factory patches. Names and material identities remain listening hypotheses.",
        "SWEEPS" to "Each of the five timbral controls at 0/.25/.5/.75/1 for every voice. Companions use MacroSpec.neutral; C4, strong gesture, HOLD off. STITCH cases include mechanical travel and closure traces.",
        "GRIDS" to "GAP × STITCH, STITCH × CORD, GAP × CAVITY, STITCH × SEAM and CORD × SEAM, each at 0/.5/1 with neutral companions.",
        "DIAGNOSTICS" to "Recorded mechanical trajectories, same-render plate/cord/eyelet/cavity/seam taps, and no-seam/no-drive/stopped-cord ablations. Taps retain their original gain.",
        "RESISTANCE" to "Identical actuator settings under quiet and strong gestures, with and without vibration resistance. Compare closure time, gap curves and actuator work before deciding what is audible.",
        "PASSIVE" to "Eight-second natural tails, drive stopped at .7 seconds, and seeded passive decay with both gesture and stitcher disabled. Unpowered stationary cords cannot create sustained friction work.",
        "EDGES" to "All five timbral controls high on every voice, high-register chirps, broad slow closure, hard near-closed contact and low-GAP cord activity.",
        "HOLD" to "Settled powered loop-only buffers for all voices at quiet/medium/strong gesture energy, plus difficult rough/high/deep cases. Initial spread/release is absent in the host's loop-buffer format; listen over several wraps.",
    )

    private data class Case(
        val section: String, val group: String, val id: String, val label: String,
        val description: String, val voice: SutureVoice, val macros: Map<String, Float>,
        val velocity: Float = 1f, val record: Boolean = false, val drive: Boolean = true,
        val gesture: Boolean = true, val cordMotion: Boolean = true, val seam: Boolean = true,
        val resistance: Boolean = true, val stopSeconds: Float? = null,
        val durationSeconds: Float? = null, val initialEnergy: Double = 0.0,
        val loop: Boolean = false, val taps: Boolean = false,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/suture-audition")
        require(root.mkdirs() || root.isDirectory) { "cannot create ${root.absolutePath}" }
        val source = listOf(File("src/main/kotlin/com/snipsnap/synth/Suture.kt"),
            File("synth/src/main/kotlin/com/snipsnap/synth/Suture.kt")).firstOrNull { it.isFile }
            ?: error("run SUTURE auditions from the repository root or synth directory so DSP source provenance is available")
        val dspSourceHash = sourceHash(source)
        val selected = args.drop(1).firstOrNull { it.startsWith("--sections=") }
            ?.substringAfter('=')?.split(',')?.map { it.trim().uppercase(Locale.ROOT) }?.toSet()
        require(selected == null || selected.isNotEmpty() && selected.all { it in descriptions }) {
            "--sections must name one or more of ${descriptions.keys.joinToString()}"
        }
        val firstListen = "--first-listen" in args.drop(1)
        val cases = (if (firstListen) firstListenCases() else cases()).filter { selected == null || it.section in selected }
        require(cases.isNotEmpty()) { "selection contains no cases" }
        val sections = descriptions.mapValues { mutableListOf<Map<String, Any?>>() }
        val heapPools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
        val runtime = Runtime.getRuntime()
        fun heapUsed() = runtime.totalMemory() - runtime.freeMemory()
        val started = System.nanoTime()
        var rendered = 0
        var wavs = 0

        fun writeCase(c: Case, samples: FloatArray, cost: Map<String, Any?>, extra: Map<String, Any?> = emptyMap()) {
            require(samples.isNotEmpty() && samples.all { it.isFinite() }) { "${c.id}: invalid raw audio" }
            // WavWriter clips out-of-range floats, so refuse rather than hiding instability.
            require(samples.all { abs(it) <= 1f }) { "${c.id}: raw output exceeds PCM range" }
            val raw = Snip(samples, channels = 1, sampleRate = Dsp.RATE)
            val matched = AuditionLevel.level(raw)
            val stem = "${c.section}/${c.id}"
            WavWriter.write(File(root, "${stem}_raw.wav"), raw, WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(root, "${stem}_matched.wav"), matched, WavWriter.BitDepth.PCM_24)
            wavs += 2
            sections.getValue(c.section) += linkedMapOf<String, Any?>(
                "id" to c.id, "group" to c.group, "label" to c.label, "description" to c.description,
                "voice" to c.voice.name, "macros" to c.macros, "velocity" to c.velocity,
                "raw" to "${stem}_raw.wav", "matched" to "${stem}_matched.wav",
                "rawMetrics" to metrics(raw), "matchedMetrics" to metrics(matched),
                "cost" to cost, "loop" to c.loop,
            ) + extra
        }

        for (c in cases) {
            val beforeBytes = heapUsed()
            heapPools.forEach { it.resetPeakUsage() }
            val began = System.nanoTime()
            fun cost(samples: FloatArray, retainedBytes: Long) = mapOf(
                "renderMs" to (System.nanoTime() - began) / 1e6,
                "heapBeforeBytes" to beforeBytes,
                "heapAfterRenderBytes" to heapUsed(),
                "sumHeapPoolPeakBytes" to heapPools.sumOf { max(0L, it.peakUsage.used) },
                "retainedSampleAndTraceArrayBytes" to retainedBytes,
                "renderedAudioSeconds" to samples.size.toDouble() / Dsp.RATE,
            )
            if (c.loop) {
                val loop = Suture.renderLoopMeasured(c.voice, c.macros, velocity = c.velocity, normalize = false)
                val measured = cost(loop.samples, loop.samples.size * 4L)
                writeCase(c, loop.samples, measured, mapOf("loopEvidence" to mapOf(
                    "seam" to finite(loop.seam), "stateError" to finite(loop.stateError),
                    "cycles" to loop.cycles, "converged" to loop.converged, "rawRms" to finite(loop.rawRms),
                    "groupErrors" to loop.groupErrors.mapValues { finite(it.value) },
                    "actuatorWorkPerCycle" to finite(loop.actuatorWorkPerCycle),
                    "acousticWorkPerCycle" to finite(loop.acousticWorkPerCycle),
                    "maxEnergy" to finite(loop.maxEnergy),
                    "metricsDefined" to (loop.seam.isFinite() && loop.stateError.isFinite()),
                    "audibleRaw" to (loop.rawRms.isFinite() && loop.rawRms > 1e-5),
                )))
            } else {
                val played = Suture.play(c.voice, c.macros, Suture.Probe(
                    record = c.record, drive = c.drive, gesture = c.gesture, cordMotion = c.cordMotion,
                    seam = c.seam, resistance = c.resistance, velocity = c.velocity,
                    stopSeconds = c.stopSeconds, durationSeconds = c.durationSeconds, initialEnergy = c.initialEnergy,
                ))
                val raw = Suture.finish(played.raw, normalize = false)
                val retainedBytes = arrayBytes(played) + raw.size * 4L
                val measured = cost(raw, retainedBytes)
                val extra = mutableMapOf<String, Any?>(
                    "probe" to mapOf("drive" to c.drive, "gesture" to c.gesture, "cordMotion" to c.cordMotion,
                        "seam" to c.seam, "resistance" to c.resistance, "stopSeconds" to c.stopSeconds,
                        "durationSeconds" to c.durationSeconds, "initialEnergy" to c.initialEnergy),
                    "maxAcousticEnergy" to played.maxEnergy, "recoveredStates" to played.recoveredStates,
                    "closureSeconds" to played.closureSeconds,
                )
                played.trace?.let { trace ->
                    val path = "${c.section}/${c.id}_trace.csv"
                    writeTrace(File(root, path), trace)
                    extra["traceCsv"] = path
                    extra["traceRateHz"] = trace.rate
                    extra["mechanics"] = mechanicalSummary(trace)
                }
                writeCase(c, raw, measured, extra)
                if (c.taps) {
                    listOf(
                        Triple("plates", "Bronze plates", played.plates),
                        Triple("cords", "Elastic cords", played.cords),
                        Triple("eyelets", "Wooden eyelets", played.eyelets),
                        Triple("cavity", "Changing cavity", played.cavity),
                        Triple("seam", "Seam contacts", played.seam),
                    ).forEach { (id, label, tap) ->
                        val signal = requireNotNull(tap) { "${c.id}: missing recorded $id tap" }
                        val tapCase = c.copy(id = "${c.id}_$id", group = "Same-render network taps", label = label,
                            description = "Isolated $label from ${c.label}; original branch gain is preserved. The branch belongs to the same coupled vessel.")
                        writeCase(tapCase, Suture.finish(signal, normalize = false), emptyMap(), mapOf("tapOf" to c.id))
                    }
                }
            }
            rendered++
            if (rendered % 20 == 0 || rendered == cases.size) println("SUTURE audition: $rendered/${cases.size} renders, $wavs WAVs")
        }

        val sectionDescriptions = if (firstListen) descriptions + ("VOICES" to
            "Six voices at C4 and strong gesture energy with all timbral controls at their declared neutral values.") else descriptions
        require(sourceHash(source) == dspSourceHash) { "Suture.kt changed during rendering; regenerate from a stable DSP snapshot" }
        val manifestData = linkedMapOf<String, Any?>(
            "engine" to "SUTURE", "modelVersion" to Suture.MODEL_VERSION,
            "dspSource" to "synth/src/main/kotlin/com/snipsnap/synth/Suture.kt", "dspSourceSha256" to dspSourceHash,
            "firstListen" to firstListen, "renderCount" to rendered,
            "caseCount" to sections.values.sumOf { it.size }, "wavCount" to wavs,
            "sampleRate" to Dsp.RATE, "internalSampleRate" to Dsp.RATE * Dsp.OVERSAMPLE, "bitDepth" to 24,
            "mechanicalControlStride" to Suture.CONTROL_STRIDE,
            "mechanicalControlRateHz" to (Dsp.RATE.toDouble() * Dsp.OVERSAMPLE / Suture.CONTROL_STRIDE),
            "jvm" to mapOf("runtimeVersion" to System.getProperty("java.runtime.version"),
                "maxHeapBytes" to runtime.maxMemory(), "availableProcessors" to runtime.availableProcessors(),
                "heapPools" to heapPools.map { it.name }),
            "elapsedSeconds" to (System.nanoTime() - started) / 1e9,
            "rawMeaning" to "Band-limited, DC-cleaned engine audio before melodic loudness targeting; original gain, no rack effects.",
            "matchedMeaning" to "Same raw render matched using AuditionLevel's shared loudest-200ms RMS target and peak guard.",
            "memoryMeaning" to "Heap readings observe this JVM and include other live objects; summed pool peaks are an upper bound, not simultaneous process RSS. Retained bytes count primitive sample/trace arrays only, excluding array headers and transient DSP state. No forced GC is performed.",
            "costMeaning" to "Per-case wall time covers engine rendering and raw finishing, excluding WAV/CSV/HTML export. JVM warmup and GC can affect the observations; loop cost includes bounded preroll.",
            "workMeaning" to "Total acoustic input includes finite note-release energy plus actuator transfers. Gesture and actuator acoustic work are recorded separately; friction work is the actual finite-work debit. Stored mechanical work is the total remaining gesture/actuator reserve, including pending transfer work reported separately. All work/loss ledgers are cumulative except the remaining and pending reserves.",
            "loopWorkMeaning" to "Per-cycle mechanical actuator and acoustic input work are increments over the final captured actuator cycle. Loop maxEnergy is the maximum acoustic energy across the entire bounded preroll, including earlier cycles.",
            "sonicApproval" to "Pending owner's listening verdict; mechanics, pitch and seam metrics do not establish sonic identity.",
            "sections" to sectionDescriptions.filterKeys { sections.getValue(it).isNotEmpty() }.map { (id, description) ->
                mapOf("id" to id, "description" to description, "cases" to sections.getValue(id))
            },
        )
        val manifest = Json.write(value(manifestData)) + "\n"
        File(root, "manifest.json").writeText(manifest)
        File(root, "index.html").writeText(page(manifest))
        val key = mapOf("engine" to "SUTURE", "modelVersion" to Suture.MODEL_VERSION,
            "dspSourceSha256" to dspSourceHash, "firstListen" to firstListen,
            "reviewStatus" to "Owner listening required",
            "cases" to sections.values.flatten().map { c -> c.filterKeys { it in setOf("id", "voice", "macros", "velocity", "probe", "loop", "tapOf") } })
        File(root, "key.json").writeText(Json.write(value(key)) + "\n")
        File(root, "README.txt").writeText(
            "SUTURE audition\nRegenerate: ./gradlew :synth:generateSutureAudition" +
                (if (firstListen) " -PsutureFirstListen" else "") +
                (selected?.let { " -PsutureSections=${it.joinToString(",")}" } ?: "") + "\n" +
                "$rendered renders, ${sections.values.sumOf { it.size }} listening cases, $wavs PCM-24 WAVs.\n" +
                "DSP model ${Suture.MODEL_VERSION}; Suture.kt SHA-256 $dspSourceHash.\n" +
                "Open index.html directly. RAW and MATCHED share the same dry engine render.\n" +
                "manifest.json includes raw/matched metrics, exact inputs, mechanical summaries, cost and memory evidence.\n" +
                "key.json maps listening IDs to voice, controls, gesture energy and diagnostic switches.\n" +
                "CSV traces preserve every recorded control tick: gaps, travel speed, tension, slips, seam activity, resistance, separate gesture/actuator work and energy/loss.\n" +
                "HOLD contains settled powered material, without the initial spread/release. Use Repeat across several wraps.\n" +
                "Listen for connected bronze bloom -> sliding cords -> resisted closure -> enclosed resonance -> seam murmur.\n" +
                "Owner sonic approval is pending. Save listening notes from the page; metrics cannot provide that verdict.\n",
        )
        println("wrote $wavs WAVs + manifest.json + key.json + README.txt + index.html under ${root.absolutePath}")
    }

    private fun neutral(voice: SutureVoice) = Suture.macrosFor(voice).associate { it.name to it.neutral } +
        mapOf("TUNE" to .5f, "HOLD" to 0f)

    private fun cases(): List<Case> = buildList {
        for (preset in SuturePresets.all()) {
            val held = Suture.isLoop(preset.macros["HOLD"] ?: 0f)
            add(Case("PRESETS", preset.voice.name, "preset_${preset.name.lowercase(Locale.ROOT).replace(' ', '_')}",
                preset.name, "${preset.voice.name}; ${line(preset.macros)}." +
                    if (held) " Settled loop-only material; initial release is absent." else "",
                preset.voice, preset.macros, loop = held))
        }
        for (voice in SutureVoice.entries) {
            for ((tune, note) in notes) for ((energy, name) in energies) {
                add(Case("VOICES", voice.name, "${tag(voice)}_${note.lowercase()}_${name.lowercase()}",
                    "$note $name", "${voice.name} defaults; finite gesture energy ${fmt(energy)}.", voice,
                    Suture.defaults(voice) + ("TUNE" to tune), velocity = energy))
            }
            for (macro in timbral) for (step in steps) {
                add(Case("SWEEPS", "${voice.name} / $macro", "${tag(voice)}_${macro.lowercase()}_${code(step)}",
                    "$macro ${fmt(step)}", "C4, strong gesture. Other timbral controls at neutral; HOLD off.",
                    voice, neutral(voice) + (macro to step), record = macro == "STITCH"))
            }
            add(Case("DIAGNOSTICS", "Default vessel trajectories", "${tag(voice)}_mechanics", voice.name,
                "Finite opening and powered closure. Download the shared-state trace; compare gap, tension, slips and seam activity.",
                voice, Suture.defaults(voice), record = true, taps = voice == SutureVoice.STRAIN))
            add(Case("EDGES", "All controls high", "${tag(voice)}_all_high", "${voice.name}: all high",
                "Five timbral controls at 1; C4, strong finite gesture, HOLD off.", voice,
                Suture.defaults(voice) + timbral.associateWith { 1f } + ("HOLD" to 0f), record = true))
            add(Case("PASSIVE", "Long natural tails", "${tag(voice)}_tail", "${voice.name}: eight-second tail",
                "One finite note followed by its natural passive ringdown; no repeated note or indefinite powered drive.",
                voice, Suture.defaults(voice), record = true, durationSeconds = 8f))
            add(Case("PASSIVE", "Stopped stitchers", "${tag(voice)}_stopped", "${voice.name}: stop at .7 s",
                "Same initial note, with powered work withdrawn at .7 seconds. The coupled acoustic and mechanical state continues.",
                voice, Suture.defaults(voice), record = true, stopSeconds = .7f, durationSeconds = 8f))
            add(Case("HOLD", "Every voice held", "${tag(voice)}_held", "${voice.name}: STRONG HOLD",
                "Strong gesture energy, settled powered cycle; use Repeat and listen for shape, event and wrap discontinuities.",
                voice, Suture.defaults(voice) + ("HOLD" to 1f), loop = true))
            for ((energy, name) in energies.filter { it.first < 1f }) {
                add(Case("HOLD", "Held velocity comparisons / ${voice.name}", "${tag(voice)}_held_${name.lowercase()}",
                    "${voice.name}: $name HOLD", "Same default powered cycle as ${voice.name}'s STRONG HOLD, with gesture energy ${fmt(energy)}. Compare RAW amplitude and per-cycle work before MATCHED timbre.",
                    voice, Suture.defaults(voice) + ("HOLD" to 1f), velocity = energy, loop = true))
            }
        }
        for ((a, b, voice) in listOf(
            Triple("GAP", "STITCH", SutureVoice.BLOOM), Triple("STITCH", "CORD", SutureVoice.THREAD),
            Triple("GAP", "CAVITY", SutureVoice.CLOSE), Triple("STITCH", "SEAM", SutureVoice.MURMUR),
            Triple("CORD", "SEAM", SutureVoice.STRAIN),
        )) for (x in listOf(0f, .5f, 1f)) for (y in listOf(0f, .5f, 1f)) {
            add(Case("GRIDS", "$a × $b / ${voice.name}", "${a.lowercase()}${code(x)}_${b.lowercase()}${code(y)}",
                "$a ${fmt(x)} · $b ${fmt(y)}", "${voice.name}, C4. Companions at neutral.",
                voice, neutral(voice) + (a to x) + (b to y), record = true))
        }
        val diagnostic = Suture.defaults(SutureVoice.STRAIN)
        add(Case("DIAGNOSTICS", "Causal comparisons", "strain_no_seam", "STRAIN: contact disabled",
            "Compare STRAIN's default mechanics clip. Geometry and cavity remain active while edge contact is disabled.",
            SutureVoice.STRAIN, diagnostic, record = true, seam = false))
        add(Case("DIAGNOSTICS", "Causal comparisons", "strain_seam_zero", "STRAIN: SEAM 0",
            "Product control's true off endpoint: compare the diagnostic contact-disabled case.",
            SutureVoice.STRAIN, diagnostic + ("SEAM" to 0f), record = true))
        add(Case("DIAGNOSTICS", "Causal comparisons", "strain_no_drive", "STRAIN: powered closure off",
            "Retains the finite initial release; powered closure is disabled and weak passive return remains.",
            SutureVoice.STRAIN, diagnostic, record = true, drive = false, durationSeconds = 8f))
        add(Case("DIAGNOSTICS", "Causal comparisons", "strain_stopped_cords", "STRAIN: cord sliding disabled",
            "Same vessel gesture with stationary cord travel; friction chirps should disappear while stored cord motion can ring down.",
            SutureVoice.STRAIN, diagnostic, record = true, cordMotion = false))
        for ((energy, label) in listOf(.25f to "quiet", 1f to "strong")) for (resistance in listOf(true, false)) {
            add(Case("RESISTANCE", "Matched actuator settings", "closure_${label}_${if (resistance) "resisted" else "control"}",
                "${label.uppercase()} / resistance ${if (resistance) "on" else "off"}",
                "Identical actuator and timbral settings. Only gesture energy and the explicit resistance diagnostic vary.",
                SutureVoice.STRAIN, neutral(SutureVoice.STRAIN) + mapOf("GAP" to .7f, "STITCH" to .5f, "CORD" to .55f, "SEAM" to .25f),
                velocity = energy, record = true, resistance = resistance, durationSeconds = 6f))
        }
        add(Case("PASSIVE", "Unpowered seeded decay", "passive_seed", "Seeded root, all work disabled",
            "Initial root-mode energy .02; no note gesture, powered drive or cord travel. Energy must decay without new friction work.",
            SutureVoice.STRAIN, diagnostic, record = true, drive = false, gesture = false,
            cordMotion = false, initialEnergy = .02, durationSeconds = 8f))
        fun edge(id: String, label: String, voice: SutureVoice, settings: Map<String, Float>) {
            add(Case("EDGES", "Mechanical extremes", id, label, "${line(settings)}. Other controls at voice defaults.",
                voice, Suture.defaults(voice) + settings, record = true))
        }
        edge("high_chirps", "High-register wooden chirps", SutureVoice.THREAD,
            mapOf("TUNE" to 1f, "STITCH" to 1f, "CORD" to 1f, "SEAM" to .35f))
        edge("slow_broad", "Broad opening, gentle take-up", SutureVoice.BLOOM,
            mapOf("GAP" to 1f, "STITCH" to 0f, "CAVITY" to 1f))
        edge("hard_contact", "Near-closed hard seam", SutureVoice.MURMUR,
            mapOf("GAP" to .05f, "STITCH" to 1f, "SEAM" to 1f))
        edge("low_gap_cords", "Cord activity at low GAP", SutureVoice.THREAD,
            mapOf("GAP" to 0f, "STITCH" to .75f, "CORD" to 1f))
        for ((id, voice, settings) in listOf(
            Triple("rough", SutureVoice.STRAIN, timbral.associateWith { 1f }),
            Triple("deep", SutureVoice.SHELL, mapOf("GAP" to 1f, "STITCH" to .15f, "CAVITY" to 1f)),
            Triple("seam", SutureVoice.MURMUR, mapOf("GAP" to .05f, "STITCH" to 1f, "CORD" to 1f, "SEAM" to 1f)),
            Triple("high", SutureVoice.THREAD, mapOf("TUNE" to 1f, "STITCH" to 1f, "CORD" to 1f)),
        )) add(Case("HOLD", "Difficult powered cycles", "held_$id", "${voice.name}: $id HOLD",
            "${line(settings)}. Settled loop buffer; inspect convergence evidence and listen across wraps.",
            voice, Suture.defaults(voice) + settings + ("HOLD" to 1f), loop = true))
    }

    private fun firstListenCases() = cases().filter { it.section == "PRESETS" } + SutureVoice.entries.map { voice ->
        Case("VOICES", voice.name, "${tag(voice)}_c4_strong_neutral", "C4 STRONG / NEUTRAL",
            "${voice.name} at C4 with declared neutral timbral controls; strong finite gesture.", voice, neutral(voice))
    }

    private fun metrics(snip: Snip): Map<String, Double> {
        var peak = 0.0; var sum = 0.0; var energy = 0.0
        for (sample in snip.samples) {
            val x = sample.toDouble(); peak = max(peak, abs(x)); sum += x; energy += x * x
        }
        return mapOf("seconds" to snip.samples.size.toDouble() / snip.sampleRate, "peak" to peak,
            "rms" to sqrt(energy / snip.samples.size), "dc" to sum / snip.samples.size,
            "loudness" to Loudness.of(snip).toDouble())
    }

    private fun arrays(trace: Suture.Trace) = trace.gap.toList() + trace.gapVelocity.toList() + trace.tension.toList() + trace.travelSpeed.toList() +
        listOf(trace.slipEvents, trace.frictionWork, trace.seamActivity, trace.seamLoss, trace.resistance,
            trace.actuatorForce, trace.actuatorWork, trace.poweredAcousticWork, trace.gestureAcousticWork,
            trace.actuatorAcousticWork, trace.storedMechanicalWork, trace.pendingMechanicalWork, trace.energy, trace.passiveLoss)

    private fun arrayBytes(played: Suture.Played): Long =
        (played.raw.size.toLong() + listOfNotNull(played.plates, played.cords, played.eyelets, played.cavity, played.seam).sumOf { it.size.toLong() } +
            (played.trace?.let { trace -> arrays(trace).sumOf { it.size.toLong() } } ?: 0L)) * 4L + played.finalState.size * 8L

    private fun mechanicalSummary(t: Suture.Trace): Map<String, Any?> {
        val count = t.energy.size
        val indices = List(minOf(100, count)) { i -> if (count <= 1) 0 else i * (count - 1) / max(1, minOf(100, count) - 1) }
        fun meanAt(a: Array<FloatArray>, i: Int) = a.sumOf { it[i].toDouble() } / max(1, a.size)
        fun end(a: FloatArray) = a.lastOrNull()?.toDouble() ?: 0.0
        fun peak(a: FloatArray) = a.maxOrNull()?.toDouble() ?: 0.0
        return mapOf(
            "maxGap" to t.gap.maxOfOrNull { peak(it) }, "maxTension" to t.tension.maxOfOrNull { peak(it) },
            "maxAbsoluteTravelSpeed" to t.travelSpeed.maxOfOrNull { a -> a.maxOfOrNull { abs(it).toDouble() } ?: 0.0 },
            "slipEventSamples" to t.slipEvents.count { it > 0f },
            "totalSlipEvents" to t.slipEvents.sumOf { it.toDouble() },
            "totalFrictionWork" to end(t.frictionWork),
            "maxSeamActivity" to peak(t.seamActivity), "totalSeamLoss" to end(t.seamLoss),
            "maxResistance" to peak(t.resistance), "actuatorWork" to end(t.actuatorWork),
            "totalAcousticInputWork" to end(t.poweredAcousticWork),
            "gestureAcousticWork" to end(t.gestureAcousticWork), "actuatorAcousticWork" to end(t.actuatorAcousticWork),
            "remainingMechanicalWork" to end(t.storedMechanicalWork),
            "remainingPendingMechanicalWork" to end(t.pendingMechanicalWork), "maxPendingMechanicalWork" to peak(t.pendingMechanicalWork),
            "passiveLoss" to end(t.passiveLoss),
            "initialEnergy" to t.energy.firstOrNull(), "finalEnergy" to end(t.energy), "maxEnergy" to peak(t.energy),
            "curves" to mapOf("seconds" to indices.map { it.toDouble() / t.rate },
                "gap" to indices.map { meanAt(t.gap, it) }, "tension" to indices.map { meanAt(t.tension, it) },
                "energy" to indices.map { t.energy[it].toDouble() },
                "seam" to indices.map { t.seamActivity[it].toDouble() },
                "actuatorInput" to indices.map { t.actuatorAcousticWork[it].toDouble() },
                "gestureInput" to indices.map { t.gestureAcousticWork[it].toDouble() },
                "workReserve" to indices.map { t.storedMechanicalWork[it].toDouble() }),
        )
    }

    private fun writeTrace(file: File, t: Suture.Trace) {
        file.parentFile.mkdirs()
        file.bufferedWriter().use { out ->
            out.appendLine((listOf("seconds") + t.gap.indices.map { "gap_$it" } +
                t.gapVelocity.indices.map { "gap_velocity_$it" } + t.tension.indices.map { "tension_$it" } +
                t.travelSpeed.indices.map { "cord_travel_speed_$it" } +
                listOf("slip_events", "friction_work_cumulative", "seam_activity", "seam_loss_cumulative", "resistance", "actuator_force",
                    "actuator_work_cumulative", "total_acoustic_input_work_cumulative", "gesture_acoustic_work_cumulative",
                    "actuator_acoustic_work_cumulative", "remaining_mechanical_work", "pending_mechanical_work", "acoustic_energy", "passive_loss_cumulative")).joinToString(","))
            for (i in t.energy.indices) {
                out.appendLine((listOf(i.toDouble() / t.rate) + t.gap.map { it[i] } + t.gapVelocity.map { it[i] } +
                    t.tension.map { it[i] } + t.travelSpeed.map { it[i] } +
                    listOf(t.slipEvents[i], t.frictionWork[i], t.seamActivity[i], t.seamLoss[i],
                    t.resistance[i], t.actuatorForce[i], t.actuatorWork[i], t.poweredAcousticWork[i], t.gestureAcousticWork[i],
                    t.actuatorAcousticWork[i], t.storedMechanicalWork[i], t.pendingMechanicalWork[i], t.energy[i], t.passiveLoss[i])).joinToString(","))
            }
        }
    }

    private fun tag(voice: SutureVoice) = voice.name.lowercase(Locale.ROOT)
    private fun sourceHash(source: File) = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    private fun finite(v: Double): Double? = if (v.isFinite()) v else null
    private fun code(v: Float) = (v * 100).roundToInt().toString().padStart(3, '0')
    private fun fmt(v: Float) = "%.2f".format(Locale.ROOT, v).trimEnd('0').trimEnd('.')
    private fun line(macros: Map<String, Float>) = macros.entries.joinToString(" · ") { "${it.key} ${fmt(it.value)}" }
    private fun value(v: Any?): JsonValue = when (v) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(v)
        is Boolean -> JsonValue.Bool(v)
        is Number -> v.toDouble().let { require(it.isFinite()) { "non-finite diagnostic value" }; JsonValue.Num(it) }
        is Map<*, *> -> JsonValue.Obj(v.entries.associate { it.key.toString() to value(it.value) })
        is Iterable<*> -> JsonValue.Arr(v.map { value(it) })
        else -> error("cannot write ${v::class.simpleName} to the SUTURE manifest")
    }

    private fun page(manifest: String): String = PAGE.replace("/* SUTURE_MANIFEST */", "window.SUTURE_MANIFEST = $manifest;")

    private val PAGE = """
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>SUTURE vessel audition</title><style>
:root{color-scheme:dark;--bg:#15100d;--panel:#241b15;--line:#64452c;--text:#f3e8d7;--muted:#c0a890;--bronze:#f8b773;--cord:#92caba}
*{box-sizing:border-box}body{margin:0;background:radial-gradient(ellipse at 20% 0,#403025,transparent 65%),var(--bg);color:var(--text);font:15px/1.5 system-ui,sans-serif}
main{max-width:1150px;margin:auto;padding:24px 16px 60px}h1{font-size:34px;letter-spacing:4px;margin:0}h2{font-size:18px;color:var(--bronze)}h3{font-size:15px;margin:0}
p{max-width:930px}.muted{color:var(--muted)}a{color:var(--cord)}button,input,select,textarea{font:inherit;color:var(--text);background:#1b1510;border:1px solid var(--line);border-radius:5px}
button{padding:7px 12px;cursor:pointer}button:hover,button:focus-visible{border-color:var(--bronze)}button.active{background:var(--bronze);color:#211409}
.transport{position:sticky;top:0;z-index:2;padding:12px 16px;margin:20px -16px;background:#17120eef;border:1px solid var(--line);backdrop-filter:blur(12px)}.row{display:flex;gap:12px;align-items:center;flex-wrap:wrap}
#now{flex:1;min-width:200px;color:var(--bronze)}audio{width:100%;height:36px;display:block;margin-top:8px}.toolbar{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin-bottom:20px}input[type=search]{padding:9px 12px;width:min(100%,440px)}
.section{border:1px solid var(--line);border-radius:7px;margin:12px 0;background:var(--panel)}.section>summary{padding:14px 16px;cursor:pointer;font-weight:700}.body{padding:0 16px 16px}.cards{display:grid;gap:10px;grid-template-columns:repeat(auto-fit,minmax(min(100%,285px),1fr))}
.card{padding:14px;border:1px solid #4c3728;border-radius:5px;background:#1b1510}.card p{font-size:13px;color:var(--muted);margin:8px 0}.buttons{display:flex;gap:8px;margin:12px 0}.card details{font-size:12px;margin-top:10px}.card summary{cursor:pointer;color:var(--muted)}
.readout{font:12px/1.5 ui-monospace,monospace;white-space:pre-wrap;overflow-wrap:anywhere}canvas{display:block;width:100%;height:105px;background:#100d0a;margin-top:8px;border-radius:4px}.legend{font-size:11px;color:var(--muted)}textarea{width:100%;padding:8px;min-height:70px;resize:vertical}select{width:100%;padding:6px;margin:8px 0}[hidden]{display:none!important}
</style></head><body><main>
<h1>SUTURE</h1><p class="muted">An evolving bronze vessel · engineering audition</p>
<p>Bronze bloom → sliding cords → resisted closure → enclosed resonance → seam murmur. Listen for one connected object whose closure is shaped by the vibration it contains.</p>
<p class="muted">RAW retains the engine's amplitude before melodic loudness targeting. MATCHED brings the same render to the shared audition level. No rack effects are applied. Mechanical evidence helps explain the gesture; the owner's sonic verdict remains pending.</p>
<p id="counts" class="muted"></p>
<div class="transport"><div class="row"><span id="now" role="status" aria-live="polite">Choose a clip</span><label><input id="repeat" type="checkbox"> Repeat</label><button id="stop" type="button">Stop</button></div><audio id="player" controls preload="none"></audio></div>
<div class="toolbar"><input id="search" type="search" aria-label="Filter listening cases" placeholder="Filter by voice, control or diagnostic…"><button id="expand" type="button">Open all</button><button id="export" type="button">Save listening notes</button><a href="manifest.json" download>Manifest</a><a href="key.json" download>Case key</a></div>
<div id="sections"></div><p class="muted">HOLD contains settled powered material; the initial spread/release is absent from this loop-buffer format. Enable Repeat for several wraps. Listen for chirp doubling, pumping, changes in shape and audible seams. Browser notes stay local until you save them.</p>
</main><script>
/* SUTURE_MANIFEST */
(() => {
'use strict';
const data=window.SUTURE_MANIFEST, player=document.querySelector('#player'), now=document.querySelector('#now'), repeat=document.querySelector('#repeat');
const storageKey='snipsnap-suture-listening-v'+data.modelVersion+'-'+data.dspSourceSha256;let notes={},selected=null,request=0,cancelPending=null;
try{notes=JSON.parse(localStorage.getItem(storageKey)||'{}')}catch(_){}
function el(tag,text,cls){const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n}
function n(v,p=4){return v===null||v===undefined?'undefined':Number(v).toFixed(p)}
function scientific(v){return v===null||v===undefined?'undefined':Number(v).toExponential(3)}
function metric(label,m){return label+': peak '+n(m.peak)+' · RMS '+n(m.rms)+' · DC '+scientific(m.dc)+' · window RMS '+n(m.loudness)}
function persist(){try{localStorage.setItem(storageKey,JSON.stringify(notes))}catch(_){}}
function play(c,variant,button){
 const token=++request,position=selected&&selected.id===c.id&&!player.ended?(selected.waiting?selected.position:player.currentTime):0;
 if(cancelPending)cancelPending();player.pause();document.querySelectorAll('.buttons button.active').forEach(b=>b.classList.remove('active'));button.classList.add('active');
 selected={id:c.id,position,waiting:true};now.textContent=c.voice+' · '+c.label+' · '+variant.toUpperCase();player.loop=repeat.checked;player.preload='auto';
 function ready(){if(token!==request)return;player.currentTime=position<player.duration?position:0;selected.waiting=false}
 function error(){if(token===request)now.textContent='Audio could not load. Check that the WAV remains beside this page, or try another browser.'}
 player.addEventListener('canplay',ready,{once:true});player.addEventListener('error',error);
 cancelPending=()=>{player.removeEventListener('canplay',ready);player.removeEventListener('error',error);cancelPending=null};
 player.src=c[variant];player.load();try{const p=player.play();if(p)p.catch(e=>{if(token===request&&e.name!=='AbortError')now.textContent='Use the audio player’s Play button to start.'})}catch(e){error()}
}
repeat.addEventListener('change',()=>player.loop=repeat.checked);document.querySelector('#stop').addEventListener('click',()=>{++request;if(cancelPending)cancelPending();player.pause();player.currentTime=0;if(selected){selected.position=0;selected.waiting=false}});
document.querySelector('#counts').textContent=data.renderCount+' renders · '+data.caseCount+' listening cases · '+data.wavCount+' PCM-'+data.bitDepth+' WAVs · '+data.sampleRate.toLocaleString()+' Hz';
function draw(canvas,curves,series=[['gap','#f8b773'],['tension','#92caba'],['energy','#c79be3']],shared=false){
 const ctx=canvas.getContext('2d'),w=canvas.width=600,h=canvas.height=180,left=40,right=w-10,top=12,bottom=h-28;
 ctx.font='17px system-ui';ctx.fillStyle='#c0a890';ctx.strokeStyle='#493729';
 for(const level of[0,.5,1]){const y=bottom-level*(bottom-top);ctx.beginPath();ctx.moveTo(left,y);ctx.lineTo(right,y);ctx.stroke();ctx.fillText(String(level),3,y+5)}
 const commonScale=Math.max(1e-9,...series.flatMap(([key])=>curves[key]));
 for(const[key,color]of series){const values=curves[key],scale=shared?commonScale:Math.max(1e-9,...values);ctx.strokeStyle=color;ctx.lineWidth=2.5;ctx.beginPath();values.forEach((v,i)=>{const x=left+i*(right-left)/Math.max(1,values.length-1),y=bottom-Math.max(0,v)/scale*(bottom-top);if(i)ctx.lineTo(x,y);else ctx.moveTo(x,y)});ctx.stroke()}
 ctx.fillText('0 s',left,h-5);ctx.fillText(n(curves.seconds.at(-1)||0,1)+' s',right-80,h-5);
}
const cards=[],groups=[],sections=[];
for(const s of data.sections){
 const wrap=el('details',undefined,'section');wrap.open=data.firstListen||s.id==='DIAGNOSTICS'||s.id==='RESISTANCE';wrap.append(el('summary',s.id+' · '+s.cases.length+' cases'));
 const body=el('div',undefined,'body');body.append(el('p',s.description,'muted'));const grouped=new Map();
 for(const c of s.cases){
  if(!grouped.has(c.group)){const group=el('div');group.append(el('h2',c.group));const list=el('div',undefined,'cards');group.append(list);body.append(group);grouped.set(c.group,list);groups.push(group)}
  const card=el('article',undefined,'card');card.dataset.search=(s.id+' '+c.group+' '+c.label+' '+c.description+' '+c.voice).toLowerCase();card.append(el('h3',c.label),el('p',c.description));
  if(c.loopEvidence&&!c.loopEvidence.converged)card.append(el('p','HOLD diagnostic: complete state/seam convergence was not reached within the bounded preroll.'));
  const buttons=el('div',undefined,'buttons');for(const variant of['raw','matched']){const b=el('button',variant.toUpperCase());b.type='button';b.setAttribute('aria-label',c.voice+' '+c.label+', '+variant);b.addEventListener('click',()=>play(c,variant,b));buttons.append(b)}card.append(buttons);
  if(c.mechanics){const canvas=el('canvas');canvas.setAttribute('role','img');canvas.setAttribute('aria-label','Gap, tension and acoustic energy over time, each normalized to its own peak');card.append(canvas);draw(canvas,c.mechanics.curves);card.append(el('div','Gap: bronze · tension: green · energy: purple. Each curve uses its own peak.','legend'));const work=el('canvas');work.setAttribute('role','img');work.setAttribute('aria-label','Cumulative gesture and actuator acoustic work and remaining mechanical reserve, sharing one normalized scale');card.append(work);draw(work,c.mechanics.curves,[['gestureInput','#f8b773'],['actuatorInput','#92caba'],['workReserve','#c79be3']],true);card.append(el('div','Gesture input: bronze · actuator input: green · work reserve: purple. Shared scale.','legend'))}
  const evidence=el('details');evidence.append(el('summary','Inputs and measurements'));let text=Object.entries(c.macros).map(([k,v])=>k+' '+n(v,2)).join(' · ')+'\nGesture '+n(c.velocity,2)+' · '+n(c.rawMetrics.seconds,2)+' s\n'+metric('RAW',c.rawMetrics)+'\n'+metric('MATCHED',c.matchedMetrics);
  if(c.cost.renderMs!==undefined)text+='\nRender '+n(c.cost.renderMs,1)+' ms · retained arrays '+n(c.cost.retainedSampleAndTraceArrayBytes/1048576,2)+' MiB\nJVM sum of pool peaks '+n(c.cost.sumHeapPoolPeakBytes/1048576,1)+' MiB (see manifest limitations)';
  if(c.closureSeconds!==undefined)text+='\nClosure '+n(c.closureSeconds,3)+' s';
  if(c.mechanics){const m=c.mechanics;text+='\nSlips '+n(m.totalSlipEvents,0)+' · max cord travel '+scientific(m.maxAbsoluteTravelSpeed)+'\nActuator mechanical work '+scientific(m.actuatorWork)+' · friction debit '+scientific(m.totalFrictionWork)+'\nAcoustic input: gesture '+scientific(m.gestureAcousticWork)+' + actuator '+scientific(m.actuatorAcousticWork)+' = total '+scientific(m.totalAcousticInputWork)+'\nRemaining mechanical reserve '+scientific(m.remainingMechanicalWork)+'\nPeak resistance '+scientific(m.maxResistance)+' · seam activity '+scientific(m.maxSeamActivity)+'\nEnergy: initial '+scientific(m.initialEnergy)+' → final '+scientific(m.finalEnergy)+' · passive loss '+scientific(m.passiveLoss)}
  if(c.loopEvidence){const l=c.loopEvidence;text+='\nSeam '+scientific(l.seam)+' · state error '+scientific(l.stateError)+'\n'+l.cycles+' cycles · converged '+l.converged+' · raw RMS '+scientific(l.rawRms)+'\nWork/cycle: mechanical actuator '+scientific(l.actuatorWorkPerCycle)+' · acoustic input '+scientific(l.acousticWorkPerCycle)+'\nMax acoustic energy across preroll '+scientific(l.maxEnergy)+'\nState groups: '+Object.entries(l.groupErrors||{}).map(([key,v])=>key+' '+scientific(v)).join(' · ');if(!l.audibleRaw)text+='\nRaw held material is below the audible capture threshold.'}
  if(c.probe)text+='\nProbe '+JSON.stringify(c.probe);evidence.append(el('div',text,'readout'));if(c.traceCsv){const a=el('a','Download mechanical trace (CSV)');a.href=c.traceCsv;a.download='';evidence.append(a)}card.append(evidence);
  const verdict=el('details');verdict.append(el('summary','Listening verdict'));const rating=el('select');rating.setAttribute('aria-label','Verdict for '+c.label);for(const name of['Unreviewed','Connected evolving vessel','Needs work']){const o=el('option',name);o.value=name;rating.append(o)}
  const note=el('textarea');note.placeholder='Bronze root, cord/eyelet timing, closure, seam tail, loop…';note.setAttribute('aria-label','Listening notes for '+c.label);rating.value=(notes[c.id]||{}).verdict||'Unreviewed';note.value=(notes[c.id]||{}).note||'';
  function save(){notes[c.id]={voice:c.voice,label:c.label,verdict:rating.value,note:note.value};persist()}rating.addEventListener('change',save);note.addEventListener('input',save);verdict.append(rating,note);card.append(verdict);grouped.get(c.group).append(card);cards.push(card);
 }
 wrap.append(body);document.querySelector('#sections').append(wrap);sections.push(wrap);
}
document.querySelector('#search').addEventListener('input',e=>{const q=e.target.value.trim().toLowerCase();for(const c of cards)c.hidden=!c.dataset.search.includes(q);for(const g of groups)g.hidden=!Array.from(g.querySelectorAll('.card')).some(c=>!c.hidden);for(const s of sections){s.hidden=!Array.from(s.querySelectorAll('.card')).some(c=>!c.hidden);if(q&&!s.hidden)s.open=true}});
document.querySelector('#expand').addEventListener('click',()=>sections.forEach(s=>s.open=true));
document.querySelector('#export').addEventListener('click',()=>{const blob=new Blob([JSON.stringify({engine:'SUTURE',modelVersion:data.modelVersion,dspSourceSha256:data.dspSourceSha256,firstListen:data.firstListen,savedAt:new Date().toISOString(),notes},null,2)],{type:'application/json'});const url=URL.createObjectURL(blob),a=el('a');a.href=url;a.download='suture-listening-notes.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)});
})();
</script></body></html>
""".trimIndent()
}
