package com.snipsnap.kit

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Restores edit history only when an adjacent sidecar describes the imported program's samples. */
internal object ImportSidecar {
    fun restore(imported: Kit, text: String?, samplesDir: File, label: String): Kit {
        if (text == null) return imported
        val saved = try {
            KitStore.fromJsonText(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("'$label' is not a readable kit sidecar: ${e.message}", e)
        }
        require(saved.pads.map { it.slot }.toSet() == imported.pads.map { it.slot }.toSet()) {
            "'$label' pad slots disagree with its program - edit history was not imported"
        }
        for (pad in imported.pads) {
            val history = requireNotNull(saved.pad(pad.slot))
            require(history.chain == null) {
                "'$label' contains chain history this importer cannot match - edit history was not imported"
            }
            require(fileKey(history.sampleFile) == fileKey(pad.sampleFile) &&
                history.velocityLayers.map { Triple(fileKey(it.sampleFile), it.velStart, it.velEnd) } ==
                pad.velocityLayers.map { Triple(fileKey(it.sampleFile), it.velStart, it.velEnd) }) {
                "'$label' sample or velocity layers disagree at pad ${pad.slot} - edit history was not imported"
            }
            // A known checksum makes external WAV edits distinguishable from the
            // sample whose recipe was saved. Imports copy WAV bytes unchanged.
            history.source["rawSha256"]?.let { expected ->
                require(expected.matches(Regex("[0-9a-fA-F]{64}")) &&
                    expected.equals(sha256(SafePath.child(samplesDir, pad.sampleFile)), ignoreCase = true)) {
                    "'$label' sample checksum disagrees at pad ${pad.slot} - edit history was not imported"
                }
            }
        }
        // Program controls remain authoritative: an external level, pan, tune,
        // envelope, trigger or mute-group edit must not be undone by old history.
        return imported.copy(
            pads = imported.pads.map { pad ->
                val history = requireNotNull(saved.pad(pad.slot))
                pad.copy(
                    displayName = history.displayName,
                    drumClass = history.drumClass,
                    colorHex = pad.colorHex ?: history.colorHex,
                    recipe = history.recipe,
                    velocityLayers = pad.velocityLayers.mapIndexed { index, layer ->
                        layer.copy(displayName = history.velocityLayers[index].displayName)
                    },
                    source = history.source + pad.source,
                )
            },
            key = saved.key,
            tempoBpm = saved.tempoBpm,
        )
    }

    private fun fileKey(name: String) = name.lowercase(Locale.ROOT)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
