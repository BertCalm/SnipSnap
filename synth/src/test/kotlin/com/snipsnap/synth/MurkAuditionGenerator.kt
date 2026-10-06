package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Dry MURK listening evidence, including the engineering-only causal switches. The generated
 * HTML contains its descriptions and manifest, so opening index.html directly works offline.
 * Run `./gradlew :synth:generateMurkAudition`; the output is deliberately outside source control.
 * No generated description constitutes a listening verdict.
 */
object MurkAuditionGenerator {
    private const val LISTENING_TARGET = .12f
    private const val LISTENING_CEILING = .90f
    private const val BASELINE_SOURCE_TARGET = .03f
    private val loopPlayback = linkedMapOf(
        "repeatByDefault" to true,
        "mainControls" to "Sample-accurate buffer looping with smooth start, pause and stop.",
        "singlePass" to "Repeat off previews one cycle with a smooth exit.",
        "loopWavs" to "Periodic WAV samples remain unchanged; playback ramps do not alter the loop boundary.",
    )
    private val holdPlaybackNotes = """
        HOLD cards repeat by default after you enable audio and press Play. The main controls
        decode the WAV once and repeat an audio buffer without seeking between cycles. Pause
        and Stop use a brief gain ramp. Turn Repeat off to preview one cycle with a smooth exit.
        Ramps apply only when starting or stopping playback; the repeating WAV remains unchanged.
        Native controls remain available as a fallback. Use the main controls over HTTP(S) for
        sample-accurate HOLD playback; local-file/native playback uses the browser's media player.
    """.trimIndent()
    private val steps = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    private val timbral = listOf("STRIKE", "TRUNK", "FOG", "AGITATION", "GROVE")
    private val voiceDescriptions = mapOf(
        MurkVoice.CLUNK to "Pitched bat-like wood, drawing on the earlier THWACK sound identified as the bat reference; a firm attack and a distant reply.",
        MurkVoice.THWACK to "A two-stage axe-like gesture: a brief 'thhh' contact lead-in followed by a distinct pitched 'wack'. This revision still needs listening review.",
        MurkVoice.FRONT to "Loaded wood pushes a broader traveling pressure front through the grove.",
        MurkVoice.HOOT to "Soft wood provides the cause; rounded root-related owl calls provide the answer.",
        MurkVoice.GROVE to "Staggered trunks and animal responses form a finite conversation.",
        MurkVoice.ALARM to "A hard gesture prompts faster barks and controlled, tonally anchored alarm calls.",
    )
    private val macroDescriptions = mapOf(
        "STRIKE" to "Broad bat-like contact becomes sharper axe-like contact. On THWACK, compare the lead-in and the pitched impact as well as the response they provoke.",
        "TRUNK" to "Lighter tight wood becomes a denser hollow resonating tree. The requested root should remain stable.",
        "FOG" to "Light loading and clear arrivals become heavier loading and broader traveling fronts. Listen to the first trunk as well as its returns.",
        "AGITATION" to "Quiet sparse replies become firmer, faster, rougher calls. Compare articulation and response latency.",
        "GROVE" to "Compact local coupling becomes more spaced arrivals and an extended conversation. Compare both nearby transfer and timing.",
    )
    private val presetDescriptions = mapOf(
        "HOLLOW BAT" to "The earlier THWACK bat character recast as CLUNK at C4, with restrained fog and a quiet distant response.",
        "DEEP TRUNK" to "Low C3 and a dense hollow trunk, with a soft gesture and restrained animal activity.",
        "HELD TREES" to "A recurring wood-led grove at G3, with spaced gestures and quiet replies.",
        "TONAL AXE" to "Revised C4 axe contact: a short 'thhh' lead-in resolves into a pitched 'wack', with relatively light atmospheric loading. Listening acceptance is pending.",
        "SOFT CHOP" to "A gentler high G4 version of the revised two-stage contact, through a denser trunk with restrained replies.",
        "FIRST PULSE" to "C4 wood drives a strong, broad pressure front into a spaced grove.",
        "HEAVY AIR" to "Low F3, dense wood and heavy loading favor broad arrivals and an extended response.",
        "DISTANT CALL" to "Soft C4 wood invites rounded calls across a wide grove.",
        "WARY SILENCE" to "High F4 with light contact and very low agitation; compare the sparse response with the continuing tonal wood.",
        "HELD OWLS" to "A recurring C4 grove with a stronger vocal presence and distinct replies between gestures.",
        "ANSWERING WOOD" to "C4 trunks and animal responses form a staggered conversation across a wide grove.",
        "CLOSE TREES" to "High E-flat4 wood in a compact grove, emphasizing nearby transfer rather than distant spacing.",
        "SOFT BARK" to "C4 with firmer contact and moderate alarm behavior; compare the shorter vocal articulation with HOOT.",
        "SHARP RETURN" to "Bright C5, sharp contact, heavy fog and high agitation challenge tonal anchoring and finite response limits.",
    )

