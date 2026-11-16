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
object SplinterDelivery {
    const val CELLS = 162
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

    /** Eight bars at 200 BPM that use every pad: break, slices, the gabber bar, the rush,
     * then the stutter, the riser and the landing. Slots are 1-based pads. */
    fun groove(): Mpc3Clip {
        val notes = mutableListOf<Mpc3Note>()
        fun hit(slot: Int, beat: Double, v: Float) { notes += Mpc3Note(Mpc3Note.noteFor(slot), (beat * 960).roundToLong(), v) }
        fun breakBar(b: Double, ride: Boolean) {
            hit(1, b, 1f); hit(1, b + .5, .7f); hit(3, b + 1, 1f); hit(4, b + 1.75, .4f); hit(4, b + 2.25, .35f)
            hit(1, b + 2.5, .85f); hit(1, b + 2.75, .6f); hit(3, b + 3, .95f); hit(4, b + 3.75, .4f)
            for (i in 0..7) hit(if (ride) 9 else 7, b + i * .5, if (i % 2 == 0) .7f else .45f)
        }
        breakBar(0.0, true)
        hit(11, 4.0, 1f); hit(12, 5.0, 1f); hit(11, 6.0, .8f); hit(12, 7.0, .9f); hit(14, 7.5, .7f); hit(5, 6.5, .7f)
        for (i in 0..7) hit(7, 6 + i * .25, if (i % 2 == 0) .65f else .4f)
        for (i in 0..2) { hit(2, 8.0 + i, 1f); hit(8, 8.5 + i, .6f) }
        hit(6, 9.0, .6f); hit(15, 10.0, .8f); hit(10, 11.0, .8f); hit(10, 11.5, .7f)
        hit(16, 12.0, 1f); hit(17, 12.0, .85f)
        hit(1, 16.0, 1f); hit(13, 16.0, .9f); hit(1, 19.0, .9f); hit(3, 19.5, .9f)
        breakBar(20.0, false)
        hit(18, 24.0, .9f); for (i in 0..3) hit(8, 24.5 + i, .5f)
        hit(2, 28.0, 1f); hit(16, 28.0, .8f); hit(3, 29.0, 1f); hit(11, 30.0, 1f); hit(12, 31.0, 1f)
        return Mpc3Clip(Splinter.TITLE, 8, notes.sortedBy { it.timePulses })
    }

