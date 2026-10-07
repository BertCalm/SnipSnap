package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Offline Undertow acceptance evidence: raw/matched audio, mechanics CSVs and
 * an accessible listening page whose manifest is embedded for file-URL use.
 * Full mode covers all six voices at three pitches and event energies, five
 * five-step neutral-companion sweeps per voice, and the five required grids.
 * No rack effects or per-clip gain are applied to the raw PCM-24 captures.
 * Run :synth:generateUndertowAudition; -PundertowQuick=true keeps a small
 * representative subset for development, explicitly labelled in the manifest.
 */
object UndertowAuditionGenerator {
    private val timbral = listOf("DRAW", "FLAP", "WEIGHT", "SPIRAL", "LEAK")
    private val steps = listOf(0f, .25f, .5f, .75f, 1f)
    private val notes = listOf(0f to "C3", .5f to "C4", 1f to "C5")
    private val energies = listOf(.25f to "QUIET", .6f to "MEDIUM", 1f to "STRONG")
    private val descriptions = linkedMapOf(
        "VOICES" to "All six voices at low/middle/high supported notes (C3/C4/C5) and event energy .25/.6/1. Voice defaults remain active.",
        "SWEEPS" to "Each of the five timbral controls at 0/.25/.5/.75/1 for every voice. Companion controls use MacroSpec.neutral, with C4 and HOLD off.",
        "GRIDS" to "DRAW × FLAP, FLAP × WEIGHT, DRAW × LEAK, SPIRAL × FLAP and WEIGHT × LEAK at 0/.5/1. Companion controls remain neutral.",
        "MECHANICS" to "Shared, independent-reservoir, isolated-inlet and contact-disabled versions of the same draws. CSVs record actual pressure, flow, aperture, motion, work and contact events. Stem captures come from the same simulation.",
        "EDGES" to "High-leak soft draws, low-leak seals, all-high controls, and high-register flutter. Compare raw amplitude before judging matched timbre.",
        "PASSIVE" to "Piston extraction stops at .7 seconds. The remaining six-second record exposes pressure relaxation and passive mechanical/acoustic decay.",
        "HOLD" to "Settled loop buffers for every voice, including quiet event energy .25 with DRAW 0 and LEAK 1, plus difficult low-draw/high-leak, low-leak seal, all-high and high-register flutter cases. The initial catch is absent from the host's loop-only sample format. Repeat across several wraps and inspect measured cycle/seam evidence.",
    )

