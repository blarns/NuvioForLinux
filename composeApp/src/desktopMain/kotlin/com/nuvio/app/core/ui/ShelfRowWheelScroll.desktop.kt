package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.rowWheelScroll(state: LazyListState, scope: CoroutineScope): Modifier =
    pointerInput(state) {
        val pixelsPerUnit = 100.dp.toPx() // a wheel notch is ~1 unit → scroll ~100dp per notch
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.type != PointerEventType.Scroll) continue

                val horizontal = event.changes.fold(0f) { acc, change -> acc + change.scrollDelta.x }
                val vertical = event.changes.fold(0f) { acc, change -> acc + change.scrollDelta.y }
                // A horizontal trackpad swipe, or Shift + the vertical wheel, scrolls the row. A
                // plain vertical wheel is ignored here so it bubbles up to the vertical page scroll.
                val delta = when {
                    horizontal != 0f -> horizontal
                    event.keyboardModifiers.isShiftPressed && vertical != 0f -> vertical
                    else -> 0f
                }
                if (delta != 0f) {
                    event.changes.forEach { it.consume() }
                    scope.launch { state.scrollBy(delta * pixelsPerUnit) }
                }
            }
        }
    }

@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.rowDragScroll(state: LazyListState): Modifier =
    pointerInput(state) {
        awaitEachGesture {
            // Watch on the Initial pass so the row sees the press before the poster card under the
            // cursor does — a drag has to be able to cancel that card's click.
            val down = awaitFirstDown(pass = PointerEventPass.Initial)
            // Mouse only: touch and stylus already drag the row natively, with a fling this can't
            // reproduce. Left button only, so right-click context menus are left alone.
            if (down.type != PointerType.Mouse) return@awaitEachGesture
            if (!currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture

            var totalX = 0f
            var totalY = 0f
            var dragging = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break

                val delta = change.position - change.previousPosition
                totalX += delta.x
                totalY += delta.y

                if (!dragging) {
                    val slop = viewConfiguration.touchSlop
                    // A mostly-vertical drag belongs to the page underneath, so abandon the gesture
                    // rather than fight it. Below the slop, decide nothing yet.
                    if (abs(totalY) > slop && abs(totalY) >= abs(totalX)) break
                    if (abs(totalX) <= slop) continue
                    dragging = true
                }

                state.dispatchRawDelta(-delta.x)
                // Consuming is what tells the card underneath this was a drag, not a click.
                change.consume()
            }
        }
    }

@Composable
actual fun BoxScope.RowScrollArrows(state: LazyListState, scope: CoroutineScope) {
    if (state.canScrollBackward) {
        ScrollArrow(
            icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
            contentDescription = "Scroll left",
            alignment = Alignment.CenterStart,
            onClick = { scope.launch { state.animateScrollBy(-pageScrollAmount(state)) } },
        )
    }
    if (state.canScrollForward) {
        ScrollArrow(
            icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = "Scroll right",
            alignment = Alignment.CenterEnd,
            onClick = { scope.launch { state.animateScrollBy(pageScrollAmount(state)) } },
        )
    }
}

/** Scroll by ~80% of the visible width per click, so a sliver of the previous cards stays for context. */
private fun pageScrollAmount(state: LazyListState): Float {
    val info = state.layoutInfo
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    return if (viewport <= 0f) 600f else viewport * 0.8f
}

@Composable
private fun BoxScope.ScrollArrow(
    icon: ImageVector,
    contentDescription: String,
    alignment: Alignment,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .align(alignment)
            .padding(horizontal = 6.dp)
            .size(40.dp)
            .alpha(if (hovered) 0.9f else 0.4f) // ~40% at rest, brighter on hover for affordance
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.85f))
            .hoverable(interaction)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
        )
    }
}
