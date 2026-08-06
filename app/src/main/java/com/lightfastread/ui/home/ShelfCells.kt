package com.lightfastread.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lightfastread.data.Book
import com.lightfastread.data.ShelfEntry
import com.lightfastread.ui.light.LightText
import com.lightfastread.ui.light.LightTextVariant
import com.lightfastread.ui.light.LightThemeTokens
import com.lightfastread.ui.light.designVerticalPxToDp
import com.lightfastread.ui.light.lightClickable
import com.lightfastread.ui.light.lightCombinedClickable

/**
 * The two things a shelf cell can be: a book, and a stack of them.
 *
 * Shared by the shelf and by the series screen it opens, which is the whole reason this is its own
 * file — a series should look exactly like the shelf it came from, because it *is* one.
 */

/** Two to a row, as on the main shelf. Anything more and the art stops being worth showing. */
internal const val COLUMNS = 2

/** Book covers are 2:3, near enough to every trade paperback ever printed. */
internal const val COVER_ASPECT = 2f / 3f

@Composable
internal fun ShelfBook(
    book: Book,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val progress = if (book.totalWords > 0) {
        (book.currentWordIndex.toFloat() / book.totalWords).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightCombinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        BookCover(
            book = book,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(COVER_ASPECT),
        )
        // Progress as a rule directly under the cover: the shelf's one piece of state, and the
        // cheapest possible way to draw it. A Material progress bar would put a grey slab here.
        ProgressRule(progress)
        Spacer(Modifier.height(6f.designVerticalPxToDp()))
        // One line each, deliberately. The LP3 is only ~472dp tall, and a cell of a 2:3 cover plus
        // two lines of title is taller than the shelf — so nothing of the next row would show and
        // the shelf would read as holding exactly two books.
        LightText(
            text = book.title,
            variant = LightTextVariant.Detail,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (book.author.isNotBlank()) {
            LightText(
                text = book.author,
                variant = LightTextVariant.Superfine,
                lighten = true,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A series, as one cell.
 *
 * Drawn as the first volume's cover with two hairline edges peeking out behind it — the way a stack
 * of books looks from the front. No badge, no count bubble: LightOS has neither, and on a matte
 * greyscale panel two offset rules read as depth at arm's length where a tinted chip would not. The
 * count is said in words underneath, where the author normally goes.
 */
@Composable
internal fun ShelfStack(
    stack: ShelfEntry.Stack,
    onClick: () -> Unit,
) {
    val colors = LightThemeTokens.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(COVER_ASPECT),
        ) {
            // The edges of the volumes behind, offset down and to the right. Outlines only: a filled
            // shape here would read as a second cover rather than as the side of one.
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(start = STACK_OFFSET * 2, top = STACK_OFFSET * 2)
                    .border(Dp.Hairline, colors.rule),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(start = STACK_OFFSET, top = STACK_OFFSET)
                    .border(Dp.Hairline, colors.rule)
                    .background(colors.background),
            )
            BookCover(
                book = stack.cover,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = STACK_OFFSET * 2, bottom = STACK_OFFSET * 2),
            )
        }
        ProgressRule(stack.progress)
        Spacer(Modifier.height(6f.designVerticalPxToDp()))
        LightText(
            text = stack.series,
            variant = LightTextVariant.Detail,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LightText(
            text = if (stack.started > 0) {
                "${stack.books.size} books · ${stack.started} started"
            } else {
                "${stack.books.size} books"
            },
            variant = LightTextVariant.Superfine,
            lighten = true,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** How far the volumes behind peek out. Two design pixels either side of a hairline, no more. */
private val STACK_OFFSET = 3.dp

@Composable
internal fun ProgressRule(progress: Float) {
    val colors = LightThemeTokens.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(3f.designVerticalPxToDp())
            .background(colors.rule),
    ) {
        if (progress > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .background(colors.content),
            )
        }
    }
}
