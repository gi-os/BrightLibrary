package com.lightfastread.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightfastread.calibre.ProgressSync
import com.lightfastread.calibre.ReadingState
import com.lightfastread.data.BionicMode
import com.gios.light.common.hw.WheelScroll
import kotlinx.coroutines.launch
import com.lightfastread.data.FontFace
import com.lightfastread.data.Fonts
import com.lightfastread.data.SettingsRepository
import com.lightfastread.data.SwipeMode
import com.lightfastread.data.ThemeMode
import com.lightfastread.data.TitleStyle
import com.lightfastread.ui.light.ColorMode
import com.lightfastread.ui.light.LightBarItem
import com.lightfastread.ui.light.LightIcon
import com.lightfastread.ui.light.LightIcons
import com.lightfastread.ui.light.LightRule
import com.lightfastread.ui.light.LightText
import com.lightfastread.ui.light.LightTextVariant
import com.lightfastread.ui.light.LightThemeTokens
import com.lightfastread.ui.light.LightTopBar
import com.lightfastread.ui.light.designVerticalPxToDp
import com.lightfastread.ui.light.gridUnitsAsDp
import com.lightfastread.ui.light.lightClickable
import com.lightfastread.ui.light.lightInset
import com.lightfastread.ui.light.lightTextStyle
import com.lightfastread.ui.light.verticalGridUnitsAsDp
import com.lightfastread.ui.reader.bionicAnnotated
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import kotlin.math.roundToInt
import androidx.compose.foundation.Canvas
import android.graphics.Color as AndroidColor

