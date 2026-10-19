package com.snipsnap.synth

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.Mpc3Importer
import com.snipsnap.kit.SessionBuilder
import com.snipsnap.kit.SessionMixdown
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.mpc3.MpcXRay
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/** Real twelve-pad VENT kits, two sampler grooves, and explicit dry/FX listening pairs. */
object VentKitAuditionGenerator {
    private const val TARGET = .03f
    private val roles = linkedMapOf(
        "bass" to "Bass", "note" to "Short note", "hit" to "Percussive hit", "texture" to "Long texture",
    )
    private data class PadAudio(
        val entry: VentKitPresets.Entry, val dry: Snip, val wet: Snip,
        val dryRecipe: PadRecipe, val wetRecipe: PadRecipe, val diagnostics: Map<String, Any?>,
    )
    private data class Groove(val id: String, val title: String, val description: String, val bpm: Float, val clip: Mpc3Clip)
    private data class PairLevel(val dry: Snip, val wet: Snip, val gain: Float)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/vent-kit-audition").absoluteFile
        root.mkdirs()
        val bundle = File(root, "kit")
        // These are generator-owned directories, never a user's source kit.
        listOf("audio", "recipes", "kit", ".verify").forEach { name ->
            val dir = File(root, name)
            check(!dir.exists() || dir.deleteRecursively()) { "Cannot replace generated $dir" }
            check(dir.mkdirs()) { "Cannot create $dir" }
        }
        val began = System.nanoTime()
        val pads = VentKitPresets.entries.map { entry -> renderPad(entry) }
        val factory = SynthKits.vent()
        check(factory.size == pads.size)
        factory.forEachIndexed { index, arranged ->
            val pad = requireNotNull(arranged)
            check(sameBits(pad.snip, pads[index].dry) && pad.recipe == pads[index].dryRecipe.toJsonValue()) { "Factory kit differs from audition source" }
        }
        check(pads.size == 12 && pads.map { it.entry.id }.distinct().size == 12)
        val grooves = grooves()
        check(grooves.flatMap { it.clip.notes }.map { Mpc3Note.slotFor(it.note) }.toSet() == (1..12).toSet()) {
            "The two grooves together must play every pad"
        }
        val clips = pads.mapIndexed { index, audio ->
            val entry = audio.entry
            val p = entry.patch
            val matched = levelPair(audio.dry, audio.wet)
            val files = writePair(root, entry.id, audio.dry, audio.wet, matched)
            val dryRecipePath = "recipes/${entry.id}.json"
            val wetRecipePath = "recipes/${entry.id}-effects.json"
            File(root, dryRecipePath).writeText(audio.dryRecipe.toJsonText() + "\n")
            File(root, wetRecipePath).writeText(audio.wetRecipe.toJsonText() + "\n")
            files["recipe"] = dryRecipePath
            files["processedRecipe"] = wetRecipePath
            println("VENT kit ${index + 1}/12: ${entry.id} ${audio.dry.durationSeconds}s + explicit FX")
            linkedMapOf<String, Any?>(
                "id" to entry.id, "group" to p.voice.name, "condition" to entry.id.substringAfter('-'),
                "slot" to index + 1, "slotLabel" to "A%02d".format(index + 1), "voice" to p.voice.name, "role" to entry.role,
                "title" to p.name, "description" to description(entry),
                "model" to p.model, "midi" to p.midi, "note" to noteName(p.midi),
                "velocity" to p.velocity, "seed" to p.seed, "macros" to p.macros,
                "durationSeconds" to audio.dry.durationSeconds, "processedDurationSeconds" to audio.wet.durationSeconds,
                "channels" to audio.dry.channels, "processedChannels" to audio.wet.channels,
                "files" to files, "metrics" to metrics(matched.dry), "processedMetrics" to metrics(matched.wet),
                "nativeMetrics" to metrics(audio.dry), "processedNativeMetrics" to metrics(audio.wet),
                "matchedGain" to matched.gain, "waveform" to waveform(matched.dry),
                "processedWaveform" to waveform(matched.wet), "fxSummary" to fxSummary(entry),
                "hashes" to fileHashes(root, files),
            )
        }

