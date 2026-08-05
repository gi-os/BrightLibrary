package com.lightfastread.ui.light

import android.graphics.fonts.SystemFonts
import android.os.Build
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * LightOS phones ship with Akkurat; use it so the app matches the system UI.
 *
 * [SystemFonts] is API 29, and this fork keeps upstream's `minSdk 24` so it still installs on
 * an old test device — where it simply falls back to the platform default.
 */
fun akkuratFamilyOrDefault(): FontFamily {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return FontFamily.Default
    return runCatching {
        val fonts = SystemFonts.getAvailableFonts()
            .filter { it.file?.name?.startsWith("Akkurat", ignoreCase = true) == true }
            .mapNotNull { f ->
                val file = f.file ?: return@mapNotNull null
                val style = if (f.style.slant != 0) FontStyle.Italic else FontStyle.Normal
                Font(file = file, weight = FontWeight(f.style.weight), style = style)
            }
        if (fonts.isNotEmpty()) FontFamily(fonts) else FontFamily.Default
    }.getOrDefault(FontFamily.Default)
}
