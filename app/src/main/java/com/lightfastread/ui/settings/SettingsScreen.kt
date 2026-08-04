package com.lightfastread.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightfastread.data.BionicMode
import com.gios.light.common.hw.WheelScroll
import com.lightfastread.data.FontFace
import com.lightfastread.data.Fonts
import com.lightfastread.data.SettingsRepository
import com.lightfastread.data.SwipeMode
import com.lightfastread.data.ThemeMode
import com.lightfastread.data.TitleStyle
import com.lightfastread.ui.reader.bionicAnnotated
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import kotlin.math.roundToInt
import androidx.compose.foundation.Canvas
import android.graphics.Color as AndroidColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { SettingsRepository.get(context) }
    val s by repo.state
    // The longest screen in the app, and the one the wheel earns its keep on.
    val scroll = rememberScrollState()
    WheelScroll(scroll)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            SectionTitle("Input")
            Text(
                "Hold zones occupy the middle 3/5 (left 1/3 = back, right 2/3 = forward). " +
                    "The bottom 1/5 is a swipe band. Top 1/5 toggles the chapter bar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = s.showZoneGuides,
                        onClick = { repo.update { it.copy(showZoneGuides = !s.showZoneGuides) } },
                        role = Role.Switch,
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(
                    checked = s.showZoneGuides,
                    onCheckedChange = { v -> repo.update { it.copy(showZoneGuides = v) } },
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Show zone guides", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Draws the gesture boundaries above. Handy while learning them, " +
                            "clutter once you know where they are.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Swipe band mode", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Column(modifier = Modifier.selectableGroup()) {
                SwipeMode.values().forEach { mode ->
                    val selected = s.swipeMode == mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected,
                                onClick = { repo.update { it.copy(swipeMode = mode) } },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                when (mode) {
                                    SwipeMode.Normal -> "Normal swipe"
                                    SwipeMode.Zone -> "Zone swipe"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                when (mode) {
                                    SwipeMode.Normal -> "Swipe right to advance, left to go back"
                                    SwipeMode.Zone -> "Move finger in right 2/3 to advance, left 1/3 to go back (any direction)"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
            }
            IntSliderRow(
                label = "Swipe distance per word (dp)",
                value = s.swipeDpPerWord,
                range = 2f..80f,
                step = 1,
                onChange = { v -> repo.update { it.copy(swipeDpPerWord = v) } }
            )

            Spacer(Modifier.height(16.dp))
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
                label = "Speed ramp-up (ms)",
                value = s.rampUpMs,
                range = 0f..2000f,
                step = 50,
                onChange = { v -> repo.update { it.copy(rampUpMs = v) } }
            )
            IntSliderRow(
                label = "Backward hold delay (ms)",
                value = s.backwardHoldMs,
                range = 100f..2000f,
                step = 50,
                onChange = { v -> repo.update { it.copy(backwardHoldMs = v) } }
            )

            Spacer(Modifier.height(16.dp))
            SectionTitle("Pauses")
            Text(
                "Multiplier of the time between words at the current WPM. x2.0 = pause for 2 word-intervals.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(4.dp))
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
            Text(
                "Each letter beyond the threshold adds (factor − 1) × word-interval to that word's display time, so extra delay scales with the current WPM.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )

            Spacer(Modifier.height(16.dp))
            SectionTitle("Text")
            IntSliderRow(
                label = "Font size",
                value = s.fontSizeSp,
                range = 16f..160f,
                step = 2,
                onChange = { v -> repo.update { it.copy(fontSizeSp = v) } }
            )
            Spacer(Modifier.height(8.dp))
            Text("Preview", style = MaterialTheme.typography.labelMedium)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
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
                        fontSize = s.fontSizeSp.sp,
                        fontFamily = Fonts.familyFor(s.fontFamily),
                        fontWeight = if (mainBionic) FontWeight.Normal else FontWeight.Medium,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Font family", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            Column(modifier = Modifier.selectableGroup()) {
                Fonts.all.forEach { face ->
                    FontFamilyOption(
                        face = face,
                        selected = s.fontFamily == face.key,
                        onClick = { repo.update { it.copy(fontFamily = face.key) } },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle("Word fade")
            Text(
                "Fades the focal word in and out over each interval. The percentage is the share of one word-interval (1 s at 60 WPM) split equally between fade-in and fade-out.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
            FloatSliderRow(
                label = "Fade duration (% of interval)",
                value = s.wordFadeFraction,
                range = 0f..1f,
                step = 0.01f,
                onChange = { v -> repo.update { it.copy(wordFadeFraction = v) } },
                display = { v -> if (v <= 0f) "off" else "%.0f%%".format(v * 100) },
            )

            Spacer(Modifier.height(16.dp))
            SectionTitle("Context text")
            Text(
                "Surrounding sentence shown above the focal word.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
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

            Spacer(Modifier.height(16.dp))
            SectionTitle("Bionic reading")
            Text(
                "Bolds the first few letters of each word — the brain fills in the rest, helping fast reading.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(8.dp))
            Column(modifier = Modifier.selectableGroup()) {
                BionicMode.values().forEach { mode ->
                    val selected = s.bionicMode == mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected,
                                onClick = { repo.update { it.copy(bionicMode = mode) } },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (mode) {
                                BionicMode.Off -> "Off"
                                BionicMode.MainOnly -> "Main word only"
                                BionicMode.ContextOnly -> "Context rows only"
                                BionicMode.Both -> "Both"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
            if (s.bionicMode != BionicMode.Off) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 2.dp,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = bionicAnnotated(
                                "reading",
                                boldWeight = FontWeight(s.bionicBoldWeight),
                                lightWeight = FontWeight(s.bionicLightWeight),
                            ),
                            fontSize = 32.sp,
                            fontFamily = Fonts.familyFor(s.fontFamily),
                        )
                    }
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

            Spacer(Modifier.height(16.dp))
            SectionTitle("ORP (focal point)")
            Text(
                "Highlights one letter per word and horizontally aligns words so " +
                    "the focal letter sits at a fixed point on screen. Eyes stop " +
                    "moving between words.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = s.orpEnabled,
                        onClick = { repo.update { it.copy(orpEnabled = !s.orpEnabled) } },
                        role = Role.Switch,
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(
                    checked = s.orpEnabled,
                    onCheckedChange = { v -> repo.update { it.copy(orpEnabled = v) } },
                )
                Spacer(Modifier.width(12.dp))
                Text("Enable ORP", style = MaterialTheme.typography.bodyLarge)
            }
            if (s.orpEnabled) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 2.dp,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp),
                        contentAlignment = Alignment.Center,
                    ) {
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
                            fontSize = 32.sp,
                            fontFamily = Fonts.familyFor(s.fontFamily),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = s.orpFocalSameColor,
                            onClick = { repo.update { it.copy(orpFocalSameColor = !s.orpFocalSameColor) } },
                            role = Role.Switch,
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(
                        checked = s.orpFocalSameColor,
                        onCheckedChange = { v -> repo.update { it.copy(orpFocalSameColor = v) } },
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Focal letter in body color", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Keep the alignment effect without coloring the focal letter.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        )
                    }
                }
                if (!s.orpFocalSameColor) {
                    Spacer(Modifier.height(8.dp))
                    OrpColorPicker(
                        argb = s.orpColorArgb,
                        onArgbChange = { v -> repo.update { it.copy(orpColorArgb = v) } },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle("Title style")
            Text(
                "How chapter titles inside the text stand out from body text.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(8.dp))
            Column(modifier = Modifier.selectableGroup()) {
                TitleStyle.values().forEach { style ->
                    val selected = s.titleStyle == style
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected,
                                onClick = { repo.update { it.copy(titleStyle = style) } },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (style) {
                                TitleStyle.Color -> "Color"
                                TitleStyle.Underline -> "Underline"
                                TitleStyle.Both -> "Color + underline"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            TitleColorPicker(
                argb = s.titleColorArgb,
                onArgbChange = { v -> repo.update { it.copy(titleColorArgb = v) } },
                showColorControls = s.titleStyle != TitleStyle.Underline,
                underline = s.titleStyle != TitleStyle.Color,
            )

            Spacer(Modifier.height(16.dp))
            SectionTitle("Appearance")
            Text("Theme", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Light Phone uses pure #000000 on every surface so OLED pixels stay " +
                    "off, and flattens the title and ORP accents to grey because the " +
                    "panel is black and white anyway.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(8.dp))
            Column(modifier = Modifier.selectableGroup()) {
                ThemeMode.values().forEach { mode ->
                    val selected = s.themeMode == mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected,
                                onClick = { repo.update { it.copy(themeMode = mode) } },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(themeModeLabel(mode))
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
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
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
    )
}

@Composable
private fun IntSliderRow(
    label: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    step: Int,
    onChange: (Int) -> Unit,
) {
    val steps = ((range.endInclusive - range.start) / step).toInt() - 1
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value.toString(), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value.toFloat().coerceIn(range),
            onValueChange = { v ->
                val rounded = (v / step).roundToInt() * step
                onChange(rounded)
            },
            valueRange = range,
            steps = steps.coerceAtLeast(0),
        )
    }
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
    val steps = ((range.endInclusive - range.start) / step).roundToInt() - 1
    val displayText = display(value)
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(displayText, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = { v ->
                val rounded = (v / step).roundToInt() * step
                onChange(rounded)
            },
            valueRange = range,
            steps = steps.coerceAtLeast(0),
        )
    }
}

@Composable
private fun FontFamilyOption(
    face: FontFace,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(
            face.displayName,
            fontFamily = face.family,
            style = MaterialTheme.typography.bodyLarge,
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
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            tonalElevation = 2.dp,
            shape = MaterialTheme.shapes.medium,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Chapter Title",
                    color = if (showColorControls) previewColor else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (underline) androidx.compose.ui.text.style.TextDecoration.Underline else null,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        if (showColorControls) {
            Spacer(Modifier.height(8.dp))
            Text("Hue", style = MaterialTheme.typography.labelMedium)
            HueBar(
                hue = hue,
                onHueChange = { hue = it; emit() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .padding(vertical = 4.dp),
            )

            Spacer(Modifier.height(4.dp))
            Text("Saturation", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = sat,
                onValueChange = { sat = it; emit() },
                valueRange = 0f..1f,
            )

            Text("Brightness", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = value,
                onValueChange = { value = it; emit() },
                valueRange = 0f..1f,
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
        Text("Hue", style = MaterialTheme.typography.labelMedium)
        HueBar(
            hue = hue,
            onHueChange = { hue = it; emit() },
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .padding(vertical = 4.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text("Saturation", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = sat,
            onValueChange = { sat = it; emit() },
            valueRange = 0f..1f,
        )
        Text("Brightness", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = value,
            onValueChange = { value = it; emit() },
            valueRange = 0f..1f,
        )
    }
}

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
