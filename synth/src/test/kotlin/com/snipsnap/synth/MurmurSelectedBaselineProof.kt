package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Explicit, serial compatibility capture; normal test discovery never renders this bank.
 * The launcher supplies a clean, independently compiled frozen-reference worktree.
 * Expected hashes are captured there, never derived from a refactored candidate.
 */
object MurmurSelectedBaselineProof {
    const val REFERENCE_COMMIT = "1e51f736bfe953906248b8d6aeb834ef362b8b84"
    const val KIND = "MURMUR_PRE_REFACTOR_BASELINE"
    private const val VERSION = 1

    private data class Case(val id: String, val patch: MurmurPatch)

    /** Exactly the old 42+18 set; all() now has three additional aliases and is unsuitable. */
    private fun old60(): List<Case> {
        val oldFactory = MurmurPresets.original() + MurmurPresets.selected()
        check(oldFactory.size == 42) { "old factory bank must contain exactly 42 recipes" }
        val legacy = MurmurVoice.entries.flatMap { voice -> (1..3).map { model ->
            MurmurPatch("legacy-$voice-$model", voice, Murmur.defaults(voice),
                config = Murmur.configurationFor(voice, model))
        } }
        check(legacy.size == 18) { "legacy default/model bank must contain exactly 18 recipes" }
        return (oldFactory + legacy).mapIndexed { index, patch -> Case("$index-${patch.name}", patch) }
            .also { cases ->
                check(cases.size == 60)
                check(cases.map { it.id }.distinct().size == 60)
                check(cases.all { it.patch.config.body == MurmurBody.LEGACY && it.patch.config.model in 1..3 }) {
                    "old60 cannot include a selected model-4 body"
                }
            }
    }

    private fun aliases3(): List<Case> = listOf(
        MurmurKeptSources.FINITE_WIRE, MurmurKeptSources.HELD_WIRE, MurmurKeptSources.STEADY_C2,
    ).map { id -> Case(id, MurmurKeptSources.byId(id).factoryPatch()) }.also { cases ->
        check(cases.size == 3 && cases.map { it.id }.distinct().size == 3)
        check(cases.all { it.patch.config.model == 4 && it.patch.config.body != MurmurBody.LEGACY })
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size in 3..4) { "capture|compare OUTPUT_DIR SOURCE_BINDING_JSON [REFERENCE_BASELINE_JSON]" }
        val mode = args[0]
        require(mode == "capture" || mode == "compare") { "unknown baseline operation $mode" }
        require((mode == "compare") == (args.size == 4)) { "only compare takes an expected baseline" }
        val out = File(args[1])
        require(out.isDirectory) { "launcher must create a new output directory" }
        val bindingFile = File(args[2])
        val binding = Json.parse(bindingFile.readText()).obj()
        check(binding.getValue("referenceCommit").str() == REFERENCE_COMMIT)
        val role = binding.getValue("role").str()
        val sourceCommit = binding.getValue("sourceCommit").str()
        if (mode == "capture") {
            check(role == "frozen-reference" && sourceCommit == REFERENCE_COMMIT) {
                "refusing to capture expected hashes from a candidate or a different source commit"
            }
        } else check(role == "candidate-comparison")
        val expected = if (mode == "compare") Json.parse(File(args[3]).readText()) else null
        expected?.let { validateEnvelope(it, requireReference = true) }

