package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Dry MURK listening evidence, including the engineering-only causal switches. The generated
 * HTML contains its descriptions and manifest, so opening index.html directly works offline.
 * Run `./gradlew :synth:generateMurkAudition`; the output is deliberately outside source control.
 * No generated description constitutes a listening verdict.
 */
object MurkAuditionGenerator {
    private val steps = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    private val timbral = listOf("STRIKE", "TRUNK", "FOG", "AGITATION", "GROVE")
    private val voiceDescriptions = mapOf(
        MurkVoice.CLUNK to "Dense, pitched bat-like wood; a rounded attack and a distant reply.",
        MurkVoice.THWACK to "Sharper tonal wood; a compact thwack followed by articulate returns.",
        MurkVoice.FRONT to "Loaded wood pushes a broader traveling pressure front through the grove.",
        MurkVoice.HOOT to "Soft wood provides the cause; rounded root-related owl calls provide the answer.",
        MurkVoice.GROVE to "Staggered trunks and animal responses form a finite conversation.",
        MurkVoice.ALARM to "A hard gesture prompts faster barks and controlled, tonally anchored alarm calls.",
    )
    private val macroDescriptions = mapOf(
        "STRIKE" to "Broad bat-like contact becomes a sharper axe-like contact. Compare the wood attack and the response it provokes.",
        "TRUNK" to "Lighter tight wood becomes a denser hollow resonating tree. The requested root should remain stable.",
        "FOG" to "Light loading and clear arrivals become heavier loading and broader traveling fronts. Listen to the first trunk as well as its returns.",
        "AGITATION" to "Quiet sparse replies become firmer, faster, rougher calls. Compare articulation and response latency.",
        "GROVE" to "Compact local coupling becomes more spaced arrivals and an extended conversation. Compare both nearby transfer and timing.",
    )
    private val presetDescriptions = mapOf(
        "HOLLOW BAT" to "Rounded, dense wood at C4 with restrained fog and a quiet distant response.",
        "DEEP TRUNK" to "Low C3 and a dense hollow trunk, with a soft gesture and restrained animal activity.",
        "HELD TREES" to "A recurring wood-led grove at G3, with spaced gestures and quiet replies.",
        "TONAL AXE" to "Sharp C4 contact with clear wood and relatively light atmospheric loading.",
        "SOFT CHOP" to "A gentler high G4 chop through a denser trunk, with restrained replies.",
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
        val root = File(args.firstOrNull() ?: "../testkit/murk-audition")
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
                branch == "full" -> AuditionLevel.level(raw)
                else -> {
                    // Source taps inherit the full reference's gain. Matching each quiet owl or
                    // fog tap independently would conceal its actual balance against the wood.
                    val reference = Snip(probe.samples, channels = 1, sampleRate = probe.sampleRate)
                    val gain = Loudness.of(AuditionLevel.level(reference)) / Loudness.of(reference).coerceAtLeast(1e-9f)
                    Snip(raw.samples.map { it * gain }.toFloatArray(), channels = 1, sampleRate = probe.sampleRate)
                }
            }
            require(output.peak() <= 1f) { "$id export peak ${output.peak()} exceeds PCM range" }
            val path = "clips/$id.wav"
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

        for (voice in MurkVoice.entries) {
            for ((tune, register) in listOf(0f to "low", 0.5f to "middle", 1f to "high")) {
                val note = noteName(Murk.midiFor(voice, tune))
                render("${voice.name.lowercase()}_$register", "${voice.name} · $note · $register",
                    "${voiceDescriptions.getValue(voice)} TUNE ${fmt(tune)}; the other controls use this voice's defaults.",
                    "voices", voice.name, voice, Murk.defaults(voice) + ("TUNE" to tune))
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
                "The exact same synthesis as the raw partner, matched to the shared audition level: loudest 200 ms RMS 0.03, with a peak guard. It has no added effects.",
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
            "loudness" to "Matched full clips use loudest 200 ms RMS 0.03 with a peak guard. Source taps inherit their full reference's gain; raw partners retain synthesis amplitude.",
            "noteRange" to "C3–C5", "velocityContract" to "Existing host velocity scales event energy; STRIKE remains contact character.",
            "renderMilliseconds" to totalRenderNanos / 1_000_000.0,
            "elapsedMilliseconds" to (System.nanoTime() - started) / 1_000_000.0,
            "peakObservedUsedHeapBytes" to peakObservedHeap,
            "memoryMeasurement" to "JVM used-heap observations before and after renders; includes retained JVM allocations and excludes native memory.",
            "clips" to clips.map { it.evidence },
        )
        val manifestText = Json.write(json(manifest)) + "\n"
        File(root, "manifest.json").writeText(manifestText)
        val template = javaClass.getResourceAsStream("/audition/murk-audition.html")
            ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("MURK audition page resource is missing")
        require(template.contains("<!-- MURK_CARDS -->") && template.contains("<!-- MURK_MANIFEST -->"))
        File(root, "index.html").writeText(template
            .replace("<!-- MURK_CARDS -->", sectionsHtml(clips))
            .replace("<!-- MURK_MANIFEST -->", "<script type=\"application/json\" id=\"murk-manifest\">${manifestText.replace("<", "\\u003c")}</script>"))
        File(root, "README.md").writeText("""
            # MURK audition

            Open `index.html` directly in a browser. All assets are relative and the descriptions
            and manifest are embedded: no web server, external fonts or network access are needed.
            Audio starts off. Enable playback, then choose Play; Stop all or Escape stops playback.
            Every clip also has native audio controls and a WAV download. A single clip plays at a
            time. Voice, section and text filters support reading before listening.

            Rebuild with `./gradlew :synth:generateMurkAudition` from the repository root.
            This set contains ${clips.size} dry mono PCM-16 WAV clips at 44.1 kHz. It covers all six
            voices, C3/C4/C5, fourteen factory presets, quiet/medium/strong event energy, all five macro sweeps on every voice,
            five 3×3 interaction grids, raw/matched pairs, causal switches, source taps, edge cases
            and representative and extreme HOLD loops. No effects are included.

            Matched full clips share loudest-200-ms RMS 0.03, with a peak guard; source taps use
            their full reference's gain to preserve relative balance, and raw partners retain
            their original amplitude. The manifest records measured peaks, loudness, timing,
            event counts, passive energy, loop seam evidence and render costs. Per-clip event JSON
            records gestures, acoustic arrivals and scheduled calls with origin/generation tags.
            Heap measurements are JVM observations, not process peak RSS or allocation counts.

            Descriptions express expected behavior. Numerical checks and generated descriptions
            do not replace the owner's listening verdict; sonic acceptance remains pending.
        """.trimIndent() + "\n")
        println("wrote ${clips.size} clips, event records, manifest and accessible index.html under ${root.absolutePath}")
        println("render time ${number(totalRenderNanos / 1_000_000_000.0)} s; observed JVM used heap ${number(peakObservedHeap / 1048576.0)} MiB")
    }

