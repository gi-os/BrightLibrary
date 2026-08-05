package com.lightfastread.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.lightfastread.data.Chapter
import com.lightfastread.hw.WheelScroll
import com.lightfastread.ui.light.LightBarItem
import com.lightfastread.ui.light.LightRule
import com.lightfastread.ui.light.LightText
import com.lightfastread.ui.light.LightTextVariant
import com.lightfastread.ui.light.designVerticalPxToDp
import com.lightfastread.ui.light.lightClickable
import com.lightfastread.ui.light.lightInset

@Composable
fun ChapterListSheet(
    chapters: List<Chapter>,
    currentIndex: Int,
    onPick: (Chapter) -> Unit,
    onDismiss: () -> Unit,
) {
    if (chapters.isEmpty()) {
        CustomBottomSheet(
            onDismiss = onDismiss,
            actions = listOf(LightBarItem.Text(text = "CLOSE", onClick = onDismiss)),
        ) {
            LightText(
                text = "No chapters detected for this book.",
                variant = LightTextVariant.Copy,
                lighten = true,
                align = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = lightInset(), vertical = 24f.designVerticalPxToDp()),
            )
        }
        return
    }
    val currentChapterIdx = chapters.indexOfLast { it.startWordIndex <= currentIndex }
        .coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentChapterIdx)
    WheelScroll(listState)
    CustomBottomSheet(
        onDismiss = onDismiss,
        actions = listOf(LightBarItem.Text(text = "CLOSE", onClick = onDismiss)),
    ) {
        SheetTitleRow("Chapters")
        LazyColumn(
            state = listState,
            // fill = false so the sheet still wraps a short table of contents; without it the
            // list would claim every pixel the height cap allows and the actions row would end
            // up under a mostly empty sheet.
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
        ) {
            itemsIndexed(chapters) { idx, chapter ->
                ChapterRow(
                    title = chapter.title,
                    isCurrent = idx == currentChapterIdx,
                    onClick = { onPick(chapter) },
                )
            }
        }
    }
}

@Composable
private fun ChapterRow(
    title: String,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().lightClickable(onClick = onClick)) {
        LightText(
            // Where you are is bracketed rather than shaded: on the LP3's matte panel a
            // container colour a few steps off the background does not read at arm's length.
            text = if (isCurrent) "[ $title ]" else title,
            variant = LightTextVariant.Copy,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = lightInset(), vertical = 10f.designVerticalPxToDp()),
        )
        LightRule()
    }
}

/** Shared with [BookmarksSheet] so both sheets head themselves the same way. */
@Composable
internal fun SheetTitleRow(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Subheading,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = lightInset())
            .padding(top = 4f.designVerticalPxToDp(), bottom = 8f.designVerticalPxToDp()),
    )
    LightRule()
}
