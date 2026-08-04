package com.lightfastread.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lightfastread.data.Chapter
import com.gios.light.common.hw.WheelScroll

@Composable
fun ChapterListSheet(
    chapters: List<Chapter>,
    currentIndex: Int,
    onPick: (Chapter) -> Unit,
    onDismiss: () -> Unit,
) {
    if (chapters.isEmpty()) {
        CustomBottomSheet(onDismiss = onDismiss) {
            Text(
                "No chapters detected for this book.",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
        }
        return
    }
    val currentChapterIdx = chapters.indexOfLast { it.startWordIndex <= currentIndex }
        .coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentChapterIdx)
    WheelScroll(listState)
    CustomBottomSheet(onDismiss = onDismiss) {
        Text(
            "Chapters",
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(chapters) { idx, chapter ->
                val isCurrent = idx == currentChapterIdx
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(chapter) },
                    color = if (isCurrent)
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
                ) {
                    Text(
                        chapter.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
