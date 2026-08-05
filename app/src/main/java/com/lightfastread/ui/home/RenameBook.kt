package com.lightfastread.ui.home

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lightfastread.data.Book
import com.lightfastread.ui.light.LightBarItem
import com.lightfastread.ui.light.LightBottomBar
import com.lightfastread.ui.light.LightRule
import com.lightfastread.ui.light.LightText
import com.lightfastread.ui.light.LightTextField
import com.lightfastread.ui.light.LightTextVariant
import com.lightfastread.ui.light.LightThemeTokens
import com.lightfastread.ui.light.designVerticalPxToDp
import com.lightfastread.ui.light.gridUnitsAsDp
import com.lightfastread.ui.light.lightInset

/**
 * Fix a book's title and author.
 *
 * This exists because of covers. An ebook's own metadata is often a filename, a publisher's
 * marketing subtitle, or an author field holding `Smith, John; Jones, Mary` — and a catalogue
 * lookup can only be as good as what it is handed. Correcting the name is the one thing a person
 * can do that a heuristic cannot, so saving here searches again immediately rather than making you
 * find FIND COVER afterwards.
 *
 * The title is selected on open: the common edit is replacing the whole thing, not amending it.
 */
@Composable
fun RenameBook(
    book: Book,
    onSave: (title: String, author: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LightThemeTokens.colors
    var title by remember(book.id) {
        mutableStateOf(TextFieldValue(book.title, TextRange(0, book.title.length)))
    }
    var author by remember(book.id) { mutableStateOf(TextFieldValue(book.author)) }
    val save = { onSave(title.text, author.text) }

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
                Column(Modifier.fillMaxWidth()) {
                    LightText("Name", LightTextVariant.Subheading)
                    Spacer(Modifier.height(1f.gridUnitsAsDp()))
                    LightTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = "TITLE",
                        imeAction = ImeAction.Next,
                    )
                    Spacer(Modifier.height(1f.gridUnitsAsDp()))
                    LightTextField(
                        value = author,
                        onValueChange = { author = it },
                        label = "AUTHOR",
                        onImeAction = save,
                    )
                    Spacer(Modifier.height(0.7f.gridUnitsAsDp()))
                    LightText(
                        text = "Saving looks for a cover again with the new name.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                    )
                }
            }
            LightRule()
            LightBottomBar(
                items = listOf(
                    LightBarItem.Text(text = "CANCEL", lighten = true, onClick = onDismiss),
                    LightBarItem.Text(text = "SAVE", onClick = save),
                ),
            )
        }
    }
}