/**
 * Every setting the app has, in the LightOS idiom: a bar, a stack of rows, hairlines between
 * sections and nothing else. Selection is a pair of brackets, on/off is the SDK's own select
 * glyph, and the only Material widget left is the slider (see [LightSlider]).
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { SettingsRepository.get(context) }
    val s by repo.state
    val colors = LightThemeTokens.colors
    // The longest screen in the app, and the one the wheel earns its keep on.
    val scroll = rememberScrollState()
    WheelScroll(scroll)
    val scope = rememberCoroutineScope()
    var editingCalibre by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding(),
    ) {
        LightTopBar(
            title = "Settings",
            left = LightBarItem.Icon(LightIcons.Back, onClick = onBack),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .navigationBarsPadding()
                .verticalScroll(scroll)
                .padding(horizontal = lightInset()),
        ) {
            SectionTitle("Input")
            Caption(
                "Hold zones occupy the middle 3/5 (left 1/3 = back, right 2/3 = forward). " +
                    "The bottom 1/5 is a swipe band. Top 1/5 toggles the chapter bar.",
            )
            ToggleRow(
                label = "Show zone guides",
                caption = "Draws the gesture boundaries above. Handy while learning them, " +
                    "clutter once you know where they are.",
                checked = s.showZoneGuides,
                onToggle = { v -> repo.update { it.copy(showZoneGuides = v) } },
            )
            ToggleRow(
                label = "Volume keys turn pages",
                caption = "Down goes forward, up goes back: a page in a book, one step down " +
                    "a comic page. While a book is open the keys stop changing the volume.",
                checked = s.volumeKeysTurnPages,
                onToggle = { v -> repo.update { it.copy(volumeKeysTurnPages = v) } },
            )
            Spacer(Modifier.height(12f.designVerticalPxToDp()))
            RowLabel("Swipe band mode")
            SwipeMode.values().forEach { mode ->
                ChoiceRow(
                    label = when (mode) {
                        SwipeMode.Normal -> "Normal swipe"
                        SwipeMode.Zone -> "Zone swipe"
                    },
                    caption = when (mode) {
                        SwipeMode.Normal -> "Swipe right to advance, left to go back"
                        SwipeMode.Zone -> "Move finger in right 2/3 to advance, left 1/3 to go back (any direction)"
                    },
                    selected = s.swipeMode == mode,
                    onClick = { repo.update { it.copy(swipeMode = mode) } },
                )
            }
            IntSliderRow(
                label = "Swipe distance per word",
                unit = "dp",
                value = s.swipeDpPerWord,
                range = 2f..80f,
                step = 1,
                onChange = { v -> repo.update { it.copy(swipeDpPerWord = v) } }
            )

            SectionTitle("Speed")
            IntSliderRow(
                label = "Min WPM (left edge)",
                value = s.minWpm,
                range = 30f..400f,
                step = 10,
                onChange = { v ->
                    repo.update { it.copy(minWpm = v.coerceAtMost(it.maxWpm - 10)) }
                }
            )
            IntSliderRow(
                label = "Max WPM (right edge)",
                value = s.maxWpm,
                range = 100f..1500f,
                step = 10,
                onChange = { v ->
                    repo.update { it.copy(maxWpm = v.coerceAtLeast(it.minWpm + 10)) }
                }
            )
            IntSliderRow(
                label = "Speed ramp-up",
                unit = "ms",
                value = s.rampUpMs,
                range = 0f..2000f,
                step = 50,
                onChange = { v -> repo.update { it.copy(rampUpMs = v) } }
            )
            IntSliderRow(
                label = "Backward hold delay",
                unit = "ms",
                value = s.backwardHoldMs,
                range = 100f..2000f,
                step = 50,
                onChange = { v -> repo.update { it.copy(backwardHoldMs = v) } }
            )

            SectionTitle("Pauses")
            Caption(
                "Multiplier of the time between words at the current WPM. x2.0 = pause for 2 word-intervals.",
            )
            FloatSliderRow(
                label = "Pause after sentence",
                value = s.pauseAfterDotFactor,
                range = 0f..5f,
                step = 0.1f,
                onChange = { v -> repo.update { it.copy(pauseAfterDotFactor = v) } }
            )
            FloatSliderRow(
                label = "Pause after paragraph",
                value = s.pauseAfterParagraphFactor,
                range = 0f..5f,
                step = 0.1f,
                onChange = { v -> repo.update { it.copy(pauseAfterParagraphFactor = v) } }
            )
            FloatSliderRow(
                label = "Extra per letter (long words)",
                value = s.extraLetterFactor,
                range = 1f..1.5f,
                step = 0.01f,
                onChange = { v -> repo.update { it.copy(extraLetterFactor = v) } },
                display = { v -> if (v <= 1.0f) "off" else "x%.2f".format(v) },
            )
            IntSliderRow(
                label = "Letter delay threshold",
                value = s.letterDelayThreshold,
                range = 1f..12f,
                step = 1,
                onChange = { v -> repo.update { it.copy(letterDelayThreshold = v) } }
            )
            Caption(
                "Each letter beyond the threshold adds (factor − 1) × word-interval to that word's display time, so extra delay scales with the current WPM.",
            )

            SectionTitle("Text")
            IntSliderRow(
                label = "Font size",
                value = s.fontSizeSp,
                range = 16f..160f,
                step = 2,
                onChange = { v -> repo.update { it.copy(fontSizeSp = v) } }
            )
            Spacer(Modifier.height(8f.designVerticalPxToDp()))
            RowLabel("Preview")
            SampleFrame(height = 8f.verticalGridUnitsAsDp()) {
                val mainBionic = s.bionicMode == BionicMode.MainOnly || s.bionicMode == BionicMode.Both
                val previewText: AnnotatedString = if (mainBionic) {
                    bionicAnnotated(
                        "reading",
                        boldWeight = FontWeight(s.bionicBoldWeight),
                        lightWeight = FontWeight(s.bionicLightWeight),
                    )
                } else AnnotatedString("reading")
                Text(
                    text = previewText,
                    color = colors.content,
                    fontSize = s.fontSizeSp.sp,
                    fontFamily = Fonts.familyFor(s.fontFamily),
                    fontWeight = if (mainBionic) FontWeight.Normal else FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(12f.designVerticalPxToDp()))
            RowLabel("Font family")
            Fonts.all.forEach { face ->
                FontFamilyOption(
                    face = face,
                    selected = s.fontFamily == face.key,
                    onClick = { repo.update { it.copy(fontFamily = face.key) } },
                )
            }

            SectionTitle("Word fade")
            Caption(
                "Fades the focal word in and out over each interval. The percentage is the share of one word-interval (1 s at 60 WPM) split equally between fade-in and fade-out.",
            )
            FloatSliderRow(
                label = "Fade duration (% of interval)",
                value = s.wordFadeFraction,
                range = 0f..1f,
                step = 0.01f,
                onChange = { v -> repo.update { it.copy(wordFadeFraction = v) } },
                display = { v -> if (v <= 0f) "off" else "%.0f%%".format(v * 100) },
            )

            SectionTitle("Context text")
            Caption("Surrounding sentence shown above the focal word.")
            IntSliderRow(
                label = "Context font size",
                value = s.contextFontSizeSp,
                range = 8f..48f,
                step = 1,
                onChange = { v -> repo.update { it.copy(contextFontSizeSp = v) } },
            )
            FloatSliderRow(
                label = "Context transparency",
                value = s.contextAlpha,
                range = 0.05f..1f,
                step = 0.01f,
                onChange = { v -> repo.update { it.copy(contextAlpha = v) } },
                display = { v -> "%.0f%%".format((1f - v) * 100) },
            )

            SectionTitle("Bionic reading")
            Caption(
                "Bolds the first few letters of each word — the brain fills in the rest, helping fast reading.",
            )
            BionicMode.values().forEach { mode ->
                ChoiceRow(
                    label = when (mode) {
                        BionicMode.Off -> "Off"
                        BionicMode.MainOnly -> "Main word only"
                        BionicMode.ContextOnly -> "Context rows only"
                        BionicMode.Both -> "Both"
                    },
                    selected = s.bionicMode == mode,
                    onClick = { repo.update { it.copy(bionicMode = mode) } },
                )
            }
            if (s.bionicMode != BionicMode.Off) {
                Spacer(Modifier.height(8f.designVerticalPxToDp()))
                SampleFrame(height = 5f.verticalGridUnitsAsDp()) {
                    Text(
                        text = bionicAnnotated(
                            "reading",
                            boldWeight = FontWeight(s.bionicBoldWeight),
                            lightWeight = FontWeight(s.bionicLightWeight),
                        ),
                        color = colors.content,
                        fontSize = 32.sp,
                        fontFamily = Fonts.familyFor(s.fontFamily),
                    )
                }
                IntSliderRow(
                    label = "Bold weight",
                    value = s.bionicBoldWeight,
                    range = 100f..900f,
                    step = 100,
                    onChange = { v -> repo.update { it.copy(bionicBoldWeight = v) } },
                )
                IntSliderRow(
                    label = "Light weight",
                    value = s.bionicLightWeight,
                    range = 100f..900f,
                    step = 100,
                    onChange = { v -> repo.update { it.copy(bionicLightWeight = v) } },
                )
            }

            SectionTitle("ORP (focal point)")
            Caption(
                "Highlights one letter per word and horizontally aligns words so " +
                    "the focal letter sits at a fixed point on screen. Eyes stop " +
                    "moving between words.",
            )
            ToggleRow(
                label = "Enable ORP",
                checked = s.orpEnabled,
                onToggle = { v -> repo.update { it.copy(orpEnabled = v) } },
            )
            if (s.orpEnabled) {
                Spacer(Modifier.height(8f.designVerticalPxToDp()))
                SampleFrame(height = 5f.verticalGridUnitsAsDp()) {
                    val orpColor = Color(s.orpColorArgb)
                    Text(
                        text = buildAnnotatedString {
                            append("rea")
                            if (s.orpFocalSameColor) {
                                append("d")
                            } else {
                                withStyle(SpanStyle(color = orpColor)) {
                                    append("d")
                                }
                            }
                            append("ing")
                        },
                        color = colors.content,
                        fontSize = 32.sp,
                        fontFamily = Fonts.familyFor(s.fontFamily),
                    )
                }
                ToggleRow(
                    label = "Focal letter in body color",
                    caption = "Keep the alignment effect without coloring the focal letter.",
                    checked = s.orpFocalSameColor,
                    onToggle = { v -> repo.update { it.copy(orpFocalSameColor = v) } },
                )
                if (!s.orpFocalSameColor) {
                    Spacer(Modifier.height(8f.designVerticalPxToDp()))
                    OrpColorPicker(
                        argb = s.orpColorArgb,
                        onArgbChange = { v -> repo.update { it.copy(orpColorArgb = v) } },
                    )
                }
            }

            SectionTitle("Title style")
            Caption("How chapter titles inside the text stand out from body text.")
            TitleStyle.values().forEach { style ->
                ChoiceRow(
                    label = when (style) {
                        TitleStyle.Color -> "Color"
                        TitleStyle.Underline -> "Underline"
                        TitleStyle.Both -> "Color + underline"
                    },
                    selected = s.titleStyle == style,
                    onClick = { repo.update { it.copy(titleStyle = style) } },
                )
            }
            Spacer(Modifier.height(8f.designVerticalPxToDp()))
            TitleColorPicker(
                argb = s.titleColorArgb,
                onArgbChange = { v -> repo.update { it.copy(titleColorArgb = v) } },
                showColorControls = s.titleStyle != TitleStyle.Underline,
                underline = s.titleStyle != TitleStyle.Color,
            )

            SectionTitle("Appearance")
            RowLabel("Theme")
            Caption(
                "Light Phone uses pure #000000 on every surface so OLED pixels stay " +
                    "off, and flattens the title and ORP accents to grey because the " +
                    "panel is black and white anyway.",
            )
            ThemeMode.values().forEach { mode ->
                ChoiceRow(
                    label = themeModeLabel(mode),
                    selected = s.themeMode == mode,
                    onClick = { repo.update { it.copy(themeMode = mode) } },
                )
            }
            Spacer(Modifier.height(12f.designVerticalPxToDp()))
            ToggleRow(
                label = "Colour covers",
                caption = "Book covers show in full colour on the shelf. Everything else " +
                    "stays black and white.",
                checked = s.colorCovers,
                onToggle = { v -> repo.update { it.copy(colorCovers = v) } },
            ) {
                // Not an error and not a blocker: without the grant the shelf simply stays grey,
                // so the missing permission is worth a line of explanation and nothing louder.
                if (!ColorMode.granted(context)) {
                    Caption("adb shell pm grant com.lightfastread android.permission.WRITE_SECURE_SETTINGS")
                    Caption("Covers stay grey until that one-time grant is given.")
                }
            }

            SectionTitle("Calibre")
            Caption(
                "Browse a Calibre library over OPDS and download straight to the shelf — LIBRARY " +
                    "on the shelf's bottom bar.",
            )
            NavRow(
                label = "Server",
                caption = s.calibre.baseUrl.ifBlank { "Not set" },
                onClick = { editingCalibre = true },
            )
            ToggleRow(
                label = "Sync reading progress",
                caption = "Pushes your position back to calibre-web, and picks up where another " +
                    "device left off when you download a book.",
                checked = s.calibre.syncProgress,
                onToggle = { v -> repo.update { it.copy(calibre = it.calibre.copy(syncProgress = v)) } },
            ) {
                if (s.calibre.syncProgress && !ReadingState.configured(s.calibre)) {
                    Caption("Needs a Kobo sync URL — tap Server above.")
                }
            }
            if (ReadingState.configured(s.calibre)) {
                NavRow(
                    label = "Sync now",
                    caption = syncMessage ?: "Pushes anything the server has not been told yet.",
                    onClick = {
                        syncMessage = "Syncing…"
                        ProgressSync.flush(context)
                        // No result to wait for: the flush is fire-and-forget by design, because a
                        // phone off the network is the normal case and a failure is a retry, not an
                        // error. The books' own progress bars are the readout.
                        scope.launch {
                            kotlinx.coroutines.delay(1_500)
                            syncMessage = "Sent whatever was outstanding."
                        }
                    },
                )
            }
            Spacer(Modifier.height(2f.gridUnitsAsDp()))
        }
    }

    if (editingCalibre) {
        CalibreSettings(
            initial = s.calibre,
            onSave = { config ->
                editingCalibre = false
                repo.update { it.copy(calibre = config) }
            },
            onApply = { config -> repo.update { it.copy(calibre = config) } },
            onDismiss = { editingCalibre = false },
        )
    }
}

private fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.System -> "System"
    ThemeMode.Light -> "Light"
    ThemeMode.Dark -> "Dark"
    ThemeMode.LightPhone -> "Light Phone (true black)"
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(20f.designVerticalPxToDp()))
    LightText(
        text = text,
        variant = LightTextVariant.Subheading,
        modifier = Modifier.padding(bottom = 8f.designVerticalPxToDp()),
    )
    LightRule()
    Spacer(Modifier.height(8f.designVerticalPxToDp()))
}

/** Explanatory prose. Always the quietest thing on screen. */
@Composable
private fun Caption(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        lighten = true,
        modifier = Modifier.padding(bottom = 6f.designVerticalPxToDp()),
    )
}

