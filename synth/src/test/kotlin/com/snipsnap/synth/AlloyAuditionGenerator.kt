package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Classifier
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.KitStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Reproducible, dry ALLOY listening pack. The default publication roster covers
 * every voice, three registers, soft/full velocity, all six five-point controls,
 * the four required interactions, causal FUSE ablations and difficult loops.
 * -PalloyFull expands timbral sweeps and grids to all six voices.
 * Raw measurements precede shared melodic gain; listening WAVs use AuditionLevel.
 * The source ablation keeps the direct attack coefficient and complete settings.
 * The selected-voice publication has its own output and review namespace; the
 * original, contrast, selected and role-comparison packs remain fixed references.
 * --factory publishes the approved complete-configuration bank separately.
 */
object AlloyAuditionGenerator {
    private val steps = listOf(0f, .25f, .5f, .75f, 1f)
    private val timbral = listOf("MATERIAL", "FUSE", "EDGE", "BODY", "LENGTH")
    // These IDs are already published and key the browser's saved listening notes.
    // New presets use name-based IDs, so changing roster order cannot move reviews.
    private val publishedPresetIds = listOf(
        "Wooden Current", "Rubber Wire", "Tine Alloy", "Warm Circuit",
        "Muted Fusion", "Lit Filament", "Breath Metal", "Copper Air",
        "Drawn Wire", "Held Filament", "Soft Forge", "Endless Ember",
    ).mapIndexed { index, name -> name to "preset_${(index + 1).toString().padStart(2, '0')}_${slug(name)}" }.toMap()
    private val sectionDescriptions = linkedMapOf(
        "PRESETS" to ("Factory patches with distinct roles" to "Four patches per voice: the selected reference and three owner-approved roles with their complete operator, routing and envelope configurations. All are dry; the original BASS/SOFT and revised KEY/PLUCK/BRASS/STRING defaults remain available."),
        "VOICES" to ("Six voices across their range" to "Low, middle and high TUNE at soft (.3) and full (1) velocity. Compare attack, body and raw level; matching loudness preserves differences in excitation."),
        "SWEEPS" to ("Five points per control" to "0, .25, .5, .75 and 1 with companion controls at declared neutral values. Finite and held timbral examples are separate; HOLD below its top step remains finite."),
        "GRIDS" to ("The required interactions" to "MATERIAL × FUSE, FUSE × EDGE, BODY × EDGE and FUSE × LENGTH at 0, .5 and 1. Selected grids also use settled held excitation."),
        "FUSE" to ("Does the attack enter the body?" to "Compare attack modulation enabled and disabled at identical FUSE .75, material, pitch, velocity, configuration and direct attack gain. The disabled source still has the direct attack. Matched listening gain is independent for a fair timbral comparison; raw captures preserve engine gain."),
        "HOLD" to ("Difficult held regions" to "Loop-only exports contain the selected material's restrained periodic excitation, with the initial full attack absent. Repeat playback uses the entire decoded buffer in Web Audio. Seam measurements compare actual consecutive cycles before export."),
        "EXTREMES" to ("Corners and high notes" to "All-high finite settings and high-register, thin-body, maximum-fusion settings for all six voices. Inspect raw peaks, DC and phase-modulation bounds beside matched audio."),
    )

    internal data class Case(
        val section: String,
        val group: String,
        val id: String,
        val title: String,
        val detail: String,
        val voice: AlloyVoice,
        val macros: Map<String, Float>,
        val midi: Int,
        val velocity: Float = 1f,
        val ablation: Boolean = false,
        val pair: String? = null,
        val raw: Boolean = false,
        val patch: AlloyPatch? = null,
        val baseMidi: Int = Alloy.DEFAULT_MIDI,
    ) {
        // This is the only configuration resolution. Audio, metadata and downloads
        // consume the same complete recipe, including its base note and velocity.
        val recipe = AlloyPatch(patch?.name ?: title, voice, macros, baseMidi, velocity,
            model = patch?.model ?: Alloy.MODEL_VERSION,
            config = patch?.config ?: Alloy.defaultConfigFor(voice))

        // A diagnostic can carry a transposed factory recipe without replacing
        // that preset's canonical saved note in patches/ or the preset gallery.
        val canonicalPresetPatch: AlloyPatch? get() = patch.takeIf { section == "PRESETS" }

        init {
            require(patch == null || patch.voice == voice) { "$id: factory voice disagrees with case" }
            require(Alloy.midiFor(voice, macros.getValue("TUNE"), baseMidi) == midi) {
                "$id: effective note disagrees with the saved recipe"
            }
        }
    }

    internal fun selectedVoices(): Map<String, String> = AlloyVoice.entries.associate { voice ->
        voice.name to if (voice == AlloyVoice.BASS || voice == AlloyVoice.SOFT) "original" else "revised"
    }

