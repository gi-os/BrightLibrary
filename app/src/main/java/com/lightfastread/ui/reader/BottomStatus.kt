package com.lightfastread.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.lightfastread.ui.light.LightText
import com.lightfastread.ui.light.LightTextVariant
import com.lightfastread.ui.light.LightThemeTokens
import com.lightfastread.ui.light.designVerticalPxToDp
import kotlin.math.roundToInt

@Composable
fun BottomStatus(
    index: Int,
    total: Int,
    liveWpm: Float,
    isHolding: Boolean,
    modifier: Modifier = Modifier,
) {
    val percent = if (total > 0) (index * 100f / total).coerceIn(0f, 100f) else 0f
    val colors = LightThemeTokens.colors
    Column(modifier = modifier) {
        if (isHolding && liveWpm > 0.5f) {
            // The one live number on the screen, so it keeps the full content colour while
            // the position counter under the rule stays lightened.
            LightText(
                text = "${liveWpm.roundToInt()} WPM",
                variant = LightTextVariant.Superfine,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 4f.designVerticalPxToDp()),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(3f.designVerticalPxToDp())
                .background(colors.rule),
        ) {
            if (percent > 0f) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(percent / 100f)
                        .background(colors.content),
                )
            }
        }
        LightText(
            text = "$index / $total  •  ${"%.1f".format(percent)}%",
            // Superfine, not Micro: the scale is defined against a 600px-tall design, and on the
            // LP3's 472dp panel Micro resolves to about 6sp - behind matte glass that is a smudge.
            variant = LightTextVariant.Superfine,
            lighten = true,
            align = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4f.designVerticalPxToDp()),
        )
    }
}
