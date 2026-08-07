package com.lightfastread.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.item
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
import com.lightfastread.data.BookKind
import com.lightfastread.data.BookRepository
import com.lightfastread.data.Covers
import com.lightfastread.data.SeriesTitle
import com.lightfastread.data.Shelf
import com.lightfastread.data.ShelfEntry
import com.lightfastread.data.Importer
import com.lightfastread.data.SettingsRepository
import com.gios.light.common.hw.WheelScroll
import com.lightfastread.ui.light.ColourEffect
import com.lightfastread.ui.light.LightBarItem
import com.lightfastread.ui.light.LightBottomBar
import com.lightfastread.ui.light.LightIcons
import com.lightfastread.ui.light.LightRule
import com.lightfastread.ui.light.LightText
import com.lightfastread.ui.light.LightTextVariant
import com.lightfastread.ui.light.LightThemeTokens
import com.lightfastread.ui.light.designVerticalPxToDp
import com.lightfastread.ui.light.gridUnitsAsDp
import com.lightfastread.ui.light.lightClickable
import com.lightfastread.ui.light.lightInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The shelf.
 *
 * Two covers to a row, which on the LP3's 411dp width gives each book about 180dp — big enough
 * that real cover art is worth looking at, and the reason the shelf lifts the phone's forced
 * greyscale while it is on screen. Everything else here is white on black in the SDK's own grid
 * and type scale; the covers are the only thing with hues in them, which is exactly how LightOS
 * treats photographs.
 *
 * **"Books" is not a fixed [LightTopBar]** — it is the grid's own first item, spanning both
 * columns, so it scrolls off with the rest of the shelf instead of sitting pinned above it. The
 * empty shelf still shows it outside the grid, since there is nothing there to scroll.
 *
 * Library, Settings and Add live in a [LightBottomBar] as icons rather than text — the SDK allows
 * five icon items but only three once any item is text, and icons are what leaves room to add a
 * fourth thing later. Add sits last, after Settings, rather than first.
 */
@Composable
fun HomeScreen(
    onOpenBook: (Book) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSeries: (String) -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { BookRepository.get(context) }
    val settings by remember { SettingsRepository.get(context) }.state
    val books = repo.books
    val colors = LightThemeTokens.colors

    // Recomputed whenever the shelf changes, which is cheap: a parse of each title and a group-by,
    // over a list that is tens of items long, not thousands.
    val entries = remember(books.toList()) { Shelf.entries(books.toList()) }
    var importing by remember { mutableStateOf(false) }
    var importStep by remember { mutableStateOf<String?>(null) }
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
                // Copied to a file before anything looks at it. A comic is a 250 MB archive that has
                // to be opened as a zip, and a `content://` stream is neither seekable nor something
                // to hold in memory — see [Importer].
                val staged = withContext(Dispatchers.IO) { Importer.cacheCopy(context, uri) }
                val name = Importer.displayName(context, uri) ?: staged.name
                val result = try {
                    withContext(Dispatchers.IO) {
                        Importer.importFile(context, staged, name) { done, total ->
                            // Converting a volume's pages takes a minute or more, and silence for a
                            // minute reads as a hang.
                            importStep = if (total > 0) "Converting page $done of $total…" else null
                        }
                    }
                } finally {
                    withContext(Dispatchers.IO) { staged.delete() }
                }
                importing = false
                importStep = null
                when (result) {
                    is Importer.Result.Added ->
                        if (result.book.kind == BookKind.Text) fetchCover(result.book)
                    is Importer.Result.Failed -> status = result.message
                }
            } catch (e: Exception) {
                importing = false
                importStep = null
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
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (books.isEmpty() && !importing) {
                // Nothing to scroll, so the title stays outside the grid rather than floating
                // above an otherwise-empty screen with no content to earn it.
                Column(Modifier.fillMaxSize()) {
                    ShelfTitle()
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        EmptyShelf(modifier = Modifier.align(Alignment.Center))
                    }
                }
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
                    // The title is the grid's own first row, spanning every column, so it scrolls
                    // away with the covers instead of sitting pinned above them in a fixed bar.
                    item(key = "title", span = { GridItemSpan(maxLineSpan) }) { ShelfTitle() }
                    items(
                        items = entries,
                        // A stack is keyed by its series, a book by its id. Without a stable key
                        // here, adding a volume would rebuild every cell on the shelf.
                        key = { entry ->
                            when (entry) {
                                is ShelfEntry.Single -> entry.book.id
                                is ShelfEntry.Stack -> "series:" + SeriesTitle.key(entry.series)
                            }
                        },
                    ) { entry ->
                        when (entry) {
                            is ShelfEntry.Single -> ShelfBook(
                                book = entry.book,
                                onClick = { onOpenBook(entry.book) },
                                onLongClick = { actionsFor = entry.book },
                            )
                            is ShelfEntry.Stack -> ShelfStack(
                                stack = entry,
                                onClick = { onOpenSeries(SeriesTitle.key(entry.series)) },
                            )
                        }
                    }
                }
            }
            if (importing) {
                Box(
                    modifier = Modifier.fillMaxSize().background(colors.background),
                    contentAlignment = Alignment.Center,
                ) {
                    LightText(importStep ?: "Importing…", LightTextVariant.Copy)
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
            // Icons, not text: the SDK allows five icon items but only three once any item is
            // text, so icons are what leaves room for a fourth thing later. Add sits last, after
            // Settings, rather than first.
            items = listOf(
                LightBarItem.Icon(LightIcons.List, onClick = onOpenLibrary),
                LightBarItem.Icon(LightIcons.Settings, onClick = onOpenSettings),
                LightBarItem.Icon(
                    icon = LightIcons.Add,
                    onClick = {
                        pickFile.launch(
                            arrayOf(
                                "application/epub+zip",
                                "application/x-mobipocket-ebook",
                                "application/x-cbz",
                                "application/vnd.comicbook+zip",
                                "application/zip",
                                "application/octet-stream",
                                "*/*",
                            )
                        )
                    },
                ),
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
            onFlipDirection = {
                actionsFor = null
                repo.setReadingDirection(book.id, !book.rightToLeft)
                status = if (book.rightToLeft) {
                    "“${book.title}” now reads left to right"
                } else {
                    "“${book.title}” now reads right to left"
                }
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

/**
 * The shelf's own title, standing in for a fixed top bar.
 *
 * A grid item rather than a [LightTopBar] — this is what lets it scroll away with the covers.
 * Left-aligned and full width, matching the inset every cover row already sits inside.
 */
@Composable
private fun ShelfTitle(modifier: Modifier = Modifier) {
    LightText(
        text = "Books",
        variant = LightTextVariant.Heading,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = lightInset(),
                vertical = 0.5f.gridUnitsAsDp(),
            ),
    )
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
