package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Native JVM audio and measured contact histories; the page performs no synthesis. */
object AccordAuditionGenerator {
    private data class Case(val id: String, val title: String, val section: String,
        val patch: AccordPatch, val description: String, val match: Boolean = true)
    private data class PhasePoint(val time: Double, val r1: Double, val r2: Double)

    private fun cases(quick: Boolean): List<Case> {
        val result = mutableListOf<Case>()
        val brush = AccordPresets.default()
        for (patch in AccordPresets.all()) {
            val profile = patch.acousticProfile.name.lowercase()
            result += Case("${patch.voice.name.lowercase()}-$profile", "${patch.voice.name} · ${patch.name}",
                if (patch.voice == AccordVoice.TINE && patch.acousticProfile != AccordAcousticProfile.REFERENCE) "profiles" else "voices",
                patch, "C3, normal touch, four seconds of mechanics preroll. The acoustic state starts at zero.")
        }
        for (voice in AccordVoice.entries) {
            val anchor = AccordPresets.forVoice(voice).first()
            for (midi in listOf(36, 60, 72)) result += Case("${voice.name.lowercase()}-note-$midi",
                "${voice.name} · C${midi / 12 - 1}", "notes", anchor.copy(midi = midi),
                "The same mechanical event history at another root. Native reference calibration belongs to this profile and note.")
            for ((touch, velocity) in listOf("soft" to .3f, "normal" to .75f, "hard" to 1f)) {
                result += Case("${voice.name.lowercase()}-touch-$touch", "${voice.name} · $touch", "velocity",
                    anchor.copy(velocity = velocity), "C3 at velocity $velocity. Keep playback volume fixed; no comparison normalization is applied.", match = false)
            }
        }
        result += Case("mechanics-independent", "Support coupling off", "mechanics",
            brush.copy(macros = brush.macros + ("COUPLING" to 0f)),
            "Identical initial phases and rate offsets, with reciprocal support influence exactly zero.", match = false)
        result += Case("mechanics-mobile", "Mobile reference support", "mechanics", brush,
            "The reference mobile support. Agreement is measured from this passage; it is not promised for every seed.", match = false)
        result += Case("mechanics-light", "Light, lightly damped support", "mechanics",
            brush.copy(macros = brush.macros + ("RAFT" to 0f)),
            "A lighter support can maintain distributed phases rather than one pulse. The body acoustic law also changes with RAFT.", match = false)
        result += Case("mechanics-scattered-start", "Raw scattered start", "mechanics",
            brush.copy(sourceStartCycles = 0.0), "Start mechanics from the stored scattered initializer, with no hidden mechanics preroll.", match = false)
        for (seed in listOf(17L, Long.MIN_VALUE)) result += Case("mechanics-project-${if (seed == 17L) "17" else "min"}",
            "Project initializer · seed $seed", "mechanics",
            brush.copy(seed = seed, initializer = AccordInitializer.PROJECT_SINE_V1),
            "Project RNG phase/rate streams are a separately saved initializer, not a promise to reproduce NumPy's seed.", match = false)
        if (!quick) {
            for (control in listOf("PACE", "COUPLING", "MISMATCH", "CONTACT", "RAFT")) {
                for (step in 0..4) {
                    val value = step / 4f
                    result += Case("${control.lowercase()}-$step", "$control ${(value * 100).toInt()}%", "controls",
                        brush.copy(macros = brush.macros + (control to value)),
                        "Only $control changes from the BRUSH reference. These control mappings remain subject to native listening.")
                }
            }
            for ((a, b) in listOf("PACE" to "CONTACT", "COUPLING" to "MISMATCH",
                "COUPLING" to "RAFT", "RAFT" to "CONTACT")) {
                for (av in listOf(0f, 1f)) for (bv in listOf(0f, 1f)) {
                    result += Case("${a.lowercase()}-${av.toInt()}-${b.lowercase()}-${bv.toInt()}",
                        "$a ${av.toInt()} × $b ${bv.toInt()}", "interactions",
                        brush.copy(macros = brush.macros + mapOf(a to av, b to bv)),
                        "Controlled corner of $a × $b; native playback retains its fixed reference calibration.")
                }
            }
        }
        return result.also { require(it.map(Case::id).distinct().size == it.size) }
    }

