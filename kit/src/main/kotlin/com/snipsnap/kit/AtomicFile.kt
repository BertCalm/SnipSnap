package com.snipsnap.kit

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Write-then-rename, so a process killed mid-write never leaves a
 * half-written file. The folder *is* the kit — a truncated `kit.json` is a
 * lost kit — and on a phone the OS kills apps at will, so every store that
 * rewrites a whole file in place goes through here instead.
 *
 * The bytes land in a sibling temp file, are flushed to the disk, and then
 * atomically renamed over the target. A reader sees either the complete
 * old file or the complete new one, never a torn mix; a crash leaves at
 * worst a stray `.tmp` beside the intact original.
 */
object AtomicFile {

    fun writeText(file: File, text: String) = writeBytes(file, text.toByteArray(Charsets.UTF_8))

    fun writeBytes(file: File, bytes: ByteArray) {
        val dir = file.parentFile ?: File(".")
        dir.mkdirs()
        val tmp = File(dir, "${file.name}.tmp")
        java.io.FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            // Force the bytes to the platter before the rename, so the rename
            // can't win a race the data hasn't finished losing.
            out.fd.sync()
        }
        try {
            Files.move(
                tmp.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            // Some filesystems can't do atomic moves; a plain replace is the
            // best available and still far safer than an in-place truncate.
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
    }
}
