package com.lightfastread.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.lightfastread.data.BookRepository
import com.lightfastread.data.Chapter
import com.lightfastread.data.Fonts
import com.lightfastread.data.SettingsRepository
import com.lightfastread.data.SwipeMode
import com.lightfastread.ui.light.LightText
import com.lightfastread.ui.light.LightTextVariant
import com.lightfastread.ui.theme.LocalIsLightPhone
import com.lightfastread.ui.theme.toLightPhoneGrey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sqrt

// How long a touch in the context-preview band (top half of the reading
// surface) can be held before it stops counting as a tap-to-open-ereader
// and instead falls through to the ordinary hold-to-speed-read gesture.
private const val TAP_MAX_MS = 220L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val bookRepo = remember { BookRepository.get(context) }
    val settingsRepo = remember { SettingsRepository.get(context) }
    val settings by settingsRepo.state
    val book = remember(bookId) { bookRepo.getBook(bookId) }

    // The LPIII panel is greyscale, so the title and ORP accents the user picked
    // in the colour wheel arrive as whatever grey their luminance happens to be.
    // Flatten them here instead of at the draw site, so the emulator preview
    // matches the device exactly and low-luma picks (the default red ORP) don't
    // vanish against the dimmed context lines.
    val isLightPhone = LocalIsLightPhone.current
    val titleColor = Color(settings.titleColorArgb).let {
        if (isLightPhone) it.toLightPhoneGrey() else it
    }
    val orpColor = Color(settings.orpColorArgb).let {
        if (isLightPhone) it.toLightPhoneGrey(floor = 0.70f) else it
    }

    if (book == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var words by remember { mutableStateOf<List<String>>(emptyList()) }
    var paragraphBreakAfter by remember { mutableStateOf(BooleanArray(0)) }
    var italic by remember { mutableStateOf(BooleanArray(0)) }
    var loading by remember { mutableStateOf(true) }
    var currentIndex by rememberSaveable(bookId) { mutableIntStateOf(book.currentWordIndex) }
    var showTopBar by rememberSaveable { mutableStateOf(false) }
    var showQuickSettings by rememberSaveable { mutableStateOf(false) }
    var liveWpm by remember { mutableFloatStateOf(0f) }
    var lastForwardWpm by remember { mutableFloatStateOf(settings.minWpm.toFloat()) }
    var isHolding by remember { mutableStateOf(false) }
    var showChapterList by rememberSaveable { mutableStateOf(false) }
    var showBookmarksList by rememberSaveable { mutableStateOf(false) }
    // A book opens to its pages, not to the word reader. FastRead is a button in the page
    // view's top bar, which is itself hidden until you tap the screen — so the app you get
    // when you open a book is an e-reader, and RSVP is a mode you ask for.
    var showEreader by rememberSaveable { mutableStateOf(true) }
    val historyBack = rememberSaveable(bookId, saver = IntListSaver) { mutableStateListOf<Int>() }
    val historyForward = rememberSaveable(bookId, saver = IntListSaver) { mutableStateListOf<Int>() }
    val bookmarks = bookRepo.books.firstOrNull { it.id == bookId }?.bookmarks ?: emptyList()

    val scope = rememberCoroutineScope()

    // The system gesture agrees with the top bar's own arrow. While the page view is up it owns
    // back itself (it is a Dialog, with its own window), so this only ever fires in RSVP.
    BackHandler(enabled = !showEreader) { showEreader = true }

    val jumpTo: (Int) -> Unit = { target ->
        val safe = target.coerceIn(0, (words.size - 1).coerceAtLeast(0))
        if (safe != currentIndex) {
            historyBack.add(currentIndex)
            historyForward.clear()
            currentIndex = safe
        }
    }
    val goHistoryBack: () -> Unit = {
        if (historyBack.isNotEmpty()) {
            val prev = historyBack.removeAt(historyBack.lastIndex)
            historyForward.add(currentIndex)
            currentIndex = prev.coerceIn(0, (words.size - 1).coerceAtLeast(0))
        }
    }
    val goHistoryForward: () -> Unit = {
        if (historyForward.isNotEmpty()) {
            val nxt = historyForward.removeAt(historyForward.lastIndex)
            historyBack.add(currentIndex)
            currentIndex = nxt.coerceIn(0, (words.size - 1).coerceAtLeast(0))
        }
    }

    LaunchedEffect(bookId) {
        loading = true
        val loaded = withContext(Dispatchers.IO) { bookRepo.loadWordsAndBreaks(book) }
        words = loaded.words
        paragraphBreakAfter = loaded.paragraphBreakAfter
        italic = loaded.italic
        if (currentIndex >= loaded.words.size) currentIndex = (loaded.words.size - 1).coerceAtLeast(0)
        loading = false
    }

    LaunchedEffect(currentIndex) {
        delay(500)
        bookRepo.updateProgress(bookId, currentIndex)
    }

    val mainWordAnim = remember { Animatable(1f) }
    LaunchedEffect(currentIndex, isHolding, settings.wordFadeFraction) {
        val fraction = settings.wordFadeFraction
        if (!isHolding || fraction <= 0f) {
            mainWordAnim.snapTo(1f)
            return@LaunchedEffect
        }
        // Snapshot the current effective WPM at the moment this word arrived.
        // Using a live read inside the animation would chase the slider while
        // the word is on screen and produce inconsistent fade timings.
        val wpm = liveWpm
        if (wpm <= 0.5f) {
            mainWordAnim.snapTo(1f)
            return@LaunchedEffect
        }
        val baseIntervalMs = (60_000f / wpm).toLong().coerceAtLeast(1L)
        val pauseMs = pauseAfterWord(
            words, paragraphBreakAfter, currentIndex, baseIntervalMs,
            settings.pauseAfterDotFactor, settings.pauseAfterParagraphFactor,
            settings.extraLetterFactor, settings.letterDelayThreshold,
        )
        val totalMs = baseIntervalMs + pauseMs
        // Fade budget is a fraction of the *base* interval (i.e. 1s @ 60 WPM),
        // independent of pause padding — so the user's percentage maps cleanly
        // to "X% of one word-interval is fade". The hold absorbs the pause.
        val fadeMs = (baseIntervalMs * fraction).toLong()
        val halfFadeMs = (fadeMs / 2L).coerceAtLeast(1L)
        val holdMs = (totalMs - 2L * halfFadeMs).coerceAtLeast(0L)
        mainWordAnim.snapTo(0f)
        mainWordAnim.animateTo(1f, tween(halfFadeMs.toInt(), easing = LinearEasing))
        if (holdMs > 0L) delay(holdMs)
        mainWordAnim.animateTo(0f, tween(halfFadeMs.toInt(), easing = LinearEasing))
    }

    DisposableEffect(bookId) {
        onDispose { bookRepo.updateProgress(bookId, currentIndex) }
    }

    /**
     * Save when the app leaves the screen, not only when the reader is closed.
     *
     * On this phone you leave an app by pressing home, which stops the activity and — some minutes
     * later, out of sight — kills the process. Neither runs `onDispose`, so without this the last
     * stretch of reading is lost and the book reopens where it was last *deliberately* closed.
     */
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, bookId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) bookRepo.updateProgress(bookId, currentIndex)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pointerX = remember { mutableFloatStateOf(0f) }

    val density = LocalDensity.current
    val navBarInsetPx = WindowInsets.navigationBars.getBottom(density)
    val bottomDeadZoneExtraPx = with(density) { 32.dp.toPx() }
    // Don't treat touches that land in the navigation-bar inset (or just above
    // it) as word-changing input — otherwise an OS swipe-up to switch apps
    // ends up incrementing/decrementing the word on the way out.
    val bottomDeadZonePx = navBarInsetPx + bottomDeadZoneExtraPx

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(words, settings.swipeMode, settings.swipeDpPerWord, bottomDeadZonePx) {
                    if (words.isEmpty()) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val width = size.width.toFloat()
                        val height = size.height.toFloat()
                        val topStripH = height * 0.2f
                        val bottomDeadStart = height - bottomDeadZonePx
                        val swipeBandStart = (height * 0.8f).coerceIn(topStripH, bottomDeadStart)

                        if (down.position.y > bottomDeadStart) {
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (!ev.changes.first().pressed) break
                            }
                            return@awaitEachGesture
                        }

                        if (down.position.y < topStripH) {
                            // While collapsed the whole top region is one tap target —
                            // any tap (no significant movement) expands it.
                            // While expanded the children (chapter title, undo/redo,
                            // bookmark) consume their own touches; if the touch lands
                            // on padding here, just drain it without advancing words.
                            if (!showTopBar) {
                                var moved = false
                                val startPos = down.position
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.first()
                                    val dx = ch.position.x - startPos.x
                                    val dy = ch.position.y - startPos.y
                                    if (dx * dx + dy * dy > 24f * 24f) moved = true
                                    if (!ch.pressed) break
                                }
                                if (!moved) showTopBar = true
                            } else {
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    if (!ev.changes.first().pressed) break
                                }
                            }
                            return@awaitEachGesture
                        }

                        if (showTopBar) {
                            // Expanded: any touch outside the top region just collapses
                            // the bar — no word advance, no scroll.
                            showTopBar = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (!ev.changes.first().pressed) break
                            }
                            return@awaitEachGesture
                        }

                        if (down.position.y >= swipeBandStart) {
                            // Bottom 1/5 swipe band.
                            val pxPerWord = settings.swipeDpPerWord.coerceAtLeast(1).dp.toPx()
                            val capturedWords = words
                            val capturedBreaks = paragraphBreakAfter
                            val dotF = settings.pauseAfterDotFactor
                            val paraF = settings.pauseAfterParagraphFactor
                            val extraF = settings.extraLetterFactor
                            val letterT = settings.letterDelayThreshold
                            when (settings.swipeMode) {
                                SwipeMode.Normal -> {
                                    var lastX = down.position.x
                                    var accum = 0f
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.first()
                                        val dx = ch.position.x - lastX
                                        lastX = ch.position.x
                                        accum += dx
                                        while (accum > 0) {
                                            val cur = currentIndex
                                            if (cur >= capturedWords.size - 1) break
                                            val factor = pauseFactorAfterWord(
                                                capturedWords, capturedBreaks, cur,
                                                dotF, paraF, extraF, letterT,
                                            )
                                            val cost = pxPerWord * (1f + factor)
                                            if (accum >= cost) {
                                                accum -= cost
                                                currentIndex = cur + 1
                                            } else break
                                        }
                                        while (accum < 0) {
                                            val cur = currentIndex
                                            if (cur <= 0) break
                                            val factor = pauseFactorAfterWord(
                                                capturedWords, capturedBreaks, cur - 1,
                                                dotF, paraF, extraF, letterT,
                                            )
                                            val cost = pxPerWord * (1f + factor)
                                            if (accum <= -cost) {
                                                accum += cost
                                                currentIndex = cur - 1
                                            } else break
                                        }
                                        if (!ch.pressed) break
                                    }
                                }
                                SwipeMode.Zone -> {
                                    val divider = width / 3f
                                    val forward = down.position.x >= divider
                                    var lastPos = down.position
                                    var accum = 0f
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.first()
                                        val dx = ch.position.x - lastPos.x
                                        val dy = ch.position.y - lastPos.y
                                        lastPos = ch.position
                                        accum += sqrt(dx * dx + dy * dy)
                                        while (accum > 0) {
                                            val cur = currentIndex
                                            if (forward) {
                                                if (cur >= capturedWords.size - 1) break
                                                val factor = pauseFactorAfterWord(
                                                    capturedWords, capturedBreaks, cur,
                                                    dotF, paraF, extraF, letterT,
                                                )
                                                val cost = pxPerWord * (1f + factor)
                                                if (accum >= cost) {
                                                    accum -= cost
                                                    currentIndex = cur + 1
                                                } else break
                                            } else {
                                                if (cur <= 0) break
                                                val factor = pauseFactorAfterWord(
                                                    capturedWords, capturedBreaks, cur - 1,
                                                    dotF, paraF, extraF, letterT,
                                                )
                                                val cost = pxPerWord * (1f + factor)
                                                if (accum >= cost) {
                                                    accum -= cost
                                                    currentIndex = cur - 1
                                                } else break
                                            }
                                        }
                                        if (!ch.pressed) break
                                    }
                                }
                            }
                            return@awaitEachGesture
                        }

                        // Top of the middle zone: the three-line context
                        // preview lives here (WordDisplay's ContextLineWindow
                        // fills the top half of this same Box). A quick tap
                        // opens the full-page ereader at whatever page that
                        // text is from; a longer hold falls through to the
                        // ordinary speed-read zones below, just starting a
                        // beat later.
                        val contextBandEnd = height * 0.5f
                        if (down.position.y < contextBandEnd) {
                            val startPos = down.position
                            var moved = false
                            val releasedInTime = withTimeoutOrNull(TAP_MAX_MS) {
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.first()
                                    val dx = ch.position.x - startPos.x
                                    val dy = ch.position.y - startPos.y
                                    if (dx * dx + dy * dy > 24f * 24f) moved = true
                                    if (!ch.pressed) break
                                }
                            } != null
                            if (releasedInTime) {
                                if (!moved) showEreader = true
                                return@awaitEachGesture
                            }
                            // Still held past the tap window: fall through to
                            // the ordinary hold-to-speed-read logic below, using
                            // the same `down`.
                        }

                        // Middle 3/5: hold zones (always available alongside the swipe band).
                        val divider = width / 3f
                        if (down.position.x < divider) {
                            if (currentIndex > 0) currentIndex--
                            pointerX.floatValue = down.position.x
                            isHolding = true
                            val holdMs = settings.backwardHoldMs.toLong()
                            val rampUp = settings.rampUpMs.toLong()
                            val targetWpm = lastForwardWpm.coerceAtLeast(settings.minWpm.toFloat())
                            val job: Job = scope.launch {
                                delay(holdMs)
                                runWordLoop(
                                    rampUpMs = rampUp,
                                    getTargetWpm = { targetWpm },
                                    forward = false,
                                    getCurrent = { currentIndex },
                                    setCurrent = { currentIndex = it },
                                    wordCount = words.size,
                                    onLiveWpm = { liveWpm = it },
                                )
                            }
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.first()
                                if (!ch.pressed) break
                            }
                            job.cancel()
                            isHolding = false
                            liveWpm = 0f
                        } else {
                            if (currentIndex < words.size - 1) currentIndex++
                            pointerX.floatValue = down.position.x
                            isHolding = true
                            val rampUp = settings.rampUpMs.toLong()
                            val minWpm = settings.minWpm.toFloat()
                            val maxWpm = settings.maxWpm.toFloat()
                            val dotPauseFactor = settings.pauseAfterDotFactor
                            val paragraphPauseFactor = settings.pauseAfterParagraphFactor
                            val extraLetterFactor = settings.extraLetterFactor
                            val letterThreshold = settings.letterDelayThreshold
                            val capturedWords = words
                            val capturedBreaks = paragraphBreakAfter
                            val job: Job = scope.launch {
                                runWordLoop(
                                    rampUpMs = rampUp,
                                    getTargetWpm = {
                                        val rangeStart = divider
                                        val rangeEnd = width
                                        val rel = ((pointerX.floatValue - rangeStart) / (rangeEnd - rangeStart))
                                            .coerceIn(0f, 1f)
                                        val wpm = minWpm + rel * (maxWpm - minWpm)
                                        lastForwardWpm = wpm
                                        wpm
                                    },
                                    forward = true,
                                    getCurrent = { currentIndex },
                                    setCurrent = { currentIndex = it },
                                    wordCount = words.size,
                                    onLiveWpm = { liveWpm = it },
                                    getPauseAfterMs = { idx, wordIntervalMs ->
                                        pauseAfterWord(
                                            capturedWords,
                                            capturedBreaks,
                                            idx,
                                            wordIntervalMs,
                                            dotPauseFactor,
                                            paragraphPauseFactor,
                                            extraLetterFactor,
                                            letterThreshold,
                                        )
                                    },
                                )
                            }
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.first()
                                if (!ch.pressed) break
                                pointerX.floatValue = ch.position.x
                            }
                            job.cancel()
                            isHolding = false
                            liveWpm = 0f
                        }
                    }
                }
        ) {
            if (settings.showZoneGuides) {
                ZoneOverlay(swipeMode = settings.swipeMode, bottomDeadZonePx = bottomDeadZonePx)
            }

            val titleWordFlags = remember(book.chapters, words.size) {
                computeTitleWordFlags(book.chapters, words.size)
            }

            if (loading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    LightText("Opening…", LightTextVariant.Copy, lighten = true)
                }
            } else if (words.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    LightText(
                        text = "No readable text in this book.",
                        variant = LightTextVariant.Detail,
                        align = TextAlign.Center,
                    )
                }
            } else {
                val quoteInfo = remember(words, paragraphBreakAfter) {
                    computeQuoteInfo(words, paragraphBreakAfter)
                }
                WordDisplay(
                    bookId = bookId,
                    words = words,
                    paragraphBreakAfter = paragraphBreakAfter,
                    isTitleWord = titleWordFlags,
                    isItalicWord = italic,
                    insideQuotes = quoteInfo.first,
                    quoteOpenChar = quoteInfo.second,
                    titleColor = titleColor,
                    titleStyle = settings.titleStyle,
                    currentIndex = currentIndex,
                    fontSizeSp = settings.fontSizeSp,
                    contextFontSizeSp = settings.contextFontSizeSp,
                    contextAlpha = settings.contextAlpha,
                    fontFamily = Fonts.familyFor(settings.fontFamily),
                    bionicMode = settings.bionicMode,
                    bionicBoldWeight = settings.bionicBoldWeight,
                    bionicLightWeight = settings.bionicLightWeight,
                    orpEnabled = settings.orpEnabled,
                    orpColor = orpColor,
                    orpFocalSameColor = settings.orpFocalSameColor,
                    mainWordAlpha = mainWordAnim.value,
                )
            }

            BottomStatus(
                index = currentIndex,
                total = words.size,
                liveWpm = liveWpm,
                isHolding = isHolding,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding(),
            )

            val currentChapter = remember(currentIndex, book.chapters) {
                currentChapterFor(book.chapters, currentIndex)
            }

            Column(modifier = Modifier.align(Alignment.TopCenter)) {
                if (showTopBar) {
                    TopBar(
                        title = book.title,
                        // Back out of the word reader is back to the *pages*, not out of the
                        // book: the page view is the book's home screen now, and leaving from
                        // there is one more press.
                        onBack = { showEreader = true },
                        onQuickSettings = { showQuickSettings = true },
                    )
                }
                ChapterStrip(
                    chapterTitle = currentChapter?.title ?: book.title,
                    expanded = showTopBar,
                    onChapterClick = { showChapterList = true },
                    canUndo = historyBack.isNotEmpty(),
                    canRedo = historyForward.isNotEmpty(),
                    onUndo = goHistoryBack,
                    onRedo = goHistoryForward,
                    isBookmarked = currentIndex in bookmarks,
                    onBookmarkTap = { bookRepo.toggleBookmark(bookId, currentIndex) },
                    onBookmarkLongPress = { showBookmarksList = true },
                    showStatusBarPadding = !showTopBar,
                )
            }

            if (showQuickSettings) {
                QuickSettingsSheet(
                    onDismiss = { showQuickSettings = false }
                )
            }

            if (showChapterList) {
                ChapterListSheet(
                    chapters = book.chapters,
                    currentIndex = currentIndex,
                    onPick = { chapter ->
                        showChapterList = false
                        jumpTo(chapter.startWordIndex)
                    },
                    onDismiss = { showChapterList = false },
                )
            }

            if (showBookmarksList) {
                BookmarksSheet(
                    bookmarks = bookmarks,
                    words = words,
                    chapters = book.chapters,
                    onPick = { idx ->
                        showBookmarksList = false
                        jumpTo(idx)
                    },
                    onDelete = { idx ->
                        bookRepo.toggleBookmark(bookId, idx)
                    },
                    onDismiss = { showBookmarksList = false },
                )
            }

            // Only once there is something to paginate. Handing the page view an empty word
            // list makes it measure zero pages, decide the book is unreadable and hand itself
            // straight back — which on an ereader-first open looks like the book refusing to
            // open at all.
            if (showEreader && !loading && words.isNotEmpty()) {
                EreaderScreen(
                    bookId = bookId,
                    title = book.title,
                    words = words,
                    paragraphBreakAfter = paragraphBreakAfter,
                    isItalicWord = italic,
                    isTitleWord = titleWordFlags,
                    titleColor = titleColor,
                    titleStyle = settings.titleStyle,
                    fontFamily = Fonts.familyFor(settings.fontFamily),
                    // The family object alone can't key a layout cache; the
                    // settings key can.
                    fontKey = settings.fontFamily,
                    fontSizeSp = settings.readerFontSizeSp,
                    initialWordIndex = currentIndex,
                    // Written straight through rather than through `jumpTo`: a page turn is reading,
                    // not a jump, and putting every one of them on the back stack would make the
                    // history arrow walk back a page at a time. Persisted here and now, because the
                    // 500ms debounce below is no use if the process is killed inside it.
                    onProgress = { newIndex ->
                        currentIndex = newIndex.coerceIn(0, (words.size - 1).coerceAtLeast(0))
                        bookRepo.updateProgress(bookId, currentIndex)
                    },
                    onFastRead = { newIndex ->
                        showEreader = false
                        jumpTo(newIndex)
                    },
                    onExit = { newIndex ->
                        jumpTo(newIndex)
                        onBack()
                    },
                )
            }
        }
    }
}

