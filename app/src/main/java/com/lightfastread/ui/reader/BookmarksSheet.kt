package com.lightfastread.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.lightfastread.data.Chapter
import com.gios.light.common.hw.WheelScroll
import com.lightfastread.ui.light.LightBarItem
import com.lightfastread.ui.light.LightIcon
import com.lightfastread.ui.light.LightIcons
import com.lightfastread.ui.light.LightRule
import com.lightfastread.ui.light.LightText
import com.lightfastread.ui.light.LightTextVariant
import com.lightfastread.ui.light.designVerticalPxToDp
import com.lightfastread.ui.light.gridUnitsAsDp
import com.lightfastread.ui.light.lightClickable
import com.lightfastread.ui.light.lightInset

@Composable
fun BookmarksSheet(
    bookmarks: List<Int>,
    words: List<String>,
    chapters: List<Chapter>,
    onPick: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    if (bookmarks.isEmpty()) {
        CustomBottomSheet(
            onDismiss = onDismiss,
            actions = listOf(LightBarItem.Text(text = "CLOSE", onClick = onDismiss)),
        ) {
            SheetTitleRow("Bookmarks")
            LightText(
                text = "No bookmarks yet. Tap the bookmark icon to save the current word.",
                variant = LightTextVariant.Copy,
                lighten = true,
                align = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = lightInset(), vertical = 16f.designVerticalPxToDp()),
            )
        }
        return
    }
    val listState = rememberLazyListState()
    WheelScroll(listState)
    CustomBottomSheet(
        onDismiss = onDismiss,
        actions = listOf(LightBarItem.Text(text = "CLOSE", onClick = onDismiss)),
    ) {
        SheetTitleRow("Bookmarks")
        LazyColumn(
            state = listState,
            // See ChapterListSheet: fill = false keeps a two-bookmark sheet short.
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
        ) {
            items(bookmarks, key = { it }) { wordIndex ->
                BookmarkRow(
                    preview = contextPreview(words, wordIndex),
                    chapterTitle = chapterFor(chapters, wordIndex)?.title,
                    onPick = { onPick(wordIndex) },
                    onDelete = { onDelete(wordIndex) },
                )
            }
        }
    }
}

@Composable
private fun BookmarkRow(
    preview: String,
    chapterTitle: String?,
    onPick: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .lightClickable(onClick = onPick)
                    .padding(start = lightInset(), end = 0.5f.gridUnitsAsDp())
                    .padding(vertical = 10f.designVerticalPxToDp()),
            ) {
                // The words are what identify a bookmark, so they take the Copy line and the
                // chapter follows as the lightened second line rather than heading the row.
                LightText(
                    text = preview,
                    variant = LightTextVariant.Copy,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (chapterTitle != null) {
                    LightText(
                        text = chapterTitle,
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .lightClickable(onClick = onDelete)
                    .padding(horizontal = lightInset(), vertical = 10f.designVerticalPxToDp()),
                contentAlignment = Alignment.Center,
            ) {
                LightIcon(
                    icon = LightIcons.Trash,
                    size = 1.5f,
                    contentDescription = "Remove bookmark",
                )
            }
        }
        LightRule()
    }
}

private fun chapterFor(chapters: List<Chapter>, wordIndex: Int): Chapter? {
    if (chapters.isEmpty()) return null
    var found: Chapter? = null
    for (c in chapters) {
        if (c.startWordIndex <= wordIndex) found = c else break
    }
    return found ?: chapters.first()
}

private fun contextPreview(words: List<String>, index: Int, before: Int = 3, after: Int = 8): String {
    if (words.isEmpty() || index < 0) return ""
    val safe = index.coerceIn(0, words.size - 1)
    val start = (safe - before).coerceAtLeast(0)
    val end = (safe + after).coerceAtMost(words.size - 1)
    val builder = StringBuilder()
    for (i in start..end) {
        if (i == safe) builder.append("[")
        builder.append(words[i])
        if (i == safe) builder.append("]")
        if (i < end) builder.append(' ')
    }
    return builder.toString()
}
