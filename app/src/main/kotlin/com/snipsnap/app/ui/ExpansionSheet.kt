package com.snipsnap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.snipsnap.app.KitShelf
import com.snipsnap.app.KitWrites
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.kit.KitIds
import com.snipsnap.kit.Names
import com.snipsnap.shell.ArtKnobs
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Expansion
import com.snipsnap.shell.ExpansionStore
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Names as NameRoll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/** Where the EXPANSION door's flow stands: the list, naming a new one, or picking its art. */
private sealed interface Stage {
    data object Sheet : Stage
    data object Naming : Stage
    data class Picking(val title: String, val knobs: ArtKnobs) : Stage
}

/**
 * The shelf's EXPANSION door for one held kit. The sheet lists NEW and every
 * expansion, marking the ones the kit is in; tapping one of those takes the
 * kit out, tapping another puts it in. NEW asks for a name (refused when
 * unsafe or taken, whatever its case), then opens the art picker for the new
 * expansion drawn from this kit; closing the picker is the birth: the kit
 * gets its id and the expansion is written with it as its first member.
 * Every write runs off the main thread under [KitWrites.mutex]; [onDone]
 * closes the flow.
 */
@Composable
internal fun ExpansionFlow(entry: KitShelf.Entry, store: ExpansionStore, onToast: (String) -> Unit, onDone: () -> Unit) {
    val scheme = LocalScheme.current
    val scope = rememberCoroutineScope()
    var stage by remember(entry) { mutableStateOf<Stage>(Stage.Sheet) }
    var revision by remember { mutableIntStateOf(0) }
    val kitName = entry.kit.name
    val loaded by produceState<Pair<List<Expansion>, String?>?>(null, revision) {
        value = offMain { store.list() to KitIds.read(entry.dir) }
    }
    val expansions = loaded?.first.orEmpty()
    val kitId = loaded?.second

    when (val s = stage) {
        Stage.Sheet -> BottomSheet(Copy.EXPANSION_SHEET_TITLE, onDone) {
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SheetRow(Copy.EXPANSION_NEW, null, marked = false) { stage = Stage.Naming }
                for (e in expansions) {
                    val member = kitId != null && kitId in e.memberKitIds
                    SheetRow(e.title, Copy.countOf(e.memberKitIds.size, "KIT", "KITS"), marked = member) {
                        scope.launch {
                            // Join or leave is decided from the store as it is now, inside the lock, not
                            // from the list on screen: a second tap can land before that list reloads.
                            val joined: Boolean? = offMain {
                                KitWrites.mutex.withLock {
                                    runCatching {
                                        val id = KitIds.read(entry.dir)
                                        val isIn = id != null && store.get(e.id)?.memberKitIds?.contains(id) == true
                                        if (isIn) {
                                            store.remove(e.id, id!!)
                                            false
                                        } else {
                                            store.add(e.id, KitIds.ensure(entry.dir))
                                            true
                                        }
                                    }.getOrNull()
                                }
                            }
                            onToast(
                                when (joined) {
                                    null -> Copy.EXPANSION_SAVE_FAILED
                                    true -> Copy.kitJoined(kitName, e.title)
                                    false -> Copy.kitLeft(kitName, e.title)
                                },
                            )
                            revision++
                        }
                    }
                }
            }
        }
        Stage.Naming -> ExpansionNameDialog(
            title = Copy.EXPANSION_NAME_TITLE,
            initialName = "",
            check = { name -> check(name, expansions, exceptId = null) },
            taken = expansions.map { it.title }.toSet(),
            onCancel = { stage = Stage.Sheet },
            onConfirm = { name -> stage = Stage.Picking(name, ArtKnobs.default(scheme)) },
        )
        is Stage.Picking -> ArtPickerSheet(
            kit = entry.kit,
            kitDir = entry.dir,
            knobs = s.knobs,
            onKnobs = { stage = s.copy(knobs = it) },
            onDismiss = {
                scope.launch {
                    val ok = offMain {
                        KitWrites.mutex.withLock {
                            runCatching { store.create(s.title, KitIds.ensure(entry.dir), s.knobs) }.isSuccess
                        }
                    }
                    onToast(if (ok) Copy.expansionBorn(s.title) else Copy.EXPANSION_SAVE_FAILED)
                    onDone()
                }
            },
            label = s.title,
            title = "${Copy.ART_FOR} ${s.title}",
        )
    }
}

/** Whether [name] can name an expansion beside [existing]: the store's own rule, read from the list already in hand. */
internal fun check(name: String, existing: List<Expansion>, exceptId: String?): ExpansionStore.NameCheck = when {
    !Names.isMpcSafe(name) -> ExpansionStore.NameCheck.Unsafe
    existing.any { it.id != exceptId && it.title.equals(name, ignoreCase = true) } -> ExpansionStore.NameCheck.Taken
    else -> ExpansionStore.NameCheck.Ok
}

@Composable
private fun SheetRow(title: String, facts: String?, marked: Boolean, onTap: () -> Unit) {
    val scheme = LocalScheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .raisedBevel(scheme)
            .tapeClick(label = title, onClick = onTap)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            TapeText(title, TapeType.marker, scheme.ink.tape)
            facts?.let { TapeText(it, TapeType.pixelSmall, scheme.ink2.tape) }
        }
        if (marked) TapeText(Copy.EXPANSION_IN, TapeType.pixel, scheme.amber.tape)
    }
}

/**
 * The one typed-name dialog expansions have: the shelf's rename dialog
 * shape (field, REROLL, CANCEL and a confirm), refusing a name that is not
 * MPC-safe or that another expansion already has, whatever its case.
 */
@Composable
internal fun ExpansionNameDialog(
    title: String,
    initialName: String,
    check: (String) -> ExpansionStore.NameCheck,
    taken: Set<String>,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
    confirmLabel: String = Copy.EXPANSION_NAME_CONFIRM,
) {
    val scheme = LocalScheme.current
    var name by remember { mutableStateOf(initialName) }
    val verdict = check(name)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).tapeClick(label = "CANCEL", onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .raisedBevel(scheme)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TapeText(title, TapeType.lcdSmall, scheme.ink.tape)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).sunkenField(scheme).padding(horizontal = 8.dp, vertical = 8.dp)) {
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        textStyle = TapeType.marker.copy(color = scheme.ink.tape),
                        cursorBrush = SolidColor(scheme.ink.tape),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ActionButton("REROLL", scheme, enabled = true, accessibilityLabel = "REROLL NAME", modifier = Modifier.width(92.dp)) {
                    name = NameRoll.roll(taken = taken)
                }
            }
            when (verdict) {
                ExpansionStore.NameCheck.Taken -> TapeText(Copy.EXPANSION_NAME_TAKEN, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
                ExpansionStore.NameCheck.Unsafe -> if (name.isNotEmpty()) {
                    TapeText(Copy.EXPANSION_NAME_UNSAFE, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
                }
                ExpansionStore.NameCheck.Ok -> Unit
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("CANCEL", scheme, enabled = true, modifier = Modifier.weight(1f), onClick = onCancel)
                ActionButton(confirmLabel, scheme, enabled = verdict == ExpansionStore.NameCheck.Ok, modifier = Modifier.weight(1f), onClick = { onConfirm(name) })
            }
        }
    }
}
