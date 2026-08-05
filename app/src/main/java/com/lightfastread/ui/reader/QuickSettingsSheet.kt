package com.lightfastread.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lightfastread.data.Fonts
import com.lightfastread.data.SettingsRepository
import com.lightfastread.data.SwipeMode
import com.gios.light.common.hw.WheelInDialog
import com.gios.light.common.hw.WheelScroll
import com.lightfastread.ui.light.LightIcon
import com.lightfastread.ui.light.LightIcons
import com.lightfastread.ui.light.LightRule
import com.lightfastread.ui.light.LightText
import com.lightfastread.ui.light.LightTextVariant
import com.lightfastread.ui.light.LightThemeTokens
import com.lightfastread.ui.light.designVerticalPxToDp
import com.lightfastread.ui.light.gridUnitsAsDp
import com.lightfastread.ui.light.lightClickable
import com.lightfastread.ui.light.lightInset
import com.lightfastread.ui.light.lightTextStyle
import kotlin.math.roundToInt

/**
 * The handful of settings worth changing without leaving the book.
 *
 * [ModalBottomSheet] stays as the container — it is its own window, which is what lets the wheel
 * keep working via [WheelInDialog] — but it is flattened to the Light palette: square corners, a
 * pure background and a hairline handle instead of Material's tonal pill.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickSettingsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository.get(context) }
    val settings by settingsRepo.state
    val colors = LightThemeTokens.colors
    val maxSheetHeight = LocalConfiguration.current.screenHeightDp.dp / 2
    val scroll = rememberScrollState()
    WheelScroll(scroll)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.background,
        contentColor = colors.content,
        shape = RectangleShape,
        dragHandle = { SheetHandle() },
    ) {
        // ModalBottomSheet is a dialog underneath, and a dialog is its own
        // window. Without this the wheel goes dead while the sheet is open.
        WheelInDialog()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .verticalScroll(scroll)
                .padding(horizontal = lightInset()),
        ) {
            SheetSection("Text")
            SheetSliderRow(
                label = "Font size",
                valueText = "${settings.fontSizeSp} sp",
                value = settings.fontSizeSp.toFloat(),
                range = 16f..160f,
                onValueChange = { v ->
                    settingsRepo.update { it.copy(fontSizeSp = v.roundToInt().coerceIn(16, 160)) }
                },
            )
            Spacer(Modifier.height(8f.designVerticalPxToDp()))
            LightText("Font family", LightTextVariant.Copy)
            Spacer(Modifier.height(6f.designVerticalPxToDp()))
            FontFamilyPicker(
                selectedKey = settings.fontFamily,
                onSelected = { key ->
                    settingsRepo.update { it.copy(fontFamily = key) }
                }
            )

            SheetSection("Context")
            SheetSliderRow(
                label = "Context font size",
                valueText = "${settings.contextFontSizeSp} sp",
                value = settings.contextFontSizeSp.toFloat(),
                range = 8f..48f,
                onValueChange = { v ->
                    settingsRepo.update { it.copy(contextFontSizeSp = v.roundToInt().coerceIn(8, 48)) }
                },
            )
            SheetSliderRow(
                label = "Context transparency",
                valueText = "${"%.0f".format((1f - settings.contextAlpha) * 100)}%",
                value = settings.contextAlpha,
                range = 0.05f..1f,
                onValueChange = { v ->
                    settingsRepo.update { it.copy(contextAlpha = v.coerceIn(0.05f, 1f)) }
                },
            )

            SheetSection("Swipe band mode")
            SwipeModePicker(
                selected = settings.swipeMode,
                onSelected = { mode ->
                    settingsRepo.update { it.copy(swipeMode = mode) }
                }
            )

            // Also here, not just in Settings: the guides are the fastest way to
            // re-find a zone boundary mid-book, and leaving the reader to flip
            // them defeats the point.
            Spacer(Modifier.height(16f.designVerticalPxToDp()))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable {
                        settingsRepo.update { it.copy(showZoneGuides = !settings.showZoneGuides) }
                    }
                    .padding(vertical = 8f.designVerticalPxToDp()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LightText("Show zone guides", LightTextVariant.Copy, modifier = Modifier.weight(1f))
                LightIcon(
                    icon = if (settings.showZoneGuides) LightIcons.SelectOn else LightIcons.SelectOff,
                    contentDescription = "Show zone guides",
                )
            }
            Spacer(Modifier.height(1f.gridUnitsAsDp()))
        }
    }
}

/** Enough of a mark to say the sheet can be dragged away, and no more than that. */
@Composable
private fun SheetHandle() {
    val colors = LightThemeTokens.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10f.designVerticalPxToDp()),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(3f.gridUnitsAsDp())
                .height(3f.designVerticalPxToDp())
                .background(colors.contentFaint),
        )
    }
}

@Composable
private fun SheetSection(title: String) {
    Spacer(Modifier.height(16f.designVerticalPxToDp()))
    LightText(
        text = title,
        variant = LightTextVariant.Subheading,
        modifier = Modifier.padding(bottom = 6f.designVerticalPxToDp()),
    )
    LightRule()
    Spacer(Modifier.height(6f.designVerticalPxToDp()))
}

/**
 * The slider survives the move to the Light palette because the alternative for font size is a
 * numeric stepper, and mid-book nobody wants to tap a plus sign forty times. Two shades, no ticks.
 */
@Composable
private fun SheetSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    val colors = LightThemeTokens.colors
    Column(modifier = Modifier.padding(vertical = 4f.designVerticalPxToDp())) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            LightText(label, LightTextVariant.Copy)
            LightText(valueText, LightTextVariant.Superfine)
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = colors.content,
                activeTrackColor = colors.content,
                inactiveTrackColor = colors.rule,
            ),
        )
    }
}

@Composable
private fun SwipeModePicker(
    selected: SwipeMode,
    onSelected: (SwipeMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
    ) {
        SwipeMode.values().forEach { mode ->
            val isSelected = selected == mode
            val label = when (mode) {
                SwipeMode.Normal -> "Normal swipe"
                SwipeMode.Zone -> "Zone swipe"
            }
            LightText(
                // Brackets rather than a filled chip: the panel is matte greyscale, where a
                // change of shade or a tint behind the words does not read at a glance.
                text = if (isSelected) "[ $label ]" else label,
                variant = LightTextVariant.Button,
                lighten = !isSelected,
                modifier = Modifier
                    .lightClickable { onSelected(mode) }
                    .padding(vertical = 6f.designVerticalPxToDp()),
            )
        }
    }
}

@Composable
private fun FontFamilyPicker(
    selectedKey: String,
    onSelected: (String) -> Unit,
) {
    val colors = LightThemeTokens.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
    ) {
        Fonts.all.forEach { face ->
            val isSelected = selectedKey == face.key
            // Each face names itself in its own typeface, which is the one thing LightText cannot
            // express: the variant carries size and tracking, the face overrides only the family.
            Text(
                text = if (isSelected) "[ ${face.displayName} ]" else face.displayName,
                style = lightTextStyle(LightTextVariant.Button).copy(fontFamily = face.family),
                color = if (isSelected) colors.content else colors.contentSecondary,
                modifier = Modifier
                    .lightClickable { onSelected(face.key) }
                    .padding(vertical = 6f.designVerticalPxToDp()),
            )
        }
    }
}
