package com.snipsnap.app.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeType
import com.snipsnap.app.theme.raisedBevel
import com.snipsnap.app.theme.sunkenField
import com.snipsnap.app.theme.tape
import com.snipsnap.kit.GrooveEdit
import com.snipsnap.kit.GrooveStore
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Layout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/*
 * BEAT's pattern list and the slip that names a pattern. Kept out of
 * GrooveScreen.kt on purpose: the main composable there sits at the dex
 * 256-register cliff, so each of these is a self-contained composable taking
 * plain values and callbacks, and the screen calls it in one line.
 */

/**
 * Every pattern of yours, as a list that scrolls: what the YOURS segment
 * opens when there are more than [GrooveEdit.CYCLE_UP_TO] of them, because
 * stepping through thirty-two with one tap each is not a way to reach one.
 * Tapping a row opens that pattern; RENAME names the one that is open.
 * The open one carries a border and says so in words, not a colour alone.
 */
@Composable
internal fun PatternList(
    kitDir: File,
    patterns: List<Mpc3Clip>,
    names: Map<String, String>,
    current: Int,
    onPick: (Int) -> Unit,
    onNames: (Map<String, String>) -> Unit,
    onToast: (String) -> Unit,
    onClose: () -> Unit,
) {
    var naming by remember { mutableStateOf(false) }
    val open = patterns.getOrNull(current)
    if (naming && open != null) {
        PatternNameDialog(kitDir, open, onToast, onCancel = { naming = false }) {
            naming = false
            onNames(it)
        }
        return
    }
    BottomSheet(Copy.PATTERN_LIST_TITLE, onClose) {
        val scheme = LocalScheme.current
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(patterns, key = { _, clip -> clip.name }) { i, clip ->
                PatternRow(
                    label = GrooveEdit.labelOf(clip.name, names) ?: clip.name,
                    place = i + 1,
                    total = patterns.size,
                    now = i == current,
                ) { onPick(i) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton(Copy.PATTERN_RENAME, scheme, enabled = open != null, modifier = Modifier.weight(1f)) { naming = true }
            ActionButton(Copy.PATTERN_LIST_CLOSE, scheme, enabled = true, modifier = Modifier.weight(1f), onClick = onClose)
        }
    }
}

@Composable
private fun PatternRow(label: String, place: Int, total: Int, now: Boolean, onTap: () -> Unit) {
    val scheme = LocalScheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Layout.MIN_HIT_TARGET.dp)
            .background(if (now) scheme.field.tape else scheme.lcd.tape, RoundedCornerShape(4.dp))
            // Selected is the app's border-plus-word: a 2 dp accent line and
            // OPEN NOW under the name, reserved blank on every other row so
            // choosing one does not grow only its own.
            .border(if (now) 2.dp else 1.dp, if (now) scheme.accent.tape else scheme.grayEdge.tape, RoundedCornerShape(4.dp))
            .semantics { selected = now }
            .tapeClick(label = Copy.patternRow(label, place, total, now), onClick = onTap)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            TapeText(label, TapeType.marker, scheme.ink.tape)
            TapeText(if (now) Copy.SONG_PICK_NOW else "", TapeType.pixelSmall, scheme.ink2.tape)
        }
        TapeText("%02d".format(java.util.Locale.ROOT, place), TapeType.pixel, scheme.amber.tape)
    }
}

/**
 * The slip that names the pattern stored as [clip]: a field, a caption that
 * says what a name can be (or why this one cannot), and one button that
 * reads SAVE, or USE DEFAULT when the field is empty. Judged on every
 * keystroke by [GrooveEdit.checkName], the one home of the rule. The write
 * and the toast are its own; [onDone] hands back the names as they now
 * stand on disk.
 */
@Composable
internal fun PatternNameDialog(
    kitDir: File,
    clip: Mpc3Clip,
    onToast: (String) -> Unit,
    onCancel: () -> Unit,
    onDone: (Map<String, String>) -> Unit,
) {
    // The names and the other programs, read off the main thread; the slip
    // opens once they are in. Both are small files read once.
    val loaded by produceState<Pair<Map<String, String>, List<String>>?>(null, kitDir) {
        value = withContext(Dispatchers.IO) { GrooveStore.loadNames(kitDir) to GrooveEdit.loadAll(kitDir).map { it.name } }
    }
    val (names, programs) = loaded ?: return
    val scheme = LocalScheme.current
    val scope = rememberCoroutineScope()
    val default = GrooveEdit.progIndexOfName(clip.name)?.let { GrooveEdit.defaultLabel(it) } ?: clip.name
    val start = names[clip.name].orEmpty()
    var field by remember { mutableStateOf(TextFieldValue(start, TextRange(start.length))) }
    var busy by remember { mutableStateOf(false) }
    val verdict = GrooveEdit.checkName(field.text, clip.name, programs, names)
    val caption = when (verdict) {
        is GrooveEdit.NameCheck.Ok -> Copy.PATTERN_NAME_NOTE to scheme.ink2.tape
        GrooveEdit.NameCheck.Default -> Copy.patternNameEmpty(default) to scheme.ink2.tape
        GrooveEdit.NameCheck.Taken -> Copy.PATTERN_NAME_TAKEN to scheme.warn.tape
    }
    val canSave = verdict != GrooveEdit.NameCheck.Taken && !busy
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun save() {
        if (!canSave) return
        busy = true
        scope.launch {
            try {
                val (result, fresh) = withContext(Dispatchers.IO) {
                    GrooveEdit.rename(kitDir, clip, field.text) to GrooveStore.loadNames(kitDir)
                }
                onToast(
                    when (result) {
                        is GrooveEdit.NameCheck.Ok -> Copy.patternNamed(result.name)
                        GrooveEdit.NameCheck.Default -> Copy.patternUnnamed(default)
                        GrooveEdit.NameCheck.Taken -> Copy.PATTERN_NAME_TAKEN
                    },
                )
                onDone(fresh)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("PatternNameDialog", "RENAME: failed", e)
                onToast(Copy.actionFailed("RENAME"))
                busy = false
            }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            // A bare gesture detector: a merging clickable here would swallow
            // the field's SetText action and the name could not be typed.
            .pointerInput(onCancel) { detectTapGestures { onCancel() } },
        contentAlignment = Alignment.TopCenter,
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
            TapeText(Copy.PATTERN_NAME_TITLE, TapeType.lcdSmall, scheme.ink.tape)
            Box(Modifier.fillMaxWidth().sunkenField(scheme).padding(horizontal = 8.dp, vertical = 8.dp)) {
                BasicTextField(
                    value = field,
                    onValueChange = { v ->
                        // Upper case and held to the cap as it is typed; the
                        // tidying (spaces, odd marks) is checkName's, on save.
                        val t = v.text.uppercase(java.util.Locale.ROOT).take(GrooveEdit.NAME_MAX)
                        field = v.copy(text = t, selection = TextRange(minOf(v.selection.start, t.length), minOf(v.selection.end, t.length)))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    textStyle = TapeType.marker.copy(color = scheme.ink.tape),
                    cursorBrush = SolidColor(scheme.ink.tape),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            TapeText(caption.first, TapeType.pixelSmall, caption.second, maxLines = 3)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("CANCEL", scheme, enabled = true, modifier = Modifier.weight(1f), onClick = onCancel)
                ActionButton(
                    if (verdict == GrooveEdit.NameCheck.Default) Copy.PATTERN_NAME_RESET else Copy.PATTERN_NAME_SAVE,
                    scheme,
                    enabled = canSave,
                    dimmed = !canSave,
                    modifier = Modifier.weight(1f),
                ) { save() }
            }
        }
    }
}
