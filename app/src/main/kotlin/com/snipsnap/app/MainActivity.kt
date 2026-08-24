package com.snipsnap.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.snipsnap.shell.Copy
import com.snipsnap.shell.Schemes

/**
 * The one Android entry point. Everything it hosts is a binding over
 * `:shell` — this class holds the `Context` so nothing else has to.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val scheme = Schemes.DEFAULT
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(scheme.lcd or 0xFF000000.toInt())),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = Copy.BOOT_READY,
                    style = TextStyle(color = Color(scheme.lcdInk or 0xFF000000.toInt())),
                )
            }
        }
    }
}
