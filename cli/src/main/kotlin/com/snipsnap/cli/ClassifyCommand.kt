package com.snipsnap.cli

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.WavReader
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap classify <wav...>` — what the classifier hears, with the
 * features it heard it in.
 *
 * This is the calibration tool docs/CONCEPT.md asks for: the classifier's
 * thresholds were tuned on synthetic material, and the way to tune them on
 * real captures is to run real captures through it and look at the numbers
 * next to the verdicts. A `?` before the class means the confidence is
 * below the same threshold the app uses to say NOT SURE.
 */
object ClassifyCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = emptySet(), boolean = emptySet())
        if (opts.positional.isEmpty()) {
            throw CliError("classify wants files: snipsnap classify <wav...>")
        }

        out.println(
            "%-28s %-12s %5s  %6s %8s %6s %5s %5s %5s".format(
                "file", "class", "conf", "dur", "centroid", "decay", "low", "mid", "high",
            ),
        )
        for (path in opts.positional) {
            val file = File(path)
            if (!file.isFile) throw CliError("no such file: $path")
            val snip = WavReader.read(file)
            val c = Classifier.classify(snip)
            val f = c.features
            val sure = if (c.confidence < ChopCommand.SURE_CONFIDENCE) "?" else " "
            out.println(
                "%-28s %s%-11s %5.2f  %5.2fs %6.0fHz %4.0fms %5.2f %5.2f %5.2f".format(java.util.Locale.ROOT, 
                    file.name.take(28), sure, c.drumClass, c.confidence,
                    f.durationSeconds, f.centroidHz, f.decayMs,
                    f.lowRatio, f.midRatio, f.highRatio,
                ),
            )
        }
        return 0
    }
}