    /** Calibrate one comparison gain from excitation only, keeping long tails out of the meter. */
    private fun matchedActive(snip: Snip, seconds: Double): Snip {
        val count = minOf(snip.samples.size, (seconds * snip.sampleRate).toInt())
        val active = snip.copy(samples = snip.samples.copyOfRange(0, count))
        val comparison = AuditionLevel.level(active)
        var gain = Loudness.of(comparison).toDouble() / Loudness.of(active).coerceAtLeast(1e-12f)
        gain = minOf(gain, .99 / snip.peak().coerceAtLeast(1e-12f))
        return snip.copy(samples = FloatArray(snip.samples.size) { (snip.samples[it] * gain).toFloat() })
    }

    private fun repository(): File = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .firstOrNull { File(it, ".git").exists() } ?: error("run the native Accord audition within its repository")

    private fun git(repo: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", repo.absolutePath) + args).redirectErrorStream(true).start()
        val value = process.inputStream.bufferedReader().readText().trim()
        require(process.waitFor() == 0) { "git failed: $value" }
        return value
    }

    private fun pythonReferences(root: File, repo: File): List<Map<String, Any?>> {
        val base = File(repo, "reference/accord/reference").takeIf { it.isDirectory } ?: File(repo, "reference/accord")
        val references = listOf(
            Triple("BRUSH", "BASE", "musicality/brush.wav"), Triple("WOOD", "BASE", "musicality/wood.wav"),
            Triple("TINE", "REFERENCE", "musicality/tine.wav"),
            Triple("TINE", "SOFT_ATTACK", "tine-options/soft_attack.wav"),
            Triple("TINE", "BRIGHT_EDGE", "tine-options/bright_edge.wav"),
            Triple("TINE", "LONG_RING", "tine-options/long_ring.wav"))
        return references.map { (voice, profile, relative) ->
            val source = File(base, relative)
            require(source.isFile) { "missing frozen Python comparison $source" }
            val id = "python-${voice.lowercase()}-${profile.lowercase()}"
            val path = "python/$id.wav"
            val copied = File(root, path)
            copied.parentFile.mkdirs(); source.copyTo(copied, overwrite = true)
            require(AccordAuditionFiles.hash(source) == AccordAuditionFiles.hash(copied))
            val original = WavReader.read(source)
            val matchedPath = "matched/$id.wav"
            val matched = matchedActive(original, 8.0)
            AccordAuditionFiles.writeAudio(File(root, matchedPath), matched)
            linkedMapOf<String, Any?>("id" to id, "title" to "Python $voice · ${profile.replace('_', ' ')}",
                "section" to "python", "voice" to voice, "profile" to profile, "midi" to 48, "velocity" to .75f,
                "sourceSeconds" to 8.0, "raw" to path, "matched" to matchedPath,
                "rawSha256" to AccordAuditionFiles.hash(source), "matchedSha256" to AccordAuditionFiles.hash(File(root, matchedPath)),
                "sourceReference" to source.relativeTo(repo).invariantSeparatorsPath,
                "description" to "Untouched accepted Python reference. Native filters and project roughness can differ; compare the complete attack, timing and release. Matching uses active eight seconds only.",
                "metrics" to AccordAuditionFiles.metrics(original), "matchedMetrics" to AccordAuditionFiles.metrics(matched))
        }
    }

    /** Same-direction passage phase; incomplete first/last cycles are excluded. */
    private fun phaseData(events: List<Accord.ContactEvent>, seconds: Double): Pair<List<PhasePoint>, Map<String, Any?>> {
        val crossings = (0 until 15).map { i -> events.filter { it.mechanism == i && it.direction > 0 }.map { it.timeSeconds } }
        if (crossings.any { it.size < 2 }) return emptyList<PhasePoint>() to mapOf("reason" to "not enough complete directional cycles")
        val from = crossings.maxOf { it.first() }
        val to = crossings.minOf { it.last() }
        if (to <= from) return emptyList<PhasePoint>() to mapOf("reason" to "no common complete-cycle window")
        fun phase(t: Double, x: List<Double>): Double {
            val found = x.binarySearch(t)
            if (found >= 0) return found * 2.0 * PI
            val index = (-found - 2).coerceIn(0, x.size - 2)
            return 2 * PI * (index + (t - x[index]) / (x[index + 1] - x[index]))
        }
        val count = maxOf(1, (seconds * 100).toInt())
        val points = (0..count).map { seconds * it / count }.filter { it in from..to }.map { t ->
            var c1 = 0.0; var s1 = 0.0; var c2 = 0.0; var s2 = 0.0
            for (x in crossings) {
                val p = phase(t, x)
                c1 += cos(p); s1 += sin(p); c2 += cos(2 * p); s2 += sin(2 * p)
            }
            PhasePoint(t, sqrt(c1 * c1 + s1 * s1) / 15, sqrt(c2 * c2 + s2 * s2) / 15)
        }
        val frequencies = crossings.map { (phase(to, it) - phase(from, it)) / (2 * PI * (to - from)) }
        val mean = frequencies.average()
        return points to linkedMapOf("windowStartSeconds" to from, "windowEndSeconds" to to,
            "meanFrequenciesHz" to frequencies,
            "relativeFrequencySpread" to ((frequencies.max() - frequencies.min()) / mean),
            "phaseMethod" to "Linear phase between individual positive-direction center passages, with incomplete edge cycles excluded")
    }

