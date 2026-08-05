package com.lightfastread.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import com.lightfastread.ui.light.LightIcon
import com.lightfastread.ui.light.LightIconSpec
import com.lightfastread.ui.light.LightIcons
import com.lightfastread.ui.light.LightRule
import com.lightfastread.ui.light.LightText
import com.lightfastread.ui.light.LightTextVariant
import com.lightfastread.ui.light.LightThemeTokens
import com.lightfastread.ui.light.designVerticalPxToDp
import com.lightfastread.ui.light.gridUnitsAsDp
import com.lightfastread.ui.light.lightClickable

/**
 * The chapter you are in, and — once the bar above it is open — the position controls.
 *
 * Always on screen, which is why it is set in the small end of the scale and lightened: it has to
 * be legible without being the thing you look at. Opaque like the bar above it; a 70%-alpha black
 * over black was doing nothing but costing a blend.
 */
@Composable
fun ChapterStrip(
    chapterTitle: String,
    expanded: Boolean,
    onChapterClick: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    isBookmarked: Boolean,
    onBookmarkTap: () -> Unit,
    onBookmarkLongPress: () -> Unit,
    showStatusBarPadding: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(LightThemeTokens.colors.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (showStatusBarPadding) Modifier.statusBarsPadding() else Modifier)
                .padding(horizontal = 0.6f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        // Hand-arbitrated rather than `lightClickable`, and unchanged from
                        // before: the reader's own gesture handler owns this whole region, so
                        // the touch has to be consumed here or a tap on the chapter title also
                        // advances words underneath.
                        if (expanded) Modifier.pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                var moved = false
                                val start = down.position
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.first()
                                    ch.consume()
                                    val dx = ch.position.x - start.x
                                    val dy = ch.position.y - start.y
                                    if (dx * dx + dy * dy > 24f * 24f) moved = true
                                    if (!ch.pressed) break
                                }
                                if (!moved) onChapterClick()
                            }
                        }
                        else Modifier
                    )
                    .padding(vertical = 10f.designVerticalPxToDp()),
                contentAlignment = Alignment.CenterStart,
            ) {
                LightText(
                    text = chapterTitle,
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (expanded) {
                StripButton(LightIcons.Back, enabled = canUndo, onClick = onUndo)
                StripButton(LightIcons.Forward, enabled = canRedo, onClick = onRedo)
                BookmarkButton(
                    isBookmarked = isBookmarked,
                    onTap = onBookmarkTap,
                    onLongPress = onBookmarkLongPress,
                )
            }
        }
        if (expanded) LightRule()
    }
}

/**
 * A disabled control recedes rather than disappearing, so the row does not reflow every time
 * you undo your way back to the start of the history.
 */
@Composable
private fun StripButton(
    icon: LightIconSpec,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(2.6f.gridUnitsAsDp())
            .lightClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LightIcon(
            icon = icon,
            size = 1.6f,
            tint = if (enabled) null else LightThemeTokens.colors.contentFaint,
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookmarkButton(
    isBookmarked: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(2.6f.gridUnitsAsDp())
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onTap,
                onLongClick = onLongPress,
            ),
        contentAlignment = Alignment.Center,
    ) {
        LightIcon(
            icon = if (isBookmarked) LightIcons.Star else LightIcons.StarOutline,
            size = 1.6f,
            contentDescription = if (isBookmarked) "Remove bookmark" else "Bookmark current word",
        )
    }
}