// Mark the words that make up each chapter's title. The chapter title text is
// also part of the body text at chapter.startWordIndex (extracted from the
// h1/h2/h3 heading), so we flag the next N words where N is the title's word
// count. If the body's heading wording differs slightly from the TOC label
// the highlight may be a token short or long, but it's a useful approximation.
private fun computeTitleWordFlags(chapters: List<Chapter>, totalWords: Int): BooleanArray {
    val flags = BooleanArray(totalWords)
    if (totalWords == 0) return flags
    for (c in chapters) {
        val n = c.title.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        if (n <= 0) continue
        val start = c.startWordIndex.coerceIn(0, totalWords)
        val end = (start + n).coerceAtMost(totalWords)
        for (i in start until end) flags[i] = true
    }
    return flags
}

private fun currentChapterFor(chapters: List<Chapter>, wordIndex: Int): Chapter? {
    if (chapters.isEmpty()) return null
    var found: Chapter? = null
    for (c in chapters) {
        if (c.startWordIndex <= wordIndex) found = c else break
    }
    return found ?: chapters.first()
}

private val IntListSaver = listSaver<SnapshotStateList<Int>, Int>(
    save = { it.toList() },
    restore = { it.toMutableStateList() },
)

private suspend fun runWordLoop(
    rampUpMs: Long,
    getTargetWpm: () -> Float,
    forward: Boolean,
    getCurrent: () -> Int,
    setCurrent: (Int) -> Unit,
    wordCount: Int,
    onLiveWpm: (Float) -> Unit,
    getPauseAfterMs: (Int, Long) -> Long = { _, _ -> 0L },
) {
    // Driven by the frame clock, not by a fixed 8 ms tick.
    //
    // The panel is 60 Hz. A delay(8) loop woke this coroutine roughly twice per frame, and
    // every wakeup calls onLiveWpm and can advance the word — both of which write Compose
    // state that nothing can draw until the next frame arrives, so half the work was thrown
    // away before it reached the screen. withFrameNanos does the same arithmetic once per
    // frame instead.
    //
    // The second effect matters more than the first: withFrameNanos is not scheduled at all
    // when the reader is not producing frames, so a session left running in the background
    // stops burning CPU on words nobody is reading. delay(8) kept going regardless.
    //
    // The ramp still reads the wall clock. It is a ramp *in seconds since you pressed*, and
    // deriving it from accumulated frame deltas would quietly change its meaning across a
    // pause.
    val startTime = System.currentTimeMillis()
    var lastFrameNs = 0L
    var progress = 0f
    while (true) {
        val frameNs = withFrameNanos { it }
        val elapsedSinceDown = System.currentTimeMillis() - startTime
        // The first frame only establishes the baseline — it has no interval to contribute.
        val deltaMs = if (lastFrameNs == 0L) 0L else (frameNs - lastFrameNs) / 1_000_000L
        lastFrameNs = frameNs

        val ramp = if (rampUpMs <= 0) 1f
        else (elapsedSinceDown.toFloat() / rampUpMs.toFloat()).coerceIn(0f, 1f)
        val target = getTargetWpm()
        val effective = (target * ramp).coerceAtLeast(0.0001f)
        onLiveWpm(effective)
        val perMs = effective / 60_000f
        progress += deltaMs * perMs
        while (progress >= 1f) {
            val cur = getCurrent()
            if (forward) {
                val wordIntervalMs = if (target > 0f) (60_000f / target).toLong() else 0L
                val pauseMs = getPauseAfterMs(cur, wordIntervalMs)
                if (pauseMs > 0) {
                    delay(pauseMs)
                    // A punctuation pause is deliberate dead time, not reading time. Re-baseline
                    // on the next frame so the interval spent waiting does not come back as a
                    // burst of skipped words.
                    lastFrameNs = withFrameNanos { it }
                    progress = 1f
                }
                if (cur < wordCount - 1) setCurrent(cur + 1) else { progress = 0f; break }
            } else {
                if (cur > 0) setCurrent(cur - 1) else { progress = 0f; break }
            }
            progress -= 1f
        }
    }
}

