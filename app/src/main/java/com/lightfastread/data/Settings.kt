package com.lightfastread.data

import kotlinx.serialization.Serializable

// LightPhone is a pure-black greyscale scheme for the Light Phone III's
// matte AMOLED panel. It is deliberately a separate mode rather than a
// redefinition of Dark, so this fork stays rebaseable on upstream FastRead.
enum class ThemeMode { System, Light, Dark, LightPhone }
enum class SwipeMode { Normal, Zone }
enum class BionicMode { Off, MainOnly, ContextOnly, Both }
enum class TitleStyle { Color, Underline, Both }

/**
 * Where the Calibre library is, and how to get into it.
 *
 * Two addresses rather than one because they are two different servers as far as the protocol is
 * concerned: the catalogue is OPDS over basic auth, and reading progress is calibre-web's Kobo sync
 * API, whose token in the URL *is* the authentication. Either can be set without the other — browse
 * with no sync, or sync a library you fill from the phone's own storage.
 *
 * The password and the token are stored in the app's own SharedPreferences in plain text, which is
 * the same protection every other setting gets. That is a deliberate limit on this feature: it is
 * pointed at a LAN server, not at anything reachable from the internet.
 */
@Serializable
data class CalibreConfig(
    /** Anything a person might type: `192.168.68.59:8768`, a hostname, or a full OPDS URL. */
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    /** The Kobo sync URL calibre-web shows on the user's page, token and all. */
    val koboUrl: String = "",
    val syncProgress: Boolean = true,
)

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
    /**
     * Show cover art in full colour on the shelf, by lifting LightOS's forced greyscale for as
     * long as the shelf is up (see `ui/light/ColorMode.kt`). Needs a one-time adb grant; without
     * it the covers simply stay grey, so this defaults on.
     */
    val colorCovers: Boolean = true,
    /** The Calibre server the LIBRARY screen browses. Empty until it is set. */
    val calibre: CalibreConfig = CalibreConfig(),
    /**
     * Fit a comic page to the screen's *width* and scroll down it, rather than showing the whole
     * page at once.
     *
     * On by default. A manga page shown whole on a 411dp screen is a page of unreadable speech
     * bubbles — the panel is 3.9 inches and the page was drawn for seven — so the honest default is
     * the one where the text can be read and the page is scrolled.
     */
    val comicFitWidth: Boolean = true,
    /**
     * Trim the white paper margin off a scanned page before fitting it.
     *
     * Off by default because it is a guess about somebody else's scan, and a wrong guess eats art.
     * See [com.lightfastread.comic.PageCrop] for what it refuses to do.
     */
    val comicCropBorders: Boolean = false,
    /**
     * Split every comic page across the middle and read the halves as two pages.
     *
     * For the volumes that print two 4-koma strips to a page — half a page fills this screen about
     * twice over, so reading them as pages is both bigger and the right unit.
     */
    val comicFourKoma: Boolean = false,
    /**
     * Turn pages by tapping the left and right thirds of the screen.
     *
     * On by default, but worth being able to switch off: it is the gesture most easily made by
     * accident while holding the phone, and with the wheel to hand it is not the only way to turn a
     * page. Off, a tap anywhere opens the reader's menu instead.
     */
    val comicTapToTurn: Boolean = true,
)
