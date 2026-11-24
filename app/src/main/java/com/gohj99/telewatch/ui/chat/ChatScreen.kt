/*
 * Copyright (c) 2024-2025 gohj99. Lorem ipsum dolor sit amet, consectetur adipiscing elit.
 * Morbi non lorem porttitor neque feugiat blandit. Ut vitae ipsum eget quam lacinia accumsan.
 * Etiam sed turpis ac ipsum condimentum fringilla. Maecenas magna.
 * Proin dapibus sapien vel ante. Aliquam erat volutpat. Pellentesque sagittis ligula eget metus.
 * Vestibulum commodo. Ut rhoncus gravida arcu.
 */

package com.gohj99.telewatch.ui.chat

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Context.CLIPBOARD_SERVICE
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.gohj99.telewatch.R
import com.gohj99.telewatch.TgApiManager.tgApi
import com.gohj99.telewatch.model.Chat
import com.gohj99.telewatch.ui.CustomButton
import com.gohj99.telewatch.ui.theme.TelewatchTheme
import com.gohj99.telewatch.ui.verticalRotaryScroll
import com.gohj99.telewatch.utils.telegram.getChat
import com.gohj99.telewatch.utils.telegram.getUserName
import com.gohj99.telewatch.utils.telegram.joinChat
import com.gohj99.telewatch.utils.telegram.sendSticker
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

object MessageCache {
    private val cache = mutableMapOf<Int, TdApi.MessageContent>()
    private var cacheSize = 0

    fun put(content: TdApi.MessageContent) : Int {
        cacheSize += 1
        cache[cacheSize] = content
        return cacheSize
    }

    fun get(cacheSize: Int): TdApi.MessageContent? = cache[cacheSize]
}

// 反射机制获取MessageContent的类信息
fun getMessageContentTypeName(messageContent: TdApi.MessageContent): String {
    return messageContent::class.simpleName ?: "Unknown"
}

// 复制对应内容
fun copyText(text: String, context: Context) {
    val clipboardManager = context.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
    val clipData = android.content.ClipData.newPlainText("Copied Text", text)
    clipboardManager.setPrimaryClip(clipData)
}

