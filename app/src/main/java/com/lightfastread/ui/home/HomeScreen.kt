package com.lightfastread.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.lightfastread.data.Book
import com.lightfastread.data.BookRepository
import com.lightfastread.data.Covers
import com.lightfastread.data.SettingsRepository
import com.gios.light.common.hw.WheelScroll
import com.lightfastread.parser.BookParser
import com.lightfastread.ui.light.ColourEffect
import com.lightfastread.ui.light.LightBarItem
import com.lightfastread.ui.light.LightBottomBar
import com.lightfastread.ui.light.LightRule
import com.lightfastread.ui.light.LightText
import com.lightfastread.ui.light.LightTextVariant
import com.lightfastread.ui.light.LightThemeTokens
import com.lightfastread.ui.light.LightTopBar
import com.lightfastread.ui.light.designVerticalPxToDp
import com.lightfastread.ui.light.gridUnitsAsDp
import com.lightfastread.ui.light.lightClickable
import com.lightfastread.ui.light.lightCombinedClickable
import com.lightfastread.ui.light.lightInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The shelf.
 *
 * Two covers to a row, which on the LP3's 411dp width gives each book about 180dp — big enough
 * that real cover art is worth looking at, and the reason the shelf lifts the phone's forced
 * greyscale while it is on screen. Everything else here is white on black in the SDK's own grid
 * and type scale; the covers are the only thing with hues in them, which is exactly how LightOS
 * treats photographs.
 *
 * Add, Library and Settings live in a [LightBottomBar] rather than in a floating action button and a
 * top bar icon. Three text items, which is exactly the SDK's limit once any item is text — so a
 * fourth thing to do from the shelf has to become an icon or move somewhere else.
 */
