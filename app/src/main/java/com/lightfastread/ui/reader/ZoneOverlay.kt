package com.lightfastread.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import com.lightfastread.data.SwipeMode
import com.lightfastread.ui.theme.LocalIsLightPhone

@Composable
fun ZoneOverlay(swipeMode: SwipeMode, bottomDeadZonePx: Float) {
    val lineColor = MaterialTheme.colorScheme.onBackground
    // 18% white is a readable hairline on a glossy panel. The LPIII's matte
    // glass diffuses it into nothing, so the zone guides get a lift there.
    val guideAlpha = if (LocalIsLightPhone.current) 0.30f else 0.18f
    Canvas(modifier = Modifier.fillMaxSize().alpha(guideAlpha)) {
        val w = size.width
        val h = size.height
        val topStrip = h * 0.2f
        val bottomDead = (h - bottomDeadZonePx).coerceAtLeast(topStrip)
        val swipeStart = (h * 0.8f).coerceIn(topStrip, bottomDead)
        val divider = w / 3f

        // Hold-zone vertical divider in the middle 3/5 (always present).
        drawLine(
            color = lineColor,
            start = Offset(divider, topStrip),
            end = Offset(divider, swipeStart),
            strokeWidth = 2f,
        )
        // Swipe-band vertical divider only for Zone swipe mode.
        if (swipeMode == SwipeMode.Zone && swipeStart < bottomDead) {
            drawLine(
                color = lineColor,
                start = Offset(divider, swipeStart),
                end = Offset(divider, bottomDead),
                strokeWidth = 2f,
            )
        }
        drawLine(
            color = lineColor,
            start = Offset(0f, topStrip),
            end = Offset(w, topStrip),
            strokeWidth = 1f,
        )
        if (swipeStart > topStrip && swipeStart < bottomDead) {
            drawLine(
                color = lineColor,
                start = Offset(0f, swipeStart),
                end = Offset(w, swipeStart),
                strokeWidth = 1f,
            )
        }
        drawLine(
            color = lineColor,
            start = Offset(0f, bottomDead),
            end = Offset(w, bottomDead),
            strokeWidth = 1f,
        )
    }
}
