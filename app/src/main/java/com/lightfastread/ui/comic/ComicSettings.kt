package com.lightfastread.ui.comic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gios.light.common.hw.WheelInDialog
import com.gios.light.common.hw.WheelScroll
import com.lightfastread.ui.light.LightBarItem
import com.lightfastread.ui.light.LightBottomBar
import com.lightfastread.ui.light.LightIcon
import com.lightfastread.ui.light.LightIcons
import com.lightfastread.ui.light.LightRule
import com.lightfastread.ui.light.LightText
import com.lightfastread.ui.light.LightTextVariant
import com.lightfastread.ui.light.LightThemeTokens
import com.lightfastread.ui.light.LightTopBar
import com.lightfastread.ui.light.designVerticalPxToDp
import com.lightfastread.ui.light.gridUnitsAsDp
import com.lightfastread.ui.light.lightClickable
import com.lightfastread.ui.light.lightInset

/** Everything the comic reader can be told, in the order it matters while reading. */
internal data class ComicOptions(
    val fitWidth: Boolean,
    val crop: Boolean,
    val fourKoma: Boolean,
    val tapToTurn: Boolean,
)

/**
 * The reader's own settings, reachable without leaving the book.
 *
 * A page turn is not a thing you want to hunt for in the app's Settings screen while holding a book
 * open — these four options change how the page in front of you behaves, so they belong on top of it.
 * Every one of them is written straight through to the saved settings, so the next book opens the way
 * this one ended.
 *
 * A full-screen page rather than a floating sheet: LightOS has no dialogs, and on a black panel a
 * Material sheet is a grey slab with a scrim that tints nothing.
 */
@Composable
internal fun ComicSettings(
    options: ComicOptions,
    onChange: (ComicOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LightThemeTokens.colors
    val scroll = rememberScrollState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Its own window, so the wheel has to be picked up again here or the list cannot be scrolled.
        WheelInDialog()
        WheelScroll(scroll)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .statusBarsPadding(),
        ) {
            LightTopBar(
                title = "Reading",
                left = LightBarItem.Icon(LightIcons.Back, onClick = onDismiss),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scroll)
                    .padding(horizontal = lightInset()),
            ) {
                Toggle(
                    label = "Fit to width",
                    caption = "Fill the screen's width and scroll down the page. Off shows the whole " +
                        "page at once, which on this screen means unreadable lettering.",
                    checked = options.fitWidth,
                    onToggle = { onChange(options.copy(fitWidth = it)) },
                )
                Toggle(
                    label = "Crop white borders",
                    caption = "Trim the paper margin off a scan before fitting it. A scan framed in " +
                        "black is left alone — in comics, black is the drawing.",
                    checked = options.crop,
                    onToggle = { onChange(options.copy(crop = it)) },
                )
                Toggle(
                    label = "4-koma mode",
                    caption = "Read the two strips printed side by side as separate pages — the right " +
                        "one first — each taken in four scrolls. Remembered for this series only: it " +
                        "is a fact about the book, not a preference.",
                    checked = options.fourKoma,
                    onToggle = { onChange(options.copy(fourKoma = it)) },
                )
                Toggle(
                    label = "Tap the edges to turn",
                    caption = "Off means a tap anywhere opens this menu instead, and pages turn with " +
                        "the wheel or a swipe — which is what you want if you keep turning pages by " +
                        "accident.",
                    checked = options.tapToTurn,
                    onToggle = { onChange(options.copy(tapToTurn = it)) },
                )
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
                LightText(
                    text = "The wheel scrolls a step at a time and turns the page when the page runs " +
                        "out. Pinch to zoom, double tap to fit again.",
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                )
                Spacer(Modifier.height(2f.gridUnitsAsDp()))
            }
            LightRule()
            LightBottomBar(
                items = listOf(LightBarItem.Text(text = "DONE", onClick = onDismiss)),
            )
        }
    }
}

@Composable
private fun Toggle(
    label: String,
    caption: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().lightClickable { onToggle(!checked) }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10f.designVerticalPxToDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                LightText(label, LightTextVariant.Copy)
                LightText(caption, LightTextVariant.Superfine, lighten = true)
            }
            Spacer(Modifier.width(1f.gridUnitsAsDp()))
            LightIcon(
                icon = if (checked) LightIcons.SelectOn else LightIcons.SelectOff,
                contentDescription = label,
            )
        }
        LightRule()
    }
}
