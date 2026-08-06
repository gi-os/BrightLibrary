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
import com.lightfastread.calibre.ProgressSync
import com.lightfastread.data.BookKind
import com.lightfastread.ui.comic.ComicReader
import android.net.Uri
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                CompositionLocalProvider(LocalWheelBus provides wheel) {
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
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                // Which reader opens is a property of the book, decided here rather than inside
                // ReaderScreen: a comic has no words to load, so the text reader should never be
                // built for one at all.
                onOpenBook = { book ->
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