    private data class PairReference(
        val clip: MutableMap<String, Any?>,
        val samples: FloatArray,
        val body: FloatArray,
        val direct: FloatArray,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/alloy-audition")
        require(root.mkdirs() || root.isDirectory) { "cannot create ${root.absolutePath}" }
        val factory = "--factory" in args.drop(1)
        val selected = factory || "--selected" in args.drop(1)
        val full = "--full" in args.drop(1)
        val refreshPage = "--refresh-page" in args.drop(1)
        if (refreshPage) {
            copyPage(root, selected, factory)
            println("refreshed ALLOY listening page under ${root.absolutePath}")
            return
        }
        val roster = cases(full)
        require(roster.map { it.id }.distinct().size == roster.size) { "duplicate ALLOY case IDs" }
        // Clear only files generated by this task, so a new roster cannot publish stale WAVs.
        File(root, "audio").deleteRecursively()
        File(root, "patches").deleteRecursively()
        File(root, "recipes").deleteRecursively()
        File(root, "clip-patches").deleteRecursively()
        File(root, "kit/SnipSnap Alloy Provisional Kit").deleteRecursively()
        File(root, "kit/SnipSnap Alloy Kit").deleteRecursively()
        File(root, "kit/SnipSnap Alloy Selected Kit").deleteRecursively()
        File(root, "kit/SnipSnap Alloy Factory Kit").deleteRecursively()
        val sections = sectionDescriptions.mapValues { mutableListOf<MutableMap<String, Any?>>() }
        val pairReferences = mutableMapOf<String, PairReference>()
        val began = System.nanoTime()
        var wavCount = 0

        roster.forEachIndexed { index, c ->
            val recipe = c.recipe
            val started = System.nanoTime()
            val p = Alloy.renderProbe(
                recipe.voice, recipe.macros, midi = recipe.midi, velocity = recipe.velocity,
                normalize = false, attackModulation = !c.ablation,
                config = recipe.config,
            )
            val renderMs = (System.nanoTime() - started) / 1e6
            val loop = Alloy.isLoop(c.macros.getValue("HOLD"))
            // Follow the public render path's finite tail treatment; held buffers stay untouched.
            if (!loop) Dsp.fadeTail(p.samples)
            require(p.samples.isNotEmpty() && p.samples.all { it.isFinite() }) { "${c.id}: invalid raw audio" }
            val native = Snip(p.samples, 1, Dsp.RATE)
            // Floating synthesis can exceed full scale before the listening/public
            // level stage. Retain the factory's raw bound; only an actual raw PCM
            // export needs the stricter encoding ceiling below.
            require(native.peak() < 2f && p.diagnostics.rawPeak < 2.0) {
                "${c.id}: raw synthesis exceeded its bound (${native.peak()})"
            }
            val matched = AuditionLevel.level(native)
            require(matched.peak() < 1f) { "${c.id}: matched output would clip PCM export" }
            val file = "audio/${c.id}.wav"
            WavWriter.write(File(root, file), matched, WavWriter.BitDepth.PCM_16)
            wavCount++
            if (loop) require(p.seamError.isFinite() && p.seamError < 1e-3) {
                "${c.id}: unsettled loop seam ${p.seamError}"
            }
            val exportedSeamError = if (loop) {
                // Compare real consecutive cycles after the actual PCM16 encoder/decoder.
                // Apply the listening file's same gain to its actual preceding cycle.
                val gain = matched.peak() / native.peak().coerceAtLeast(1e-30f)
                val previous = Snip(FloatArray(p.previousCycle.size) { p.previousCycle[it] * gain }, 1, Dsp.RATE)
                val bytes = ByteArrayOutputStream().also { WavWriter.write(it, previous, WavWriter.BitDepth.PCM_16) }.toByteArray()
                val encodedPrevious = WavReader.read(bytes).samples
                val decoded = WavReader.read(File(root, file))
                require(decoded.channels == 1 && decoded.sampleRate == Dsp.RATE && decoded.frameCount == native.frameCount)
                Keys.seamError(encodedPrevious + decoded.samples, encodedPrevious.size).also {
                    require(it.isFinite() && it < 1e-3) { "${c.id}: PCM16 export seam $it" }
                }
            } else null
            val clip = linkedMapOf<String, Any?>(
                "id" to c.id, "title" to c.title, "detail" to c.detail, "group" to c.group,
                "file" to file, "voice" to c.voice.name, "midi" to c.midi, "note" to noteName(c.midi),
                "velocity" to c.velocity, "macros" to c.macros, "loop" to loop,
                "baseMidi" to recipe.midi, "effectiveMidi" to c.midi,
                "model" to recipe.model, "config" to recipe.config.toJsonValue(),
                "completePatch" to recipe.toJsonValue(),
                "attackModulation" to !c.ablation, "renderRecipeFile" to "recipes/${c.id}.json",
                "patchFile" to if (c.ablation) null else "clip-patches/${c.id}.json",
                "frames" to native.frameCount, "duration" to native.durationSeconds,
                "rawPeak" to native.peak(), "rms" to rms(p.samples), "dc" to dc(p.samples),
                "loudness" to Loudness.of(native), "matchedPeak" to matched.peak(),
                "matchedLoudness" to Loudness.of(matched), "renderMs" to renderMs,
                "spectralCentroidHz" to centroid(p.samples),
                "waveform" to waveform(matched.samples),
                "seamError" to if (loop) finite(p.seamError) else null,
                "exportedSeamError" to exportedSeamError,
                "stateError" to if (loop) finite(p.stateError) else null,
                "prerollCycles" to if (loop) p.prerollCycles else null,
                "loopStart" to if (loop) 0 else null,
                "loopEnd" to if (loop) native.durationSeconds else null,
                "diagnostics" to linkedMapOf(
                    "rawPeakBeforeConditioning" to p.diagnostics.rawPeak,
                    "maxPhaseModulationRadians" to p.diagnostics.maxPhaseModulation,
                    "maxConditionedAttack" to p.diagnostics.maxConditionedAttack,
                    "rootHz" to p.diagnostics.rootHz, "internalRate" to p.diagnostics.internalRate,
                ),
            )
            val classificationMeaning = when (c.id) {
                "pluck_all_low_c2" -> "The all-low C2 PLUCK corner has a short percussive decay while retaining its pitched root. These are actual full-export heuristic classifications, separate from the engine's declared pitched export class."
                "picked_fibre_c2" -> "Picked Fibre at C2 retains its stable pitched root and approved configuration, but its short attack and decay trigger the generic TOM heuristic. Actual raw and matched classifications are recorded separately from the declared TONAL export class."
                else -> null
            }
            if (classificationMeaning != null) {
                val decodedMatched = WavReader.read(File(root, file))
                clip["classification"] = linkedMapOf(
                    "declaredEngineClass" to Alloy.drumClassFor(recipe.voice, recipe.macros).name,
                    "rawFullRenderClass" to Classifier.classify(native).drumClass.name,
                    "matchedPcm16FullRenderClass" to Classifier.classify(decodedMatched).drumClass.name,
                    "meaning" to classificationMeaning,
                )
            }
            File(root, "recipes/${c.id}.json").apply {
                parentFile.mkdirs(); writeText(Json.write(value(recipeRecord(c))) + "\n")
            }
            if (!c.ablation) File(root, "clip-patches/${c.id}.json").apply {
                parentFile.mkdirs(); writeText(recipe.toJsonText() + "\n")
            }
            if (c.raw) {
                require(native.peak() < 1f) { "${c.id}: raw output would clip PCM export (${native.peak()})" }
                val rawFile = "audio/${c.id}_raw.wav"
                WavWriter.write(File(root, rawFile), native, WavWriter.BitDepth.PCM_24)
                clip["rawFile"] = rawFile
                wavCount++
            }
            c.canonicalPresetPatch?.let { patch ->
                val patchFile = "patches/${slug(patch.name)}.json"
                File(root, patchFile).apply { parentFile.mkdirs(); writeText(patch.toJsonText() + "\n") }
                clip["preset"] = patch.name
                clip["patch"] = patchFile
            }
            c.pair?.let { pair ->
                clip["pair"] = pair
                clip["ablation"] = c.ablation
                if (!c.ablation) {
                    pairReferences[pair] = PairReference(clip, p.samples, p.body, p.directAttack)
                } else {
                    val reference = requireNotNull(pairReferences.remove(pair)) { "${c.id}: no enabled reference" }
                    require(reference.direct.contentEquals(p.directAttack)) { "${c.id}: source ablation changed the direct attack path" }
                    val evidence = linkedMapOf(
                        "directAttackBitIdentical" to true,
                        "rawBodyRelativeDifference" to relativeDifference(reference.body, p.body),
                        "matchedWaveformRelativeDifference" to relativeDifference(
                            AuditionLevel.level(Snip(reference.samples, 1, Dsp.RATE)).samples, matched.samples,
                        ),
                    )
                    clip["fusionEvidence"] = evidence
                    reference.clip["fusionEvidence"] = evidence
                }
            }
            sections.getValue(c.section) += clip
            if ((index + 1) % 10 == 0 || index == roster.lastIndex) {
                println("ALLOY audition ${index + 1}/${roster.size}: ${c.id} (${renderMs.roundToInt()} ms)")
            }
        }
        require(pairReferences.isEmpty()) { "unpaired source ablations" }
        val downloads = exportKit(root, selected, factory)
        if (selected) downloads += exportPresetBank(root, factory)
        val patchZip = File(root, "alloy-patches.zip")
        zipDirectory(File(root, "patches"), patchZip, "patches")
        // Preserve download URLs shared from the first audition.
        if (!selected) patchZip.copyTo(File(root, "alloy-provisional-patches.zip"), overwrite = true)
        downloads += mapOf("title" to "${AlloyPresets.all().size} factory patch JSONs", "file" to patchZip.name)
        downloads += mapOf("title" to "Raw measurements · CSV", "file" to "diagnostics.csv")
        downloads += mapOf("title" to "Complete render recipes · JSON", "file" to "render-recipes.json")

        val manifest = linkedMapOf<String, Any?>(
            "engine" to "ALLOY", "title" to "Alloy · hybrid attack / phase modulation",
            "version" to if (factory) 3 else if (selected) 2 else 1, "sampleRate" to Dsp.RATE, "channels" to 1,
            "bitDepth" to 16, "rawBitDepth" to 24, "provisional" to !factory, "full" to full,
            "stage" to if (factory) "factory-role-release" else if (selected) "selected-release-listening" else "factory-listening",
            "selectedVoices" to selectedVoices(), "presetReviewPending" to !factory,
            "roleBankApproved" to factory,
            "presetConfigurationMeaning" to "Voice studies use selected defaults; each factory preset retains its own complete approved configuration.",
            "deviceAcceptancePending" to true,
            "presetCount" to AlloyPresets.all().size,
            "presetsPerVoice" to AlloyVoice.entries.map { AlloyPresets.forVoice(it).size }.distinct().single(),
            "clipCount" to roster.size, "wavCount" to wavCount,
            "elapsedSeconds" to (System.nanoTime() - began) / 1e9,
            "generatedBy" to if (factory) "./gradlew :synth:generateAlloyFactoryAudition${if (full) " -PalloyFactoryFull" else ""}"
                else if (selected) "./gradlew :synth:generateAlloySelectedAudition${if (full) " -PalloySelectedFull" else ""}"
                else "./gradlew :synth:generateAlloyAudition${if (full) " -PalloyFull" else ""}",
            "coverage" to if (full) "Expanded all-voice timbral sweeps and finite/held interaction grids."
                else "Curated publication pack: six voices × three registers × two velocities; five-point finite/held timbral sweeps; all-voice HOLD sweeps; four interaction grids; causal source ablations; hard held and extreme settings.",
            "rawMeaning" to "Dry engine output before shared melodic loudness targeting. Raw PCM-24 captures are supplied for causal comparisons, middle-register references and difficult cases; every clip retains its raw measurements.",
            "matchedMeaning" to "The same render at AuditionLevel's shared loudest-200ms RMS target (0.03), with peak guard. No rack FX.",
            "holdMeaning" to "HOLD 1 exports settled loop-only material. Its restrained periodic source enters the same conditioned phase-modulation port; the initial full attack is absent.",
            "fusionMeaning" to "FUSE is generated transient phase modulation in radians, with a retained direct attack. This is a reduced hybrid proposal, not copied Yamaha sample data or hardware reconstruction.",
            "listeningVerdict" to if (factory) "The owner heard the distinct-role comparison, judged it much better and authorized these patches for the factory bank. Each voice retains its selected reference plus three approved roles. Phone profiling and physical hardware MPC playback remain open."
                else "The owner selected original BASS/SOFT and revised KEY/PLUCK/BRASS/STRING defaults. Factory presets preserve their authored complete configurations. Phone and physical hardware MPC acceptance remain open.",
            "downloads" to downloads,
            "provenance" to provenance(roster, root),
            "sections" to sectionDescriptions.map { (id, names) ->
                linkedMapOf("id" to id, "title" to names.first, "description" to names.second, "clips" to sections.getValue(id))
            },
        )
        File(root, "manifest.json").writeText(Json.write(value(manifest)) + "\n")
        copyPage(root, selected, factory)
        File(root, "README.txt").writeText(
            "ALLOY ${if (factory) "approved factory release" else if (selected) "selected-voice release" else "expanded factory"} audition\n" +
                "Regenerate: ${manifest.getValue("generatedBy")}\n" +
                "${roster.size} listening clips, $wavCount WAVs; mono 44.1 kHz.\n" +
                "Serve this folder as a static website and open index.html.\n" +
                "Listening files share the project's audition level. Raw PCM24 files retain pre-target engine output.\n" +
                "Held files are loop-only regions; initial attacks are absent. Use Repeat to review several wraps.\n" +
                "${AlloyPresets.all().size} factory patch JSONs cover four distinct roles per voice. Every clip has an exact diagnostic render recipe under recipes/; ordinary clips also have importable patches under clip-patches/.\n" +
                "Voice defaults retain original BASS/SOFT and revised KEY/PLUCK/BRASS/STRING; factory presets retain their complete authored configurations.\n" +
                "${manifest.getValue("listeningVerdict")}\n" +
                "Original and contrast auditions remain fixed references with their existing reviews.\n",
        )
        writeMetrics(File(root, "diagnostics.csv"), sections.values.flatten())
        println("wrote ${roster.size} clips, $wavCount WAVs, patch JSONs, native MPC kit and listening page under ${root.absolutePath}")
    }

