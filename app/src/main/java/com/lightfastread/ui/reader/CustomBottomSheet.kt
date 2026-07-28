package com.lightfastread.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lightfastread.ui.theme.LocalIsLightPhone
import com.lightfastread.ui.theme.lpBorder
import kotlinx.coroutines.launch

// Material3 ModalBottomSheet attaches Modifier.anchoredDraggable directly to
// its Surface. When a LazyColumn inside is at its top edge and the user swipes
// down, the LazyColumn (already at the top, can't scroll up) refuses the
// gesture and the sheet's surface-level draggable claims it — dismissing the
// sheet from a scroll attempt. A nested-scroll blocker only intercepts the
// nested-scroll path, not that direct surface draggable. This sheet drops the
// surface draggable entirely: only the explicit drag-handle dismisses by drag,
// so the LazyColumn body owns its own scroll without contention.
@Composable
fun CustomBottomSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val offsetY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true,
        ),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            // Half the screen is a sane cap at 890dp tall. The LPIII is ~472dp,
            // where half leaves room for about four chapter rows once the drag
            // handle is subtracted, so the sheet is allowed to go taller there.
            val maxSheetHeight =
                if (LocalIsLightPhone.current) maxHeight * 0.78f else maxHeight / 2
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = maxSheetHeight)
                    .offset { IntOffset(0, offsetY.value.toInt()) }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                // Without this the sheet is black-on-black and reads as loose
                // text floating over the reader.
                border = lpBorder(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    BottomSheetDragHandle(
                        onDrag = { dy ->
                            scope.launch {
                                offsetY.snapTo((offsetY.value + dy).coerceAtLeast(0f))
                            }
                        },
                        onDragStopped = { velocity ->
                            if (offsetY.value > DISMISS_DISTANCE_PX || velocity > DISMISS_VELOCITY) {
                                onDismiss()
                            } else {
                                offsetY.animateTo(0f, initialVelocity = velocity)
                            }
                        },
                    )
                    content()
                }
            }
        }
    }
}

@Composable
private fun BottomSheetDragHandle(
    onDrag: (Float) -> Unit,
    onDragStopped: suspend (Float) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .draggable(
                state = rememberDraggableState { dy -> onDrag(dy) },
                orientation = Orientation.Vertical,
                onDragStopped = { velocity -> onDragStopped(velocity) },
            )
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 32.dp, height = 4.dp)
                .background(
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (LocalIsLightPhone.current) 0.8f else 0.4f,
                    ),
                    shape = RoundedCornerShape(2.dp),
                ),
        )
    }
}

private const val DISMISS_DISTANCE_PX = 200f
private const val DISMISS_VELOCITY = 1000f
