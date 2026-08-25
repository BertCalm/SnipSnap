package com.snipsnap.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.snipsnap.app.nav.NavState
import com.snipsnap.app.nav.Screen
import com.snipsnap.app.nav.goTo
import com.snipsnap.app.nav.tickQuip
import com.snipsnap.app.theme.LcdSurface
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeFonts
import com.snipsnap.app.theme.TapeOsTheme
import com.snipsnap.app.theme.toColor
import com.snipsnap.app.ui.SnipSnapWindow
import com.snipsnap.shell.Motion
import com.snipsnap.shell.Schemes
import kotlinx.coroutines.delay

/**
 * The one Android entry point. Everything it hosts is a binding over
 * `:shell` — this class holds the `Context` so nothing else has to.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var state by remember { mutableStateOf(NavState(Screen.KITS)) }

            LaunchedEffect(state.personality) {
                while (true) {
                    delay(Motion.QUIP_ROTATE_MS.toLong())
                    state = state.tickQuip()
                }
            }

            TapeOsTheme(Schemes.DEFAULT) {
                SnipSnapWindow(
                    state = state,
                    onMenu = { state = state.goTo(it) },
                    tapeCell = "TAPE 0:00",
                    snipCell = "0 SNIPS",
                ) {
                    LcdSurface(modifier = Modifier.fillMaxSize()) {
                        BasicText(
                            text = state.screen.menuLabel,
                            modifier = Modifier.align(Alignment.Center),
                            style = TextStyle(
                                color = LocalScheme.current.lcdInk.toColor(),
                                fontFamily = TapeFonts.lcd,
                                fontSize = 25.sp,
                            ),
                        )
                    }
                }
            }
        }
    }
}
