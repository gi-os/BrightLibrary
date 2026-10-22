package com.lightfastread

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lightfastread.data.SettingsRepository
import com.lightfastread.data.ThemeMode
import com.gios.light.common.hw.LightKey
import com.gios.light.common.hw.LightKeys
import com.gios.light.common.hw.LocalWheelBus
import com.gios.light.common.hw.WheelBus
import com.lightfastread.hw.LocalVolumeBus
import com.lightfastread.hw.VolumeBus
import com.lightfastread.hw.volumePageStep
import com.lightfastread.calibre.ProgressSync
import com.lightfastread.data.BookKind
import com.lightfastread.data.BookRepository
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import com.lightfastread.ui.comic.ComicReader
import android.net.Uri
import android.content.Intent
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.lightfastread.data.Importer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.lightfastread.ui.home.HomeScreen
import com.lightfastread.ui.home.SeriesShelf
import com.lightfastread.ui.library.LibraryScreen
import com.lightfastread.ui.light.ColorMode
import com.lightfastread.ui.reader.ReaderScreen
import com.lightfastread.ui.settings.SettingsScreen
import com.lightfastread.ui.theme.FastReadTheme
import com.gios.light.common.report.LightReport
import com.gios.light.common.report.ReportOverlay

class MainActivity : ComponentActivity() {

    /** Wheel notches on their way to whichever screen is up. */
    private val wheel = WheelBus()

    /**
     * Volume presses on their way to whichever reader has claimed them.
     *
     * Nothing claims it unless the setting is on and a reader is on screen, and until something does
     * every volume press goes to the system untouched. See `hw/VolumeKeys.kt`.
     */
    private val volume = VolumeBus()

