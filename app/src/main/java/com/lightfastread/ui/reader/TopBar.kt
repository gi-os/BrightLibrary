package com.lightfastread.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lightfastread.ui.light.LightBarItem
import com.lightfastread.ui.light.LightIcons
import com.lightfastread.ui.light.LightRule
import com.lightfastread.ui.light.LightThemeTokens
import com.lightfastread.ui.light.LightTopBar

/**
 * The word reader's top bar, revealed by tapping the top of the screen.
 *
 * Opaque and edged with a rule rather than translucent over the text. Upstream faded the bar to
 * 95% over a Material surface, which on this panel is a black bar over black text — the fade
 * bought nothing and the tonal elevation that was supposed to separate them is a no-op at pure
 * black.
 *
 * Back leads to the book's pages, not out of the book: the page view is where a book opens, and
 * this screen is the mode you asked for from there.
 */
@Composable
fun TopBar(
    title: String,
    onBack: () -> Unit,
    onQuickSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(LightThemeTokens.colors.background)
            .statusBarsPadding(),
    ) {
        LightTopBar(
            title = title,
            left = LightBarItem.Icon(icon = LightIcons.Back, onClick = onBack),
            right = LightBarItem.Icon(icon = LightIcons.Settings, onClick = onQuickSettings),
        )
        LightRule()
    }
}