        val dryDir = File(bundle, "Source Kits/VENT Dry")
        val wetDir = File(bundle, "Source Kits/VENT Effects")
        val dryKit = assemble(pads, factory, false, dryDir)
        val wetKit = assemble(pads, factory, true, wetDir)
        for (dir in listOf(dryDir, wetDir)) {
            GrooveStore.save(dir, grooves.map { it.clip })
            check(GrooveStore.load(dir) == grooves.map { it.clip }) { "Groove sidecar roundtrip differs" }
        }
        verifySourceKit(dryKit, dryDir, pads.map { it.dryRecipe })
        verifySourceKit(wetKit, wetDir, pads.map { it.wetRecipe })
        val exportReports = listOf(dryKit to dryDir, wetKit to wetDir).map { (kit, dir) ->
            val result = Mpc3Exporter.exportTrack(
                kit, dir, File(bundle, "MPC Programs"), overwrite = true,
                clips = grooves.map { it.clip }, mpc2Twin = true,
            )
            val imported = Mpc3Importer.import(result.program, File(root, ".verify/tracks"), overwrite = true)
            check(imported.kit.pads.size == 12 && imported.grooveCount == 2)
            check(GrooveStore.load(imported.directory) == grooves.map { it.clip }) { "Native track groove replay differs" }
            verifyImportedSamples(kit, dir, imported.kit, imported.directory)
            val twins = File(bundle, "MPC Programs").walkTopDown().filter { it.extension == "xpm" && it.nameWithoutExtension == kit.name }.toList()
            check(twins.size == 1)
            val reading = MpcXRay.read(twins.single())
            check(reading.unreadable == null && reading.programs.single().pads.count { it.hasSample } == 12) { "MPC 2 twin is not a twelve-pad program" }
            linkedMapOf("kit" to kit.name, "nativeTrack" to result.program.relativeTo(bundle).invariantSeparatorsPath,
                "mpc2Program" to twins.single().relativeTo(bundle).invariantSeparatorsPath,
                "samples" to result.samples.size, "nativeRoundtrip" to true, "mpc2Readable" to true)
        }
        val sessionReports = mutableListOf<Map<String, Any?>>()
        val grooveClips = grooves.map { groove ->
            val dry = SessionMixdown.render(listOf(dryDir), groove.bpm) { _, _ -> groove.clip }
            val wet = SessionMixdown.render(listOf(wetDir), groove.bpm) { _, _ -> groove.clip }
            requireAudio(groove.id, "dry mix", dry)
            requireAudio(groove.id, "effects mix", wet)
            check(sameBits(dry, SessionMixdown.render(listOf(dryDir), groove.bpm) { _, _ -> groove.clip })) { "Dry mix is nondeterministic" }
            check(sameBits(wet, SessionMixdown.render(listOf(wetDir), groove.bpm) { _, _ -> groove.clip })) { "Effects mix is nondeterministic" }
            val matched = levelPair(dry, wet)
            val files = writePair(root, groove.id, dry, wet, matched)
            for ((variant, dir) in listOf("Dry" to dryDir, "Effects" to wetDir)) {
                val name = "VENT ${groove.title} $variant"
                val session = SessionBuilder.build(name, listOf(dir), File(bundle, "MPC Sessions"),
                    overwrite = true, tempoBpm = groove.bpm, groovesFor = { _, _ -> listOf(groove.clip) })
                val project = Mpc3Project.read(session.xpj)
                check(project.isProject && (project.data?.get("masterTempo") as? JsonValue.Num)?.value == groove.bpm.toDouble()) { "Session master tempo differs" }
                val sequences = (project.data?.get("sequences") as? JsonValue.Arr)?.items.orEmpty()
                check(sequences.isNotEmpty() && sequences.all { sequence ->
                    val value = sequence.obj().getValue("value").obj()
                    (value["bpm"] as? JsonValue.Num)?.value == groove.bpm.toDouble()
                }) { "Session sequence tempo differs" }
                val imported = Mpc3Importer.importProject(session.xpj, File(root, ".verify/${groove.id}-$variant"), overwrite = true)
                check(imported.skipped.isEmpty() && imported.kits.size == 1)
                val track = imported.kits.single()
                check(track.kit.pads.size == 12 && GrooveStore.load(track.directory) == listOf(groove.clip)) { "Session did not retain all pads and exact events" }
                val replay = SessionMixdown.render(listOf(track.directory), groove.bpm) { _, _ -> groove.clip }
                check(sameBits(if (variant == "Dry") dry else wet, replay)) { "Imported session mix differs" }
                sessionReports += linkedMapOf("id" to groove.id, "variant" to variant, "tempoBpm" to groove.bpm,
                    "project" to session.xpj.relativeTo(bundle).invariantSeparatorsPath,
                    "padCount" to 12, "eventCount" to groove.clip.notes.size,
                    "eventsRoundtrip" to true, "mixRoundtrip" to true)
            }
            linkedMapOf<String, Any?>(
                "id" to groove.id, "title" to groove.title, "description" to groove.description,
                "tempoBpm" to groove.bpm, "bars" to groove.clip.bars, "meter" to "4/4",
                "durationSeconds" to dry.durationSeconds, "processedDurationSeconds" to wet.durationSeconds,
                "channels" to dry.channels, "processedChannels" to wet.channels,
                "files" to files, "metrics" to metrics(matched.dry), "processedMetrics" to metrics(matched.wet),
                "nativeMetrics" to metrics(dry), "processedNativeMetrics" to metrics(wet),
                "matchedGain" to matched.gain, "waveform" to waveform(matched.dry), "processedWaveform" to waveform(matched.wet),
                "fxSummary" to "The same stored per-pad Tape / Echo / Spring chains as the pad comparisons; no master effect.",
                "events" to groove.clip.notes.map { event ->
                    val slot = Mpc3Note.slotFor(event.note)
                    linkedMapOf("slot" to slot, "padId" to pads[slot - 1].entry.id,
                        "step" to event.timePulses.toDouble() / Mpc3Clip.PULSES_PER_16TH,
                        "timePulses" to event.timePulses, "velocity" to event.velocity,
                        "lengthPulses" to event.lengthPulses)
                }, "hashes" to fileHashes(root, files),
            )
        }
        val provenance = provenance()
        val summary = linkedMapOf<String, Any?>(
            "padCount" to 12, "grooveCount" to 2, "browserWavCount" to 56,
            "finite" to true, "unclipped" to true, "integrationFailures" to 0,
            "deterministic" to true, "recipeRoundtrip" to true, "rawRoundtrip" to true,
            "sourceKitRoundtrip" to true, "nativeTrackRoundtrip" to true, "sessionMixRoundtrip" to true,
            "allPadsUsed" to true, "maxAbsoluteDc" to (clips + grooveClips).maxOf { c ->
                maxOf(abs((c.getValue("nativeMetrics") as Map<*, *>)["dc"] as Double),
                    abs((c.getValue("processedNativeMetrics") as Map<*, *>)["dc"] as Double))
            },
            "levelNote" to "Matched uses one scalar shared by each Dry/Effects pair: .03 divided by the louder loudest-200-ms RMS, with a shared .99 peak guard. This preserves pair gain differences and all internal dynamics.",
            "nativeNote" to "Pad Native is the normal leveled VENT render or its stored FX-chain output. Groove Native is SnipSnap SessionMixdown/KitPreview over the source kit's PCM24 WAVs, with saved pad levels, velocity gains and the standard .95 preview peak guard.",
            "velocityNote" to "Each kit pad saves one fixed synthesis velocity in its ordinary recipe. Pattern event velocities apply the sampler preview gain law (.35 + .65*velocity); they do not rerender attractor forcing per hit or supply velocity layers.",
            "acceptance" to "The owner heard distinct clean voice characters and authorized a playable kit. These twelve musical roles and grooves are new listening candidates.",
        )
        val manifest = linkedMapOf<String, Any?>(
            "engine" to "VENT", "model" to Vent.CLEAN_MODEL, "experiment" to "VENT_PLAYABLE_KIT", "experimentVersion" to 1,
            "title" to "VENT / Playable kit", "description" to "Twelve playable roles from PULSE, COIL and PLUME, heard alone and in two actual sampler grooves.",
            "sampleRate" to Dsp.RATE, "channels" to 1, "grooveChannels" to 2, "groups" to listOf(VentVoice.PULSE, VentVoice.COIL, VentVoice.PLUME).map { voice ->
                linkedMapOf("id" to voice.name, "title" to voice.name.lowercase().replaceFirstChar { it.uppercase() },
                    "description" to when (voice) { VentVoice.PULSE -> "Struck orbit: root, pin, knuckle and dust."; VentVoice.COIL -> "Moving projection: root, fleck, tick and ribbon."; else -> "Pressure gesture: root, reed, knock and cloud." })
            },
            "conditions" to roles.map { (id, title) -> linkedMapOf("id" to id, "title" to title,
                "description" to when (id) { "bass" -> "C2 roots with stronger LEASH and darker readout."; "note" -> "Short pitched notes in a C-minor-pentatonic palette."; "hit" -> "Brief freer accents; a tuned percussive role, not a drum simulation."; else -> "Long finite passages for a quieter moving bed." }) },
            "clips" to clips, "grooves" to grooveClips, "diagnostics" to summary,
            "fxNote" to "Effects are optional saved SnipSnap rack treatments: light Tape on bass, Tape + Echo on notes, Tape + Spring on hits, and Tape + Echo + Spring on textures. The VENT source remains unchanged.",
            "downloads" to linkedMapOf("kit" to "kit.zip", "audio" to "audition.zip"),
        ) + provenance
        File(root, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
        File(root, "diagnostics.json").writeText(Json.write(json(linkedMapOf(
            "summary" to summary, "pads" to pads.map { it.diagnostics }, "nativeExports" to exportReports,
            "sessions" to sessionReports, "generationSeconds" to (System.nanoTime() - began) / 1e9,
        ) + provenance)) + "\n")
        File(bundle, "README.txt").writeText(bundleReadme())
        check(File(root, "audio").listFiles { f -> f.extension == "wav" }!!.size == 56)
        zip(File(root, "kit.zip"), bundle.walkTopDown().filter { it.isFile }.toList()) { "VENT Playable Kit/" + it.relativeTo(bundle).invariantSeparatorsPath }
        val listening = listOf(File(root, "manifest.json"), File(root, "diagnostics.json")) +
            File(root, "recipes").walkTopDown().filter { it.isFile }.toList() +
            File(root, "audio").listFiles()!!.filter { it.name.endsWith("-matched.wav") }
        zip(File(root, "audition.zip"), listening) { it.relativeTo(root).invariantSeparatorsPath }
        File(root, ".verify").deleteRecursively()
        println("VENT playable kit: 12 pads, 2 dry/effects grooves, 56 browser WAVs, exact recipes, MPC 3/MPC 2 programs and four replay-checked sessions at ${root.path}")
    }

