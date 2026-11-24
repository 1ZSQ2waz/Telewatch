/*
 * Copyright (c) 2024-2025 gohj99. Lorem ipsum dolor sit amet, consectetur adipiscing elit.
 * Morbi non lorem porttitor neque feugiat blandit. Ut vitae ipsum eget quam lacinia accumsan.
 * Etiam sed turpis ac ipsum condimentum fringilla. Maecenas magna.
 * Proin dapibus sapien vel ante. Aliquam erat volutpat. Pellentesque sagittis ligula eget metus.
 * Vestibulum commodo. Ut rhoncus gravida arcu.
 */

package com.gohj99.telewatch.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gohj99.telewatch.R
import com.gohj99.telewatch.TgApiManager
import com.gohj99.telewatch.model.Chat
import com.gohj99.telewatch.ui.SearchBar
import com.gohj99.telewatch.ui.verticalRotaryScroll
import com.gohj99.telewatch.utils.telegram.loadChats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi

// 字符串匹配
fun matchingString(target: String, original: String): Boolean {
    return if (target != "")
        if (original != "") original.contains(target, ignoreCase = true)
        else false
    else true
}

@Composable
fun ChatLazyColumn(
    itemsList: MutableState<List<Chat>>,
    callback: (Chat) -> Unit,
    chatsFolder: TdApi.ChatFolder? = null,
    contactsList: List<Chat> = listOf(),
    currentUserId: MutableState<Long>
) {
    val listState = rememberLazyListState()
    val searchText = rememberSaveable { mutableStateOf("") }

    val api = TgApiManager.tgApi
    val allChats = itemsList.value
    val query = searchText.value.trim()
    val contacts = remember(contactsList) { contactsList.mapTo(hashSetOf()) { it.id } }
    val included = remember(chatsFolder) { chatsFolder?.includedChatIds?.toSet().orEmpty() }
    val excluded = remember(chatsFolder) { chatsFolder?.excludedChatIds?.toSet().orEmpty() }
    val pinned = remember(chatsFolder) { chatsFolder?.pinnedChatIds?.toSet().orEmpty() }
    val shown by produceState(emptyList<Chat>(), allChats, query, chatsFolder, contacts) {
        value = withContext(Dispatchers.Default) {
            allChats.asSequence().filter { chat ->
                chat.order != -1L && chat.order != 0L && chat.isArchiveChatPin == null &&
                    matchingString(query, chat.title) &&
                    (chatsFolder == null || when {
                        chat.id in excluded -> false
                        chat.id in included || chat.id in pinned -> true
                        chat.isChannel && chatsFolder.includeChannels -> true
                        chat.isGroup && chatsFolder.includeGroups -> true
                        chat.isPrivateChat -> when {
                            chat.isBot -> chatsFolder.includeBots
                            chat.id in contacts -> chatsFolder.includeContacts
                            else -> chatsFolder.includeNonContacts
                        }
                        else -> false
                    })
            }.sortedWith(
                compareByDescending<Chat> { if (chatsFolder == null) it.isPinned else it.id in pinned }
                    .thenByDescending { it.order }.thenByDescending { it.id }
            ).toList()
        }
    }

    LaunchedEffect(listState, api, chatsFolder, query) {
        if (chatsFolder == null && query.isEmpty()) {
            snapshotFlow {
                val layout = listState.layoutInfo
                val last = layout.visibleItemsInfo.lastOrNull()?.index ?: 0
                val total = layout.totalItemsCount
                total.takeIf {
                    api?.connectionReady?.value == true && !listState.isScrollInProgress &&
                        total > 3 && last >= total - 5
                }
            }.collect { if (it != null) api?.loadChats() }
        }
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalRotaryScroll(listState)
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
        }
        item {
            // 搜索框
            SearchBar(
                query = searchText.value,
                onQueryChange = { searchText.value = it },
                placeholder = stringResource(id = R.string.Search),
                modifier = Modifier
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        items(shown, key = { it.id }) { chat ->
            ChatViewMainCard(chat, callback, currentUserId)
        }

        item {
            Spacer(modifier = Modifier.height(50.dp))
        }
    }
}

@Composable
fun ArchivedChatsLazyColumn(itemsList: MutableState<List<Chat>>, callback: (Chat) -> Unit) {
    val listState = rememberLazyListState()
    val searchText = rememberSaveable { mutableStateOf("") }

    // 只有首次加载时分区数据，之后直接更新 pinned 和 regular 数据
    val pinnedChats = remember { mutableStateOf<List<Chat>>(emptyList()) }
    val regularChats = remember { mutableStateOf<List<Chat>>(emptyList()) }

    // 每次itemsList更新时，增量更新 pinnedChats 和 regularChats
    LaunchedEffect(itemsList.value) {
        // 处理可能存在的 order 字段缺失问题（使用安全调用和默认值）
        val sortedList = itemsList.value
            .filter {
                it.isArchiveChatPin != null  // 仅接受归档会话
            }
            .sortedWith(
                // 第一排序条件：order不为null时降序排列
                compareByDescending<Chat> { it.order }
                    // 第二排序条件：当order相同时按id降序排列
                    .thenByDescending { it.id }
            )

        // 分离置顶消息时需要保持原有置顶顺序
        val newPinnedChats = sortedList
            .filter { it.isPinned }
            .sortedWith(compareByDescending { it.order ?: Long.MIN_VALUE }) // 置顶组内单独排序

        val newRegularChats = sortedList
            .filter { it !in newPinnedChats }
            .sortedWith(compareByDescending { it.order ?: Long.MIN_VALUE }) // 非置顶组内单独排序


        if (newPinnedChats != pinnedChats.value) {
            pinnedChats.value = newPinnedChats
        }

        if (newRegularChats != regularChats.value) {
            regularChats.value = newRegularChats
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize() // 确保 LazyColumn 填满父容器
            .padding(horizontal = 16.dp) // 只在左右添加 padding
            .verticalRotaryScroll(listState)
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp)) // 添加一个高度为 8dp 的 Spacer
        }
        item {
            // 搜索框
            SearchBar(
                query = searchText.value,
                onQueryChange = { searchText.value = it },
                placeholder = stringResource(id = R.string.Search),
                modifier = Modifier
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        // 渲染置顶消息
        items(pinnedChats.value, key = { "${it.id}_${it.isPinned}" }) { item ->
            ChatView(
                chat = item,
                callback = callback,
                searchText = searchText,
                pinnedView = true
            )
        }
        // 渲染普通消息
        items(regularChats.value, key = { it.id }) { item ->
            ChatView(
                chat = item,
                callback = callback,
                searchText = searchText,
                pinnedView = false
            )
        }
        item {
            Spacer(modifier = Modifier.height(50.dp)) // 添加一个高度为 50dp 的 Spacer
        }
    }
}

@Composable
fun ContactsLazyColumn(itemsList: List<Chat>, callback: (Chat) -> Unit) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize() // 确保 LazyColumn 填满父容器
            .padding(horizontal = 16.dp) // 只在左右添加 padding
            .verticalRotaryScroll(listState)
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp)) // 添加一个高度为 8dp 的 Spacer
        }
        items(itemsList) { item ->
            ChatView(item, callback)
        }
        item {
            Spacer(modifier = Modifier.height(50.dp)) // 添加一个高度为 50dp 的 Spacer
        }
    }
}