@Composable
private fun RowLabel(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Copy,
        modifier = Modifier.padding(bottom = 4f.designVerticalPxToDp()),
    )
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    caption: String? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable { onToggle(!checked) }
            .padding(vertical = 8f.designVerticalPxToDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            LightText(label, LightTextVariant.Copy)
            if (caption != null) {
                LightText(caption, LightTextVariant.Detail, lighten = true)
            }
            extra?.invoke(this)
        }
        Spacer(Modifier.width(1f.gridUnitsAsDp()))
        LightIcon(
            icon = if (checked) LightIcons.SelectOn else LightIcons.SelectOff,
            contentDescription = label,
        )
    }
}

/**
 * A row that opens something else, or does something once.
 *
 * Distinct from [ChoiceRow] because a choice is a state — it brackets itself when it is the one in
 * force — and this is not. Brackets on a row that merely opens a page read as "this option is
 * selected", which is exactly the wrong thing to say about an address.
 */
@Composable
private fun NavRow(label: String, caption: String?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 6f.designVerticalPxToDp()),
    ) {
        LightText(label, LightTextVariant.Button)
        if (caption != null) {
            LightText(
                text = caption,
                variant = LightTextVariant.Detail,
                lighten = true,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One option out of a set. The chosen one is bracketed rather than tinted or ticked: the panel is
 * matte greyscale, and a change of shade alone does not read at arm's length.
 */
@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    caption: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 6f.designVerticalPxToDp()),
    ) {
        LightText(
            text = if (selected) "[ $label ]" else label,
            variant = LightTextVariant.Button,
            lighten = !selected,
        )
        if (caption != null) {
            LightText(caption, LightTextVariant.Detail, lighten = true)
        }
    }
}