    private data class Clip(val evidence: Map<String, Any?>, val group: String) {
        val id get() = evidence.getValue("id") as String
        val category get() = evidence.getValue("category") as String
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val positional = args.filterNot { it.startsWith("--") }
        require(positional.size <= 1) { "supply one output directory, then --baseline-dir= and --baseline-revision=" }
        require(args.filter { it.startsWith("--") }.all {
            it.startsWith("--baseline-dir=") || it.startsWith("--baseline-revision=") || it == "--refresh-page"
        }) { "unknown MURK audition option" }
        fun argument(prefix: String): String? = args.filter { it.startsWith(prefix) }.let {
            require(it.size <= 1) { "duplicate $prefix argument" }
            it.singleOrNull()?.removePrefix(prefix)?.also { value -> require(value.isNotBlank()) }
        }
        val root = File(positional.firstOrNull() ?: "../testkit/murk-audition")
        if ("--refresh-page" in args) {
            require(args.count { it == "--refresh-page" } == 1) { "duplicate --refresh-page argument" }
            require(args.none { it.startsWith("--baseline-") }) { "page refresh uses the existing manifest's baseline" }
            val original = Json.parse(File(root, "manifest.json").readText()).obj()
            require(original.getValue("engine").str() == "MURK") { "page refresh requires a MURK manifest" }
            require(kotlin.math.abs((original["listeningTarget"]?.num() ?: 0.0) - LISTENING_TARGET) < 1e-6) { "regenerate the current listening library before refreshing its page" }
            val clips = original.getValue("clips").arr().map { row ->
                val evidence = row.obj().mapValues { fromJson(it.value) }
                for (key in listOf("path", "eventPath")) {
                    val asset = File(root, evidence.getValue(key) as String).canonicalFile
                    require(asset.toPath().startsWith(root.canonicalFile.toPath()) && asset.isFile) { "missing or unsafe $key" }
                }
                Clip(evidence, evidence.getValue("group") as String)
            }
            require(original.getValue("clipCount").int() == clips.size && clips.isNotEmpty()) { "manifest clip count is inconsistent" }
            val manifest = original.toMutableMap()
            manifest["loopPlayback"] = json(loopPlayback)
            val manifestText = Json.write(JsonValue.Obj(manifest)) + "\n"
            File(root, "manifest.json").writeText(manifestText)
            writePage(root, clips, manifestText, original["comparison"] != null && original["comparison"] != JsonValue.Null)
            val notes = File(root, "README.md")
            val heading = "## HOLD playback"
            val existing = notes.takeIf { it.isFile }?.readText().orEmpty()
            notes.writeText(existing.substringBefore("\n$heading").trimEnd() + "\n\n$heading\n\n$holdPlaybackNotes\n")
            println("refreshed ${clips.size} MURK audition cards and playback controls; WAVs and event records unchanged")
            return
        }
        val baselineDir = argument("--baseline-dir=")?.let(::File)
        val baselineRevision = argument("--baseline-revision=")
        require((baselineDir == null) == (baselineRevision == null)) { "baseline directory and full source revision must be supplied together" }
        require(baselineRevision == null || baselineRevision.matches(Regex("[0-9a-f]{40}"))) { "baseline revision must be the full 40-character Git commit" }
        require(baselineDir == null || baselineDir.canonicalFile != root.canonicalFile) { "preserve the baseline in a separate directory before rendering" }
        val baselineManifest = baselineDir?.let { Json.parse(File(it, "manifest.json").readText()).obj() }
        require(baselineManifest == null || baselineManifest.getValue("engine").str() == "MURK") { "baseline manifest must describe MURK" }
        val baselineRows = baselineManifest?.getValue("clips")?.arr()?.associate { row ->
            val fields = row.obj()
            fields.getValue("id").str() to fields
        }.orEmpty()
        root.mkdirs()
        val clips = mutableListOf<Clip>()
        var totalRenderNanos = 0L
        var peakObservedHeap = 0L
        val started = System.nanoTime()

        fun observeHeap() {
            val runtime = Runtime.getRuntime()
            peakObservedHeap = maxOf(peakObservedHeap, runtime.totalMemory() - runtime.freeMemory())
        }