    // Every hardware key arrives here before the view hierarchy sees it - the
    // DecorView calls the window callback first - so the wheel wins even when
    // something focusable is under it. Both halves of a notch are consumed: one
    // notch is a complete DOWN+UP pair, and letting the UP through would let a
    // text field read it as a keypress.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (LightKeys.of(event)) {
            LightKey.WheelUp -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(1)
                return true
            }
            LightKey.WheelDown -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(-1)
                return true
            }
            else -> Unit
        }
        // Volume up and down turn pages, when a reader has claimed them. Consuming the key is the
        // whole feature and also its whole cost: the system never sees it, so there is no volume
        // slider and no change in volume while that reader is up. Hence the claim, and hence the
        // setting being off out of the box.
        //
        // Both halves of the press are consumed - letting the UP through would leave the system
        // acting on half a press - and a held key is ignored after the first DOWN, so leaning on the
        // button does not autorepeat through the chapter.
        if (volume.claimed) {
            val step = volumePageStep(event.keyCode)
            if (step != null) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) volume.send(step)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * The phone must not be left in colour when the app is not on screen.
     *
     * The daltonizer is a *display-wide* secure setting, so the shelf lifting it colours the whole
     * of LightOS. Dropping it in `onStop` and re-lifting in `onStart` keeps that bounded to the
     * moments the covers are actually visible; without it, backing out of the app would leave the
     * user's phone in colour until they came back.
     */
    override fun onStart() {
        super.onStart()
        ColorMode.onAppVisible(this)
    }

    override fun onStop() {
        super.onStop()
        ColorMode.onAppHidden(this)
        // Leaving the app is the reliable moment to tell Calibre where you got to: the position has
        // stopped moving, and the throttle that keeps the reader from pushing per word means the last
        // few minutes of reading are otherwise still unsent. Fire-and-forget on its own scope, so it
        // survives this Activity going away.
        ProgressSync.flush(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeBook(intent)
    }

    /**
     * A book handed over by another app: `ACTION_SEND` with the file as `EXTRA_STREAM`, or
     * `ACTION_VIEW` on an EPUB. Web Tools' SEND TO LIBRARY does the first with an article it
     * turned into an EPUB. Same staging as the picker: copied to a file before anything reads it,
     * imported off the main thread, the copy deleted after.
     */
    private fun takeBook(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri == null) return
        intent.action = null // once
        lifecycleScope.launch {
            val result = runCatching {
                val staged = withContext(Dispatchers.IO) { Importer.cacheCopy(this@MainActivity, uri) }
                val name = Importer.displayName(this@MainActivity, uri) ?: staged.name
                try {
                    withContext(Dispatchers.IO) { Importer.importFile(this@MainActivity, staged, name) }
                } finally {
                    withContext(Dispatchers.IO) { staged.delete() }
                }
            }.getOrElse { Importer.Result.Failed(it.message ?: "Could not read that file.") }
            val text = when (result) {
                is Importer.Result.Added -> "Added “${result.book.title}”"
                is Importer.Result.Failed -> result.message
            }
            Toast.makeText(this@MainActivity, text, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        takeBook(intent)
        // First thing, before anything else can throw. This is the whole of the app's
        // reporting setup: it names the app for issue titles, hands over the key, and arms the
        // crash handler, which chains onto whatever is already installed and only writes a
        // file — safe this early.
        //
        // `label` stays `fastread` after the rename to LightBooks: the triage skill routes on
        // it and every issue already filed carries it, so a new label would orphan both.
        LightReport.install(
            context = this,
            appName = "LightBooks",
            label = "fastread",
            token = BuildConfig.REPORT_TOKEN,
        )
        // Transparent bars over a black window. LightOS draws no persistent
        // system bars, but on a normal Android device or the LPIII emulator this
        // keeps the chrome from punching two grey slabs into a black screen.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        setContent {
            val settingsRepo = SettingsRepository.get(this)
            val settings by settingsRepo.state
            val useLightIcons = settings.themeMode != ThemeMode.Light
            SideEffect {
                // Status/nav icons have to be light on black and dark on white.
                // enableEdgeToEdge only decides this once, at Activity start, so
                // it has to be re-applied when the user switches theme.
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !useLightIcons
                    isAppearanceLightNavigationBars = !useLightIcons
                }
            }
            FastReadTheme(themeMode = settings.themeMode) {
                // Every screen below can reach the wheel; the sheets reach it too,
                // through their own windows. See light-common's hw/Wheel.kt.
                CompositionLocalProvider(
                    LocalWheelBus provides wheel,
                    LocalVolumeBus provides volume,
                ) {
                    AppNav()
                }
                // Shake to report, the crash offer on next launch, and the app's own noticed
                // failures. A sibling, not a wrapper — the sheet is its own window, so it covers
                // the app whether or not it contains it.
                ReportOverlay()
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun AppNav() {
    val navController = rememberNavController()
    val appContext = LocalContext.current.applicationContext
    // Opening a book stamps it, so the shelf puts what you are reading first.
    val bookRepo = remember { BookRepository.get(appContext) }
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                // Which reader opens is a property of the book, decided here rather than inside
                // ReaderScreen: a comic has no words to load, so the text reader should never be
                // built for one at all.
                onOpenBook = { book ->
                    bookRepo.markOpened(book.id)
                    val route = if (book.kind == BookKind.Comic) "comic" else "reader"
                    navController.navigate("$route/${book.id}")
                },
                onOpenSettings = { navController.navigate("settings") },
                onOpenLibrary = { navController.navigate("library") },
                // The series *key* travels, not the display name: it is already lowercase and
                // punctuation-free, so it survives being a path segment without encoding tricks,
                // and it still identifies the stack after a volume is renamed.
                onOpenSeries = { key -> navController.navigate("series/${Uri.encode(key)}") },
            )
        }
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable("library") {
            LibraryScreen(onBack = { navController.popBackStack() })
        }
        composable("series/{seriesKey}") { backStackEntry ->
            SeriesShelf(
                seriesKey = Uri.decode(backStackEntry.arguments?.getString("seriesKey").orEmpty()),
                onOpenBook = { book ->
                    bookRepo.markOpened(book.id)
                    val route = if (book.kind == BookKind.Comic) "comic" else "reader"
                    navController.navigate("$route/${book.id}")
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable("comic/{bookId}") { backStackEntry ->
            ComicReader(
                bookId = backStackEntry.arguments?.getString("bookId").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
        composable("reader/{bookId}") { backStackEntry ->
            val bookId = backStackEntry.arguments?.getString("bookId").orEmpty()
            ReaderScreen(
                bookId = bookId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
