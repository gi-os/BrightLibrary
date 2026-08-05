package com.lightfastread.ui.light

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp

/**
 * Top and bottom bars in the LightOS idiom, ported from `lightphone/light-sdk` (MIT).
 *
 * Both bars are sized in grid units — 3 units tall at the top, 4 at the bottom — and both
 * take at most a left item, a centre and a right item. Text items are set in the Button
 * variant, which is tracked out wide and reads as a label rather than as prose.
 *
 * The SDK's own limit is five icon items, but **three if any item is text**. The shelf sits at
 * exactly three (ADD / LIBRARY / SETTINGS), so anything else it has to offer has to be an icon.
 */

private const val TOPBAR_HEIGHT_UNITS = 3f
private const val BOTTOMBAR_HEIGHT_UNITS = 4f
private const val HORIZONTAL_PADDING_UNITS = 1f
private const val CENTER_MAX_WIDTH_UNITS = 18f

sealed interface LightBarItem {
    val onClick: (() -> Unit)?

    data class Text(
        val text: String,
        val active: Boolean = false,
        val lighten: Boolean = false,
        override val onClick: (() -> Unit)?,
    ) : LightBarItem

    data class Icon(
        val icon: LightIconSpec,
        val sizeUnits: Float = 2f,
        val active: Boolean = false,
        val lighten: Boolean = false,
        override val onClick: (() -> Unit)?,
    ) : LightBarItem
}

@Composable
fun LightTopBar(
    title: String? = null,
    left: LightBarItem? = null,
    right: LightBarItem? = null,
    modifier: Modifier = Modifier,
) {
    val barHeight = TOPBAR_HEIGHT_UNITS.gridUnitsAsDp()
    Box(
        modifier
            .fillMaxWidth()
            .height(barHeight)
            .padding(horizontal = HORIZONTAL_PADDING_UNITS.gridUnitsAsDp()),
    ) {
        Row(
            Modifier.fillMaxWidth().height(barHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.height(barHeight), contentAlignment = Alignment.CenterStart) {
                BarItemView(left, barHeight)
            }
            Box(Modifier.weight(1f))
            Box(Modifier.height(barHeight), contentAlignment = Alignment.CenterEnd) {
                BarItemView(right, barHeight)
            }
        }
        if (title != null) {
            Box(
                Modifier.fillMaxWidth().height(barHeight),
                contentAlignment = Alignment.Center,
            ) {
                LightText(
                    text = title,
                    variant = LightTextVariant.Fine,
                    align = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = CENTER_MAX_WIDTH_UNITS.gridUnitsAsDp()),
                )
            }
        }
    }
}

/** LightOS's action bar. */
@Composable
fun LightBottomBar(items: List<LightBarItem?>, modifier: Modifier = Modifier) {
    require(items.size <= 5) { "LightBottomBar supports at most 5 items" }
    val barHeight = BOTTOMBAR_HEIGHT_UNITS.gridUnitsAsDp()
    Row(
        modifier
            .fillMaxWidth()
            .height(barHeight)
            .padding(horizontal = HORIZONTAL_PADDING_UNITS.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (items.size) {
            0 -> Unit
            1 -> Box(Modifier.fillMaxWidth(), Alignment.Center) { BarItemView(items[0], barHeight) }
            else -> items.forEachIndexed { i, item ->
                val align = when (i) {
                    0 -> Alignment.CenterStart
                    items.lastIndex -> Alignment.CenterEnd
                    else -> Alignment.Center
                }
                BarSlot(align) { BarItemView(item, barHeight) }
            }
        }
    }
}

@Composable
private fun RowScope.BarSlot(align: Alignment, content: @Composable () -> Unit) {
    Box(Modifier.weight(1f), contentAlignment = align) { content() }
}

@Composable
private fun BarItemView(item: LightBarItem?, barHeight: Dp) {
    when (item) {
        null -> LightIcon(LightIcons.Spacer, contentDescription = null)
        is LightBarItem.Text -> Box(
            Modifier
                .let { m -> item.onClick?.let { m.lightClickable(onClick = it) } ?: m }
                .height(barHeight),
            contentAlignment = Alignment.Center,
        ) {
            // The active item is bracketed as well as brightened: on a matte greyscale panel,
            // a change of shade alone does not read at arm's length.
            LightText(
                text = if (item.active) "[ ${item.text} ]" else item.text,
                variant = LightTextVariant.Button,
                lighten = item.lighten && !item.active,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        is LightBarItem.Icon -> Column(
            modifier = Modifier
                .let { m -> item.onClick?.let { m.lightClickable(onClick = it) } ?: m }
                .height(barHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            LightIcon(
                icon = item.icon,
                size = item.sizeUnits,
                tint = if (item.lighten && !item.active) {
                    LightThemeTokens.colors.contentSecondary
                } else {
                    null
                },
            )
            if (item.active) {
                Spacer(Modifier.height(0.35f.verticalGridUnitsAsDp()))
                Box(
                    Modifier
                        .width(item.sizeUnits.gridUnitsAsDp())
                        .height(3f.designVerticalPxToDp())
                        .background(LightThemeTokens.colors.content),
                )
            }
        }
    }
}

/** A hairline, two design pixels thick like the SDK's own underlines. */
@Composable
fun LightRule(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(2f.designVerticalPxToDp())
            .background(LightThemeTokens.colors.rule),
    )
}

/** The app's standard horizontal inset: one grid unit, as everywhere in LightOS. */
@Composable
fun lightInset(): Dp = HORIZONTAL_PADDING_UNITS.gridUnitsAsDp()
