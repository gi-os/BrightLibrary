package com.lightfastread.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.lightfastread.calibre.CalibreClient
import com.lightfastread.calibre.CalibreImport
import com.lightfastread.calibre.OpdsEntry
import com.lightfastread.calibre.OpdsFeed
import com.lightfastread.calibre.ProgressSync
import com.lightfastread.data.BookRepository
import com.lightfastread.data.CalibreConfig
import com.lightfastread.data.SettingsRepository
import com.lightfastread.data.Storage
import com.gios.light.common.hw.WheelScroll
import com.lightfastread.ui.light.ColourEffect
import com.lightfastread.ui.light.LightBarItem
import com.lightfastread.ui.light.LightBottomBar
import com.lightfastread.ui.light.LightIcon
import com.lightfastread.ui.light.LightIcons
import com.lightfastread.ui.light.LightRule
import com.lightfastread.ui.light.LightText
import com.lightfastread.ui.light.LightTextVariant
import com.lightfastread.ui.light.LightThemeTokens
import com.lightfastread.ui.light.LightTopBar
import com.lightfastread.ui.light.designVerticalPxToDp
import com.lightfastread.ui.light.gridUnitsAsDp
import com.lightfastread.ui.light.lightClickable
import com.lightfastread.ui.light.lightInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Calibre library, browsed over OPDS.
 *
 * A list, not the shelf's grid. A server library is a few hundred books rather than the dozen on the
 * phone, and what you do here is find one — so this is one row per book, with the cover small enough
 * to identify it and the format on the right so it is obvious what will be downloaded.
 *
 * The catalogue is a tree, and this keeps its own back stack of feed URLs. Back therefore means "up
 * one level" until there is no level left, and only then leaves the screen: hardware back that
 * dropped you out of the app from four levels deep would be the single most annoying thing here.
 */
