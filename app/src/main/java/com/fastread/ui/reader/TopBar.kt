package com.fastread.ui.reader

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fastread.ui.theme.LocalIsLightPhone
import com.fastread.ui.theme.lpBottomEdge

@Composable
fun TopBar(
    title: String,
    onBack: () -> Unit,
    onQuickSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // On the Light Phone scheme the surface is pure black and tonal elevation
    // is a no-op, so the bar needs an explicit edge to read as a bar. Keep it
    // fully opaque there too - a 95%-alpha black over black text is just haze.
    val lp = LocalIsLightPhone.current
    Surface(
        modifier = modifier.fillMaxWidth().lpBottomEdge(),
        color = if (lp) MaterialTheme.colorScheme.surface
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = if (lp) 0.dp else 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .statusBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text(
                title,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
            IconButton(onClick = onQuickSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Text settings")
            }
        }
    }
}
