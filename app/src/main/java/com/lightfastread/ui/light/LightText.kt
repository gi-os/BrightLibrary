package com.lightfastread.ui.light

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

/**
 * The SDK's type scale, by name. Ported from `lightphone/light-sdk` (MIT).
 *
 * Using variants rather than raw sizes is what keeps a tool looking like LightOS: the scale
 * is deliberately coarse, and picking from it is a decision about what a piece of text *is*,
 * not how big it should be.
 */
enum class LightTextVariant {
    Title,
    Subtitle,
    Heading,
    Subheading,
    Copy,
    Button,
    Paragraph,
    ParagraphWide,
    Detail,
    Fine,
    Superfine,
    Micro,
}

@Composable
fun lightTextStyle(variant: LightTextVariant): TextStyle {
    val t = LightThemeTokens.typography
    val base = when (variant) {
        LightTextVariant.Title -> t.title
        LightTextVariant.Subtitle -> t.subtitle
        LightTextVariant.Heading -> t.heading
        LightTextVariant.Subheading -> t.subheading
        LightTextVariant.Copy -> t.copy
        LightTextVariant.Button -> t.button
        LightTextVariant.Paragraph -> t.paragraph
        LightTextVariant.ParagraphWide -> t.paragraphWide
        LightTextVariant.Detail -> t.detail
        LightTextVariant.Fine -> t.fine
        LightTextVariant.Superfine -> t.superfine
        LightTextVariant.Micro -> t.micro
    }
    return base.scaledForScreenHeight()
}

@Composable
internal fun TextStyle.scaledForScreenHeight(): TextStyle = copy(
    fontSize = fontSize.scaledForScreenHeight(),
    lineHeight = lineHeight.scaledForScreenHeight(),
    letterSpacing = letterSpacing.scaledForScreenHeight(),
)

@Composable
internal fun TextUnit.scaledForScreenHeight(): TextUnit {
    if (this == TextUnit.Unspecified) return this
    return value.designVerticalPxToSp()
}

@Composable
fun LightText(
    text: String,
    variant: LightTextVariant,
    modifier: Modifier = Modifier,
    align: TextAlign? = null,
    lighten: Boolean = false,
    underline: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    color: Color? = null,
) {
    val colors = LightThemeTokens.colors
    Text(
        text = text,
        modifier = modifier,
        color = color ?: if (lighten) colors.contentSecondary else colors.content,
        style = lightTextStyle(variant)
            .let { if (align != null) it.copy(textAlign = align) else it }
            .let { if (underline) it.copy(textDecoration = TextDecoration.Underline) else it },
        maxLines = maxLines,
        overflow = overflow,
    )
}

/** Same, for text that carries its own emphasis spans. */
@Composable
fun LightText(
    text: AnnotatedString,
    variant: LightTextVariant,
    modifier: Modifier = Modifier,
    lighten: Boolean = false,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val colors = LightThemeTokens.colors
    Text(
        text = text,
        modifier = modifier,
        color = if (lighten) colors.contentSecondary else colors.content,
        style = lightTextStyle(variant).let { if (align != null) it.copy(textAlign = align) else it },
        maxLines = maxLines,
        overflow = overflow,
    )
}
