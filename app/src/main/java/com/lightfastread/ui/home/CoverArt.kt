package com.lightfastread.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.lightfastread.data.Book
import com.lightfastread.data.Covers
import com.lightfastread.ui.light.LightRule
import com.lightfastread.ui.light.LightText
import com.lightfastread.ui.light.LightTextVariant
import com.lightfastread.ui.light.LightThemeTokens
import com.lightfastread.ui.light.designVerticalPxToDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A book's cover, at whatever size the shelf gives it.
 *
 * Real art when the book has any — in full colour, since the shelf lifts the phone's greyscale
 * while it is up (see `ui/light/ColorMode.kt`). A book with no art gets a typographic cover
 * instead of a placeholder glyph: on a monochrome shelf, a grid of identical book icons tells you
 * nothing, while the title set large is the one thing that does.
 *
 * Decoding happens off the main thread. A shelf scrolled quickly composes and disposes cells
 * faster than a JPEG decodes, and doing it inline dropped frames on the LP3.
 */
@Composable
fun BookCover(book: Book, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = LightThemeTokens.colors
    var image by remember(book.id) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(book.id, book.coverFileName, book.coverUpdatedAtMs) {
        image = if (book.coverFileName == null) {
            null
        } else {
            withContext(Dispatchers.IO) { Covers.load(context, book) }
        }
    }

    Box(
        modifier.border(1f.designVerticalPxToDp(), colors.rule),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            TypographicCover(book)
        }
    }
}

/**
 * The fallback cover: the title, set as large as it will go, over the author.
 *
 * Deliberately not centred vertically — text sitting slightly high with the author beneath it
 * reads as a cover, while text in the exact middle of a rectangle reads as an error message.
 */
@Composable
private fun TypographicCover(book: Book) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                horizontal = 10f.designVerticalPxToDp(),
                vertical = 12f.designVerticalPxToDp(),
            ),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        LightText(
            text = book.title,
            variant = LightTextVariant.Paragraph,
            align = TextAlign.Start,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Column {
            LightRule()
            Spacer(Modifier.height(6f.designVerticalPxToDp()))
            LightText(
                text = book.author.ifBlank { book.format },
                variant = LightTextVariant.Superfine,
                lighten = true,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