    private data class Case(
        val section: String,
        val group: String,
        val id: String,
        val label: String,
        val description: String,
        val voice: UndertowVoice,
        val macros: Map<String, Float>,
        val energy: Float = 1f,
        val options: Undertow.ProbeOptions = Undertow.ProbeOptions(recordDiagnostics = false),
        val stems: Boolean = false,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/undertow-audition")
        require(root.mkdirs() || root.isDirectory) { "cannot create ${root.absolutePath}" }
        val quick = "--quick" in args.drop(1)
        val cases = cases().filter { !quick || isQuickCase(it) }
        require(cases.map { it.id }.distinct().size == cases.size) { "duplicate audition case IDs" }
        val sections = descriptions.mapValues { mutableListOf<Map<String, Any?>>() }
        var wavs = 0
        val started = System.nanoTime()

        fun write(c: Case, samples: FloatArray, renderMs: Double, extra: Map<String, Any?> = emptyMap()) {
            require(samples.isNotEmpty() && samples.all { it.isFinite() }) { "${c.id}: invalid raw audio" }
            // PCM conversion otherwise silently clips an unstable raw render.
            require(samples.all { abs(it) < 1f }) { "${c.id}: raw output reaches or exceeds PCM range" }
            val raw = Snip(samples, channels = 1, sampleRate = Dsp.RATE)
            val matched = AuditionLevel.level(raw)
            val path = "${c.section}/${c.id}"
            WavWriter.write(File(root, "${path}_raw.wav"), raw, WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(root, "${path}_matched.wav"), matched, WavWriter.BitDepth.PCM_24)
            wavs += 2
            sections.getValue(c.section) += linkedMapOf<String, Any?>(
                "id" to c.id, "group" to c.group, "label" to c.label,
                "description" to c.description, "voice" to c.voice.name,
                "macros" to c.macros, "eventEnergy" to c.energy,
                "requestedHz" to Undertow.frequencyFor(c.voice, c.macros.getValue("TUNE")),
                "raw" to "${path}_raw.wav", "matched" to "${path}_matched.wav",
                "rawMetrics" to metrics(raw), "matchedMetrics" to metrics(matched),
                "renderMs" to renderMs, "loop" to ((c.macros["HOLD"] ?: 0f) >= .999f),
            ) + extra
        }

        cases.forEachIndexed { index, c ->
            val heapBefore = usedHeap()
            val began = System.nanoTime()
            val p = Undertow.probe(c.voice, c.macros, energy = c.energy,
                options = c.options.copy(normalize = false))
            val renderMs = (System.nanoTime() - began) / 1e6
            val heapAfter = usedHeap()
            val extra = linkedMapOf<String, Any?>(
                "probe" to mapOf(
                    "independentReservoirs" to c.options.independentReservoirs,
                    "contactsEnabled" to c.options.contactsEnabled,
                    "activeChambers" to c.options.activeChambers,
                    "durationSeconds" to c.options.durationSeconds,
                    "driveStopSeconds" to c.options.driveStopSeconds,
                    "seedContext" to c.options.seedContext,
                ),
                "rawPeakBeforeExport" to finite(p.rawPeak.toDouble()),
                "finalNetworkEnergy" to finite(p.finalEnergy),
                "heapBeforeBytes" to heapBefore, "heapAfterBytes" to heapAfter,
                "heapDeltaBytes" to (heapAfter - heapBefore),
            )
            if (c.options.recordDiagnostics) {
                val trace = "${c.section}/${c.id}_state.csv"
                val contacts = "${c.section}/${c.id}_contacts.csv"
                writeTrace(File(root, trace), p.snapshots)
                writeContacts(File(root, contacts), p.contacts)
                extra["traceCsv"] = trace
                extra["contactsCsv"] = contacts
                extra["mechanics"] = mechanics(p)
            }
            if ((c.macros["HOLD"] ?: 0f) >= .999f) {
                extra["loopEvidence"] = mapOf(
                    "seamError" to finite(p.seamError),
                    "cycleStateError" to finite(p.cycleStateError),
                    "meetsSeamTarget" to (p.seamError.isFinite() && p.seamError < 1e-3),
                    "previousCycleFrames" to p.previousCycle.size,
                    "acousticCycleRelativeError" to cycleError(p.samples, p.previousCycle),
                    "rawRms" to metrics(Snip(p.samples, 1, Dsp.RATE)).getValue("rms"),
                )
            }
            write(c, p.samples, renderMs, extra)
            if (c.stems) {
                listOf("ceramic" to p.ceramic, "airflow" to p.airflow, "shell" to p.shell).forEach { (id, audio) ->
                    write(c.copy(id = "${c.id}_$id", group = "${c.voice.name} / same-run stems",
                        label = "${c.voice.name}: $id", description = "Actual $id contribution from ${c.id}; raw gain is preserved."),
                        audio, 0.0, mapOf("stemOf" to c.id, "stem" to id))
                }
            }
            if ((index + 1) % 20 == 0 || index == cases.lastIndex) {
                println("UNDERTOW audition: ${index + 1}/${cases.size} renders, $wavs WAVs")
            }
        }

        val manifest = Json.write(value(linkedMapOf(
            "engine" to "UNDERTOW", "quick" to quick,
            "coverage" to if (quick) "Representative development subset; regenerate without -PundertowQuick for the complete acceptance roster." else "Complete section-18 audition roster.",
            "renderCount" to cases.size, "caseCount" to sections.values.sumOf { it.size },
            "wavCount" to wavs, "sampleRate" to Dsp.RATE, "bitDepth" to 24,
            "elapsedSeconds" to (System.nanoTime() - started) / 1e9,
            "rawMeaning" to "Band-limited engine output before shared melodic loudness targeting. No per-clip raw gain or rack effects.",
            "matchedMeaning" to "The same render at AuditionLevel's shared loudest-200ms RMS target, with its peak guard.",
            "memoryMeaning" to "JVM used-heap readings immediately before/after each probe; delta includes unrelated allocation and garbage collection and is not an allocation profiler.",
            "listeningVerdict" to "Pending owner review. Numeric evidence does not establish convincing identity, useful macros or seam quality.",
            "sections" to descriptions.filterKeys { sections.getValue(it).isNotEmpty() }.map { (id, description) ->
                mapOf("id" to id, "description" to description, "cases" to sections.getValue(id))
            },
        ))) + "\n"
        File(root, "manifest.json").writeText(manifest)
        val template = UndertowAuditionGenerator::class.java.getResourceAsStream("/audition/undertow-audition.html")
            ?.bufferedReader()?.use { it.readText() } ?: error("missing Undertow listening page")
        require("/* UNDERTOW_MANIFEST */" in template) { "page has no manifest insertion point" }
        File(root, "index.html").writeText(template.replace("/* UNDERTOW_MANIFEST */", "window.UNDERTOW_MANIFEST = $manifest;"))
        File(root, "README.txt").writeText(
            "UNDERTOW audition\n" +
                "Regenerate: ./gradlew :synth:generateUndertowAudition${if (quick) " -PundertowQuick=true" else ""}\n" +
                "${cases.size} renders, ${sections.values.sumOf { it.size }} listening cases, $wavs paired PCM-24 WAV files.\n" +
                "Open index.html directly. RAW and MATCHED are downloadable for every case.\n" +
                "Mechanics, edge, passive and HOLD cases include state/contact CSVs and real measured evidence.\n" +
                "HOLD contains settled loop material, with the first catch absent. Enable Repeat to review seams.\n" +
                "Owner listening verdict remains pending. ${if (quick) "This quick run is not the full acceptance roster." else ""}\n",
        )
        println("wrote $wavs WAVs + manifest.json + index.html under ${root.absolutePath}")
    }

