package com.lightfastread.data

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.lightfastread.R

data class FontFace(
    val key: String,
    val displayName: String,
    private val builder: () -> FontFamily,
) {
    val family: FontFamily by lazy { builder() }
}

object Fonts {
    private val registry: List<FontFace> = listOf(
        FontFace("Default", "System default") { FontFamily.Default },
        FontFace("Serif", "System serif") { FontFamily.Serif },
        FontFace("SansSerif", "System sans") { FontFamily.SansSerif },
        FontFace("Monospace", "System mono") { FontFamily.Monospace },
        FontFace("AtkinsonHyperlegible", "Atkinson Hyperlegible") {
            FontFamily(
                Font(R.font.atkinson_hyperlegible_regular, FontWeight.Normal),
                Font(R.font.atkinson_hyperlegible_bold, FontWeight.Bold),
            )
        },
        FontFace("Inter", "Inter") { variableFamily(R.font.inter) },
        FontFace("Lora", "Lora") { variableFamily(R.font.lora) },
        FontFace("Bitter", "Bitter") { variableFamily(R.font.bitter) },
        FontFace("Merriweather", "Merriweather") {
            FontFamily(
                Font(R.font.merriweather, FontWeight.Normal),
                Font(R.font.merriweather_bold, FontWeight.Bold),
            )
        },
        FontFace("JetBrainsMono", "JetBrains Mono") { variableFamily(R.font.jetbrains_mono) },
    )

    val all: List<FontFace> = registry

    fun byKey(key: String): FontFace = registry.firstOrNull { it.key == key } ?: registry[0]

    fun familyFor(key: String): FontFamily = byKey(key).family

    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    private fun variableFamily(resId: Int): FontFamily = FontFamily(
        Font(resId, FontWeight.Normal, FontStyle.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(resId, FontWeight.Bold, FontStyle.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    )
}