@Composable
fun ChatView(
    chat: Chat,
    callback: (Chat) -> Unit,
    searchText: MutableState<String> = mutableStateOf(""),
    currentUserId: MutableState<Long> = mutableStateOf(-1),
    pinnedView: Boolean? = null,
    chatFolderInfo: TdApi.ChatFolder? = null,
    contactsSet: Set<Long> = emptySet(),  // 确保类型为 Long
    includedChatIdsSet: Set<Long> = emptySet(),  // 确保类型为 Long
    excludedChatIdsSet: Set<Long> = emptySet(),  // 确保类型为 Long
) {
    // 使用 derivedStateOf 来确保不必要的重渲染
    val isMatchingSearchText by remember(searchText.value) {
        derivedStateOf { matchingString(searchText.value, chat.title) }
    }

    if (isMatchingSearchText) {
        if (chatFolderInfo == null) {
            if (pinnedView == null) {
                ChatViewMainCard(chat, callback, currentUserId)
            } else {
                if (chat.isPinned == pinnedView) {
                    ChatViewMainCard(chat, callback, currentUserId)
                }
            }
        } else {
            if ((chat.id in chatFolderInfo.pinnedChatIds.map { it } == pinnedView)) {

                // 基于过滤条件设置显示会话
                //println(chat.isBot)
                val isShow by remember(chat.id, includedChatIdsSet, excludedChatIdsSet, contactsSet) {
                    derivedStateOf {
                        when {
                            chat.id in excludedChatIdsSet -> false
                            chat.id in includedChatIdsSet -> true
                            chat.isChannel && chatFolderInfo.includeChannels -> true
                            chat.isGroup && chatFolderInfo.includeGroups -> true
                            chat.isPrivateChat -> when {
                                chat.isBot -> chatFolderInfo.includeBots
                                chat.id in contactsSet -> chatFolderInfo.includeContacts
                                else -> chatFolderInfo.includeNonContacts
                            }
                            else -> false
                        }
                    }
                }

                if (isShow) {
                    ChatViewMainCard(chat, callback, currentUserId)
                }
            }
        }
    }
}
