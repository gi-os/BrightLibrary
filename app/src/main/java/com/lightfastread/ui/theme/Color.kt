package com.lightfastread.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// ---------------------------------------------------------------------------
// Light Phone III palette
//
// The LPIII has a 3.92" 1080x1240 AMOLED behind matte glass, and LightOS
// renders everything in black and white. Two consequences drive this palette:
//
//   1. OLED black is *off* pixels - true #000000 draws no power and produces
//      infinite contrast. Material 3's dark scheme uses #1C1B1F-ish greys and
//      tonal elevation overlays, which on an OLED means a visibly lit grey
//      slab instead of a black void. Every surface here is pure black and
//      surfaceTint is black so elevation overlays composite to nothing.
//   2. Because the panel is greyscale, any hue we ship is thrown away and only
//      its luminance survives. So the palette is greyscale by construction -
//      what you see in the emulator is what the phone shows.
//
// The matte glass also scatters light, which lowers perceived contrast. Mid
// greys that look fine on a glossy phone go muddy here, so the secondary text
// ramp sits higher than Material's defaults.
// ---------------------------------------------------------------------------

val LpBlack = Color(0xFF000000)
val LpWhite = Color(0xFFFFFFFF)

/** Secondary text / icons. Bright enough to survive the matte diffuser. */
val LpGrey85 = Color(0xFFD9D9D9)

/** Tertiary text - counters, captions, progress labels. */
val LpGrey65 = Color(0xFFA6A6A6)

/** Disabled text and de-emphasised context lines. */
val LpGrey45 = Color(0xFF737373)

/** Hairline borders and dividers. */
val LpGrey30 = Color(0xFF4D4D4D)

/** Barely-there separators - the faintest thing still visible on matte glass. */
val LpGrey18 = Color(0xFF2E2E2E)

/**
 * Collapses a colour to the grey the LPIII would actually display, then lifts
 * it into the visible range.
 *
 * The Rec. 709 luma of FastRead's default title orange (#FFA726) is ~0.68 and
 * the default ORP red (#E53935) is ~0.36 - as raw greys on black those read as
 * "dim white" and "muddy", and the red in particular disappears against the
 * de-emphasised context text. Remapping luma into [floor, 1.0] keeps the
 * *relative* ordering the user picked in the colour wheel while guaranteeing
 * every accent stays legible on the matte panel.
 */
fun Color.toLightPhoneGrey(floor: Float = 0.62f): Color {
    val luma = (0.2126f * red + 0.7152f * green + 0.0722f * blue).coerceIn(0f, 1f)
    val lifted = floor + (1f - floor) * luma
    return Color(red = lifted, green = lifted, blue = lifted, alpha = alpha)
}