private val pauseTrailingTrim = charArrayOf(
    '"', '\'', ')', ']', '}', '”', '’', '»', '"', '»',
)

private val pauseLeadingTrim = charArrayOf(
    '"', '\'', '(', '[', '{', '“', '‘', '«', '¿', '¡',
)

// Tokens (lowercase, period stripped) that end with a period but are
// almost never a sentence ending.
private val nonSentenceEndingAbbreviations: Set<String> = setOf(
    // Titles / honorifics
    "mr", "mrs", "ms", "mx", "dr", "prof", "sr", "jr", "st", "ste",
    "rev", "fr", "sgt", "capt", "cmdr", "cdr", "col", "lt",
    "maj", "gen", "pres", "hon", "gov", "sen", "rep", "supt",
    "det", "cpl", "pvt", "pfc", "brig", "adm", "ens", "ofc",
    // Streets / places / orgs
    "ave", "blvd", "rd", "mt", "mts", "ft", "pt", "hwy", "ln",
    "sq", "terr", "cir", "dept", "univ",
    // Companies
    "inc", "ltd", "co", "corp", "bros", "llc", "plc",
    // Post-name titles (when written without internal dots)
    "esq", "phd", "md", "ba", "ma", "dds", "jd",
    // Months
    "jan", "feb", "mar", "apr", "jun", "jul", "aug",
    "sep", "sept", "oct", "nov", "dec",
    // Days
    "mon", "tue", "tues", "wed", "thu", "thur", "thurs", "fri", "sat", "sun",
    // Measurements / references
    "in", "lb", "lbs", "oz", "cm", "mm", "km", "mi",
    "yd", "qt", "gal", "min", "sec", "hr", "yr", "wk",
    "no", "nos", "vol", "vols", "pg", "pgs", "p", "pp",
    "ch", "art", "fig", "figs", "tab", "eq", "ref", "refs",
    "eds", "ed", "para", "chap",
    // Latin & misc
    "vs", "v", "etc", "eg", "ie", "cf", "viz", "al", "ibid",
    "op", "cit", "approx", "ca",
)

