package com.lightfastread.ui.light

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Lifts LightOS's forced greyscale while book covers are on screen.
 *
 * The Light Phone III's panel is a **full-colour AMOLED**. Its black-and-white look is
 * Android's accessibility colour correction — the daltonizer — pinned to monochromacy, which
 * is a secure setting and a SurfaceFlinger colour-matrix change, so switching
 * `accessibility_display_daltonizer_enabled` off shows true colour instantly with no restart.
 * LightOS does the same thing itself: photos and video play in colour on a phone that is
 * otherwise grey.
 *
 * A shelf of covers is a good case for it, and a narrow one: everything else this app draws is
 * white on black by design and stays that way. Colour is held exactly while the shelf is up and
 * dropped the moment you open a book, so the reading screens are as monochrome as LightOS.
 *
 * Writing the setting needs `WRITE_SECURE_SETTINGS`, which is
 * `signature|privileged|**development**` and so grantable over adb exactly once:
 *
 * ```
 * adb shell pm grant com.lightfastread android.permission.WRITE_SECURE_SETTINGS
 * ```
 *
 * Without the grant every call here quietly does nothing — the `SecurityException` is swallowed
 * — and covers stay grey like the rest of the phone. It degrades rather than breaks, which is
 * why colour is on by default.
 *
 * Ported from LightChat's `ColorMode` via LightNotebook, deliberately as a straight port: the
 * reference counting and the foreground handling below are load-bearing and were arrived at the
 * hard way.
 */
object ColorMode {

    private const val TAG = "ColorMode"
    private const val ENABLED = "accessibility_display_daltonizer_enabled"
    private const val MODE = "accessibility_display_daltonizer"

    /**
     * The daltonizer mode to put back — LightOS pins 0, simulate monochromacy. Non-null exactly
     * while we are holding the phone in colour.
     */
    private var savedMode: Int? = null

    /**
     * How many screens want colour, not whether one does.
     *
     * Two things can want colour at once — a shelf being disposed while a cover sheet opens over
     * it — and with a boolean, whichever released first would drop colour out from under the
     * other.
     */
    private var holders = 0

    fun acquire(context: Context) {
        holders++
        if (holders == 1) lift(context)
    }

    fun release(context: Context) {
        if (holders > 0) holders--
        if (holders == 0) restore(context)
    }

    /** The app left the foreground: the rest of the phone should be grey again at once. */
    fun onAppHidden(context: Context) = restore(context)

    /**
     * Back in the foreground — re-lift if anything still wants colour. Deliberately does not
     * touch [holders]: leaving the app is not the same as leaving the shelf.
     */
    fun onAppVisible(context: Context) {
        if (holders > 0) lift(context)
    }

    /** True if the one-time adb grant has been given. Used to explain itself in settings. */
    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** True if the phone is in colour right now, whoever put it there. */
    fun phoneIsColour(context: Context): Boolean =
        runCatching { Settings.Secure.getInt(context.contentResolver, ENABLED, 0) != 1 }
            .getOrDefault(false)

    private fun lift(context: Context) {
        val resolver = context.contentResolver
        // Already in colour — somebody else's doing, or the user's. Leave it alone entirely,
        // including on the way out: restoring greyscale we never removed would be us turning a
        // colour phone monochrome.
        if (runCatching { Settings.Secure.getInt(resolver, ENABLED, 0) }.getOrDefault(0) != 1) {
            return
        }
        val mode = runCatching { Settings.Secure.getInt(resolver, MODE, 0) }.getOrDefault(0)
        try {
            Settings.Secure.putInt(resolver, ENABLED, 0)
            savedMode = mode
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS not granted; staying greyscale")
        }
    }

    private fun restore(context: Context) {
        val mode = savedMode ?: return
        try {
            Settings.Secure.putInt(context.contentResolver, MODE, mode)
            Settings.Secure.putInt(context.contentResolver, ENABLED, 1)
            savedMode = null
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS revoked mid-hold; can't restore greyscale")
        }
    }
}

/**
 * Holds the phone in colour for as long as the calling composable is on screen.
 *
 * Display-wide, not per-view: Android has no way to colourise one surface. It reads as
 * covers-only anyway, because everything else on the shelf is white on black — the only things
 * with hues in them are the books.
 */
@Composable
fun ColourEffect(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        if (enabled) ColorMode.acquire(context)
        onDispose { if (enabled) ColorMode.release(context) }
    }
}
