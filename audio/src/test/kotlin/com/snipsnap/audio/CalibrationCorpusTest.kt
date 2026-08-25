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