private fun isSentenceEnd(words: List<String>, index: Int): Boolean {
    val word = words[index]
    val core = word.trimEnd(*pauseTrailingTrim)
    if (core.isEmpty()) return false
    val last = core.last()
    if (last != '.' && last != '?' && last != '!') return false

    // Trailing run of dots (ellipsis "...") is ambiguous; don't pause.
    if (last == '.' && core.length >= 2 && core[core.length - 2] == '.') return false

    val body = core.trimEnd('.', '?', '!').trimStart(*pauseLeadingTrim)

    // Single letter + period: initial like "A.", "J.", "I."
    if (body.length == 1 && body[0].isLetter()) return false

    // Internal-dot abbreviations: "U.S.", "U.S.A.", "Ph.D.", "e.g.", "i.e."
    if (body.contains('.')) return false

    // Bare number followed by period: list items, ordinals ("1.", "23.")
    if (body.isNotEmpty() && body.all { it.isDigit() }) return false

    if (body.isNotEmpty() && body.lowercase() in nonSentenceEndingAbbreviations) return false

    // If the next non-empty token starts with a lowercase letter, it's almost
    // certainly a continuation — catches abbreviations not in the list above.
    val nextStart = nextWordStart(words, index)
    if (nextStart != null && nextStart.isLetter() && nextStart.isLowerCase()) return false

    return true
}

