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
import com.lightfastread.hw.LightKey
import com.lightfastread.hw.LightKeys
import com.lightfastread.hw.LocalWheelBus
import com.lightfastread.hw.WheelBus
import com.lightfastread.ui.home.HomeScreen
import com.lightfastread.ui.reader.ReaderScreen
import com.lightfastread.ui.settings.SettingsScreen
import com.lightfastread.ui.theme.FastReadTheme
import com.lightfastread.report.CrashLog
import com.lightfastread.report.ReportOverlay

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First thing, before anything else can throw: the handler chains onto whatever is
        // already installed and only writes a file, so it is safe this early.
        CrashLog.install(this)
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
                // through their own windows. See hw/Wheel.kt.
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
                onOpenBook = { book -> navController.navigate("reader/${book.id}") },
                onOpenSettings = { navController.navigate("settings") },
            )
        }
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
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
