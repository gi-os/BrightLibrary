package com.lightfastread.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lightfastread.ui.light.LightBarItem
import com.lightfastread.ui.light.LightBottomBar
import com.lightfastread.ui.light.LightRule
import com.lightfastread.ui.light.LightText
import com.lightfastread.ui.light.LightTextField
import com.lightfastread.ui.light.LightTextVariant
import com.lightfastread.ui.light.LightThemeTokens
import com.lightfastread.ui.light.gridUnitsAsDp
import com.lightfastread.ui.light.lightInset

/**
 * Ask the server for a book by name.
 *
 * A page rather than a floating field, like every other decision in this app — LightOS has no
 * dialogs. Needs a system IME to raise, which on a Light Phone III means LightKeyboard: the phone's
 * own keyboard is an in-app component rather than an input method.
 */
@Composable
fun LibrarySearch(onSearch: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = LightThemeTokens.colors
    var query by remember { mutableStateOf(TextFieldValue("")) }
    val submit = { onSearch(query.text) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = lightInset()),
                verticalArrangement = Arrangement.Center,
            ) {
                LightText("Search the library", LightTextVariant.Subheading)
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
                LightTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "TITLE OR AUTHOR",
                    imeAction = ImeAction.Search,
                    onImeAction = submit,
                )
                Spacer(Modifier.height(0.7f.gridUnitsAsDp()))
                LightText(
                    text = "Searches the whole Calibre library, not just this shelf.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                )
            }
            LightRule()
            LightBottomBar(
                items = listOf(
                    LightBarItem.Text(text = "CANCEL", lighten = true, onClick = onDismiss),
                    LightBarItem.Text(text = "SEARCH", onClick = submit),
                ),
            )
        }
    }
}