    private val categories = linkedMapOf(
        "voices" to ("Six voices, three registers" to "Begin with each voice's defaults at C3, C4 and C5. Each sound begins with pitched wood, travels through the grove and can prompt a finite vocal reply."),
        "presets" to ("Fourteen factory sounds" to "The named recipes available in SnipSnap, rendered dry with their exact saved controls. These descriptions are intended characters; the factory roster still needs listening sign-off."),
        "energy" to ("Quiet, medium and strong events" to "Event energy uses the existing host velocity contract. Loudness matching helps expose changes in propagation and behavior."),
        "sweeps" to ("Macro sweeps on every voice" to "Each timbral control is sampled at 0, 0.25, 0.5, 0.75 and 1; its companions use their declared neutral values."),
        "grids" to ("Five interaction grids" to "HOOT at neutral companions. Each 3 × 3 grid tests a required pair of controls at 0, 0.5 and 1."),
        "levels" to ("Raw and matched pairs" to "The same dry synthesis appears twice. Raw preserves its original amplitude; matched uses this audition's shared quiet level."),
        "diagnostics" to ("Causal probes and edge cases" to "Engineering switches and separate source taps help explain how a gesture becomes a finite ecosystem response. These switches are absent from the instrument's public controls."),
        "hold" to ("Recurring groves and HOLD transition" to "Settled loops contain repeated explicit gestures. Repeat is optional and starts off; listen across the seam for double strikes, clipped calls or timing discontinuities."),
    )

    private fun sectionsHtml(clips: List<Clip>): String = buildString {
        for ((category, copy) in categories) {
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
        val macros = (e.getValue("macros") as Map<*, *>).entries.joinToString(" · ") { "${it.key} ${fmt(it.value as Float)}" }
        return """
            <article class="clip" id="clip-${clip.id}" data-voice="$voice" data-category="${clip.category}" aria-labelledby="title-${clip.id}">
              <div class="clip-badges"><span>${h(voice)}</span><span>${h(e.getValue("level").toString())}</span>${if (loop) "<span>loop</span>" else ""}</div>
              <h4 id="title-${clip.id}">${h(title)}</h4>
              <p class="description" id="desc-${clip.id}">${h(description)}</p>
              <p class="macro-line">${h(macros)}</p>
              <p class="clip-meta">$duration s · ${e.getValue("arrivalCount")} arrivals · ${e.getValue("callCount")} calls · $firstCall</p>
              <div class="clip-actions"><button class="play" type="button" aria-label="Play ${h(title)}" aria-describedby="desc-${clip.id}" aria-pressed="false" disabled>Play</button>${if (loop) "<label class=\"repeat\"><input type=\"checkbox\" class=\"loop-toggle\"> Repeat</label>" else ""}<a class="download" href="${e.getValue("path")}" download>WAV <span class="sr-only">${h(title)}</span></a></div>
              <progress value="0" max="$duration" aria-label="Playback progress for ${h(title)}"></progress>
              <details class="evidence"><summary>Measurements and event record</summary><dl><dt>Raw peak</dt><dd>${number((e.getValue("rawPeak") as Number).toDouble())}</dd><dt>Export peak</dt><dd>${number((e.getValue("exportPeak") as Number).toDouble())}</dd><dt>Final passive energy</dt><dd>${String.format(Locale.ROOT, "%.3g", (e.getValue("finalPassiveEnergy") as Number).toDouble())}</dd>$seam</dl><a href="${e.getValue("eventPath")}">Causal event JSON <span class="sr-only">for ${h(title)}</span></a></details>
              <details class="native"><summary>Native audio controls</summary><p class="native-hint" hidden>Enable audio playback above to show these controls.</p><audio controls preload="none" src="${e.getValue("path")}" aria-label="${h(title)}" aria-describedby="desc-${clip.id}">Your browser can download the WAV above.</audio></details>
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

    private fun macroLine(macros: Map<String, Float>) = macros.entries.joinToString(", ") { "${it.key} ${fmt(it.value)}" }
    private fun tag(value: Float) = (value * 100).roundToInt().toString().padStart(3, '0')
    private fun fmt(value: Float) = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
    private fun number(value: Double) = String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')
    private fun noteName(midi: Int) = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[midi % 12] + (midi / 12 - 1)
    private fun h(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
