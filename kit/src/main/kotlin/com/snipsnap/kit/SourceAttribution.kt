package com.snipsnap.kit

import java.io.File

/** Source notices travel with rendered samples even when their program format has no provenance fields. */
object SourceAttribution {
    private val noticeKeys = setOf("attribution", "license", "licenseUrl", "sourceUrl", "modifications")

    /** Per-kit names keep several tracks sharing a project sample folder from replacing each other's notices. */
    fun fileName(kit: Kit): String = "${Names.sanitizeStem(kit.name)}-source-attribution.txt"

    /** Preserve the supplied source metadata verbatim, in a stable pad/key order. Uncredited kits add no output. */
    fun render(kit: Kit): String? {
        val credited = kit.pads.sortedBy { it.slot }.filter { pad ->
            pad.source.any { (key, value) -> key in noticeKeys && value.isNotBlank() }
        }
        if (credited.isEmpty()) return null
        return buildString {
            appendLine("SOURCE ATTRIBUTION")
            appendLine(kit.name)
            appendLine()
            for (pad in credited) {
                val bank = 'A' + (pad.slot - 1) / 16
                val number = ((pad.slot - 1) % 16 + 1).toString().padStart(2, '0')
                appendLine("$bank$number — ${pad.displayName}")
                for ((key, value) in pad.source.toSortedMap()) appendLine("$key: $value")
                appendLine()
            }
        }
    }

    /** A repeat export updates only this kit's notice; other kits in the same directory stay intact. */
    fun writeTo(kit: Kit, samplesDir: File): File? {
        val file = File(samplesDir, fileName(kit))
        val text = render(kit)
        if (text == null) {
            if (file.isFile && !file.delete()) throw java.io.IOException("could not remove stale source attribution $file")
            return null
        }
        samplesDir.mkdirs()
        file.writeText(text, Charsets.UTF_8)
        return file
    }
}