    private fun cases(): List<Case> = buildList {
        for (voice in UndertowVoice.entries) {
            val tag = voice.name.lowercase()
            val defaults = Undertow.defaults(voice)
            val neutral = neutral(voice)
            for ((tune, note) in notes) for ((energy, name) in energies) {
                add(Case("VOICES", voice.name, "${tag}_${note.lowercase()}_${name.lowercase()}", "$note $name",
                    "${voice.name} defaults; event energy ${fmt(energy)}.", voice,
                    defaults + ("TUNE" to tune), energy = energy))
            }
            for (macro in timbral) for (step in steps) {
                add(Case("SWEEPS", "${voice.name} / $macro", "${tag}_${macro.lowercase()}_${code(step)}",
                    "$macro ${fmt(step)}", "C4, energy 1. Companion timbral controls at their declared neutral values.",
                    voice, neutral + (macro to step)))
            }
            add(Case("MECHANICS", "${voice.name} / causal comparisons", "${tag}_shared", "${voice.name}: shared",
                "Four inlets share one suction reservoir. Compare pressure, onset and contact chronology with the three ablations.",
                voice, defaults, options = Undertow.ProbeOptions(), stems = voice == UndertowVoice.SEAL))
            add(Case("MECHANICS", "${voice.name} / causal comparisons", "${tag}_independent", "${voice.name}: independent reservoirs",
                "Same settings and seed, four independent diagnostic reservoirs. Shared competition is removed.",
                voice, defaults, options = Undertow.ProbeOptions(independentReservoirs = true)))
            add(Case("MECHANICS", "${voice.name} / causal comparisons", "${tag}_isolated", "${voice.name}: isolated inlet",
                "Only the dominant inlet is active. Compare its flow and contact timing with the four-inlet shared draw.",
                voice, defaults, options = Undertow.ProbeOptions(activeChambers = 1)))
            add(Case("MECHANICS", "${voice.name} / causal comparisons", "${tag}_no_contact", "${voice.name}: contact disabled",
                "Same pressure/flow network with rim contacts disabled. Compare ceramic audio and event evidence.",
                voice, defaults, options = Undertow.ProbeOptions(contactsEnabled = false)))
            add(Case("EDGES", "High-leak soft draws", "${tag}_soft_leaky", "${voice.name}: soft, high leak",
                "DRAW .05, LEAK 1, quiet event energy .25. Contact and pitched material should remain useful at low drive.",
                voice, defaults + mapOf("DRAW" to .05f, "LEAK" to 1f), energy = .25f,
                options = Undertow.ProbeOptions()))
            add(Case("EDGES", "Low-leak seals", "${tag}_tight_seal", "${voice.name}: tight seal",
                "DRAW 1, LEAK 0, FLAP .75. Inspect closure, redirection and subsequent pressure relaxation.",
                voice, defaults + mapOf("DRAW" to 1f, "LEAK" to 0f, "FLAP" to .75f),
                options = Undertow.ProbeOptions()))
            add(Case("EDGES", "All controls high", "${tag}_all_high", "${voice.name}: all high",
                "All five timbral controls at 1, C4, energy 1 and HOLD off.",
                voice, defaults + timbral.associateWith { 1f }, options = Undertow.ProbeOptions()))
            add(Case("PASSIVE", "Stopped extraction", "${tag}_stopped", "${voice.name}: piston stops at .7 s",
                "Drive stops at .7 seconds; six-second diagnostic capture continues through passive relaxation and ringdown.",
                voice, defaults, options = Undertow.ProbeOptions(durationSeconds = 6f, driveStopSeconds = .7f)))
            add(Case("HOLD", "Every voice held", "${tag}_held", "${voice.name}: HOLD 1",
                "Settled loop-only suction material. Repeat and listen for extra contacts, absent breath or pressure jumps.",
                voice, defaults + ("HOLD" to 1f), options = Undertow.ProbeOptions()))
            add(Case("HOLD", "Quiet high-leak maintenance", "${tag}_held_quiet_leaky", "${voice.name}: quiet, high-leak HOLD",
                "DRAW 0, LEAK 1, HOLD 1 and quiet event energy .25. Inspect whether powered maintenance preserves useful raw pitched airflow across loop wraps.",
                voice, defaults + mapOf("DRAW" to 0f, "LEAK" to 1f, "HOLD" to 1f), energy = .25f,
                options = Undertow.ProbeOptions()))
        }
        for ((a, b, voice) in listOf(
            Triple("DRAW", "FLAP", UndertowVoice.FLUTTER),
            Triple("FLAP", "WEIGHT", UndertowVoice.KNOCK),
            Triple("DRAW", "LEAK", UndertowVoice.SEAL),
            Triple("SPIRAL", "FLAP", UndertowVoice.HOLLOW),
            Triple("WEIGHT", "LEAK", UndertowVoice.SURGE),
        )) for (x in listOf(0f, .5f, 1f)) for (y in listOf(0f, .5f, 1f)) {
            add(Case("GRIDS", "$a × $b / ${voice.name}", "${a.lowercase()}${code(x)}_${b.lowercase()}${code(y)}",
                "$a ${fmt(x)} · $b ${fmt(y)}", "${voice.name}, C4 and event energy 1. Companion controls at neutral.",
                voice, neutral(voice) + mapOf(a to x, b to y)))
        }
        add(Case("EDGES", "High register", "high_flutter", "FLUTTER: high-register flutter",
            "C5, FLAP 1 and DRAW 1; inspect powered flap repetition and high-register audio.",
            UndertowVoice.FLUTTER, Undertow.defaults(UndertowVoice.FLUTTER) + mapOf("TUNE" to 1f, "DRAW" to 1f, "FLAP" to 1f),
            options = Undertow.ProbeOptions()))
        for ((id, voice, settings) in listOf(
            Triple("soft_leaky", UndertowVoice.BREATH, mapOf("DRAW" to .02f, "LEAK" to 1f)),
            Triple("sealed", UndertowVoice.SEAL, mapOf("DRAW" to 1f, "FLAP" to 1f, "WEIGHT" to 1f, "LEAK" to 0f)),
            Triple("all_high", UndertowVoice.SURGE, timbral.associateWith { 1f }),
            Triple("high_flutter", UndertowVoice.FLUTTER, mapOf("TUNE" to 1f, "DRAW" to 1f, "FLAP" to 1f)),
        )) {
            add(Case("HOLD", "Difficult held cases", "held_$id", "${voice.name}: $id HOLD",
                "${line(settings)}; HOLD 1. Review cycle state, raw energy and contact continuity as well as the seam metric.",
                voice, Undertow.defaults(voice) + settings + ("HOLD" to 1f), options = Undertow.ProbeOptions()))
        }
    }