/**
 * The slider is the one Material control left in the app. LightOS has none, but the alternative
 * for WPM and font size is a numeric stepper, and tapping a plus sign eighty times to cross a
 * range is worse than borrowing a widget. Stripped to two shades and no tick marks so it reads as
 * a rule with a dot on it.
 */
@Composable
private fun LightSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
) {
    val colors = LightThemeTokens.colors
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        colors = SliderDefaults.colors(
            thumbColor = colors.content,
            activeTrackColor = colors.content,
            inactiveTrackColor = colors.rule,
        ),
    )
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4f.designVerticalPxToDp())) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            LightText(label, LightTextVariant.Copy)
            LightText(valueText, LightTextVariant.Superfine)
        }
        LightSlider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}

@Composable
private fun IntSliderRow(
    label: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    step: Int,
    onChange: (Int) -> Unit,
    unit: String? = null,
) {
    SliderRow(
        label = label,
        valueText = if (unit == null) value.toString() else "$value $unit",
        value = value.toFloat().coerceIn(range),
        range = range,
        onValueChange = { v -> onChange((v / step).roundToInt() * step) },
    )
}

@Composable
private fun FloatSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    onChange: (Float) -> Unit,
    display: (Float) -> String = { v -> if (v <= 0f) "off" else "x%.1f".format(v) },
) {
    SliderRow(
        label = label,
        valueText = display(value),
        value = value.coerceIn(range),
        range = range,
        onValueChange = { v -> onChange((v / step).roundToInt() * step) },
    )
}

