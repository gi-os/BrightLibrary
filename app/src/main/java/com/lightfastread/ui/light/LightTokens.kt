package com.lightfastread.ui.light

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.gios.light.common.theme.akkuratFamilyOrDefault
import androidx.compose.ui.unit.sp

/**
 * The Light Phone III design language, ported from `lightphone/light-sdk` (MIT licence,
 * © 2026 The Light Phone — see LICENSE-light-sdk) so that a plain sideloaded APK looks
 * and behaves like a tool built against the SDK.
 *
 * Three ideas carry most of the look:
 *
 *  - **A 27 x 31 grid.** Every size and gap is a fraction of the screen rather than a
 *    fixed dp, which is how LightOS keeps its proportions on a 3.92" panel.
 *  - **A named type scale, scaled by screen height.** The sizes below are the LP3's own
 *    design pixels; [designVerticalPxToSp] converts them against a 600px baseline.
 *  - **Three colours only.** Background, content, secondary content. Everything else —
 *    state, selection, emphasis — is carried by inversion, brackets or weight.
 *
 * This replaces the Material 3 chrome the fork inherited from upstream FastRead. Material
 * is still underneath (see `FastReadTheme`) so that sheets and dialogs inherit the palette,
 * but nothing in this app should be reaching for `MaterialTheme.typography` any more.
 */

/* ---------------- grid ---------------- */

object LightGrid {
    const val WIDTH = 27
    const val HEIGHT = 31
}

@Composable
fun Float.gridUnitsAsDp(): Dp {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    return (screenWidthDp.toFloat() / LightGrid.WIDTH * this).dp
}

@Composable
fun Float.verticalGridUnitsAsDp(): Dp {
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    return (screenHeightDp.toFloat() / LightGrid.HEIGHT * this).dp
}

private const val FONT_VERTICAL_SCALE_BASELINE_PX = 600f

@Composable
fun Float.designVerticalPxToSp(): TextUnit {
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.toFloat()
    return (this * screenHeightDp / FONT_VERTICAL_SCALE_BASELINE_PX).sp
}

@Composable
fun Float.designVerticalPxToDp(): Dp {
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.toFloat()
    return (this * screenHeightDp / FONT_VERTICAL_SCALE_BASELINE_PX).dp
}

/* ---------------- colours ---------------- */

@Immutable
data class LightColors(
    val background: Color,
    val content: Color,
    val contentSecondary: Color,
    /** Marks that must be present but must not compete with the words. */
    val contentFaint: Color,
    /** Hairlines between rows. Not an SDK token; a library is a list of things. */
    val rule: Color,
)

object LightThemeColors {
    val Dark = LightColors(
        background = Color.Black,
        content = Color.White,
        contentSecondary = Color(0xFFBBBBBB),
        contentFaint = Color(0xFF5E5E5E),
        rule = Color(0xFF262626),
    )

    /**
     * For the two non-Light themes upstream still offers. The type scale and grid are the
     * same; only the two ends of the ramp swap, so a white-background build is legible
     * rather than white-on-white.
     */
    val OnWhite = LightColors(
        background = Color.White,
        content = Color.Black,
        contentSecondary = Color(0xFF555555),
        contentFaint = Color(0xFF9A9A9A),
        rule = Color(0xFFDDDDDD),
    )
}

/* ---------------- typography ---------------- */

@Immutable
data class LightTypography(
    val title: TextStyle,
    val subtitle: TextStyle,
    val heading: TextStyle,
    val subheading: TextStyle,
    val copy: TextStyle,
    val button: TextStyle,
    val paragraph: TextStyle,
    val paragraphWide: TextStyle,
    val detail: TextStyle,
    val fine: TextStyle,
    val superfine: TextStyle,
    val micro: TextStyle,
)