        fun render(
            id: String,
            title: String,
            description: String,
            category: String,
            group: String,
            voice: MurkVoice = MurkVoice.HOOT,
            macros: Map<String, Float> = Murk.defaults(voice),
            energy: Float = 1f,
            options: Murk.ProbeOptions = Murk.ProbeOptions(),
            branch: String = "full",
            matched: Boolean = true,
            existing: Murk.Probe? = null,
        ): Murk.Probe {
            val before = System.nanoTime()
            observeHeap()
            val probe = existing ?: Murk.probe(voice, macros, energy = energy, options = options)
            val nanos = if (existing == null) System.nanoTime() - before else 0L
            totalRenderNanos += nanos
            observeHeap()
            val samples = when (branch) {
                "direct-trunks" -> probe.directTrunks
                "reexcited-trunks" -> probe.reexcitedTrunks
                "atmosphere" -> probe.atmosphere
                "owls" -> probe.owls
                "owl-fog-input" -> probe.owlFogInput
                else -> probe.samples
            }
            require(samples.isNotEmpty() && samples.all { it.isFinite() }) { "$id has invalid samples" }
            val raw = Snip(samples, channels = 1, sampleRate = probe.sampleRate)
            // Raw comparisons must not be silently normalized or clipped by the WAV conversion.
            require(matched || raw.peak() <= 1f) { "$id raw peak ${raw.peak()} exceeds PCM range" }
            val output = when {
                !matched -> raw
                branch == "full" -> listeningLevel(raw)
                else -> {
                    // Source taps inherit the full reference's gain. Matching each quiet owl or
                    // fog tap independently would conceal its actual balance against the wood.
                    val reference = Snip(probe.samples, channels = 1, sampleRate = probe.sampleRate)
                    val gain = Loudness.of(listeningLevel(reference)) / Loudness.of(reference).coerceAtLeast(1e-9f)
                    Snip(raw.samples.map { it * gain }.toFloatArray(), channels = 1, sampleRate = probe.sampleRate)
                }
            }
            require(output.peak() <= if (matched) LISTENING_CEILING else 1f) { "$id export peak ${output.peak()} exceeds its ceiling" }
            val path = "clips/$id${if (matched) "_listen_012" else ""}.wav"
            WavWriter.write(File(root, path), output, WavWriter.BitDepth.PCM_16)
            val isLoop = (macros["HOLD"] ?: 0f) >= Murk.LOOP_THRESHOLD
            val events = linkedMapOf<String, Any?>(
                "id" to id,
                "arrivals" to probe.arrivals.map { event ->
                    linkedMapOf("timeSeconds" to event.timeSeconds, "destination" to event.destination,
                        "strength" to event.strength, "origin" to event.origin,
                        "generation" to event.generation, "fromCall" to event.fromCall)
                },
                "calls" to probe.calls.map { event ->
                    linkedMapOf("timeSeconds" to event.timeSeconds, "tree" to event.tree,
                        "agitation" to event.agitation, "strength" to event.strength,
                        "generation" to event.generation, "remainingBudget" to event.remainingBudget,
                        "stimulusTimeSeconds" to event.stimulusTimeSeconds)
                },
                "gestures" to probe.gestures.map { event ->
                    linkedMapOf("timeSeconds" to event.timeSeconds, "tree" to event.tree, "strength" to event.strength)
                },
                "stateSnapshots" to probe.stateSnapshots.map { state ->
                    linkedMapOf("timeSeconds" to state.timeSeconds, "agitation" to state.agitation.toList(),
                        "remainingBudget" to state.remainingBudget, "remainingVocalEnergy" to state.remainingVocalEnergy,
                        "passiveEnergy" to state.passiveEnergy)
                },
            )
            val eventPath = "events/$id.json"
            File(root, eventPath).apply { parentFile.mkdirs(); writeText(Json.write(json(events)) + "\n") }
            val firstCall = probe.calls.minOfOrNull { it.timeSeconds }
            val firstArrival = probe.arrivals.minOfOrNull { it.timeSeconds }
            clips += Clip(linkedMapOf(
                "id" to id, "title" to title, "description" to description,
                "category" to category, "voice" to voice.name, "group" to group,
                "path" to path, "eventPath" to eventPath,
                "macros" to macros, "energy" to energy, "branch" to branch,
                "level" to if (!matched) "raw" else if (branch == "full") "matched" else "reference gain", "loop" to isLoop,
                "durationSeconds" to output.durationSeconds,
                "sampleRate" to probe.sampleRate, "rawPeak" to raw.peak(), "exportPeak" to output.peak(),
                "rawLoudness" to Loudness.of(raw), "exportLoudness" to Loudness.of(output),
                "renderMilliseconds" to nanos / 1_000_000.0,
                "arrivalCount" to probe.arrivals.size, "callCount" to probe.calls.size,
                "gestureCount" to probe.gestures.size,
                "firstArrivalSeconds" to firstArrival, "firstCallSeconds" to firstCall,
                "finalPassiveEnergy" to probe.finalPassiveEnergy,
                "seamError" to if (isLoop) probe.seamError else null,
                "options" to linkedMapOf("linksEnabled" to options.linksEnabled,
                    "owlsEnabled" to options.owlsEnabled, "owlToFogEnabled" to options.owlToFogEnabled,
                    "owlAudioEnabled" to options.owlAudioEnabled,
                    "primaryStrikeEnabled" to options.primaryStrikeEnabled,
                    "isolatedCall" to options.isolatedCall, "normalize" to options.normalize,
                    "durationSeconds" to options.durationSeconds, "seedContext" to options.seedContext),
            ), group)
            if (clips.size % 25 == 0) println("rendered ${clips.size} MURK audition clips")
            return probe
        }

        fun importBaseline(voice: MurkVoice, register: String, note: String, tune: Float) {
            val sourceId = "${voice.name.lowercase()}_$register"
            val source = baselineRows[sourceId] ?: error("baseline has no $sourceId clip")
            require(source.getValue("voice").str() == voice.name && source.getValue("level").str() == "matched"
                && !source.getValue("loop").bool() && source.getValue("branch").str() == "full") { "$sourceId is not a matched finite full-voice baseline" }
            require(kotlin.math.abs(source.getValue("macros").obj().getValue("TUNE").num() - tune) < 1e-6) { "$sourceId has the wrong note" }
            val preserved = requireNotNull(baselineDir)
            fun baselineFile(path: String): File {
                val file = File(preserved, path).canonicalFile
                require(file.toPath().startsWith(preserved.canonicalFile.toPath()) && file.isFile) { "baseline asset is missing or outside its directory: $path" }
                return file
            }
            val sourceWav = baselineFile(source.getValue("path").str())
            val baselineAudio = WavReader.read(sourceWav)
            require(baselineAudio.channels == 1 && baselineAudio.sampleRate == 44_100 && baselineAudio.samples.all { it.isFinite() }) { "$sourceId is not finite mono 44.1 kHz audio" }
            val sourceLoudness = Loudness.of(baselineAudio)
            require(kotlin.math.abs(sourceLoudness - BASELINE_SOURCE_TARGET) < .0001f) { "$sourceId is not at the original baseline audition level" }
            val playbackAudio = listeningLevel(baselineAudio)
            val playbackGain = Loudness.of(playbackAudio) / sourceLoudness.coerceAtLeast(1e-9f)
            val id = "comparison_before_$sourceId"
            val path = "clips/${id}_listen_012.wav"
            WavWriter.write(File(root, path), playbackAudio, WavWriter.BitDepth.PCM_16)
            val sourceEvent = baselineFile(source.getValue("eventPath").str())
            val events = Json.parse(sourceEvent.readText()).obj().toMutableMap()
            events["id"] = JsonValue.Str(id)
            events["baselineSourceClipId"] = JsonValue.Str(sourceId)
            events["baselineSourceRevision"] = JsonValue.Str(requireNotNull(baselineRevision))
            val eventPath = "events/$id.json"
            File(root, eventPath).also { it.parentFile.mkdirs(); it.writeText(Json.write(JsonValue.Obj(events)) + "\n") }
            val originalEvidence = source.mapValues { fromJson(it.value) }
            val description = if (voice == MurkVoice.THWACK)
                "Earlier THWACK at $note: the owner described this as the imagined bat sound. Its preserved source is raised with linear listening gain to match the revised clips; compare the revised THWACK's 'thhh' lead-in and pitched 'wack'."
            else "Earlier CLUNK at $note, preserved before the contact revision and raised with linear listening gain. Compare its attack with the revised CLUNK, which draws on the earlier THWACK bat reference."
            val evidence = originalEvidence + linkedMapOf(
                "id" to id, "title" to "Before · ${voice.name} · $note", "description" to description,
                "category" to "comparison", "group" to "${voice.name} · $note",
                "path" to path, "eventPath" to eventPath, "comparisonVersion" to "before",
                "baselineSourceRevision" to baselineRevision, "baselineSourceClipId" to sourceId,
                "baselineSourceAudioSha256" to sha256(sourceWav),
                "baselineSourceEventSha256" to sha256(sourceEvent),
                "baselineSourceLevel" to BASELINE_SOURCE_TARGET,
                "baselineSourceLoudness" to sourceLoudness, "baselineSourcePeak" to baselineAudio.peak(),
                "baselinePlaybackGain" to playbackGain,
                "exportPeak" to playbackAudio.peak(), "exportLoudness" to Loudness.of(playbackAudio),
                "renderMilliseconds" to 0,
            )
            clips += Clip(evidence, "${voice.name} · $note")
        }