    private fun isQuickCase(c: Case): Boolean = when (c.section) {
        "VOICES" -> c.id.endsWith("_c4_strong")
        "MECHANICS" -> c.voice == UndertowVoice.SEAL
        "EDGES" -> c.id in setOf("breath_soft_leaky", "seal_tight_seal", "surge_all_high", "high_flutter")
        "PASSIVE" -> c.voice == UndertowVoice.HOLLOW
        "HOLD" -> c.id in setOf("breath_held", "breath_held_quiet_leaky", "held_soft_leaky", "held_sealed", "held_high_flutter")
        else -> false
    }

    private fun mechanics(p: Undertow.Probe): Map<String, Any?> {
        val trace = p.snapshots
        val indices = List(minOf(100, trace.size)) { i -> if (trace.size <= 1) 0 else i * (trace.size - 1) / max(1, minOf(100, trace.size) - 1) }
        return mapOf(
            "snapshotCount" to trace.size, "contactCount" to p.contacts.size,
            "firstContactSeconds" to p.contacts.firstOrNull()?.timeSeconds,
            "contactsPerChamber" to (0..3).map { chamber -> p.contacts.count { it.chamber == chamber } },
            "maxSuction" to trace.maxOfOrNull { it.suction },
            "maxFlow" to trace.maxOfOrNull { it.flows.sum() },
            "maxStateEnergy" to finite(trace.maxOfOrNull { it.energy } ?: 0.0),
            "pistonWork" to finite(trace.lastOrNull()?.pistonWork ?: 0.0),
            "sealedSnapshotCounts" to (0..3).map { chamber -> trace.count { it.sealed.getOrElse(chamber) { false } } },
            "curves" to mapOf(
                "seconds" to indices.map { trace[it].timeSeconds },
                "suction" to indices.map { trace[it].suction },
                "totalFlow" to indices.map { trace[it].flows.sum() },
                "meanAperture" to indices.map { trace[it].apertures.average() },
            ),
        )
    }

