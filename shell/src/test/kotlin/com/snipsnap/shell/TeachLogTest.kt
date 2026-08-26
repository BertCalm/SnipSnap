package com.snipsnap.shell

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.FeatureExtractor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TeachLogTest {

    @Test
    fun `examples round-trip jsonl and replay through the feature path`() {
        val kick = FeatureExtractor.extract(DrumSynth.kick())
        val hat = FeatureExtractor.extract(DrumSynth.closedHat())
        val examples = listOf(
            TeachLog.Example(DrumClass.KICK, kick, machineSaid = DrumClass.TOM),
            TeachLog.Example(DrumClass.HAT_CLOSED, hat, machineSaid = DrumClass.HAT_OPEN),
        )

        val jsonl = TeachLog.toJsonl(examples)
        assertEquals(2, jsonl.trim().lines().size, "one line per example")
        val back = TeachLog.fromJsonl(jsonl)
        assertEquals(examples, back, "features and labels survive exactly")

        // The whole point: a logged feature vector re-classifies without audio.
        assertEquals(
            Classifier.classify(DrumSynth.kick()).drumClass,
            Classifier.classify(back[0].features).drumClass,
        )
    }

    @Test
    fun `a torn last line from a killed append does not lose the good ones`() {
        // Two whole records, then a line cut off mid-write - what a process
        // death leaves behind.
        val kick = FeatureExtractor.extract(DrumSynth.kick())
        val whole = TeachLog.toJsonl(
            listOf(
                TeachLog.Example(DrumClass.KICK, kick, machineSaid = DrumClass.TOM),
                TeachLog.Example(DrumClass.KICK, kick, machineSaid = DrumClass.TOM),
            ),
        )
        val torn = whole + "{\"label\":\"KICK\",\"mach"
        assertEquals(2, TeachLog.fromJsonl(torn).size, "the two complete records survive")
    }

    @Test
    fun `append accumulates and read of nothing is empty`() {
        val temp = java.nio.file.Files.createTempDirectory("teach").toFile()
        val file = File(temp, TeachLog.FILE_NAME)
        assertEquals(emptyList(), TeachLog.read(file))

        val ex = TeachLog.Example(
            DrumClass.SNARE, FeatureExtractor.extract(DrumSynth.snare()), DrumClass.CLAP,
        )
        TeachLog.append(file, listOf(ex))
        TeachLog.append(file, listOf(ex))
        TeachLog.append(file, emptyList()) // no-op
        assertEquals(2, TeachLog.read(file).size)
        temp.deleteRecursively()
    }

    @Test
    fun `chop overrides harvest as labeled examples`() {
        val rate = 44_100
        val kick = DrumSynth.kick()
        val padded = FloatArray(rate + kick.samples.size)
        kick.samples.copyInto(padded, rate / 2)
        val model = ChopReviewModel.chop(
            com.snipsnap.audio.Snip(padded, 1, rate),
            ChopReviewModel.ChopMode.Grid(1),
        )
        assertEquals(emptyList(), model.labeledOverrides(), "no overrides, no examples")

        model.cycleLabel(0)
        val harvest = model.labeledOverrides()
        assertEquals(1, harvest.size)
        assertEquals(model.rows[0].effectiveClass, harvest[0].label)
        assertEquals(model.rows[0].classification.drumClass, harvest[0].machineSaid)
    }

    /** The harness half: logged corrections in the calibration folder get scored. */
    @Test
    fun `the calibration overrides log is scored when present`() {
        val file = File("../reference/calibration/${TeachLog.FILE_NAME}")
        val examples = TeachLog.read(file)
        if (examples.isEmpty()) {
            println("no ${TeachLog.FILE_NAME} in reference/calibration/ - nothing logged yet")
            return
        }
        var agree = 0
        for (e in examples) {
            val got = Classifier.classify(e.features).drumClass
            if (got == e.label) agree++
            else println("MISS ${e.label} (machine said ${e.machineSaid}, rules now say $got) ${e.features}")
        }
        println("teach log: $agree/${examples.size} corrections now agreed with")
        assertTrue(examples.isNotEmpty())
    }
}