        for (voice in MurkVoice.entries) {
            for ((tune, register) in listOf(0f to "low", 0.5f to "middle", 1f to "high")) {
                val note = noteName(Murk.midiFor(voice, tune))
                val noteMacros = Murk.defaults(voice) + ("TUNE" to tune)
                val probe = render("${voice.name.lowercase()}_$register", "${voice.name} · $note · $register",
                    "${voiceDescriptions.getValue(voice)} TUNE ${fmt(tune)}; the other controls use this voice's defaults.",
                    "voices", voice.name, voice, noteMacros)
                if (baselineDir != null && voice in listOf(MurkVoice.CLUNK, MurkVoice.THWACK)) {
                    importBaseline(voice, register, note, tune)
                    render("comparison_after_${voice.name.lowercase()}_$register", "After · ${voice.name} · $note",
                        if (voice == MurkVoice.CLUNK)
                            "Revised CLUNK at $note: the earlier THWACK bat reference now informs this voice. Compare its pitched contact with both Before CLUNK and Before THWACK. This revised sound still needs listening review."
                        else "Revised THWACK at $note: a short 'thhh' lead-in followed by a distinct pitched 'wack'. Compare that two-stage gesture with the earlier bat-like THWACK. This revision still needs listening review.",
                        "comparison", "${voice.name} · $note", voice, noteMacros, existing = probe)
                    val after = clips.last()
                    clips[clips.lastIndex] = after.copy(evidence = after.evidence + mapOf(
                        "comparisonVersion" to "after", "comparisonBaselineRevision" to baselineRevision,
                        "currentSourceClipId" to "${voice.name.lowercase()}_$register"))
                }
            }
        }

        for (preset in MurkPresets.all()) {
            val held = (preset.macros["HOLD"] ?: 0f) >= Murk.LOOP_THRESHOLD
            val note = noteName(Murk.midiFor(preset.voice, preset.macros.getValue("TUNE")))
            render("preset_" + preset.name.lowercase().replace(' ', '_'), "${preset.name} · $note",
                presetDescriptions.getValue(preset.name) + if (held) " This is a settled loop: the initial isolated strike is excluded, and Repeat starts off." else " This factory recipe is a finite dry render.",
                "presets", preset.voice.name, preset.voice, preset.macros)
        }

        for (voice in MurkVoice.entries) {
            for ((energy, label) in listOf(0.3f to "quiet", 0.65f to "medium", 1f to "strong")) {
                render("${voice.name.lowercase()}_energy_${tag(energy)}", "${voice.name} · $label event",
                    "Host velocity ${fmt(energy)} changes event energy, pressure-front strength and the responses it can provoke. STRIKE remains at the voice default. Playback is matched in loudness so response changes remain comparable.",
                    "energy", voice.name, voice, energy = energy)
            }
        }

        for (voice in MurkVoice.entries) {
            val neutral = Murk.macrosFor(voice).associate { it.name to it.neutral }
            for (macro in timbral) for (step in steps) {
                render("${voice.name.lowercase()}_${macro.lowercase()}_${tag(step)}", "${voice.name} · $macro ${fmt(step)}",
                    "${macroDescriptions.getValue(macro)} All companion controls are at their declared neutral values: ${macroLine(neutral.filterKeys { it != macro })}.",
                    "sweeps", "${voice.name} · $macro", voice, neutral + (macro to step))
            }
        }

