package com.fastread.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// Separating black from black.
//
// Upstream FastRead relies on Material 3 tonal elevation to lift sheets, top
// bars and book rows off the background: at elevation the surface gets a
// lighter tint, and that tint *is* the visual boundary. The Light Phone scheme
// deliberately kills that tint to keep the OLED unlit, which means an elevated
// surface and the background behind it are both #000000 and the boundary
// disappears - a bottom sheet reads as floating text with no container.
//
// So in Light Phone mode we swap tonal elevation for explicit hairlines. Same
// job, no lit pixels: a 1dp outline costs a single row of dim grey instead of a
// whole grey slab.
//
// All of these no-op outside Light Phone mode, so upstream behaviour on grey
// dark/light themes is untouched.
// ---------------------------------------------------------------------------

/** Hairline colour, or null when tonal elevation is doing the job instead. */
@Composable
fun lpEdgeColor(): Color? =
    if (LocalIsLightPhone.current) MaterialTheme.colorScheme.outline else null

/** `border` argument for a Material 3 [androidx.compose.material3.Surface]. */
@Composable
fun lpBorder(): BorderStroke? = lpEdgeColor()?.let { BorderStroke(1.dp, it) }

/**
 * Draws a hairline along the bottom edge, on top of the content.
 *
 * Uses `drawWithContent` rather than `Modifier.border` because `Surface`
 * applies the caller's modifier *before* painting its own background, so a
 * border added there would be painted over and lost.
 */
@Composable
fun Modifier.lpBottomEdge(): Modifier {
    val color = lpEdgeColor() ?: return this
    return this.drawWithContent {
        drawContent()
        val y = size.height - 0.5f
        drawLine(
            color = color,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f,
        )
    }
}

/** Draws a hairline along the top edge, on top of the content. */
@Composable
fun Modifier.lpTopEdge(): Modifier {
    val color = lpEdgeColor() ?: return this
    return this.drawWithContent {
        drawContent()
        drawLine(
            color = color,
            start = Offset(0f, 0.5f),
            end = Offset(size.width, 0.5f),
            strokeWidth = 1f,
        )
    }
}