@SuppressLint("MutableCollectionMutableState")
@Composable
fun SplashChatScreen(
    chatTitle: String,
    chatList: MutableState<List<TdApi.Message>>,
    chatId: Long,
    goToChat: (Chat) -> Unit,
    press: (TdApi.Message) -> Unit,
    longPress: suspend (String, TdApi.Message) -> String,
    chatObject: TdApi.Chat,
    lastReadOutboxMessageId: MutableState<Long>,
    lastReadInboxMessageId: MutableState<Long>,
    inputText: MutableState<String>,
    listState: LazyListState = rememberLazyListState(),
    onLinkClick: (String) -> Unit,
    chatTitleClick: () -> Unit,
    currentUserId: MutableState<Long>,
    chatTopics: Map<Long, String>
) {
    // 获取context
    val context = LocalContext.current

    var isLongPressed = remember { mutableStateOf(false) }
    val selectMessage = remember {
        mutableStateOf(TdApi.Message())
    }
    val senderNameMap = remember { mutableStateOf(mutableMapOf<Long, String?>()) }
    val pagerState = rememberPagerState(pageCount = { 2 }, initialPage = 0)
    var notJoin by remember { mutableStateOf(chatObject.positions.isEmpty()) }
    val coroutineScope = rememberCoroutineScope()
    var planReplyMessage = remember { mutableStateOf(tgApi!!.replyMessage.value) }
    var planReplyMessageSenderName by rememberSaveable { mutableStateOf("") }
    var planEditMessage = remember { mutableStateOf<TdApi.Message?>((null)) }
    var planEditMessageText = remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val selectTopicId = rememberSaveable { mutableLongStateOf(0L) }
    //var chatReadList = tgApi?.chatReadList!!

    // 获取show_unknown_message_type值
    val settingsSharedPref = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    val showUnknownMessageType = settingsSharedPref.getBoolean("show_unknown_message_type", false)

    // 获取加载内容数量
    val messagePreloadQuantity = settingsSharedPref.getInt("Message_preload_quantity", 10)

    // 获取下滑按钮显示偏移量
    val downButtonOffset = settingsSharedPref.getInt("Down_Button_Offset", 25)

    //println(chatsListManager.chatsList.value)
    //val chatPermissions: TdApi.ChatPermissions? = chatObject.permissions

    val api = tgApi
    var stickerPicker by remember(api, chatId) { mutableStateOf<StickerPickerRequest?>(null) }
    val inputSelection = remember(api, chatId) { mutableStateOf(TextRange(inputText.value.length)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val hasNewer = api?.historyHasNewer?.value == true
    suspend fun jumpToLatest() {
        if (api == null || !api.historyHasNewer.value || api.loadHistory(chatId, chatList)) {
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(lifecycle, api, chatId, listState) {
        val reported = HashSet<Long>()
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow {
                if (pagerState.currentPage != 0 || listState.isScrollInProgress) emptyList()
                else listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Long }
            }.collect { visible ->
                if (api?.ownsHistory(chatId, chatList) == true) {
                    val newlyVisible = visible.filter(reported::add)
                    api.reportVisibleMessages(chatId, newlyVisible.toLongArray())
                }
            }
        }
    }

    // 更新将回复消息的发送者
    LaunchedEffect(planReplyMessage.value) {
        if (planReplyMessage.value != null) {
            when (val sender = planReplyMessage.value!!.senderId) {
                is TdApi.MessageSenderUser -> {
                    if (sender.userId in senderNameMap.value) {
                        planReplyMessageSenderName = senderNameMap.value[sender.userId]!!
                    } else {
                        tgApi?.getUserName(sender.userId) { user ->
                            planReplyMessageSenderName = user
                            senderNameMap.value[sender.userId] = user
                        }
                    }
                    val replyChatId = planReplyMessage.value!!.chatId
                    if (replyChatId != chatId && replyChatId != sender.userId) {
                        val itChat = tgApi?.getChat(replyChatId)
                        itChat.let {
                            planReplyMessageSenderName += " -> ${it!!.title}"
                        }
                    }
                }
                is TdApi.MessageSenderChat -> {
                    if (sender.chatId == chatId) {
                        planReplyMessageSenderName = chatTitle
                    } else {
                        val itChat = tgApi?.getChat(sender.chatId)
                        itChat.let {
                            planReplyMessageSenderName = it!!.title
                        }
                    }
                    val replyChatId = planReplyMessage.value!!.chatId
                    if (replyChatId != chatId && replyChatId != sender.chatId) {
                        val itChat = tgApi?.getChat(replyChatId)
                        itChat.let {
                            planReplyMessageSenderName += " -> ${it!!.title}"
                        }
                    }
                }
                else -> "" // 处理未知类型
            }
        }
    }

    LaunchedEffect(lifecycle, api, chatId, listState, messagePreloadQuantity) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch {
                // Retry if the first network request timed out while the app was asleep.
                snapshotFlow { api?.connectionReady?.value == true && chatList.value.isEmpty() }
                    .collect { if (it) api?.loadHistory(chatId, chatList) }
            }
            launch {
                snapshotFlow {
                    val messages = chatList.value
                    val farthest = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    if (pagerState.currentPage == 0 && farthest >= messages.size - messagePreloadQuantity) {
                        messages.lastOrNull()?.id
                    } else null
                }.collect { boundary ->
                    if (boundary != null) api?.loadMoreHistory(chatId, chatList, boundary, newer = false)
                }
            }
            launch {
                snapshotFlow {
                    if (pagerState.currentPage == 0 && listState.firstVisibleItemIndex <= 3 &&
                        api?.historyHasNewer?.value == true) chatList.value.firstOrNull()?.id else null
                }.collect { boundary ->
                    if (boundary != null) api?.loadMoreHistory(chatId, chatList, boundary, newer = true)
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 标题
            Box(
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 0.dp) // 调整垂直填充
                    .clickable(
                        onClick = { chatTitleClick() }
                    )
            ) {
                Text(
                    text = chatTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .padding(start = 8.dp, end = 8.dp)
                    .fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> {
                        // 消息部分
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 0.dp)
                                .verticalRotaryScroll(listState, true, pagerState, 0)
                                .weight(1f),
                            reverseLayout = true, // 反转布局
                            verticalArrangement = Arrangement.Top,
                        ) {
                            item(key = "chat-footer") {
                                // 未加入会话
                                if (notJoin) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 28.dp),
                                        horizontalArrangement = Arrangement.SpaceEvenly,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CustomButton(
                                            onClick = {
                                                println("按钮点击")
                                                tgApi?.joinChat(
                                                    chatId = chatId,
                                                    reInit = { }
                                                )
                                                notJoin = false
                                            },
                                            text = stringResource(id = R.string.join_in)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                } else {
                                    Spacer(modifier = Modifier.height(55.dp))
                                }
                            }
                            // 消息正文
                            itemsIndexed(
                                chatList.value,
                                key = { _, message -> message.id },
                                contentType = { _, message -> message.content?.constructor }
                            ) { index, message ->
                                val isCurrentUser = message.isOutgoing
                                val backgroundColor =
                                    if (isCurrentUser) Color(0xFF003C68) else Color(0xFF2C323A)
                                val textColor = if (isCurrentUser) Color(0xFF66D3FE) else Color(0xFFFEFEFE)
                                val alignment = if (isCurrentUser) Arrangement.End else Arrangement.Start
                                val modifier = if (isCurrentUser) Modifier.align(Alignment.End) else Modifier
                                var stateDownloadDone = rememberSaveable { mutableStateOf(false) }
                                var stateDownload = rememberSaveable { mutableStateOf(false) }

                                MessageHandleCompose(
                                    message = message,
                                    chatList = chatList,
                                    index = index,
                                    chatId = chatId,
                                    chatTitle = chatTitle,
                                    isCurrentUser = isCurrentUser,
                                    modifier = modifier,
                                    alignment = alignment,
                                    textColor = textColor,
                                    backgroundColor = backgroundColor,
                                    stateDownload = stateDownload,
                                    stateDownloadDone = stateDownloadDone,
                                    showUnknownMessageType = showUnknownMessageType,
                                    selectMessage = selectMessage,
                                    isLongPressed = isLongPressed,
                                    senderNameMap = senderNameMap,
                                    listState = listState,
                                    press = { clicked ->
                                        val sticker = (clicked.content as? TdApi.MessageSticker)?.sticker
                                        if (sticker != null) stickerPicker = StickerPickerRequest(sticker)
                                        else press(clicked)
                                    },
                                    lastReadOutboxMessageId = lastReadOutboxMessageId,
                                    lastReadInboxMessageId = lastReadInboxMessageId,
                                    onLinkClick = onLinkClick,
                                    goToChat = goToChat,
                                    chatTopics = chatTopics
                                )
                            }
                        }
                    }
                    1 -> {
                        // 发送消息页面部分
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 0.dp)
                                .verticalScroll(scrollState)
                                .verticalRotaryScroll(
                                    state = scrollState,
                                    pagerState = pagerState,
                                    pageCurrent = 1
                                ),
                            verticalArrangement = Arrangement.Top
                        ) {
                            SendMessageCompose(
                                chatId = chatId,
                                inputText = inputText,
                                currentUserId = currentUserId,
                                planReplyMessage = planReplyMessage,
                                planReplyMessageSenderName = planReplyMessageSenderName,
                                planEditMessage = planEditMessage,
                                planEditMessageText = planEditMessageText,
                                jumpToLatest = ::jumpToLatest,
                                pagerState = pagerState,
                                showUnknownMessageType = showUnknownMessageType,
                                onLinkClick = onLinkClick,
                                chatTopics = chatTopics,
                                selectTopicId = selectTopicId,
                                inputSelection = inputSelection,
                                onOpenStickerPicker = { stickerPicker = StickerPickerRequest() }
                            )
                        }
                    }
                }
            }
        }

        val pickerRequest = stickerPicker
        if (pickerRequest != null && api != null) {
            StickerPicker(
                api = api,
                request = pickerRequest,
                onDismiss = { stickerPicker = null },
                onEmoji = { emoji ->
                    val text = inputText.value
                    val start = inputSelection.value.min.coerceIn(0, text.length)
                    val end = inputSelection.value.max.coerceIn(start, text.length)
                    inputText.value = text.replaceRange(start, end, emoji)
                    inputSelection.value = TextRange(start + emoji.length)
                    coroutineScope.launch { pagerState.animateScrollToPage(1) }
                },
                onSend = { sticker ->
                    val result = api.sendSticker(chatId, sticker, planReplyMessage.value, selectTopicId.longValue)
                    val failure = result.sendingState as? TdApi.MessageSendingStateFailed
                    check(failure == null) { failure!!.error.message }
                    planReplyMessage.value = null
                    api.replyMessage.value = null
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(0)
                        jumpToLatest()
                    }
                }
            )
        }

        // 长按处理
        if (isLongPressed.value) {
            LongPressBox(
                callBack = { select ->
                    when (select) {
                        // 特殊处理
                        "Edit" -> {
                            val content = selectMessage.value.content
                            if (content is TdApi.MessageText) {
                                planEditMessage.value = selectMessage.value
                                planEditMessageText.value = content.text.text
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(1)
                                }
                            } else {
                                longPress(select, selectMessage.value)
                            }
                            ""
                        }
                        "Reply" -> {
                            // 返回空字符串同时执行操作
                            planReplyMessage.value = selectMessage.value
                            tgApi!!.replyMessage.value = selectMessage.value
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(1)
                            }
                            ""
                        }
                        else -> return@LongPressBox longPress(select, selectMessage.value)
                    }
                },
                onDismiss = { isLongPressed.value = false }
            )
        }

        // 0页下方功能区
        if (pagerState.currentPage == 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd) // 定位到右下角
                    .offset(x = (-downButtonOffset).dp, y = (-downButtonOffset).dp) // 向左上偏移，避免紧贴屏幕边缘
            ) {
                MessageBottomFunctionalCompose(
                    listState = listState,
                    chatId = chatId,
                    hasNewer = hasNewer,
                    jumpToLatest = ::jumpToLatest
                )
            }
        }
    }
}

