package com.snipsnap.synth

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scale
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.xpm.WavInfo
import java.io.File

/** Export dry native audio, complete saved performances and both MPC program generations. */
object AccordKitGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "supply only the output root" }
        val root = File(args.firstOrNull() ?: "../testkit").canonicalFile
        root.mkdirs()
        val staging = File(root, ".accord-kit-work")
        require(!staging.exists() || staging.deleteRecursively())
        val destination = File(root, AccordKits.NAME)
        try {
            val arranged = SynthKits.accord()
            require(arranged.size == 16 && arranged.all { it.oneShot && it.softVariants.isEmpty() && it.takes.isEmpty() })
            val kit = KitAssembler.assembleArranged(AccordKits.NAME, arranged, staging,
                key = KeySpec(rootSemitone = 0, scale = Scale.MINOR_PENTATONIC))
            val stagingFindings = Preflight.check(kit, staging)
            require(!stagingFindings.blocked()) { "Accord kit staging: $stagingFindings" }
            val exported = Mpc3Exporter.exportTrack(kit, staging, destination, overwrite = true, mpc2Twin = true)
            require(exported.samples.size == 16 && exported.program.isFile)
            val data = exported.samples.first().parentFile
            KitStore.save(kit, data)
            val restored = KitStore.load(data)
            require(restored.pads.map { it.recipe } == kit.pads.map { it.recipe })
            val findings = Preflight.check(restored, data)
            require(!findings.blocked()) { "Accord kit export: $findings" }
            val recipes = AccordKits.patches()
            val pads = kit.pads.zip(recipes).map { (pad, patch) ->
                val sample = File(data, pad.sampleFile)
                val info = WavInfo.read(sample)
                require(info.sampleRate == 44_100 && info.channels == 1 && info.bitsPerSample == 24)
                require(AccordAuditionFiles.hash(sample) == AccordAuditionFiles.hash(File(staging, pad.sampleFile)))
                val recipe = PadRecipe(patch)
                val recipeFile = File(destination, "recipes/A%02d.json".format(java.util.Locale.ROOT, pad.slot))
                recipeFile.parentFile.mkdirs()
                recipeFile.writeText(recipe.toJsonText() + "\n")
                require(PadRecipe.fromJsonText(recipeFile.readText()) == recipe)
                linkedMapOf("slot" to pad.slot, "name" to patch.name, "midi" to patch.midi,
                    "voice" to patch.voice.name, "profile" to patch.acousticProfile.name,
                    "velocity" to patch.velocity, "seed" to patch.seed.toString(),
                    "initializer" to patch.initializer.name, "model" to patch.model,
                    "class" to pad.drumClass.name, "oneShot" to pad.oneShot,
                    "sample" to sample.relativeTo(destination).invariantSeparatorsPath,
                    "sampleSha256" to AccordAuditionFiles.hash(sample), "frames" to info.frameCount,
                    "seconds" to info.frameCount.toDouble() / info.sampleRate,
                    "capturedReleaseSeconds" to (info.frameCount.toDouble() / info.sampleRate - patch.sourceSeconds),
                    "recipe" to recipeFile.relativeTo(destination).invariantSeparatorsPath)
            }
            AccordAuditionFiles.writeJson(File(destination, "manifest.json"), linkedMapOf(
                "engine" to "ACCORD", "model" to Accord.MODEL_VERSION, "name" to AccordKits.NAME,
                "audio" to "44.1 kHz mono PCM24", "playback" to "FINITE_ONE_SHOT",
                "calibrationVersion" to AccordCalibration.VERSION,
                "levelPolicy" to "Exact engine output; no additional export gain matching. The engine's constant performance gain is shared across touch velocities.",
                "scope" to "Sixteen dry finite performances. LOOP is duration filing; no seamless or held playback is claimed.",
                "stagingPreflightPassed" to true, "exportPreflightPassed" to true, "pads" to pads))
            File(destination, "README.txt").writeText("""
                SNIPSNAP ACCORD KIT

                Load ${AccordKits.NAME}.xtd on MPC 3. The MPC 2 .xpm twin is
                inside its _[TrackData] folder beside the shared PCM24 WAVs.
                Keep that folder with the MPC 3 track. kit.json retains editable recipes.

                A01-A08: BRUSH in C minor pentatonic from C3.
                A09-A13: WOOD and TINE Reference, Soft Attack, Bright Edge, Long Ring.
                A14-A16: soft BRUSH, hard BRUSH and soft WOOD at their native levels.

                Every pad plays once. LOOP is a duration classification only;
                these are finite performances, without seamless or sustained HOLD.
                No effects, choking or additional export gain matching. The engine uses one
                constant calibration gain per saved performance, shared across its velocities;
                there is no per-contact normalization or fast automatic gain control.
                Capture tails follow actual note, touch and body decay; file lengths vary.
                Native acceptance, phone performance and MPC hardware listening remain separate checks.
            """.trimIndent() + "\n")
            val archive = File(root, "SnipSnap-Accord-Kit.zip")
            AccordAuditionFiles.archive(destination, archive)
            println("Accord kit: ${exported.program.absolutePath}; ${archive.absolutePath}")
        } finally {
            staging.deleteRecursively()
        }
    }
}
