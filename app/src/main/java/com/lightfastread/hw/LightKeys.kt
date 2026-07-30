package com.lightfastread.hw

import android.view.KeyEvent

/** The wheel's two directions, as they arrive from the sensor. */
enum class LightKey {
    /** Wheel turned towards the top of the phone. */
    WheelUp,

    /** Wheel turned towards the bottom of the phone. */
    WheelDown,
}

/**
 * Recognising the LPIII's brightness wheel.
 *
 * The wheel is not a rotary encoder. It is a `Pixart pat9126ja` optical sensor on
 * `/dev/input/event4` that emits one discrete DOWN+UP key pair per notch, roughly 35–60 ms
 * apart, so this is key handling and not `AXIS_SCROLL` / `onRotaryScrollEvent`.
 *
 * Light patched `/system/usr/keylayout/Generic.kl` — the layout every input device on the
 * phone loads — to relabel five scancodes:
 *
 * ```
 * key 19    WHEEL_CCW      # wheel up      (Pixart, was R)
 * key 20    WHEEL_CW       # wheel down    (Pixart, was T)
 * key 66    WHEEL_CLICK    # wheel press   (gpio-keys, was F8)
 * key 80    FOCUS          # camera stage 1 (gpio-keys, was NUMPAD_2)
 * key 27    CAMERA         # camera stage 2 (gpio-keys, was RIGHT_BRACKET)
 * ```
 *
 * Nothing intercepts these in `PhoneWindowManager`; they are dispatched to the focused
 * window like any other key, which is why an app that ignores the keycode gets nothing —
 * and why handling it needs no root and no accessibility service.
 *
 * Only the turns are handled here. The wheel click and the camera button belong to
 * LightControl, which owns them across the whole phone and deliberately passes bare turns
 * through to apps like this one, because per-notch scrolling inside the app beats the
 * synthetic finger it would otherwise have to draw.
 *
 * `WHEEL_CCW`, `WHEEL_CW` and `WHEEL_CLICK` are not AOSP keycodes; Light added them, so
 * their integer values are Light's to change. Hence two ways in, in order:
 *
 *  1. Resolve the label to a keycode at runtime. [KeyEvent.keyCodeFromString] reads the
 *     same native label table the keylayout parser uses, so Light's additions resolve.
 *  2. Fall back to the raw Linux scancode, which is fixed by the hardware. Scancode 19 is
 *     also `r` on a Bluetooth keyboard, so that path is gated on the device name.
 */
object LightKeys {

    // Linux scancodes, from `getevent -pl`. These are hardware, not software.
    private const val SCAN_WHEEL_UP = 19 // KEY_R
    private const val SCAN_WHEEL_DOWN = 20 // KEY_T

    /** The wheel's own sensor. Nothing else may claim these scancodes. */
    private val trustedDevices = setOf("Pixart pat9126ja")

    private val byScanCode = mapOf(
        SCAN_WHEEL_UP to LightKey.WheelUp,
        SCAN_WHEEL_DOWN to LightKey.WheelDown,
    )

    private val byKeyCode: Map<Int, LightKey> = buildMap {
        putLabel("WHEEL_CCW", LightKey.WheelUp)
        putLabel("WHEEL_CW", LightKey.WheelDown)
    }

    private fun MutableMap<Int, LightKey>.putLabel(label: String, key: LightKey) {
        val code = runCatching { KeyEvent.keyCodeFromString(label) }
            .getOrDefault(KeyEvent.KEYCODE_UNKNOWN)
        if (code != KeyEvent.KEYCODE_UNKNOWN) put(code, key)
    }

    /** Which control produced [event], or null if it wasn't one of ours. */
    fun of(event: KeyEvent): LightKey? {
        byKeyCode[event.keyCode]?.let { return it }
        // Either the labels moved or this build doesn't have them. Trust the scancode,
        // but only from the two devices that physically own these controls — otherwise a
        // paired keyboard's `r` would scroll the article.
        val device = event.device?.name ?: return null
        if (device in trustedDevices) return byScanCode[event.scanCode]
        return null
    }

    /** True if this build maps the wheel labels at all — useful for a settings readout. */
    fun wheelLabelsPresent(): Boolean = byKeyCode.containsValue(LightKey.WheelUp)
}