        val neutral = Murk.macrosFor(MurkVoice.HOOT).associate { it.name to it.neutral }
        val interactions = listOf("STRIKE" to "FOG", "TRUNK" to "FOG", "FOG" to "GROVE",
            "GROVE" to "AGITATION", "STRIKE" to "AGITATION")
        for ((a, b) in interactions) for (va in listOf(0f, 0.5f, 1f)) for (vb in listOf(0f, 0.5f, 1f)) {
            render("grid_${a.lowercase()}_${tag(va)}_${b.lowercase()}_${tag(vb)}", "$a ${fmt(va)} × $b ${fmt(vb)}",
                "HOOT interaction grid. Compare this corner with its row and column: the remaining controls use declared neutral values. A useful interaction changes timing, loading, transfer or call character as well as level.",
                "grids", "$a × $b", macros = neutral + (a to va) + (b to vb))
        }

        for (voice in MurkVoice.entries) {
            val raw = render("${voice.name.lowercase()}_raw", "${voice.name} · raw synthesis",
                "Dry synthesis before engine loudness targeting or audition matching. Its original amplitude is preserved; compare the matched partner's balance and quiet details.",
                "levels", voice.name, voice, matched = false)
            render("${voice.name.lowercase()}_matched", "${voice.name} · matched synthesis",
                "The exact same synthesis as the raw partner, raised to MURK's listening level: loudest 200 ms RMS 0.12, with a 0.90 peak ceiling. It has no added effects.",
                "levels", voice.name, voice, existing = raw)
        }

        val diagnostic = Murk.defaults(MurkVoice.HOOT) + mapOf("STRIKE" to 0.7f, "AGITATION" to 0.7f, "GROVE" to 0.75f)
        val noOwls = Murk.ProbeOptions(owlsEnabled = false)
        val full = render("diagnostic_full", "Connected ecosystem",
            "Reference for the diagnostic switches: one wood gesture, finite traveling arrivals and bounded vocal replies. The event file records the causal order.",
            "diagnostics", "Causal switches", macros = diagnostic)
        val passive = render("diagnostic_no_owls", "No owls · passive wood and fog",
            "Owls and their fog injection are disabled. Wood, atmospheric transport and returning trunk excitation remain; their ringdown must be finite.",
            "diagnostics", "Causal switches", macros = diagnostic, options = noOwls)
        render("diagnostic_direct", "Direct trunks alone",
            "Direct pitched wood from the no-owl reference. FOG still loads the original trunk. This tap separates trunk tuning from bending calls and later arrivals.",
            "diagnostics", "Separate the sources", macros = diagnostic, options = noOwls, branch = "direct-trunks", existing = passive)
        render("diagnostic_returns", "Re-excited trunks alone",
            "Trunks awakened by arriving and returning energy. The direct strike and owl audio are absent from this tap.",
            "diagnostics", "Separate the sources", macros = diagnostic, options = noOwls, branch = "reexcited-trunks", existing = passive)
        render("diagnostic_atmosphere", "Atmosphere audio alone",
            "Delayed, damped acoustic travel from the no-owl reference. Slow pressure envelopes are behavior diagnostics rather than additional audio oscillators.",
            "diagnostics", "Separate the sources", macros = diagnostic, options = noOwls, branch = "atmosphere", existing = passive)
        render("diagnostic_owl_audio", "Owl audio alone",
            "Synthesized calls extracted from the connected reference. Listen for rounded hoots, distinct boundaries and root-related tonal anchors.",
            "diagnostics", "Separate the sources", macros = diagnostic, branch = "owls", existing = full)
        render("diagnostic_owl_fog_input", "Owl-generated fog input alone",
            "The quieter vocal signal injected into the atmosphere. This tap distinguishes calls disturbing the shared medium from a separate wildlife layer.",
            "diagnostics", "Separate the sources", macros = diagnostic, branch = "owl-fog-input", existing = full)
        render("diagnostic_call_fog_off", "Owl-to-fog coupling off",
            "Direct calls remain enabled, but they cannot inject new fog energy. Compare secondary arrivals and answers with Connected ecosystem.",
            "diagnostics", "Causal switches", macros = diagnostic, options = Murk.ProbeOptions(owlToFogEnabled = false))
        render("diagnostic_owl_muted", "Owl audio muted · vocal fog input retained",
            "Calls still occur and disturb the fog, but their direct audio is muted. This deliberately differs from disabling owls or disabling their fog injection.",
            "diagnostics", "Causal switches", macros = diagnostic, options = Murk.ProbeOptions(owlAudioEnabled = false))
        render("diagnostic_links_off", "Travel links off",
            "Direct contact and local replies remain. Turning off grove links removes transported arrivals and their corresponding distant responses.",
            "diagnostics", "Causal switches", macros = diagnostic, options = Murk.ProbeOptions(linksEnabled = false))
        render("diagnostic_passive_ringdown", "Long passive ringdown",
            "One initial wood impulse, then no repeated performers or owl sources. The passive tree-and-fog network is observed for the full maximum duration; inspect the final energy and listen for decay rather than growth.",
            "diagnostics", "Stability and edge cases", macros = diagnostic + ("FOG" to 1f) + ("GROVE" to 1f),
            options = Murk.ProbeOptions(owlsEnabled = false, durationSeconds = Murk.MAX_SECONDS))
        render("diagnostic_isolated_call", "Isolated synthesized call",
            "No primary wood strike. An explicit diagnostic call excites the owl branch and the medium, revealing the voice and its causal downstream responses.",
            "diagnostics", "Separate the sources", macros = diagnostic,
            options = Murk.ProbeOptions(primaryStrikeEnabled = false, isolatedCall = true))
        render("diagnostic_long_response", "Long finite conversation",
            "A wide grove with heavy fog and high agitation. Late replies must remain audible where present, while budgets and attenuation bring the one-shot to an end.",
            "diagnostics", "Stability and edge cases", macros = diagnostic + mapOf("FOG" to 0.9f, "GROVE" to 1f, "AGITATION" to 0.85f),
            options = Murk.ProbeOptions(durationSeconds = Murk.MAX_SECONDS))
        for (voice in MurkVoice.entries) {
            render("${voice.name.lowercase()}_all_high", "${voice.name} · all timbral controls high",
                "All five timbral macros at 1, HOLD off and TUNE centered. Check bounded responses, anchored pitch and a finite tail in this extreme one-shot.",
                "diagnostics", "All-high extremes", voice, Murk.defaults(voice) + timbral.associateWith { 1f })
        }
        render("diagnostic_low_clunk", "CLUNK · low-register edge", "Lowest supported CLUNK with a dense trunk. The low tonal tail should remain useful for melodic routing.",
            "diagnostics", "Stability and edge cases", MurkVoice.CLUNK, Murk.defaults(MurkVoice.CLUNK) + mapOf("TUNE" to 0f, "TRUNK" to 1f))
        render("diagnostic_high_thwack", "THWACK · high-register edge", "Highest supported THWACK with sharp contact. Its tonal root should survive the bright transient.",
            "diagnostics", "Stability and edge cases", MurkVoice.THWACK, Murk.defaults(MurkVoice.THWACK) + mapOf("TUNE" to 1f, "STRIKE" to 1f))