@Composable
fun DateText(date: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = date,
            modifier = Modifier,
            color = Color(0xFF3A80BF)
        )
    }
}

@SuppressLint("UnrememberedMutableState")
@Preview(showBackground = true)
@Composable
fun SplashChatScreenPreview() {
    val sampleMessages = remember {
        mutableStateOf(
            listOf(
                TdApi.Message().apply {
                    date = 1692127800
                    editDate = 283848839
                    id = 1
                    senderId = TdApi.MessageSenderUser(2) // 对方用户
                    content = TdApi.MessageText(
                        TdApi.FormattedText(
                            "这可是用高贵的jetpack compose写的。\n原生啊，原生懂吗？",
                            emptyArray()
                        ),
                        null,
                        null
                    )
                },
                TdApi.Message().apply {
                    date = 1692127800
                    id = 2
                    senderId = TdApi.MessageSenderUser(2) // 对方用户
                    content = TdApi.MessageText(
                        TdApi.FormattedText("你再骂！", emptyArray()),
                        null,
                        null
                    )
                },
                TdApi.Message().apply {
                    date = 1692127800
                    id = 3
                    senderId = TdApi.MessageSenderUser(1) // 当前用户
                    content = TdApi.MessageText(
                        TdApi.FormattedText(
                            "我去，大佬你用qt开发的吗，太美了",
                            emptyArray()
                        ),
                        null,
                        null
                    )
                },
            )
        )
    }

    TelewatchTheme {
        SplashChatScreen(
            chatTitle = "XCちゃん",
            chatList = sampleMessages,
            chatId = 1L,
            goToChat = { },
            press = {
                println("点击触发")
            },
            longPress = { select, message ->
                println("长按触发")
                println(message)
                return@SplashChatScreen select
            },
            chatObject = TdApi.Chat(),
            lastReadOutboxMessageId = mutableLongStateOf(0L),
            lastReadInboxMessageId = mutableLongStateOf(0L),
            inputText = mutableStateOf(""),
            onLinkClick = {},
            chatTitleClick = {},
            currentUserId = mutableLongStateOf(-1L),
            chatTopics = mutableMapOf()
        )
    }
}
