package com.snipsnap.synth

import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Native Kotlin renders and portable HTML; no listening or hardware acceptance is implied. */
object PatioAuditionGenerator {
    @JvmStatic fun main(args: Array<String>) {
        if (args.firstOrNull() == "--working") {
            require(args.size == 3) { "--working pad-id output.wav" }
            WavWriter.write(File(args[2]), PatioWorkingKit.render(PatioStudy.recipes().single { it.id == args[1] }))
            return
        }
        if (args.firstOrNull() == "--replay") {
            require(args.size == 3) { "--replay recipe.json output.wav" }
            WavWriter.write(File(args[2]), PatioStudy.fromJsonText(File(args[1]).readText()).render())
            return
        }
        if (args.firstOrNull() == "--hand") {
            require(args.size == 6) { "--hand OPEN|SLAP tune touch hold output.wav" }
            WavWriter.write(File(args[5]), PatioHand.render(PatioHand.Stroke.valueOf(args[1]),
                PatioHand.Settings(args[2].toFloat(), args[3].toFloat(), args[4].toFloat())))
            return
        }
        val root = File(args.firstOrNull() ?: "testkit/patio-audition").absoluteFile
        root.mkdirs()
        val recipes = PatioStudy.recipes()
        val pads = mutableListOf<ArrangedPad>()
        val dryHits = mutableListOf<Snip>()
        val wetHits = mutableListOf<Snip>()
        val rows = StringBuilder()
        val csv = StringBuilder("id,stage,seconds,peak,dc,centroid_hz,median_render_ms\n")
        fun emit(id: String, stage: String, s: Snip, ms: Double = 0.0): String {
            check(s.samples.all { it.isFinite() } && s.peak() <= 1f)
            WavWriter.write(File(root, "native/$id-$stage.wav"), s)
            val link = "listen/$id-$stage.wav"
            WavWriter.write(File(root, link), AuditionLevel.level(s))
            csv.append(String.format(Locale.ROOT, "%s,%s,%.6f,%.6f,%.8f,%.2f,%.3f%n", id, stage,
                s.durationSeconds, s.peak(), s.samples.average(), FeatureExtractor.extract(s).centroidHz, ms))
            return "<audio controls preload='none' src='$link'></audio>"
        }
        for ((index, r) in recipes.withIndex()) {
            File(root, "recipes/${r.id}.json").apply { parentFile.mkdirs(); writeText(r.toJsonText()) }
            val dry = r.dry()
            val tape = PatioWear.tape(dry, r.treatment, r.seed)
            val wet = PatioWear.amp(tape, r.treatment)
            repeat(2) { r.render() }
            val times = List(5) { val start = System.nanoTime(); r.render(); (System.nanoTime() - start) / 1e6 }
            rows.append("<tr><th>A${(index + 1).toString().padStart(2, '0')} ${r.name}</th>")
            rows.append("<td>${emit(r.id, "dry", dry)}</td><td>${emit(r.id, "tape", tape)}</td><td>${emit(r.id, "amp", wet, times.sorted()[2])}</td></tr>")
            dryHits += dry; wetHits += wet
            // The custom study format lives beside the WAVs. Do not put an unreadable format in PadRecipe.
            pads += ArrangedPad(wet, r.drumClass, level = r.mixGain, displayName = r.name,
                source = mapOf("patioStudyVersion" to PatioStudy.VERSION.toString(), "patioRecipe" to "recipes/${r.id}.json"),
                muteGroup = if (index in 2..3) 1 else 0)
        }
        // A diagnostic arrangement, not a claim to a traditional rhythm. Each pad gets an exposed hit.
        fun phrase(hits: List<Snip>, levels: List<Float> = recipes.map { it.mixGain }): Snip {
            val stride = (Dsp.RATE * .6).toInt()
            val x = FloatArray(stride * hits.size + Dsp.RATE * 2)
            hits.forEachIndexed { slot, s -> s.samples.forEachIndexed { i, v ->
                if (slot * stride + i < x.size) x[slot * stride + i] += v * levels[slot]
            } }
            Dsp.limitPeak(x, .90f)
            return Snip(x, 1, Dsp.RATE)
        }
        val dryPhrase = phrase(dryHits)
        val comparisons = "<p>Dry roll call ${emit("roll-call", "dry", dryPhrase)}</p>" +
            "<p>Individually processed pads ${emit("roll-call", "per-pad", phrase(wetHits))}</p>" +
            "<p>Shared tape and amp ${emit("roll-call", "shared", PatioWear.process(dryPhrase, PatioWear.Settings(), 729))}</p>"
        val characterRows = StringBuilder()
        val studies = listOf("alegre-open" to PatioCharacter.Voice.OPEN,
            "alegre-slap" to PatioCharacter.Voice.SLAP, "scrape-long" to PatioCharacter.Voice.SCRAPER,
            "guache" to PatioCharacter.Voice.SEEDS, "marimba" to PatioCharacter.Voice.BUZZ_BAR)
        for ((id, voice) in studies) {
            val old = recipes.single { it.id == id }
            val revised = PatioCharacter.render(voice)
            repeat(2) { PatioCharacter.render(voice) }
            val times = List(5) { val start = System.nanoTime(); PatioCharacter.render(voice); (System.nanoTime() - start) / 1e6 }
            val before = "<audio controls preload='none' src='listen/$id-dry.wav'></audio>"
            characterRows.append("<tr><th>${old.name}</th><td>$before</td><td>${emit("revision-$id", "dry", revised, times.sorted()[2])}</td>")
            characterRows.append("<td>${emit("revision-$id", "amp", PatioWear.process(revised, old.treatment, old.seed))}</td></tr>")
        }
        val handRows = StringBuilder()
        val handDynamics = StringBuilder()
        for (stroke in PatioHand.Stroke.entries) {
            val id = "alegre-${stroke.name.lowercase(Locale.ROOT)}"
            val recipe = recipes.single { it.id == id }
            val dry = PatioHand.render(stroke)
            repeat(2) { PatioHand.render(stroke) }
            val times = List(5) { val start = System.nanoTime(); PatioHand.render(stroke); (System.nanoTime() - start) / 1e6 }
            handRows.append("<tr><th>${recipe.name}</th><td><audio controls preload='none' src='listen/revision-$id-dry.wav'></audio></td>")
            handRows.append("<td>${emit("hand-$id", "dry", dry, times.sorted()[2])}</td><td>${emit("hand-$id", "amp", PatioWear.process(dry, recipe.treatment, recipe.seed))}</td></tr>")
            val strengths = listOf(.15f, .4f, .65f, .9f)
            val stride = (Dsp.RATE * .55).toInt()
            val sequence = FloatArray(stride * 4 + Dsp.RATE)
            strengths.forEachIndexed { slot, touch ->
                val hit = PatioHand.render(stroke, PatioHand.Settings(touch = touch))
                hit.samples.forEachIndexed { i, value -> sequence[slot * stride + i] += value * (.25f + .65f * touch) }
            }
            Dsp.limitPeak(sequence, .95f)
            handDynamics.append("<p>${recipe.name}, soft → hard ${emit("hand-$id", "touch", Snip(sequence, 1, Dsp.RATE))}</p>")
        }
        val refRows = StringBuilder()
        val refHits = linkedMapOf<String, Snip>()
        for (stroke in PatioHand.Stroke.entries) {
            val id = "alegre-${stroke.name.lowercase(Locale.ROOT)}"
            refHits[id] = PatioHand.render(stroke, reference = true)
        }
        for (direction in PatioScrape.Direction.entries) {
            refHits["scrape-${direction.name.lowercase(Locale.ROOT)}"] = PatioScrape.render(direction)
        }
        for (d in PatioScrape.Direction.entries) {
            emit("scrape-v1-${d.name.lowercase(Locale.ROOT)}", "dry", PatioScrape.render(d, modelVersion = 1))
            emit("scrape-v2-${d.name.lowercase(Locale.ROOT)}", "dry", PatioScrape.render(d, modelVersion = 2))
        }
        val alternating = FloatArray(Dsp.RATE * 4)
        for (i in 0 until 6) {
            val key = if (i % 2 == 0) "scrape-forward" else "scrape-return"
            val hit = AuditionLevel.level(refHits.getValue(key))
            val start = (i * .55 * Dsp.RATE).toInt()
            hit.samples.forEachIndexed { j, v -> alternating[start + j] += v * .8f }
        }
        val alternatingHtml = emit("scrape-v3-alternating", "dry", Snip(alternating, 1, Dsp.RATE))
        for ((id, hit) in refHits) {
            val old = if (id.startsWith("alegre")) "hand-$id-dry" else "scrape-v2-${id.removePrefix("scrape-")}-dry"
            refRows.append("<tr><th>$id</th><td><audio controls preload='none' src='listen/$old.wav'></audio></td><td>${emit("reference-$id", "dry", hit)}</td><td>${emit("reference-$id", "amp", PatioWear.process(hit, PatioWear.Settings(), 729))}</td></tr>")
        }
        // Two bars at 100 BPM, an authored audition pattern, not a traditional rhythm claim.
        val groove = FloatArray((6.0 * Dsp.RATE).toInt())
        fun place(hit: Snip, beat: Double, gain: Float) {
            val start = (beat * .6 * Dsp.RATE).toInt()
            hit.samples.forEachIndexed { i, value -> if (start + i < groove.size) groove[start + i] += value * gain }
        }
        val shaker = PatioCharacter.render(PatioCharacter.Voice.SEEDS)
        val bar = PatioCharacter.render(PatioCharacter.Voice.BUZZ_BAR)
        for (beat in 0 until 8) {
            if (beat % 2 == 0) place(PatioWorkingKit.dry(recipes[0]), beat.toDouble(), .48f)
            else place(PatioWorkingKit.dry(recipes[1]), beat.toDouble(), .22f)
            place(PatioWorkingKit.dry(recipes[2]), beat + .5, .10f)
            place(shaker, beat.toDouble(), .10f)
            place(refHits.getValue("scrape-forward"), beat.toDouble(), .19f * PatioWorkingKit.SCRAPE_MIX_TRIM)
            place(refHits.getValue("scrape-return"), beat + .5, .13f * PatioWorkingKit.SCRAPE_MIX_TRIM)
            place(refHits.getValue(if (beat % 2 == 0) "alegre-open" else "alegre-slap"), beat + .25, .38f)
            if (beat == 3 || beat == 7) place(refHits.getValue("alegre-open"), beat + .75, .24f)
        }
        place(bar, 0.0, .15f); place(bar, 4.0, .15f)
        Dsp.limitPeak(groove, .90f)
        val grooveDry = Snip(groove, 1, Dsp.RATE)
        val grooveHtml = "<p>Dry groove ${emit("reference-groove", "dry", grooveDry)}</p>" +
            "<p>Shared tape → amp ${emit("reference-groove", "amp", PatioWear.process(grooveDry, PatioWear.Settings(), 729))}</p>"
        val treatmentRows = StringBuilder()
        for (treatment in PatioTreatments.ALL) {
            val treated = PatioTreatments.process(grooveDry, treatment)
            treatmentRows.append("<tr><th>${treatment.title}${if (treatment.id == "chewed") " · working default" else ""}</th><td>${emit("treatment-${treatment.id}", "amp", treated)}</td></tr>")
        }
        emit("cleaner-groove", "amp", PatioCleanup.process(grooveDry))
        val cleanerHits = recipes.map { PatioCleanup.render(it) }
        recipes.zip(cleanerHits).forEach { (r, hit) -> emit("cleaner-${r.id}", "amp", hit) }
        emit("cleaner-roll-call", "amp", phrase(cleanerHits, recipes.map { PatioWorkingKit.level(it) }))
        val detailHits = recipes.map { PatioDetail.render(it) }
        recipes.zip(detailHits).forEach { (r, hit) -> emit("detail-${r.id}", "amp", hit) }
        emit("detail-roll-call", "amp", phrase(detailHits, recipes.map { PatioWorkingKit.level(it) }))
        val focusedIndices = recipes.indices.filter { recipes[it].id in PatioDetail.IDS }
        val focusedLevels = focusedIndices.map { PatioWorkingKit.level(recipes[it]) }
        emit("cleaner-focus", "amp", phrase(focusedIndices.map { cleanerHits[it] }, focusedLevels))
        emit("detail-focus", "amp", phrase(focusedIndices.map { detailHits[it] }, focusedLevels))
        val articulationHits = recipes.map { PatioArticulation.render(it) }
        val articulationDry = recipes.map { PatioArticulation.dry(it) }
        val detailDry = recipes.map { if (it.id in PatioDetail.IDS) PatioDetail.patch(it).render() else PatioCleanup.dry(it) }
        recipes.forEachIndexed { i, r ->
            emit("articulation-${r.id}", "amp", articulationHits[i])
            emit("articulation-${r.id}", "dry", articulationDry[i])
            emit("detail-${r.id}", "dry", detailDry[i])
        }
        emit("articulation-roll-call", "amp", phrase(articulationHits, recipes.map { PatioWorkingKit.level(it) }))
        emit("articulation-roll-call", "dry", phrase(articulationDry, recipes.map { PatioWorkingKit.level(it) }))
        emit("detail-roll-call", "dry", phrase(detailDry, recipes.map { PatioWorkingKit.level(it) }))
        emit("articulation-focus", "amp", phrase(focusedIndices.map { articulationHits[it] }, focusedLevels))
        emit("articulation-focus", "dry", phrase(focusedIndices.map { articulationDry[it] }, focusedLevels))
        emit("detail-focus", "dry", phrase(focusedIndices.map { detailDry[it] }, focusedLevels))
        val hollowHits = recipes.map { PatioHollow.render(it) }
        val hollowDry = recipes.map { PatioHollow.dry(it) }
        recipes.forEachIndexed { i, r ->
            emit("hollow-${r.id}", "amp", hollowHits[i])
            emit("hollow-${r.id}", "dry", hollowDry[i])
        }
        emit("hollow-roll-call", "amp", phrase(hollowHits, recipes.map { PatioWorkingKit.level(it) }))
        emit("hollow-roll-call", "dry", phrase(hollowDry, recipes.map { PatioWorkingKit.level(it) }))
        emit("hollow-focus", "amp", phrase(focusedIndices.map { hollowHits[it] }, focusedLevels))
        emit("hollow-focus", "dry", phrase(focusedIndices.map { hollowDry[it] }, focusedLevels))
        val grooveSamples = recipes.mapIndexed { i, r -> r.id to hollowHits[i] }.toMap()
        val grooveLevels = recipes.associate { it.id to PatioWorkingKit.level(it) }
        for (variation in PatioGrooveStudy.VARIATIONS) {
            emit("groove-$variation", "amp", PatioGrooveStudy.render(PatioGrooveStudy.events(variation), grooveSamples, grooveLevels, PatioGrooveStudy.BEATS))
        }
        emit("groove-run-through", "amp", PatioGrooveStudy.render(PatioGrooveStudy.runThrough(), grooveSamples, grooveLevels, PatioGrooveStudy.BEATS * 3))
        File(root, "groove-patterns.json").writeText(Json.write(JsonValue.Obj(mapOf(
            "version" to JsonValue.Num(PatioGrooveStudy.VERSION.toDouble()),
            "bpm" to JsonValue.Num(PatioGrooveStudy.BPM.toDouble()),
            "beatsPerVariation" to JsonValue.Num(PatioGrooveStudy.BEATS.toDouble()),
            "kit" to JsonValue.Str(PatioHollow.NAME),
            "rendering" to JsonValue.Str("Processed pad WAVs, fixed kit levels times event amplitude; hat mute group with 2ms choke; no additional bus effects; four bars plus 1s release; run-through offsets 0,16,32 beats"),
            "levels" to JsonValue.Obj(grooveLevels.mapValues { JsonValue.Num(it.value.toDouble()) }),
            "variations" to JsonValue.Obj(PatioGrooveStudy.VARIATIONS.associate { v -> v to JsonValue.Arr(PatioGrooveStudy.events(v).map { hit ->
                JsonValue.Obj(mapOf("pad" to JsonValue.Str(hit.pad), "beat" to JsonValue.Num(hit.beat), "gain" to JsonValue.Num(hit.gain.toDouble()))) }) })
        ))))
        val coreRows = StringBuilder()
        for (r in recipes.filter { it.id in PatioCore.IDS }) {
            val before = PatioTreatments.process(r.dry(), PatioWorkingKit.treatment, r.seed)
            val after = PatioWorkingKit.render(r)
            coreRows.append("<tr><th>${r.name}</th><td>${emit("core-before-${r.id}", "chewed", before)}</td><td>${emit("core-after-${r.id}", "chewed", after)}</td></tr>")
        }
        val workingPads = mutableListOf<ArrangedPad>()
        val workingHits = mutableListOf<Snip>()
        val workingRows = StringBuilder()
        val workingManifest = JsonValue.Obj(mapOf(
            "version" to JsonValue.Num(PatioWorkingKit.VERSION.toDouble()),
            "name" to JsonValue.Str(PatioWorkingKit.NAME),
            "processing" to JsonValue.Str("Chewed baked per pad; differs from shared-bus groove"),
            "treatment" to PatioWorkingKit.treatment.json(),
            "pads" to JsonValue.Arr(recipes.map { r -> JsonValue.Obj(mapOf(
                "id" to JsonValue.Str(r.id), "name" to JsonValue.Str(PatioWorkingKit.name(r)),
                "level" to JsonValue.Num(PatioWorkingKit.level(r).toDouble()),
                "source" to JsonValue.Str(PatioWorkingKit.source(r)), "effectSeed" to JsonValue.Num(r.seed.toDouble()),
                "selectedPatch" to (if (r.id in PatioCore.IDS) PatioCore.patch(r).toJsonValue() else JsonValue.Str("see source descriptor")),
                "baseStudy" to r.toJsonValue())) })))
        for ((i, r) in recipes.withIndex()) {
            val hit = PatioWorkingKit.render(r)
            workingHits += hit
            workingPads += ArrangedPad(hit, r.drumClass, level = PatioWorkingKit.level(r), displayName = PatioWorkingKit.name(r),
                source = mapOf("patioWorkingKitVersion" to PatioWorkingKit.VERSION.toString(), "padId" to r.id),
                muteGroup = if (i in 2..3) 1 else 0)
            workingRows.append("<tr><th>A${(i + 1).toString().padStart(2, '0')} ${PatioWorkingKit.name(r)}</th><td>${emit("working-${r.id}", "chewed", hit)}</td></tr>")
        }
        val workingRoll = emit("working-roll-call", "chewed", phrase(workingHits, recipes.map { PatioWorkingKit.level(it) }))
        val work = File(root, ".kit-work")
        try {
            val kit = KitAssembler.assembleArranged("SnipSnap Patio Study", pads, work)
            val exported = KitExporter.exportProgramFolder(kit, work, root, overwrite = true)
            val sidecars = File(exported.directory, "recipes").apply { mkdirs() }
            recipes.forEach { File(sidecars, "${it.id}.json").writeText(it.toJsonText()) }
            val selected = KitAssembler.assembleArranged(PatioWorkingKit.NAME, workingPads, work)
            val selectedExport = KitExporter.exportProgramFolder(selected, work, root, overwrite = true)
            File(selectedExport.directory, "working-kit.json").writeText(Json.write(workingManifest))
            File(root, "working-kit.json").writeText(Json.write(workingManifest))
        } finally { work.deleteRecursively() }
        val cleanerWork = File(root, ".cleaner-work")
        try {
            val cleanerPads = recipes.mapIndexed { i, r -> ArrangedPad(cleanerHits[i], r.drumClass,
                level = PatioWorkingKit.level(r), displayName = PatioWorkingKit.name(r),
                source = mapOf("patioCleanupVersion" to PatioCleanup.VERSION.toString(), "padId" to r.id),
                muteGroup = if (i in 2..3) 1 else 0) }
            val kit = KitAssembler.assembleArranged(PatioCleanup.NAME, cleanerPads, cleanerWork)
            val exported = KitExporter.exportProgramFolder(kit, cleanerWork, root, overwrite = true)
            val descriptor = Json.write(JsonValue.Obj(mapOf(
                "version" to JsonValue.Num(PatioCleanup.VERSION.toDouble()),
                "treatment" to PatioCleanup.treatment.json(), "base" to workingManifest,
                "conditioning" to JsonValue.Str("310Hz -3.5dB Q=.75 bell then 6500Hz one-pole before tape; 8000Hz one-pole after amp; surdo/cajon exponential tail after .10s tau=.20s; same pad levels and seeds"))))
            File(exported.directory, "cleaner-kit.json").writeText(descriptor)
            File(root, "cleaner-kit.json").writeText(descriptor)
        } finally { cleanerWork.deleteRecursively() }
        val detailWork = File(root, ".detail-work")
        try {
            val detailPads = recipes.mapIndexed { i, r -> ArrangedPad(detailHits[i], r.drumClass,
                level = PatioWorkingKit.level(r), displayName = PatioWorkingKit.name(r),
                source = mapOf("patioDetailVersion" to PatioDetail.VERSION.toString(), "padId" to r.id),
                muteGroup = if (i in 2..3) 1 else 0) }
            val kit = KitAssembler.assembleArranged(PatioDetail.NAME, detailPads, detailWork)
            val exported = KitExporter.exportProgramFolder(kit, detailWork, root, overwrite = true)
            val descriptor = Json.write(JsonValue.Obj(mapOf(
                "version" to JsonValue.Num(PatioDetail.VERSION.toDouble()),
                "base" to Json.parse(File(root, "cleaner-kit.json").readText()),
                "changedPatches" to JsonValue.Obj(recipes.filter { it.id in PatioDetail.IDS }.associate { it.id to PatioDetail.patch(it).toJsonValue() }))))
            File(exported.directory, "detail-kit.json").writeText(descriptor)
            File(root, "detail-kit.json").writeText(descriptor)
        } finally { detailWork.deleteRecursively() }
        val articulationWork = File(root, ".articulation-work")
        try {
            val articulationPads = recipes.mapIndexed { i, r -> ArrangedPad(articulationHits[i], r.drumClass,
                level = PatioWorkingKit.level(r), displayName = PatioWorkingKit.name(r),
                source = mapOf("patioArticulationVersion" to PatioArticulation.VERSION.toString(), "padId" to r.id),
                muteGroup = if (i in 2..3) 1 else 0) }
            val kit = KitAssembler.assembleArranged(PatioArticulation.NAME, articulationPads, articulationWork)
            val exported = KitExporter.exportProgramFolder(kit, articulationWork, root, overwrite = true)
            val descriptor = Json.write(JsonValue.Obj(mapOf(
                "version" to JsonValue.Num(PatioArticulation.VERSION.toDouble()),
                "base" to Json.parse(File(root, "cleaner-kit.json").readText()),
                "sourceRenderer" to JsonValue.Str("PatioArticulation.source v1: fixed authored modal/contact recipes; 4x internal; seed=base pad effectSeed"),
                "changedPads" to JsonValue.Arr(PatioArticulation.IDS.map { JsonValue.Str(it) }),
                "status" to JsonValue.Str("Josh: Detail v1 lacked distinct character. New articulations await listening; synthetic studies, not measured replicas."))))
            File(exported.directory, "articulation-kit.json").writeText(descriptor)
            File(root, "articulation-kit.json").writeText(descriptor)
        } finally { articulationWork.deleteRecursively() }
        val hollowWork = File(root, ".hollow-work")
        try {
            val hollowPads = recipes.mapIndexed { i, r -> ArrangedPad(hollowHits[i], r.drumClass,
                level = PatioWorkingKit.level(r), displayName = PatioWorkingKit.name(r),
                source = mapOf("patioHollowVersion" to PatioHollow.VERSION.toString(), "padId" to r.id),
                muteGroup = if (i in 2..3) 1 else 0) }
            val kit = KitAssembler.assembleArranged(PatioHollow.NAME, hollowPads, hollowWork)
            val exported = KitExporter.exportProgramFolder(kit, hollowWork, root, overwrite = true)
            val descriptor = Json.write(JsonValue.Obj(mapOf(
                "version" to JsonValue.Num(PatioHollow.VERSION.toDouble()),
                "base" to Json.parse(File(root, "articulation-kit.json").readText()),
                "sourceRenderer" to JsonValue.Str("PatioHollow v1: PatioArticulation.source(agogo-low, seed, pitchHz=490, hollowRevision=2); remaining pads unchanged from base"),
                "changedPads" to JsonValue.Arr(listOf(JsonValue.Str("agogo-low"))),
                "status" to JsonValue.Str("Lower hollow bell with damped ring; awaiting listening."))))
            File(exported.directory, "hollow-kit.json").writeText(descriptor)
            File(root, "hollow-kit.json").writeText(descriptor)
        } finally { hollowWork.deleteRecursively() }
        File(root, "metrics.csv").writeText(csv.toString())
        File(root, "manifest.json").writeText(Json.write(JsonValue.Obj(mapOf(
            "status" to JsonValue.Str("Kotlin study; not heard; regional voices include documented approximations"),
            "modelVersion" to JsonValue.Num(PatioStudy.VERSION.toDouble()),
            "workingDefault" to JsonValue.Str("chewed"),
            "workingKit" to workingManifest,
            "treatments" to JsonValue.Arr(PatioTreatments.ALL.map { it.json() }),
            "treatmentSource" to JsonValue.Str("reference-groove-dry; shared tape then amp; seed=729; dropout times=1.02,2.73,4.15 s, radius=.035 s"),
            "referenceStudy" to JsonValue.Obj(mapOf(
                "handVersion" to JsonValue.Num(PatioHand.REFERENCE_VERSION.toDouble()),
                "scrapeVersion" to JsonValue.Num(PatioScrape.VERSION.toDouble()),
                "handReplay" to JsonValue.Str("PatioHand.render(stroke, reference=true); default settings and seed"),
                "scrapeReplay" to JsonValue.Str("PatioScrape.render(direction); default settings and seed"),
                "groove" to JsonValue.Str("100 BPM, 2 bars, generator-authored arrangement; shared processing"),
                "status" to JsonValue.Str("Guided by user video measurements; no reference audio sampled; awaiting listening"))),
            "handStudy" to JsonValue.Obj(mapOf(
                "version" to JsonValue.Num(PatioHand.VERSION.toDouble()),
                "renderer" to JsonValue.Str("PatioHand.render"),
                "settings" to JsonValue.Str("tune=.5 touch=.6 hold=.45 seed=Dsp.seedFor(PATIO_HAND, stroke)"),
                "touchSequence" to JsonValue.Arr(listOf(.15, .4, .65, .9).map { JsonValue.Num(it) }),
                "status" to JsonValue.Str("New alegre open and slap; awaiting listening"))),
            "characterStudy" to JsonValue.Obj(mapOf(
                "version" to JsonValue.Num(PatioCharacter.VERSION.toDouble()),
                "renderer" to JsonValue.Str("PatioCharacter.render"),
                "settings" to JsonValue.Str("tune=.5 touch=.6 hold=.45 seed=Dsp.seedFor(PATIO_CHARACTER, voice)"),
                "voices" to JsonValue.Arr(studies.map { JsonValue.Str(it.second.name) }),
                "status" to JsonValue.Str("Josh: scraper, shaker and marimba improved; alegre still bland. Retained for comparison."))),
            "recipes" to JsonValue.Arr(recipes.map { it.toJsonValue() }),
            "rollCallSpacingSeconds" to JsonValue.Num(.6),
        ))))
        File(root, "archive.html").writeText("""
            <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
            <title>PATIO · reference-based revision</title><style>
            body{font:16px system-ui;margin:32px;background:#181b19;color:#eee}h1{color:#e5b476}
            table{border-collapse:collapse}th,td{padding:12px;text-align:left;border-bottom:1px solid #454a45}
            audio{width:240px}a{color:#e5b476}.scroll{overflow:auto}p{max-width:900px;line-height:1.5}
            </style><h1>PATIO: Chewed working kit</h1>
            <p>Chewed is the selected working default. The new <strong>SnipSnap Patio Chewed</strong> MPC program uses
            the revised kick, snare and hats alongside the latest alegre, guacharaca, shaker and marimba, with Chewed baked into all sixteen pads.</p>
            <h2>Kick, snare and hats: first voicing pass</h2>
            <p>A tighter kick with more beater, a fuller head/wire snare, and a matched metallic hat pair.
            Both columns use the same Chewed treatment and matched listening level. These new voicings await your judgment.</p>
            <div class="scroll"><table><thead><tr><th>Pad</th><th>Before · Chewed</th><th>New · Chewed</th></tr></thead><tbody>$coreRows</tbody></table></div>
            <p>Mix revision: both guacharaca strokes are 3 dB lower in the groove and MPC program. Solo comparisons keep their matched listening level.</p>
            <p>Processed kit roll call $workingRoll</p>
            <p>Load <strong>SnipSnap Patio Chewed.xpm</strong> from its matching folder in the download.
            These pads are processed individually. The shared tape/amp groove below has different inter-instrument
            saturation and dropout timing. MPC hardware loading has not been verified.</p>
            <details><summary>Listen to all 16 Chewed pads</summary><table><thead><tr><th>Pad</th><th>Chewed</th></tr></thead><tbody>$workingRows</tbody></table></details>
            <h2>Shared processing: selected sound and alternatives</h2>
            <p>The same two-bar groove through four treatments. Listening copies match the loudest 200 ms window;
            compare attack, motion and texture rather than playback volume.</p>
            <div class="scroll"><table><thead><tr><th>Treatment</th><th>Listen</th></tr></thead><tbody>
            <tr><th>Current chain</th><td><audio controls preload="none" src="listen/reference-groove-amp.wav"></audio></td></tr>
            $treatmentRows</tbody></table></div>
            <p><strong>Pushed:</strong> stronger tape saturation and amp breakup.
            <strong>Chewed:</strong> deeper wobble, darker tape, brief losses and gritty amp.
            <strong>Overloaded:</strong> heavy drive and supply sag for compressed attacks and rough sustain.</p>
            <p>All processing is shared across the groove. Settings are in the manifest; treatment-* WAVs are in the download.
            Chewed is the working selection; Pushed and Overloaded remain alternatives.</p>
            <h2>Source comparisons and dry groove</h2>
            <p>Alegre now emphasizes the reference's body resonances and hand damping with less added noise.
            Guacharaca now adds a firm initial catch, thicker rasp and woody contact body. Forward accelerates; return is a fuller answering scrape.
            Compare against the previous lighter strokes. These are authored gestures, not an acoustic replica.</p>
            <p>Compare dry first, then the groove. Video measurements guide these synthetic models;
            the recordings are not sampled and acoustic authenticity remains a listening question.</p>
            <div class="scroll"><table><thead><tr><th>Voice</th><th>Previous dry</th><th>New dry</th><th>New → tape → amp</th></tr></thead><tbody>$refRows</tbody></table></div>
            <h2>Forward → return, repeated three times</h2>
            <p>Each hit is level-matched before sequencing, so compare its shape and length. $alternatingHtml</p>
            <h2>Two-bar groove · 100 BPM</h2>
            <p>New alegre and guacharaca with the retained shaker/marimba and revised kick, snare and hats.
            An audition arrangement, not a traditional pattern.</p>
            $grooveHtml
            <p>New files use reference-* names in the download. The download includes the selected Chewed program and the original study program.</p>
            <h2>Previous alegre studies</h2>
            <p>Third audition. Start with the dry comparison: the open stroke adds hollow body and skin texture;
            the slap separates a brief finger crack from a choked head. These are model changes to judge,
            not a claim of acoustic authenticity.</p>
            <div class="scroll"><table><thead><tr><th>Stroke</th><th>Previous dry (revision two)</th><th>New dry</th><th>New → tape → amp</th></tr></thead><tbody>$handRows</tbody></table></div>
            <h2>Hand pressure: soft → hard</h2>
            <p>Four strikes of each stroke. Pressure changes contact width and skin balance as well as playback level.</p>
            $handDynamics
            <p>New files are named hand-alegre-* in the listen and native folders. The original MPC program is still included.</p>
            <h2>Revision two: retained comparisons</h2>
            <p>Revision two responds to the first audition sounding too much like a basic drum machine.
            Scraper, shaker and marimba were closer in your listening; the earlier alegre is retained here for reference.</p>
            <p>Open and slap now have different hand contacts; the scraper has slower, irregular ribs;
            the seed shaker has two collision clouds; the bar drives an air resonance and a threshold buzz.
            These are synthetic hypotheses, not measured replicas. Judge the revised dry column first.</p>
            <div class="scroll"><table><thead><tr><th>Study</th><th>Original dry</th><th>Revised dry</th><th>Revised → tape → amp</th></tr></thead><tbody>$characterRows</tbody></table></div>
            <p>The revised studies are in the ZIP under listen/revision-* and native/revision-*.
            The original study program is retained separately from the selected Chewed kit.</p>
            <h2>Original sixteen-pad kit</h2>
            <p>Sixteen synthetic percussion studies. Compare contact, body and decay before judging the wear.
            Listening copies match loudness; native files preserve renderer levels. The original kit was heard as too generic; later studies above record the revisions.</p>
            <p>The hand drums, caixa, hats and marimba reuse existing models and are approximations.
            Scrape and guache use new event models. Marimba buzz is still TERRA's contact-noise proxy.</p>
            $comparisons<div class="scroll"><table><thead><tr><th>Pad</th><th>Dry</th><th>Tape wear</th><th>Tape → amp</th></tr></thead><tbody>$rows</tbody></table></div>
            <p><a href="metrics.csv">Measurements</a> · <a href="manifest.json">Complete study recipes</a></p>
            <p>Load the SnipSnap Patio Study XPM folder on MPC. Study recipes replay through the generator;
            they are not yet editable through the app's patch picker. No hardware load has been verified.</p>
            <script>document.querySelectorAll('audio').forEach(player => {
              const row = player.closest('tr');
              if(row) player.setAttribute('aria-label', row.querySelector('th').textContent + ' ' + player.closest('table').querySelectorAll('thead th')[player.closest('td').cellIndex].textContent);
              player.addEventListener('play', () => document.querySelectorAll('audio').forEach(other => { if(other !== player) other.pause(); }));
            });</script></html>
        """.trimIndent())
        val options = recipes.mapIndexed { i, r ->
            "<option value='${r.id}'>A${(i + 1).toString().padStart(2, '0')} · ${PatioWorkingKit.name(r)}</option>"
        }.joinToString("\n")
        val page = PatioAuditionGenerator::class.java.getResource("/audition/patio-compact.html")!!.readText()
        File(root, "cleanup-comparison.html").writeText(page.replace("@@PADS@@", options))
        val detailOptions = recipes.mapIndexedNotNull { i, r -> if (r.id in PatioDetail.IDS)
            "<option value='${r.id}'>A${(i + 1).toString().padStart(2, '0')} · ${PatioWorkingKit.name(r)}</option>" else null }.joinToString("\n")
        val detailPage = PatioAuditionGenerator::class.java.getResource("/audition/patio-detail.html")!!.readText()
        File(root, "detail-comparison.html").writeText(detailPage.replace("@@PADS@@", detailOptions))
        val articulationPage = PatioAuditionGenerator::class.java.getResource("/audition/patio-articulation.html")!!.readText()
        File(root, "articulation-comparison.html").writeText(articulationPage.replace("@@PADS@@", detailOptions))
        File(root, "hollow-comparison.html").writeText(PatioAuditionGenerator::class.java.getResource("/audition/patio-hollow.html")!!.readText())
        File(root, "index.html").writeText(PatioAuditionGenerator::class.java.getResource("/audition/patio-grooves.html")!!.readText())
        ZipOutputStream(File(root, "patio-grooves.zip").outputStream().buffered()).use { out ->
            val assets = listOf("groove-patterns.json") + listOf("native", "listen").flatMap { folder ->
                (PatioGrooveStudy.VARIATIONS + "run-through").map { "$folder/groove-$it-amp.wav" }
            }
            for (path in assets) {
                out.putNextEntry(ZipEntry(path).apply { time = 0 })
                File(root, path).inputStream().use { it.copyTo(out) }; out.closeEntry()
            }
        }
        for ((name, filename) in listOf(PatioWorkingKit.NAME to "patio-chewed-kit.zip", PatioCleanup.NAME to "patio-cleaner-kit.zip", PatioDetail.NAME to "patio-detail-kit.zip", PatioArticulation.NAME to "patio-articulation-kit.zip", PatioHollow.NAME to "patio-hollow-kit.zip")) {
            ZipOutputStream(File(root, filename).outputStream().buffered()).use { out ->
                File(root, name).walkTopDown().filter { it.isFile }.forEach { file ->
                    out.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath).apply { time = 0 })
                    file.inputStream().use { it.copyTo(out) }; out.closeEntry()
                }
            }
        }
        val zip = File(root.parentFile, "patio-audition.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            root.walkTopDown().filter { it.isFile && it.extension != "zip" }.sortedBy { it.relativeTo(root).path }.forEach { file ->
                out.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath).apply { time = 0 })
                file.inputStream().use { it.copyTo(out) }; out.closeEntry()
            }
        }
        println("PATIO audition: ${root.path}; archive: ${zip.path}")
    }
}
