package com.snipsnap.shell

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Latest staged settings per directory. A kit switch must not redirect an earlier write. */
class SurfaceSettingsWrites {
    private val pending = ConcurrentHashMap<File, SurfaceStore.Settings>()

    fun stage(dir: File, settings: SurfaceStore.Settings) { pending[dir] = settings }

    /** Caller holds the kit-write mutex; stage remains safe on the UI thread during IO. */
    fun save(dir: File): File {
        require(dir.isDirectory) { "kit folder no longer exists: $dir" }
        return SurfaceStore.save(dir, requireNotNull(pending[dir]) { "no staged surface settings for $dir" })
    }
}