    internal fun cases(full: Boolean): List<Case> = buildList {
        for (patch in AlloyPresets.all()) {
            val m = Alloy.defaults(patch.voice) + patch.macros
            val id = publishedPresetIds[patch.name] ?: "preset_${slug(patch.name)}"
            add(Case("PRESETS", patch.voice.name, id,
                patch.name, "Owner-approved factory ${patch.voice.name.lowercase()} role with its complete authored configuration; empty FX.",
                patch.voice, m, Alloy.midiFor(patch.voice, m.getValue("TUNE"), patch.midi),
                velocity = patch.velocity, patch = patch, baseMidi = patch.midi))
        }
        for (voice in AlloyVoice.entries) {
            val d = Alloy.defaults(voice)
            for ((tune, register) in listOf(0f to "low", .5f to "middle", 1f to "high")) {
                val midi = Alloy.midiFor(voice, tune)
                for ((velocity, label) in listOf(.3f to "Soft", 1f to "Full")) {
                    add(Case("VOICES", voice.name, "${tag(voice)}_${register}_${slug(label)}", "${voice.name} · ${noteName(midi)} · $label",
                        "Voice defaults, $register register; velocity ${fmt(velocity)}.", voice,
                        d + ("TUNE" to tune), midi, velocity, raw = tune == .5f && velocity == 1f))
                }
            }
            for (step in steps) {
                add(Case("SWEEPS", "${voice.name} / HOLD", "${tag(voice)}_hold_${code(step)}", "${voice.name} · HOLD ${fmt(step)}",
                    if (step == 1f) "Settled loop-only held excitation; first full attack absent." else "Finite note: HOLD below the top step changes the sustain window.",
                    voice, neutral(voice) + ("HOLD" to step), Alloy.midiFor(voice, .5f)))
            }
            for ((name, settings) in listOf(
                "all_high" to timbral.associateWith { 1f },
                "thin_high_fusion" to mapOf("MATERIAL" to 1f, "FUSE" to 1f, "EDGE" to 1f, "BODY" to 0f, "LENGTH" to 0f, "TUNE" to 1f),
            )) {
                val m = d + settings + ("HOLD" to 0f)
                val baseMidi = if (name == "thin_high_fusion") 72 else Alloy.DEFAULT_MIDI
                add(Case("EXTREMES", voice.name, "${tag(voice)}_$name", "${voice.name} · ${name.replace('_', ' ')}",
                    "Dry bounded corner; raw output retained for level/DC inspection.", voice, m,
                    Alloy.midiFor(voice, m.getValue("TUNE"), baseMidi), raw = true, baseMidi = baseMidi))
            }
            val upper = d + mapOf("MATERIAL" to 1f, "FUSE" to 1f, "EDGE" to 1f, "BODY" to .3f, "LENGTH" to .25f, "TUNE" to 1f, "HOLD" to 0f)
            add(Case("EXTREMES", voice.name, "${tag(voice)}_upper_pitch", "${voice.name} · upper pitch C8",
                "Base MIDI96 with TUNE1 gives effective MIDI108. Maximum FUSE/EDGE exercise the high-register bandwidth budget.",
                voice, upper, Alloy.midiFor(voice, 1f, 96), raw = true, baseMidi = 96))
            for ((name, settings) in listOf(
                "hard_fuse" to mapOf("MATERIAL" to 1f, "FUSE" to 1f, "EDGE" to 1f, "BODY" to .15f, "LENGTH" to 1f, "TUNE" to 1f),
                "quiet_wood" to mapOf("MATERIAL" to 0f, "FUSE" to .75f, "EDGE" to .05f, "BODY" to 1f, "LENGTH" to 1f, "TUNE" to 0f),
            )) {
                val m = d + settings + ("HOLD" to 1f)
                val baseMidi = if (name == "hard_fuse") 72 else Alloy.DEFAULT_MIDI
                add(Case("HOLD", voice.name, "${tag(voice)}_held_$name", "${voice.name} · ${name.replace('_', ' ')}",
                    "Settled loop-only source. Listen over several wraps; raw cycle and convergence diagnostics retained.",
                    voice, m, Alloy.midiFor(voice, m.getValue("TUNE"), baseMidi),
                    velocity = if (name == "quiet_wood") .3f else 1f, raw = true, baseMidi = baseMidi))
            }
            addAblationPairs(voice, held = false)
            if (full || voice in listOf(AlloyVoice.KEY, AlloyVoice.STRING)) addAblationPairs(voice, held = true)
        }
        val lowPluck = Alloy.defaults(AlloyVoice.PLUCK) + timbral.associateWith { 0f } +
            mapOf("TUNE" to .5f, "HOLD" to 0f)
        add(Case("EXTREMES", AlloyVoice.PLUCK.name, "pluck_all_low_c2",
            "PLUCK · all-low C2 corner",
            "Short percussive corner with its pitched root retained. Actual raw and decoded matched classifications are recorded separately from the declared tonal export class.",
            AlloyVoice.PLUCK, lowPluck, 36, raw = true, baseMidi = 36))
        val pickedFibre = AlloyPresets.forVoice(AlloyVoice.STRING).single { it.name == "Picked Fibre" }.copy(midi = 36)
        add(Case("EXTREMES", AlloyVoice.STRING.name, "picked_fibre_c2",
            "STRING · Picked Fibre · C2",
            "Approved Picked Fibre at C2 retains its stable pitched root but triggers the generic TOM heuristic. Compare actual raw and decoded matched classifications with the declared TONAL export class.",
            pickedFibre.voice, pickedFibre.macros, 36, velocity = pickedFibre.velocity,
            raw = true, patch = pickedFibre, baseMidi = pickedFibre.midi))

        val finiteVoices = mapOf(
            "MATERIAL" to listOf(AlloyVoice.KEY, AlloyVoice.BRASS),
            "FUSE" to listOf(AlloyVoice.PLUCK, AlloyVoice.SOFT),
            "EDGE" to listOf(AlloyVoice.BASS, AlloyVoice.STRING),
            "BODY" to listOf(AlloyVoice.BASS, AlloyVoice.KEY),
            "LENGTH" to listOf(AlloyVoice.PLUCK, AlloyVoice.SOFT),
        )
        val heldVoices = mapOf("MATERIAL" to AlloyVoice.KEY, "FUSE" to AlloyVoice.STRING,
            "EDGE" to AlloyVoice.BRASS, "BODY" to AlloyVoice.SOFT, "LENGTH" to AlloyVoice.SOFT)
        for (macro in timbral) for (held in listOf(false, true)) {
            val voices = if (full) AlloyVoice.entries else if (held) listOf(heldVoices.getValue(macro)) else finiteVoices.getValue(macro)
            for (voice in voices) for (step in steps) {
                val mode = if (held) "held" else "finite"
                add(Case("SWEEPS", "${voice.name} / $macro / $mode", "${tag(voice)}_${macro.lowercase()}_${code(step)}_$mode",
                    "${voice.name} · $macro ${fmt(step)} · $mode", "Five-point sweep; companions at declared neutral values.",
                    voice, neutral(voice) + mapOf(macro to step, "HOLD" to if (held) 1f else 0f), Alloy.midiFor(voice, .5f)))
            }
        }
        val grids = listOf(
            Triple(AlloyVoice.KEY, "MATERIAL", "FUSE"), Triple(AlloyVoice.PLUCK, "FUSE", "EDGE"),
            Triple(AlloyVoice.BASS, "BODY", "EDGE"), Triple(AlloyVoice.STRING, "FUSE", "LENGTH"),
        )
        for ((representative, a, b) in grids) for (held in listOf(false, true)) {
            if (!full && held && a !in listOf("MATERIAL", "FUSE")) continue
            if (!full && held && b == "LENGTH") continue
            val voices = if (full) AlloyVoice.entries else listOf(representative)
            for (voice in voices) for (x in listOf(0f, .5f, 1f)) for (y in listOf(0f, .5f, 1f)) {
                val mode = if (held) "held" else "finite"
                add(Case("GRIDS", "${voice.name} / $a × $b / $mode", "${tag(voice)}_grid_${a.lowercase()}${code(x)}_${b.lowercase()}${code(y)}_$mode",
                    "$a ${fmt(x)} · $b ${fmt(y)}", "${voice.name}; $mode; neutral companion controls.", voice,
                    neutral(voice) + mapOf(a to x, b to y, "HOLD" to if (held) 1f else 0f), Alloy.midiFor(voice, .5f)))
            }
        }
    }

