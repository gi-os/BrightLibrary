package com.lightfastread.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lightfastread.calibre.CalibreClient
import com.lightfastread.calibre.ReadingState
import com.lightfastread.data.CalibreConfig
import com.gios.light.common.hw.WheelInDialog
import com.gios.light.common.hw.WheelScroll
import com.lightfastread.ui.light.LightBarItem
import com.lightfastread.ui.light.LightBottomBar
import com.lightfastread.ui.light.LightRule
import com.lightfastread.ui.light.LightText
import com.lightfastread.ui.light.LightTextField
import com.lightfastread.ui.light.LightTextVariant
import com.lightfastread.ui.light.LightThemeTokens
import com.lightfastread.ui.light.designVerticalPxToDp
import com.lightfastread.ui.light.gridUnitsAsDp
import com.lightfastread.ui.light.lightClickable
import com.lightfastread.ui.light.lightInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where the Calibre library is.
 *
 * Its own page because it is four text fields, and four text fields do not belong in a scrolling list
 * of toggles — the IME would cover whichever one you were typing into. Reached from the Calibre
 * section of Settings.
 *
 * TEST is on this page rather than in Settings on purpose: the address you want to test is the one
 * currently on screen, not the one that was saved before you started editing.
 */
@Composable
fun CalibreSettings(
    initial: CalibreConfig,
    onSave: (CalibreConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LightThemeTokens.colors
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    WheelScroll(scroll)

    var url by remember { mutableStateOf(TextFieldValue(initial.baseUrl)) }
    var user by remember { mutableStateOf(TextFieldValue(initial.username)) }
    var password by remember { mutableStateOf(TextFieldValue(initial.password)) }
    var kobo by remember { mutableStateOf(TextFieldValue(initial.koboUrl)) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    val edited = {
        CalibreConfig(
            baseUrl = url.text.trim(),
            username = user.text.trim(),
            password = password.text,
            koboUrl = kobo.text.trim(),
            syncProgress = initial.syncProgress,
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // A Dialog is its own window, so the activity's key dispatch never sees the wheel while this
        // is up — and this is the longest new page in the app. Without this the wheel is simply dead
        // here and the only way down the page is a drag.
        WheelInDialog()
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
                    .verticalScroll(scroll)
                    .padding(horizontal = lightInset()),
            ) {
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
                LightText("Calibre server", LightTextVariant.Subheading)
                Spacer(Modifier.height(0.6f.gridUnitsAsDp()))
                LightText(
                    text = "An address and, if the server asks for one, an account. calibre-web, " +
                        "calibre-server and COPS all publish the same OPDS catalogue.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                )
                Spacer(Modifier.height(1f.gridUnitsAsDp()))

                LightTextField(
                    value = url,
                    onValueChange = { url = it; result = null },
                    label = "ADDRESS",
                    imeAction = ImeAction.Next,
                )
                Caption("For example 192.168.68.59:8768 — /opds is added for you.")
                Spacer(Modifier.height(1f.gridUnitsAsDp()))

                LightTextField(
                    value = user,
                    onValueChange = { user = it; result = null },
                    label = "USERNAME",
                    imeAction = ImeAction.Next,
                )
                Spacer(Modifier.height(1f.gridUnitsAsDp()))

                LightTextField(
                    value = password,
                    onValueChange = { password = it; result = null },
                    label = "PASSWORD",
                    imeAction = ImeAction.Next,
                )
                // Said plainly rather than hidden behind dots. The field shows what you typed
                // because getting a password in through a wheel and a borrowed keyboard is hard
                // enough without it being invisible — and the value is stored in the app's own
                // preferences in the clear either way, which is the more honest thing to warn about.
                Caption("Stored unencrypted in the app's settings. Point this at a LAN server.")
                Spacer(Modifier.height(1.4f.gridUnitsAsDp()))

                LightText("Reading progress", LightTextVariant.Subheading)
                Spacer(Modifier.height(0.6f.gridUnitsAsDp()))
                LightTextField(
                    value = kobo,
                    onValueChange = { kobo = it; result = null },
                    label = "KOBO SYNC URL",
                    onImeAction = { onSave(edited()) },
                )
                Caption(
                    "calibre-web only. Admin → Basic Configuration → Enable Kobo sync, then copy " +
                        "the sync URL from your user page. The token in it is the password, so it " +
                        "belongs on a LAN."
                )
                if (kobo.text.isNotBlank() && ReadingState.koboBase(kobo.text) == null) {
                    Caption("That does not look like a Kobo sync URL — it should contain /kobo/<token>.")
                }
                Spacer(Modifier.height(1f.gridUnitsAsDp()))

                Column(
                    Modifier
                        .fillMaxWidth()
                        .lightClickable {
                            if (testing) return@lightClickable
                            testing = true
                            result = null
                            val config = edited()
                            scope.launch {
                                val answer = withContext(Dispatchers.IO) {
                                    runCatching { CalibreClient(config).probe() }
                                }
                                testing = false
                                result = answer.getOrElse { it.message ?: "No answer." }
                            }
                        },
                ) {
                    LightRule()
                    LightText(
                        text = if (testing) "TESTING…" else "TEST CONNECTION",
                        variant = LightTextVariant.Button,
                        modifier = Modifier.padding(vertical = 14f.designVerticalPxToDp()),
                    )
                    LightRule()
                }
                result?.let {
                    Spacer(Modifier.height(0.6f.gridUnitsAsDp()))
                    LightText(it, LightTextVariant.Detail, lighten = true)
                }
                Spacer(Modifier.height(2f.gridUnitsAsDp()))
            }

            LightRule()
            LightBottomBar(
                items = listOf(
                    LightBarItem.Text(text = "CANCEL", lighten = true, onClick = onDismiss),
                    LightBarItem.Text(text = "SAVE", onClick = { onSave(edited()) }),
                ),
            )
        }
    }
}

@Composable
private fun Caption(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Superfine,
        lighten = true,
        modifier = Modifier.padding(top = 4f.designVerticalPxToDp()),
    )
}
