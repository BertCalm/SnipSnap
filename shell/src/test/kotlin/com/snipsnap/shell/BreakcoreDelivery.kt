package com.snipsnap.shell

import com.snipsnap.audio.*
import com.snipsnap.json.*
import com.snipsnap.kit.*
import com.snipsnap.mpc3.*
import com.snipsnap.synth.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.*

/** Shelf, saved source, playback and exported PCM evidence for every SPLINTER cell:
 * 18 pads x 3 velocities x 3 takes = 162. Also writes the listening files (a groove,
 * a touch clip and a pad reel played from the saved kit) and one archive per format.
 */
/** One delivery over every [BreakcoreKit]: shelf, saved source, playback and exported PCM evidence
 * for every cell (pads x 3 velocities x 3 takes), the listening files, and one archive per format.
 * [SplinterDelivery] is this with SPLINTER's spec. */
object BreakcoreDelivery {
    private fun json(v: Any?): JsonValue = when (v) {
        null -> JsonValue.Null; is String -> JsonValue.Str(v); is Boolean -> JsonValue.Bool(v); is Number -> JsonValue.Num(v.toDouble())
        is Map<*, *> -> JsonValue.Obj(v.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(v.map(::json)); else -> error("json")
    }
    private fun hash(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
    private fun archive(root: File, out: File) {
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            root.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
                z.putNextEntry(ZipEntry(root.name + "/" + f.relativeTo(root).invariantSeparatorsPath).apply { time = 0 })
                f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
    }

    /** What differs between kits: the groove (every pad at least once), the pair that chokes
     * (1-based slots, closed hat second) or null, the three pads for the touch clip, and the words. */
    class Spec(val kit: BreakcoreKit, val prefix: String, val groove: Mpc3Clip, val choke: Pair<Int, Int>?, val touchSlots: List<Int>,
               val readme: String, val status: String, val manifestExtra: Map<String, Any> = emptyMap())

    fun generate(spec: Spec, root: File) {
        val family = spec.kit; val prefix = spec.prefix; val CELLS = 9 * family.layout.size; val id = family.id; val title = family.title
        val started = System.nanoTime()
        root.mkdirs(); val audio = File(root, "audio").apply { mkdirs() }
        val base = File(root, id); val native = File(base, "Native")
        val starter = checkNotNull(StarterKits.byId(id))
        check(StarterShelf.display().flatMap { it.second }.count { it.id == id } == 1)
        val kit = starter.render(title, native); val loaded = KitStore.load(native)
        check(kit == loaded && loaded.pads.size == family.layout.size)
        val renderSeconds = (System.nanoTime() - started) / 1e9
        val wavs = loaded.pads.associate { it.slot to WavReader.read(File(native, it.sampleFile)) }
        val expectedByPad = mutableMapOf<Int, List<List<Snip>>>()
        // Saved source -> rerender -> the PCM on disk -> what the pad resolver plays, for all 162 cells.
        // The fourth repeat (t = 3) must wrap to the first take.
        for (p in loaded.pads) {
            val recipe = Breakcore.Recipe.fromJsonText(p.source.getValue("sourceRecipe"))
            check(recipe.pad == p.slot - 1)
            val wav = wavs.getValue(p.slot)
            val grid = Breakcore.VELOCITIES.map { v -> (0..2).map { t -> recipe.copy(velocity = v, take = t).render() } }
            expectedByPad[p.slot] = grid
            for ((z, v) in Breakcore.VELOCITIES.withIndex()) for (t in 0..3) {
                val hit = checkNotNull(PadHit.resolve(p, v.toFloat(), t) { wav.frameCount.toLong() })
                val expected = grid[z][t % 3]; check(hit.endFrameExclusive - hit.startFrame == expected.frameCount.toLong())
                check(abs(hit.gainLeft - p.level * v.toFloat()) < 1e-6)
                for (i in expected.samples.indices) check(abs(expected.samples[i] - wav.samples[hit.startFrame.toInt() + i]) <= 3f / 8388608)
            }
            check(p.chain!!.zones!!.all { it.cycle == 3 })
        }
        val sfz = SfzWriter.write(loaded, native, File(base, "SFZ"), overwrite = true)
        check(sfz.readText().lineSequence().count { it.startsWith("<region>") } == CELLS)
        val imported = SfzImporter.import(sfz, File(base, "verify-sfz"), overwrite = true)
        check(imported.skipped.isEmpty() && imported.kit.pads.size == family.layout.size)
        for ((p, q) in loaded.pads.zip(imported.kit.pads)) {
            check(p.chain == q.chain); val a = wavs.getValue(p.slot); val b = WavReader.read(File(imported.directory, q.sampleFile))
            check(a.samples.contentEquals(b.samples))
            for (v in Breakcore.VELOCITIES) for (t in 0..3) {
                val x = PadHit.resolve(p, v.toFloat(), t) { a.frameCount.toLong() }!!; val y = PadHit.resolve(q, v.toFloat(), t) { b.frameCount.toLong() }!!
                check(x.startFrame == y.startFrame && x.endFrameExclusive == y.endFrameExclusive)
            }
        }
        File(base, "verify-sfz").deleteRecursively()
        val bpm = family.bpm.toFloat(); val clip = spec.groove
        check(clip.notes.map { it.note }.toSet().size == family.layout.size) { "the groove must play every pad" }
        val preview = KitPreview.render(loaded, native, clip, bpm)
        check(preview.samples.contentEquals(KitPreview.render(KitStore.load(native), native, clip, bpm).samples))
        // Counterexamples: with the takes frozen, or the hats unchoked, playback must differ.
        val fixed = loaded.copy(pads = loaded.pads.map { p -> p.copy(chain = p.chain!!.copy(zones = p.chain!!.zones!!.map { it.copy(cycle = 1) })) })
        check(!preview.samples.contentEquals(KitPreview.render(fixed, native, clip, bpm).samples))
        // A kit with no mute group (SHATTER) has no choke to prove.
        val chokers = family.layout.indices.filter { family.layout[it].muteGroup != 0 }
        check(spec.choke != null == chokers.isNotEmpty()) { "choke pair must be named exactly when the kit has a mute group" }
        spec.choke?.let { (first, second) ->
            check(first - 1 in chokers && second - 1 in chokers)
            val choke = Mpc3Clip("Hat choke", 1, listOf(Mpc3Note(Mpc3Note.noteFor(first), 0, 1f), Mpc3Note(Mpc3Note.noteFor(second), 77, 1f)))
            check(!KitPreview.render(loaded, native, choke, bpm).samples.contentEquals(
                KitPreview.render(loaded.copy(pads = loaded.pads.map { it.copy(muteGroup = 0) }), native, choke, bpm).samples))
        }
        val mpc = Mpc3Exporter.exportTrack(loaded, native, File(base, "MPC3"), overwrite = true, mpc2Twin = false, clip = clip)
        check(!ExportReadBack.verify(loaded, ExportOutcome(ExportFormat.MPC3_TRACK, mpc.program, mpc.samples.first().parentFile, mpc.findings)).blocked())
        val instruments = Mpc3Project.read(mpc.program).drumPrograms().single().getValue("instruments").arr()
        val exportSamples = mpc.samples.associateBy { it.name }
        for (p in loaded.pads) {
            val layers = instruments[p.slot - 1].obj().getValue("layersv").arr().map { it.obj() }.filter { it.getValue("sampleFile").str().isNotBlank() }
            check(layers.size == 3)
            for ((i, z) in p.chain!!.zones!!.asReversed().withIndex()) {
                val l = layers[i]; check(l.getValue("sliceCycleLength").int() == 3 && l.getValue("sliceIncrement").int() == 1 && l.getValue("sliceIndex").int() == z.baseSlice)
                val f = checkNotNull(exportSamples[l.getValue("sampleFile").str()])
                check(WavReader.read(f).samples.contentEquals(wavs.getValue(p.slot).samples))
            }
        }
        // Listening files. A preview-only common gain leaves the downloadable pad grid untouched.
        fun write(name: String, s: Snip, ceiling: Float? = .9f) {
            val gain = if (ceiling == null) 1f else min(1f, ceiling / s.peak())
            WavWriter.write(File(audio, name), s.copy(samples = FloatArray(s.samples.size) { s.samples[it] * gain }), WavWriter.BitDepth.PCM_24)
        }
        write("$prefix-groove.wav", preview)
        // Touch: three pads at soft, medium, hard, three repeats each, from the saved kit.
        val touch = Mpc3Clip("Touch", 14, spec.touchSlots.flatMapIndexed { k, slot -> (0..8).map { n ->
            Mpc3Note(Mpc3Note.noteFor(slot), (k * 18 + n * 2) * 960L, Breakcore.VELOCITIES[n / 3].toFloat()) } })
        write("$prefix-touch.wav", KitPreview.render(loaded, native, touch, bpm))
        // Reel: every pad once at full force, raw source level times its mixer level, in pad order.
        val gap = WavWriter.MPC_SAMPLE_RATE / 4
        val hits = loaded.pads.map { p -> expectedByPad.getValue(p.slot)[2][0].samples.let { s -> FloatArray(s.size) { i -> s[i] * p.level } } }
        val reel = FloatArray(hits.sumOf { it.size + gap }); var at = 0
        for (h in hits) { h.copyInto(reel, at); at += h.size + gap }
        write("$prefix-pad-reel.wav", Snip(reel, 1, WavWriter.MPC_SAMPLE_RATE), ceiling = null)
        for (p in loaded.pads) write("$prefix-pad-%02d.wav".format(p.slot), expectedByPad.getValue(p.slot)[2][0], ceiling = null)
        val downloads = mutableMapOf<String, String>()
        for (format in listOf("Native", "SFZ", "MPC3")) {
            val folder = File(base, format)
            File(folder, "README.txt").writeText(spec.readme)
            val zip = File(root, "${id}-${format.lowercase()}.zip"); archive(folder, zip); downloads[format] = zip.name
        }
        File(root, "manifest.json").writeText(Json.write(json(mapOf(
            "id" to id, "title" to title, "bpm" to family.bpm, "recipeVersion" to Breakcore.RECIPE_VERSION,
            "status" to spec.status,
            "pads" to family.layout.mapIndexed { i, p -> mapOf("slot" to i + 1, "name" to p.name, "level" to p.level, "muteGroup" to p.muteGroup) },
            "downloads" to downloads, "gridCells" to CELLS, "nativeRecipeReplay" to true, "nativePlayback" to true, "hatChoke" to (spec.choke != null),
            "sfzGridPcmAndPlayback" to true, "mpc3GridAndPcm" to true, "hardware" to "unverified",
            "starterRenderSeconds" to renderSeconds, "renderVerifySeconds" to (System.nanoTime() - started) / 1e9,
            *spec.manifestExtra.toList().toTypedArray(),
            "audioSha256" to audio.listFiles()!!.filter { it.extension == "wav" }.sortedBy { it.name }.associate { it.name to hash(it) }))))
        println("PASS ${id}: $CELLS cells through saved source, playback${if (spec.choke != null) ", hat choke" else ""}, SFZ and MPC 3. Starter render %.1f s.".format(renderSeconds))
    }
}