    private fun MutableList<Case>.addAblationPairs(voice: AlloyVoice, held: Boolean) {
        val mode = if (held) "held" else "finite"
        for (material in listOf(0f, .5f, 1f)) {
            val pair = "${tag(voice)}_fusion_material${code(material)}_$mode"
            val m = neutral(voice) + mapOf("MATERIAL" to material, "FUSE" to .75f, "LENGTH" to .55f, "HOLD" to if (held) 1f else 0f)
            for (disabled in listOf(false, true)) add(Case("FUSE", "${voice.name} / material ${fmt(material)} / $mode",
                "${pair}_${if (disabled) "ablated" else "enabled"}", if (disabled) "Attack modulation disabled" else "Attack modulation enabled",
                "MATERIAL ${fmt(material)}, FUSE .75, $mode; identical direct attack path and complete settings.",
                voice, m, Alloy.midiFor(voice, .5f), ablation = disabled, pair = pair, raw = true))
        }
    }

    private fun neutral(voice: AlloyVoice) = Alloy.macrosFor(voice).associate { it.name to it.neutral } + mapOf("TUNE" to .5f, "HOLD" to 0f)

    private fun exportKit(root: File, selected: Boolean, factory: Boolean): MutableList<Map<String, String>> {
        val work = File(root, ".alloy-kit-work")
        work.deleteRecursively()
        val kit = KitAssembler.assembleArranged(if (factory) "SnipSnap Alloy Factory Kit" else if (selected) "SnipSnap Alloy Selected Kit" else "SnipSnap Alloy Kit", SynthKits.alloy(), work)
        val result = KitExporter.exportProgramFolder(kit, work, File(root, "kit"), overwrite = true)
        require(File(result.directory, KitStore.FILE_NAME).isFile) { "starter export omitted its native history" }
        File(result.directory, "README.txt").writeText(
            "SnipSnap Alloy MPC kit\n" +
                "Load the XPM program with its PCM24 WAV files beside it.\n" +
                "Sixteen dry pads retain full approved configurations alongside the ${AlloyPresets.all().size}-preset factory library.\n" +
                "Pads A01-A08 play Long Prism up the C minor pentatonic; A09-A14 showcase Hollow Rail, Reed Switch, Open Chime, Bright Stamp, Beating Glass and Velvet Rise.\n" +
                "The standard drum program leaves sample looping disabled.\n" +
                "Pads A15 (Held Filament) and A16 (Endless Ember) contain settled loop-only audio; the initial full attack is absent.\n" +
                "Enable sample looping across the whole sample on those MPC pads for indefinite HOLD.\n" +
                "The published audition's Repeat button performs this looping directly in Web Audio.\n" +
                "Native kit.json includes all sixteen complete recipes beside the PCM24 WAVs and XPM.\n" +
                "The owner approved the distinct-role direction; physical phone and hardware MPC acceptance remain open.\n",
        )
        val zip = File(root, "alloy-mpc-kit.zip")
        zipDirectory(result.directory, zip, result.directory.name)
        if (!selected) zip.copyTo(File(root, "alloy-provisional-mpc-kit.zip"), overwrite = true)
        work.deleteRecursively()
        return mutableListOf(mapOf("title" to "16-pad MPC kit · XPM + PCM24 WAVs", "file" to zip.name))
    }