        val groups = linkedMapOf<String, JsonValue>()
        val old = old60()
        val selected = aliases3()
        // Check every current recipe before synthesis. A changed bank must not redefine a case.
        if (expected != null) {
            verifyRecipeRoster(expected, "old60", old)
            verifyRecipeRoster(expected, "m1Aliases3", selected)
        }
        for ((name, cases) in listOf("old60" to old, "m1Aliases3" to selected)) {
            val records = linkedMapOf<String, JsonValue>()
            for ((index, case) in cases.withIndex()) {
                records[case.id] = renderCase(case.patch)
                groups[name] = JsonValue.Obj(records.toMap())
                writeReport(out, "observed.partial.json", sourceCommit, bindingFile, groups, complete = false)
                println("$name ${index + 1}/${cases.size}: ${case.id}")
            }
        }
        // Public render historically cleans these inputs; Patch constructors rightly refuse some.
        // Keep the requests and their results separate from the old60 and three alias counts.
        groups["publicVelocitySemantics"] = publicVelocitySemantics()
        val observed = report(sourceCommit, bindingFile, groups, complete = true)
        val target = if (mode == "capture") "baseline.json" else "candidate-observed.json"
        File(out, target).writeText(Json.write(observed))
        if (expected != null) {
            assertEquivalent(expected, observed)
            File(out, "comparison.json").writeText(Json.write(JsonValue.Obj(linkedMapOf(
                "kind" to JsonValue.Str("MURMUR_BASELINE_COMPARISON"),
                "status" to JsonValue.Str("passed"),
                "referenceCommit" to JsonValue.Str(REFERENCE_COMMIT),
                "candidateCommit" to JsonValue.Str(sourceCommit),
                "referenceBaselineSha256" to JsonValue.Str(hash(File(args[3]).readBytes())),
                "candidateObservedSha256" to JsonValue.Str(hash(File(out, target).readBytes())),
                "old60Count" to JsonValue.Num(60.0),
                "m1Aliases3Count" to JsonValue.Num(3.0),
            ))))
        }
        println("$mode completed; old60=60; m1Aliases3=3; publicVelocitySemantics=6")
    }

    private fun renderCase(patch: MurmurPatch): JsonValue.Obj {
        val text = patch.toJsonText()
        val native = Murmur.probe(patch.voice, patch.macros, patch.midi, patch.velocity, patch.config)
        val house = patch.render()
        return JsonValue.Obj(linkedMapOf(
            "recipeText" to JsonValue.Str(text),
            "recipeSha256" to JsonValue.Str(hash(text.toByteArray(Charsets.UTF_8))),
            "rawCurrent" to audio(native.samples, 1, Dsp.RATE),
            "rawPredecessor" to audio(native.previousCycle, 1, Dsp.RATE),
            "nativeRootHz" to JsonValue.Num(native.rootHz),
            "nativeSeamError" to JsonValue.Num(native.seamError),
            "house" to audio(house.samples, house.channels, house.sampleRate),
        ))
    }

    private fun publicVelocitySemantics(): JsonValue.Obj {
        val source = MurmurPatch("public-velocity-semantics", MurmurVoice.PLUCK,
            Murmur.defaults(MurmurVoice.PLUCK), config = Murmur.configurationFor(MurmurVoice.PLUCK, 2))
        val requests = listOf("zero" to 0f, "negative" to -1f, "above-one" to 2f,
            "nan" to Float.NaN, "positive-infinity" to Float.POSITIVE_INFINITY,
            "negative-infinity" to Float.NEGATIVE_INFINITY)
        return JsonValue.Obj(requests.associateTo(linkedMapOf()) { (id, velocity) ->
            val native = Murmur.probe(source.voice, source.macros, source.midi, velocity, source.config)
            val house = Murmur.render(source.voice, source.macros, source.midi, velocity, source.config)
            id to JsonValue.Obj(linkedMapOf(
                "basePatchText" to JsonValue.Str(source.toJsonText()),
                "requestedVelocityFloatBitsHex" to JsonValue.Str(Integer.toHexString(velocity.toRawBits())),
                "rawCurrent" to audio(native.samples, 1, Dsp.RATE),
                "rawPredecessor" to audio(native.previousCycle, 1, Dsp.RATE),
                "house" to audio(house.samples, house.channels, house.sampleRate),
            ))
        })
    }

    private fun audio(samples: FloatArray, channels: Int, rate: Int): JsonValue.Obj {
        check(channels == 1 && rate == WavWriter.MPC_SAMPLE_RATE) { "unexpected MURMUR output format" }
        check(samples.all { it.isFinite() }) { "nonfinite native/house sample in compatibility capture" }
        val floats = ByteBuffer.allocate(samples.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(floats::putFloat)
        val wav = ByteArrayOutputStream().also {
            WavWriter.write(it, Snip(samples, channels, rate), WavWriter.BitDepth.PCM_24)
        }.toByteArray()
        return JsonValue.Obj(linkedMapOf(
            "frames" to JsonValue.Num((samples.size / channels).toDouble()),
            "channels" to JsonValue.Num(channels.toDouble()),
            "sampleRate" to JsonValue.Num(rate.toDouble()),
            "floatLittleEndianSha256" to JsonValue.Str(hash(floats.array())),
            "pcm24WavSha256" to JsonValue.Str(hash(wav)),
            "pcm24WavBytes" to JsonValue.Num(wav.size.toDouble()),
        ))
    }

    /** Reusable focused comparison assertion; never manufactures or updates expected hashes. */
    fun assertEquivalent(expected: JsonValue, observed: JsonValue) {
        validateEnvelope(expected, requireReference = true)
        validateEnvelope(observed, requireReference = false)
        val left = expected.obj().getValue("groups").obj()
        val right = observed.obj().getValue("groups").obj()
        check(left.keys == right.keys) { "baseline group identity differs" }
        for (group in left.keys) {
            val expectedCases = left.getValue(group).obj()
            val actualCases = right.getValue(group).obj()
            check(expectedCases.keys.toList() == actualCases.keys.toList()) { "$group case identity/order differs" }
            for ((id, record) in expectedCases) {
                val actual = actualCases.getValue(id)
                check(record == actual) { "$group $id changed recipe, raw/predecessor, house Float or PCM24 identity" }
            }
        }
    }

    private fun verifyRecipeRoster(expected: JsonValue, group: String, cases: List<Case>) {
        val records = expected.obj().getValue("groups").obj().getValue(group).obj()
        check(records.keys.toList() == cases.map { it.id }) { "$group recipe identity/order changed before rendering" }
        for (case in cases) check(records.getValue(case.id).obj().getValue("recipeText").str() == case.patch.toJsonText()) {
            "$group ${case.id} recipe changed before rendering"
        }
    }

    private fun validateEnvelope(value: JsonValue, requireReference: Boolean) {
        val obj = value.obj()
        check(obj.getValue("kind").str() == KIND && obj.getValue("version").int() == VERSION)
        check(obj.getValue("complete").bool()) { "partial capture cannot be a baseline" }
        check(obj.getValue("referenceCommit").str() == REFERENCE_COMMIT)
        if (requireReference) check(obj.getValue("sourceCommit").str() == REFERENCE_COMMIT) {
            "a refactored candidate cannot be its own expected baseline"
        }
        val groups = obj.getValue("groups").obj()
        check(groups.keys == setOf("old60", "m1Aliases3", "publicVelocitySemantics"))
        check(groups.getValue("old60").obj().size == 60)
        check(groups.getValue("m1Aliases3").obj().size == 3)
        check(groups.getValue("publicVelocitySemantics").obj().size == 6)
    }

    private fun report(sourceCommit: String, binding: File, groups: Map<String, JsonValue>, complete: Boolean) =
        JsonValue.Obj(linkedMapOf(
            "kind" to JsonValue.Str(KIND),
            "version" to JsonValue.Num(VERSION.toDouble()),
            "referenceCommit" to JsonValue.Str(REFERENCE_COMMIT),
            "sourceCommit" to JsonValue.Str(sourceCommit),
            "sourceBindingSha256" to JsonValue.Str(hash(binding.readBytes())),
            "complete" to JsonValue.Bool(complete),
            "groups" to JsonValue.Obj(groups.toMap()),
        ))

    private fun writeReport(out: File, name: String, sourceCommit: String, binding: File,
                            groups: Map<String, JsonValue>, complete: Boolean) {
        File(out, name).writeText(Json.write(report(sourceCommit, binding, groups, complete)))
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