        for (voice in MurkVoice.entries) {
            render("${voice.name.lowercase()}_hold", "${voice.name} · settled HOLD",
                "A settled recurring grove with explicit periodic performer gestures. The initial isolated strike is excluded because the host supplies one loop buffer. Enable Repeat to examine the seam and event timing.",
                "hold", "Default recurring groves", voice, Murk.defaults(voice) + ("HOLD" to 1f))
            render("${voice.name.lowercase()}_hold_extreme", "${voice.name} · difficult HOLD",
                "All timbral controls high, widest grove and HOLD 1. Performers supply recurring energy; fog circulation must remain bounded, and calls must remain discrete across the wrap.",
                "hold", "Difficult recurring groves", voice, Murk.defaults(voice) + timbral.associateWith { 1f } + ("HOLD" to 1f))
        }
        for (step in steps) {
            render("hoot_hold_${tag(step)}", "HOOT · HOLD ${fmt(step)}",
                if (step >= Murk.LOOP_THRESHOLD) "The loop threshold returns settled recurring activity; the initial isolated strike is excluded."
                else "A finite render below the 0.99 loop threshold. Higher intermediate values add a bounded number of performer gestures; the final step enables settled recurring activity.",
                "hold", "HOLD transition", macros = neutral + ("HOLD" to step))
        }

        val manifest = linkedMapOf<String, Any?>(
            "engine" to "MURK", "formatVersion" to 1,
            "description" to "Dry audition evidence. Text describes intended behavior; owner listening acceptance is pending.",
            "clipCount" to clips.size, "audioStartsOff" to true,
            "loudness" to "Matched full clips use MURK listening level: loudest 200 ms RMS 0.12 with a 0.90 peak ceiling. Source taps inherit their full reference's gain; raw partners retain synthesis amplitude.",
            "listeningTarget" to LISTENING_TARGET, "listeningPeakCeiling" to LISTENING_CEILING,
            "defaultPlaybackVolume" to .85f,
            "loopPlayback" to loopPlayback,
            "noteRange" to "C3–C5", "velocityContract" to "Existing host velocity scales event energy; STRIKE remains contact character.",
            "renderMilliseconds" to totalRenderNanos / 1_000_000.0,
            "elapsedMilliseconds" to (System.nanoTime() - started) / 1_000_000.0,
            "peakObservedUsedHeapBytes" to peakObservedHeap,
            "memoryMeasurement" to "JVM used-heap observations before and after renders; includes retained JVM allocations and excludes native memory.",
            "comparison" to baselineDir?.let { linkedMapOf(
                "baselineSourceRevision" to baselineRevision, "clipCount" to 12,
                "baselineManifestSha256" to sha256(File(it, "manifest.json")),
                "baselineAudio" to "Six preserved source WAVs at original level 0.03, exported with linear gain to the same 0.12 listening target as current clips. Original revision, recipe and source hashes remain recorded per clip.",
                "currentAudio" to "Six matched copies of the current CLUNK and THWACK note renders; listening acceptance remains pending.") },
            "clips" to clips.map { it.evidence },
        )
        val manifestText = Json.write(json(manifest)) + "\n"
        File(root, "manifest.json").writeText(manifestText)
        writePage(root, clips, manifestText, baselineDir != null)
        val comparisonNotes = if (baselineDir == null) "" else """

            ## Reproduce the contact comparison

            The six Before sources are preserved from source revision `$baselineRevision`.
            Their original loudest-200-ms RMS 0.03 is validated, then a linear listening gain
            brings them to the same RMS 0.12 target and 0.90 peak ceiling as the After clips.
            Every baseline card records the original revision, recipe, source WAV/event SHA-256,
            source level and applied gain. After cards reuse the current note render.
            Earlier THWACK is the owner's bat reference; revised CLUNK draws on it, while revised
            THWACK aims for a distinct lead-in and impact. The owner has not accepted the revision.

            To rebuild the baseline, check out `$baselineRevision` separately and run
            `./gradlew :synth:generateMurkAudition` there. Preserve its `testkit/murk-audition`
            directory, then render the current checkout with
            `./gradlew :synth:generateMurkAudition -PmurkBaselineDir=/absolute/path/to/preserved-baseline -PmurkBaselineRevision=$baselineRevision`.
            A baseline directory must include its original manifest and the six CLUNK/THWACK
            low/middle/high WAVs and event JSON. It must differ from the current output directory.
            Without those optional arguments the ordinary audition contains no comparison section.
        """.trimIndent()
        File(root, "README.md").writeText("""
            # MURK audition

            Open `index.html` directly in a browser. All assets are relative and the descriptions
            and manifest are embedded: no web server, external fonts or network access are needed.
            Audio starts off. Enable playback, then choose Play; Stop all or Escape stops playback.
            The page's volume starts at 85%.
            Every clip also has native audio controls and a WAV download. A single clip plays at a
            time. Voice, section and text filters support reading before listening.

            Rebuild with `./gradlew :synth:generateMurkAudition` from the repository root.
            This set contains ${clips.size} dry mono PCM-16 WAV clips at 44.1 kHz. It covers all six
            voices, C3/C4/C5, fourteen factory presets, quiet/medium/strong event energy, all five macro sweeps on every voice,
            five 3×3 interaction grids, raw/matched pairs, causal switches, source taps, edge cases
            and representative and extreme HOLD loops. No effects are included.

            Matched full clips use MURK's loudest-200-ms RMS 0.12 with a 0.90 peak ceiling; source taps use
            their full reference's gain to preserve relative balance, and raw partners retain
            their original amplitude. The manifest records measured peaks, loudness, timing,
            event counts, passive energy, loop seam evidence and render costs. Per-clip event JSON
            records gestures, acoustic arrivals and scheduled calls with origin/generation tags.
            Heap measurements are JVM observations, not process peak RSS or allocation counts.

            Descriptions express expected behavior. Numerical checks and generated descriptions
            do not replace the owner's listening verdict; sonic acceptance remains pending.
        """.trimIndent() + "\n" + comparisonNotes + "\n\n## HOLD playback\n\n$holdPlaybackNotes\n")
        println("wrote ${clips.size} clips, event records, manifest and accessible index.html under ${root.absolutePath}")
        println("render time ${number(totalRenderNanos / 1_000_000_000.0)} s; observed JVM used heap ${number(peakObservedHeap / 1048576.0)} MiB")
    }