    private fun renderPad(entry: VentKitPresets.Entry): PadAudio {
        val p = entry.patch
        val detailed = render(p)
        val d = detailed.diagnostics
        requireAudio(entry.id, "dry", detailed.snip)
        check(d.signalPath == "clean-linear" && d.integrationFailures == 0 && d.maxState <= 256.0)
        check(d.modelTimeStep.isFinite() && d.modelTimeStep > 0.0 && d.modelTimeStep <= .0200001)
        check(detailed.raw.all { it.isFinite() && abs(it) < 1f })
        val dryRecipe = PadRecipe(patch = p)
        val restored = PadRecipe.fromJsonText(dryRecipe.toJsonText())
        check(restored == dryRecipe && sameBits(detailed.snip, restored.render())) { "${entry.id}: dry recipe replay differs" }
        val replay = render(restored.patch as VentPatch)
        check(sameBits(detailed.snip, replay.snip) && sameBits(detailed.raw, replay.raw)) { "${entry.id}: raw/native replay differs" }
        val wetRecipe = PadRecipe(patch = p, fx = effects(entry))
        val wet = wetRecipe.process(detailed.snip)
        requireAudio(entry.id, "effects", wet)
        val decodedWet = PadRecipe.fromJsonText(wetRecipe.toJsonText())
        check(decodedWet == wetRecipe && sameBits(wet, decodedWet.render())) { "${entry.id}: effects recipe replay differs" }
        check(sameBits(wet, wetRecipe.process(detailed.snip))) { "${entry.id}: effects chain is nondeterministic" }
        return PadAudio(entry, detailed.snip, wet, dryRecipe, wetRecipe, linkedMapOf(
            "id" to entry.id, "model" to p.model, "note" to p.midi, "velocity" to p.velocity, "seed" to p.seed,
            "macros" to p.macros, "rawSamplesSha256" to samplesHash(detailed.raw),
            "nativeSamplesSha256" to samplesHash(detailed.snip.samples), "effectsSamplesSha256" to samplesHash(wet.samples),
            "integrationFailures" to d.integrationFailures, "maxState" to d.maxState,
            "modelTimeStep" to d.modelTimeStep, "leashResets" to d.leashResets,
            "externalForceIntegral" to d.externalForceIntegral, "projectionSpan" to d.projectionSpan,
            "rawReplay" to true, "nativeRecipeReplay" to true, "effectsRecipeReplay" to true,
            "dry" to metrics(detailed.snip), "effects" to metrics(wet), "fx" to wetRecipe.fx!!.toJsonValue(),
        ))
    }

