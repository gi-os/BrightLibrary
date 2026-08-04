package com.lightfastread.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lightfastread.data.Chapter
import com.gios.light.common.hw.WheelScroll

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
        CustomBottomSheet(onDismiss = onDismiss) {
            Text(
                "Bookmarks",
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "No bookmarks yet. Tap the bookmark icon to save the current word.",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }
        return
    }
    val listState = rememberLazyListState()
    WheelScroll(listState)
    CustomBottomSheet(onDismiss = onDismiss) {
        Text(
            "Bookmarks",
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(bookmarks, key = { it }) { wordIndex ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(wordIndex) },
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 24.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
                        ) {
                            val chapter = chapterFor(chapters, wordIndex)
                            if (chapter != null) {
                                Text(
                                    chapter.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                contextPreview(words, wordIndex),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            onClick = { onDelete(wordIndex) },
                            modifier = Modifier.size(40.dp).padding(end = 8.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove bookmark",
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
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
