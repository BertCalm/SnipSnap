package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.SafePath
import com.snipsnap.synth.ConfluenceAssemblyRecipe
import com.snipsnap.synth.ConfluenceBankVoice
import com.snipsnap.synth.ConfluenceRole
import com.snipsnap.synth.ConfluenceVoiceBank
import java.io.File
import java.security.MessageDigest

/** Rebuild a saved wordless arrangement into a new pad; current audio and treatments stay put. */
object ConfluenceWorkshop {
    data class Controls(
        val foundationVoiceId: String,
        val divergence: Float,
        val space: Float,
        val answerGapSeconds: Float,
        val foundationGain: Float,
        val airyGain: Float,
        val answerGain: Float,
    ) {
        init {
            require(divergence.isFinite() && divergence in 0f..1f) { "Individuality must be between 0 and 1." }
            require(space.isFinite() && space in 0f..1f) { "Room must be between 0 and 1." }
            require(answerGapSeconds.isFinite() && answerGapSeconds in .05f..2f) { "Answer gap must be 0.05–2 seconds." }
            require(listOf(foundationGain, airyGain, answerGain).all { it.isFinite() && it in .05f..2f }) {
                "Voice levels must be between 0.05 and 2."
            }
        }
    }

    /** A cheap entrypoint check. [open] performs the full source/recipe validation off the UI thread. */
    fun isGathering(pad: KitPad): Boolean = pad.source["engine"] == "CONFLUENCE_VOICE_BANK" &&
        !pad.source["assemblyRecipe"].isNullOrBlank() &&
        (pad.source["roles"] == null || pad.source.getValue("roles").split(',').toSet() == ConfluenceRole.entries.map { it.name }.toSet())

    fun firstEmptySlot(model: KitBuilderModel): Int? = (1..128).firstOrNull { model.pad(it) == null }

    /** The adjacent portable bank wins. A missing factory pack can resolve only against exact bundled identities. */
    fun open(kitDir: File, pad: KitPad): Editor {
        require(isGathering(pad)) { "Choose a complete Confluence gathering to edit." }
        val root = kitDir.canonicalFile
        val local = bankDirectory(root)
        val bank = if (local.exists()) {
            require(local.isDirectory) { "The gathering's voice bank is not a folder." }
            ConfluenceVoiceBank.fromDirectory(local)
        } else ConfluenceVoiceBank.bundled()
        require(pad.source["bankId"] == bank.id && pad.source["bankVersion"] == bank.version.toString()) {
            "This voice bank does not match the saved gathering. Restore its source bank."
        }
        val recipeText = requireNotNull(pad.source["assemblyRecipe"])
        require(recipeText.length <= 100_000) { "The saved gathering recipe is too large." }
        val recipe = try {
            ConfluenceAssemblyRecipe.fromJsonText(recipeText)
        } catch (e: RuntimeException) {
            throw IllegalArgumentException("The saved gathering recipe cannot be read. Restore the original gathering.", e)
        }
        // Resolve exact eligible native regions before accepting the controls, without rendering audio.
        for (role in recipe.roles) {
            require(bank.voices.any { role.role in it.roles && role.region == it.region }) {
                "The saved gathering refers to an unavailable voice region. Restore its source bank."
            }
        }
        pad.source["sourceHashes"]?.let { hashes ->
            require(hashes.split(',').toSet() == recipe.roles.map { it.region.assetHash }.toSet()) {
                "The saved gathering's source references disagree."
            }
        }
        return Editor(root, pad.copy(source = pad.source.toMap()), fileHash(SafePath.child(root, pad.sampleFile)), bank, recipe)
    }