    /** Every selected factory recipe reaches a hardware-loadable program, not only
     * the sixteen starter pads. Exporter-owned history retains actual WAV fingerprints. */
    private fun exportPresetBank(root: File, factory: Boolean): Map<String, String> {
        val presets = AlloyPresets.all()
        require(presets.size == 24 && presets.map { it.name }.distinct().size == presets.size)
        val work = File(root, ".alloy-bank-work")
        val bankName = if (factory) "SnipSnap Alloy Factory Bank" else "SnipSnap Alloy Selected Bank"
        val destination = File(root, "bank/$bankName")
        work.deleteRecursively()
        destination.deleteRecursively()
        require(destination.mkdirs() || destination.isDirectory)
        val groups = listOf(
            "Bass Key" to listOf(AlloyVoice.BASS, AlloyVoice.KEY),
            "Pluck Brass" to listOf(AlloyVoice.PLUCK, AlloyVoice.BRASS),
            "String Soft" to listOf(AlloyVoice.STRING, AlloyVoice.SOFT),
        )
        try {
            val programs = groups.map { (title, voices) ->
                val patches = voices.flatMap(AlloyPresets::forVoice)
                require(patches.size == 8)
                val pads = patches.map { patch ->
                    val recipe = PadRecipe(patch)
                    ArrangedPad(recipe.render(), Alloy.drumClassFor(patch.voice, patch.macros),
                        recipe.toJsonValue(), oneShot = true, displayName = patch.name)
                }
                val staging = File(work, title.replace(' ', '-'))
                val kit = KitAssembler.assembleArranged("SnipSnap Alloy $title", pads, staging)
                val result = KitExporter.exportProgramFolder(kit, staging, destination, overwrite = true)
                require(result.samples.size == patches.size)
                val sidecar = File(result.directory, KitStore.FILE_NAME)
                require(sidecar.isFile) { "$title export omitted its native history" }
                val exported = KitStore.load(result.directory)
                require(exported.pads.map { it.recipe } == kit.pads.map { it.recipe }) {
                    "$title changed complete recipes during export"
                }
                val exportedPads = exported.pads.zip(patches)
                val heldLabels = exportedPads.filter { (_, patch) ->
                    Alloy.isLoop(patch.macros.getValue("HOLD"))
                }.joinToString("; ") { (pad, patch) ->
                    "A${pad.slot.toString().padStart(2, '0')} ${patch.name}"
                }
                val heldInstructions = if (heldLabels.isEmpty()) "This program has no held pads."
                    else "For indefinite HOLD, enable whole-sample looping on: $heldLabels."
                File(result.directory, "README.txt").writeText(
                    "ALLOY factory bank — $title\n" +
                        "A01–A04: ${voices[0]}; A05–A08: ${voices[1]}. All patches are dry at their saved root and velocity.\n" +
                        "Load the XPM with its PCM24 WAVs and native kit.json beside it.\n" +
                        "These drum programs play each file once. $heldInstructions\n" +
                        "The owner approved these distinct roles. Physical phone and hardware MPC acceptance remain open.\n",
                )
                linkedMapOf<String, Any?>(
                    "name" to kit.name, "voices" to voices.map { it.name }, "presetCount" to patches.size,
                    "programFile" to result.program.relativeTo(destination).invariantSeparatorsPath,
                    "kitFile" to sidecar.relativeTo(destination).invariantSeparatorsPath,
                    "pads" to exportedPads.map { (pad, patch) ->
                        val sample = File(result.directory, pad.sampleFile)
                        val digest = sha256(sample.readBytes())
                        require(pad.source["rawSha256"] == digest) { "$title lost the exported WAV fingerprint" }
                        linkedMapOf("slot" to pad.slot, "name" to patch.name,
                            "voice" to patch.voice.name, "held" to Alloy.isLoop(patch.macros.getValue("HOLD")),
                            "file" to sample.relativeTo(destination).invariantSeparatorsPath,
                            "sha256" to digest, "patch" to patch.toJsonValue())
                    },
                )
            }
            File(destination, "bank.json").writeText(Json.write(value(linkedMapOf(
                "engine" to "ALLOY", "presetCount" to presets.size, "programCount" to groups.size,
                "selectedVoices" to selectedVoices(),
                "playback" to "Three eight-pad drum programs. Each complete finite WAV plays once. These are drum programs, not keygroup instruments.",
                "programs" to programs,
            ))) + "\n")
            File(destination, "README.txt").writeText(
                "ALLOY factory bank\n" +
                    "All ${presets.size} factory patches in three eight-pad programs: Bass Key, Pluck Brass, String Soft.\n" +
                    "Each voice retains its selected reference and three approved full-config roles. Every program carries its XPM, PCM24 WAVs and exporter-owned native kit.json with actual sample fingerprints.\n" +
                    "These factory roles are finite notes. The separate sixteen-pad starter kit retains two legacy held references.\n" +
                    "See bank.json for complete per-pad patches, program paths and held-pad flags.\n" +
                    "The owner approved the distinct-role direction; physical phone and hardware MPC acceptance remain open.\n",
            )
            val archive = File(root, if (factory) "alloy-factory-preset-bank.zip" else "alloy-selected-preset-bank.zip")
            zipDirectory(destination, archive, destination.name)
            println("ALLOY factory bank: three eight-pad programs, ${presets.size} complete recipes")
            return mapOf("title" to "${presets.size}-preset MPC bank · 3 × 8 pads · XPM + PCM24", "file" to archive.name)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun copyPage(root: File, selected: Boolean, factory: Boolean) {
        val pages = if (factory) listOf("alloy-factory/index.html" to "index.html",
            "alloy-factory/app.js" to "app.js", "alloy-factory/styles.css" to "styles.css")
        else if (selected) listOf("alloy-selected/index.html" to "index.html",
            "alloy-selected/app.js" to "app.js", "alloy-selected/styles.css" to "styles.css")
        else listOf("alloy-audition.html" to "index.html", "alloy-audition.css" to "alloy-audition.css", "alloy-audition.js" to "alloy-audition.js")
        for ((source, destination) in pages) {
            val resource = AlloyAuditionGenerator::class.java.getResourceAsStream("/audition/$source")
                ?: error("missing listening resource: synth/src/test/resources/audition/$source")
            File(root, destination).outputStream().use { out -> resource.use { it.copyTo(out) } }
        }
    }

    private fun provenance(roster: List<Case>, root: File): Map<String, Any?> {
        val sourceNames = listOf("Alloy.kt", "AlloyConfig.kt", "AlloyPatch.kt", "AlloyPresets.kt", "AlloyFactoryRoles.kt", "AlloyContrastPresets.kt")
        val cwd = File(System.getProperty("user.dir"))
        val synth = if (File(cwd, "src/main/kotlin/com/snipsnap/synth/Alloy.kt").isFile) cwd else File(cwd, "synth")
        val repo = synth.parentFile
        val paths = sourceNames.map { "synth/src/main/kotlin/com/snipsnap/synth/$it" } +
            listOf("synth/src/test/kotlin/com/snipsnap/synth/AlloyAuditionGenerator.kt", "synth/src/main/kotlin/com/snipsnap/synth/SynthKits.kt")
        val sources = paths.map { path ->
            val file = File(repo, path)
            require(file.isFile) { "cannot record source provenance: $file" }
            linkedMapOf("path" to path, "sha256" to sha256(file.readBytes()))
        }
        fun executableClasses(cls: Class<*>): List<Class<*>> =
            listOf(cls) + cls.declaredClasses.flatMap { executableClasses(it) }
        // Include the shared stages that shape the rendered and written bytes, not only ALLOY's own classes.
        val compiled = listOf(Alloy::class.java, AlloyConfig::class.java, AlloyPatch::class.java,
            AlloyPresets::class.java, AlloyFactoryRoles::class.java, AlloyContrastPresets::class.java, AlloyAuditionGenerator::class.java,
            SynthKits::class.java, Dsp::class.java, Tide::class.java, Resampler::class.java,
            AuditionLevel::class.java, Loudness::class.java, Snip::class.java, WavWriter::class.java,
            KitAssembler::class.java, KitExporter::class.java, KitStore::class.java,
            Classifier::class.java, FeatureExtractor::class.java)
            .flatMap { executableClasses(it) }.distinctBy { it.name }.sortedBy { it.name }.map { cls ->
            val bytes = cls.getResourceAsStream("/${cls.name.replace('.', '/')}.class")?.use { it.readBytes() }
                ?: error("cannot record compiled provenance for ${cls.name}")
            linkedMapOf("class" to cls.name, "sha256" to sha256(bytes))
        }
        val recipes = roster.map(::recipeRecord)
        val recipeJson = Json.write(value(recipes)) + "\n"
        File(root, "render-recipes.json").writeText(recipeJson)
        fun git(vararg args: String): String {
            val process = ProcessBuilder(listOf("git") + args).directory(repo).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            check(process.waitFor() == 0)
            return output
        }
        val revision = git("rev-parse", "HEAD")
        val clean = git("status", "--porcelain").isEmpty()
        return linkedMapOf(
            "baseGitRevision" to revision, "gitRevision" to revision,
            "sourceWorkingTreeClean" to clean, "sourceFiles" to sources,
            "sourceSha256" to sha256(Json.write(value(sources)).toByteArray(Charsets.UTF_8)),
            "compiledClasses" to compiled,
            "rosterSha256" to sha256(recipeJson.toByteArray(Charsets.UTF_8)),
            "renderRecipesFile" to "render-recipes.json",
            "meaning" to "The Git revision, clean-source flag, source and compiled-class hashes identify this render. Each case uses one complete recipe for rendering and metadata; attackModulation explicitly identifies diagnostic ablations.",
        )
    }

    internal fun recipeRecord(c: Case): Map<String, Any?> = linkedMapOf(
        "id" to c.id, "voice" to c.recipe.voice.name, "macros" to c.recipe.macros,
        "baseMidi" to c.recipe.midi, "effectiveMidi" to c.midi,
        "velocity" to c.recipe.velocity, "attackModulation" to !c.ablation,
        "config" to Json.write(c.recipe.config.toJsonValue()),
        "patch" to c.recipe.toJsonValue(), "renderRecipeFile" to "recipes/${c.id}.json",
        "patchFile" to if (c.ablation) null else "clip-patches/${c.id}.json",
    )

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }

    private fun zipDirectory(directory: File, destination: File, prefix: String) {
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            for (file in directory.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(directory).invariantSeparatorsPath }) {
                val entry = ZipEntry("$prefix/${file.relativeTo(directory).invariantSeparatorsPath}")
                entry.time = 0L // Stable archives; no current filesystem timestamps.
                zip.putNextEntry(entry)
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun writeMetrics(file: File, clips: List<Map<String, Any?>>) {
        val keys = listOf("id", "voice", "midi", "velocity", "loop", "frames", "duration", "rawPeak", "rms", "dc", "loudness", "matchedPeak", "matchedLoudness", "spectralCentroidHz", "seamError", "exportedSeamError", "stateError", "prerollCycles", "renderMs")
        file.bufferedWriter().use { out ->
            out.appendLine(keys.joinToString(","))
            clips.forEach { clip -> out.appendLine(keys.joinToString(",") { clip[it]?.toString() ?: "" }) }
        }
    }

    private fun centroid(samples: FloatArray): Double {
        val spectrum = Fft.magnitudeSpectrum(samples, 8192)
        var weighted = 0.0
        var total = 0.0
        for (i in 1 until spectrum.size) {
            val power = spectrum[i].toDouble().pow(2)
            total += power
            weighted += power * Fft.binToHz(i, 8192, Dsp.RATE)
        }
        return weighted / total.coerceAtLeast(1e-30)
    }

    private fun rms(samples: FloatArray) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1))
    private fun dc(samples: FloatArray) = samples.sumOf { it.toDouble() } / samples.size.coerceAtLeast(1)
    private fun waveform(samples: FloatArray): List<Float> = (0 until 200).map { bin ->
        val from = bin * samples.size / 200
        val end = ((bin + 1) * samples.size / 200).coerceAtMost(samples.size)
        (from until end).maxOfOrNull { abs(samples[it]) } ?: 0f
    }
    private fun relativeDifference(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "causal pair has different duration" }
        var diff = 0.0
        var power = 0.0
        for (i in a.indices) { val delta = a[i].toDouble() - b[i]; diff += delta * delta; power += a[i].toDouble() * a[i] }
        return sqrt(diff / power.coerceAtLeast(1e-30))
    }
    private fun finite(number: Double): Double? = number.takeIf { it.isFinite() }
    private fun tag(voice: AlloyVoice) = voice.name.lowercase(Locale.ROOT)
    private fun code(value: Float) = (value * 100).roundToInt().toString().padStart(3, '0')
    private fun fmt(value: Float) = "%.2f".format(Locale.ROOT, value).trimEnd('0').trimEnd('.')
    private fun slug(text: String) = text.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_')
    private fun noteName(midi: Int) = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[midi % 12] + (midi / 12 - 1)
    private fun value(item: Any?): JsonValue = when (item) {
        null -> JsonValue.Null
        is JsonValue -> item
        is String -> JsonValue.Str(item)
        is Boolean -> JsonValue.Bool(item)
        is Number -> item.toDouble().let { require(it.isFinite()) { "non-finite ALLOY diagnostic" }; JsonValue.Num(it) }
        is Map<*, *> -> JsonValue.Obj(item.entries.associate { it.key.toString() to value(it.value) })
        is Iterable<*> -> JsonValue.Arr(item.map { value(it) })
        else -> error("unsupported manifest value: ${item::class.simpleName}")
    }
}