/** Mirrors the LP3 table in LightOS's own `style/index.ts`, unscaled. */
private fun buildTypography(fontFamily: FontFamily): LightTypography = LightTypography(
    title = TextStyle(
        fontSize = 115.sp, fontFamily = fontFamily, fontWeight = FontWeight.Light,
        lineHeight = (115 * 1.10).sp,
    ),
    subtitle = TextStyle(
        fontSize = 52.sp, fontFamily = fontFamily, fontWeight = FontWeight.Normal,
        lineHeight = (52 * 1.20).sp,
    ),
    heading = TextStyle(
        fontSize = 38.sp, fontFamily = fontFamily, fontWeight = FontWeight.Normal,
        lineHeight = (38 * 1.35).sp,
    ),
    subheading = TextStyle(
        fontSize = 30.sp, fontFamily = fontFamily, fontWeight = FontWeight.Normal,
        letterSpacing = (30 * 0.03).sp, lineHeight = (30 * 1.25).sp,
    ),
    copy = TextStyle(
        fontSize = 30.sp, fontFamily = fontFamily, fontWeight = FontWeight.Normal,
        lineHeight = (30 * 1.50).sp,
    ),
    button = TextStyle(
        fontSize = 30.sp, fontFamily = fontFamily, fontWeight = FontWeight.Medium,
        letterSpacing = (30 * 0.15).sp, lineHeight = (30 * 1.10).sp,
    ),
    paragraph = TextStyle(
        fontSize = 24.5.sp, fontFamily = fontFamily, fontWeight = FontWeight.Normal,
        lineHeight = (24.5 * 1.25).sp,
    ),
    paragraphWide = TextStyle(
        fontSize = 25.sp, fontFamily = fontFamily, fontWeight = FontWeight.Normal,
        letterSpacing = (25 * 0.02).sp, lineHeight = (25 * 1.30).sp,
    ),
    detail = TextStyle(
        fontSize = 20.sp, fontFamily = fontFamily, fontWeight = FontWeight.Normal,
        lineHeight = (20 * 1.45).sp,
    ),
    fine = TextStyle(
        fontSize = 25.sp, fontFamily = fontFamily, fontWeight = FontWeight.Normal,
        letterSpacing = (25 * 0.03).sp, lineHeight = (25 * 1.15).sp,
    ),
    superfine = TextStyle(
        fontSize = 16.sp, fontFamily = fontFamily, fontWeight = FontWeight.Normal,
        lineHeight = (16 * 1.20).sp,
    ),
    micro = TextStyle(
        fontSize = 8.sp, fontFamily = fontFamily, fontWeight = FontWeight.Normal,
        lineHeight = (8 * 1.20).sp,
    ),
)

private val FallbackTypography = buildTypography(FontFamily.Default)

@Composable
internal fun rememberLightTypography(): LightTypography {
    val fam = remember { akkuratFamilyOrDefault() }
    return remember(fam) { buildTypography(fam) }
}

val LocalLightColors = staticCompositionLocalOf { LightThemeColors.Dark }
val LocalLightTypography = staticCompositionLocalOf { FallbackTypography }

object LightThemeTokens {
    val colors: LightColors
        @Composable get() = LocalLightColors.current

    val typography: LightTypography
        @Composable get() = LocalLightTypography.current
}

/* ---------------- touch ---------------- */

object LightHaptics {
    /** Tuned for the LP3's slow motor, same as the SDK. */
    @Suppress("DEPRECATION")
    fun click(context: Context) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                // minSdk is still 24, so the pre-31 accessor has to stay.
                context.getSystemService(Vibrator::class.java)
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(45L, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}

/**
 * Clickable with no ripple and no press state, buzzing the way LightOS does. A ripple would
 * be the single most un-Light thing in the app.
 *
 * The buzz is raised from the click, not from finger-down: `awaitFirstDown` cannot tell a tap
 * from the first moment of a scroll, so dragging a finger down a shelf of books buzzed once
 * per cover it passed under.
 */
fun Modifier.lightClickable(
    enabled: Boolean = true,
    haptics: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val context = LocalContext.current
    val buzz = enabled && haptics
    clickable(
        interactionSource = null,
        indication = null,
        enabled = enabled,
    ) {
        if (buzz) LightHaptics.click(context)
        onClick()
    }
}

/** Same, with a long press — where a book's own actions live. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.lightCombinedClickable(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
): Modifier = composed {
    val context = LocalContext.current
    combinedClickable(
        interactionSource = null,
        indication = null,
        onLongClick = {
            LightHaptics.click(context)
            onLongClick()
        },
    ) {
        LightHaptics.click(context)
        onClick()
    }
}
