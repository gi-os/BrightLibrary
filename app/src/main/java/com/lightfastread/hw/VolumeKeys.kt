package com.lightfastread.hw

import android.view.KeyEvent
import android.view.View
import android.view.ViewParent
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Which way a volume key turns a page: +1 forward, -1 back, null for any other key.
 *
 * **Up is forward, which is not what KOReader does** - it turns forward on volume *down*. The wheel
 * on this phone already means forward when it is rolled up (`WheelUp` sends +1, in MainActivity's
 * own `dispatchKeyEvent`), and two hardware controls an inch apart disagreeing about which way the
 * book goes is worse than disagreeing with another app.
 *
 * These are ordinary Android keycodes rather than `LightKey`s. light-common knows the five scancodes
 * Light patched into `Generic.kl` - the wheel, its click, focus and camera - and volume is not one of
 * them, so there is nothing there to reuse and this stays a plain keycode check inside the app.
 */
fun volumePageStep(keyCode: Int): Int? = when (keyCode) {
    KeyEvent.KEYCODE_VOLUME_UP -> 1
    KeyEvent.KEYCODE_VOLUME_DOWN -> -1
    else -> null
}

/**
 * Volume presses on their way to whichever reader is up, in the Activity's own window.
 *
 * Claimed rather than always live. Consuming a volume key means the system never sees it: no volume
 * slider, and no change in volume either. That is the price of turning a page with it, and it may
 * only be paid on the screens that asked for it - a claim is taken by the reader that is listening
 * and dropped when it leaves, so the shelf, settings and library still change the volume.
 *
 * The count is atomic rather than Compose state because the Activity's `dispatchKeyEvent` reads it
 * from outside any composition, before the view hierarchy has seen the event.
 */
class VolumeBus {
    private val claims = AtomicInteger(0)

    /** Buffered so a press is never dropped for want of a collector mid-recomposition. */
    private val _presses = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val presses: SharedFlow<Int> = _presses

    /** Whether any screen currently wants volume keys taken away from the system. */
    val claimed: Boolean get() = claims.get() > 0

    fun claim() {
        claims.incrementAndGet()
    }

    fun release() {
        claims.decrementAndGet()
    }

    fun send(step: Int) {
        _presses.tryEmit(step)
    }
}

/** The Activity's volume bus, for the screens that live in its window. */
val LocalVolumeBus = compositionLocalOf<VolumeBus?> { null }

/**
 * One volume press, one page turn - wherever the caller happens to be drawn.
 *
 * There are two ways a screen can be on this phone and they need different plumbing, which is the
 * only interesting thing in this file:
 *
 * - **In the Activity's window** - the comic reader is a plain NavHost destination - the Activity's
 *   `dispatchKeyEvent` already sees every key first, so this claims [VolumeBus] and collects from it.
 * - **In a Dialog's window** - the text reader is wrapped in one - that `dispatchKeyEvent` never runs
 *   at all. A dialog is a separate window with its own `Window.Callback`, and the DecorView calls
 *   *that* callback, so the only place to catch the key is the dialog's own window. The callback is
 *   borrowed for as long as this is composed, delegating everything it does not want to the original,
 *   and handed back on dispose. It is the same trick light-common's `WheelInDialog` plays for wheel
 *   notches, copied here rather than added to the library: volume is not a Light key, and one app
 *   wanting it is not a reason to cut a light-common release.
 *
 * [enabled] false does nothing at all - no claim, no borrowed callback - so with the setting off the
 * volume keys belong to the system exactly as they did before.
 *
 * Only `ACTION_DOWN` with `repeatCount == 0` turns a page. Both halves of the press are still
 * consumed, or the system would act on the UP; and a held key would otherwise autorepeat through the
 * book at the key-repeat rate, which is not something a page turn should do.
 */
@Composable
fun VolumePageTurns(enabled: Boolean, onTurn: (Int) -> Unit) {
    val handler by rememberUpdatedState(onTurn)
    val view = LocalView.current
    // Which kind of window this is cannot change for a given view, so it is resolved once.
    val dialogWindow = remember(view) { view.dialogWindow() }
    val bus = LocalVolumeBus.current

    if (!enabled) return

    if (dialogWindow != null) {
        DisposableEffect(dialogWindow) {
            val original = dialogWindow.callback ?: return@DisposableEffect onDispose { }
            dialogWindow.callback = object : Window.Callback by original {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    val step = volumePageStep(event.keyCode)
                    if (step != null) {
                        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            handler(step)
                        }
                        return true
                    }
                    return original.dispatchKeyEvent(event)
                }
            }
            onDispose { dialogWindow.callback = original }
        }
    } else if (bus != null) {
        DisposableEffect(bus) {
            bus.claim()
            onDispose { bus.release() }
        }
        LaunchedEffect(bus) { bus.presses.collect { handler(it) } }
    }
}

/**
 * The window a dialog's content is hosted in, or null in the Activity's own window.
 *
 * Compose marks the host view of a dialog window with [DialogWindowProvider]; the whole parent chain
 * is walked rather than just the immediate parent, because how many views sit between the composition
 * and that host is an implementation detail.
 */
private fun View.dialogWindow(): Window? {
    var parent: ViewParent? = this.parent
    while (parent != null) {
        if (parent is DialogWindowProvider) return parent.window
        parent = (parent as? View)?.parent
    }
    return null
}
