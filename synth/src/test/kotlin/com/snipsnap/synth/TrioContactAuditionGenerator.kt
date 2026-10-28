package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt

/** A listening experiment, with no new production mapping or ordinary candidate recipe. */
object TrioContactAuditionGenerator {
    private fun str(x: String) = JsonValue.Str(x)
    private fun num(x: Number) = JsonValue.Num(x.toDouble())
    private fun obj(vararg p: Pair<String, JsonValue>) = JsonValue.Obj(linkedMapOf(*p))
    private fun arr(x: List<JsonValue>) = JsonValue.Arr(x)
    private fun sha(x: ByteArray) = MessageDigest.getInstance("SHA-256").digest(x).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun scaled(x: Snip, gain: Float) = Snip(FloatArray(x.samples.size) { x.samples[it] * gain }, 1, Dsp.RATE)
    private fun finished(probe: TrioProbe): Snip {
        val x = probe.samples.copyOf()
        Dsp.levelTo(x, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(x)
        return Snip(x, 1, Dsp.RATE)
    }
    private data class Selection(val id: String, val title: String, val kind: String, val variant: String,
        val drama: Float, val touch: Float?, val view: String, val snip: Snip, val recipe: String?,
        val cueValues: List<Float> = emptyList())
    private fun quickSweep(parts: List<Snip>): Snip {
        val step = (1.7f * Dsp.RATE).roundToInt()
        val excerpt = (1.5f * Dsp.RATE).roundToInt()
        val fade = (.012f * Dsp.RATE).roundToInt()
        val x = FloatArray(step * parts.size)
        for ((k, part) in parts.withIndex()) for (i in 0 until excerpt) {
            val g = if (i >= excerpt - fade) (excerpt - 1 - i).toFloat() / fade else 1f
            x[k * step + i] = part.samples[i] * g
        }
        return Snip(x, 1, Dsp.RATE)
    }
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.single()).absoluteFile.also { it.mkdirs() }
        val config = TrioConfig(seed = 47010, model = 6)
        val selections = linkedMapOf<String, Selection>()
        val probes = mutableMapOf<String, TrioProbe>()
        val files = mutableListOf<String>()
        fun scene(drama: Float, touch: Float, variant: String): String {
            val id = "d${(drama * 100).roundToInt()}_t${(touch * 100).roundToInt()}_$variant"
            if (id in selections) return id
            val macros = Trio.defaults(TrioVoice.ENSEMBLE) + mapOf("TOUCH" to touch, "DRAMA" to drama, "SIDES" to (1f / 6f))
            val patch = TrioPatch("Contact listening $id", TrioVoice.ENSEMBLE, macros, 60, 1f, config)
            val probe = if (variant == "current") Trio.renderProbe(patch.voice, macros, 60, 1f, config)
                else TrioContactProfile.render(macros, 60, 1f, config)
            probes[id] = probe
            val snip = finished(probe)
            check(snip.samples.all { it.isFinite() } && snip.peak() <= .99001f)
            val recipe = "recipes/$id.json"
            val value = if (variant == "current") patch.toJsonValue() else obj(
                "type" to str("trio-contact-listening-experiment"), "basePatch" to patch.toJsonValue(),
                "stage" to str("excitation before string loops"), "contactAmount" to num(TrioContactProfile.amount(touch)),
                "delayFractionOfCoursePeriod" to num(1.0 / 3.0),
                "note" to str("Diagnostic wrapper; ordinary SnipSnap recipes retain the current mapping."))
            File(root, recipe).also { it.parentFile.mkdirs(); it.writeText(Json.write(value) + "\n") }
            files += recipe
            selections[id] = Selection(id, "${if (variant == "current") "Current" else "Contact experiment"} · TOUCH $touch · DRAMA $drama",
                "scene", variant, drama, touch, "mix", snip, recipe)
            return id
        }
        val groups = mutableListOf<JsonValue>()
        fun group(id: String, title: String, description: String, drama: Float, values: List<Float>,
            variants: List<String>, view: String = "mix") {
            fun selection(touch: Float, variant: String): String {
                val baseId = scene(drama, touch, variant)
                if (view == "mix") return baseId
                val tapId = "${baseId}_tres"
                if (tapId !in selections) {
                    val probe = probes.getValue(baseId)
                    val sum = selections.getValue(baseId).snip.samples
                    val peakAt = probe.samples.indices.maxBy { abs(probe.samples[it]) }
                    val gain = sum[peakAt] / probe.samples[peakAt]
                    val tap = FloatArray(sum.size) { probe.actors.getValue(TrioActor.TRES)[it] * gain }
                    Dsp.fadeTail(tap)
                    selections[tapId] = Selection(tapId, "Tres source · TOUCH $touch · $variant", "tap", variant,
                        drama, touch, view, Snip(tap, 1, Dsp.RATE), selections.getValue(baseId).recipe)
                }
                return tapId
            }
            val rows = values.map { touch -> obj("touch" to num(touch), "clips" to obj(*variants.map {
                it to str(selection(touch, it)) }.toTypedArray())) }
            val sweeps = variants.map { variant ->
                val sweepId = "${id}_${variant}_sweep"
                // Normalize each original selection before making excerpts; the sweep adds no gain.
                val parts = values.map { selections.getValue(selection(it, variant)).snip }
                selections[sweepId] = Selection(sweepId, "$title · ${if (variant == "current") "Current" else "Contact experiment"} sweep",
                    "sweep", variant, drama, null, view, quickSweep(parts), null, values)
                variant to str(sweepId)
            }
            groups += obj("id" to str(id), "title" to str(title), "description" to str(description),
                "values" to arr(values.map(::num)), "variants" to arr(variants.map(::str)), "rows" to arr(rows),
                "sweeps" to obj(*sweeps.toTypedArray()))
        }
        group("full-range", "First, hear the whole range", "Current TOUCH 0 → 0.5 → 1, at DRAMA 1. This separates a weak upper range from the whole control.",
            1f, listOf(0f, .5f, 1f), listOf("current"))
        for (drama in listOf(1f, .5f)) group("upper-d${(drama * 100).roundToInt()}", "Upper range · DRAMA $drama",
            "Compare 0.8 → 0.9 → 1. The contact experiment progressively reduces every third partial, creating a lower/mid color change.",
            drama, listOf(.8f, .9f, 1f), listOf("current", "contact"))
        group("tres-detail", "Closer listen · Tres source", "The Tres contribution from those same DRAMA 1 ensembles, with the same strums and returned-wave interaction.",
            1f, listOf(.8f, .9f, 1f), listOf("current", "contact"), "tres")
        for (drama in listOf(.5f, 1f)) {
            val current = probes.getValue(scene(drama, .8f, "current"))
            val candidate = probes.getValue(scene(drama, .8f, "contact"))
            check(current.samples.contentEquals(candidate.samples))
            for (touch in listOf(.8f, .9f, 1f)) {
                val a = probes.getValue(scene(drama, touch, "current"));val b = probes.getValue(scene(drama, touch, "contact"))
                check(a.events == b.events)
                for (actor in listOf(TrioActor.OUD, TrioActor.BOUZOUKI, TrioActor.PHIN)) check(b.events.count { it.actor == actor } == 1)
            }
        }
        val clips = selections.values.map { s ->
            val assets = listOf("raw", "matched").associateWith { level ->
                val path = "audio/${s.id}_$level.wav"
                val audio = if (s.kind == "sweep" && level == "matched") {
                    // A matched sweep is rebuilt from separately matched source notes, preserving their comparison levels.
                    val group = groups.first { (it as JsonValue.Obj).entries.getValue("sweeps").let { v ->
                        (v as JsonValue.Obj).entries.values.any { (it as JsonValue.Str).value == s.id } } } as JsonValue.Obj
                    val rows = (group.entries.getValue("rows") as JsonValue.Arr).items
                    val parts = rows.map { row ->
                        val ids = ((row as JsonValue.Obj).entries.getValue("clips") as JsonValue.Obj).entries
                        val source = selections.getValue((ids.getValue(s.variant) as JsonValue.Str).value).snip
                        scaled(source, .03f / Loudness.of(source).coerceAtLeast(1e-9f))
                    }
                    quickSweep(parts)
                } else scaled(s.snip, if (level == "raw") .25f else .03f / Loudness.of(s.snip).coerceAtLeast(1e-9f))
                check(audio.samples.all { it.isFinite() } && audio.peak() < .99f)
                WavWriter.write(File(root, path), audio);files += path;str(path)
            }
            obj("id" to str(s.id), "title" to str(s.title), "kind" to str(s.kind), "variant" to str(s.variant),
                "drama" to num(s.drama), "touch" to (s.touch?.let(::num) ?: JsonValue.Null), "view" to str(s.view),
                "assets" to JsonValue.Obj(assets), "recipePath" to (s.recipe?.let(::str) ?: JsonValue.Null),
                "durationSeconds" to num(s.snip.durationSeconds), "cueValues" to arr(s.cueValues.map(::num)),
                "cueStepSeconds" to num(1.7))
        }
        val sourcePaths = listOf("src/main/kotlin/com/snipsnap/synth/Trio.kt", "src/main/kotlin/com/snipsnap/synth/TrioScore.kt",
            "src/main/kotlin/com/snipsnap/synth/Strings.kt", "src/main/kotlin/com/snipsnap/synth/Dsp.kt", "src/main/kotlin/com/snipsnap/synth/Modes.kt",
            "src/test/kotlin/com/snipsnap/synth/TrioContactProfile.kt", "src/test/kotlin/com/snipsnap/synth/TrioContactAuditionGenerator.kt",
            "src/test/resources/trio-contact/index.html")
        val hashes = files.distinct().associateWith { sha(File(root, it).readBytes()) }
        val round = "trio-contact-" + sha(hashes.values.joinToString().toByteArray()).take(16)
        val manifest = obj("engine" to str("TRIO"), "baseModel" to num(6), "listeningRound" to str(round), "clips" to arr(clips),
            "groups" to arr(groups), "assetHashes" to JsonValue.Obj(hashes.mapValues { str(it.value) }),
            "sourceHashes" to obj(*sourcePaths.map { it to str(sha(File(it).readBytes())) }.toTypedArray()),
            "levelMethod" to str("Matched: each full selection uses the loudest 200 ms proxy at .03, not LUFS. Raw: engine level at .25 monitor gain; Tres taps retain their scene gain. Sweeps use the first 1.5 seconds of those selections, 12 ms excerpt fades and .2-second gaps."),
            "status" to str("Listening-only contact experiment; production mapping and saved recipes remain unchanged."))
        val json = Json.write(manifest)
        File(root, "manifest.json").writeText(json + "\n")
        File(root, "index.html").writeText(File("src/test/resources/trio-contact/index.html").readText().replace("__MANIFEST__", json.replace("</", "<\\/")))
        println("TRIO contact audition: $round; ${clips.size} selections; ${hashes.size} assets")
    }
}