@Composable
fun LibraryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by remember { SettingsRepository.get(context) }.state
    val config = settings.calibre
    val repo = remember { BookRepository.get(context) }
    val colors = LightThemeTokens.colors
    val scope = rememberCoroutineScope()

    // Feed URLs from the root down to what is on screen. The last one is the current feed.
    val trail = remember { mutableListOf<String>() }
    var feed by remember { mutableStateOf<OpdsFeed?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var busyWith by remember { mutableStateOf<String?>(null) }
    var busyStep by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    WheelScroll(listState)

    // Covers again, so the same greyscale lift the shelf does applies to the thumbnails here.
    ColourEffect(enabled = settings.colorCovers)

    val load: (String, Boolean) -> Unit = { url, push ->
        scope.launch {
            loading = true
            error = null
            val result = withContext(Dispatchers.IO) { runCatching { CalibreClient(config).feed(url) } }
            loading = false
            result.onSuccess {
                if (push) trail.add(url)
                feed = it
                listState.scrollToItem(0)
            }.onFailure { error = it.message ?: "Could not reach the server." }
        }
    }

    LaunchedEffect(config.baseUrl, config.username, config.password) {
        // Opening the library is also the moment to settle whatever progress is owed: the phone is
        // demonstrably on the network, which is the one thing a background flush cannot assume.
        ProgressSync.flush(context)
        if (config.baseUrl.isBlank()) return@LaunchedEffect
        trail.clear()
        // `runCatching` catches Throwable, not just Exception, and that is not a detail: this line
        // once swallowed an `ExceptionInInitializerError` from a regex that Android refused to
        // compile, and reported it as a bad address. Whatever went wrong is now named.
        val root = runCatching { CalibreClient.catalogUrl(config.baseUrl) }
        val rootUrl = root.getOrNull()
        if (rootUrl == null) {
            val cause = root.exceptionOrNull()
            error = "That server address is not a URL — " +
                "${cause?.javaClass?.simpleName}: ${cause?.message}"
            return@LaunchedEffect
        }
        load(rootUrl, true)
    }

    val up: () -> Unit = {
        if (trail.size > 1) {
            trail.removeAt(trail.lastIndex)
            load(trail.last(), false)
        } else {
            onBack()
        }
    }

    // Hardware back has to mean the same thing the Back icon does, or four levels into a catalogue it
    // drops you out of the app entirely.
    BackHandler(onBack = up)

    val open: (OpdsEntry) -> Unit = { entry ->
        val href = entry.feedHref
        if (entry.isPublication) {
            if (busyWith == null) {
                busyWith = entry.title
                status = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        CalibreImport.download(context, config, entry) { done, total ->
                            // A comic is converted page by page after it lands, which takes a minute
                            // or more — long enough that "Downloading…" alone reads as a hang.
                            busyStep = if (total > 0) "Converting page $done of $total…" else null
                        }
                    }
                    busyWith = null
                    busyStep = null
                    status = when (result) {
                        is CalibreImport.Result.Added ->
                            if (result.resumedAtWord != null) {
                                "Added “${result.book.title}” — resumed where you left off"
                            } else {
                                "Added “${result.book.title}”"
                            }
                        is CalibreImport.Result.AlreadyOnShelf ->
                            "“${result.existing.title}” is already on the shelf"
                        is CalibreImport.Result.Failed -> result.message
                    }
                }
            }
        } else if (href != null) {
            load(href, true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding(),
    ) {
        LightTopBar(
            title = feed?.title?.ifBlank { "Library" } ?: "Library",
            left = LightBarItem.Icon(LightIcons.Back, onClick = up),
        )

        Box(Modifier.fillMaxWidth().weight(1f)) {
            val current = feed
            when {
                config.baseUrl.isBlank() -> Notice(
                    "No Calibre server yet.",
                    "Settings → Calibre. A LAN address and, if the server asks for one, a " +
                        "username and password.",
                    Modifier.align(Alignment.Center),
                )

                busyWith != null -> Notice(
                    busyStep ?: "Downloading…",
                    busyWith.orEmpty(),
                    Modifier.align(Alignment.Center),
                )

                loading && current == null -> Notice(
                    "Asking the server…",
                    "",
                    Modifier.align(Alignment.Center),
                )

                error != null -> Notice(
                    "Cannot read the catalogue",
                    // The address is part of the error. A failure that names a server you did not
                    // expect is solved in a second; one that says only "cannot connect" is not.
                    error.orEmpty() + "\n\n" + runCatching {
                        CalibreClient.catalogUrl(config.baseUrl)
                    }.getOrElse { config.baseUrl },
                    Modifier.align(Alignment.Center),
                )

                current == null || current.entries.isEmpty() -> Notice(
                    "Nothing here",
                    "This part of the catalogue is empty.",
                    Modifier.align(Alignment.Center),
                )

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = lightInset(),
                        end = lightInset(),
                        bottom = 1f.gridUnitsAsDp(),
                    ),
                ) {
                    items(current.navigation) { entry ->
                        NavigationRow(entry) { open(entry) }
                    }
                    items(current.publications) { entry ->
                        BookRow(
                            entry = entry,
                            config = config,
                            onShelf = entry.uuid?.let { repo.hasCalibreBook(it) } == true,
                            onClick = { open(entry) },
                        )
                    }
                    // Paging is a row rather than an on-scroll trigger on purpose: a feed that loads
                    // itself as you scroll cannot be stopped, and on a metered LAN-less connection
                    // that is somebody's data.
                    current.nextHref?.let { next ->
                        item {
                            ActionRow(if (loading) "LOADING…" else "MORE") {
                                if (!loading) load(next, false)
                            }
                        }
                    }
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
                    text = "SEARCH",
                    lighten = config.baseUrl.isBlank(),
                    onClick = { if (config.baseUrl.isNotBlank()) searching = true },
                ),
                LightBarItem.Text(
                    text = "RELOAD",
                    onClick = { trail.lastOrNull()?.let { load(it, false) } },
                ),
            ),
            modifier = Modifier.navigationBarsPadding(),
        )
    }

    if (searching) {
        LibrarySearch(
            onSearch = { query ->
                searching = false
                if (query.isNotBlank()) {
                    scope.launch {
                        loading = true
                        error = null
                        // Resolve the URL first and then load it like any other feed, so the trail
                        // holds something RELOAD and paging can actually re-fetch.
                        val url = withContext(Dispatchers.IO) {
                            runCatching { CalibreClient(config).searchUrl(query) }
                        }
                        loading = false
                        url.onSuccess {
                            // A search result is a level of its own, so backing out of it returns to
                            // wherever the search was started from rather than to the root.
                            load(it, true)
                        }.onFailure { error = it.message ?: "Search failed." }
                    }
                }
            },
            onDismiss = { searching = false },
        )
    }
}

