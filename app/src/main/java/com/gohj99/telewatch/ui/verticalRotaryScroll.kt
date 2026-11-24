/*
 * Copyright (c) 2024-2025 gohj99. Lorem ipsum dolor sit amet, consectetur adipiscing elit.
 * Morbi non lorem porttitor neque feugiat blandit. Ut vitae ipsum eget quam lacinia accumsan.
 * Etiam sed turpis ac ipsum condimentum fringilla. Maecenas magna.
 * Proin dapibus sapien vel ante. Aliquam erat volutpat. Pellentesque sagittis ligula eget metus.
 * Vestibulum commodo. Ut rhoncus gravida arcu.
 */

package com.gohj99.telewatch.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.rememberActiveFocusRequester
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

@OptIn(ExperimentalWearFoundationApi::class)
@Composable
fun Modifier.verticalRotaryScroll(
    state: ScrollableState,
    reverse: Boolean = false,
    pagerState: PagerState? = null,
    pageCurrent: Int = -1
): Modifier {
    val focusRequester = rememberActiveFocusRequester()
    val rotary = remember(state, reverse) { RotaryDeltas() }
    LaunchedEffect(state, rotary) {
        for (signal in rotary.ready) {
            withFrameNanos { }
            val delta = rotary.consume()
            // A touch gesture may interrupt this scroll without cancelling the consumer.
            if (delta != 0f) launch { state.scrollBy(delta) }.join()
        }
    }

    if (pagerState != null) {

        LaunchedEffect(pagerState.isScrollInProgress) {
            if (!pagerState.isScrollInProgress && pagerState.currentPage == pageCurrent) focusRequester.requestFocus()
        }
    }

    return then(
        Modifier
            .onRotaryScrollEvent { event ->
                rotary.add(if (reverse) -event.verticalScrollPixels else event.verticalScrollPixels)
                true
            }
            .focusRequester(focusRequester) // 请求焦点
            .focusable() // 聚焦
    )
}

private class RotaryDeltas {
    val ready = Channel<Unit>(Channel.CONFLATED)
    private var pending = 0f

    fun add(delta: Float) {
        pending += delta
        ready.trySend(Unit)
    }

    fun consume(): Float = pending.also { pending = 0f }
}