    private fun writeTrace(file: File, trace: List<Undertow.StateSnapshot>) {
        file.parentFile.mkdirs()
        file.bufferedWriter().use { out ->
            out.appendLine((listOf("seconds", "suction") + listOf("local_pressure", "flow", "aperture", "displacement", "velocity", "sealed")
                .flatMap { name -> (0..3).map { "${name}_$it" } } + listOf("piston_work", "network_energy")).joinToString(","))
            for (s in trace) {
                val row = listOf(s.timeSeconds, s.suction) + s.localPressure.toList() + s.flows.toList() +
                    s.apertures.toList() + s.displacement.toList() + s.velocity.toList() +
                    s.sealed.map { if (it) 1 else 0 } + listOf(s.pistonWork, s.energy)
                out.appendLine(row.joinToString(","))
            }
        }
    }

    private fun writeContacts(file: File, contacts: List<Undertow.ContactEvent>) {
        file.parentFile.mkdirs()
        file.bufferedWriter().use { out ->
            out.appendLine("seconds,chamber,strength,sealed")
            contacts.forEach { out.appendLine("${it.timeSeconds},${it.chamber},${it.strength},${if (it.sealed) 1 else 0}") }
        }
    }

    private fun metrics(snip: Snip): Map<String, Double> {
        var peak = 0.0; var sum = 0.0; var energy = 0.0
        for (sample in snip.samples) {
            val x = sample.toDouble(); peak = max(peak, abs(x)); sum += x; energy += x * x
        }
        return mapOf("seconds" to snip.samples.size.toDouble() / snip.sampleRate,
            "peak" to peak, "rms" to sqrt(energy / snip.samples.size),
            "dc" to sum / snip.samples.size, "loudness" to Loudness.of(snip).toDouble())
    }

    private fun cycleError(current: FloatArray, previous: FloatArray): Double? {
        if (current.isEmpty() || current.size != previous.size) return null
        var difference = 0.0; var power = 0.0
        for (i in current.indices) {
            val d = current[i].toDouble() - previous[i]; difference += d * d
            power += current[i].toDouble() * current[i]
        }
        return finite(sqrt(difference / max(1e-18, power)))
    }
    private fun neutral(voice: UndertowVoice) = Undertow.macrosFor(voice).associate { it.name to it.neutral } + mapOf("TUNE" to .5f, "HOLD" to 0f)
    private fun usedHeap() = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    private fun finite(v: Double): Double? = v.takeIf { it.isFinite() }
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
        else -> error("cannot write ${v::class.simpleName} to Undertow manifest")
    }
}
