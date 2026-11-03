package com.snipsnap.app.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.snipsnap.app.AndroidJCard
import com.snipsnap.app.AndroidKitArt
import com.snipsnap.app.CardWriter
import com.snipsnap.app.Exports
import com.snipsnap.app.KitWrites
import com.snipsnap.app.PREFS
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.lcdPanel
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.tape
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Expansion
import com.snipsnap.shell.ExpansionExportModel
import com.snipsnap.shell.ExpansionMembers
import com.snipsnap.shell.ExpansionStore
import com.snipsnap.shell.LandingNote
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Member
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

private const val TAG = "ExpansionExport"

/** One expansion and the shelf as it is now: what the panel draws and the model writes from. */
private class Loaded(val all: List<Expansion>, val expansion: Expansion?, val members: List<Member>, val seed: Member?)

/**
 * EXPORT's EXPANSION subject: which expansion, the kits in it now, the form
 * (on-card folder or one `.xpn`), the tile as it will be written with
 * CHANGE ART into the same picker the expansion was born with, and the
 * write. The write lands in the phone's export folder first, as a kit's
 * does, then goes to the card when one is picked, through the same card
 * copy and the same replace check a kit's write uses.
 */
@Composable
internal fun ExpansionExportPanel(
    store: ExpansionStore,
    shelfRoot: File,
    expansionId: String,
    appScope: CoroutineScope,
    onToast: (String) -> Unit,
    onNote: (LandingNote.Note) -> Unit,
    onPick: (String) -> Unit,
) {
    val scheme = LocalScheme.current
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    val loaded by produceState<Loaded?>(null, expansionId, revision) {
        value = offMain {
            runCatching {
                val all = store.list()
                // An empty id is the switch's "the first one there is".
                val e = all.firstOrNull { it.id == expansionId } ?: all.firstOrNull().takeIf { expansionId.isEmpty() }
                val index = ExpansionMembers.index(shelfRoot)
                Loaded(all, e, e?.let { ExpansionMembers.present(it, index) }.orEmpty(), e?.let { ExpansionMembers.seed(it, index) })
            }.getOrNull()
        }
    }
    val now = loaded ?: run {
        Box(Modifier.fillMaxSize().lcdPanel(scheme))
        return
    }
    val e = now.expansion ?: run {
        Box(Modifier.fillMaxSize().lcdPanel(scheme).padding(14.dp), contentAlignment = Alignment.Center) {
            TapeText(Copy.EXPANSION_NONE, TapeType.lcdSmall, scheme.lcdInk.tape, maxLines = 3)
        }
        return
    }
    val model = remember(e, now.members) {
        ExpansionExportModel(
            e, now.members, now.seed,
            tileRenderer = { k, d, knobs, label -> AndroidKitArt.png(k, d, knobs, label = label, assets = context.assets) },
            jCardRenderer = { k, d, c -> AndroidJCard.png(k, d, catalog = c, assets = context.assets) },
        )
    }
    var form by remember(model) { mutableStateOf(model.form) }
    var tileRevision by remember(model) { mutableIntStateOf(0) }
    var picking by remember(model) { mutableStateOf(false) }
    var busy by remember(model) { mutableStateOf(false) }
    var overwriting by remember(model) { mutableStateOf<File?>(null) }
    // A card copy held back because it would replace something there: the phone write it came from,
    // so the confirming tap copies only, as a kit's PUT ON CARD does, rather than writing the phone again.
    var cardPending by remember(model) { mutableStateOf<Pair<List<File>, Int>?>(null) }
    var cardReplacing by remember(model) { mutableStateOf<String?>(null) }
    LaunchedEffect(overwriting, cardReplacing) {
        if (overwriting == null && cardReplacing == null) return@LaunchedEffect
        delay(EXPORT_ARM_MS)
        overwriting = null
        cardReplacing = null
        cardPending = null
    }
    val card = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_CARD_TREE, null)?.let { Uri.parse(it) } }
    val tile by produceState<ImageBitmap?>(null, model, tileRevision) {
        value = offMain {
            runCatching { model.tile()?.let { BitmapFactory.decodeByteArray(it, 0, it.size).asImageBitmap() } }.getOrNull()
        }
    }

    /** Copies [items] onto [card] and says how it went, as a kit's card copy does. */
    suspend fun copyOnto(card: Uri, items: List<File>, files: Int) {
        val copied = offMain { CardWriter.copy(context, card, items) }
        cardPending = null
        cardReplacing = null
        onToast(
            when {
                copied.contested > 0 -> Copy.dubDoneCardContested(copied.contested)
                copied.replaced > 0 -> Copy.dubDoneCardReplaced(copied.replaced)
                else -> Copy.DUB_DONE_CARD
            },
        )
        if (copied.contested == 0) {
            onNote(LandingNote.dubbed(e.title, onCard = true, files = files, where = "${Copy.cardName(card.lastPathSegment)}/${items.first().name}"))
        }
    }

    fun write() {
        if (busy || model.empty) return
        val pending = cardPending
        if (pending != null && card != null) {
            // The confirm of a held-back card copy: the phone already holds this write.
            busy = true
            appScope.launch {
                try {
                    copyOnto(card, pending.first, pending.second)
                } catch (ex: Exception) {
                    if (ex is CancellationException) throw ex
                    Log.e(TAG, "write: card copy failed", ex)
                    onNote(LandingNote.dubFailed())
                } finally {
                    busy = false
                }
            }
            return
        }
        busy = true
        val replace = overwriting != null
        appScope.launch {
            try {
                val result = offMain {
                    KitWrites.mutex.withLock {
                        val root = Exports.dir(context) ?: throw IOException("external storage unavailable")
                        model.write(root, overwrite = replace)
                    }
                }
                when (result) {
                    is ExpansionExportModel.WriteResult.WouldOverwrite -> {
                        overwriting = result.path
                        onToast(Copy.dubWouldOverwrite(result.path.name))
                    }
                    is ExpansionExportModel.WriteResult.Nothing -> {
                        overwriting = null
                        result.skipped.forEach { (kit, why) -> onToast(Copy.memberSkipped(kit, why)) }
                        onToast(Copy.EXPORT_BLOCKED)
                    }
                    is ExpansionExportModel.WriteResult.Done -> {
                        overwriting = null
                        result.skipped.forEach { (kit, why) -> onToast(Copy.memberSkipped(kit, why)) }
                        val items = ExpansionExportModel.cardItems(result.result, model.form)
                        val written = Copy.expansionWritten(e.title, result.result.packed.size)
                        val files = offMain { items.sumOf { f -> if (f.isFile) 1 else f.walkTopDown().count { it.isFile } } }
                        if (card == null) {
                            onToast(written)
                            onNote(LandingNote.dubbed(e.title, onCard = false, files = files, where = items.first().absolutePath))
                        } else {
                            val standing = offMain { CardWriter.preflight(context, card, items) }
                            onToast(written)
                            if (standing.isNotEmpty()) {
                                // Held back: the next tap copies what the phone now holds, nothing more.
                                cardPending = items to files
                                cardReplacing = standing.first().name
                                onToast(Copy.dubCardWouldReplace(standing.first().name))
                            } else {
                                copyOnto(card, items, files)
                            }
                        }
                    }
                }
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                Log.e(TAG, "write: expansion write failed", ex)
                onNote(LandingNote.dubFailed())
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Heading(Copy.EXPANSION_PICK)
        ChipLine {
            for (x in now.all) Chip(x.title, x.id == e.id) { onPick(x.id) }
        }
        Heading(Copy.EXPANSION_MEMBERS)
        if (model.empty) {
            TapeText(Copy.EXPANSION_EMPTY, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
        } else {
            for (m in now.members) TapeText(m.kit.name, TapeType.marker, scheme.ink.tape)
        }
        ChipLine {
            Chip(Copy.EXPANSION_FORM_FOLDER, form == ExpansionExportModel.Form.FOLDER) {
                model.form = ExpansionExportModel.Form.FOLDER
                form = model.form
            }
            Chip(Copy.EXPANSION_FORM_XPN, form == ExpansionExportModel.Form.XPN) {
                model.form = ExpansionExportModel.Form.XPN
                form = model.form
            }
        }
        // The last look: the tile as it will be written, and the door back into the picker.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(160.dp).lcdPanel(scheme), contentAlignment = Alignment.Center) {
                val t = tile
                when {
                    t != null -> Image(t, contentDescription = null, modifier = Modifier.size(160.dp))
                    !model.empty -> TapeText(Copy.ART_BUSY, TapeType.pixelSmall, scheme.lcdInk.tape)
                }
            }
            if (!model.empty) Chip(Copy.CHANGE_ART, false) { picking = true }
        }
        TapeText(card?.let { Copy.expansionToCard(Copy.cardName(it.lastPathSegment)) } ?: Copy.EXPANSION_TO_PHONE, TapeType.pixelSmall, scheme.ink2.tape, maxLines = 2)
        ActionButton(Copy.EXPANSION_WRITE, scheme, enabled = !busy && !model.empty, modifier = Modifier.fillMaxWidth()) { write() }
    }

    val seed = model.seed
    if (picking && seed != null) {
        ArtPickerSheet(
            kit = seed.kit,
            kitDir = seed.dir,
            knobs = model.knobs,
            onKnobs = { picked ->
                model.knobs = picked
                tileRevision++
                save(appScope, onToast) { store.setArt(e.id, picked, model.seed?.kitId) }
            },
            onDismiss = { picking = false; revision++ },
            label = e.title,
            seedKits = now.members,
            onSeedKit = { m ->
                model.seed = m
                tileRevision++
                save(appScope, onToast) { store.setArt(e.id, model.knobs, m.kitId) }
            },
            title = "${Copy.ART_FOR} ${e.title}",
        )
    }
}

/** One change to the store, off the main thread under the kit-write lock; a failure is said, never swallowed. */
private fun save(scope: CoroutineScope, onToast: (String) -> Unit, change: () -> Unit) {
    scope.launch {
        val ok = offMain { KitWrites.mutex.withLock { runCatching { change() }.isSuccess } }
        if (!ok) onToast(Copy.EXPANSION_SAVE_FAILED)
    }
}

@Composable
private fun Heading(text: String) {
    TapeText(text, TapeType.pixelSmall, LocalScheme.current.ink2.tape)
}

@Composable
private fun ChipLine(chips: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { chips() }
}

@Composable
private fun Chip(text: String, picked: Boolean, onTap: () -> Unit) {
    val scheme = LocalScheme.current
    Box(
        Modifier
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .raisedBevel(scheme)
            .border(if (picked) 2.dp else 0.dp, if (picked) scheme.amber.tape else scheme.grayEdge.tape, RoundedCornerShape(4.dp))
            .tapeClick(label = text, onClick = onTap)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        TapeText(text, TapeType.pixel, if (picked) scheme.amber.tape else scheme.ink.tape)
    }
}