/** Type set at a size and face the user chose, which is the one thing the scale cannot say. */
@Composable
private fun SampleFrame(height: Dp, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        LightRule()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
        LightRule()
    }
}

@Composable
private fun FontFamilyOption(
    face: FontFace,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LightThemeTokens.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 6f.designVerticalPxToDp()),
    ) {
        // Each face names itself in its own typeface, so this one row cannot go through LightText:
        // the variant carries the size and tracking, the face overrides only the family.
        Text(
            text = if (selected) "[ ${face.displayName} ]" else face.displayName,
            style = lightTextStyle(LightTextVariant.Button).copy(fontFamily = face.family),
            color = if (selected) colors.content else colors.contentSecondary,
        )
    }
}

@Composable
private fun TitleColorPicker(
    argb: Int,
    onArgbChange: (Int) -> Unit,
    showColorControls: Boolean = true,
    underline: Boolean = false,
) {
    val colors = LightThemeTokens.colors
    // Initialize from the persisted ARGB once on first composition. Sliders are
    // the source of truth thereafter — re-deriving hue/sat/value on every argb
    // change would cause float-rounding jitter as the user drags.
    val initialHsv = remember {
        FloatArray(3).also { AndroidColor.colorToHSV(argb, it) }
    }
    var hue by remember { mutableStateOf(initialHsv[0]) }
    var sat by remember { mutableStateOf(initialHsv[1]) }
    var value by remember { mutableStateOf(initialHsv[2]) }

    fun emit() {
        onArgbChange(AndroidColor.HSVToColor(floatArrayOf(hue, sat, value)))
    }

    val previewColor = Color(AndroidColor.HSVToColor(floatArrayOf(hue, sat, value)))

    Column(modifier = Modifier.fillMaxWidth()) {
        // Preview swatch with sample title text on top.
        SampleFrame(height = 3.5f.verticalGridUnitsAsDp()) {
            LightText(
                text = "Chapter Title",
                variant = LightTextVariant.Subheading,
                color = if (showColorControls) previewColor else colors.content,
                underline = underline,
            )
        }

        if (showColorControls) {
            Spacer(Modifier.height(8f.designVerticalPxToDp()))
            RowLabel("Hue")
            HueBar(
                hue = hue,
                onHueChange = { hue = it; emit() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2f.verticalGridUnitsAsDp())
                    .padding(vertical = 4f.designVerticalPxToDp()),
            )
            SliderRow(
                label = "Saturation",
                valueText = "%.0f%%".format(sat * 100),
                value = sat,
                range = 0f..1f,
                onValueChange = { sat = it; emit() },
            )
            SliderRow(
                label = "Brightness",
                valueText = "%.0f%%".format(value * 100),
                value = value,
                range = 0f..1f,
                onValueChange = { value = it; emit() },
            )
        }
    }
}

