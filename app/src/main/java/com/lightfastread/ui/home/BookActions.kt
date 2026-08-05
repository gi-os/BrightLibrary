package com.lightfastread.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lightfastread.data.Book
import com.lightfastread.ui.light.LightRule
import com.lightfastread.ui.light.LightText
import com.lightfastread.ui.light.LightTextVariant
import com.lightfastread.ui.light.LightThemeTokens
import com.lightfastread.ui.light.designVerticalPxToDp
import com.lightfastread.ui.light.gridUnitsAsDp
import com.lightfastread.ui.light.lightClickable
import com.lightfastread.ui.light.lightInset

/**
 * What you can do to a book, on a long press.
 *
 * A full-screen page rather than an AlertDialog. LightOS has no floating dialogs — a decision
 * takes over the screen and then gives it back — and on a black panel a Material dialog is a
 * grey slab with a scrim that tints nothing behind it.
 */
@Composable
fun BookActions(
    book: Book,
    onFindCover: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LightThemeTokens.colors
    var confirmingDelete by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(horizontal = lightInset()),
            verticalArrangement = Arrangement.Center,
        ) {
            LightText(
                text = book.title,
                variant = LightTextVariant.Subheading,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (book.author.isNotBlank()) {
                LightText(book.author, LightTextVariant.Detail, lighten = true, maxLines = 1)
            }
            Spacer(Modifier.height(1f.gridUnitsAsDp()))
            LightRule()

            if (confirmingDelete) {
                // Two taps to lose a book, and the second one says what it does rather than
                // asking again in a smaller box.
                ActionRow(text = "DELETE — TAP TO CONFIRM", onClick = onDelete)
                ActionRow(text = "KEEP IT", onClick = { confirmingDelete = false })
            } else {
                ActionRow(text = "FIND COVER", onClick = onFindCover)
                // Directly under FIND COVER, because it is the thing to try when that keeps
                // failing: a lookup is only as good as the title it is given.
                ActionRow(text = "FIX NAME", onClick = onRename)
                ActionRow(text = "DELETE", onClick = { confirmingDelete = true })
                ActionRow(text = "CANCEL", lighten = true, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun ActionRow(
    text: String,
    lighten: Boolean = false,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().lightClickable(onClick = onClick)) {
        LightText(
            text = text,
            variant = LightTextVariant.Button,
            lighten = lighten,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(vertical = 14f.designVerticalPxToDp()),
        )
        LightRule()
    }
}