    private val categories = linkedMapOf(
        "comparison" to ("CLUNK and THWACK: before / after" to "Earlier THWACK was identified as the imagined bat sound. Revised CLUNK draws on that reference; revised THWACK adds a 'thhh' lead-in before its pitched 'wack'. Compare both voices at C3, C4 and C5 at the same listening level. Listening acceptance of the revision is pending."),
        "voices" to ("Six voices, three registers" to "Begin with each voice's defaults at C3, C4 and C5. Each sound begins with pitched wood, travels through the grove and can prompt a finite vocal reply."),
        "presets" to ("Fourteen factory sounds" to "The named recipes available in SnipSnap, rendered dry with their exact saved controls. These descriptions are intended characters; the factory roster still needs listening sign-off."),
        "energy" to ("Quiet, medium and strong events" to "Event energy uses the existing host velocity contract. Loudness matching helps expose changes in propagation and behavior."),
        "sweeps" to ("Macro sweeps on every voice" to "Each timbral control is sampled at 0, 0.25, 0.5, 0.75 and 1; its companions use their declared neutral values."),
        "grids" to ("Five interaction grids" to "HOOT at neutral companions. Each 3 × 3 grid tests a required pair of controls at 0, 0.5 and 1."),
        "levels" to ("Raw and matched pairs" to "The same dry synthesis appears twice. Raw preserves its original amplitude; matched uses MURK's RMS 0.12 listening target with a 0.90 peak ceiling."),
        "diagnostics" to ("Causal probes and edge cases" to "Engineering switches and separate source taps help explain how a gesture becomes a finite ecosystem response. These switches are absent from the instrument's public controls."),
        "hold" to ("Recurring groves and HOLD transition" to "HOLD loops repeat continuously after you enable audio and press Play. Pause and Stop fade out smoothly. Turn Repeat off to preview one cycle with a smooth ending; the downloadable WAV keeps the complete repeating boundary."),
    )

    private fun writePage(root: File, clips: List<Clip>, manifestText: String, hasComparison: Boolean) {
        val template = javaClass.getResourceAsStream("/audition/murk-audition.html")
            ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("MURK audition page resource is missing")
        require(template.contains("<!-- MURK_CARDS -->") && template.contains("<!-- MURK_MANIFEST -->"))
        File(root, "index.html").writeText(template
            .replace("<!-- MURK_CARDS -->", sectionsHtml(clips))
            .replace("<!-- MURK_COMPARISON_NOTE -->", if (!hasComparison) "" else "<p class=\"comparison-note\"><a href=\"#comparison\">Start with the CLUNK / THWACK before-and-after comparison</a> · Earlier THWACK is the bat reference; revised THWACK aims for a 'thhh — wack' gesture. Listening review is pending.</p>")
            .replace("<!-- MURK_COMPARISON_FILTER -->", if (!hasComparison) "" else "<option value=\"comparison\">Before / after contact revision</option>")
            .replace("<!-- MURK_COMPARISON_NAV -->", if (!hasComparison) "" else "<li><a href=\"#comparison\">Before / after</a></li>")
            .replace("<!-- MURK_MANIFEST -->", "<script type=\"application/json\" id=\"murk-manifest\">${manifestText.replace("<", "\\u003c")}</script>"))
    }