@Composable
fun HomeScreen(
    onOpenBook: (Book) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { BookRepository.get(context) }
    val settings by remember { SettingsRepository.get(context) }.state
    val books = repo.books
    val colors = LightThemeTokens.colors

    var importing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var actionsFor by remember { mutableStateOf<Book?>(null) }
    var renaming by remember { mutableStateOf<Book?>(null) }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    WheelScroll(gridState)

    // Covers in colour for as long as the shelf is up, and only the shelf: opening a book drops
    // back to greyscale, so reading is as monochrome as the rest of the phone.
    ColourEffect(enabled = settings.colorCovers)

    /**
     * Ask Open Library for art the file didn't carry.
     *
     * Fired after the import finishes rather than during it, so a book is on the shelf the moment
     * its text is parsed instead of waiting on a network round trip that may never come back.
     *
     * Either outcome is recorded. A search that found nothing used to leave no trace at all, so the
     * book sat there coverless and nothing ever asked again — see [Book.coverSearchedAtMs] and the
     * sweep below.
     */
    val fetchCover: (Book) -> Unit = { book ->
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                Covers.fetchFromOpenLibrary(book.title, book.author)
            }
            if (bytes != null) {
                withContext(Dispatchers.IO) { repo.setCover(book.id, bytes) }
                status = null
            } else {
                repo.markCoverSearched(book.id)
                status = "No cover found for “${book.title}” — long-press it to fix the name"
            }
        }
    }

    /**
     * Look again for the covers that are still missing.
     *
     * The only thing that used to trigger a lookup was the import itself, in a coroutine tied to
     * this screen — so a book imported with no signal, or one whose shelf was left before the
     * request came back, never got a second chance. That is the whole of "the covers sometimes
     * never search".
     *
     * Runs whenever the shelf appears, one book at a time so a library of thirty does not open
     * thirty sockets, and skips anything asked about within [COVER_RETRY_MS]. Cancellation is
     * fine and expected: opening a book stops the sweep, and returning to the shelf resumes it.
     */
    LaunchedEffect(Unit) {
        val cutoff = System.currentTimeMillis() - COVER_RETRY_MS
        val pending = books.filter { it.coverFileName == null && it.coverSearchedAtMs < cutoff }
        if (pending.isEmpty()) return@LaunchedEffect
        status = if (pending.size == 1) "Looking for a cover…" else "Looking for ${pending.size} covers…"
        var found = 0
        for (book in pending) {
            val bytes = withContext(Dispatchers.IO) {
                Covers.fetchFromOpenLibrary(book.title, book.author)
            }
            if (bytes != null && withContext(Dispatchers.IO) { repo.setCover(book.id, bytes) }) {
                found++
            } else {
                repo.markCoverSearched(book.id)
            }
        }
        status = when {
            found == pending.size -> null
            found > 0 -> "Found $found of ${pending.size}. Long-press a book to fix its name."
            else -> "No covers found. Long-press a book to fix its name and search again."
        }
    }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        status = null
        scope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) { BookParser.parse(context, uri) }
                val words = BookRepository.countWords(parsed.text)
                if (words == 0) throw IllegalStateException("No readable text found in file")
                val id = UUID.randomUUID().toString()
                val rawPairs = parsed.chapters.map { it.title to it.charOffset }
                val chapters = repo.chaptersFromCharOffsets(parsed.text, rawPairs)
                val book = Book(
                    id = id,
                    title = parsed.title.ifBlank { "Untitled" },
                    author = parsed.author,
                    format = parsed.format,
                    textFileName = "$id.txt",
                    totalWords = words,
                    currentWordIndex = 0,
                    chapters = chapters,
                )
                withContext(Dispatchers.IO) { repo.addBook(book, parsed.text) }

                // The file's own art first — it is the publisher's cover, already in hand.
                val embedded = parsed.coverImage?.takeIf { Covers.looksLikeImage(it) }
                val stored = embedded != null &&
                    withContext(Dispatchers.IO) { repo.setCover(id, embedded) }
                importing = false
                if (!stored) fetchCover(book)
            } catch (e: Exception) {
                importing = false
                status = e.message ?: "Failed to import"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding(),
    ) {
        LightTopBar(title = "Books")

        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (books.isEmpty() && !importing) {
                EmptyShelf(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(COLUMNS),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = lightInset(),
                        end = lightInset(),
                        top = 0.5f.gridUnitsAsDp(),
                        bottom = 1f.gridUnitsAsDp(),
                    ),
                    horizontalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
                    verticalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
                ) {
                    items(books, key = { it.id }) { book ->
                        ShelfBook(
                            book = book,
                            onClick = { onOpenBook(book) },
                            onLongClick = { actionsFor = book },
                        )
                    }
                }
            }
            if (importing) {
                Box(
                    modifier = Modifier.fillMaxSize().background(colors.background),
                    contentAlignment = Alignment.Center,
                ) {
                    LightText("Importing…", LightTextVariant.Copy)
                }
            }
        }

        status?.let { message ->
            Column(Modifier.lightClickable { status = null }) {
                LightRule()
                LightText(
                    text = message,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = lightInset(), vertical = 8f.designVerticalPxToDp()),
                )
            }
        }

        LightRule()
        LightBottomBar(
            items = listOf(
                LightBarItem.Text(
                    text = "ADD",
                    onClick = {
                        pickFile.launch(
                            arrayOf(
                                "application/epub+zip",
                                "application/x-mobipocket-ebook",
                                "application/octet-stream",
                                "*/*",
                            )
                        )
                    },
                ),
                // Three text items is the SDK's hard limit once any item is text, so this bar is now
                // full: ADD from storage, LIBRARY from the Calibre server, SETTINGS.
                LightBarItem.Text(text = "LIBRARY", onClick = onOpenLibrary),
                LightBarItem.Text(text = "SETTINGS", onClick = onOpenSettings),
            ),
            modifier = Modifier.navigationBarsPadding(),
        )
    }

    actionsFor?.let { book ->
        BookActions(
            book = book,
            onFindCover = {
                actionsFor = null
                status = "Looking for a cover…"
                fetchCover(book)
            },
            onRename = {
                actionsFor = null
                renaming = book
            },
            onDelete = {
                actionsFor = null
                repo.deleteBook(book)
            },
            onDismiss = { actionsFor = null },
        )
    }

    renaming?.let { book ->
        RenameBook(
            book = book,
            onSave = { title, author ->
                renaming = null
                repo.rename(book.id, title, author)
                // Search with what was typed rather than with `book`, which is the pre-edit copy —
                // a data class captured before the rename would send the old title straight back to
                // the catalogue and look like the fix had done nothing.
                status = "Looking for a cover…"
                fetchCover(book.copy(title = title.trim(), author = author.trim()))
            },
            onDismiss = { renaming = null },
        )
    }
}

/**
 * How long a failed cover search is left alone.
 *
 * Long enough that opening the shelf repeatedly does not hammer Open Library with a question it has
 * already declined to answer, short enough that a book imported on a train has its cover by the
 * next day. A rename clears the timestamp outright, since a new name is a new question.
 */
private const val COVER_RETRY_MS = 12L * 60 * 60 * 1000

/** Two to a row, as asked. Anything more and the art stops being worth showing. */
private const val COLUMNS = 2

/** Book covers are 2:3, near enough to every trade paperback ever printed. */
private const val COVER_ASPECT = 2f / 3f

@Composable
private fun ShelfBook(
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
        // the shelf would read as holding exactly two books. The cover is the label anyway; this is
        // the caption under it.
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

@Composable
private fun ProgressRule(progress: Float) {
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

@Composable
private fun EmptyShelf(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = lightInset()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LightText("No books yet", LightTextVariant.Subheading, align = TextAlign.Center)
        Spacer(Modifier.height(10f.designVerticalPxToDp()))
        LightText(
            text = "Add an EPUB or MOBI from your phone and it will appear here, cover and all.",
            variant = LightTextVariant.Detail,
            lighten = true,
            align = TextAlign.Center,
        )
    }
}
