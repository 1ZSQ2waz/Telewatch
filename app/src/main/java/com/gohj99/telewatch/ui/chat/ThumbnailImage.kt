/*
 * Copyright (c) 2025 gohj99. Lorem ipsum dolor sit amet, consectetur adipiscing elit.
 * Morbi non lorem porttitor neque feugiat blandit. Ut vitae ipsum eget quam lacinia accumsan.
 * Etiam sed turpis ac ipsum condimentum fringilla. Maecenas magna.
 * Proin dapibus sapien vel ante. Aliquam erat volutpat. Pellentesque sagittis ligula eget metus.
 * Vestibulum commodo. Ut rhoncus gravida arcu.
 */

package com.gohj99.telewatch.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.gohj99.telewatch.R
import com.gohj99.telewatch.TgApiManager.tgApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onStart
import org.drinkless.tdlib.TdApi

@Composable
fun ThumbnailImage(
    thumbnail: TdApi.File,
    imageWidth: Int,
    imageHeight: Int,
    textColor: Color,
    modifier: Modifier = Modifier,
    loadingText: String = stringResource(R.string.loading),
    maxScreenFraction: Float = 1f,
    minithumbnail: TdApi.Minithumbnail? = null
) {
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val maxEdge = (minOf(config.screenWidthDp, config.screenHeightDp) * maxScreenFraction).dp
    val longest = maxOf(imageWidth, imageHeight, 1)
    val edge = minOf(with(density) { longest.toDp() }, maxEdge)
    val width = edge * (imageWidth.coerceAtLeast(1).toFloat() / longest)
    val height = edge * (imageHeight.coerceAtLeast(1).toFloat() / longest)
    val api = tgApi
    var imagePath by remember(api, thumbnail.id) {
        mutableStateOf(api?.thumbnailDownloads?.cachedPath(thumbnail))
    }
    var finished by remember(api, thumbnail.id) { mutableStateOf(false) }

    LaunchedEffect(api, thumbnail.id) {
        if (api == null || !imagePath.isNullOrEmpty()) return@LaunchedEffect
        // TDLib queues downloads while connecting. Collect keeps in-flight work across state changes.
        snapshotFlow { api.connectionReady.value }.onStart { emit(true) }.collect { ready ->
            if (ready && imagePath.isNullOrEmpty()) {
                delay(100) // Avoid starting requests for rows passed during a fast flick.
                finished = false
                imagePath = api.thumbnailDownloads.path(thumbnail)
                finished = true
            }
        }
    }

    Box(
        modifier.size(width, height).background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
    ) {
        if (!imagePath.isNullOrEmpty()) {
            AsyncImage(
                model = imagePath,
                contentDescription = stringResource(R.string.Photo),
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize()
            )
        } else if (minithumbnail?.data?.isNotEmpty() == true) {
            AsyncImage(
                model = minithumbnail.data,
                contentDescription = stringResource(R.string.Photo),
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize()
            )
        } else {
            Text(
                text = if (finished) stringResource(R.string.No_thumbnail_available) else loadingText,
                color = textColor,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}