    private fun sectionsHtml(clips: List<Clip>): String = buildString {
        for ((category, copy) in categories) {
            if (clips.none { it.category == category }) continue
            append("<section class=\"audition-section\" id=\"$category\" aria-labelledby=\"heading-$category\"><div class=\"section-heading\"><p class=\"eyebrow\">${h(category)}</p><h2 id=\"heading-$category\">${h(copy.first)}</h2><p>${h(copy.second)}</p></div>")
            for ((group, members) in clips.filter { it.category == category }.groupBy { it.group }) {
                append("<div class=\"clip-group\"><h3>${h(group)}</h3><div class=\"clip-grid\">")
                for (clip in members) append(clipHtml(clip))
                append("</div></div>")
            }
            append("</section>")
        }
    }

    private fun clipHtml(clip: Clip): String {
        val e = clip.evidence
        val title = e.getValue("title") as String
        val description = e.getValue("description") as String
        val voice = e.getValue("voice") as String
        val duration = number((e.getValue("durationSeconds") as Number).toDouble())
        val loop = e.getValue("loop") as Boolean
        val firstCall = (e["firstCallSeconds"] as? Number)?.let { "first call ${number(it.toDouble())} s" } ?: "no calls"
        val seam = (e["seamError"] as? Number)?.let { "<dt>Loop seam error</dt><dd>${h(String.format(Locale.ROOT, "%.3g", it.toDouble()))}</dd>" } ?: ""
        val macros = (e.getValue("macros") as Map<*, *>).entries.joinToString(" · ") { "${it.key} ${fmt((it.value as Number).toFloat())}" }
        val version = e["comparisonVersion"] as? String
        val provenance = (e["baselineSourceRevision"] as? String)?.let {
            "<dt>Before source</dt><dd><code>${h(it.take(12))}</code></dd><dt>Source WAV SHA-256</dt><dd><code>${h((e.getValue("baselineSourceAudioSha256") as String).take(12))}…</code></dd><dt>Baseline listening gain</dt><dd>${number((e.getValue("baselinePlaybackGain") as Number).toDouble())}×</dd>"
        }.orEmpty()
        return """
            <article class="clip" id="clip-${clip.id}" data-voice="$voice" data-category="${clip.category}" aria-labelledby="title-${clip.id}">
              <div class="clip-badges"><span>${h(voice)}</span><span>${h(e.getValue("level").toString())}</span>${if (version == null) "" else "<span>${h(version)}</span>"}${if (loop) "<span>loop</span>" else ""}</div>
              <h4 id="title-${clip.id}">${h(title)}</h4>
              <p class="description" id="desc-${clip.id}">${h(description)}</p>
              <p class="macro-line">${h(macros)}</p>
              <p class="clip-meta">$duration s · ${(e.getValue("arrivalCount") as Number).toInt()} arrivals · ${(e.getValue("callCount") as Number).toInt()} calls · $firstCall</p>
              <div class="clip-actions"><button class="play" type="button" aria-label="Play ${h(title)}" aria-describedby="desc-${clip.id}" aria-pressed="false" disabled>Play</button>${if (loop) "<label class=\"repeat\"><input type=\"checkbox\" class=\"loop-toggle\" checked disabled> Repeat</label>" else ""}<a class="download" href="${e.getValue("path")}" download>WAV <span class="sr-only">${h(title)}</span></a></div>
              <progress value="0" max="$duration" aria-label="Playback progress for ${h(title)}"></progress>
              <details class="evidence"><summary>Measurements and event record</summary><dl><dt>Raw peak</dt><dd>${number((e.getValue("rawPeak") as Number).toDouble())}</dd><dt>Export peak</dt><dd>${number((e.getValue("exportPeak") as Number).toDouble())}</dd><dt>Final passive energy</dt><dd>${String.format(Locale.ROOT, "%.3g", (e.getValue("finalPassiveEnergy") as Number).toDouble())}</dd>$seam$provenance</dl><a href="${e.getValue("eventPath")}">Causal event JSON <span class="sr-only">for ${h(title)}</span></a></details>
              <details class="native"><summary>Native audio controls</summary><p class="native-hint" hidden>Enable audio playback above to show these controls.</p><audio controls${if (loop) " loop" else ""} preload="none" src="${e.getValue("path")}" aria-label="${h(title)}" aria-describedby="desc-${clip.id}">Your browser can download the WAV above.</audio></details>
            </article>
        """.trimIndent()
    }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) { "non-finite evidence: $value" } })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("unsupported audition evidence ${value.javaClass}")
    }

    private fun fromJson(value: JsonValue): Any? = when (value) {
        JsonValue.Null -> null
        is JsonValue.Str -> value.value
        is JsonValue.Bool -> value.value
        is JsonValue.Num -> value.value
        is JsonValue.Obj -> value.entries.mapValues { fromJson(it.value) }
        is JsonValue.Arr -> value.items.map { fromJson(it) }
    }

    /** MURK's audition gain only. Shared audition helpers and production rendering stay separate. */
    private fun listeningLevel(snip: Snip): Snip {
        val samples = snip.samples.copyOf()
        Dsp.levelTo(samples, snip.sampleRate, target = LISTENING_TARGET,
            ceiling = LISTENING_CEILING, channels = snip.channels)
        return Snip(samples, channels = snip.channels, sampleRate = snip.sampleRate)
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }

    private fun macroLine(macros: Map<String, Float>) = macros.entries.joinToString(", ") { "${it.key} ${fmt(it.value)}" }
    private fun tag(value: Float) = (value * 100).roundToInt().toString().padStart(3, '0')
    private fun fmt(value: Float) = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
    private fun number(value: Double) = String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')
    private fun noteName(midi: Int) = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[midi % 12] + (midi / 12 - 1)
    private fun h(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
