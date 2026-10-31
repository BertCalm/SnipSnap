package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitStore
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal enum class ApsisScoreKind { PAD_PERFORMANCE, LAYER_INSPECTION }

/** A PCM24 sample already read back from the exported kit, never a fresh note render. */
internal data class ApsisScoreLayer(
    val velStart: Int, val velEnd: Int, val patch: ApsisPatch,
    val sample: String, val sha256: String, val samples: FloatArray,
)

internal data class ApsisScorePad(
    val slot: Int, val id: String, val preset: String, val role: String,
    val patch: ApsisPatch, val oneShot: Boolean, val level: Float,
    val muteGroup: Int, val layers: List<ApsisScoreLayer>,
) {
    init {
        require(slot in 1..16 && muteGroup == slot)
        require(level.isFinite() && level in 0f..1f)
        require(layers.isNotEmpty())
        require(layers.first().velStart == 1 && layers.last().velEnd == 127)
        for ((i, layer) in layers.withIndex()) {
            require(layer.velStart <= layer.velEnd && layer.samples.isNotEmpty())
            require(layer.samples.all { it.isFinite() })
            if (i > 0) require(layer.velStart == layers[i - 1].velEnd + 1)
            require(layer.patch.midi == patch.midi && layer.patch.config == patch.config)
        }
    }
    fun layer(velocity: Int) = layers.single { velocity in it.velStart..it.velEnd }
}

internal data class ApsisScoreEvent(
    val atFrame: Int, val slot: Int, val midi: Int, val velocity: Int,
    val preset: String, val modelVersion: Int, val nativeVelocity: Float,
    val gateFrames: Int? = null, val requestedGateFrames: Int? = gateFrames,
)

