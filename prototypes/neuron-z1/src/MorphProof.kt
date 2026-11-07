package com.snipsnap.synth

import com.snipsnap.audio.*
import com.snipsnap.json.Json
import java.io.File
import kotlin.math.*
import kotlin.test.*

/**
 * Reconstructed finite proof from the original conversation.
 * This is physical parameter mapping, not a neural model or registered engine.
 * Breath is bounded colored-noise excitation, not a nonlinear reed valve.
 */
internal object MorphProof {
    const val VERSION = 1
    const val SECONDS = 1.4
    const val SOURCE_OFF = .55
    private const val IR = Dsp.RATE * Dsp.OVERSAMPLE

    data class Recipe(
        val midi: Int = 60,
        val model: Float = .4f,
        val space: Float = .5f,
        val stiff: Float = .6f,
        val bright: Float = .5f,
        val velocity: Float = .7f,
    ) {
        fun settled() = copy(
            midi = midi.coerceIn(48, 72),
            model = safe(model, .4f),
            space = safe(space, .5f),
            stiff = safe(stiff, .6f),
            bright = safe(bright, .5f),
            velocity = safe(velocity, .7f),
        )
        private fun safe(v: Float, default: Float) =
            if (v.isFinite()) v.coerceIn(0f, 1f) else default
    }

    data class Plan(
        val hz: Float, val loopHz: Float, val stiffness: Float,
        val fb: Float, val n: Int, val a: Float,
    )

    data class Result(
        val recipe: Recipe,
        val raw: FloatArray,
        val matched: FloatArray,
        val rawPeak: Float,
        val loopPeak: Float,
        val releaseRatio: Double,
        val planHit: Boolean,
        val millis: Double,
    )

    // Only immutable coefficients are cached. Running state is always fresh.
    private val plans = object : LinkedHashMap<List<Any>, Plan>(32, .75f, true) {
        override fun removeEldestEntry(
            e: MutableMap.MutableEntry<List<Any>, Plan>?
        ) = size > 32
    }

    fun clearCache() = synchronized(plans) { plans.clear() }
    fun cacheSize() = synchronized(plans) { plans.size }

    private fun plan(r: Recipe): Pair<Plan, Boolean> = synchronized(plans) {
        val key = listOf(VERSION, IR, r.midi, r.stiff, r.bright)
        plans[key]?.let { return@synchronized it to true }

        val hz = (440.0 * 2.0.pow((r.midi - 69) / 12.0)).toFloat()
        val cutoff = 2200f + 10500f * r.bright
        val stiffness = -.2f - .65f * r.stiff
        val tuning = Strings.tune(hz, cutoff, IR, stiffness = stiffness)
        val decay = .28 + .62 * r.stiff
        val fb = exp(-ln(1000.0) / (hz * decay))
            .toFloat().coerceAtMost(.999f)

        Plan(hz, cutoff, stiffness, fb, tuning.n, tuning.a)
            .also { plans[key] = it } to false
    }

    enum class Readout { ORIGINAL, EARLY_MOTION, FROZEN_EARLY }

    // One finite color excursion while the pluck still carries energy. The
    // later return joins the original MODEL center smoothly, before source-off.
    fun earlyPosition(t: Double, model: Float): Float = when {
        t < .09 -> sin(PI * t.coerceAtLeast(0.0) / .09).let { (it * it).toFloat() }
        t < .18 -> ((t - .09) / .09).let { (it * it * (3 - 2 * it)).toFloat() * model }
        else -> model
    }

