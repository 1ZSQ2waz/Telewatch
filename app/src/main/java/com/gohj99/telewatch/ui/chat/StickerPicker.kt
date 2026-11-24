package com.gohj99.telewatch.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.gohj99.telewatch.R
import com.gohj99.telewatch.ui.verticalRotaryScroll
import com.gohj99.telewatch.utils.telegram.TgApi
import com.gohj99.telewatch.utils.telegram.stickerRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi

internal data class StickerPickerRequest(val sticker: TdApi.Sticker? = null)

private data class StickerLibrary(
    val sets: List<TdApi.StickerSetInfo>,
    val recent: List<TdApi.Sticker>,
    val favorite: List<TdApi.Sticker>
)

@Composable
internal fun StickerPicker(
    api: TgApi,
    request: StickerPickerRequest,
    onDismiss: () -> Unit,
    onEmoji: (String) -> Unit,
    onSend: suspend (TdApi.Sticker) -> Unit
) {
    val scope = rememberCoroutineScope()
    var emojiTab by remember { mutableStateOf(false) }
    var setId by remember { mutableStateOf(request.sticker?.setId?.takeIf { it != 0L }) }
    var selected by remember { mutableStateOf(request.sticker?.takeIf { it.setId == 0L }) }
    var pack by remember { mutableStateOf<TdApi.StickerSet?>(null) }
    var library by remember { mutableStateOf<StickerLibrary?>(null) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val grid = remember(setId, emojiTab) { LazyGridState() }
    val previewGrid = rememberLazyGridState()

    fun back() {
        when {
            selected != null -> selected = null
            setId != null -> setId = null
            else -> onDismiss()
        }
        error = null
    }

    LaunchedEffect(api, setId, retry, emojiTab) {
        if (emojiTab) return@LaunchedEffect
        error = null
        loading = true
        pack = null
        try {
            withTimeout(25_000) {
                val id = setId
                if (id != null) {
                    pack = api.stickerRequest(TdApi.GetStickerSet(id))
                } else {
                    library = coroutineScope {
                        val sets = async { api.stickerRequest(TdApi.GetInstalledStickerSets(TdApi.StickerTypeRegular())) }
                        val recent = async { api.stickerRequest(TdApi.GetRecentStickers(false)) }
                        val favorite = async { api.stickerRequest(TdApi.GetFavoriteStickers()) }
                        StickerLibrary(sets.await().sets.toList(), recent.await().stickers.toList(), favorite.await().stickers.toList())
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            if (cancelled is kotlinx.coroutines.TimeoutCancellationException) error = api.context.getString(R.string.stickers_timeout)
            else throw cancelled
        } catch (failure: Exception) {
            error = failure.message
        } finally {
            loading = false
        }
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false)
    ) {
        BackHandler { if (!busy) back() }
        Column(
            Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { back() }, enabled = !busy) { Text(stringResource(R.string.stickers_back)) }
                TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.stickers_close)) }
            }
            if (selected == null && setId == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { emojiTab = true; setId = null }, enabled = !busy) {
                        Text(stringResource(R.string.stickers_emoji), color = if (emojiTab) Color.Cyan else Color.White)
                    }
                    TextButton(onClick = { emojiTab = false }, enabled = !busy) {
                        Text(stringResource(R.string.stickers_title), color = if (!emojiTab) Color.Cyan else Color.White)
                    }
                }
            }
            // A separate state keeps the pack's scroll position while previewing a sticker.
            val state = if (selected == null) grid else previewGrid
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (emojiTab) 4 else 3),
                state = state,
                modifier = Modifier.weight(1f).verticalRotaryScroll(state),
                contentPadding = PaddingValues(bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                error?.let { message ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(message, color = Color(0xFFFFB4AB), fontSize = 13.sp)
                            if (!busy && selected == null) TextButton(onClick = { retry++ }) { Text(stringResource(R.string.Retry)) }
                        }
                    }
                }
                val choice = selected
                val currentPack = pack
                when {
                    choice != null -> {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                StickerArtwork(api, choice, Modifier.size(96.dp), priority = 24)
                                Button(
                                    enabled = !busy,
                                    onClick = {
                                        busy = true
                                        error = null
                                        scope.launch {
                                            try {
                                                onSend(choice)
                                                onDismiss()
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (failure: Exception) {
                                                error = failure.message
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    }
                                ) { Text(stringResource(if (busy) R.string.stickers_sending else R.string.stickers_send)) }
                            }
                        }
                    }
                    emojiTab -> {
                        emojiGroups.forEach { (title, emojis) ->
                            item(span = { GridItemSpan(maxLineSpan) }) { Text(title, color = Color.Cyan, modifier = Modifier.padding(vertical = 8.dp)) }
                            items(emojis) { emoji ->
                                val label = stringResource(R.string.stickers_insert_emoji, emoji)
                                Box(
                                    Modifier.heightIn(min = 42.dp).semantics { contentDescription = label }
                                        .clickable { onEmoji(emoji); onDismiss() },
                                    contentAlignment = Alignment.Center
                                ) { Text(emoji, fontSize = 26.sp) }
                            }
                        }
                    }
                    loading -> item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.loading), color = Color.White) }
                    currentPack != null -> {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                Text(currentPack.title, color = Color.White)
                                Button(
                                    enabled = !busy && !currentPack.isInstalled,
                                    onClick = {
                                        busy = true
                                        error = null
                                        scope.launch {
                                            try {
                                                withTimeout(25_000) {
                                                    api.stickerRequest(TdApi.ChangeStickerSet(currentPack.id, true, false))
                                                    pack = api.stickerRequest(TdApi.GetStickerSet(currentPack.id))
                                                }
                                            } catch (cancelled: CancellationException) {
                                                if (cancelled is kotlinx.coroutines.TimeoutCancellationException) error = api.context.getString(R.string.stickers_timeout)
                                                else throw cancelled
                                            } catch (failure: Exception) {
                                                error = failure.message
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    }
                                ) { Text(stringResource(if (currentPack.isInstalled) R.string.stickers_added else R.string.stickers_add)) }
                            }
                        }
                        items(currentPack.stickers.toList(), key = { it.sticker.id }) { sticker ->
                            StickerChoice(api, sticker, enabled = !busy) { selected = sticker }
                        }
                    }
                    else -> library?.let { data ->
                        if (data.favorite.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.stickers_favorite), color = Color.Cyan) }
                            items(data.favorite, key = { "favorite-${it.sticker.id}" }) { sticker -> StickerChoice(api, sticker) { selected = sticker } }
                        }
                        if (data.recent.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.stickers_recent), color = Color.Cyan) }
                            items(data.recent, key = { "recent-${it.sticker.id}" }) { sticker -> StickerChoice(api, sticker) { selected = sticker } }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.stickers_packs), color = Color.Cyan) }
                        if (data.sets.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.stickers_empty), color = Color.White) }
                        items(data.sets, key = { "set-${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { info ->
                            Row(
                                Modifier.fillMaxWidth().background(Color(0xFF20252B), RoundedCornerShape(10.dp))
                                    .clickable { setId = info.id }.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                info.covers.firstOrNull()?.let { StickerArtwork(api, it, Modifier.size(40.dp)) }
                                Text(info.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StickerChoice(api: TgApi, sticker: TdApi.Sticker, enabled: Boolean = true, onClick: () -> Unit) {
    val label = stringResource(R.string.stickers_select, sticker.emoji)
    Box(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).background(Color(0xFF171C22), RoundedCornerShape(8.dp))
            .semantics { contentDescription = label }.clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { StickerArtwork(api, sticker, Modifier.size(52.dp)) }
}

@Composable
private fun StickerArtwork(api: TgApi, sticker: TdApi.Sticker, modifier: Modifier, priority: Int = 8) {
    val file = sticker.thumbnail?.file ?: sticker.sticker.takeIf { sticker.format is TdApi.StickerFormatWebp }
    var path by remember(api, file?.id) { mutableStateOf(file?.let(api.thumbnailDownloads::cachedPath)) }
    LaunchedEffect(api, file?.id, priority) {
        if (file == null || path != null) return@LaunchedEffect
        snapshotFlow { api.connectionReady.value }.onStart { emit(true) }.collect { ready ->
            if (ready && path == null) {
                if (priority < 16) kotlinx.coroutines.delay(100)
                path = api.thumbnailDownloads.path(file, priority)
            }
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (path != null) AsyncImage(model = path, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        else Text(sticker.emoji.ifBlank { "◇" }, fontSize = 28.sp, color = Color.White)
    }
}

private val emojiGroups = listOf(
    "😀" to "😀 😃 😄 😁 😆 😅 😂 🤣 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😋 😛 😜 🤪 🤗 🤔 🤫 🤭 😎 🥳 😏 😒 🙄 😔 😢 😭 😤 😡 🤬 😱 😳 🥺 😴 🤮 🤧 🤒 🤕 🤯 🥶 🥵 😈 💀 👻 🤖 💩",
    "👍" to "👍 👎 👏 🙌 👐 🤝 🙏 👋 🤚 ✋ 👌 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✊ 👊 💪 👀 🧠 🫂 💋",
    "❤️" to "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💯 💢 💥 💫 💦 💨 💤 ✨ ⭐ 🌟 🔥 ✅ ❌ ❓ ❗ 🎉 🎊 🎁 🎈",
    "🐱" to "🐱 🐶 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐷 🐸 🐵 🐔 🐧 🐦 🦆 🦉 🦋 🐝 🐢 🐬 🐳 🌸 🌹 🌻 🌲 🍀 🌈 ☀️ 🌤️ ☁️ 🌧️ ❄️ 🌙 🌍",
    "🍎" to "🍎 🍊 🍋 🍉 🍇 🍓 🍒 🍑 🥭 🍍 🥑 🍅 🥕 🌽 🍞 🧀 🥚 🍔 🍟 🍕 🌭 🥪 🌮 🍜 🍝 🍣 🍚 🍰 🎂 🍩 🍪 🍫 🍿 ☕ 🍵 🥤 🍺 🥂",
    "⚽" to "⚽ 🏀 🏈 ⚾ 🎾 🏐 🏓 🏸 🏆 🏅 🎮 🎲 🎯 🎨 🎵 🎶 🎤 🎧 🎸 🎹 📷 📱 💻 ⌚ 💡 📚 ✏️ 📝 💰 🚗 🚕 🚌 🚆 ✈️ 🚀 🏠 🏖️ ⛰️"
).map { (title, emojis) -> title to emojis.split(' ') }