/** Explicit sampler score. Finite note-off is ignored; self-mute and held note-off are exact cuts. */
internal data class ApsisMusicalScore(
    val id: String, val title: String, val description: String, val role: String,
    val frames: Int, val monitorGain: Float, val pads: List<ApsisScorePad>,
    val events: List<ApsisScoreEvent>, val kind: ApsisScoreKind = ApsisScoreKind.PAD_PERFORMANCE,
    val tempoBpm: Int = 80,
) {
    init {
        require(frames in 1..Dsp.RATE * 30 && monitorGain.isFinite() && monitorGain in 0f..1f)
        require(tempoBpm in 20..300 && events.isNotEmpty() && events.size <= 128)
        require(pads.map { it.slot }.distinct().size == pads.size)
        require(events.map { it.atFrame to it.slot }.distinct().size == events.size)
        for (event in events) {
            require(event.atFrame >= 0 && event.atFrame < frames && event.velocity in 1..127)
            val pad = pads.single { it.slot == event.slot }
            val layer = pad.layer(event.velocity)
            require(event.midi == pad.patch.midi && event.preset == pad.preset)
            require(event.modelVersion == layer.patch.config.modelVersion && event.nativeVelocity == layer.patch.velocity)
            if (kind == ApsisScoreKind.PAD_PERFORMANCE) require(event.velocity == 127) {
                "Primary performances use unity MIDI velocity; the hardware amplitude curve is unmeasured"
            }
            if (!pad.oneShot) require(event.gateFrames != null && event.gateFrames > 0)
        }
    }

    fun toJsonValue(): JsonValue = apsisJson(linkedMapOf(
        "schema" to "snipsnap.apsis.score.v1", "id" to id, "title" to title,
        "description" to description, "role" to role, "kind" to kind.name,
        "sampleRate" to Dsp.RATE, "frames" to frames, "tempoBpm" to tempoBpm,
        "monitorGain" to monitorGain,
        "onsetPolicy" to "Every onset is a pad trigger. The approach is retained; no contact peak is trimmed or silently moved onto the beat.",
        "velocityPolicy" to if (kind == ApsisScoreKind.PAD_PERFORMANCE)
            "All note velocities are 127/unity; rendered patch velocities remain inside the exact exported samples. No hardware amplitude curve is assumed."
            else "Select actual exported velocity layers at unity before hardware velocity scaling. This is a sample-layer inspection, not a hardware performance.",
        "finitePolicy" to "ONE_SHOT ignores note-off and retains its complete tail unless another trigger of the SAME pad chokes it. Different pads keep ringing.",
        "heldPolicy" to "NOTE_ON begins in the settled loop, without an attack prefix. Repeat [0, sampleFrames), restarting at frame zero on each trigger. Gate-off is an immediate cut; release=0. No preview fade.",
        "retriggerPolicy" to "Self muteGroup equals slot: a new trigger cuts the preceding voice of the same pad only.",
        "pads" to pads.map { pad -> mapOf(
            "slot" to pad.slot, "id" to pad.id, "preset" to pad.preset, "role" to pad.role,
            "patch" to apsisFromJson(pad.patch.toJsonValue()), "oneShot" to pad.oneShot,
            "level" to pad.level, "muteGroup" to pad.muteGroup, "loopStartFrame" to 0,
            "releaseFrames" to 0, "layers" to pad.layers.map { layer -> mapOf(
                "velStart" to layer.velStart, "velEnd" to layer.velEnd,
                "patch" to apsisFromJson(layer.patch.toJsonValue()), "sample" to layer.sample,
                "sha256" to layer.sha256, "frames" to layer.samples.size) }) },
        "events" to events.map { e -> mapOf(
            "atFrame" to e.atFrame, "onsetSeconds" to e.atFrame.toDouble() / Dsp.RATE,
            "beat" to e.atFrame.toDouble() / Dsp.RATE * tempoBpm / 60,
            "slot" to e.slot, "midi" to e.midi, "velocity" to e.velocity,
            "preset" to e.preset, "modelVersion" to e.modelVersion, "nativeVelocity" to e.nativeVelocity,
            "gateFrames" to e.gateFrames, "requestedGateFrames" to e.requestedGateFrames,
            "noteOffFrame" to e.gateFrames?.let { e.atFrame + it },
            "playback" to if (pads.single { it.slot == e.slot }.oneShot) "ONE_SHOT" else "GATED_LOOP") },
    ))

    companion object {
        fun fromJsonValue(value: JsonValue, load: (String, String) -> FloatArray): ApsisMusicalScore {
            val o = value.obj()
            require(o.getValue("schema").str() == "snipsnap.apsis.score.v1")
            require(o.getValue("sampleRate").int() == Dsp.RATE)
            val pads = o.getValue("pads").arr().map { item ->
                val p = item.obj()
                require(p.getValue("loopStartFrame").int() == 0 && p.getValue("releaseFrames").int() == 0)
                ApsisScorePad(p.getValue("slot").int(), p.getValue("id").str(),
                    p.getValue("preset").str(), p.getValue("role").str(),
                    ApsisPatch.fromJsonValue(p.getValue("patch")), p.getValue("oneShot").bool(),
                    p.getValue("level").num().toFloat(), p.getValue("muteGroup").int(),
                    p.getValue("layers").arr().map { entry ->
                        val l = entry.obj(); val path = l.getValue("sample").str(); val hash = l.getValue("sha256").str()
                        val samples = load(path, hash)
                        require(samples.size == l.getValue("frames").int()) { "$path frame count differs from score" }
                        ApsisScoreLayer(l.getValue("velStart").int(), l.getValue("velEnd").int(),
                            ApsisPatch.fromJsonValue(l.getValue("patch")), path, hash, samples)
                    })
            }
            fun optionalInt(obj: Map<String, JsonValue>, key: String) = obj[key]?.takeUnless { it is JsonValue.Null }?.int()
            val events = o.getValue("events").arr().map { item ->
                val e = item.obj()
                ApsisScoreEvent(e.getValue("atFrame").int(), e.getValue("slot").int(), e.getValue("midi").int(),
                    e.getValue("velocity").int(), e.getValue("preset").str(), e.getValue("modelVersion").int(),
                    e.getValue("nativeVelocity").num().toFloat(), optionalInt(e, "gateFrames"), optionalInt(e, "requestedGateFrames"))
            }
            return ApsisMusicalScore(o.getValue("id").str(), o.getValue("title").str(),
                o.getValue("description").str(), o.getValue("role").str(), o.getValue("frames").int(),
                o.getValue("monitorGain").num().toFloat(), pads, events,
                ApsisScoreKind.valueOf(o.getValue("kind").str()), o.getValue("tempoBpm").int())
        }
    }
}