    fun render(
        input: Recipe,
        releaseAt: Double = SOURCE_OFF,
        readout: Readout = Readout.ORIGINAL,
    ): Result {
        val start = System.nanoTime()
        val r = input.settled()
        val (p, hit) = plan(r)
        val loop = Strings.Loop(
            p.n, p.a, p.fb, p.loopHz, IR, stiffness = p.stiffness
        )

        // Controls share the same random take so comparisons isolate mappings.
        val seed = Dsp.seedFor("morph-proof", VERSION, r.midi)
        val burst = Strings.pluckExciter(
            p.n, p.hz, 1200f + 14000f * r.bright,
            .23f, seed, IR, IR
        )
        val noise = Dsp.Noise(seed)
        val breathLp = Dsp.OnePole(IR)
        val breathHp = Dsp.OnePole(IR)
        val bodyLp = Dsp.OnePole(IR)
        val narrowLp = Dsp.OnePole(IR)
        val narrowLp2 = Dsp.OnePole(IR)
        val formA = Dsp.Biquad().apply {
            bandpass(720f + 350f * r.model, 1.8f, IR)
        }
        val formB = Dsp.Biquad().apply {
            bandpass(2400f + 1700f * r.model, 2.4f, IR)
        }

        val wave = FloatArray((SECONDS * IR).roundToInt())
        val velocity = r.velocity.toDouble().pow(.65).toFloat()
        var peak = 0f
        var heldEnergy = 0.0
        var tailEnergy = 0.0
        var heldN = 0
        var tailN = 0

        for (i in wave.indices) {
            val t = i.toDouble() / IR
            val charge = ((t - .025) / .16).coerceIn(0.0, 1.0)
                .let { it * it * (3 - 2 * it) }
            val end = ((releaseAt - t) / .06).coerceIn(0.0, 1.0)
                .let { it * it * (3 - 2 * it) }

            val low = breathLp.lp(
                noise.next(), 1500f + 10000f * r.bright
            )
            val flow = low - breathHp.lp(low, 90f)
            val pick = if (i < burst.size)
                burst[i] * (.65f - .3f * r.model) else 0f
            val drive = (
                pick + (.025 * r.model * charge * end * flow).toFloat()
            ) * velocity
            val y = loop.next(drive)
            peak = max(peak, abs(y))

            // Readout motion does not retune or feed back into the guide.
            val a = formA.process(y)
            val b = formB.process(y)
            val lowBody = bodyLp.lp(y, 1100f + 800f * r.model)
            val dark = .7f * lowBody + .3f * a
            val light = .7f * (y - lowBody) + .3f * b
            if (readout == Readout.ORIGINAL || r.space == 0f) {
                val orbit = (.5 + .5 * sin(2 * PI * 1.4 * t)).toFloat()
                val position = (1 - r.space) * r.model + r.space * orbit
                wave[i] = .2f * y +
                    .8f * ((1 - position) * dark + position * light)
            } else {
                // A stronger contrast of two readouts of this same guide, not
                // another instrument, feedback change, or extra excitation.
                // Keep the direct root, and scale the dark bandwidth with pitch.
                val narrow = narrowLp2.lp(narrowLp.lp(y, 1.35f * p.hz), 1.35f * p.hz)
                val darkWide = .9f * narrow + .1f * a
                val lightWide = 1.8f * (y - lowBody) + .2f * b
                val position = if (readout == Readout.FROZEN_EARLY) 0f else earlyPosition(t, r.model)
                val moving = .2f * y + .8f * ((1 - position) * darkWide + position * lightWide)
                val stationary = .2f * y + .8f * ((1 - r.model) * dark + r.model * light)
                // Give the first quarter of the knob useful travel, while
                // preserving the original static output exactly at SPACE zero.
                val depth = sqrt(r.space)
                wave[i] = stationary + depth * (moving - stationary)
            }

            if (t in .32.. .50) {
                heldEnergy += y * y
                heldN++
            }
            if (t in 1.25..1.39) {
                tailEnergy += y * y
                tailN++
            }
        }

        Tide.bandLimit(wave, IR)
        val raw = Dsp.decimate(wave, Dsp.RATE)
        val mean = raw.average().toFloat()
        val hp = Dsp.OnePole(Dsp.RATE)
        for (i in raw.indices) {
            val x = raw[i] - mean
            raw[i] = x - hp.lp(x, 20f)
        }
        val rawPeak = raw.maxOf { abs(it) }
        val matched = raw.copyOf()
        if (r.velocity > 0f) {
            Dsp.levelTo(
                matched, Dsp.RATE,
                Dsp.MELODIC_LOUDNESS_TARGET * velocity,
                ceiling = .99f * velocity
            )
        }
        Dsp.fadeTail(raw)
        Dsp.fadeTail(matched)

        return Result(
            r, raw, matched, rawPeak, peak,
            (tailEnergy / tailN) /
                (heldEnergy / heldN).coerceAtLeast(1e-30),
            hit, (System.nanoTime() - start) / 1e6
        )
    }
}

class MorphProofTest {
    private val out = File(System.getProperty("morph.workspace"), "audition")
    private fun snip(x: FloatArray) = Snip(x, 1, Dsp.RATE)