    fun generate(root: File) {
        val started = System.nanoTime()
        root.mkdirs(); val audio = File(root, "audio").apply { mkdirs() }
        val base = File(root, Splinter.ID); val native = File(base, "Native")
        val starter = checkNotNull(StarterKits.byId(Splinter.ID))
        check(StarterShelf.display().flatMap { it.second }.count { it.id == Splinter.ID } == 1)
        val kit = starter.render(Splinter.TITLE, native); val loaded = KitStore.load(native)
        check(kit == loaded && loaded.pads.size == 18)
        val renderSeconds = (System.nanoTime() - started) / 1e9
        val wavs = loaded.pads.associate { it.slot to WavReader.read(File(native, it.sampleFile)) }
        val expectedByPad = mutableMapOf<Int, List<List<Snip>>>()
        // Saved source -> rerender -> the PCM on disk -> what the pad resolver plays, for all 162 cells.
        // The fourth repeat (t = 3) must wrap to the first take.
        for (p in loaded.pads) {
            val recipe = Splinter.Recipe.fromJsonText(p.source.getValue("sourceRecipe"))
            check(recipe.pad == p.slot - 1)
            val wav = wavs.getValue(p.slot)
            val grid = Splinter.velocities.map { v -> (0..2).map { t -> recipe.copy(velocity = v, take = t).render() } }
            expectedByPad[p.slot] = grid
            for ((z, v) in Splinter.velocities.withIndex()) for (t in 0..3) {
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
        check(imported.skipped.isEmpty() && imported.kit.pads.size == 18)
        for ((p, q) in loaded.pads.zip(imported.kit.pads)) {
            check(p.chain == q.chain); val a = wavs.getValue(p.slot); val b = WavReader.read(File(imported.directory, q.sampleFile))
            check(a.samples.contentEquals(b.samples))
            for (v in Splinter.velocities) for (t in 0..3) {
                val x = PadHit.resolve(p, v.toFloat(), t) { a.frameCount.toLong() }!!; val y = PadHit.resolve(q, v.toFloat(), t) { b.frameCount.toLong() }!!
                check(x.startFrame == y.startFrame && x.endFrameExclusive == y.endFrameExclusive)
            }
        }
        File(base, "verify-sfz").deleteRecursively()
        val bpm = Splinter.BPM.toFloat(); val clip = groove()
        check(clip.notes.map { it.note }.toSet().size == 18) { "the groove must play every pad" }
        val preview = KitPreview.render(loaded, native, clip, bpm)
        check(preview.samples.contentEquals(KitPreview.render(KitStore.load(native), native, clip, bpm).samples))
        // Counterexamples: with the takes frozen, or the hats unchoked, playback must differ.
        val fixed = loaded.copy(pads = loaded.pads.map { p -> p.copy(chain = p.chain!!.copy(zones = p.chain!!.zones!!.map { it.copy(cycle = 1) })) })
        check(!preview.samples.contentEquals(KitPreview.render(fixed, native, clip, bpm).samples))
        val choke = Mpc3Clip("Hat choke", 1, listOf(Mpc3Note(Mpc3Note.noteFor(8), 0, 1f), Mpc3Note(Mpc3Note.noteFor(7), 77, 1f)))
        check(!KitPreview.render(loaded, native, choke, bpm).samples.contentEquals(
            KitPreview.render(loaded.copy(pads = loaded.pads.map { it.copy(muteGroup = 0) }), native, choke, bpm).samples))
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
        write("splinter-groove.wav", preview)
        // Touch: kick, crack snare and slice at soft, medium, hard, three repeats each, from the saved kit.
        val touch = Mpc3Clip("Touch", 14, listOf(1, 3, 11).flatMapIndexed { k, slot -> (0..8).map { n ->
            Mpc3Note(Mpc3Note.noteFor(slot), (k * 18 + n * 2) * 960L, Splinter.velocities[n / 3].toFloat()) } })
        write("splinter-touch.wav", KitPreview.render(loaded, native, touch, bpm))
        // Reel: every pad once at full force, raw source level times its mixer level, in pad order.
        val gap = WavWriter.MPC_SAMPLE_RATE / 4
        val hits = loaded.pads.map { p -> expectedByPad.getValue(p.slot)[2][0].samples.let { s -> FloatArray(s.size) { i -> s[i] * p.level } } }
        val reel = FloatArray(hits.sumOf { it.size + gap }); var at = 0
        for (h in hits) { h.copyInto(reel, at); at += h.size + gap }
        write("splinter-pad-reel.wav", Snip(reel, 1, WavWriter.MPC_SAMPLE_RATE), ceiling = null)
        for (p in loaded.pads) write("splinter-pad-%02d.wav".format(p.slot), expectedByPad.getValue(p.slot)[2][0], ceiling = null)
        val downloads = mutableMapOf<String, String>()
        for (format in listOf("Native", "SFZ", "MPC3")) {
            val folder = File(base, format)
            File(folder, "README.txt").writeText("${Splinter.TITLE}: sample-free synthesis at ${Splinter.BPM} BPM. 18 pads, three velocities x three takes on every pad, including the snare rush and riser.\nKick and snare sources are the owner-approved Spore pair; the edits and the arrangement await listening.\nNative sourceRecipe metadata rerenders through Kotlin; not an app-editable synth patch. Native, SFZ and MPC 3 grids and PCM are software-verified. Phone and MPC playback unverified. No MPC 2 export.\n")
            val zip = File(root, "${Splinter.ID}-${format.lowercase()}.zip"); archive(folder, zip); downloads[format] = zip.name
        }
        File(root, "manifest.json").writeText(Json.write(json(mapOf(
            "id" to Splinter.ID, "title" to Splinter.TITLE, "bpm" to Splinter.BPM, "recipeVersion" to Splinter.VERSION,
            "status" to "approved kick/snare sources; edits and arrangement awaiting listen",
            "pads" to Splinter.layout.mapIndexed { i, p -> mapOf("slot" to i + 1, "name" to p.name, "level" to p.level, "muteGroup" to p.muteGroup) },
            "downloads" to downloads, "gridCells" to CELLS, "nativeRecipeReplay" to true, "nativePlayback" to true, "hatChoke" to true,
            "sfzGridPcmAndPlayback" to true, "mpc3GridAndPcm" to true, "hardware" to "unverified",
            "starterRenderSeconds" to renderSeconds, "renderVerifySeconds" to (System.nanoTime() - started) / 1e9,
            "kickModelSha256" to SporeKick.MODEL_SHA256, "snareModelSha256" to SporeSnare.MODEL_SHA256,
            "audioSha256" to audio.listFiles()!!.filter { it.extension == "wav" }.sortedBy { it.name }.associate { it.name to hash(it) }))))
        println("PASS ${Splinter.ID}: $CELLS cells through saved source, playback, hat choke, SFZ and MPC 3. Starter render %.1f s.".format(renderSeconds))
    }
    @JvmStatic fun main(args: Array<String>) = generate(File(args.first()))
}