    class Editor internal constructor(
        private val kitDirectory: File,
        private val originalPad: KitPad,
        private val originalAudioHash: String,
        private val bank: ConfluenceVoiceBank,
        private val originalRecipe: ConfluenceAssemblyRecipe,
    ) {
        val foundationVoices: List<ConfluenceBankVoice> = bank.voicesForRole(ConfluenceRole.FOUNDATION)
        val seconds: Float get() = originalRecipe.seconds
        val performers: Int get() = originalRecipe.roles.sumOf { it.performerIds.size }
        val initialControls: Controls = run {
            val low = originalRecipe.roles.single { it.role == ConfluenceRole.FOUNDATION }
            Controls(bank.voices.single { it.region == low.region }.id, originalRecipe.divergence,
                originalRecipe.space, originalRecipe.answerDelaySeconds, low.gain,
                originalRecipe.roles.single { it.role == ConfluenceRole.AIRY }.gain,
                originalRecipe.roles.single { it.role == ConfluenceRole.ANSWER }.gain)
        }

        /** CPU work: callers render before acquiring the kit-write mutex. */
        fun prepare(controls: Controls): PreparedVariation {
            val low = foundationVoices.firstOrNull { it.id == controls.foundationVoiceId }
            requireNotNull(low) { "Choose an available low voice." }
            val recipe = originalRecipe.copy(
                name = "GATHERING VARIATION", space = controls.space, divergence = controls.divergence,
                answerDelaySeconds = controls.answerGapSeconds,
                roles = originalRecipe.roles.map { role -> when (role.role) {
                    ConfluenceRole.FOUNDATION -> role.copy(region = low.region, gain = controls.foundationGain)
                    ConfluenceRole.AIRY -> role.copy(gain = controls.airyGain)
                    ConfluenceRole.ANSWER -> role.copy(gain = controls.answerGain)
                } },
            )
            val pad = bank.arrangedPad(recipe).let { rendered -> rendered.copy(source = rendered.source + mapOf(
                "editingParentSlot" to originalPad.slot.toString(), "editingParentSampleSha256" to originalAudioHash,
            )) }
            return PreparedVariation(this, controls, pad)
        }

        /** Recheck against a freshly opened model under the caller's kit-write mutex, then assign; caller saves. */
        fun makeVariation(fresh: KitBuilderModel, prepared: PreparedVariation, targetSlot: Int): KitPad {
            require(prepared.editor === this) { "Preview this gathering again before making a variation." }
            require(fresh.kitDir.canonicalFile == kitDirectory) { "The kit changed. Reopen the gathering." }
            val current = fresh.pad(originalPad.slot)
            require(current != null && current.sampleFile == originalPad.sampleFile && current.source == originalPad.source &&
                fileHash(SafePath.child(kitDirectory, originalPad.sampleFile)) == originalAudioHash) {
                "The source pad changed. Reopen the gathering before making a variation."
            }
            require(targetSlot in 1..128 && fresh.pad(targetSlot) == null) {
                "That pad is now occupied. Choose another empty pad."
            }
            val local = bankDirectory(kitDirectory)
            if (local.exists()) {
                require(local.isDirectory && ConfluenceVoiceBank.fromDirectory(local).manifestText == bank.manifestText) {
                    "The gathering's voice bank changed. Reopen the gathering."
                }
            } else bank.writePortableBank(local)
            val pad = prepared.arrangedPad
            fresh.assign(targetSlot, pad.snip, pad.drumClass, pad.displayName, pad.source)
            return fresh.update(targetSlot) { it.copy(level = prepared.previewLevel, oneShot = pad.oneShot) }
        }
    }

    class PreparedVariation internal constructor(
        internal val editor: Editor,
        val controls: Controls,
        private val rendered: ArrangedPad,
    ) {
        // Mirror assign's fresh KitPad default through its owning model, rather than the edited pad's mix/FX.
        val previewLevel: Float = rendered.level ?: KitPad(1, "preview.wav").level

        /** The preview and committed render share immutable values; consumers cannot mutate the cached PCM. */
        val arrangedPad: ArrangedPad get() = rendered.copy(
            snip = Snip(rendered.snip.samples.copyOf(), rendered.snip.channels, rendered.snip.sampleRate),
            source = rendered.source.toMap(),
        )
    }

    private fun bankDirectory(root: File): File = File(root, StarterKits.CONFLUENCE_BANK_DIR).also {
        require(it.canonicalFile.parentFile == root) { "The gathering's voice bank must be inside this kit." }
    }

    private fun fileHash(file: File): String {
        require(file.isFile) { "The original pad audio is missing. Restore it before making a variation." }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