    private fun plot(file: File, title: String, events: List<Accord.ContactEvent>, points: List<PhasePoint>, seconds: Double) {
        fun f(x: Double) = "%.3f".format(Locale.ROOT, x)
        fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val x = { t: Double -> 75 + 900 * t / seconds }
        val text = buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1040 550\"><rect width=\"1040\" height=\"550\" fill=\"#f6f3eb\"/>")
            append("<g fill=\"#243830\" font-family=\"sans-serif\" font-size=\"14\"><text x=\"75\" y=\"30\">${escape(title)}</text>")
            append("<text x=\"75\" y=\"57\">Individual contacts: 15 mechanisms</text><text x=\"75\" y=\"350\">R1: full-cycle phase (green); R2: second harmonic (brown)</text>")
            for (tick in 0..4) {
                val t = seconds * tick / 4
                append("<text x=\"${f(x(t))}\" y=\"528\">${f(t)} s</text>")
            }
            append("</g><g stroke=\"#8ca196\" stroke-width=\"1\">")
            for (event in events) {
                val xx = x(event.timeSeconds); val y = 78 + event.mechanism * 15
                append("<path d=\"M${f(xx)} $y v10\"/>")
            }
            append("</g>")
            for ((color, value) in listOf("#275e49" to { p: PhasePoint -> p.r1 }, "#9d7046" to { p: PhasePoint -> p.r2 })) {
                append("<path fill=\"none\" stroke=\"$color\" stroke-width=\"2\" d=\"")
                points.forEachIndexed { i, p -> append("${if (i == 0) 'M' else 'L'}${f(x(p.time))} ${f(500 - 130 * value(p))} ") }
                append("\"/>")
            }
            append("</svg>")
        }
        file.parentFile.mkdirs(); file.writeText(text)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull { !it.startsWith("--") } ?: "../testkit/accord-audition").canonicalFile
        root.mkdirs()
        val repo = repository()
        val allCases = cases("--quick" in args)
        val entries = mutableListOf<Map<String, Any?>>()
        var maximumPcmError = 0.0
        val began = System.nanoTime()
        for ((index, case) in allCases.withIndex()) {
            val probe = Accord.probe(case.patch)
            val native = probe.samples
            require(native.peak() <= 1f && native.samples.all { it.isFinite() })
            val rawPath = "raw/${case.id}.wav"
            maximumPcmError = maxOf(maximumPcmError, AccordAuditionFiles.writeAudio(File(root, rawPath), native))
            val matchedPath = if (case.match) "matched/${case.id}.wav" else null
            val matched = if (case.match) matchedActive(native, case.patch.sourceSeconds) else null
            if (matched != null) maximumPcmError = maxOf(maximumPcmError,
                AccordAuditionFiles.writeAudio(File(root, requireNotNull(matchedPath)), matched))
            val recipePath = "recipes/${case.id}.json"
            val recipe = PadRecipe(case.patch)
            val recipeFile = File(root, recipePath)
            recipeFile.parentFile.mkdirs(); recipeFile.writeText(recipe.toJsonText() + "\n")
            require(PadRecipe.fromJsonText(recipeFile.readText()) == recipe)
            val (points, phase) = phaseData(probe.events, case.patch.sourceSeconds)
            val diagnosticPath = "diagnostics/${case.id}.json"
            AccordAuditionFiles.writeJson(File(root, diagnosticPath), linkedMapOf(
                "native" to probe.diagnostics.toJsonValue(), "unleveledMetrics" to AccordAuditionFiles.metrics(probe.raw),
                "phaseSummary" to phase,
                "phaseOrder" to points.map { listOf(it.time, it.r1, it.r2) },
                "contacts" to probe.events.map { linkedMapOf("seconds" to it.timeSeconds, "cycle" to it.cycle,
                    "mechanism" to it.mechanism, "direction" to it.direction,
                    "canonicalSpeed" to it.canonicalSpeed, "ordinal" to it.ordinal.toString()) }))
            val plotPath = "diagnostics/${case.id}.svg"
            plot(File(root, plotPath), case.title, probe.events, points, case.patch.sourceSeconds)
            entries += linkedMapOf("id" to case.id, "title" to case.title, "section" to case.section,
                "voice" to case.patch.voice.name, "profile" to case.patch.acousticProfile.name,
                "midi" to case.patch.midi, "velocity" to case.patch.velocity,
                "sourceSeconds" to case.patch.sourceSeconds, "raw" to rawPath, "matched" to matchedPath,
                "rawSha256" to AccordAuditionFiles.hash(File(root, rawPath)),
                "matchedSha256" to matchedPath?.let { AccordAuditionFiles.hash(File(root, it)) },
                "recipe" to recipePath, "diagnostics" to diagnosticPath, "plot" to plotPath,
                "description" to case.description, "metrics" to AccordAuditionFiles.metrics(native),
                "matchedMetrics" to matched?.let(AccordAuditionFiles::metrics))
            println("Accord audition ${index + 1}/${allCases.size}: ${case.id}")
        }
        entries += pythonReferences(root, repo)
        val sourceNames = listOf("Accord.kt", "AccordDynamics.kt", "AccordAcoustics.kt", "AccordPatch.kt",
            "AccordPresets.kt", "AccordKits.kt", "Dsp.kt", "Modes.kt").map { "synth/src/main/kotlin/com/snipsnap/synth/$it" } +
            listOf("synth/src/test/kotlin/com/snipsnap/synth/AccordAuditionGenerator.kt",
                "synth/src/test/kotlin/com/snipsnap/synth/AccordAuditionFiles.kt",
                "synth/src/test/resources/audition/accord-audition.html", "synth/build.gradle.kts")
        val hashes = linkedMapOf<String, String>()
        for (path in sourceNames) {
            val file = File(repo, path)
            require(file.isFile) { "missing native Accord source $path" }
            val copied = File(root, "source/$path")
            copied.parentFile.mkdirs(); file.copyTo(copied, overwrite = true)
            hashes[path] = AccordAuditionFiles.hash(copied)
        }
        val manifest = linkedMapOf<String, Any?>("title" to "Accord native audition", "engine" to "ACCORD",
            "status" to "Native Kotlin build; owner listening, phone timing and MPC hardware checks remain pending",
            "model" to Accord.MODEL_VERSION, "defaultVoice" to "BRUSH", "archive" to "Accord-native-audition.zip",
            "playback" to "Finite one-shot performances; HOLD unsupported. LOOP is duration classification only.",
            "levelPolicy" to "raw/ files are exact native calibrated output. Optional matched/ files use one static audition gain. Touch and causal comparisons retain native levels.",
            "maximumPcm24Error" to maximumPcmError,
            "generationSeconds" to (System.nanoTime() - began) / 1e9,
            "source" to mapOf("revision" to git(repo, "rev-parse", "HEAD"),
                "dirty" to git(repo, "status", "--porcelain").isNotEmpty(), "sha256" to hashes),
            "referenceScope" to "Copied Python PCM is unchanged; native roughness and house filters are separate measured implementation decisions.",
            "clips" to entries)
        AccordAuditionFiles.writeJson(File(root, "manifest.json"), manifest)
        val encoded = Json.write(AccordAuditionFiles.json(manifest)).replace("<", "\\u003c")
        val html = AccordAuditionGenerator::class.java.getResourceAsStream("/audition/accord-audition.html")
            ?.bufferedReader()?.use { it.readText() } ?: error("missing Accord audition HTML")
        require(html.contains("/*ACCORD_MANIFEST*/null"))
        File(root, "index.html").writeText(html.replace("/*ACCORD_MANIFEST*/null", encoded))
        File(root, "README.txt").writeText("""
            ACCORD NATIVE AUDITION
            Open index.html. Its manifest is embedded, so local file playback needs no server.
            Native level is the engine's exact calibrated PCM24 output, retaining velocity.
            Matched level is an optional fixed audition gain; touch/causal clips offer no matching.
            recipes/ contains complete replayable patches. diagnostics/ contains measured native
            states/metrics, exact contact lists and crossing-derived phase/raster SVGs.
            These finite performances do not wrap seamlessly and reject sustained HOLD.
            Native listening acceptance, phone timing and MPC hardware checks remain open.
            Build: ./gradlew :synth:generateAccordAudition
        """.trimIndent() + "\n")
        val archive = File(root, "Accord-native-audition.zip")
        AccordAuditionFiles.archive(root, archive)
        println("Accord native audition: ${root.absolutePath}; ${archive.absolutePath}")
    }
}
