package com.lightfastread.ui.light

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue

/**
 * A line to type on, in the LightOS idiom: no box, no filled container, no floating label — just
 * text sitting on a rule. Ported from the SDK's `LightTextField`, where the underline is three
 * design pixels and the field does not draw a background at all.
 *
 * `BasicTextField` rather than Material's `TextField` for the same reason the rest of `ui/light`
 * exists, plus one practical one: Material's version reserves ~56dp of height for decoration this
 * design has none of, and on a 472dp screen that matters.
 *
 * Note that text entry depends on there being a system IME to raise. LightOS's own keyboard is an
 * in-app component rather than an input method, so on a bare phone this field can be focused with
 * nothing to type into it — LightKeyboard installed is what makes it work.
 */
@Composable
fun LightTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    singleLine: Boolean = true,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
) {
    val colors = LightThemeTokens.colors
    Column(modifier.fillMaxWidth()) {
        if (label != null) {
            LightText(label, LightTextVariant.Superfine, lighten = true)
            Spacer(Modifier.height(4f.designVerticalPxToDp()))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = lightTextStyle(LightTextVariant.Copy).copy(color = colors.content),
            cursorBrush = SolidColor(colors.content),
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = KeyboardActions(onDone = { onImeAction() }, onGo = { onImeAction() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(5f.designVerticalPxToDp()))
        Box(
            Modifier
                .fillMaxWidth()
                .height(3f.designVerticalPxToDp())
                .background(colors.content),
        )
    }
}