    private fun render(p: VentPatch) = Vent.renderDetailed(p.voice, p.macros, p.midi, p.velocity, p.seed, p.model)

    private fun assemble(pads: List<PadAudio>, factory: List<ArrangedPad?>, processed: Boolean, dir: File): Kit {
        val arranged = factory.mapIndexed { index, source ->
            val a = pads[index]
            requireNotNull(source).copy(
                snip = if (processed) a.wet else a.dry,
                recipe = (if (processed) a.wetRecipe else a.dryRecipe).toJsonValue(),
            )
        }
        val kit = KitAssembler.assembleArranged(if (processed) "VENT Effects" else "VENT Dry", arranged, dir, key = KeySpec.parse("Cminpent"), tempoBpm = 92f)
        check(KitStore.load(dir) == kit)
        return kit
    }

    private fun verifySourceKit(kit: Kit, dir: File, recipes: List<PadRecipe>) {
        val restored = KitStore.load(dir)
        check(restored == kit && restored.pads.size == 12)
        restored.pads.forEachIndexed { index, pad ->
            check(PadRecipe.fromJsonValue(requireNotNull(pad.recipe)) == recipes[index])
            val onDisk = WavReader.read(File(dir, pad.sampleFile))
            val expected = recipes[index].render()
            check(onDisk.frameCount == expected.frameCount && onDisk.channels == expected.channels)
            check(onDisk.samples.indices.all { abs(onDisk.samples[it] - expected.samples[it]) <= 1.3e-7f }) { "Source kit PCM24 does not match its recipe" }
        }
    }

