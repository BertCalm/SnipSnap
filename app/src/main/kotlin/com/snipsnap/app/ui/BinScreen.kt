package com.snipsnap.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.snipsnap.app.KitShelf
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdInk2
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Scheme
import java.io.File

/**
 * BIN: the plan's bench for everything that has been deleted and can still be
 * taken back (`docs/SCREEN_PLAN_2026_10.md` §3.1 and §3.8) — reached from the
 * shelf, with a ◄ SHELF chip back. It holds two headed lists, DELETED KITS
 * above DELETED SNIPS, each with its own RESTORE rows and its own EMPTY THE BIN
 * NOW.
 *
 * It is one screen rather than two because the two it replaces
 * (`DeletedKitsScreen`, reached from the kit shelf, and `DeletedSnipsScreen`,
 * reached from SNIPS) were the same layout: an LCD header row, a list of rows
 * with a days-left count and a RESTORE button, an empty state, and a red
 * two-tap EMPTY button under it. Two doors to two copies of one room is the
 * seam the four-place shape removes. Each list is now a section
 * ([DeletedKitsSection], [DeletedSnipsSection]) that keeps its own state and
 * its own writes and draws only a heading line; this screen owns what the two
 * share, the header row and Back, and gives each section half of the height.
 *
 * Back is registered unconditionally and leaves the bench. The old screens kept
 * their BackHandler registered while a write was in flight so that Back could
 * not fall through to `Activity.finish()` mid-RESTORE (they swallowed it while
 * busy); this one is always registered for the same reason, and `busy` lives in
 * the sections, which the bench cannot see.
 */
@Composable
fun BinScreen(
    shelf: KitShelf,
    snipsRoot: File,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    onKitRestored: (KitShelf.Entry) -> Unit,
    onSnipRestored: (File) -> Unit,
) {
    val scheme = LocalScheme.current
    // Either list mid-write holds the door, the way each screen held its own:
    // leaving would cancel the section's scope and lose the toast and the
    // shelf refresh its RESTORE owes, though the write itself would finish.
    var kitsBusy by remember { mutableStateOf(false) }
    var snipsBusy by remember { mutableStateOf(false) }
    val busy = kitsBusy || snipsBusy

    BackHandler { if (!busy) onBack() }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Layout.MIN_HIT_TARGET.dp)
                .lcdPanel(scheme)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderChip("◄ SHELF", scheme, Modifier.width(72.dp), enabled = !busy, onClick = onBack)
            Spacer(Modifier.weight(1f))
            TapeText("BIN", TapeType.lcdHeader, scheme.lcdInk.tape)
            Spacer(Modifier.weight(1f))
            // Balances the chip so the title stays centred, as on the screens
            // this one replaces (which put a count here; each list's heading
            // carries its own count now).
            Spacer(Modifier.width(72.dp))
        }

        DeletedKitsSection(
            shelf = shelf,
            onToast = onToast,
            onRestored = onKitRestored,
            onBusyChange = { kitsBusy = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        DeletedSnipsSection(
            snipsRoot = snipsRoot,
            onToast = onToast,
            onRestored = onSnipRestored,
            onBusyChange = { snipsBusy = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

/** Duplicated from `SnipsScreen.kt`'s own private `HeaderChip` (and `TakesBinScreen.kt`'s) rather than exported — every screen-local button in this codebase stays private to its own file, per that file's own stated convention. */
@Composable
private fun HeaderChip(
    label: String,
    scheme: Scheme,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .border(1.dp, scheme.lcdInk2, RoundedCornerShape(3.dp))
            // `enabled` goes through `tapeClick`, not around it: a dimmed
            // chip stays in the semantics tree instead of silently
            // vanishing from it (accessibility audit finding 12).
            .tapeClick(label = label, enabled = enabled, spoken = spokenBack(label), onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(label, TapeType.pixel, if (enabled) scheme.lcdInk.tape else scheme.lcdInk.tape.copy(alpha = 0.4f))
    }
}
