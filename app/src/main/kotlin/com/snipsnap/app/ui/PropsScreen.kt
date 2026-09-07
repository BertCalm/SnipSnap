package com.snipsnap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.TapeFonts
import com.snipsnap.app.theme.toColor
import com.snipsnap.shell.Layout
import com.snipsnap.shell.Personality
import com.snipsnap.shell.SchemeId
import com.snipsnap.shell.Schemes

/**
 * TAPE PROPERTIES: the scheme picker and the personality slider.
 *
 * Each swatch previews its own scheme's chrome and LCD side by side —
 * you pick a look by seeing it, which is the point of having six.
 */
@Composable
fun PropsScreen(
    current: SchemeId,
    personality: Personality,
    onScheme: (SchemeId) -> Unit,
    onPersonality: (Personality) -> Unit,
) {
    val s = LocalScheme.current
    Column(
        modifier = Modifier.fillMaxSize().padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LcdHeader(left = "TAPE PROPERTIES", right = Schemes[current].id.displayName)

        BasicText(
            text = "SCHEME",
            style = TextStyle(color = s.ink2.toColor(), fontFamily = TapeFonts.pixel, fontSize = 9.sp),
        )

        for (scheme in Schemes.ALL) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Layout.MIN_HIT_TARGET.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(scheme.gray.toColor())
                    .then(
                        if (scheme.id == current) {
                            Modifier.border(2.dp, s.lcdInk.toColor(), RoundedCornerShape(4.dp))
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onScheme(scheme.id) }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                BasicText(
                    text = scheme.id.displayName,
                    style = TextStyle(
                        color = scheme.ink.toColor(),
                        fontFamily = TapeFonts.display,
                        fontSize = 11.sp,
                        letterSpacing = 2.sp,
                    ),
                )
                Box(
                    modifier = Modifier
                        .height(26.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(scheme.lcd.toColor())
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = "LCD",
                        style = TextStyle(
                            color = scheme.lcdInk.toColor(),
                            fontFamily = TapeFonts.lcd,
                            fontSize = 17.sp,
                        ),
                    )
                }
            }
        }

        BasicText(
            text = "PERSONALITY",
            style = TextStyle(color = s.ink2.toColor(), fontFamily = TapeFonts.pixel, fontSize = 9.sp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (level in Personality.entries) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (level == personality) s.lcd.toColor() else s.gray.toColor())
                        .clickable { onPersonality(level) },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = level.name,
                        style = TextStyle(
                            color = if (level == personality) s.lcdInk.toColor() else s.ink2.toColor(),
                            fontFamily = TapeFonts.pixel,
                            fontSize = 9.sp,
                        ),
                    )
                }
            }
        }
    }
}