    private fun verifyImportedSamples(original: Kit, source: File, restored: Kit, dir: File) {
        original.pads.forEach { pad ->
            val got = requireNotNull(restored.pad(pad.slot))
            check(fileHash(File(source, pad.sampleFile)) == fileHash(File(dir, got.sampleFile))) { "Native export changed sample bytes" }
            check(pad.level == got.level && pad.pan == got.pan && pad.muteGroup == got.muteGroup && pad.oneShot == got.oneShot)
        }
    }

    private fun effects(entry: VentKitPresets.Entry): FxChain {
        val tape = mapOf("WOBBLE" to .05f, "DRIVE" to .12f, "AGE" to .14f)
        val echo = mapOf("TIME" to .42f, "REPEAT" to .21f, "TONE" to .40f, "MIX" to .19f)
        val spring = mapOf("SIZE" to .24f, "TONE" to .48f, "MIX" to .14f)
        return when (entry.id.substringAfter('-')) {
            "bass" -> FxChain(tape = tape)
            "note" -> FxChain(tape = tape, echo = echo)
            "hit" -> FxChain(tape = tape, spring = spring)
            else -> FxChain(tape = tape, echo = echo + ("MIX" to .12f), spring = spring + ("MIX" to .10f))
        }
    }
    private fun fxSummary(entry: VentKitPresets.Entry) = when (entry.id.substringAfter('-')) {
        "bass" -> "Light Tape: WOBBLE .05, DRIVE .12, AGE .14."
        "note" -> "Light Tape, then Echo: TIME .42, REPEAT .21, TONE .40, MIX .19."
        "hit" -> "Light Tape, then Spring: SIZE .24, TONE .48, MIX .14."
        else -> "Light Tape, Echo MIX .12 and Spring MIX .10; a quiet tail around the finite passage."
    }
    private fun description(entry: VentKitPresets.Entry) = "${entry.patch.voice.name.lowercase().replaceFirstChar { it.uppercase() }} ${entry.role.lowercase()} · ${noteName(entry.patch.midi)} · a saved finite model-2 patch."

