package com.snipsnap.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.snipsnap.kit.Project
import com.snipsnap.shell.ProjectTempo
import kotlin.reflect.KProperty

/**
 * The open kit as every screen sees it: the kit with the **project's**
 * tempo on it ([ProjectTempo.effective]), so the ~25 readers of
 * `entry.kit.tempoBpm` follow BEAT's one tempo without each learning about
 * projects.
 *
 * That tempo exists in memory only. This holds the entry App set; reading
 * gives the effective one, recomputed (and kept as one instance) only when
 * the entry or the project's tempo changes. Nothing the screens save is
 * affected: every kit write in the app goes through a `KitBuilderModel`,
 * which loads the kit from its folder and so saves the file's own tempo,
 * and what such a model hands back through `onKitUpdated` is that same
 * file-true kit.
 */
internal class OpenKit(
    private val entry: MutableState<KitShelf.Entry?>,
    private val effective: State<KitShelf.Entry?>,
) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): KitShelf.Entry? = effective.value

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: KitShelf.Entry?) {
        entry.value = value
    }
}

/** A delegate for App's `open`; [project] is read live, so a tempo change reaches it. */
@Composable
internal fun rememberOpenKit(project: () -> Project?): OpenKit {
    val entry = remember { mutableStateOf<KitShelf.Entry?>(null) }
    val effective = remember {
        derivedStateOf {
            entry.value?.let { e ->
                val kit = ProjectTempo.effective(e.kit, project())
                if (kit === e.kit) e else e.copy(kit = kit)
            }
        }
    }
    return remember { OpenKit(entry, effective) }
}
