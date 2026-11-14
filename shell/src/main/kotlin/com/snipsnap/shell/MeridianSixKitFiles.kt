package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.synth.Meridian
import com.snipsnap.synth.MeridianSixKits
import com.snipsnap.synth.MeridianSixVelocityRecipe
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Dedicated source binding check; the generic starter path does not gain a global disk-admission hook. */
object MeridianSixKitFiles {
    fun validate(directory: File, kit: Kit = KitStore.load(directory)): List<MeridianSixVelocityRecipe> {
        require(!Files.isSymbolicLink(directory.toPath()) && directory.isDirectory)
        require(kit.pads.map { it.slot }.toSet() == (1..16).toSet() && kit.pads.size == 16) { "MERIDIAN six-voice needs sixteen distinct declared pads" }
        require(kit.key == null) { "MERIDIAN six-voice does not invent a major/minor scale" }
        val banks = ArrayList<MeridianSixVelocityRecipe>()
        val sampleNames = linkedSetOf<String>()
        for (pad in kit.pads.sortedBy { it.slot }) {
            val encoded = requireNotNull(pad.source[MeridianSixVelocityRecipe.SOURCE_KEY]) { "MERIDIAN six-voice has no independent source layers" }
            val bank = MeridianSixVelocityRecipe.fromJsonText(encoded)
            require(bank.slot == pad.slot && pad.recipe == bank.layers.last().recipe.toJsonValue()) { "MERIDIAN six-voice main source differs" }
            require(pad.oneShot && !pad.loop && pad.chain == null && pad.muteGroup == 0 && pad.tuneCoarse == 0 && pad.tuneFine == 0) {
                "MERIDIAN six-voice retains finite, unretuned and unchoked pad delivery"
            }
            val hard = bank.hardPatch
            val entry = MeridianSixKits.entries()[pad.slot - 1]
            require(!Meridian.isLoop(hard.macros, hard.model)) { "MERIDIAN six-voice bank contains finite sources only" }
            require(pad.drumClass == Meridian.drumClassFor(hard.voice, hard.macros, hard.model)) { "MERIDIAN six-voice source filing changed" }
            val expectedSource = mapOf("engine" to hard.engine, "voice" to hard.voice.name,
                "factoryPreset" to bank.factoryPatch.name, "model" to hard.model.toString(),
                "seedContext" to hard.seedContext.toString(), "rootMidi" to entry.midi.toString(),
                "registerRole" to entry.registerRole,
                "finiteRole" to if (entry.registerRole == "FACTORY") "FACTORY_FINITE" else "REGISTER_FINITE", "nativeVelocityPolicy" to "ABSOLUTE_030_065_100",
                "playbackVelocityPolicy" to "KIT_PLAYER_GAIN_ONCE",
                "playback" to "FINITE_ONE_SHOT", "capturePolicy" to "NATIVE_FINITE_ENDING",
                "bankId" to MeridianSixKits.STARTER_ID, "bankVersion" to bank.bankVersion.toString(),
                "terminalFadeMs" to (bank.layers.last().recipe.terminalFadeMs?.toString() ?: "0"))
            require(expectedSource.all { (key, value) -> pad.source[key] == value }) { "MERIDIAN six-voice pad provenance changed" }
            require(pad.velocityLayers.size == 3 && pad.sampleFile == pad.velocityLayers.last().sampleFile)
            for ((index, layer) in pad.velocityLayers.withIndex()) {
                val recipe = bank.layers[index]
                require(layer.velStart == recipe.velocityStart && layer.velEnd == recipe.velocityEnd &&
                    layer.displayName == recipe.name) { "MERIDIAN six-voice velocity mapping changed" }
                require(layer.sampleFile == File(layer.sampleFile).name && '/' !in layer.sampleFile && '\\' !in layer.sampleFile &&
                    layer.sampleFile.endsWith(".wav") && sampleNames.add(layer.sampleFile)) { "Unsafe or duplicate MERIDIAN six-voice sample" }
                val wav = File(directory, layer.sampleFile)
                require(!Files.isSymbolicLink(wav.toPath()) && wav.isFile)
                require(hash(wav) == recipe.wavSha256) { "MERIDIAN six-voice sample differs from its independent source binding" }
                val snip = WavReader.read(wav)
                require(snip.channels == 1 && snip.sampleRate == WavWriter.MPC_SAMPLE_RATE && snip.frameCount > 0 && snip.samples.all { it.isFinite() })
                require(WavReader.readSmplStrict(wav) == null) { "MERIDIAN six-voice kit captures have no sampler-loop sheet" }
            }
            banks += bank
        }
        require(sampleNames.size == 48)
        val actualWavs = directory.listFiles()!!.filter { it.isFile && it.extension.equals("wav", ignoreCase = true) }.map { it.name }.toSet()
        require(actualWavs == sampleNames) { "Unexpected or missing MERIDIAN six-voice sample files" }
        return banks
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(65536)
            while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
