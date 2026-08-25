package com.snipsnap.shell

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Features
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File

/**
 * Teach the machine, without taking anything: every chip override in the
 * chop screen is a human correcting the classifier, and this log records
 * exactly that — **feature vectors and labels, never audio**. Rights-clean
 * by construction: a feature vector cannot be played back.
 *
 * The jsonl lands in `reference/calibration/` beside the WAV corpus and
 * the harness replays it through [Classifier.classify]'s feature path —
 * so a correction made on a phone becomes a data point a threshold gets
 * moved by.
 */
object TeachLog {

    const val FILE_NAME = "overrides.jsonl"

    data class Example(
        /** What the human said it is. */
        val label: DrumClass,
        /** What the machine measured. */
        val features: Features,
        /** What the machine wrongly said (context for the report). */
        val machineSaid: DrumClass,
    )

    /** One line per example; append-friendly. */
    fun toJsonl(examples: List<Example>): String =
        examples.joinToString("") { Json.write(toJson(it)) .replace("\n", "").replace("    ", "") + "\n" }

    fun fromJsonl(text: String): List<Example> =
        text.lineSequence().filter { it.isNotBlank() }.map { fromJson(Json.parse(it)) }.toList()

    fun append(file: File, examples: List<Example>) {
        if (examples.isEmpty()) return
        file.parentFile?.mkdirs()
        file.appendText(toJsonl(examples))
    }

    fun read(file: File): List<Example> =
        if (file.isFile) fromJsonl(file.readText()) else emptyList()

    private fun toJson(e: Example): JsonValue = JsonValue.Obj(
        linkedMapOf(
            "label" to JsonValue.Str(e.label.name),
            "machineSaid" to JsonValue.Str(e.machineSaid.name),
            "features" to JsonValue.Obj(
                linkedMapOf(
                    "centroidHz" to JsonValue.Num(e.features.centroidHz.toDouble()),
                    "rolloffHz" to JsonValue.Num(e.features.rolloffHz.toDouble()),
                    "flatness" to JsonValue.Num(e.features.flatness.toDouble()),
                    "zeroCrossingRate" to JsonValue.Num(e.features.zeroCrossingRate.toDouble()),
                    "lowRatio" to JsonValue.Num(e.features.lowRatio.toDouble()),
                    "midRatio" to JsonValue.Num(e.features.midRatio.toDouble()),
                    "highRatio" to JsonValue.Num(e.features.highRatio.toDouble()),
                    "durationSeconds" to JsonValue.Num(e.features.durationSeconds.toDouble()),
                    "decayMs" to JsonValue.Num(e.features.decayMs.toDouble()),
                    "peak" to JsonValue.Num(e.features.peak.toDouble()),
                    "attackBursts" to JsonValue.Num(e.features.attackBursts.toDouble()),
                ),
            ),
        ),
    )

    private fun fromJson(v: JsonValue): Example {
        val obj = (v as JsonValue.Obj).entries
        val f = (obj["features"] as JsonValue.Obj).entries
        fun num(k: String) = (f[k] as JsonValue.Num).value.toFloat()
        fun cls(k: String) = (obj[k] as JsonValue.Str).value.let { name ->
            DrumClass.entries.firstOrNull { it.name == name }
                ?: throw IllegalArgumentException("unknown class '$name' in teach log")
        }
        return Example(
            label = cls("label"),
            machineSaid = cls("machineSaid"),
            features = Features(
                centroidHz = num("centroidHz"),
                rolloffHz = num("rolloffHz"),
                flatness = num("flatness"),
                zeroCrossingRate = num("zeroCrossingRate"),
                lowRatio = num("lowRatio"),
                midRatio = num("midRatio"),
                highRatio = num("highRatio"),
                durationSeconds = num("durationSeconds"),
                decayMs = num("decayMs"),
                peak = num("peak"),
                attackBursts = num("attackBursts").toInt(),
            ),
        )
    }
}
