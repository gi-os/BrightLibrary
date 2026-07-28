package com.fastread

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fastread.data.SettingsRepository
import com.fastread.data.ThemeMode
import com.fastread.ui.home.HomeScreen
import com.fastread.ui.reader.ReaderScreen
import com.fastread.ui.settings.SettingsScreen
import com.fastread.ui.theme.FastReadTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                AppNav()
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
