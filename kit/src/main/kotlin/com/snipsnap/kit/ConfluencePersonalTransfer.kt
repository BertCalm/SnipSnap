package com.snipsnap.kit

import java.io.File
import java.io.IOException
import java.nio.file.Files

/** A local dependency copy has the same closure caps and file checks as a native backup. */
internal object ConfluencePersonalTransfer {
    private data class Voice(val id: String, val files: List<Pair<String, File>>)

    fun requireTogether(sources: List<Pair<Kit, File>>) { plan(sources) }

    private fun plan(sources: List<Pair<Kit, File>>): List<Voice> {
        val voices = LinkedHashMap<String, Voice>()
        for ((kit, root) in sources) {
            val files = ConfluencePersonalFiles.files(kit, root)
            for ((id, grouped) in files.groupBy { it.first.split('/')[1] }) {
                add(voices, Voice(id, grouped))
            }
        }
        requireBudget(voices.values)
        return voices.values.toList()
    }

    private fun add(voices: MutableMap<String, Voice>, voice: Voice) {
        val existing = voices[voice.id]
        if (existing == null) voices[voice.id] = voice
        else require(existing.files.map { it.first } == voice.files.map { it.first } &&
            existing.files.zip(voice.files).all { (a, b) -> a.second.length() == b.second.length() &&
                XpnSourceMetadata.sha256(a.second) == XpnSourceMetadata.sha256(b.second) }) {
            "personal voice ID ${voice.id} has conflicting immutable source files"
        }
        require(voices.size <= ConfluencePersonalFiles.MAX_VOICES) { "personal voice copy exceeds ${ConfluencePersonalFiles.MAX_VOICES} distinct voices" }
    }

    private fun requireBudget(voices: Collection<Voice>) {
        require(voices.sumOf { v -> v.files.sumOf { it.second.length() } } <=
            ConfluencePersonalFiles.MAX_KIT_BYTES - ConfluencePersonalFiles.KIT_METADATA_RESERVE_BYTES) {
            "personal voice copy exceeds the native backup byte budget including kit metadata reserve"
        }
    }

    fun copyReferenced(kit: Kit, sourceDir: File, targetDir: File) {
        val incoming = plan(listOf(kit to sourceDir))
        if (incoming.isEmpty()) return
        // Check all parent paths before creating anything; this also refuses a linked container.
        ConfluencePersonalFiles.child(targetDir, "${ConfluencePersonalFiles.DIRECTORY}/${incoming.first().id}/voice.json")
        val container = File(targetDir, ConfluencePersonalFiles.DIRECTORY)
        val all = LinkedHashMap<String, Voice>()
        if (container.exists()) {
            require(container.isDirectory) { "personal voice destination is not a directory" }
            Files.newDirectoryStream(container.toPath()).use { entries ->
                for (entry in entries) {
                    val id = entry.fileName.toString()
                    require(ConfluencePersonalFiles.ID.matches(id) && !Files.isSymbolicLink(entry) && Files.isDirectory(entry)) {
                        "unsafe existing personal voice destination: $id"
                    }
                    ConfluencePersonalFiles.validateVoice(targetDir, id)
                    add(all, Voice(id, listOf("voice.json", "raw.wav", "source.wav").map { name ->
                        val path = "${ConfluencePersonalFiles.DIRECTORY}/$id/$name"
                        path to ConfluencePersonalFiles.child(targetDir, path)
                    }))
                }
            }
        }
        val existingIds = all.keys.toSet()
        incoming.forEach { add(all, it) }
        requireBudget(all.values)
        require(targetDir.isDirectory || targetDir.mkdirs()) { "cannot create personal voice copy destination" }
        require(container.isDirectory || container.mkdirs()) { "cannot create personal voice container" }
        for (voice in incoming.filter { it.id !in existingIds }) {
            val staging = Files.createTempDirectory(targetDir.toPath(), ".personal-source-copy-").toFile()
            try {
                for ((path, source) in voice.files) {
                    val destination = ConfluencePersonalFiles.child(staging, path)
                    require(destination.parentFile.isDirectory || destination.parentFile.mkdirs()) { "cannot stage personal voice source" }
                    source.copyTo(destination)
                    require(XpnSourceMetadata.sha256(source) == XpnSourceMetadata.sha256(destination)) { "personal voice source changed while copying" }
                }
                ConfluencePersonalFiles.validateVoice(staging, voice.id)
                val staged = File(staging, "${ConfluencePersonalFiles.DIRECTORY}/${voice.id}")
                val destination = File(container, voice.id)
                require(!destination.exists()) { "personal voice destination appeared while copying" }
                if (!staged.renameTo(destination)) throw IOException("cannot publish copied personal voice ${voice.id}")
            } finally { staging.deleteRecursively() }
        }
    }
}