    private fun grooves(): List<Groove> {
        val a = mutableListOf<Mpc3Note>()
        val b = mutableListOf<Mpc3Note>()
        fun hit(notes: MutableList<Mpc3Note>, id: String, step: Int, velocity: Float, nudge: Long = 0) {
            val slot = VentKitPresets.entries.indexOfFirst { it.id == id } + 1
            check(slot > 0) { "Missing kit preset $id" }
            notes += Mpc3Note(Mpc3Note.noteFor(slot), step * Mpc3Clip.PULSES_PER_16TH + nudge, velocity)
        }
        // A sparse, late-sixteenth answer around a sustained midrange ribbon.
        for (step in listOf(0, 7, 16, 24)) hit(a, "pulse-bass", step, if (step % 16 == 0) .94f else .74f)
        for (step in listOf(4, 12, 20, 28)) hit(a, "pulse-hit", step, .82f)
        for (step in listOf(2, 10, 18, 26, 30)) hit(a, "plume-hit", step, if (step == 30) .48f else .58f, 26)
        for (step in listOf(6, 14, 22)) hit(a, "pulse-note", step, .63f, 18)
        for (step in listOf(11, 27)) hit(a, "plume-note", step, .52f, 32)
        hit(a, "coil-texture", 0, .58f)
        // Four bars: a busier tick spine and alternating C2 roots, with two early clouds.
        for (bar in 0..3) {
            val offset = bar * 16
            for (step in listOf(0, 6, 10)) hit(b, if (bar % 2 == 0) "coil-bass" else "plume-bass", offset + step, if (step == 0) .92f else .70f)
            for (step in listOf(2, 4, 8, 12, 14)) hit(b, "coil-hit", offset + step, if (step % 4 == 0) .75f else .44f)
            for (step in listOf(3, 11)) hit(b, "coil-note", offset + step, .60f, 18)
            if (bar % 2 == 1) hit(b, "plume-note", offset + 15, .48f)
        }
        hit(b, "pulse-texture", 0, .50f)
        hit(b, "plume-texture", 16, .44f)
        return listOf(
            Groove("gravity-92", "Gravity", "Two loose bars at 92 BPM: PULSE roots and accents, a COIL ribbon and PLUME replies.", 92f,
                Mpc3Clip("Gravity 92", 2, a.sortedBy { it.timePulses })),
            Groove("filament-124", "Filament", "Four tighter bars at 124 BPM: alternating COIL/PLUME roots, COIL ticks and early moving texture beds.", 124f,
                Mpc3Clip("Filament 124", 4, b.sortedBy { it.timePulses })),
        )
    }

