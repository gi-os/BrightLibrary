package com.lightfastread.data

import kotlinx.serialization.Serializable

// LightPhone is a pure-black greyscale scheme for the Light Phone III's
// matte AMOLED panel. It is deliberately a separate mode rather than a
// redefinition of Dark, so this fork stays rebaseable on upstream FastRead.
enum class ThemeMode { System, Light, Dark, LightPhone }
enum class SwipeMode { Normal, Zone }
enum class BionicMode { Off, MainOnly, ContextOnly, Both }
enum class TitleStyle { Color, Underline, Both }

@Serializable
data class Settings(
    val minWpm: Int = 100,
    val maxWpm: Int = 500,
    val rampUpMs: Int = 300,
    val backwardHoldMs: Int = 500,
    val pauseAfterDotFactor: Float = 1.0f,
    val pauseAfterParagraphFactor: Float = 2.0f,
    val extraLetterFactor: Float = 1.0f,
    val letterDelayThreshold: Int = 5,
    val fontSizeSp: Int = 56,
    val contextFontSizeSp: Int = 18,
    val contextAlpha: Float = 0.5f,
    val fontFamily: String = "Default",
    val themeMode: ThemeMode = ThemeMode.LightPhone,
    val swipeMode: SwipeMode = SwipeMode.Normal,
    val swipeDpPerWord: Int = 10,
    // The zone guides draw hairlines along every gesture boundary. Useful while
    // you're learning where the zones are, clutter once you know - and on the
    // Light Phone III every lit pixel is a choice, so they default to off.
    val showZoneGuides: Boolean = false,
    val bionicMode: BionicMode = BionicMode.Off,
    val bionicBoldWeight: Int = 700,
    val bionicLightWeight: Int = 400,
    val titleColorArgb: Int = 0xFFFFA726.toInt(),
    val titleStyle: TitleStyle = TitleStyle.Color,
    val orpEnabled: Boolean = false,
    val orpColorArgb: Int = 0xFFE53935.toInt(),
    val orpFocalSameColor: Boolean = false,
    // Fraction of the per-word interval (60_000 / WPM) spent fading the main
    // word in and out. 0 = no fade, 1 = the entire interval is split equally
    // between fade-in and fade-out (no hold time at full opacity).
    val wordFadeFraction: Float = 0f,
    // Body text size for the full-page ereader mode (tap the context window to
    // open it). Independent of the giant RSVP focal word and the tiny
    // three-line context preview - this is meant to be read normally, a full
    // page at a time.
    val readerFontSizeSp: Int = 22,
)
