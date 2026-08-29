package com.snipsnap.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.snipsnap.app.theme.TapeTheme
import com.snipsnap.app.theme.rememberDeskBrush
import com.snipsnap.app.theme.windowFrame
import com.snipsnap.app.ui.AppScreen
import com.snipsnap.app.ui.HelpScreen
import com.snipsnap.app.ui.KitScreen
import com.snipsnap.app.ui.KitsScreen
import com.snipsnap.app.ui.MenuRow
import com.snipsnap.app.ui.PropertiesScreen
import com.snipsnap.app.ui.StatusBar
import com.snipsnap.app.ui.StubScreen
import com.snipsnap.app.ui.TitleBar
import com.snipsnap.app.ui.ToastOverlay
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Motion
import com.snipsnap.shell.Personality
import com.snipsnap.shell.SchemeId
import com.snipsnap.shell.Schemes
import com.snipsnap.shell.StarterKits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

private const val PREFS = "tapeos"
private const val PREF_SCHEME = "scheme"
private const val PREF_PERSONALITY = "personality"

/**
 * The whole M0 app: the SNIPSNAP.EXE window on its desk, the menu row,
 * the status bar, and the three real screens. State is plain Compose
 * state — the screens' actual machines live tested in `:shell`, and the
 * later milestones bind to them; M0's state is navigation and a shelf.
 */
@Composable
fun App(shelf: KitShelf) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    var schemeId by remember {
        mutableStateOf(
            prefs.getString(PREF_SCHEME, null)
                ?.let { saved -> SchemeId.entries.firstOrNull { it.name == saved } }
                ?: Schemes.DEFAULT.id,
        )
    }
    var personality by remember {
        mutableStateOf(
            prefs.getString(PREF_PERSONALITY, null)
                ?.let { saved -> Personality.entries.firstOrNull { it.name == saved } }
                ?: Personality.FULL,
        )
    }
    val scheme = Schemes[schemeId]

    var screen by remember { mutableStateOf(AppScreen.KITS) }
    var kits by remember { mutableStateOf<List<KitShelf.Entry>>(emptyList()) }
    var open by remember { mutableStateOf<KitShelf.Entry?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        kits = withContext(Dispatchers.IO) { shelf.list() }
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(Motion.TOAST_DWELL_MS.toLong())
            toast = null
        }
    }

    fun fresh(starter: StarterKits.Starter) {
        if (busy != null) return
        busy = "DUBBING ${starter.displayName}…"
        scope.launch {
            val entry = try {
                withContext(Dispatchers.IO) { shelf.render(starter, Random.nextInt()) }
            } catch (e: Exception) {
                busy = null
                // Law 3: when it breaks, say exactly what happened.
                toast = "DUB FAILED: ${e.message ?: e.javaClass.simpleName}"
                return@launch
            }
            kits = withContext(Dispatchers.IO) { shelf.list() }
            busy = null
            toast = Copy.FRESH_TAPE
            open = entry
            screen = AppScreen.KIT
        }
    }

    TapeTheme(scheme, personality) {
        Box(
            Modifier
                .fillMaxSize()
                .background(rememberDeskBrush(scheme))
                // targetSdk 35 means Android 15 hands us the whole window and
                // reserves nothing for the status/navigation bars. The desk
                // brush is meant to bleed edge to edge, so it stays outside
                // this — only the window frame is pushed into the safe area,
                // or the titlebar renders under the clock.
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(Layout.OUTER_MARGIN.dp),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowFrame(scheme)
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TitleBar()
                MenuRow(current = screen, onSelect = { screen = it })
                Box(Modifier.weight(1f)) {
                    when (screen) {
                        AppScreen.KITS -> KitsScreen(
                            kits = kits,
                            busy = busy != null,
                            onOpen = { open = it; screen = AppScreen.KIT },
                            onFresh = ::fresh,
                        )
                        AppScreen.KIT -> KitScreen(open)
                        AppScreen.PROPERTIES -> PropertiesScreen(
                            currentScheme = schemeId,
                            onScheme = {
                                schemeId = it
                                prefs.edit().putString(PREF_SCHEME, it.name).apply()
                            },
                            personality = personality,
                            onPersonality = {
                                personality = it
                                prefs.edit().putString(PREF_PERSONALITY, it.name).apply()
                            },
                        )
                        AppScreen.HELP -> HelpScreen()
                        else -> StubScreen(screen)
                    }
                }
                StatusBar(
                    screenLabel = screen.label,
                    shelfLabel = "KITS: ${kits.size}",
                    busy = busy,
                )
            }
            ToastOverlay(toast)
        }
    }
}
