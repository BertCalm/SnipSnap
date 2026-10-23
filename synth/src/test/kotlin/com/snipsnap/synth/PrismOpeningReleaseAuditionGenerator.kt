package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Delivered presets over their complete registers; JVM cost measurements are not phone latency. */
object PrismOpeningReleaseAuditionGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/prism-opening-release-audition").absoluteFile
        val audio = File(root, "audio").apply { mkdirs() }
        val recipes = File(root, "recipes").apply { mkdirs() }
        val repository = repositoryRoot()
        val cases = PrismOpeningReleaseEvidence.cases()
        check(cases.size == 24) { "release matrix needs four presets, three notes and two velocities" }
        // Exercise the common render path before timing. Each recorded interval below contains
        // one render only, without replay, spectral probes, matching, hashes or filesystem work.
        val bases = PrismOpeningPresets.all()
        bases.forEach { it.render() }
        val started = System.nanoTime()
        val clips = mutableListOf<Map<String, Any?>>()
        for (case in cases) {
            val patch = case.patch
            val began = System.nanoTime()
            val native = patch.render()
            val singleRenderMillis = (System.nanoTime() - began) / 1e6
            val recipe = PadRecipe(patch)
            val recipeText = recipe.toJsonText()
            val reopened = PadRecipe.fromJsonText(recipeText)
            check(reopened == recipe && sameBits(native.samples, reopened.render().samples)) { "${case.id} saved replay changed" }
            check(sameBits(native.samples, Velocity.atVelocity(patch.copy(velocity = .8f), patch.velocity).samples)) {
                "${case.id} production velocity routing changed the saved sound"
            }
            check(native.channels == 1 && native.sampleRate == Dsp.RATE && native.samples.all { it.isFinite() })
            val probe = Prism.probe(patch.voice, patch.macros, patch.velocity, patch.template,
                normalize = false, motion = patch.motion)
            check(probe.partialHz.all { it > 0.0 && it < Prism.USABLE_BANDWIDTH_HZ })
            val rawPeak = probe.raw.maxOf { abs(it.toDouble()) }
            check(rawPeak <= Prism.RAW_PEAK_CEILING + 1e-6)
            val m = PrismOpeningReleaseEvidence.measure(patch, native.samples)
            check(abs(m.pitchCents) < 5.0 && m.periodCorrelation > .7 && abs(m.dc) < .001 && m.peak <= 1.0) {
                "${case.id} pitch/periodicity/DC/level probe failed: $m"
            }
            val matched = AuditionLevel.level(native)
            val nativePath = "audio/${case.id}-native.wav"
            val matchedPath = "audio/${case.id}-matched.wav"
            val recipePath = "recipes/${case.id}.json"
            WavWriter.write(File(audio, "${case.id}-native.wav"), native, WavWriter.BitDepth.PCM_16)
            WavWriter.write(File(audio, "${case.id}-matched.wav"), matched, WavWriter.BitDepth.PCM_16)
            File(recipes, "${case.id}.json").writeText(recipeText)
            clips += linkedMapOf(
                "id" to case.id, "preset" to patch.name, "voice" to patch.voice.name,
                "register" to case.register, "midi" to Prism.midiFor(patch.voice, patch.macros.getValue("TUNE")),
                "velocityLabel" to case.velocityLabel, "velocity" to patch.velocity,
                "seconds" to native.durationSeconds, "native" to nativePath, "matched" to matchedPath, "recipe" to recipePath,
                "warmedSingleRenderMillis" to singleRenderMillis,
                "rawAdditivePeak" to rawPeak, "nativePeak" to m.peak, "nativeRms" to m.rms, "nativeDc" to m.dc,
                "nativeLoudness" to Loudness.of(native), "matchedLoudness" to Loudness.of(matched),
                "expectedHz" to m.expectedHz, "measuredRootHz" to m.measuredHz, "rootCents" to m.pitchCents,
                "normalizedPeriodCorrelation" to m.periodCorrelation,
                "publicPitchDetectorHz" to m.detectorHz, "publicPitchDetectorConfidence" to m.detectorConfidence,
                "publicPitchDetectorNote" to if (m.expectedHz < 40.0) "Below the public detector's 40 Hz range; measured by FineTuning plus one-period correlation." else "Public detector estimate is supplementary; FineTuning measures root accuracy.",
                "carrierCount" to probe.partialHz.size, "highestCarrierHz" to probe.partialHz.max(),
                "allCarriersBelowOutputBandwidth" to true,
                "recipeReplayExact" to true, "productionVelocityReplayExact" to true,
                "nativeSamplesSha256" to samplesHash(native.samples),
                "nativeWavSha256" to hash(File(root, nativePath)), "matchedWavSha256" to hash(File(root, matchedPath)),
                "recipeSha256" to hash(File(root, recipePath)),
            )
            println("PRISM release ${clips.size}/24: ${patch.name} ${case.register} ${case.velocityLabel}; render ${"%.2f".format(singleRenderMillis)} ms")
        }
        val cancellation = bases.map(::interruptInProgress)
        val durations = clips.map { (it.getValue("warmedSingleRenderMillis") as Number).toDouble() }.sorted()
        val sources = listOf(
            "synth/src/main/kotlin/com/snipsnap/synth/Prism.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/PrismMotion.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/PrismPatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/PrismOpeningPresets.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/PrismOpeningPlayabilityTest.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/PrismOpeningReleaseAuditionGenerator.kt",
        ).associateWith { hash(File(repository, it)) }
        val manifest = linkedMapOf<String, Any?>(
            "title" to "PRISM body openings: register and touch checks",
            "generatedAt" to Instant.now().toString(), "sourceRevision" to git(repository, "rev-parse", "HEAD"),
            "sourceDirty" to git(repository, "status", "--porcelain", "--untracked-files=normal")?.isNotBlank(),
            "sourceFilesSha256" to sources, "sampleRate" to Dsp.RATE, "channels" to 1, "bitDepth" to 16,
            "javaVersion" to System.getProperty("java.version"), "javaVm" to System.getProperty("java.vm.name"),
            "osArchitecture" to System.getProperty("os.arch"), "warmupRenderCount" to bases.size,
            "nativeMeaning" to "Normal dry engine output, including its fixed output conditioning and velocity-dependent loudness target.",
            "matchedMeaning" to "Shared loudest-200-ms RMS target .03 with peak guard; use native files to compare performance dynamics.",
            "measurementNote" to "Full valid TUNE range per delivered voice, at velocities .3 and 1. Root accuracy uses FineTuning over 1.05–1.50 seconds. Independent normalized one-period correlation reports periodicity; public Pitch is supplementary and excludes roots below 40 Hz. Carrier bandwidth checks are not a complete alias-spectrum measurement.",
            "timingNote" to "Each warmed JVM interval measures one patch.render call only. Android preview, scheduler, audio-output and MPC timing remain unmeasured. No hard latency promise is inferred from this machine.",
            "minimumSingleRenderMillis" to durations.first(), "medianSingleRenderMillis" to durations[durations.size / 2],
            "maximumSingleRenderMillis" to durations.last(), "cancellation" to cancellation,
            "clipCount" to clips.size, "clips" to clips,
            "generationSeconds" to (System.nanoTime() - started) / 1e9,
        )
        File(root, "manifest.json").writeText(Json.write(json(manifest)))
        File(root, "diagnostics.json").writeText(Json.write(json(mapOf("clips" to clips, "cancellation" to cancellation))))
        File(root, "index.html").writeText(page(clips))
        println("Wrote PRISM release evidence to ${root.path}")
    }

    /** Observe a real interrupt after the worker has passed sixteen synthesis control blocks. */
    private fun interruptInProgress(patch: PrismPatch): Map<String, Any?> {
        val entered = CountDownLatch(1)
        val finished = AtomicLong()
        val outcome = AtomicReference("running")
        var blocks = 0
        val worker = Thread({
            try {
                Prism.probe(patch.voice, patch.macros, patch.velocity, patch.template,
                    cancelled = {
                        if (++blocks == 16) {
                            entered.countDown()
                            // Hold an in-progress render at a known check until the caller
                            // interrupts. Do not throw here: the next DSP check must observe it.
                            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                            while (!Thread.currentThread().isInterrupted && System.nanoTime() < deadline) {
                                Thread.onSpinWait()
                            }
                            check(Thread.currentThread().isInterrupted) { "interrupt rendezvous timed out" }
                        }
                        false
                    },
                    keepRaw = false, motion = patch.motion)
                outcome.set("completed before cancellation was observed")
            } catch (_: CancellationException) {
                outcome.set("cancelled")
            } catch (failure: Throwable) {
                outcome.set("failed: ${failure.javaClass.simpleName}: ${failure.message}")
            } finally {
                finished.set(System.nanoTime())
            }
        }, "prism-release-interrupt").apply { isDaemon = true }
        worker.start()
        if (!entered.await(5, TimeUnit.SECONDS)) {
            worker.interrupt()
            worker.join(2000)
            error("${patch.name} did not reach the cancellation observation point")
        }
        val requested = System.nanoTime()
        worker.interrupt()
        worker.join(2000)
        check(!worker.isAlive) { "${patch.name} did not stop within the JVM audit's two-second join limit" }
        check(outcome.get() == "cancelled") { "${patch.name} did not observe the in-progress interrupt: ${outcome.get()}" }
        check(finished.get() >= requested) { "${patch.name} exited before its interruption request" }
        val latency = (finished.get() - requested) / 1e6
        return linkedMapOf("preset" to patch.name, "outcome" to outcome.get(), "observedControlBlocks" to blocks,
            "interruptToWorkerExitMillis" to latency,
            "note" to "A bounded rendezvous holds the sixteenth synthesis callback until the caller interrupts; the next DSP check must throw. Measures JVM interrupt-to-exit including local scheduling, not natural polling or Android preview latency.")
    }

    private fun page(clips: List<Map<String, Any?>>): String {
        fun escape(value: Any?) = value.toString().replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
        val rows = clips.joinToString("\n") { c ->
            "<tr><th>${escape(c["preset"])}<br>${escape(c["register"])} · MIDI ${escape(c["midi"])} · ${escape(c["velocityLabel"])}</th>" +
                "<td><audio controls preload=\"none\" src=\"${escape(c["native"])}\"></audio></td>" +
                "<td><audio controls preload=\"none\" src=\"${escape(c["matched"])}\"></audio></td>" +
                "<td>${"%.2f".format(c["warmedSingleRenderMillis"])} ms<br><a href=\"${escape(c["recipe"])}\">Recipe</a></td></tr>"
        }
        return """<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>PRISM body opening playability</title><style>body{font:16px system-ui;margin:2rem;background:#16181b;color:#eee}a{color:#ffb2a3}table{border-collapse:collapse}th,td{padding:1rem;border-bottom:1px solid #555;text-align:left}audio{max-width:30vw}</style>
            <h1>PRISM body opening playability</h1><p>Three notes and two touches for each delivered opening. Native keeps performance dynamics; matched uses the shared listening level. Measurements support listening and do not establish sonic approval.</p>
            <p>Render times are warmed single-call JVM measurements. Phone and MPC playback remain device checks. <a href="manifest.json">Manifest</a> · <a href="diagnostics.json">Diagnostics</a></p>
            <table><thead><tr><th>Case</th><th>Native</th><th>Matched</th><th>JVM render</th></tr></thead><tbody>$rows</tbody></table></html>""".trimIndent()
    }

    private fun sameBits(a: FloatArray, b: FloatArray) = a.size == b.size && a.indices.all { a[it].toRawBits() == b[it].toRawBits() }
    private fun samplesHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) bytes.putInt(sample.toRawBits())
        return digest(bytes.array())
    }
    private fun hash(file: File) = digest(file.readBytes())
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun repositoryRoot(): File {
        var directory = File(".").canonicalFile
        while (!File(directory, ".git").exists() && directory.parentFile != null) directory = directory.parentFile
        require(File(directory, ".git").exists()) { "cannot locate the source checkout" }
        return directory
    }
    private fun git(repository: File, vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git", "-C", repository.path) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) output else null
    }.getOrNull()
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble())
        is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associateTo(LinkedHashMap()) { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("unsupported release evidence value ${value.javaClass.name}")
    }
}
