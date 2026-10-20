package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Additional listening evidence only: the original core and nine A/B recordings stay intact. */
object ScannedChainSweepGenerator {
    private const val RATE = 44_100
    private const val SILENCE_RMS = 1e-8
    private const val TARGET_RMS = .12
    private const val PEAK_CEILING = .90

    internal data class Step(
        val id: String, val label: String, val value: Any,
        val config: ScannedChainPrototype.Config, val inputShape: FloatArray,
        val shapeId: String, val isDefault: Boolean,
    )

    internal data class Control(
        val id: String, val label: String, val parameter: String, val unit: String,
        val explanation: String, val expectedChange: String, val caveat: String,
        val baseConfig: ScannedChainPrototype.Config, val inputShape: FloatArray,
        val shapeId: String, val defaultValue: Any, val steps: List<Step>,
        val shapeProvenance: Map<String, Any?> = emptyMap(),
        val referenceValue: Any = defaultValue,
        val referenceLabel: String = "Authored default",
        val referenceExplanation: String = "Compare the authored default with the selected value.",
    )

    internal data class LevelPlan(val targetRms: Double, val gains: List<Double>)
    private data class Rendered(val step: Step, val result: ScannedChainPrototype.Result, val milliseconds: Double)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Usage: ScannedChainSweepGenerator [existing-audition-directory]" }
        val repo = repositoryRoot()
        val root = File(args.firstOrNull() ?: File(repo, "testkit/scanned-chain-audition").path)
        val acceptedFile = File(root, "manifest.json")
        require(acceptedFile.isFile) { "Generate the original scanned-chain audition before its sweeps" }
        val accepted = Json.parse(acceptedFile.readText()).obj()
        val core = File(repo, "synth/src/test/kotlin/com/snipsnap/synth/ScannedChainPrototype.kt")
        val generator = File(repo, "synth/src/test/kotlin/com/snipsnap/synth/ScannedChainSweepGenerator.kt")
        val coreHash = sha256(core.readBytes())
        val acceptedCoreHash = accepted.getValue("provenance").obj().getValue("coreSha256").str()
        check(coreHash == acceptedCoreHash) { "The core differs from the accepted nine-pair audition; do not generate incomparable sweeps" }
        val originalAudio = originalAudioHashes(root, accepted)
        val controls = listOf(shapeControl(accepted)) + numericControls()
        check(controls.size == 10 && controls.all { it.steps.size == 5 && it.steps.count(Step::isDefault) == 1 })
        val output = File(root, "sweeps")
        check(output.isDirectory || output.mkdirs())
        val entries = controls.mapIndexed { index, control ->
            check(sha256(core.readBytes()) == coreHash) { "Core changed while generating sweeps" }
            val rendered = control.steps.map { step ->
                validateIsolation(control, step)
                render(step)
            }
            val maxPeak = rendered.maxOf { peak(it.result.raw.samples) }
            val rawGain = rawExportGain(rendered.map { it.result.raw.samples })
            val matched = matchedGains(rendered.map { it.result.raw.samples })
            val clips = rendered.mapIndexed { stepIndex, result -> export(root, control, result, rawGain, matched.gains[stepIndex]) }
            val defaultIndex = control.steps.indexOfFirst { it.isDefault }
            val referenceIndex = control.steps.indexOfFirst { sameValue(it.value, control.referenceValue) }
            check(referenceIndex >= 0) { "${control.id} reference value is missing from its five steps" }
            val baseline = clips[referenceIndex]
            val physicalShape = rendered[defaultIndex].result.initialShape
            println("SCANNED SWEEPS ${index + 1}/${controls.size}: ${control.label}, five levels")
            linkedMapOf<String, Any?>(
                "id" to control.id, "label" to control.label, "parameter" to control.parameter, "unit" to control.unit,
                "explanation" to control.explanation, "expectedChange" to control.expectedChange, "caveat" to control.caveat,
                "defaultValue" to control.defaultValue, "fixedConfig" to configuration(control.baseConfig),
                "defaultStepId" to clips[defaultIndex].getValue("id"),
                "referenceValue" to control.referenceValue, "referenceLabel" to control.referenceLabel,
                "referenceExplanation" to control.referenceExplanation,
                "inputShape" to control.inputShape.toList(), "inputShapeSha256" to floatHash(control.inputShape),
                "shape" to physicalShape.toList(), "shapeSha256" to floatHash(physicalShape),
                "shapeId" to control.shapeId, "shapeProvenance" to control.shapeProvenance,
                "seed" to control.baseConfig.seed, "rawExportGain" to rawGain,
                "matchedTargetRms" to matched.targetRms, "maximumUnscaledRawPeak" to maxPeak,
                "baselineFile" to baseline.getValue("matchedFile"), "baselineRawFile" to baseline.getValue("rawFile"),
                "baselineRawMetrics" to baseline.getValue("rawExportMetrics"), "baselineMatchedMetrics" to baseline.getValue("matchedMetrics"),
                "referenceStepId" to baseline.getValue("id"), "steps" to clips,
            )
        }
        check(sha256(core.readBytes()) == coreHash)
        check(originalAudio.all { (path, hash) -> sha256(File(root, path).readBytes()) == hash }) {
            "An original audition WAV changed during sweep generation"
        }
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "modelVersion" to accepted.getValue("modelVersion").str(),
            "generatedAt" to Instant.now().toString(), "sampleRate" to RATE, "durationSeconds" to 3.0,
            "coreSha256" to coreHash, "generatorSha256" to sha256(generator.readBytes()),
            "provenance" to linkedMapOf(
                "coreFile" to core.relativeTo(repo).invariantSeparatorsPath, "coreSha256" to coreHash,
                "generatorFile" to generator.relativeTo(repo).invariantSeparatorsPath,
                "generatorSha256" to sha256(generator.readBytes()),
            ),
            "acceptedManifestSha256" to sha256(acceptedFile.readBytes()),
            "originalAudioSha256" to originalAudio,
            "scope" to "50 finite mono parameter steps. Each group declares its authored default and its comparison reference separately; BLOOM compares no added force with the selected amount. The original audition retains its separate frozen/moving comparisons. Same experimental core; no product registration or HOLD/sustain loop.",
            "comparisonMethod" to linkedMapOf(
                "raw" to "One common attenuation within each control group of five steps, only if required for 0.90 PCM headroom. Raw mode preserves gain and decay differences within that group.",
                "matched" to "Constant gain per clip equalizes whole-file RMS within each control group, targeting 0.12. A group-shared target is reduced if any clip would exceed 0.90 peak; silence is never amplified.",
                "targetRms" to TARGET_RMS, "peakCeiling" to PEAK_CEILING,
                "caveat" to "RMS matching can hide the level and decay consequences of DAMP, ATTACK, RELEASE and BLOOM. Compare both modes; RMS is not perceptual loudness matching. Raw levels across different groups can have different common export attenuation.",
                "exportMetrics" to "RMS, peak and DC are measured after decoding each written 24-bit PCM WAV. Pitch and spectral centroid are measured on the unscaled render and reused under constant gain.",
                "isolation" to "Each numerical step changes only its named Config field. SHAPE changes only the initial array. Mechanical mappings may affect several forces or the antialias budget as a consequence of that one field.",
                "drive" to "Drive is a bounded force during a maximum two-second window in these three-second files; increasing it does not create infinite sustain.",
                "reference" to "The authored default remains marked independently. BLOOM uses amount 0 (BLOOM OFF) as its A reference so the selected B amount is compared directly with no added force; other groups use their authored default.",
            ),
            "controls" to entries,
        )
        File(output, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
        println("SCANNED SWEEPS: 50 steps in ${output.absolutePath}; original audio hashes unchanged")
    }

    /** Fixture-free descriptors let structural tests inspect actual fields and saved shape inputs. */
    internal fun numericControls(): List<Control> {
        val passive = ScannedChainPrototype.Config("Passive METAL C4", voice = ScannedChainPrototype.Voice.METAL,
            mechanicalHz = 1.2f, dampingPerSecond = 1f, nonlinear = 2f, restFraction = .12f, drive = 0f)
        val passiveShape = ScannedChainPrototype.initialShape(passive)
        val bloom = ScannedChainPrototype.factoryConfigs().single { it.voice == ScannedChainPrototype.Voice.BLOOM }
            .copy(name = "Driven BLOOM C4", seconds = 3f)
        val bloomShape = ScannedChainPrototype.initialShape(bloom)
        // A quiet, bright strike leaves room for the existing low-mode driver to arrive later.
        // This fixed listening context changes only BLOOM; DRIVE RATE keeps its factory context.
        val bloomDemo = bloom.copy(dampingPerSecond = 1.4f, restFraction = .05f)
        val bloomDemoShape = conditionShape(FloatArray(128) { i ->
            val p = 2.0 * PI * i / 128
            (.12 * sin(p) + sin(2 * p) + .65 * sin(3 * p + .3) + .4 * sin(5 * p)).toFloat()
        }, .12)
        val rough = FloatArray(128) { i ->
            val p = 2.0 * PI * i / 128
            (sin(p) + .8 * sin(24 * p + .3) + .6 * sin(31 * p) + .5 * sin(47 * p)).toFloat()
        }.let { conditionShape(it) }
        val bend = passive.copy(name = "Rough BEND C4", mechanicalHz = .8f, dampingPerSecond = .55f, restFraction = .10f)
        return listOf(
            numeric("motion", "MOTION", "motion", "ratio", passive, passiveShape, "factory_metal", listOf(0f, .25f, .5f, .75f, 1f),
                "Sets the speed of the physical evolution while the scanner stays at C4. Zero holds the whole initial shape still.",
                "Listen for slower or faster changes in the waveform's color; the scanner rate remains the same.",
                "This scales spring/centering rates, damping and driven motion together. It is not a vibrato or pitch control.") { c, n -> c.copy(motion = n.toFloat()) },
            numeric("damp", "DAMP", "dampingPerSecond", "s^-1", passive, passiveShape, "factory_metal", listOf(0f, .5f, 1f, 3f, 6f),
                "Removes mechanical velocity through viscous damping. The three-second amplitude envelope stays fixed.",
                "Listen for changes in how long the chain keeps moving and how quickly its color settles.",
                "Large damping can slow motion instead of making every component decay faster. Matched mode can hide the lost energy.") { c, n -> c.copy(dampingPerSecond = n.toFloat()) },
            numeric("bend", "BEND", "nonlinear", "coefficient", bend, rough, "authored_rough_1_24_31_47", listOf(0f, 1f, 2f, 4f, 8f),
                "Adds a cubic term to the spring force. A fixed rough shape puts neighboring masses under enough strain to exercise it.",
                "Listen for changes in the evolving tone as strained springs react differently at larger displacement.",
                "This is amplitude-dependent mechanical stiffness, not pitch bend or an output distortion. The rough context is deliberate; smooth shapes can make this control subtle.") { c, n -> c.copy(nonlinear = n.toFloat()) },
            numeric("bloom", "BLOOM", "drive", "force amount", bloomDemo, bloomDemoShape, "authored_quiet_bright_bloom_1_2_3_5", listOf(0f, .25f, .5f, .8f, 1f),
                "Adds a seeded low-frequency force after a quiet, bright strike. Only the force amount changes across these five examples.",
                "Compare BLOOM OFF with the selected amount. Listen one to two seconds after the strike for a lower body developing.",
                "The quiet starting strike makes the delayed force easier to hear. The force ends within two seconds; matched mode removes overall level differences.") { c, n -> c.copy(drive = n.toFloat()) },
            numeric("tune", "TUNE", "midi", "MIDI note", passive, passiveShape, "factory_metal", listOf(48, 54, 60, 66, 72),
                "Changes only the audio-rate scanner pitch. The physical chain follows the same slow motion.",
                "Listen for the same evolving waveform read at C3, F#3, C4, F#4 and C5.",
                "The spatial harmonic budget changes with scanner pitch to protect the output band. A detector can report a harmonic rather than the scanner fundamental.") { c, n -> c.copy(midi = n.toInt()) },
            numeric("attack", "ATTACK", "attackSeconds", "seconds", passive, passiveShape, "factory_metal", listOf(.002f, .008f, .035f, .15f, .4f),
                "Sets the output envelope's onset time. The chain still begins evolving at the start of the file.",
                "Listen for an immediate onset or a slower reveal of the already moving shape.",
                "This changes the amplitude envelope, not the physical excitation. Matched mode partly compensates for the lost onset energy.") { c, n -> c.copy(attackSeconds = n.toFloat()) },
            numeric("release", "RELEASE", "releaseSeconds", "seconds", passive, passiveShape, "factory_metal", listOf(.05f, .15f, .25f, .6f, 1f),
                "Sets the fade at the end of each fixed three-second file. This passive context has no driver.",
                "Listen near the end for a short exit or a longer fade.",
                "This is a finite sample-tail envelope, not MIDI note-off behavior or a HOLD loop. Raw mode preserves its energy effect.") { c, n -> c.copy(releaseSeconds = n.toFloat()) },
            numeric("memory", "MEMORY", "restFraction", "fraction", passive, passiveShape, "factory_metal", listOf(0f, .06f, .12f, .24f, .35f),
                "Pulls masses toward their original positions while reducing the neighboring spring share, keeping the first mechanical mode's nominal frequency fixed.",
                "Listen for how much of the original waveform character returns as the motion settles.",
                "This is restorative centering, not a delay or memory buffer. It also changes the allocation of spring and centering forces.") { c, n -> c.copy(restFraction = n.toFloat()) },
            numeric("drive_rate", "DRIVE RATE", "driveHz", "Hz", bloom, bloomShape, "factory_bloom", listOf(.25f, .45f, .67f, 1f, 2f),
                "Changes the frequency of the seeded driver; its amount stays at 0.8 and its force window stays bounded.",
                "Listen for differences in the pace and pattern of the added motion, including rates near the chain's mechanical mode.",
                "This is a force rate, not scanner pitch. The driver also contains a fixed 0.618-ratio component; its effect depends on resonance and phase.") { c, n -> c.copy(driveHz = n.toFloat()) },
        )
    }

    private fun numeric(
        id: String, label: String, parameter: String, unit: String, base: ScannedChainPrototype.Config,
        shape: FloatArray, shapeId: String, values: List<Number>, explanation: String, expected: String, caveat: String,
        update: (ScannedChainPrototype.Config, Number) -> ScannedChainPrototype.Config,
    ): Control {
        val default = configuration(base).getValue(parameter) as Number
        val steps = values.mapIndexed { index, value -> Step("${id}_${index + 1}", if (id == "tune") noteName(value.toInt()) else value.toString(), value,
            update(base, value), shape.copyOf(), shapeId, value.toDouble() == default.toDouble()) }
        return Control(id, label, parameter, unit, explanation, expected, caveat, base, shape.copyOf(), shapeId, default, steps,
            if (id == "bend") linkedMapOf("kind" to "DRAWN", "formula" to "sin(p) + 0.8*sin(24*p + 0.3) + 0.6*sin(31*p) + 0.5*sin(47*p); p=2*pi*i/128; initial-only zero-mean and peak=0.35")
            else if (id == "bloom") linkedMapOf(
                "kind" to "DRAWN",
                "formula" to "0.12*sin(p) + sin(2*p) + 0.65*sin(3*p + 0.3) + 0.4*sin(5*p); p=2*pi*i/128",
                "conditioning" to "Initial-only zero mean and one scalar to authored peak 0.12 before the unchanged core applies velocity. No root is added to the output.",
                "authoredPeak" to .12,
                "contextRationale" to "A quiet bright strike with fixed damping 1.4 and restorative fraction 0.05 exposes the existing low-mode driver's delayed contribution. Only drive amount changes among these five steps; DRIVE RATE retains the original factory context.",
            )
            else linkedMapOf("kind" to "FACTORY", "factoryShape" to shapeId),
            referenceValue = if (id == "bloom") 0f else default,
            referenceLabel = if (id == "bloom") "BLOOM OFF" else "Authored default",
            referenceExplanation = if (id == "bloom") "Compare no added force (0) with the selected BLOOM amount. The authored default is still 0.8."
                else "Compare the authored default with the selected value.")
    }

    private fun shapeControl(accepted: Map<String, JsonValue>): Control {
        val base = ScannedChainPrototype.factoryConfigs().single { it.voice == ScannedChainPrototype.Voice.PLUCK }.copy(name = "SHAPE fixed PLUCK C4")
        val factoryShapes = ScannedChainPrototype.Voice.entries.map { voice ->
            voice.name.lowercase() to ScannedChainPrototype.initialShape(base.copy(voice = voice))
        }
        val room = accepted.getValue("pairs").arr().single { it.obj().getValue("id").str() == "capture_room_c4" }.obj()
        val roomInput = room.getValue("inputShape").arr().map { it.num().toFloat() }.toFloatArray()
        val shapes = factoryShapes + listOf("drawn" to ScannedChainPrototype.drawnShape(), "room" to roomInput)
        val initial = shapes.first().second
        val labels = mapOf("pluck" to "Factory PLUCK", "metal" to "Factory METAL", "bloom" to "Factory BLOOM", "drawn" to "Drawn line", "room" to "Room timbral imprint")
        val steps = shapes.mapIndexed { index, (id, shape) -> Step("shape_${index + 1}", labels.getValue(id), id, base, shape.copyOf(), id, id == "pluck") }
        return Control("shape", "SHAPE", "initialShape", "source shape",
            "Chooses only the initial 128-point displacement. Every option uses the same passive PLUCK physics, scanner, seed and envelope.",
            "Listen for different starting colors and for how the same physics moves each starting shape.",
            "The room option is an arbitrary saved 20 ms timbral imprint, not its original pitch or envelope. Selecting Factory BLOOM here does not enable the BLOOM driver.",
            base, initial, "pluck", "pluck", steps,
            linkedMapOf("factoryShapes" to "ScannedChainPrototype.initialShape(); shapes only, no voice configuration switching",
                "drawnShape" to "ScannedChainPrototype.drawnShape()", "roomSource" to room.getValue("sourceProvenance"),
                "roomAcceptedInputShapeSha256" to room.getValue("inputShapeSha256")))
    }

    private fun validateIsolation(control: Control, step: Step) {
        val differences = differences(configuration(control.baseConfig), configuration(step.config))
        if (control.parameter == "initialShape") check(differences.isEmpty()) { "SHAPE changed config: $differences" }
        else {
            check(differences.keys.all { it == control.parameter }) { "${step.id} changed unintended parameters: $differences" }
            check(step.inputShape.contentEquals(control.inputShape)) { "${step.id} changed the fixed input shape" }
            check(step.isDefault == differences.isEmpty()) { "${step.id} default marker disagrees with real config" }
        }
    }

    private fun render(step: Step): Rendered {
        val began = System.nanoTime()
        val result = ScannedChainPrototype.render(step.config, step.inputShape.copyOf())
        check(result.raw.sampleRate == RATE && result.raw.channels == 1 && result.raw.durationSeconds <= 3.00001f)
        check(result.raw.samples.all { it.isFinite() } && result.diagnostics.finite)
        return Rendered(step, result, (System.nanoTime() - began) / 1e6)
    }

    private fun export(root: File, control: Control, rendered: Rendered, rawGain: Double, matchedGain: Double): Map<String, Any?> {
        val step = rendered.step
        val rawPath = "sweeps/raw/${step.id}.wav"
        val matchedPath = "sweeps/matched/${step.id}.wav"
        val raw = Snip(applyGain(rendered.result.raw.samples, rawGain), 1, RATE)
        val matched = Snip(applyGain(rendered.result.raw.samples, matchedGain), 1, RATE)
        check(raw.peak() <= PEAK_CEILING + 1e-6 && matched.peak() <= PEAK_CEILING + 1e-6)
        WavWriter.write(File(root, rawPath), raw, WavWriter.BitDepth.PCM_24)
        WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_24)
        val physicalMetrics = metrics(rendered.result.raw)
        return linkedMapOf(
            "id" to step.id, "label" to step.label, "value" to step.value, "actualParameterValue" to step.value,
            "isDefault" to step.isDefault, "config" to configuration(step.config),
            "isReference" to sameValue(step.value, control.referenceValue),
            "configDifferences" to differences(configuration(control.baseConfig), configuration(step.config)),
            "shapeChanged" to (control.id == "shape" && !step.isDefault),
            "inputShape" to step.inputShape.toList(), "inputShapeSha256" to floatHash(step.inputShape),
            "shape" to rendered.result.initialShape.toList(), "shapeSha256" to floatHash(rendered.result.initialShape),
            "shapeId" to step.shapeId, "seed" to step.config.seed,
            "rawFile" to rawPath, "matchedFile" to matchedPath,
            "rawSha256" to sha256(File(root, rawPath).readBytes()), "matchedSha256" to sha256(File(root, matchedPath).readBytes()),
            "rawFloatSha256" to floatHash(rendered.result.raw.samples), "rawExportGain" to rawGain,
            "matchedGain" to matchedGain, "gain" to matchedGain, "renderMs" to rendered.milliseconds,
            "metrics" to physicalMetrics,
            "rawExportMetrics" to exportMetrics(physicalMetrics, WavReader.read(File(root, rawPath))),
            "matchedMetrics" to exportMetrics(physicalMetrics, WavReader.read(File(root, matchedPath))),
            "diagnostics" to diagnostics(rendered.result.diagnostics),
        )
    }

    internal fun matchedGains(samples: List<FloatArray>, targetRms: Double = TARGET_RMS, peakCeiling: Double = PEAK_CEILING): LevelPlan {
        require(targetRms > 0 && targetRms.isFinite() && peakCeiling > 0 && peakCeiling <= 1 && peakCeiling.isFinite())
        require(samples.all { a -> a.all { it.isFinite() } })
        val target = samples.filter { rms(it) > SILENCE_RMS }.fold(targetRms) { t, a -> minOf(t, peakCeiling * rms(a) / peak(a).coerceAtLeast(1e-30)) }
        return LevelPlan(target, samples.map { a -> if (rms(a) > SILENCE_RMS) target / rms(a) else 1.0 })
    }

    internal fun rawExportGain(samples: List<FloatArray>, peakCeiling: Double = PEAK_CEILING): Double {
        require(peakCeiling > 0 && peakCeiling <= 1 && peakCeiling.isFinite())
        require(samples.all { a -> a.all { it.isFinite() } })
        val maximum = samples.maxOfOrNull(::peak) ?: 0.0
        return if (maximum > peakCeiling) peakCeiling / maximum else 1.0
    }

    internal fun applyGain(samples: FloatArray, gain: Double): FloatArray {
        require(gain >= 0 && gain.isFinite())
        return FloatArray(samples.size) { (samples[it] * gain).toFloat() }
    }

    private fun differences(base: Map<String, Any?>, current: Map<String, Any?>): Map<String, Any?> =
        current.filter { (key, value) -> value != base[key] }.mapValues { (key, value) -> linkedMapOf("base" to base[key], "value" to value) }

    private fun sameValue(a: Any, b: Any): Boolean = if (a is Number && b is Number) a.toDouble() == b.toDouble() else a == b

    private fun configuration(c: ScannedChainPrototype.Config): Map<String, Any?> = linkedMapOf(
        "name" to c.name, "voice" to c.voice.name, "midi" to c.midi, "velocity" to c.velocity, "seconds" to c.seconds,
        "motion" to c.motion, "frozen" to c.frozen, "physicsRate" to c.physicsRate, "snapshotRate" to c.snapshotRate,
        "mechanicalHz" to c.mechanicalHz, "dampingPerSecond" to c.dampingPerSecond, "nonlinear" to c.nonlinear,
        "restFraction" to c.restFraction, "drive" to c.drive, "driveHz" to c.driveHz, "seed" to c.seed,
        "attackSeconds" to c.attackSeconds, "releaseSeconds" to c.releaseSeconds,
    )

    private fun diagnostics(d: ScannedChainPrototype.Diagnostics): Map<String, Any?> = linkedMapOf(
        "initialEnergy" to d.initialEnergy, "finalEnergy" to d.finalEnergy, "maxEnergy" to d.maxEnergy,
        "driveWork" to d.driveWork, "dampingLoss" to d.dampingLoss, "maxDisplacement" to d.maxDisplacement,
        "maxVelocity" to d.maxVelocity, "maxCourant" to d.maxCourant, "shapeMotionRms" to d.shapeMotionRms,
        "physicsSteps" to d.physicsSteps, "finite" to d.finite, "retainedHarmonics" to d.retainedHarmonics,
        "scannerHz" to d.scannerHz, "mechanicalFundamentalHz" to d.mechanicalFundamentalHz, "rawRms" to d.rawRms,
        "maxLinearizedModeHz" to d.maxLinearizedModeHz, "energyBalanceError" to d.energyBalanceError,
    )

    private fun metrics(snip: Snip): Map<String, Any?> {
        val pitch = if (rms(snip.samples) > SILENCE_RMS) Pitch.detect(snip, .15f, .30f) else null
        return linkedMapOf("rms" to rms(snip.samples), "peak" to peak(snip.samples), "dc" to snip.samples.average(),
            "finite" to snip.samples.all { it.isFinite() }, "frames" to snip.frameCount, "durationSeconds" to snip.durationSeconds,
            "pitchHz" to pitch?.hz, "pitchConfidence" to pitch?.confidence,
            "earlySpectralCentroidHz" to centroid(snip.samples, .15), "lateSpectralCentroidHz" to centroid(snip.samples, 1.5))
    }

    private fun exportMetrics(original: Map<String, Any?>, decoded: Snip): Map<String, Any?> = original +
        linkedMapOf("rms" to rms(decoded.samples), "peak" to peak(decoded.samples), "dc" to decoded.samples.average())

    private fun centroid(samples: FloatArray, seconds: Double): Double {
        val size = 8192
        val start = minOf((seconds * RATE).roundToInt(), (samples.size - size).coerceAtLeast(0))
        val spectrum = Fft.magnitudeSpectrum(samples.copyOfRange(start, minOf(samples.size, start + size)), size)
        var sum = 0.0; var weighted = 0.0
        for (i in 1 until spectrum.size) {
            val power = spectrum[i].toDouble() * spectrum[i]
            sum += power; weighted += power * i * RATE / size
        }
        return if (sum > 1e-30) weighted / sum else 0.0
    }

    private fun conditionShape(input: FloatArray, targetPeak: Double = .35): FloatArray {
        val mean = input.average()
        val centered = FloatArray(input.size) { (input[it] - mean).toFloat() }
        val gain = targetPeak / peak(centered)
        return applyGain(centered, gain)
    }

    private fun originalAudioHashes(root: File, accepted: Map<String, JsonValue>): Map<String, String> {
        val hashes = linkedMapOf<String, String>()
        for (value in accepted.getValue("pairs").arr()) {
            val pair = value.obj()
            for (variant in listOf("frozen", "moving")) {
                val clip = pair.getValue(variant).obj()
                for (kind in listOf("raw", "matched")) {
                    val path = clip.getValue("${kind}File").str()
                    val expected = clip.getValue("${kind}Sha256").str()
                    val actual = sha256(File(root, path).readBytes())
                    check(actual == expected) { "Accepted original audio fingerprint differs: $path" }
                    hashes[path] = actual
                }
            }
            (pair["sourcePreview"] as? JsonValue.Str)?.value?.let { path ->
                val expected = pair.getValue("sourcePreviewInfo").obj().getValue("sha256").str()
                val actual = sha256(File(root, path).readBytes())
                check(actual == expected) { "Accepted original source preview fingerprint differs: $path" }
                hashes[path] = actual
            }
        }
        return hashes
    }

    internal fun floatHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putFloat(it) }
        return sha256(bytes.array())
    }
    private fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1))
    private fun peak(samples: FloatArray): Double = samples.maxOfOrNull { abs(it.toDouble()) } ?: 0.0
    private fun noteName(midi: Int): String = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")[midi % 12] + (midi / 12 - 1)
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun repositoryRoot(): File = listOf(File("."), File("..")).firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?.canonicalFile ?: error("Run from the repository root or synth module")
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is Boolean -> JsonValue.Bool(value)
        is Number -> if (value.toDouble().isFinite()) JsonValue.Num(value.toDouble()) else JsonValue.Null
        is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported sweep JSON ${value.javaClass.name}")
    }
}