private fun isClauseBreak(words: List<String>, index: Int): Boolean {
    val word = words[index]
    val core = word.trimEnd(*pauseTrailingTrim)
    if (core.isEmpty()) return false
    val last = core.last()
    return last == ',' || last == ';' || last == ':'
}

private fun nextWordStart(words: List<String>, index: Int): Char? {
    for (i in index + 1 until words.size) {
        val w = words[i].trimStart(*pauseLeadingTrim)
        val c = w.firstOrNull { !it.isWhitespace() } ?: continue
        return c
    }
    return null
}

// Unitless multiplier matching pauseAfterWord's pause-time logic, used by
// swipe-driven modes to scale per-word pixel cost (e.g. cost = pxPerWord * (1+f)).
private fun pauseFactorAfterWord(
    words: List<String>,
    paragraphBreakAfter: BooleanArray,
    index: Int,
    dotPauseFactor: Float,
    paragraphPauseFactor: Float,
    extraLetterFactor: Float,
    letterDelayThreshold: Int,
): Float {
    if (index < 0 || index >= words.size) return 0f
    val word = words[index]
    var factor = 0f
    if (extraLetterFactor > 1f) {
        val letters = word.count { it.isLetter() }
        val excess = letters - letterDelayThreshold
        if (excess > 0) factor = excess * (extraLetterFactor - 1f)
    }
    if (dotPauseFactor > 0f && (isSentenceEnd(words, index) || isClauseBreak(words, index))) {
        if (dotPauseFactor > factor) factor = dotPauseFactor
    }
    if (paragraphPauseFactor > 0f
        && index < paragraphBreakAfter.size
        && paragraphBreakAfter[index]
    ) {
        if (paragraphPauseFactor > factor) factor = paragraphPauseFactor
    }
    return factor
}

