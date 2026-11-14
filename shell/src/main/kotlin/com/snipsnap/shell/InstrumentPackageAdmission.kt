package com.snipsnap.shell

import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.mpc3.Mpc3TrackWriter
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Fresh OneNote-layout shelf admission, with provenance inside the complete TrackData directory. */
object InstrumentPackageAdmission {
    data class Hooks(val staged: (File) -> Unit = {}, val moved: (String) -> Unit = {})

    /**
     * The complete packet is validated before its three entries reach the shelf.
     * Sidecar last prevents listing a program whose samples are still in transit.
     * Existing names are refused. This lock covers this helper's publishers only;
     * handled failures roll back, but readers/crashes are not transaction-isolated.
     */
    @Synchronized
    fun <T> publishNew(name: String, destination: File, build: (File) -> T,
                       validate: (File) -> Unit, hooks: Hooks = Hooks()): T {
        require(Names.isMpcSafe(name) && name.length <= OneNote.MAX_NAME_LENGTH)
        require(!Files.isSymbolicLink(destination.toPath()))
        destination.mkdirs()
        require(destination.isDirectory)
        val entries = listOf(Mpc3TrackWriter.trackDataDirName(name), "$name.xty", "$name${InstrumentStore.SUFFIX}")
        for (entry in entries) require(!Files.exists(File(destination, entry).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            "Instrument package already exists: $entry"
        }
        val holder = Files.createTempDirectory(destination.toPath(), ".instrument-admission-").toFile()
        val packet = File(holder, "packet")
        val published = mutableListOf<String>()
        var retainForRecovery = false
        try {
            val result = build(packet)
            hooks.staged(packet)
            require(!Files.isSymbolicLink(packet.toPath()) && packet.isDirectory)
            require(packet.listFiles()!!.map { it.name }.toSet() == entries.toSet()) {
                "Declare all package provenance inside TrackData; unexpected or missing root entries"
            }
            for (entry in entries) require(!Files.isSymbolicLink(File(packet, entry).toPath()))
            validate(packet)
            require(packet.listFiles()!!.map { it.name }.toSet() == entries.toSet()) {
                "Validation introduced undeclared package entries"
            }
            for (entry in entries) {
                // No overwrite flag: another publisher's destination must be refused.
                Files.move(File(packet, entry).toPath(), File(destination, entry).toPath())
                published += entry
                hooks.moved(entry)
            }
            return result
        } catch (failure: Throwable) {
            try {
                for (entry in published.asReversed()) {
                    val target = File(destination, entry)
                    if (target.exists() && !target.deleteRecursively()) throw IOException("Cannot roll back new instrument entry: $entry")
                }
            } catch (rollback: Throwable) {
                retainForRecovery = true
                failure.addSuppressed(IOException("Admission rollback failed; remaining staged files retained at $holder", rollback))
            }
            throw failure
        } finally {
            if (!retainForRecovery) holder.deleteRecursively()
        }
    }
}
