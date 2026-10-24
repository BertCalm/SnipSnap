package com.snipsnap.synth

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Rebuilds the playable bank, real MPC exports and finite musical listening examples. */
object TrioMusicalBankGenerator {
    private const val BPM = 104f
    private const val MONITOR = .25f
    private val PAN_FOLD = sqrt(.5f)
    private data class Hit(val beat: Float, val slot: Int, val velocity: Float = 1f)
    private data class Gesture(val beat: Float, val patch: TrioPatch)
    private fun obj(vararg fields: Pair<String, JsonValue>) = JsonValue.Obj(linkedMapOf(*fields))
    private fun arr(values: List<JsonValue>) = JsonValue.Arr(values)
    private fun str(value: String) = JsonValue.Str(value)
    private fun num(value: Number) = JsonValue.Num(value.toDouble())
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun json(value: JsonValue) = Json.write(value) + "\n"
    private fun text(root: File, path: String, value: String) = File(root, path).also { it.parentFile.mkdirs() }.writeText(value)
    private fun scaled(snip: Snip, gain: Float) = Snip(FloatArray(snip.samples.size) { snip.samples[it] * gain }, 1, Dsp.RATE)
    private fun metrics(snip: Snip) = obj("peak" to num(snip.peak()), "loudness" to num(Loudness.of(snip)),
        "dc" to num(snip.samples.sumOf { it.toDouble() } / snip.samples.size),
        "durationSeconds" to num(snip.durationSeconds))
    private fun hashFiles(root: File, paths: List<String>) = obj(*paths.distinct().sorted().map {
        it to str(sha(File(root, it).readBytes()))
    }.toTypedArray())
    private fun mix(parts: List<Triple<Float, Snip, Float>>): Snip {
        val starts = parts.map { (beat, _, _) -> (beat * 60f / BPM * Dsp.RATE).roundToInt() }
        val out = FloatArray(parts.indices.maxOf { starts[it] + parts[it].second.frameCount })
        for ((n, part) in parts.withIndex()) {
            for (i in part.second.samples.indices) out[starts[n] + i] += part.second.samples[i] * part.third
        }
        check(out.all { it.isFinite() } && out.maxOf { kotlin.math.abs(it) } < .99f) { "Musical sum exceeds headroom" }
        return Snip(out, 1, Dsp.RATE)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1)
        val root = File(args.firstOrNull() ?: "../testkit/trio-musical-bank").absoluteFile
        root.mkdirs()
        val repository = listOf(File("."), File("..")).first { File(it, "synth/src/main/kotlin/com/snipsnap/synth/Trio.kt").isFile }.canonicalFile
        val sourcePaths = listOf("Trio.kt", "TrioScore.kt", "TrioV1.kt", "TrioV2.kt", "TrioV3.kt", "TrioV4.kt", "TrioPatch.kt", "TrioPresets.kt", "TrioKits.kt", "Patches.kt", "PadRecipe.kt", "Dsp.kt", "Strings.kt", "Modes.kt", "Keys.kt", "Thump.kt")
            .map { "synth/src/main/kotlin/com/snipsnap/synth/$it" } + listOf(
                "synth/src/test/kotlin/com/snipsnap/synth/TrioMusicalBankGenerator.kt", "synth/src/test/kotlin/com/snipsnap/synth/AuditionLevel.kt",
                "synth/src/test/resources/trio-musical/index.html", "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt", "audio/src/main/kotlin/com/snipsnap/audio/WavReader.kt",
                "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt", "audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt", "kit/src/main/kotlin/com/snipsnap/kit/KitAssembler.kt",
                "kit/src/main/kotlin/com/snipsnap/kit/Mpc3Exporter.kt", "mpc3/src/main/kotlin/com/snipsnap/mpc3/Mpc3TrackWriter.kt", "xpm/src/main/kotlin/com/snipsnap/xpm/XpmWriter.kt")
        val sources = sourcePaths.associateWith { sha(File(repository, it).readBytes()) }
        val patches = TrioKits.patches()
        val sourceHash = sha(json(obj(*sources.map { it.key to str(it.value) }.toTypedArray())).toByteArray())
        val recipeHash = sha(json(arr(patches.map { it.toJsonValue() })).toByteArray())
        val round = "trio-bank-${sourceHash.take(12)}-${recipeHash.take(12)}"
        val kitRoot = File(root, "kit").also { it.mkdirs() }
        val work = File(root, ".work").also { it.deleteRecursively() }
        val hits = listOf(Hit(0f, 4, .9f), Hit(2f, 1, .7f), Hit(4f, 5, .9f), Hit(6.5f, 3, .6f),
            Hit(8f, 9, .9f), Hit(10f, 2, .7f), Hit(12f, 13, .9f), Hit(14f, 16, 1f))
        val groove = Mpc3Clip("Conversations 104 BPM", 4, hits.map {
            Mpc3Note(Mpc3Note.noteFor(it.slot), (it.beat * Mpc3Clip.PULSES_PER_BEAT).toLong(), it.velocity)
        })
        val kit = KitAssembler.assembleArranged(TrioKits.NAME, TrioKits.classic(), work, KeySpec.parse("Cminpent"), BPM)
        val exported = Mpc3Exporter.exportTrack(kit, work, kitRoot, overwrite = true, clip = groove, mpc2Twin = true)
        val sourceKit = File(kitRoot, "Source Kit")
        sourceKit.deleteRecursively()
        check(work.copyRecursively(sourceKit, overwrite = true))
        check(KitStore.load(sourceKit) == kit)
        work.deleteRecursively()
        val dataDir = exported.samples.first().parentFile
        val padSnips = kit.pads.map { WavReader.read(File(sourceKit, it.sampleFile)) }
        // Export WAVs and original editable-kit WAVs must be identical.
        for (i in kit.pads.indices) check(File(dataDir, kit.pads[i].sampleFile).readBytes().contentEquals(File(sourceKit, kit.pads[i].sampleFile).readBytes()))
        val clips = arrayListOf<JsonValue>()
        val pads = arrayListOf<JsonValue>()
        fun audio(id: String, title: String, description: String, native: Snip, score: JsonValue, group: String): JsonValue {
            val rawPath = "audio/$round/${id}_raw.wav"
            val matchedPath = "audio/$round/${id}_matched.wav"
            val scorePath = "scores/$round/$id.json"
            WavWriter.write(File(root, rawPath), native)
            val matched = AuditionLevel.level(native)
            WavWriter.write(File(root, matchedPath), matched)
            text(root, scorePath, json(score))
            return obj("id" to str(id), "title" to str(title), "description" to str(description), "group" to str(group),
                "rawPath" to str(rawPath), "matchedPath" to str(matchedPath), "scorePath" to str(scorePath), "rawPlaybackGain" to num(1),
                "rawMetrics" to metrics(native), "matchedMetrics" to metrics(matched), "assets" to hashFiles(root, listOf(rawPath, matchedPath, scorePath)))
        }
        fun hitJson(hit: Hit) = obj("beat" to num(hit.beat), "slot" to num(hit.slot), "velocity" to num(hit.velocity),
            "timePulses" to num((hit.beat * Mpc3Clip.PULSES_PER_BEAT).toLong()), "note" to num(Mpc3Note.noteFor(hit.slot)))
        val grooveScore = obj("tempoBpm" to num(BPM), "bars" to num(4), "ppq" to num(Mpc3Clip.PULSES_PER_BEAT),
            "monitorGain" to num(MONITOR), "panFoldGain" to num(PAN_FOLD), "velocityLaw" to str("0.35 + 0.65 * noteVelocity (SnipSnap preview law)"),
            "events" to arr(hits.map(::hitJson)))
        clips += audio("kit_groove", "Conversations · four bars", "The exported pads in a sparse 104 BPM phrase. Wives strum; each triggered husband strikes once and rings through the next pad.",
            mix(hits.map { hit -> Triple(hit.beat, padSnips[hit.slot - 1], MONITOR * PAN_FOLD * kit.pads[hit.slot - 1].level * (.35f + .65f * hit.velocity)) }), grooveScore, "groove")
        val anchor = patches.first { it.name == "Family Root" }
        val melodic = listOf(60, 55, 58, 60)
        fun phrase(id: String, title: String, description: String, drama: Float, sides: Float, motion: Float, group: String) {
            val gestures = melodic.mapIndexed { i, midi -> Gesture(i * 2f, anchor.copy(name = title, midi = midi,
                velocity = .9f, macros = anchor.macros + mapOf("DRAMA" to drama, "SIDES" to sides, "MOTION" to motion))) }
            val score = obj("tempoBpm" to num(BPM), "monitorGain" to num(MONITOR),
                "events" to arr(gestures.map { obj("beat" to num(it.beat), "patch" to it.patch.toJsonValue()) }))
            clips += audio(id, title, description, mix(gestures.map { Triple(it.beat, it.patch.render(), MONITOR) }), score, group)
        }
        phrase("drama_low", "Low · DRAMA .25", "Same four notes, seed and touch in all three DRAMA phrases. Listen for delayed single-strike replies.", .25f, 1f / 6f, 0f, "drama")
        phrase("drama_middle", "Middle · DRAMA .5", "The husband replies move closer together within the same melody.", .5f, 1f / 6f, 0f, "drama")
        phrase("drama_high", "High · DRAMA 1", "The accepted overlapping entrances within the same melody.", 1f, 1f / 6f, 0f, "drama")
        phrase("alliance_tres", "Tres apart", "SIDES 0. Tres stands outside the berimbau–charango pair. All other settings match the next two phrases.", .5f, 0f, 0f, "alliance")
        phrase("alliance_wire", "Berimbau apart", "SIDES 1/3. The outsider moves to berimbau, with the same melody and DRAMA .5.", .5f, 1f / 3f, 0f, "alliance")
        phrase("alliance_charango", "Charango apart", "SIDES 2/3. Charango stands apart from the other two performers.", .5f, 2f / 3f, 0f, "alliance")
        phrase("motion", "Moving alliance", "Starts at Tres apart, then MOTION .8 moves the alliance during each finite gesture. Compare with Tres apart.", .5f, 0f, .8f, "motion")
        for ((i, pad) in kit.pads.withIndex()) {
            val patch = patches[i]
            val id = "pad_${(i + 1).toString().padStart(2, '0')}"
            val rawPath = File(dataDir, pad.sampleFile).relativeTo(root).invariantSeparatorsPath
            val matchedPath = "audio/$round/${id}_matched.wav"
            val patchPath = "patches/$round/$id.json"
            val recipePath = "recipes/$round/$id.json"
            WavWriter.write(File(root, matchedPath), AuditionLevel.level(padSnips[i]))
            text(root, patchPath, patch.toJsonText() + "\n")
            text(root, recipePath, json(requireNotNull(pad.recipe)))
            pads += obj("id" to str(id), "slot" to num(i + 1), "title" to str(patch.name), "group" to str(TrioPresets.groupFor(patch).name),
                "midi" to num(patch.midi), "velocity" to num(patch.velocity), "drama" to num(patch.macros.getValue("DRAMA")), "tempoBpm" to num(patch.config.tempoBpm),
                "rawPath" to str(rawPath), "matchedPath" to str(matchedPath), "patchPath" to str(patchPath), "recipePath" to str(recipePath),
                "rawPlaybackGain" to num(MONITOR * PAN_FOLD * pad.level), "rawMetrics" to metrics(padSnips[i]),
                "assets" to hashFiles(root, listOf(rawPath, matchedPath, patchPath, recipePath)))
        }
        val groovePath = "scores/$round/kit_groove.json"
        text(root, "kit/README.txt", "TRIO Conversations\nOpen TRIO Conversations.xtd with its adjacent _[TrackData] folder. Set the MPC project to 104 BPM for the embedded four-bar Conversations clip.\nFor MPC2, open the .xpm inside _[TrackData] and keep its WAVs adjacent. Source Kit contains the editable SnipSnap kit.json and original WAVs.\nAll 16 pads are finite 3.5-second one-shots; no mute groups or loops. Each triggered husband has one strike.\nThe audition preview uses SnipSnap's velocity law and a fixed .25 monitor gain; hardware playback remains to be auditioned.\n")
        val kitFiles = kitRoot.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()
        val kitZipPath = "TRIO-Conversations-MPC-Kit.zip"
        ZipOutputStream(File(root, kitZipPath).outputStream().buffered()).use { zip ->
            for (path in kitFiles) {
                zip.putNextEntry(ZipEntry("TRIO Conversations/" + path.removePrefix("kit/")).also { it.time = 0L })
                File(root, path).inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        check(sourcePaths.all { sha(File(repository, it).readBytes()) == sources[it] }) { "Sources changed during rendering" }
        val standaloneIds = listOf("kit_groove", "drama_low", "drama_middle", "drama_high")
        val manifest = obj("engine" to str("TRIO"), "currentModel" to num(Trio.MODEL_VERSION), "listeningRound" to str(round), "tempoBpm" to num(BPM),
            "clipCount" to num(clips.size), "padCount" to num(pads.size), "sampleRate" to num(Dsp.RATE), "bitDepth" to num(24), "channels" to num(1),
            "sourceFiles" to obj(*sources.map { it.key to str(it.value) }.toTypedArray()), "recipeSha256" to str(recipeHash),
            "levelMethod" to str("Matched: loudest 200 ms after a gentle low cut, target .03, peak guard .99; SnipSnap proxy, not LUFS. Raw demos: fixed .25 monitor gain. Raw pads: exact exported WAV at .25 × padLevel × sqrt(.5), full strike. Matching leaves export samples unchanged."),
            "standalonePreviewIds" to arr(standaloneIds.map(::str)), "clips" to arr(clips), "pads" to arr(pads),
            "kit" to obj("trackPath" to str(exported.program.relativeTo(root).invariantSeparatorsPath),
                "programPath" to str(File(dataDir, "${kit.name}.xpm").relativeTo(root).invariantSeparatorsPath),
                "kitJsonPath" to str("kit/Source Kit/kit.json"), "kitZipPath" to str(kitZipPath), "groovePath" to str(groovePath),
                "previewClipId" to str("kit_groove"), "assets" to hashFiles(root, kitFiles + kitZipPath)))
        text(root, "manifest.json", json(manifest))
        val template = File(repository, "synth/src/test/resources/trio-musical/index.html").readText()
        text(root, "index.html", template.replace("__MANIFEST__", json(manifest).replace("</", "<\\/")))
        text(root, "README.md", "# TRIO musical bank\n\nEight musical examples and sixteen actual exported pads. Matched/raw playback; compare Low/Middle/High, fixed alliances and moving alliance.\n\nRebuild: ./gradlew :synth:generateTrioMusicalBank\nRound: $round\n\nThe kit ZIP contains native MPC3 track with a 104 BPM four-bar clip, XPM twin, Source Kit, WAVs and complete recipes. See kit/README.txt. The musical groove uses those exact quantized WAVs, pad levels and note events with the documented preview velocity law. Its .25 monitor gain is listening headroom, not a kit modification. Context phrases save every note's full patch in scores/.\n\nlisten.html embeds four selected demos and all 16 pads for offline playback; alliance/motion and kit downloads remain online. index.html beside assets includes the full pack. Source/asset hashes in manifest.json; CHECKSUMS.sha256 covers folder assets, excluding itself and listen.html. Hardware and phone acceptance remain owner listening checks.\n")
        val allFiles = ((clips + pads).flatMap { it.obj().getValue("assets").obj().keys } + kitFiles + kitZipPath + listOf("index.html", "manifest.json", "README.md")).distinct().sorted()
        text(root, "CHECKSUMS.sha256", allFiles.joinToString("\n", postfix = "\n") { "${sha(File(root, it).readBytes())}  $it" })
        val selected = clips.filter { it.obj().getValue("id").str() in standaloneIds } + pads
        val embedPaths = selected.flatMap { it.obj().getValue("assets").obj().keys }.distinct()
        val embedded = obj(*embedPaths.map { path -> path to str("data:${if (path.endsWith(".wav")) "audio/wav" else "application/json"};base64," + Base64.getEncoder().encodeToString(File(root, path).readBytes())) }.toTypedArray())
        val stand = JsonValue.Obj(LinkedHashMap(manifest.entries).apply { this["embeddedFiles"] = embedded })
        val html = template.replace("__MANIFEST__", json(stand).replace("</", "<\\/"))
        check(html.toByteArray().size <= 32 * 1024 * 1024) { "Standalone exceeds 32 MiB" }
        text(root, "listen.html", html)
        println("TRIO musical bank: ${clips.size} demos, ${pads.size} pads, $kitZipPath; standalone ${html.toByteArray().size} bytes; $round")
    }
}