private fun pauseAfterWord(
    words: List<String>,
    paragraphBreakAfter: BooleanArray,
    index: Int,
    wordIntervalMs: Long,
    dotPauseFactor: Float,
    paragraphPauseFactor: Float,
    extraLetterFactor: Float,
    letterDelayThreshold: Int,
): Long {
    if (index < 0 || index >= words.size) return 0L
    val word = words[index]
    var pause = 0L
    if (extraLetterFactor > 1f && wordIntervalMs > 0) {
        val letters = word.count { it.isLetter() }
        val excess = letters - letterDelayThreshold
        if (excess > 0) pause = (excess * (extraLetterFactor - 1f) * wordIntervalMs).toLong()
    }
    if (dotPauseFactor > 0f && wordIntervalMs > 0
        && (isSentenceEnd(words, index) || isClauseBreak(words, index))
    ) {
        val dotPauseMs = (wordIntervalMs * dotPauseFactor).toLong()
        if (dotPauseMs > pause) pause = dotPauseMs
    }
    if (paragraphPauseFactor > 0f
        && wordIntervalMs > 0
        && index < paragraphBreakAfter.size
        && paragraphBreakAfter[index]
    ) {
        val paragraphPauseMs = (wordIntervalMs * paragraphPauseFactor).toLong()
        if (paragraphPauseMs > pause) pause = paragraphPauseMs
    }
    return pause
}
