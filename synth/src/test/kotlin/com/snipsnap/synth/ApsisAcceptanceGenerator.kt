package com.snipsnap.synth

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scales
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class ApsisAcceptanceEntry(val slot: Int, val preset: String, val role: String, val patch: ApsisPatch) {
    val label get() = "A%02d".format(slot)
    val held get() = Apsis.isLoop(patch.macros.getValue("HOLD"))
    val id get() = "duo-pad-${slot.toString().padStart(2, '0')}"
    fun layers() = listOf(ApsisBankLayer(1, 41, patch.copy(velocity = .35f)),
        ApsisBankLayer(42, 84, patch.copy(velocity = .65f)), ApsisBankLayer(85, 127, patch.copy(velocity = 1f)))
}

/** Download-only acceptance bank. Factory recipes and the original PASSAGE bank stay frozen. */
internal object ApsisAcceptanceBank {
    fun entries(): List<ApsisAcceptanceEntry> {
        fun pad(slot: Int, voice: ApsisVoice, preset: Int, midi: Int, role: String): ApsisAcceptanceEntry {
            val source = ApsisPresets.forVoice(voice)[preset]
            return ApsisAcceptanceEntry(slot, source.name, role,
                source.copy(name = "${source.name} ${Scales.nameOf(midi)}", midi = midi, velocity = 1f))
        }
        return buildList {
            listOf(60, 63, 67, 70).forEachIndexed { i, note -> add(pad(i + 1, ApsisVoice.GRAIN, 0, note, "Grain walk")) }
            listOf(48, 51, 55, 58).forEachIndexed { i, note -> add(pad(i + 5, ApsisVoice.ANCHOR, 0, note, "Anchor walk")) }
            add(pad(9, ApsisVoice.GRAIN, 1, 60, "Movement"))
            add(pad(10, ApsisVoice.ANCHOR, 1, 48, "Movement"))
            add(pad(11, ApsisVoice.GRAIN, 0, 48, "Lower reference"))
            add(pad(12, ApsisVoice.ANCHOR, 0, 60, "Upper reference"))
            add(pad(13, ApsisVoice.GRAIN, 0, 60, "Carry copy of A01"))
            add(pad(14, ApsisVoice.ANCHOR, 0, 48, "Carry copy of A05"))
            add(pad(15, ApsisVoice.GRAIN, 2, 60, "Held"))
            add(pad(16, ApsisVoice.ANCHOR, 2, 48, "Held"))
        }
    }
}

/** Render once, export both programs, reopen the actual WAVs, then perform those samples. */
object ApsisAcceptanceGenerator {
    const val NAME = "SnipSnap Apsis Duo"
    const val ARCHIVE = "SnipSnap-Apsis-Grain-Anchor-Kit.zip"
    @JvmStatic fun main(args: Array<String>) {
        generate(File(args.firstOrNull() ?: "testkit/apsis-acceptance"))
    }