@Composable
private fun OrpColorPicker(
    argb: Int,
    onArgbChange: (Int) -> Unit,
) {
    val initialHsv = remember {
        FloatArray(3).also { AndroidColor.colorToHSV(argb, it) }
    }
    var hue by remember { mutableStateOf(initialHsv[0]) }
    var sat by remember { mutableStateOf(initialHsv[1]) }
    var value by remember { mutableStateOf(initialHsv[2]) }

    fun emit() {
        onArgbChange(AndroidColor.HSVToColor(floatArrayOf(hue, sat, value)))
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        RowLabel("Hue")
        HueBar(
            hue = hue,
            onHueChange = { hue = it; emit() },
            modifier = Modifier
                .fillMaxWidth()
                .height(2f.verticalGridUnitsAsDp())
                .padding(vertical = 4f.designVerticalPxToDp()),
        )
        SliderRow(
            label = "Saturation",
            valueText = "%.0f%%".format(sat * 100),
            value = sat,
            range = 0f..1f,
            onValueChange = { sat = it; emit() },
        )
        SliderRow(
            label = "Brightness",
            valueText = "%.0f%%".format(value * 100),
            value = value,
            range = 0f..1f,
            onValueChange = { value = it; emit() },
        )
    }
}

/**
 * The one place in the app that draws in colour on purpose. Left exactly as it was: the panel is
 * greyscale, so the gradient is a grey ramp there, but the value it picks is real and shows up on
 * any other phone the APK is sideloaded onto.
 */
@Composable
private fun HueBar(
    hue: Float,
    onHueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hueColors = remember {
        // 7 stops covering 0..360 degrees so the gradient is smooth.
        listOf(
            Color(AndroidColor.HSVToColor(floatArrayOf(0f, 1f, 1f))),
            Color(AndroidColor.HSVToColor(floatArrayOf(60f, 1f, 1f))),
            Color(AndroidColor.HSVToColor(floatArrayOf(120f, 1f, 1f))),
            Color(AndroidColor.HSVToColor(floatArrayOf(180f, 1f, 1f))),
            Color(AndroidColor.HSVToColor(floatArrayOf(240f, 1f, 1f))),
            Color(AndroidColor.HSVToColor(floatArrayOf(300f, 1f, 1f))),
            Color(AndroidColor.HSVToColor(floatArrayOf(360f, 1f, 1f))),
        )
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.horizontalGradient(hueColors))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    onHueChange((down.position.x / w * 360f).coerceIn(0f, 360f))
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.first()
                        onHueChange((ch.position.x / w * 360f).coerceIn(0f, 360f))
                        if (!ch.pressed) break
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val x = (hue / 360f) * size.width
            val thumbStroke = 3f
            drawRect(
                color = Color.White,
                topLeft = Offset(x - 6f, 0f),
                size = Size(12f, size.height),
                style = Stroke(width = thumbStroke),
            )
            drawRect(
                color = Color.Black,
                topLeft = Offset(x - 6f - thumbStroke, 0f),
                size = Size(12f + thumbStroke * 2f, size.height),
                style = Stroke(width = 1f),
            )
        }
    }
}
