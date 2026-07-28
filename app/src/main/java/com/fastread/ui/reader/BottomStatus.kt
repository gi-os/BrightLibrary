package com.fastread.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fastread.ui.theme.LocalIsLightPhone
import com.fastread.ui.theme.LpContrast
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
    val lp = LocalIsLightPhone.current
    val readoutAlpha = if (lp) LpContrast.floor(0.7f, 0.85f) else 0.7f
    Column(modifier = modifier) {
        if (isHolding && liveWpm > 0.5f) {
            Text(
                "${liveWpm.roundToInt()} WPM",
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "$index / $total  •  ${"%.1f".format(percent)}%",
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = readoutAlpha),
        )
    }
}