    fun generate(root: File): File {
        root.mkdirs()
        val entries = ApsisAcceptanceBank.entries()
        val work = File(root, ".duo-work")
        check(!work.exists() || work.deleteRecursively())
        work.mkdirs()
        val destination = File(root, NAME)
        val probes = mutableMapOf<ApsisPatch, Apsis.Probe>()
        fun render(patch: ApsisPatch): Snip {
            val p = probes.getOrPut(patch) {
                Apsis.probe(patch.voice, patch.macros, patch.midi, patch.velocity, patch.config,
                    Apsis.ProbeOptions(normalize = true, recordStems = false, recordTraces = false)).also {
                    check(it.samples.contentEquals(patch.render().samples)) { "Diagnostic/native PCM differ for ${patch.name}" }
                    check(it.samples.all { v -> v.isFinite() && abs(v) <= 1f })
                    if (Apsis.isLoop(patch.macros.getValue("HOLD"))) {
                        check(it.previousCycle.isNotEmpty() && it.stateError < 1e-8 && it.seamError < Keys.MAX_SEAM_ERROR)
                    }
                }
            }
            return Snip(p.samples, 1, Dsp.RATE)
        }
        try {
            val arranged = entries.map { entry ->
                val layers = entry.layers().map { render(it.patch) }
                ArrangedPad(layers.last(), Apsis.drumClassFor(entry.patch.voice, entry.patch.macros),
                    PadRecipe(entry.patch).toJsonValue(), level = 1f, oneShot = !entry.held,
                    softVariants = layers.dropLast(1), displayName = entry.patch.name, muteGroup = entry.slot,
                    layerNames = listOf("Soft .35", "Normal .65", "Hard 1.00"),
                    source = mapOf("engine" to "APSIS", "voice" to entry.patch.voice.name,
                        "rootMidi" to entry.patch.midi.toString(), "bankId" to entry.id))
            }
            val initial = KitAssembler.assembleArranged(NAME, arranged, work, key = KeySpec.parse("Cminpent"))
            val kit = initial.copy(pads = initial.pads.map { pad ->
                val held = entries[pad.slot - 1].held
                pad.copy(loop = held, attack = if (held) 0f else null, decay = if (held) 1f else null)
            })
            KitStore.save(kit, work)
            for ((index, pad) in kit.pads.withIndex()) {
                val entry = entries[index]
                for ((i, layer) in pad.velocityLayers.withIndex()) {
                    val snip = render(entry.layers()[i].patch)
                    WavWriter.write(File(work, layer.sampleFile), snip,
                        smpl = SmplChunk(entry.patch.midi, if (entry.held) SmplChunk.Loop(0, snip.frameCount.toLong()) else null))
                }
            }
            check(!Preflight.check(kit, work).blocked())
            check(!destination.exists() || destination.deleteRecursively())
            val export = Mpc3Exporter.exportTrack(kit, work, destination, overwrite = true, mpc2Twin = true)
            val data = export.samples.first().parentFile
            KitStore.save(kit, data)
            ApsisKitGenerator.verifyPrograms(destination, kit)
            check(KitStore.load(data) == kit && !Preflight.check(kit, data).blocked())
            check(export.samples.size == 48)
            val scorePads = entries.map { entry ->
                val pad = kit.pads.single { it.slot == entry.slot }
                val layers = pad.velocityLayers.mapIndexed { index, layer ->
                    val file = File(data, layer.sampleFile)
                    check(file.readBytes().contentEquals(File(work, layer.sampleFile).readBytes())) { "Export changed ${layer.sampleFile}" }
                    val decoded = WavReader.read(file)
                    val native = render(entry.layers()[index].patch)
                    val encoded = ByteArrayOutputStream().also { WavWriter.write(it, native) }.toByteArray()
                    check(decoded.samples.contentEquals(WavReader.read(encoded).samples))
                    val sheet = requireNotNull(WavReader.readSmpl(file))
                    check(sheet.rootNote == entry.patch.midi)
                    check(sheet.loop == if (entry.held) SmplChunk.Loop(0, decoded.frameCount.toLong()) else null)
                    val relative = file.relativeTo(root).invariantSeparatorsPath
                    val recipe = "recipes/${entry.label}-v${index + 1}.json"
                    write(destination, recipe, PadRecipe(entry.layers()[index].patch).toJsonText())
                    ApsisScoreLayer(layer.velStart, layer.velEnd, entry.layers()[index].patch, relative, hash(file), decoded.samples)
                }
                write(destination, "recipes/${entry.label}.json", PadRecipe(entry.patch).toJsonText())
                ApsisScorePad(entry.slot, entry.id, entry.preset, entry.role, entry.patch, !entry.held, 1f, entry.slot, layers)
            }
            for ((a, b) in listOf(1 to 13, 5 to 14)) for (index in 0..2)
                check(scorePads[a - 1].layers[index].sha256 == scorePads[b - 1].layers[index].sha256) { "Carry copies differ" }
            val padRows = scorePads.map { pad ->
                val entry = entries[pad.slot - 1]
                linkedMapOf("slot" to pad.slot, "label" to entry.label, "id" to entry.id,
                    "name" to entry.patch.name, "presetName" to entry.preset, "voice" to entry.patch.voice.name,
                    "midi" to entry.patch.midi, "role" to entry.role, "loop" to entry.held,
                    "oneShot" to pad.oneShot, "muteGroup" to pad.muteGroup, "level" to 1,
                    "recipe" to "$NAME/recipes/${entry.label}.json", "patch" to entry.patch.toJsonValue(),
                    "layers" to pad.layers.mapIndexed { i, layer ->
                        val p = probes.getValue(layer.patch)
                        linkedMapOf("velStart" to layer.velStart, "velEnd" to layer.velEnd,
                            "renderVelocity" to layer.patch.velocity, "sample" to layer.sample,
                            "sha256" to layer.sha256, "frames" to layer.samples.size,
                            "recipe" to "$NAME/recipes/${entry.label}-v${i + 1}.json",
                            "seamError" to p.seamError, "stateError" to p.stateError)
                    })
            }
            val manifest = linkedMapOf("schema" to "snipsnap.apsis.acceptance.v1", "name" to NAME,
                "sourceRevision" to gitRevision(), "padCount" to 16, "sampleCount" to 48,
                "archive" to ARCHIVE, "program" to "$NAME/$NAME.xtd",
                "mpc2Program" to "$NAME/${NAME}_[TrackData]/$NAME.xpm",
                "velocityPolicy" to "1–41: native .35; 42–84: native .65; 85–127: native 1.00. Hardware velocity amplitude is unmeasured.",
                "playbackPolicy" to "Finite one-shots retain tails. Same-pad retrigger chokes only itself. A13/A14 are exact copies for alternating-pad overlap. A15/A16 gate the complete settled loop, release control 0.",
                "validation" to linkedMapOf("nativePcmExact" to true, "programReadbacksPassed" to true,
                    "smplReadbacksPassed" to true, "preflightPassed" to true,
                    "physicalMpcAcceptance" to "Not performed; use the included checklist."), "pads" to padRows)
            write(root, "kit-manifest.json", Json.write(json(manifest)))
            write(destination, "kit-manifest.json", Json.write(json(manifest)))
            write(destination, "README.txt", instructions(entries))
            ZipOutputStream(File(root, ARCHIVE).outputStream().buffered()).use { zip ->
                destination.walkTopDown().filter { it.isFile }.sortedBy { it.invariantSeparatorsPath }.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath).also { it.time = 0 })
                    file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
            write(root, "$ARCHIVE.sha256", "${hash(File(root, ARCHIVE))}  $ARCHIVE")
            val scores = scores(scorePads)
            val clips = scores.map { score ->
                write(root, "scores/${score.id}.json", Json.write(score.toJsonValue()))
                val replay = ApsisMusicalScore.fromJsonValue(score.toJsonValue()) { path, sha -> readSample(root, path, sha) }
                val samples = ApsisScorePlayer.render(replay)
                check(samples.contentEquals(ApsisScorePlayer.render(score)))
                check(samples.maxOf { abs(it) } <= 1f) { "Score exceeds PCM headroom" }
                val gain = matchGain(samples)
                for ((suffix, level) in listOf("raw" to 1.0, "matched" to gain)) {
                    val target = File(root, "audio/${score.id}_$suffix.wav").also { it.parentFile.mkdirs() }
                    WavWriter.write(target, Snip(FloatArray(samples.size) { i -> (samples[i] * level).toFloat() }, 1, Dsp.RATE))
                }
                linkedMapOf("id" to score.id, "title" to score.title, "description" to score.description,
                    "kind" to score.kind.name, "score" to "scores/${score.id}.json", "frames" to samples.size,
                    "rawFile" to "audio/${score.id}_raw.wav", "matchedFile" to "audio/${score.id}_matched.wav",
                    "rawPeak" to samples.maxOf { abs(it) }, "matchedGain" to gain, "monitorGain" to score.monitorGain)
            }
            write(root, "performances.json", Json.write(json(mapOf("clips" to clips))))
            println("APSIS acceptance: 16 pads, 48 native PCM24 WAVs, ${scores.size} exported-sample scores. Physical MPC acceptance pending.")
            return File(root, ARCHIVE)
        } finally { work.deleteRecursively() }
    }

    internal fun scores(pads: List<ApsisScorePad>): List<ApsisMusicalScore> {
        fun frame(seconds: Double) = (seconds * Dsp.RATE).roundToInt()
        fun event(at: Double, slot: Int, velocity: Int = 127, gate: Double? = null): ApsisScoreEvent {
            val pad = pads.single { it.slot == slot }; val layer = pad.layer(velocity)
            return ApsisScoreEvent(frame(at), slot, pad.patch.midi, velocity, pad.preset,
                layer.patch.config.modelVersion, layer.patch.velocity, gate?.let(::frame))
        }
        fun score(id: String, title: String, description: String, seconds: Double, events: List<ApsisScoreEvent>,
            kind: ApsisScoreKind = ApsisScoreKind.PAD_PERFORMANCE) = ApsisMusicalScore("duo-$id", title, description,
                id, frame(seconds), .35f, pads, events, kind, 80)
        return listOf(
            score("performance", "Grain and Anchor / kit performance", "80 BPM; native hard layers at unity MIDI velocity. Different pads retain their complete approaches and tails.", 16.5,
                listOf(event(0.0, 5), event(0.0, 1), event(1.5, 2), event(3.0, 7), event(3.0, 3), event(4.5, 4),
                    event(6.0, 6), event(6.0, 9), event(7.5, 10), event(9.0, 11), event(9.0, 12), event(10.5, 8), event(10.5, 2))),
            score("held", "Held voices / wrap, retrigger, release", "A15 starts at 0, repeats across its join, retriggers at 8 and gates off at 12. A16 starts at 1 and gates off at 11. No preview release fade.", 14.0,
                listOf(event(0.0, 15, gate = 13.0), event(1.0, 16, gate = 10.0), event(8.0, 15, gate = 4.0))),
            score("grain-touch", "Grain / soft, normal, hard", "Actual exported .35 / .65 / 1.00 layers at unity, 6.25 seconds apart. This inspects native samples before hardware amplitude scaling.", 18.5,
                listOf(event(0.0, 1, 20), event(6.25, 1, 63), event(12.5, 1)), ApsisScoreKind.LAYER_INSPECTION),
            score("anchor-touch", "Anchor / soft, normal, hard", "Actual exported .35 / .65 / 1.00 layers at unity, 6.25 seconds apart. This inspects native samples before hardware amplitude scaling.", 18.5,
                listOf(event(0.0, 5, 20), event(6.25, 5, 63), event(12.5, 5)), ApsisScoreKind.LAYER_INSPECTION),
            score("grain-carry", "Grain / retrigger, then carry", "First A01 twice, .75 seconds apart. Then A01 and its exact A13 copy: different pads let the earlier tail continue.", 14.25,
                listOf(event(0.0, 1), event(.75, 1), event(7.5, 1), event(8.25, 13))),
            score("anchor-carry", "Anchor / retrigger, then carry", "First A05 twice, .75 seconds apart. Then A05 and its exact A14 copy: different pads let the earlier tail continue.", 14.25,
                listOf(event(0.0, 5), event(.75, 5), event(7.5, 5), event(8.25, 14))),
        )
    }

    internal fun readSample(root: File, relative: String, sha: String): FloatArray {
        val file = File(root, relative).canonicalFile
        require(file.toPath().startsWith(root.canonicalFile.toPath()) && file.isFile) { "Score sample is outside its bank" }
        require(hash(file) == sha) { "Score WAV checksum differs: $relative" }
        return WavReader.read(file).samples
    }
    private fun instructions(entries: List<ApsisAcceptanceEntry>) = buildString {
        appendLine(NAME)
        appendLine("MPC 3: load $NAME.xtd with its _[TrackData] folder beside it.")
        appendLine("MPC 2: load $NAME.xpm inside that folder. Copy the entire kit folder.")
        appendLine("48 mono 44.1 kHz PCM24 WAVs; no FX, matching or transposition baked in.")
        appendLine("All pads: 1–41 soft .35, 42–84 normal .65, 85–127 hard 1.00 native excitation.")
        appendLine("The hardware's additional velocity-to-amplitude curve is unmeasured.")
        appendLine()
        entries.forEach { appendLine("${it.label}  ${it.patch.name}  ${it.role}") }
        appendLine()
        appendLine("PHYSICAL ACCEPTANCE CHECKLIST (not performed by automated checks)")
        appendLine("1. Listen to A01–04 and A05–08: roots and voice identity through the note walk.")
        appendLine("2. Play A01/A05 softly, normally and firmly: layer boundaries, color and level.")
        appendLine("3. Retrigger A01 alone, then alternate A01/A13. Repeat with A05/A14; check tail overlap.")
        appendLine("4. Hold A15/A16 across several full joins. Release and retrigger; check clicks and response.")
        appendLine("5. Compare with the exported-sample scores. Approaches are retained; no arrival is trimmed to a beat.")
        appendLine("6. Record phone render latency and MPC release/retrigger behavior separately.")
        appendLine("Finite note-off is ignored. Held pads loop the full buffer, release control 0, no attack prefix.")
        appendLine("Each pad has its own mute group. A13/A14 are exact sample copies for overlap comparisons.")
        appendLine("Native program readback, SMPL metadata, recipe and PCM verification passed.")
    }
    private fun matchGain(samples: FloatArray): Double {
        val window = Dsp.RATE / 5
        var sum = 0.0; var loudest = 0.0
        for (i in samples.indices) {
            sum += samples[i].toDouble() * samples[i]
            if (i >= window) sum -= samples[i - window].toDouble() * samples[i - window]
            if (i >= window - 1) loudest = maxOf(loudest, sqrt(sum.coerceAtLeast(0.0) / window))
        }
        return minOf(.12 / loudest.coerceAtLeast(1e-20), .85 / samples.maxOf { abs(it).toDouble() }.coerceAtLeast(1e-20))
    }
    internal fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun write(root: File, path: String, value: String) { File(root, path).also { it.parentFile.mkdirs() }.writeText(value.trimEnd() + "\n") }
    private fun gitRevision() = ProcessBuilder("git", "rev-parse", "HEAD").start().inputStream.bufferedReader().readText().trim()
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Number -> JsonValue.Num(value.toDouble())
        is Boolean -> JsonValue.Bool(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported evidence $value")
    }
}
