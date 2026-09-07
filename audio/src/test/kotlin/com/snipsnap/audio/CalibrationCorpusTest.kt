package com.snipsnap.audio

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The real-capture calibration harness — `reference/calibration/`'s
 * labeled WAVs against the classifier, with the report that makes a
 * threshold change an evidence-based edit instead of a vibe.
 *
 * Quiet while the corpus is empty; loud the moment it isn't.
 */
class CalibrationCorpusTest {

    /** X4.1's contract: the snip path and the feature path are one classifier. */
    @Test
    fun `classify by snip and by features agree on every render`() {
        val renders = listOf(
            DrumSynth.kick(), DrumSynth.snare(), DrumSynth.closedHat(), DrumSynth.openHat(),
            DrumSynth.clap(), DrumSynth.tom(), DrumSynth.tonal(), DrumSynth.loop(),
        )
        for (snip in renders) {
            val bySnip = Classifier.classify(snip)
            val byFeatures = Classifier.classify(FeatureExtractor.extract(snip))
            kotlin.test.assertEquals(bySnip.drumClass, byFeatures.drumClass)
            kotlin.test.assertEquals(bySnip.confidence, byFeatures.confidence)
        }
    }

    private val corpusDir = File("../reference/calibration")

    private val labels = mapOf(
        "kick" to DrumClass.KICK,
        "snare" to DrumClass.SNARE,
        "clap" to DrumClass.CLAP,
        "hatclosed" to DrumClass.HAT_CLOSED,
        "closedhat" to DrumClass.HAT_CLOSED,
        "hatopen" to DrumClass.HAT_OPEN,
        "openhat" to DrumClass.HAT_OPEN,
        "tom" to DrumClass.TOM,
        "perc" to DrumClass.PERC,
        "tonal" to DrumClass.TONAL,
        "loop" to DrumClass.LOOP,
    )

    /**
     * TT3: the harness no longer idles — a permanent phone-sim corpus,
     * labeled by construction. Every synthetic render goes through the
     * phone treatment (~24 dB/oct at 150 Hz, the rolloff the reference
     * capture measured at a thousandth of its sub) and must classify to
     * its own label under a rolled-off [CaptureProfile] — the kick by
     * the gutless rule, everything else exactly as before.
     */
    @Test
    fun `the phone-sim corpus classifies to its labels under the profile`() {
        fun phoneSim(snip: Snip): Snip {
            var out = snip.samples.copyOf()
            repeat(4) {
                val a = Math.exp(-2.0 * Math.PI * 150.0 / snip.sampleRate).toFloat()
                val res = FloatArray(out.size)
                var yPrev = 0f
                var xPrev = 0f
                for (i in out.indices) {
                    val y = a * (yPrev + out[i] - xPrev)
                    res[i] = y
                    yPrev = y
                    xPrev = out[i]
                }
                out = res
            }
            return Snip(out, snip.channels, snip.sampleRate)
        }
        // A kick like the real ones: sub + knock + click - a pure-sub
        // render can't lose its sub to a highpass, only its level.
        val kick = Snip(
            FloatArray((0.4f * 44_100).toInt()) { i ->
                val t = i.toDouble() / 44_100
                (
                    0.9 * Math.sin(2.0 * Math.PI * 55.0 * t) * Math.exp(-9.0 * t) +
                        0.2 * Math.sin(2.0 * Math.PI * 220.0 * t) * Math.exp(-25.0 * t) +
                        (if (i < 30) 0.08 * (1.0 - i / 30.0) else 0.0)
                    ).toFloat()
            },
            1, 44_100,
        )
        val corpus = listOf(
            DrumClass.KICK to kick,
            DrumClass.SNARE to DrumSynth.snare(),
            DrumClass.CLAP to DrumSynth.clap(),
            DrumClass.HAT_CLOSED to DrumSynth.closedHat(),
            DrumClass.HAT_OPEN to DrumSynth.openHat(),
            DrumClass.TONAL to DrumSynth.tonal(),
        )
        // The profile the corpus lives under, measured from a phone-simmed
        // DRUM mix - the profile's real patient is a beat capture. (A
        // sustained low note held for seconds keeps enough sub through
        // any realistic rolloff to read full-range; that's honest, and
        // it's why the profile is measured, never assumed.)
        val mix = FloatArray(3 * 44_100)
        for ((i, hit) in listOf(kick, DrumSynth.snare(), DrumSynth.closedHat(), kick, DrumSynth.snare()).withIndex()) {
            val start = i * 44_100 / 2
            for (j in hit.samples.indices) {
                if (start + j < mix.size) mix[start + j] += hit.samples[j] * 0.7f
            }
        }
        val profile = CaptureProfile.measure(phoneSim(Snip(mix, 1, 44_100)))
        assertTrue(profile.rolledOff, "the corpus's drum mix reads rolled off: ${profile.subShare}")

        for ((expected, render) in corpus) {
            val got = Classifier.classify(phoneSim(render), profile)
            kotlin.test.assertEquals(
                expected, got.drumClass,
                "phone-simmed $expected classified as ${got.drumClass} (conf ${got.confidence})",
            )
        }
    }