/**
 * `items` for a plain list, without pulling in the whole lazy DSL import.
 *
 * Kept local because both call sites want the same thing and neither wants a key: an OPDS entry has
 * no id worth keying on across feeds — some servers reuse `urn:uuid:` ids between a search result and
 * a browse result — and a wrong key is worse than none.
 */
private fun <T> LazyListScope.items(
    list: List<T>,
    content: @Composable (T) -> Unit,
) = items(list.size) { index -> content(list[index]) }

@Composable
private fun NavigationRow(entry: OpdsEntry, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().lightClickable(onClick = onClick)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12f.designVerticalPxToDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                LightText(
                    text = entry.title,
                    variant = LightTextVariant.Copy,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.summary.isNotBlank()) {
                    LightText(
                        text = entry.summary,
                        variant = LightTextVariant.Superfine,
                        lighten = true,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            LightIcon(LightIcons.Forward, size = 1.4f, tint = LightThemeTokens.colors.contentSecondary)
        }
        LightRule()
    }
}

@Composable
private fun BookRow(
    entry: OpdsEntry,
    config: CalibreConfig,
    onShelf: Boolean,
    onClick: () -> Unit,
) {
    val readable = entry.formatLabel()
    Column(Modifier.fillMaxWidth().lightClickable(onClick = onClick)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8f.designVerticalPxToDp()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.6f.gridUnitsAsDp()),
        ) {
            RemoteThumb(
                url = entry.thumbnailHref,
                config = config,
                fallbackLetter = entry.title,
                modifier = Modifier.width(THUMB_WIDTH_UNITS.gridUnitsAsDp()).aspectRatio(2f / 3f),
            )
            Column(Modifier.weight(1f)) {
                LightText(
                    text = entry.title,
                    variant = LightTextVariant.Detail,
                    lighten = readable == null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.author.isNotBlank()) {
                    LightText(
                        text = entry.author,
                        variant = LightTextVariant.Superfine,
                        lighten = true,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Three states in one slot, because there is only room for one: already downloaded, a
            // format this app reads, or a book it cannot open at all. A readable one also says how
            // big it is — the difference between a 4 MB novel and a 240 MB volume is worth knowing
            // before tapping it on a phone.
            val size = entry.bestDownload()?.first?.length
            LightText(
                text = when {
                    onShelf -> "ON SHELF"
                    readable != null && size != null -> "$readable  ${Storage.humanBytes(size)}"
                    readable != null -> readable
                    else -> "—"
                },
                variant = LightTextVariant.Superfine,
                lighten = true,
            )
        }
        LightRule()
    }
}

@Composable
private fun ActionRow(text: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().lightClickable(onClick = onClick)) {
        LightText(
            text = text,
            variant = LightTextVariant.Button,
            align = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14f.designVerticalPxToDp()),
        )
        LightRule()
    }
}

@Composable
private fun Notice(title: String, detail: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = lightInset()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LightText(title, LightTextVariant.Subheading, align = TextAlign.Center)
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(10f.designVerticalPxToDp()))
            LightText(detail, LightTextVariant.Detail, lighten = true, align = TextAlign.Center)
        }
    }
}

/** Wide enough to recognise a cover, narrow enough that the title still gets two lines. */
private const val THUMB_WIDTH_UNITS = 2.6f