    private fun levelPair(dry: Snip, wet: Snip): PairLevel {
        var gain = TARGET / maxOf(Loudness.of(dry), Loudness.of(wet), 1e-9f)
        val peak = maxOf(dry.peak(), wet.peak())
        if (peak * gain > .99f) gain = .99f / peak
        fun apply(s: Snip) = Snip(FloatArray(s.samples.size) { s.samples[it] * gain }, s.channels, s.sampleRate)
        return PairLevel(apply(dry), apply(wet), gain)
    }
    private fun writePair(root: File, id: String, dry: Snip, wet: Snip, matched: PairLevel): LinkedHashMap<String, String> {
        val files = linkedMapOf("native" to "audio/$id-native.wav", "matched" to "audio/$id-matched.wav",
            "processedNative" to "audio/$id-effects-native.wav", "processedMatched" to "audio/$id-effects-matched.wav")
        listOf("native" to dry, "matched" to matched.dry, "processedNative" to wet, "processedMatched" to matched.wet).forEach { (key, snip) ->
            requireAudio(id, key, snip)
            WavWriter.write(File(root, files.getValue(key)), snip, WavWriter.BitDepth.PCM_24)
        }
        return files
    }
    private fun requireAudio(id: String, label: String, snip: Snip) {
        check(snip.sampleRate == Dsp.RATE && snip.channels in 1..2 && snip.frameCount > 0)
        check(snip.samples.all { it.isFinite() } && snip.peak() in 1e-6f..0.999999f) { "$id: invalid $label samples" }
        val dc = snip.samples.sumOf { it.toDouble() } / snip.samples.size
        check(abs(dc) < .02) { "$id: excessive $label DC ($dc)" }
    }
    private fun metrics(snip: Snip): Map<String, Any?> {
        val sum = snip.samples.sumOf { it.toDouble() }
        val energy = snip.samples.sumOf { it.toDouble() * it }
        return linkedMapOf("peak" to snip.peak(), "rms" to sqrt(energy / snip.samples.size),
            "dc" to sum / snip.samples.size, "loudness" to Loudness.of(snip), "channels" to snip.channels,
            "sampleRate" to snip.sampleRate, "frames" to snip.frameCount)
    }
    private fun waveform(snip: Snip): List<Double> {
        val peak = snip.peak().coerceAtLeast(1e-9f)
        return (0 until 128).map { i ->
            val start = i * snip.frameCount / 128
            val end = maxOf(start + 1, (i + 1) * snip.frameCount / 128).coerceAtMost(snip.frameCount)
            var value = 0f
            for (f in start until end) for (ch in 0 until snip.channels) value = maxOf(value, abs(snip.samples[f * snip.channels + ch]))
            (value / peak).toDouble()
        }
    }
    private fun sameBits(a: Snip, b: Snip) = a.channels == b.channels && a.sampleRate == b.sampleRate && sameBits(a.samples, b.samples)
    private fun sameBits(a: FloatArray, b: FloatArray) = a.size == b.size && a.indices.all { a[it].toRawBits() == b[it].toRawBits() }
    private fun samplesHash(samples: FloatArray): String {
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putInt(it.toRawBits()) }
        return digest(buffer.array())
    }
    private fun fileHash(file: File) = digest(file.readBytes())
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun fileHashes(root: File, files: Map<String, String>) = files.mapValues { fileHash(File(root, it.value)) }
    private fun noteName(midi: Int) = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")[midi % 12] + (midi / 12 - 1)
    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output
    }
    private fun provenance(): Map<String, Any?> {
        val source = File(git("rev-parse", "--show-toplevel"))
        val names = listOf("Vent.kt", "VentModel2.kt", "VentPatch.kt", "VentKitPresets.kt", "SynthKits.kt", "FxChain.kt", "Tape.kt", "Echo.kt", "Spring.kt", "Dsp.kt")
        val files = names.map { "synth/src/main/kotlin/com/snipsnap/synth/$it" } + listOf(
            "synth/src/test/kotlin/com/snipsnap/synth/VentKitAuditionGenerator.kt", "kit/src/main/kotlin/com/snipsnap/kit/KitPreview.kt",
            "kit/src/main/kotlin/com/snipsnap/kit/SessionMixdown.kt", "kit/src/main/kotlin/com/snipsnap/kit/SessionBuilder.kt")
        return linkedMapOf("sourceCommit" to git("rev-parse", "HEAD"), "sourceTreeDirty" to git("status", "--porcelain").isNotEmpty(),
            "sourceFilesSha256" to files.associateWith { fileHash(File(source, it)) }, "generatedAt" to Instant.now().toString())
    }
    private fun zip(file: File, files: List<File>, path: (File) -> String) {
        ZipOutputStream(file.outputStream().buffered()).use { out ->
            for (item in files.sortedBy(path)) {
                out.putNextEntry(ZipEntry(path(item)).also { it.time = 0L })
                item.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }
    private fun bundleReadme() = """
        VENT PLAYABLE KIT — PULSE / COIL / PLUME

        Source Kits/ contains two real SnipSnap kit folders (kit.json, groove.json and twelve PCM24 WAVs).
        VENT Dry stores the clean model-2 source recipes. VENT Effects stores the same patches with explicit
        optional Tape/Echo/Spring chains. Both preserve ordinary PadRecipes; MPC files cannot preserve recipes.

        MPC Programs/ contains native MPC 3 .xtd tracks with the two embedded patterns, plus MPC 2 .xpm twins
        inside each _[TrackData] sample folder. MPC 2 programs carry pads, not the MPC 3 embedded patterns.
        Standalone track files do not set the hardware master tempo: Gravity is 92 BPM, Filament is 124 BPM.

        MPC Sessions/ contains four .xpj projects: Gravity Dry/Effects at 92 BPM and Filament Dry/Effects
        at 124 BPM, each with its own self-contained _[ProjectData] folder and exact pattern events.
        Keep each native file beside its matching data folder when copying to a card.

        Kit pads use stored mix levels: bass .72, short note .54, hit .60, texture .22. All are finite one-shots.
        Event velocity changes sampler gain, not synthesis forcing; no velocity-layer or seamless-loop claim.
        Source kit WAVs and MPC samples use native rendering, not the quieter web audition matching.
        Source kit metadata defaults to Gravity at 92 BPM; switch to 124 for standalone Filament playback.

        Generated by :synth:generateVentKitAudition. See the adjacent audition manifest and diagnostics for
        source provenance, exact file hashes, matching policy and replay checks. New presets await listening.
    """.trimIndent() + "\n"
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble())
        is Map<*, *> -> JsonValue.Obj(value.entries.associateTo(LinkedHashMap()) { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("Unsupported manifest value ${value::class}")
    }
}
