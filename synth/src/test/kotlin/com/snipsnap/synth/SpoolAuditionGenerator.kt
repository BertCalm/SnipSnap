package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/** Real offline Spool recordings and evidence; the listening site consumes manifest.json. */
object SpoolAuditionGenerator {
    private val timbral = listOf("HEAD", "STRAIN", "RETURN", "IMPRINT", "SPLICE")
    private val descriptions = mapOf(
        SpoolVoice.STRING to "Broad bowed harmonics and a bow-shaped attack, carried by overlapping reverse swells and tape flutter.",
        SpoolVoice.CHOIR to "Pronounced evolving vowel formants and a soft voiced onset, with a returning breath beneath the note.",
        SpoolVoice.FLUTE to "A pure airy root, sparse upper harmonics and filtered airflow with gently moving tape pitch.",
        SpoolVoice.BRASS to "Bright even harmonics, a fast lip-shaped attack and a darker sustain that blooms backward.",
        SpoolVoice.PALIMPSEST to "Vocal and bowed recordings combine with filtered inherited traces from neighboring strips.",
        SpoolVoice.CUT to "A brighter brass/flute recording is rearranged by overlapping recorded-section cuts.",
    )
    private val macroDescriptions = mapOf(
        "HEAD" to "Aligned pickup to diagonal track and recorded-time travel.",
        "STRAIN" to "Gentle common wow/flutter to stronger competing strip loads, slip and recovery.",
        "RETURN" to "Reversed recorded decays swell and brighten beneath the held recording.",
        "IMPRINT" to "Delayed neighboring traces are heard during the layered return.",
        "SPLICE" to "Continuous timeline to smoothly overlapped reordered sections.",
    )

