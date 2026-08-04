package com.lightfastread.ui.reader

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lightfastread.data.Fonts
import com.lightfastread.data.SettingsRepository
import com.lightfastread.data.SwipeMode
import com.gios.light.common.hw.WheelInDialog
import com.gios.light.common.hw.WheelScroll
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickSettingsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository.get(context) }
    val settings by settingsRepo.state
    val maxSheetHeight = LocalConfiguration.current.screenHeightDp.dp / 2
    val scroll = rememberScrollState()
    WheelScroll(scroll)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // ModalBottomSheet is a dialog underneath, and a dialog is its own
        // window. Without this the wheel goes dead while the sheet is open.
        WheelInDialog()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .verticalScroll(scroll)
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            Text("Text", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Text("Font size: ${settings.fontSizeSp} sp")
            Slider(
                value = settings.fontSizeSp.toFloat(),
                onValueChange = { v ->
                    settingsRepo.update { it.copy(fontSizeSp = v.roundToInt().coerceIn(16, 160)) }
                },
                valueRange = 16f..160f,
            )
            Spacer(Modifier.height(8.dp))
            Text("Font family")
            Spacer(Modifier.height(8.dp))
            FontFamilyPicker(
                selectedKey = settings.fontFamily,
                onSelected = { key ->
                    settingsRepo.update { it.copy(fontFamily = key) }
                }
            )

            Spacer(Modifier.height(20.dp))
            Text("Context", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("Context font size: ${settings.contextFontSizeSp} sp")
            Slider(
                value = settings.contextFontSizeSp.toFloat(),
                onValueChange = { v ->
                    settingsRepo.update { it.copy(contextFontSizeSp = v.roundToInt().coerceIn(8, 48)) }
                },
                valueRange = 8f..48f,
            )
            Spacer(Modifier.height(8.dp))
            Text("Context transparency: ${"%.0f".format((1f - settings.contextAlpha) * 100)}%")
            Slider(
                value = settings.contextAlpha,
                onValueChange = { v ->
                    settingsRepo.update { it.copy(contextAlpha = v.coerceIn(0.05f, 1f)) }
                },
                valueRange = 0.05f..1f,
            )

            Spacer(Modifier.height(20.dp))
            Text("Swipe band mode", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SwipeModePicker(
                selected = settings.swipeMode,
                onSelected = { mode ->
                    settingsRepo.update { it.copy(swipeMode = mode) }
                }
            )

            // Also here, not just in Settings: the guides are the fastest way to
            // re-find a zone boundary mid-book, and leaving the reader to flip
            // them defeats the point.
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = settings.showZoneGuides,
                    onCheckedChange = { v ->
                        settingsRepo.update { it.copy(showZoneGuides = v) }
                    },
                )
                Spacer(Modifier.width(12.dp))
                Text("Show zone guides", style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeModePicker(
    selected: SwipeMode,
    onSelected: (SwipeMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SwipeMode.values().forEach { mode ->
            FilterChip(
                selected = selected == mode,
                onClick = { onSelected(mode) },
                label = {
                    Text(
                        when (mode) {
                            SwipeMode.Normal -> "Normal swipe"
                            SwipeMode.Zone -> "Zone swipe"
                        }
                    )
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FontFamilyPicker(
    selectedKey: String,
    onSelected: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Fonts.all.forEach { face ->
            FilterChip(
                selected = selectedKey == face.key,
                onClick = { onSelected(face.key) },
                label = {
                    Text(face.displayName, fontFamily = face.family)
                },
            )
        }
    }
}