internal object ApsisScorePlayer {
    /** Sum the exact exported samples. The score owns the only monitor gain; no FX or normalization. */
    fun render(score: ApsisMusicalScore): FloatArray {
        val out = FloatArray(score.frames)
        val events = score.events.sortedWith(compareBy<ApsisScoreEvent> { it.atFrame }.thenBy { it.slot })
        for ((index, event) in events.withIndex()) {
            val pad = score.pads.single { it.slot == event.slot }
            val sample = pad.layer(event.velocity).samples
            val intended = if (pad.oneShot) sample.size else requireNotNull(event.gateFrames)
            val nextTrigger = events.drop(index + 1).firstOrNull { it.slot == event.slot }?.atFrame
            val length = minOf(intended, nextTrigger?.minus(event.atFrame) ?: intended)
            require(event.atFrame + length <= out.size) { "${score.id}: score cuts an unchoked tail; extend the score" }
            val gain = score.monitorGain * pad.level
            for (frame in 0 until length) out[event.atFrame + frame] += sample[if (pad.oneShot) frame else frame % sample.size] * gain
        }
        require(out.all { it.isFinite() })
        return out
    }
}

/** Musical contexts made from the downloadable kit's actual quantized WAVs, not substitute renders. */
object ApsisMusicalAuditionGenerator {
    private const val LISTENING_TARGET = .12f
    private const val PEAK_CEILING = .90f

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "--score") {
            require(args.size == 4) { "Use --score score.json audition-root output.wav" }
            val root = File(args[2]).canonicalFile
            val score = ApsisMusicalScore.fromJsonValue(Json.parse(File(args[1]).readText())) { path, hash -> readSample(root, path, hash) }
            val samples = ApsisScorePlayer.render(score)
            require(samples.maxOf { abs(it) } <= 1f) { "Score exceeds PCM headroom" }
            WavWriter.write(File(args[3]), Snip(samples, 1, Dsp.RATE), WavWriter.BitDepth.PCM_24)
            return
        }
        require(args.size <= 2) { "Use output-folder exported-kit-folder, or --score score.json audition-root output.wav" }
        val root = File(args.firstOrNull() ?: "../testkit/apsis-musical").absoluteFile.also { it.mkdirs() }
        val kitFolder = File(args.getOrNull(1) ?: File(root, ApsisKitGenerator.KIT_NAME).path).canonicalFile
        val pads = loadExportedBank(kitFolder, root)
        val initial = scores(pads, .25f)
        val peakAtQuarter = initial.maxOf { score -> ApsisScorePlayer.render(score).maxOf { abs(it) } }
        val monitor = .25f * minOf(1f, PEAK_CEILING / peakAtQuarter.coerceAtLeast(1e-9f))
        val prepared = scores(pads, monitor).map { score -> score to ApsisScorePlayer.render(score) }
        val gains = prepared.groupBy { (score, _) -> comparisonGroup(score) }.mapValues { (_, group) ->
            val loudness = group.maxOf { (_, samples) -> Loudness.of(Snip(samples, 1, Dsp.RATE)) }
            val peak = group.maxOf { (_, samples) -> samples.maxOf { abs(it) } }
            minOf(LISTENING_TARGET / loudness.coerceAtLeast(1e-9f), PEAK_CEILING / peak.coerceAtLeast(1e-9f))
        }
        val clips = prepared.map { (score, samples) ->
            val rawPath = "audio/${score.id}_raw.wav"
            val matchedPath = "audio/${score.id}_matched.wav"
            val scorePath = "scores/${score.id}.json"
            val gain = gains.getValue(comparisonGroup(score))
            writeWav(File(root, rawPath), samples, 1f)
            writeWav(File(root, matchedPath), samples, gain)
            writeJson(File(root, scorePath), score.toJsonValue())
            val replay = ApsisMusicalScore.fromJsonValue(Json.parse(File(root, scorePath).readText())) { path, hash -> readSample(root, path, hash) }
            check(samples.contentEquals(ApsisScorePlayer.render(replay))) { "${score.id}: saved score replay differs" }
            val colorPath = if (score.kind == ApsisScoreKind.LAYER_INSPECTION) "audio/${score.id}_color.wav" else null
            val colorGain = colorPath?.let {
                minOf(LISTENING_TARGET / Loudness.of(Snip(samples, 1, Dsp.RATE)).coerceAtLeast(1e-9f),
                    PEAK_CEILING / samples.maxOf { abs(it) }.coerceAtLeast(1e-9f)).also { g -> writeWav(File(root, it), samples, g) }
            }
            println("APSIS music ${score.id}: ${samples.size.toDouble() / Dsp.RATE}s, ${score.events.size} triggers, peak=${samples.maxOf { abs(it) }}")
            linkedMapOf<String, Any?>(
                "id" to score.id, "title" to score.title, "description" to score.description,
                "group" to if (score.kind == ApsisScoreKind.LAYER_INSPECTION) "touch" else "music",
                "kind" to when {
                    score.kind == ApsisScoreKind.LAYER_INSPECTION -> "layer-inspection"
                    score.role == "ensemble" -> "ensemble"
                    else -> "sketch"
                },
                "scoreKind" to score.kind.name, "tempoBpm" to score.tempoBpm,
                "scoreSummary" to "${score.events.size} pad triggers at ${score.tempoBpm} BPM; ${score.events.map { it.slot }.distinct().size} exact exported pads; ${if (score.kind == ApsisScoreKind.PAD_PERFORMANCE) "velocity 127/unity" else "sample layers before hardware velocity scaling"}.",
                "role" to score.role, "modelVersion" to Apsis.MODEL_VERSION,
                "rawFile" to rawPath, "matchedFile" to matchedPath, "recipeFile" to scorePath,
                "scoreFile" to scorePath, "colorFile" to colorPath, "colorGain" to colorGain,
                "loop" to false, "durationSeconds" to samples.size.toDouble() / Dsp.RATE,
                "comparisonGroup" to comparisonGroup(score), "monitorGain" to monitor,
                "gainPolicy" to "Raw uses one frozen monitor gain across all scores. Listening uses one shared gain per comparison group. Color inspection alone independently matches each layer; it is not hardware velocity scaling.",
                "metrics" to metrics(samples) + mapOf("listeningGain" to gain, "scoreReplayExact" to true),
                "events" to apsisFromJson(score.toJsonValue().obj().getValue("events")),
                "presets" to score.events.map { it.preset }.distinct(),
                "rawSha256" to sha(File(root, rawPath).readBytes()),
                "matchedSha256" to sha(File(root, matchedPath).readBytes()),
                "scoreSha256" to sha(File(root, scorePath).readBytes()),
            )
        }
        val kitManifest = File(kitFolder, "kit-manifest.json")
        val kitArchive = File(kitFolder.parentFile, ApsisKitGenerator.ARCHIVE_NAME)
        require(kitManifest.isFile && kitArchive.isFile) { "Complete the native kit generation before the musical audition" }
        val archivePath = "downloads/${ApsisKitGenerator.ARCHIVE_NAME}"
        val kitManifestPath = "downloads/apsis-kit-manifest.json"
        File(root, archivePath).also { it.parentFile.mkdirs() }.let { kitArchive.copyTo(it, overwrite = true) }
        kitManifest.copyTo(File(root, kitManifestPath), overwrite = true)
        val manifest = linkedMapOf<String, Any?>(
            "engine" to "APSIS", "title" to "Apsis / Played passages", "modelVersion" to Apsis.MODEL_VERSION,
            "sampleRate" to Dsp.RATE, "channels" to 1, "bitDepth" to 24,
            "clipCount" to clips.size, "padCount" to pads.size, "tempoBpm" to 80,
            "baseRevision" to git("rev-parse", "HEAD"), "workingTreeDirty" to !git("status", "--porcelain").isNullOrEmpty(),
            "sourceFiles" to sourceHashes(), "kitManifestSha256" to if (kitManifest.isFile) sha(kitManifest.readBytes()) else null,
            "monitorGain" to monitor,
            "listeningTarget" to mapOf("loudest200msRms" to LISTENING_TARGET, "peakCeiling" to PEAK_CEILING),
            "status" to "Dry musical proof using the exported pad samples. Hardware, phone and sonic acceptance remain listening checks.",
            "playbackPolicy" to "Finite one-shots retain tails except same-pad self-mute. Held samples repeat their settled cycle, restart at each trigger, and cut immediately at note-off. No preview-only fades or effects.",
            "velocityPolicy" to "Primary scores use 127/unity. Soft/middle/hard inspections play the exported sample layers before hardware velocity scaling; the hardware amplitude curve has not been measured.",
            "kit" to mapOf("name" to ApsisKitGenerator.KIT_NAME, "archiveFile" to archivePath,
                "manifestFile" to kitManifestPath, "archiveSha256" to sha(kitArchive.readBytes()),
                "padCount" to 16, "sampleCount" to pads.sumOf { it.layers.size },
                "formats" to listOf("MPC 3 native track", "MPC 2 XPM", "Editable SnipSnap kit", "PCM24 WAVs and complete layer recipes")),
            "clips" to clips,
            "pads" to pads.map { p ->
                mapOf(
                    "slot" to p.slot, "id" to p.id, "preset" to p.preset,
                    "role" to p.role, "midi" to p.patch.midi, "modelVersion" to p.patch.config.modelVersion,
                    "oneShot" to p.oneShot, "muteGroup" to p.muteGroup,
                    "layers" to p.layers.map { l ->
                        mapOf("velStart" to l.velStart, "velEnd" to l.velEnd,
                            "nativeVelocity" to l.patch.velocity, "sample" to l.sample, "sha256" to l.sha256,
                            "patch" to apsisFromJson(l.patch.toJsonValue()))
                    },
                )
            },
        )
        writeJson(File(root, "manifest.json"), apsisJson(manifest))
        writeJson(File(root, "data/manifest.json"), apsisJson(manifest))
        File(root, "PLAYBACK.txt").writeText("""
            APSIS musical proof / sound model ${Apsis.MODEL_VERSION}
            Seven dry musical scores use the downloadable kit's exact PCM24 samples, all at MIDI velocity 127.
            Onsets are pad triggers, not bright-contact peaks. The approach is never trimmed.
            Finite one-shots ignore note-off and keep their tails unless the same pad is retriggered.
            Self-mute groups are unique per pad. Different pads overlap freely.
            Held notes start in a settled loop with no attack prefix, repeat the complete sample and cut at gate-off.
            Native release is zero. There are no preview-only attacks, releases, reverbs or other effects.
            Held gate times may be moved at most 10 ms to a quiet sample; both requested and actual frames are saved.
            One frozen raw monitor gain ($monitor) provides linear mixing headroom; no limiter is used.
            Six sketches share listening gain; the ensemble has its own gain; the three layer inspections share a gain.
            The optional color WAVs alone match each layer independently for timbre inspection.
            Layer inspections are before hardware velocity scaling; they do not claim a measured MPC amplitude curve.
            Replay: ApsisMusicalAuditionGenerator --score scores/ID.json AUDITION_FOLDER output.wav
        """.trimIndent() + "\n")
        println("Wrote ${clips.size} APSIS musical/inspection scores using ${pads.size} exact exported pads to $root")
    }

    private fun loadExportedBank(kitFolder: File, root: File): List<ApsisScorePad> {
        require(kitFolder.isDirectory) { "Generate the Apsis kit first; missing $kitFolder" }
        val stores = kitFolder.walkTopDown().filter { it.isFile && it.name == "kit.json" }.toList()
        require(stores.size == 1) { "Expected exactly one editable kit.json under $kitFolder" }
        val kit = KitStore.load(stores.single().parentFile)
        val bank = ApsisKitBank.all()
        require(kit.pads.size == 16 && bank.size == 16)
        return bank.map { entry ->
            val pad = kit.pads.single { it.slot == entry.slot }
            require(pad.level == 1f && pad.pan == .5f && pad.tuneCoarse == 0 && pad.tuneFine == 0)
            require(pad.muteGroup == entry.slot && pad.oneShot == !entry.held)
            val actual = pad.velocityLayers.map { Triple(it.velStart, it.velEnd, it.sampleFile) }
                .ifEmpty { listOf(Triple(1, 127, pad.sampleFile)) }
            val authored = entry.layers()
            require(actual.size == authored.size)
            val layers = actual.mapIndexed { index, (start, end, file) ->
                val layer = authored[index]
                require(start == layer.velStart && end == layer.velEnd)
                val source = File(stores.single().parentFile, file)
                val path = "pads/${entry.label}_v${index + 1}.wav"
                val target = File(root, path).also { it.parentFile.mkdirs() }
                source.copyTo(target, overwrite = true)
                check(source.readBytes().contentEquals(target.readBytes()))
                val snip = WavReader.read(target)
                require(snip.sampleRate == Dsp.RATE && snip.channels == 1)
                ApsisScoreLayer(start, end, layer.patch, path, sha(target.readBytes()), snip.samples)
            }
            ApsisScorePad(entry.slot, entry.id, entry.presetName, entry.role.name,
                entry.patch, pad.oneShot, pad.level, pad.muteGroup, layers)
        }
    }

    private fun scores(bank: List<ApsisScorePad>, monitor: Float): List<ApsisMusicalScore> {
        fun frames(seconds: Double) = (seconds * Dsp.RATE).roundToInt()
        fun event(slot: Int, at: Double, gate: Double? = null, velocity: Int = 127): ApsisScoreEvent {
            val pad = bank.single { it.slot == slot }; val layer = pad.layer(velocity)
            val requested = gate?.let(::frames)
            val actual = requested?.let { quietGate(layer.samples, it) }
            return ApsisScoreEvent(frames(at), slot, pad.patch.midi, velocity, pad.preset,
                layer.patch.config.modelVersion, layer.patch.velocity, actual, requested)
        }
        fun score(id: String, title: String, description: String, role: String, seconds: Double,
            events: List<ApsisScoreEvent>, kind: ApsisScoreKind = ApsisScoreKind.PAD_PERFORMANCE) =
            ApsisMusicalScore(id, title, description, role, frames(seconds), monitor,
                bank.filter { p -> events.any { it.slot == p.slot } }, events, kind)
        val backing = listOf(event(1, 0.0), event(3, 3.0))
        return listOf(
            score("close_rush_bass", "Close Rush / bass steps",
                "C2, G2 and E-flat2 trigger 2.25 seconds apart. Each close rush follows its retained approach; different pad tails overlap.",
                "bass", 10.5, listOf(event(1, 0.0), event(3, 2.25), event(2, 4.5))),
            score("slow_apse_keys", "Slow Apse / two patient keys",
                "C3, then G3 three seconds later. Two full one-shots leave room to judge the slow passage and its recession.",
                "keys", 9.0, listOf(event(5, 0.0), event(7, 3.0))),
            score("ringing_chords", "Ringing Bodies / chord answers",
                "C minor answers as an open C-minor seventh voicing. Repeated C4/G4 pads restart; E-flat4 keeps its own tail. Six triggers, no chord effect.",
                "chords", 9.0, listOf(event(9, 0.0), event(10, 0.0), event(11, 0.0),
                    event(9, 3.0), event(11, 3.0), event(12, 3.0))),
            score("gathered_bloom", "Gathered Bloom / over the bass",
                "One gathered C4 bloom enters over two low Close Rush notes. Compare Spaced Replies: the backing, trigger time and source level are identical.",
                "gathered", 9.0, backing + event(13, .75)),
            score("spaced_replies", "Spaced Replies / over the bass",
                "The same C4 population enters with staggered orbital arrivals, over exactly the Gathered Bloom backing. Only the saved color-pad recipe changes.",
                "staggered", 9.0, backing + event(14, .75)),
            score("held_return", "Held Return / overlapping fifth",
                "Settled C3 and G3 loops overlap. C3 crosses a genuine full-cycle wrap; both voices cut at their explicit note-off, without a preview release fade.",
                "held", 11.0, listOf(event(15, 0.0, 10.5), event(16, 3.0, 7.5))),
            score("ensemble", "Returning ensemble / seventeen seconds",
                "All six recipes share one dry scene: low rushes, patient keys, chord answers, gathered and spaced color, and a looping fifth. Trigger times retain every approach.",
                "ensemble", 17.0, listOf(event(15, 0.0, 14.0), event(16, 4.0, 10.0),
                    event(1, 0.0), event(3, 4.5), event(2, 9.0),
                    event(5, 2.25), event(7, 3.75), event(6, 6.0), event(8, 8.25), event(7, 10.5),
                    event(9, 4.0), event(10, 4.0), event(11, 4.0), event(9, 10.0), event(11, 10.0), event(12, 10.0),
                    event(13, 1.0), event(14, 8.0))),
        ) + listOf(32 to "soft", 80 to "middle", 127 to "hard").map { (velocity, name) ->
            score("touch_$name", "Native layer / $name",
                "The actual exported A01 Close Rush C2 $name layer at unity, before hardware velocity scaling. Shared listening gain preserves its native strength; color inspection alone matches its level independently.",
                "touch", 6.0, listOf(event(1, 0.0, velocity = velocity)), ApsisScoreKind.LAYER_INSPECTION)
        }
    }

    private fun quietGate(samples: FloatArray, wanted: Int): Int {
        val radius = (Dsp.RATE * .010).roundToInt()
        return ((wanted - radius).coerceAtLeast(1)..wanted + radius)
            .minWith(compareBy<Int> { abs(samples[(it - 1) % samples.size]) }.thenBy { abs(it - wanted) })
    }
    private fun comparisonGroup(score: ApsisMusicalScore) = when {
        score.kind == ApsisScoreKind.LAYER_INSPECTION -> "touch-native"
        score.role == "ensemble" -> "ensemble"
        else -> "six-sketches"
    }
    private fun readSample(root: File, path: String, hash: String): FloatArray {
        val file = File(root, path).canonicalFile
        require(file.toPath().startsWith(root.canonicalFile.toPath()) && path.startsWith("pads/"))
        require(sha(file.readBytes()) == hash) { "$path does not match its score hash" }
        val snip = WavReader.read(file)
        require(snip.sampleRate == Dsp.RATE && snip.channels == 1)
        return snip.samples
    }
    private fun metrics(samples: FloatArray) = mapOf(
        "rawPeak" to samples.maxOf { abs(it) },
        "rawRms" to sqrt(samples.sumOf { it.toDouble() * it } / samples.size),
        "rawDc" to samples.sumOf { it.toDouble() } / samples.size,
        "rawLoudness" to Loudness.of(Snip(samples, 1, Dsp.RATE)),
    )
    private fun writeWav(file: File, samples: FloatArray, gain: Float) {
        val scaled = FloatArray(samples.size) { samples[it] * gain }
        require(scaled.all { it.isFinite() && abs(it) <= 1f }) { "${file.name}: no PCM clipping is allowed" }
        WavWriter.write(file, Snip(scaled, 1, Dsp.RATE), WavWriter.BitDepth.PCM_24)
    }
    private fun writeJson(file: File, value: JsonValue) { file.parentFile.mkdirs(); file.writeText(Json.write(value) + "\n") }
    private fun sourceHashes(): List<Map<String, String>> {
        val repo = File(git("rev-parse", "--show-toplevel") ?: "..")
        return listOf("synth/src/main/kotlin/com/snipsnap/synth", "synth/src/test/kotlin/com/snipsnap/synth")
            .flatMap { File(repo, it).listFiles()?.filter { f -> f.isFile && f.name.startsWith("Apsis") && f.extension == "kt" }.orEmpty() }
            .sortedBy { it.name }.map { mapOf("path" to it.relativeTo(repo).invariantSeparatorsPath, "sha256" to sha(it.readBytes())) }
    }
    private fun git(vararg args: String): String? = runCatching {
        val p = ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start()
        val s = p.inputStream.bufferedReader().readText().trim(); s.takeIf { p.waitFor() == 0 }
    }.getOrNull()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
}

private fun apsisJson(value: Any?): JsonValue = when (value) {
    null -> JsonValue.Null
    is String -> JsonValue.Str(value)
    is Boolean -> JsonValue.Bool(value)
    is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
    is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to apsisJson(it.value) })
    is Iterable<*> -> JsonValue.Arr(value.map(::apsisJson))
    else -> error("Unsupported score evidence ${value.javaClass}")
}
private fun apsisFromJson(value: JsonValue): Any? = when (value) {
    JsonValue.Null -> null
    is JsonValue.Str -> value.value
    is JsonValue.Bool -> value.value
    is JsonValue.Num -> value.value
    is JsonValue.Obj -> value.entries.mapValues { apsisFromJson(it.value) }
    is JsonValue.Arr -> value.items.map(::apsisFromJson)
}