    @Test
    fun `every labeled capture is classified and reported`() {
        val wavs = corpusDir.listFiles { f: File -> f.extension.equals("wav", true) }
            ?.sortedBy { it.name } ?: emptyList()
        if (wavs.isEmpty()) {
            println("calibration corpus is empty ($corpusDir) - see its README to contribute captures")
            return
        }

        data class Row(val file: String, val expected: DrumClass, val got: Classification)

        val rows = mutableListOf<Row>()
        val unlabeled = mutableListOf<String>()
        for (f in wavs) {
            val word = f.name.substringBefore('_').lowercase()
            val expected = labels[word]
            if (expected == null) {
                unlabeled += f.name
                continue
            }
            rows += Row(f.name, expected, Classifier.classify(WavReader.read(f)))
        }
        assertTrue(unlabeled.isEmpty(), "unlabeled files (fix the name prefix): $unlabeled")
        assertTrue(rows.isNotEmpty())

        println("== calibration corpus: ${rows.size} captures ==")
        for (r in rows) {
            val mark = if (r.got.drumClass == r.expected) "ok  " else "MISS"
            print(
                "%s %-28s want %-10s got %-10s conf %.2f".format(
                    mark, r.file, r.expected, r.got.drumClass, r.got.confidence,
                ),
            )
            if (r.got.drumClass != r.expected) {
                val ft = r.got.features
                // The numbers a threshold gets moved by.
                print(
                    "  | centroid %.0fHz low %.2f mid %.2f high %.2f decay %.0fms dur %.2fs".format(
                        ft.centroidHz, ft.lowRatio, ft.midRatio, ft.highRatio, ft.decayMs, ft.durationSeconds,
                    ),
                )
            }
            println()
        }

        val classes = rows.map { it.expected }.distinct().sorted()
        println("-- confusion (rows = truth) --")
        for (want in classes) {
            val ofClass = rows.filter { it.expected == want }
            val cells = ofClass.groupingBy { it.got.drumClass }.eachCount()
                .entries.sortedByDescending { it.value }
                .joinToString("  ") { "${it.key}:${it.value}" }
            println("%-10s %s".format(want, cells))
        }

        val correct = rows.count { it.got.drumClass == it.expected }
        val accuracy = correct.toDouble() / rows.size
        println("accuracy: %d/%d (%.0f%%)".format(correct, rows.size, accuracy * 100))

        // The floor is deliberately low: this gate says "broken on real
        // material", not "imperfect". Tuning happens by reading the report.
        assertTrue(
            accuracy >= 0.6,
            "classifier under 60%% on real captures (%.0f%%) - read the report above".format(accuracy * 100),
        )
    }
}
