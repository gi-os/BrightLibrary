package com.lightfastread.ui.home

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lightfastread.data.Book
import com.lightfastread.data.BookRepository
import com.lightfastread.parser.BookParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import com.gios.light.common.hw.WheelScroll
import com.lightfastread.ui.theme.lpBorder

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onOpenBook: (Book) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { BookRepository.get(context) }
    val books = repo.books

    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<Book?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    WheelScroll(listState)

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        importError = null
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
            } catch (e: Exception) {
                importError = e.message ?: "Failed to import"
                Toast.makeText(context, importError, Toast.LENGTH_LONG).show()
            } finally {
                importing = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FastRead") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    pickFile.launch(arrayOf("application/epub+zip", "application/x-mobipocket-ebook", "application/octet-stream", "*/*"))
                },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add book") },
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (books.isEmpty() && !importing) {
                EmptyState(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // Scaffold does not reserve space for the FAB, and with only
                    // ~472dp of height on the LPIII the last book row ends up
                    // permanently under "Add book". Pad past it.
                    contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp)
                ) {
                    items(books, key = { it.id }) { book ->
                        BookRow(
                            book = book,
                            onClick = { onOpenBook(book) },
                            onLongClick = { pendingDelete = book },
                        )
                    }
                }
            }
            if (importing) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 4.dp,
                        border = lpBorder(),
                    ) {
                        Row(
                            modifier = Modifier.padding(24.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator()
                            Spacer(Modifier.width(16.dp))
                            Text("Importing…")
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete book?") },
            text = { Text("Remove \"${book.title}\" from your library?") },
            confirmButton = {
                TextButton(onClick = {
                    repo.deleteBook(book)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookRow(
    book: Book,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val progress = if (book.totalWords > 0) book.currentWordIndex.toFloat() / book.totalWords else 0f
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        // Tonal elevation is what separates a row from the background upstream.
        // The Light Phone scheme has no tonal lift, so rows get a hairline.
        border = lpBorder(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.MenuBook,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (book.author.isNotBlank()) {
                    Text(
                        book.author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${(progress * 100).toInt()}% • ${book.totalWords} words • ${book.format}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.MenuBook,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No books yet",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Tap “Add book” to import an EPUB or MOBI file from your device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