    private data class Case(
        val id: String,
        val section: String,
        val voice: SpoolVoice,
        val label: String,
        val description: String,
        val macros: Map<String, Float> = emptyMap(),
        val midi: Int = 60,
        val velocity: Float = 1f,
        val gate: Float = 1.35f,
        val sourceSeconds: Float = 3f,
        val motorTorque: Float = 1f,
        val companionLoad: Boolean = false,
        val frozenHead: Boolean = false,
        val writes: Boolean = true,
        val repeatedCopies: Int = 1,
        val macro: String? = null,
        val value: Float? = null,
        val groupKey: String? = null,
        val machine: SpoolMachine? = null,
        val excerptReread: Boolean = false,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull { !it.startsWith("--") } ?: "../testkit/spool-audition")
        val quick = "--quick" in args
        val movementOnly = "--movement-only" in args
        require(!(quick && movementOnly)) { "Movement refresh requires all five sweep positions" }
        require(root.isDirectory || root.mkdirs()) { "Could not create ${root.absolutePath}" }
        val cases = cases(quick).filter { !movementOnly || it.section == "macros" }
        check(cases.map { it.id }.distinct().size == cases.size)
        val source = listOf(File("src/main/kotlin/com/snipsnap/synth/Spool.kt"), File("synth/src/main/kotlin/com/snipsnap/synth/Spool.kt"))
            .first { it.isFile }
        val sourceFiles = listOf("Spool.kt", "SpoolPatch.kt", "SpoolPresets.kt").associate { name ->
            "synth/src/main/kotlin/com/snipsnap/synth/$name" to File(source.parentFile, name)
        }
        val sourceFileHashes = sourceFiles.mapValues { (_, file) -> sha256(file.readBytes()) }
        val sourceHash = sourceFileHashes.getValue("synth/src/main/kotlin/com/snipsnap/synth/Spool.kt")
        fun verifySources() {
            for ((path, file) in sourceFiles) check(file.isFile && sha256(file.readBytes()) == sourceFileHashes.getValue(path)) {
                "$path changed during rendering; regenerate one consistent pack"
            }
        }
        val previous = if (movementOnly) {
            require(File(root, "manifest.json").isFile) { "Movement refresh needs an existing complete pack" }
            Json.parse(File(root, "manifest.json").readText()).obj()
        } else null
        if (previous != null) {
            require(previous.getValue("provenance").obj().getValue("sourceFilesSha256") == j(sourceFileHashes)) {
                "DSP or factory geometry changed; regenerate the complete pack"
            }
            val oldClips = previous.getValue("clips").arr().map { it.obj() }
            require(timbral.all { macro -> oldClips.count {
                it["section"]?.str() == "macros" && it["macro"]?.str() == macro
            } == 5 }) { "Movement refresh needs an existing complete five-position pack" }
        }
        val entries = previous?.getValue("clips")?.arr()?.filter {
            it.obj().getValue("section").str() != "macros"
        }?.toMutableList() ?: mutableListOf()
        for ((index, case) in cases.withIndex()) {
            verifySources()
            val macros = Spool.defaults(case.voice) + case.macros + ("TUNE" to ((case.midi - 48) / 24f))
            val geometry = case.machine ?: SpoolMachine.forVoice(case.voice).copy(gateSeconds = case.gate, sourceSeconds = case.sourceSeconds, motorTorque = case.motorTorque)
            val machine = if (case.companionLoad) geometry.copy(roster = geometry.roster.mapIndexed { i, strip ->
                if (i == 2) strip.copy(tension = 2f, drag = 1f) else strip
            }) else geometry
            val start = System.nanoTime()
            val result = Spool.renderProbe(case.voice, macros, machine, case.velocity,
                normalize = false, frozenHead = case.frozenHead, writes = case.writes)
            verifySources()
            val renderMs = (System.nanoTime() - start) / 1_000_000.0
            require(result.samples.isNotEmpty() && result.samples.all { it.isFinite() }) { "Invalid clip ${case.id}" }
            val held = Spool.isLoop(macros.getValue("HOLD"))
            val rereadStart = if (case.excerptReread) (result.diagnostics.returnEndSeconds * Dsp.RATE).toInt() else 0
            val rereadEnd = if (case.excerptReread)
                ((result.diagnostics.returnEndSeconds + result.diagnostics.reengageSeconds) * Dsp.RATE).toInt().coerceAtMost(result.samples.size)
                else result.samples.size
            require(!case.excerptReread || !held && case.repeatedCopies == 1 && rereadEnd > rereadStart) {
                "A reread excerpt needs a finite reengagement"
            }
            val samples = if (case.excerptReread) result.samples.copyOfRange(rereadStart, rereadEnd)
                else if (case.repeatedCopies == 1) result.samples else
                FloatArray(result.samples.size * case.repeatedCopies) { result.samples[it % result.samples.size] }
            val native = Snip(samples, 1, Dsp.RATE)
            val nativeMetrics = metrics(native)
            val peak = samples.maxOf { abs(it) }
            val exportGain = if (peak > .99f) .99f / peak else 1f
            val exported = if (exportGain == 1f) native else Snip(FloatArray(samples.size) { samples[it] * exportGain }, 1, Dsp.RATE)
            val matched = AuditionLevel.level(native)
            val rawPath = "raw/${case.id}.wav"
            val matchedPath = "matched/${case.id}.wav"
            WavWriter.write(File(root, rawPath), exported, WavWriter.BitDepth.PCM_16)
            WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_16)
            val tracePath = "diagnostics/${case.id}_transport.csv"
            val d = result.diagnostics
            File(root, tracePath).apply {
                parentFile.mkdirs()
                bufferedWriter().use { out ->
                    out.appendLine("time_seconds,motor_rate,pos_0,pos_1,pos_2,rate_0,rate_1,rate_2,head_0,head_1,head_2,spring_energy,motor_work,friction_loss")
                    val stride = (d.rate / 40).coerceAtLeast(1)
                    d.trace.filterIndexed { i, _ -> i % stride == 0 }.forEach { f ->
                        out.appendLine((listOf(f.timeSeconds, f.motorRate) + f.positions + f.rates + f.headCoordinates +
                            listOf(f.springEnergy, f.motorWork, f.frictionLoss)).joinToString(","))
                    }
                }
            }
            val eventsPath = "diagnostics/${case.id}_imprint.csv"
            File(root, eventsPath).bufferedWriter().use { out ->
                out.appendLine("time_seconds,source_strip,destination_strip,coordinate_seconds,gain")
                d.imprintEvents.forEach { event ->
                    out.appendLine(listOf(event.timeSeconds, event.source, event.destination, event.coordinateSeconds, event.gain).joinToString(","))
                }
            }
            val pickupPath = "diagnostics/${case.id}_pickups.csv"
            File(root, pickupPath).bufferedWriter().use { out ->
                out.appendLine("time_seconds,fwd_0,fwd_1,fwd_2,rev_0,rev_1,rev_2,fwd_rate_0,fwd_rate_1,fwd_rate_2,rev_rate_0,rev_rate_1,rev_rate_2,fwd_gain_0,fwd_gain_1,fwd_gain_2,rev_gain_0,rev_gain_1,rev_gain_2,fwd_min_0,fwd_min_1,fwd_min_2,fwd_max_0,fwd_max_1,fwd_max_2,rev_min_0,rev_min_1,rev_min_2,rev_max_0,rev_max_1,rev_max_2,rev_support_0,rev_support_1,rev_support_2,physical_rate_0,physical_rate_1,physical_rate_2,varispeed_blend,tape_rate")
                val stride = (d.rate / 40).coerceAtLeast(1)
                d.pickupTrace.filterIndexed { i, _ -> i % stride == 0 }.forEach { f ->
                    out.appendLine((listOf(f.timeSeconds) + f.forwardCoordinates + f.reverseCoordinates +
                        f.forwardReadRates + f.reverseReadRates + f.forwardGains + f.reverseGains +
                        f.forwardMinCoordinates + f.forwardMaxCoordinates + f.reverseMinCoordinates +
                        f.reverseMaxCoordinates + f.reverseSupports + f.physicalRates + listOf(f.varispeedBlend, f.tapeRate)).joinToString(","))
                }
            }
            val diagnosticValues = linkedMapOf<String, Any?>(
                "gateSeconds" to d.gateSeconds, "returnEndSeconds" to d.returnEndSeconds,
                "durationSeconds" to d.durationSeconds, "reengageSeconds" to d.reengageSeconds, "sourceHashes" to d.sourceHashes,
                "sourcePeak" to d.sourcePeak, "overlayPeak" to d.overlayPeak, "feedbackBound" to d.feedbackBound,
                "writeCount" to d.writeCount, "firstWriteSeconds" to d.firstWriteSeconds,
                "firstOverlayReadSeconds" to d.firstOverlayReadSeconds, "maxForwardRate" to d.maxForwardRate,
                "maxReverseRate" to d.maxReverseRate, "returnTravel" to d.returnTravel,
                "sourceFrames" to d.sourceFrames, "allocatedBytes" to d.allocatedBytes,
                "writesFrozen" to d.writesFrozen, "spliceCount" to d.spliceCount, "writePassLimit" to d.writePassLimit,
                "writeTimingReference" to if (held) "finite deterministic preroll" else "exported one-shot",
                "readTimingReference" to if (held) "exported frozen-snapshot cycle" else "exported one-shot",
                "frozenHead" to case.frozenHead, "writesEnabled" to case.writes,
                "motorTorque" to machine.motorTorque, "companionLoad" to case.companionLoad,
                "tracePath" to tracePath, "traceRate" to 40, "eventsPath" to eventsPath, "imprintEventCount" to d.imprintEvents.size,
                "pickupTracePath" to pickupPath, "pitchMode" to d.pitchMode,
                "overlapSeconds" to d.overlapSeconds, "overlapMeasure" to "active grain/contact time; audible RMS overlap is in the stem measurements", "carrierRateMeaning" to "grain branches share an integrated wow/flutter clock; physical transport is in the transport trace",
                "tapeMotion" to "common 0.73 Hz wow and 6.7/12.1 Hz flutter; both heads stay together",
                "tapeExcursionBoundCents" to Spool.MAX_TAPE_CENTS,
                "audioStartSeconds" to rereadStart.toDouble() / Dsp.RATE,

            )
            val entry = linkedMapOf<String, Any?>(
                "id" to case.id, "section" to case.section, "voice" to case.voice.name,
                "label" to case.label, "description" to case.description,
                "macros" to macros, "midi" to case.midi, "requestedHz" to Keys.midiHz(case.midi),
                "velocity" to case.velocity, "held" to held, "loop" to (held && case.repeatedCopies == 1),
                "memoryMode" to machine.holdMode.name, "repeatedCopies" to case.repeatedCopies,
                "rawPath" to rawPath, "matchedPath" to matchedPath, "file" to matchedPath,
                "frames" to samples.size, "seconds" to samples.size.toDouble() / Dsp.RATE,
                "renderMs" to renderMs, "rawExportGain" to exportGain,
                "rawMetrics" to nativeMetrics, "matchedMetrics" to metrics(matched),
                "diagnostics" to diagnosticValues, "seamError" to result.seamError,
                "boundaryError" to if (held) boundaryError(result.samples) else null,
                "recipeStatus" to "Provisional dry identity; owner listening review pending",
            )
            val forward = requireNotNull(result.forwardStem)
            val reverse = requireNotNull(result.reverseStem)
            val stems = linkedMapOf<String, Any?>()
            val stemClips = mutableListOf<JsonValue>()
            if (case.section in setOf("voices", "registers", "loops", "voice-comparison")) {
                val overlap = SpoolListeningMeasure.overlap(forward, reverse, d.gateSeconds.toDouble(), d.returnEndSeconds.toDouble())
                entry["overlap"] = overlap.values()
                for ((kind, stem) in listOf("forward" to forward, "reverse" to reverse)) {
                    val stemSnip = Snip(stem, 1, Dsp.RATE)
                    val values = linkedMapOf<String, Any?>("metrics" to metrics(stemSnip), "signalStage" to "actual pickup before shared contact coloration")
                    if (overlap.untilSeconds - overlap.fromSeconds > .21) values["tone"] =
                        SpoolListeningMeasure.tone(stem, overlap.fromSeconds, overlap.untilSeconds, Keys.midiHz(case.midi).toDouble()).values()
                    if (case.section == "voices") {
                        val stemRaw = "stems/${case.id}_$kind.wav"
                        val stemMatched = "stems/matched/${case.id}_$kind.wav"
                        WavWriter.write(File(root, stemRaw), stemSnip, WavWriter.BitDepth.PCM_16)
                        val stemLevelled = AuditionLevel.level(stemSnip)
                        WavWriter.write(File(root, stemMatched), stemLevelled, WavWriter.BitDepth.PCM_16)
                        values["rawPath"] = stemRaw; values["matchedPath"] = stemMatched
                        val derivative = LinkedHashMap(entry)
                        derivative["id"] = "stem_${case.id}_$kind"
                        derivative["section"] = "diagnostics"
                        derivative["label"] = "${case.label} · $kind pickup only"
                        derivative["description"] = "Actual $kind pickup from the same parent render, before shared contact coloration. The matched version is levelled for inspection; native audio retains its contribution level."
                        derivative["parentClipId"] = case.id
                        derivative["stemKind"] = kind
                        derivative["rawPath"] = stemRaw; derivative["matchedPath"] = stemMatched; derivative["file"] = stemMatched
                        derivative["rawMetrics"] = metrics(stemSnip); derivative["matchedMetrics"] = metrics(stemLevelled)
                        derivative["rawExportGain"] = 1f; derivative["signalStage"] = "precolor pickup"
                        stemClips += j(derivative)
                    }
                    stems[kind] = values
                }
                entry["stems"] = stems
                if (case.section != "loops") {
                    val wanted = Keys.midiHz(case.midi).toDouble()
                    fun profile(s: FloatArray): Map<String, Any?> {
                        val p = SpoolTimbreListeningMeasure.profile(s, wanted, .85, 1.25)
                        return mapOf("partialPower" to p.partialPower, "rootShare" to p.rootShare,
                            "centroidHz" to p.centroidHarmonic * wanted, "upperShare" to p.upperShare,
                            "oddUpperShare" to p.oddUpperShare, "evenShare" to p.evenShare)
                    }
                    entry["timbre"] = mapOf("fromSeconds" to .85, "untilSeconds" to 1.25,
                        "normalization" to "total measured harmonic power; independent of gain",
                        "mixed" to profile(samples), "forward" to profile(forward), "reverse" to profile(reverse))
                }
            }
            if (case.macro != null) entry["macro"] = case.macro
            if (case.section == "macros") entry["recipe"] = SpoolPatch(case.label, case.voice, macros, machine, case.velocity).toJsonValue()
            if (case.excerptReread) entry["auditionWindow"] = mapOf(
                "kind" to "finite second read after the causal write pass", "startFrame" to rereadStart,
                "endFrame" to rereadEnd, "performanceFrames" to result.samples.size,
                "description" to "An excerpt of the actual second read; trace times refer to the full performance.")
            if (case.value != null) entry["value"] = case.value
            if (case.groupKey != null) entry["groupKey"] = case.groupKey
            if (case.section in setOf("voices", "registers")) entry["forwardPitch"] = forwardPitch(samples, Keys.midiHz(case.midi))
            entries += j(entry)
            entries += stemClips
            println("Spool audition ${index + 1}/${cases.size}: ${case.id} (${"%.0f".format(Locale.ROOT, renderMs)} ms)")
        }
        verifySources()
        val kit = previous?.getValue("kit") ?: j(exportKit(root))
        verifySources()
        val patches = SpoolPresets.all().map { it.toJsonValue() }
        File(root, "patches.json").writeText(Json.write(JsonValue.Arr(patches)) + "\n")
        val manifest = linkedMapOf<String, Any?>(
            "engine" to "SPOOL", "title" to "Spool — a recording that returns", "generatedAt" to Instant.now().toString(),
            "sampleRate" to Dsp.RATE, "channels" to 1, "bitDepth" to 16,
            "memoryMode" to "FROZEN_SNAPSHOT", "controls" to (timbral + "HOLD"),
            "description" to "Forward and backward pickups overlap through original synthesized recordings. Clean layers preserve the requested pitch, while causal imprint carries neighboring traces. All audio is rendered by SnipSnap's Spool engine without external tape effects.",
            "holdDescription" to "HOLD prepares a finite deterministic imprint snapshot, freezes writes, then exports a repeating forward/return cycle. Memory does not continue accumulating during sample playback.",
            "exportDescription" to "The overlapping pickup motion and serialized gate are baked into each sample. Sampler pitch transposition changes gesture timing as well as pitch; playback-time release samples are not provided.",
            "levelDescription" to "Native dry files preserve measured machine level, with any PCM attenuation recorded per clip. Matched dry files share the repository's quiet audition loudness of 0.03 over the loudest 200 ms.",
            "provenance" to mapOf("sourceSha256" to sourceHash, "sourceFilesSha256" to sourceFileHashes,
                "renderer" to "com.snipsnap.synth.Spool.renderProbe", "internalRate" to Dsp.RATE * Dsp.OVERSAMPLE),
            "clips" to entries, "kit" to kit,
            "downloads" to mapOf("patches" to "patches.json"),
        )
        verifySources()
        File(root, "manifest.json").writeText(Json.write(j(manifest)) + "\n")
        println("Spool audition: ${entries.size} clips and a 16-pad MPC kit under ${root.absolutePath}")
    }

    private fun cases(quick: Boolean): List<Case> = buildList {
        for (voice in SpoolVoice.entries) {
            val id = voice.name.lowercase(Locale.ROOT)
            val preset = SpoolPresets.forVoice(voice).first()
            add(Case("voice_$id", "voices", voice, preset.name, descriptions.getValue(voice), preset.macros))
            for (midi in if (quick) listOf(48, 72) else listOf(48, 60, 72)) {
                add(Case("register_${id}_$midi", "registers", voice, "$voice · MIDI $midi",
                    "The same dry machine at ${mapOf(48 to "C3", 60 to "C4", 72 to "C5").getValue(midi)}. Compare its forward root separately from the slowing answer.", midi = midi))
            }
            for (velocity in if (quick) listOf(.35f) else listOf(.25f, .65f)) {
                add(Case("velocity_${id}_${(velocity * 100).toInt()}", "velocity", voice, "$voice · velocity $velocity",
                    "Velocity articulates the original source and transport contact; matched audio lets you compare the gesture at equal loudness.", velocity = velocity))
            }
        }
        val identityControls = mapOf("HEAD" to .2f, "STRAIN" to .1f, "RETURN" to .65f,
            "IMPRINT" to .15f, "SPLICE" to 0f, "HOLD" to 0f)
        for (midi in listOf(48, 60, 72)) for (voice in listOf(SpoolVoice.STRING, SpoolVoice.CHOIR, SpoolVoice.FLUTE, SpoolVoice.BRASS)) {
            val note = mapOf(48 to "C3", 60 to "C4", 72 to "C5").getValue(midi)
            add(Case("identity_${voice.name.lowercase(Locale.ROOT)}_$midi", "voice-comparison", voice,
                "$voice · same controls · $note", "Four source families at the same note, velocity and controls. Use Matched level to compare their harmonic color. " + descriptions.getValue(voice),
                identityControls, midi = midi, groupKey = "identity_$midi"))
        }
        val baseline = mapOf("HEAD" to .25f, "STRAIN" to .2f, "RETURN" to .6f, "IMPRINT" to .4f, "SPLICE" to .2f)
        val focused = mapOf("HEAD" to 0f, "STRAIN" to .1f, "RETURN" to 0f,
            "IMPRINT" to 0f, "SPLICE" to 0f, "HOLD" to 0f)
        fun isolated(voice: SpoolVoice) = SpoolMachine.forVoice(voice).let { machine ->
            machine.copy(roster = machine.roster.mapIndexed { i, strip -> strip.copy(gain = if (i == 0) 1f else 0f) })
        }
        val crossingHead = SpoolMachine.forVoice(SpoolVoice.PALIMPSEST).let { machine ->
            machine.copy(roster = machine.roster.mapIndexed { i, strip ->
                strip.copy(gain = listOf(.70f, .20f, .10f)[i])
            }, headOffsets = listOf(.12f, -.35f, .35f))
        }
        val imprintEncounter = isolated(SpoolVoice.CHOIR).copy(
            routes = listOf(SpoolRoute(2, 0, .55f)), reengage = true)
        val attackSplices = isolated(SpoolVoice.BRASS).copy(gateSeconds = 2.2f,
            spliceSchedule = listOf(SpoolSplice(.12f, -.12f), SpoolSplice(.27f, -.27f),
                SpoolSplice(.40f, -.40f), SpoolSplice(.55f, -.40f)))
        for (macro in timbral) for (value in if (quick) listOf(0f, .5f, 1f) else listOf(0f, .25f, .5f, .75f, 1f)) {
            val id = "sweep_${macro.lowercase(Locale.ROOT)}_${(value * 100).toInt()}"
            val demo = when (macro) {
                "HEAD" -> Case(id, "macros", SpoolVoice.PALIMPSEST, "$macro · $value",
                    "A bowed recording shares the pickup with choir and flute. Listen for the vowel and air becoming more prominent as HEAD crosses the recordings.",
                    focused + ("RETURN" to .6f), machine = crossingHead)
                "RETURN" -> Case(id, "macros", SpoolVoice.BRASS, "$macro · $value",
                    "A short brass note has a bright attack. Listen for that attack swelling backwards beneath its forward tone as RETURN rises.",
                    focused, machine = isolated(SpoolVoice.BRASS).copy(gateSeconds = .85f))
                "IMPRINT" -> Case(id, "macros", SpoolVoice.CHOIR, "$macro · $value",
                    "This is the choir recording's second read. Raising IMPRINT brings the inherited flute trace forward and retains more of its recorded detail. Compare each neighboring position.",
                    focused + ("RETURN" to 1f), machine = imprintEncounter, excerptReread = true)
                "SPLICE" -> Case(id, "macros", SpoolVoice.BRASS, "$macro · $value",
                    "A longer brass recording makes the cuts audible. Listen for its attack returning at new points as SPLICE reorders the recorded sections.",
                    focused, machine = attackSplices)
                else -> Case(id, "macros", SpoolVoice.STRING, "$macro · $value",
                    macroDescriptions.getValue(macro), baseline)
            }
            add(demo.copy(macros = demo.macros + (macro to value), macro = macro, value = value))
        }
        val pairs = listOf("HEAD" to "IMPRINT", "STRAIN" to "RETURN", "RETURN" to "IMPRINT", "HEAD" to "SPLICE", "STRAIN" to "SPLICE")
        for ((a, b) in pairs) for (av in if (quick) listOf(0f, 1f) else listOf(0f, .5f, 1f)) for (bv in if (quick) listOf(0f, 1f) else listOf(0f, .5f, 1f)) {
            val group = "${a.lowercase(Locale.ROOT)}_${b.lowercase(Locale.ROOT)}"
            add(Case("interaction_${group}_${(av * 100).toInt()}_${(bv * 100).toInt()}", "interactions", SpoolVoice.PALIMPSEST,
                "$a $av × $b $bv", "Listen for how ${a.lowercase(Locale.ROOT)} changes the encounter shaped by ${b.lowercase(Locale.ROOT)}.",
                baseline + (a to av) + (b to bv), groupKey = group))
        }
        for (voice in if (quick) listOf(SpoolVoice.STRING, SpoolVoice.CUT) else SpoolVoice.entries) {
            val id = voice.name.lowercase(Locale.ROOT)
            for (gate in listOf(.4f, 2.4f)) add(Case("gate_${id}_${(gate * 100).toInt()}", "gates", voice,
                "$voice · gate ${gate}s", "The spring answer reads only the recording distance actually traversed before this serialized gate.",
                mapOf("RETURN" to .85f, "IMPRINT" to .65f), gate = gate))
            add(Case("all_high_$id", "extremes", voice, "$voice · all five controls high",
                "A bounded experimental corner with the original root still present in the forward material.", timbral.associateWith { 1f }))
        }
        for (voice in listOf(SpoolVoice.STRING, SpoolVoice.PALIMPSEST, SpoolVoice.CUT)) {
            val id = voice.name.lowercase(Locale.ROOT)
            add(Case("moving_head_$id", "diagnostics", voice, "$voice · moving head reference",
                "The exact active counterpart of frozen head: same original recordings, macros, transport and writes, with diagonal pickup traversal enabled.",
                baseline + ("HEAD" to .9f), groupKey = "head_$id"))
            add(Case("frozen_head_$id", "diagnostics", voice, "$voice · frozen head",
                "Diagonal head traversal removed while strip transport, source recordings and writes remain active. Compare the moving-head reference.",
                baseline + ("HEAD" to .9f), frozenHead = true, groupKey = "head_$id"))
            add(Case("writes_enabled_$id", "diagnostics", voice, "$voice · imprint reference",
                "The exact active counterpart of writes disabled: compare the identical initial encounter and the changed inherited return/reread.",
                baseline + ("IMPRINT" to 1f), groupKey = "imprint_$id"))
            add(Case("no_writes_$id", "diagnostics", voice, "$voice · writes disabled",
                "The original immutable recordings and transport stay identical; inherited overlays are disabled. Compare the imprint reference.",
                baseline + ("IMPRINT" to 1f), writes = false, groupKey = "imprint_$id"))
        }
        add(Case("motor_load_reference", "diagnostics", SpoolVoice.STRING, "Shared motor · ordinary neighbor load",
            "A constrained motor with STRAIN 0.8. Compare the heavier neighboring strip without changing the dominant recording.",
            baseline + ("STRAIN" to .8f), motorTorque = .4f, groupKey = "motor_load"))
        add(Case("motor_load_heavier_neighbor", "diagnostics", SpoolVoice.STRING, "Shared motor · heavier neighbor",
            "Only the third strip's tension and drag increase. Its load changes the shared motor and the unchanged dominant strip's travel.",
            baseline + ("STRAIN" to .8f), motorTorque = .4f, companionLoad = true, groupKey = "motor_load"))
        for (voice in SpoolVoice.entries) {
            val id = voice.name.lowercase(Locale.ROOT)
            val macros = Spool.defaults(voice) + ("HOLD" to 1f) + ("RETURN" to .75f) + ("IMPRINT" to .8f)
            add(Case("loop_$id", "loops", voice, "$voice · frozen snapshot loop",
                "A finite imprint snapshot repeats a powered forward/return cycle with writes frozen for export.", macros))
            add(Case("repeat_$id", "repeated-loops", voice, "$voice · three consecutive cycles",
                "Three exact consecutive exported cycles expose any repeated seam or doubled event to listening.", macros, repeatedCopies = 3))
        }
    }

    private fun exportKit(root: File): Map<String, Any?> {
        val work = File(root, ".spool-kit-work")
        work.deleteRecursively()
        val kit = KitAssembler.assembleArranged("SnipSnap Spool Kit", SynthKits.spool(), work)
        val result = KitExporter.exportProgramFolder(kit, work, File(root, "kit"), overwrite = true)
        work.deleteRecursively()
        return mapOf("name" to kit.name, "pads" to 16, "directory" to "kit/${result.directory.name}",
            "format" to "MPC XPM program folder", "memoryMode" to "FROZEN_SNAPSHOT")
    }

    private fun metrics(snip: Snip): Map<String, Any?> {
        val s = snip.samples
        var energy = 0.0
        var peak = 0f
        var jump = 0f
        for (i in s.indices) {
            energy += s[i].toDouble() * s[i]
            peak = maxOf(peak, abs(s[i]))
            if (i > 0) jump = maxOf(jump, abs(s[i] - s[i - 1]))
        }
        return mapOf("peak" to peak, "rms" to sqrt(energy / s.size), "dc" to s.average(),
            "loudness" to Loudness.of(snip), "maxAdjacentStep" to jump,
            "endPeak" to s.takeLast(minOf(s.size, Dsp.RATE / 100)).maxOf { abs(it) })
    }

    /** Sample and slope at the wrap; the machine probe also supplies its cycle comparison metric. */
    private fun boundaryError(s: FloatArray): Double {
        val energy = s.sumOf { it.toDouble() * it } / s.size
        val step = s.first() - s.last().toDouble()
        val slope = (s[1] - s[0]) - (s.last() - s[s.lastIndex - 1]).toDouble()
        return (step * step + slope * slope) / energy.coerceAtLeast(1e-30)
    }

    private fun forwardPitch(s: FloatArray, wanted: Float): Map<String, Any?> {
        val first = (Dsp.RATE * .25f).toInt()
        val count = minOf((Dsp.RATE * .7f).toInt(), s.size - first)
        val n = 65536
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until count) re[i] = s[first + i] * (.5 - .5 * cos(2 * PI * i / (count - 1))).toFloat()
        Fft.forward(re, im)
        val power = DoubleArray(n / 2) { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
        val bin = Dsp.RATE.toDouble() / n
        val lo = (wanted * .65 / bin).toInt().coerceAtLeast(2)
        val hi = (wanted * 1.4 / bin).toInt().coerceAtMost(power.lastIndex - 1)
        val best = (lo..hi).maxBy { power[it] }
        val left = sqrt(power[best - 1]); val center = sqrt(power[best]); val right = sqrt(power[best + 1])
        val curve = left - 2 * center + right
        val offset = if (abs(curve) < 1e-20) 0.0 else (.5 * (left - right) / curve).coerceIn(-1.0, 1.0)
        val hz = (best + offset) * bin
        val root = ((wanted * .96 / bin).toInt()..(wanted * 1.04 / bin).toInt()).sumOf { power[it] }
        val total = ((40 / bin).toInt()..(15000 / bin).toInt()).sumOf { power[it] }
        return mapOf("hz" to hz, "cents" to 1200 * ln(hz / wanted) / ln(2.0), "rootPowerShare" to root / total.coerceAtLeast(1e-30),
            "fromSeconds" to .25, "toSeconds" to .95)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun j(v: Any?): JsonValue = when (v) {
        null -> JsonValue.Null
        is JsonValue -> v
        is String -> JsonValue.Str(v)
        is Number -> JsonValue.Num(v.toDouble().also { require(it.isFinite()) })
        is Boolean -> JsonValue.Bool(v)
        is Map<*, *> -> JsonValue.Obj(v.entries.associateTo(LinkedHashMap()) { it.key.toString() to j(it.value) })
        is Iterable<*> -> JsonValue.Arr(v.map(::j))
        else -> error("Unsupported manifest value ${v::class}")
    }
}