    private fun distance(a: FloatArray, b: FloatArray): Double {
        val n = 8192
        val x = Fft.magnitudeSpectrum(a.copyOfRange(4410, 4410 + n), n)
        val y = Fft.magnitudeSpectrum(b.copyOfRange(4410, 4410 + n), n)
        val xn = sqrt(x.sumOf { it.toDouble() * it }).coerceAtLeast(1e-12)
        val yn = sqrt(y.sumOf { it.toDouble() * it }).coerceAtLeast(1e-12)
        val squared = (4..2229).mapNotNull { i ->
            val xx = x[i] / xn
            val yy = y[i] / yn
            if (max(xx, yy) < 1e-3) null else {
                val d = 20 * log10(max(xx, 1e-3) / max(yy, 1e-3))
                d * d
            }
        }
        return sqrt(squared.average())
    }

    @Test
    fun `reconstructed finite audition and probes`() {
        out.mkdirs()
        val results = linkedMapOf<String, MorphProof.Result>()
        val base = MorphProof.Recipe()
        MorphProof.clearCache()
        results["baseline-cold"] = MorphProof.render(base)
        results["baseline-repeat"] = MorphProof.render(base)
        assertContentEquals(
            results.getValue("baseline-cold").matched,
            results.getValue("baseline-repeat").matched
        )
        assertFalse(results.getValue("baseline-cold").planHit)
        assertTrue(results.getValue("baseline-repeat").planHit)

        for (note in listOf(48, 60, 72))
            results["note-$note"] = MorphProof.render(base.copy(midi = note))

        for (control in listOf("model", "space", "stiff", "bright")) {
            for (v in listOf(0f, .25f, .5f, .75f, 1f)) {
                val r = when (control) {
                    "model" -> base.copy(model = v)
                    "space" -> base.copy(space = v)
                    "stiff" -> base.copy(stiff = v)
                    else -> base.copy(bright = v)
                }
                results["$control-$v"] = MorphProof.render(r)
            }
        }

        for (note in listOf(48, 60, 72))
            for (m in listOf(0f, 1f))
                for (s in listOf(0f, 1f))
                    results["model-stiff-$note-$m-$s"] =
                        MorphProof.render(
                            base.copy(midi = note, model = m, stiff = s)
                        )

        for (m in listOf(0f, 1f))
            for (s in listOf(0f, 1f))
                results["model-space-$m-$s"] =
                    MorphProof.render(base.copy(model = m, space = s))

        for (v in listOf(.2f, 1f))
            results["velocity-$v"] = MorphProof.render(base.copy(velocity = v))

        assertEquals(43, results.size)
        assertTrue(MorphProof.render(base.copy(velocity = 0f))
            .matched.all { it == 0f })
        assertContentEquals(
            MorphProof.render(base.copy(model = Float.NaN)).matched,
            results.getValue("baseline-cold").matched
        )

        val csv = StringBuilder(
            "id,midi,model,space,stiff,bright,velocity,ms,cache_hit," +
            "raw_peak,guide_peak,peak,dc,loudness,pitch_hz,confidence," +
            "cents,release_energy_ratio\n"
        )
        for ((id, r) in results) {
            assertEquals(61740, r.matched.size, id)
            assertTrue(r.raw.all { it.isFinite() }, id)
            assertTrue(r.matched.all { it.isFinite() }, id)
            val peak = r.matched.maxOf { abs(it) }
            val dc = abs(r.matched.average())
            assertTrue(peak in .01f..1f, "$id peak=$peak")
            assertTrue(r.loopPeak < 2f, "$id guide=${r.loopPeak}")
            assertTrue(dc < .005, "$id dc=$dc")
            assertTrue(r.releaseRatio < .02, "$id release=${r.releaseRatio}")

            val pitch = Pitch.detect(snip(r.matched), .08f, .25f)
            assertNotNull(pitch, "$id has no detected root")
            val target = 440 * 2.0.pow((r.recipe.midi - 69) / 12.0)
            val cents = 1200 * log2(pitch.hz / target)
            assertTrue(abs(cents) < 35, "$id cents=$cents")
            assertTrue(pitch.confidence >= .3f, id)

            WavWriter.write(File(out, "raw/$id.wav"), snip(r.raw))
            WavWriter.write(File(out, "matched/$id.wav"), snip(r.matched))
            val q = r.recipe
            File(out, "recipes/$id.json").apply {
                parentFile.mkdirs()
            }.writeText(
                """{"version":1,"midi":${q.midi},"model":${q.model},"space":${q.space},"stiff":${q.stiff},"bright":${q.bright},"velocity":${q.velocity},"hold":false}"""
            )
            csv.append(
                "$id,${q.midi},${q.model},${q.space},${q.stiff}," +
                "${q.bright},${q.velocity},${r.millis},${r.planHit}," +
                "${r.rawPeak},${r.loopPeak},$peak,$dc," +
                "${Loudness.of(snip(r.matched))},${pitch.hz}," +
                "${pitch.confidence},$cents,${r.releaseRatio}\n"
            )
        }
        File(out, "metrics.csv").writeText(csv.toString())

        val activity = StringBuilder(
            "control,range,normalized_spectral_distance_db\n"
        )
        for (c in listOf("model", "space", "stiff", "bright")) {
            for ((lo, hi) in listOf(0f to 1f, 0f to .25f, .25f to .5f)) {
                val d = distance(
                    results.getValue("$c-$lo").matched,
                    results.getValue("$c-$hi").matched
                )
                val threshold = if (hi == 1f) 1.0 else .2
                assertTrue(d > threshold, "$c $lo-$hi activity=$d dB")
                activity.append("$c,$lo-$hi,$d\n")
            }
        }
        File(out, "macro-activity.csv").writeText(activity.toString())

        // Counterexample: a source that never releases must fail the release gate.
        assertTrue(
            MorphProof.render(base, releaseAt = 2.0).releaseRatio > .02,
            "Release guard failed its negative control"
        )

        repeat(4) { MorphProof.render(base) }
        val timings = StringBuilder("trial,uncached_ms,cached_ms\n")
        repeat(7) { i ->
            MorphProof.clearCache()
            val cold = MorphProof.render(base)
            val warm = MorphProof.render(base)
            assertFalse(cold.planHit)
            assertTrue(warm.planHit)
            assertContentEquals(cold.matched, warm.matched)
            timings.append("$i,${cold.millis},${warm.millis}\n")
        }
        File(out, "timings.csv").writeText(timings.toString())

        for (note in 48..80)
            MorphProof.render(
                base.copy(
                    midi = note.coerceAtMost(72),
                    bright = (note - 48) / 32f
                )
            )
        assertEquals(32, MorphProof.cacheSize())

        val cards = results.keys.filter { it != "baseline-repeat" }
            .joinToString("\n") { id ->
                """
                <article><h2>$id</h2>
                <p>Matched</p>
                <audio controls preload="none" src="matched/$id.wav"></audio>
                <p>Raw</p>
                <audio controls preload="none" src="raw/$id.wav"></audio>
                <p><a href="recipes/$id.json">Recipe</a></p></article>
                """.trimIndent()
            }
        File(out, "index.html").writeText(
            """
            <!doctype html>
            <html lang="en"><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Physical morph — reconstructed audition</title>
            <style>
            body{font:17px system-ui;background:#171b20;color:#eee;
            max-width:950px;margin:40px auto;padding:20px}
            article{border-top:1px solid #555;padding:18px 0}
            audio{width:min(100%,600px)}a{color:#99d0ff}
            </style></head><body>
            <h1>Physical morph prototype</h1>
            <p>This is a reconstructed build from the recorded Kotlin design,
            not recovery of the original ZIP. Measurements belong to this build.</p>
            <p>Picked attack → breath-driven sustain → moving body → release.
            Deterministic physical parameter mapping; no neural model or HOLD.</p>
            <p>Start with MODEL 0, 0.5 and 1. Does this remain one object,
            or read as a pluck with added air? Not listening-approved.</p>
            <p><a href="verification.json">Build verification</a> ·
            <a href="metrics.csv">Measurements</a> ·
            <a href="macro-activity.csv">Macro comparisons</a></p>
            $cards
            </body></html>
            """.trimIndent()
        )
    }
}

object MorphProofReplay {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Usage: recipe.json output.wav" }
        val q = Json.parse(File(args[0]).readText()).obj()
        require(q.keys == setOf(
            "version", "midi", "model", "space", "stiff",
            "bright", "velocity", "hold"
        ))
        require(q.getValue("version").int() == MorphProof.VERSION)
        require(!q.getValue("hold").bool()) { "HOLD is not implemented" }
        val midi = q.getValue("midi").int()
        require(midi in 48..72)
        fun value(k: String) = q.getValue(k).num().also {
            require(it.isFinite() && it in 0.0..1.0)
        }.toFloat()
        val r = MorphProof.Recipe(
            midi, value("model"), value("space"), value("stiff"),
            value("bright"), value("velocity")
        )
        val output = File(args[1])
        require(!output.exists()) { "Output already exists" }
        WavWriter.write(
            output, Snip(MorphProof.render(r).matched, 1, Dsp.RATE)
        )
    }
}
