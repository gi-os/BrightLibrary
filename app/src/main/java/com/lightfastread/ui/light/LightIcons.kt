package com.lightfastread.ui.light

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.lightfastread.R

/**
 * LightOS's own icon set. The vector drawables in `res/drawable/ic_*` are copied from
 * `lightphone/light-sdk` (MIT licence, © 2026 The Light Phone — see LICENSE-light-sdk);
 * an app that draws its own back chevron never quite looks like it belongs on the phone.
 */
class LightIconSpec(val name: String, @DrawableRes val res: Int)

object LightIcons {
    val Back = LightIconSpec("back", R.drawable.ic_back_white)
    val Forward = LightIconSpec("forward", R.drawable.ic_arrow_right_white)
    val Add = LightIconSpec("add book", R.drawable.ic_add_white)
    val Settings = LightIconSpec("settings", R.drawable.ic_settings_white)
    val Search = LightIconSpec("search", R.drawable.ic_search_white)
    val Close = LightIconSpec("close", R.drawable.ic_close_white)
    val Accept = LightIconSpec("confirm", R.drawable.ic_accept_white)
    val Trash = LightIconSpec("delete", R.drawable.ic_trash)
    val Backspace = LightIconSpec("backspace", R.drawable.ic_delete_white)
    val Pencil = LightIconSpec("edit", R.drawable.ic_pencil_white)
    val Star = LightIconSpec("bookmarked", R.drawable.ic_star_white)
    val StarOutline = LightIconSpec("bookmark", R.drawable.ic_star_outline_white)
    val List = LightIconSpec("contents", R.drawable.ic_list_white)
    val SelectOn = LightIconSpec("on", R.drawable.ic_select_on_white)
    val SelectOff = LightIconSpec("off", R.drawable.ic_select_off_white)
    val Refresh = LightIconSpec("try again", R.drawable.ic_refresh_white)
    val Up = LightIconSpec("up", R.drawable.ic_up_white)
    val Down = LightIconSpec("down", R.drawable.ic_down_white)
    val Spacer = LightIconSpec("", R.drawable.ic_spacer)
}

private const val DEFAULT_SIZE_UNITS = 2f

/**
 * Icons are sized in grid units and take the content colour, as in LightOS. [tint] exists
 * only for the cases the SDK doesn't have: a bar item that has to recede.
 */
@Composable
fun LightIcon(
    icon: LightIconSpec,
    modifier: Modifier = Modifier,
    size: Float = DEFAULT_SIZE_UNITS,
    tint: Color? = null,
    contentDescription: String? = icon.name,
) {
    Icon(
        painter = painterResource(icon.res),
        contentDescription = contentDescription?.takeIf { it.isNotBlank() },
        tint = tint ?: LightThemeTokens.colors.content,
        modifier = modifier.size(size.gridUnitsAsDp()),
    )
}
