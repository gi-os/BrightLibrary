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
)
