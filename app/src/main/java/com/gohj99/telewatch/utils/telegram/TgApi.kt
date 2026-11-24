/*
 * Copyright (c) 2024-2025 gohj99. Lorem ipsum dolor sit amet, consectetur adipiscing elit.
 * Morbi non lorem porttitor neque feugiat blandit. Ut vitae ipsum eget quam lacinia accumsan.
 * Etiam sed turpis ac ipsum condimentum fringilla. Maecenas magna.
 * Proin dapibus sapien vel ante. Aliquam erat volutpat. Pellentesque sagittis ligula eget metus.
 * Vestibulum commodo. Ut rhoncus gravida arcu.
 */

package com.gohj99.telewatch.utils.telegram

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.os.Build
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.gohj99.telewatch.model.Chat
import com.gohj99.telewatch.model.ChatMessagesSave
import com.gohj99.telewatch.utils.proxy.CloudflareProxy
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.resume

private val tdlibQuietMode by lazy {
    runCatching { Client.execute(TdApi.SetLogVerbosityLevel(1)) }
        .onFailure { Log.w("TelewatchTDLib", "Could not reduce TDLib logging", it) }
}

class TgApi(
    internal val context: Context,
    var chatsList: MutableState<List<Chat>>,
    internal val userId: String = "",
    internal val topTitle: MutableState<String>,
    internal val chatsFoldersList: MutableState<List<TdApi.ChatFolder>>
) {
    var saveChatId = 0L
    var replyMessage = mutableStateOf<TdApi.Message?>(null)
    var updateFileCallBackList = mutableMapOf<Int, (TdApi.File) -> Unit>()
    internal var saveChatMessagesList = mutableMapOf<Long, ChatMessagesSave>() //聊天在后台时更新
    internal var saveChatList = mutableStateOf(emptyList<TdApi.Message>()) // 保存的聊天列表，前台更新
    internal var saveChatIdList = mutableMapOf<Long, MutableList<Long>>()
    internal val client: Client = run {
        tdlibQuietMode
        Client.create({ update -> handleUpdate(update) }, null, null)
    }
    internal val thumbnailDownloads = ThumbnailDownloads(client)
    internal val sharedPref = context.getSharedPreferences("LoginPref", MODE_PRIVATE)
    internal val settingsSharedPref = context.getSharedPreferences("app_settings", MODE_PRIVATE)
    internal val chatMutex = Mutex()
    private val historyMutex = Mutex()
    @Volatile private var historyGeneration = 0L
    internal val historyHasNewer = mutableStateOf(false)
    internal val connectionReady = mutableStateOf(false)
    internal val homeLoadMutex = Mutex()
    internal var homeHistoryComplete = false
    @Volatile
    internal var isAuthorized: Boolean = false
    internal val authLatch = CountDownLatch(1)
    internal var isExitChatPage = true
    internal var lastReadOutboxMessageId = mutableStateOf(0L)
    internal var lastReadInboxMessageId = mutableStateOf(0L)
    internal var currentUser: List<String> = emptyList()
    var forwardMessage: MutableState<TdApi.Message?> = mutableStateOf(null)
    var chatReadList = mutableStateMapOf<Long, Int>()


    init {
        // 获取应用外部数据目录
        val externalDir: File = context.getExternalFilesDir(null)
            ?: throw IllegalStateException("Failed to get external directory.")
        // 获取API ID和API Hash
        val config = loadConfig(context)
        val tdapiId = config.getProperty("api_id").toInt()
        val tdapiHash = config.getProperty("api_hash")
        val encryptionKeyString = sharedPref.getString("encryption_key", null)
        CloudflareProxy.attach(client)
        client.send(TdApi.SetTdlibParameters().apply {
            databaseDirectory = externalDir.absolutePath + (if (userId == "") "/tdlib" else {
                "/$userId/tdlib"
            })
            useMessageDatabase = true
            useSecretChats = true
            apiId = tdapiId
            apiHash = tdapiHash
            systemLanguageCode = context.resources.configuration.locales[0].language
            deviceModel = Build.MODEL
            systemVersion = Build.VERSION.RELEASE
            applicationVersion = getAppVersion(context)
            useSecretChats = false
            useMessageDatabase = true
            databaseEncryptionKey = encryptionKeyString?.chunked(2)?.map { it.toInt(16).toByte() }
                ?.toByteArray()
                ?: throw IllegalStateException("Encryption key not found")
        }) { result ->
            println("SetTdlibParameters result: $result")
            if (result is TdApi.Error) {
                CloudflareProxy.detach(client)
                throw IllegalStateException(result.message)
            } else {
                CloudflareProxy.ready(context, client)
            }
        }

        // 等待授权状态更新
        try {
            authLatch.await()
        } catch (e: InterruptedException) {
            close()
            throw IllegalStateException("Interrupted while waiting for authorization", e)
        }

        if (!isAuthorized) {
            close()
            throw IllegalStateException("Failed to authorize")
        }

        client.send(TdApi.GetMe()) {
            if (it is TdApi.User) {
                val user = it
                currentUser = listOf(user.id.toString(), "${user.firstName} ${user.lastName}")
            }
        }
    }

    // 处理 TDLib 更新的函数
    private fun handleUpdate(update: TdApi.Object) {
        when (update.constructor) {
            TdApi.UpdateAuthorizationState.CONSTRUCTOR -> handleAuthorizationState(update as TdApi.UpdateAuthorizationState)
            TdApi.UpdateNewMessage.CONSTRUCTOR -> handleNewMessage(update as TdApi.UpdateNewMessage)
            TdApi.UpdateMessageContent.CONSTRUCTOR -> handleMessageContentUpdate(update as TdApi.UpdateMessageContent)
            TdApi.UpdateMessageEdited.CONSTRUCTOR -> handleMessageEdited(update as TdApi.UpdateMessageEdited)
            TdApi.UpdateDeleteMessages.CONSTRUCTOR -> handleDeleteMessages(update as TdApi.UpdateDeleteMessages)
            TdApi.UpdateNewChat.CONSTRUCTOR -> handleNewChat(update as TdApi.UpdateNewChat)
            TdApi.UpdateConnectionState.CONSTRUCTOR -> handleConnectionUpdate(update as TdApi.UpdateConnectionState)
            TdApi.UpdateChatReadInbox.CONSTRUCTOR -> handleChatReadInboxUpdate(update as TdApi.UpdateChatReadInbox)
            TdApi.UpdateChatReadOutbox.CONSTRUCTOR -> handleChatReadOutboxUpdate(update as TdApi.UpdateChatReadOutbox)
            TdApi.UpdateChatFolders.CONSTRUCTOR -> handleChatFoldersUpdate(update as TdApi.UpdateChatFolders)
            TdApi.UpdateChatTitle.CONSTRUCTOR -> handleChatTitleUpdate(update as TdApi.UpdateChatTitle)
            TdApi.UpdateUser.CONSTRUCTOR -> handleUpdateUser(update as TdApi.UpdateUser)
            TdApi.UpdateChatLastMessage.CONSTRUCTOR -> handleChatLastMessageUpdate(update as TdApi.UpdateChatLastMessage)
            TdApi.UpdateChatPosition.CONSTRUCTOR -> handleChatPositionUpdate(update as TdApi.UpdateChatPosition)
            TdApi.UpdateMessageSendSucceeded.CONSTRUCTOR -> handleMessageSendSucceededUpdate(update as TdApi.UpdateMessageSendSucceeded)
            TdApi.UpdateFile.CONSTRUCTOR -> handleFileUpdate(update as TdApi.UpdateFile)
            TdApi.UpdateChatPhoto.CONSTRUCTOR -> handleChatPhotoUpdate(update as TdApi.UpdateChatPhoto)
            TdApi.UpdateChatNotificationSettings.CONSTRUCTOR -> handleChatNotificationSettingsUpdate(update as TdApi.UpdateChatNotificationSettings)
            TdApi.UpdateChatDraftMessage.CONSTRUCTOR -> handleChatDraftMessageUpdate(update as TdApi.UpdateChatDraftMessage)
            else -> Unit
        }
    }

    // 进入聊天页面
    suspend fun openChatPage(openChatId: Long, chatList: MutableState<List<TdApi.Message>>) {
        chatMutex.withLock {
            //println("进入聊天页面开始执行")
            if (saveChatId != openChatId) {
                client.send(TdApi.OpenChat(openChatId)) { result ->
                    if (result.constructor == TdApi.Ok.CONSTRUCTOR) {
                        println("Opened chat page successfully, ChatId: $openChatId")
                    } else {
                        println("Failed to open chat page: $result")
                    }
                }
                if (saveChatId != 0L && saveChatId != -1L){
                    saveChatMessagesList = saveChatMessagesList.toMutableMap().apply {
                        put(saveChatId, ChatMessagesSave(
                            saveChatList.value.toMutableList(),
                            lastReadInboxMessageId.value,
                            lastReadOutboxMessageId.value,
                            historyHasNewer.value
                        ))
                    }
                }
                saveChatId = openChatId
                historyGeneration++
                val oldChatList = saveChatMessagesList[openChatId]
                historyHasNewer.value = oldChatList?.hasNewerHistory ?: false
                if (oldChatList == null) {
                    saveChatList = chatList
                    lastReadInboxMessageId.value = 0L
                    lastReadOutboxMessageId.value = 0L
                } else {
                    saveChatMessagesList.remove(openChatId)
                    chatList.value = oldChatList.messages.toMutableList()
                    saveChatList = chatList
                    lastReadInboxMessageId.value = oldChatList.lastReadInboxMessageId
                    lastReadOutboxMessageId.value = oldChatList.lastReadOutboxMessageId
                }
                isExitChatPage = false
            } else if (saveChatList !== chatList) {
                historyGeneration++
                chatList.value = saveChatList.value
                saveChatList = chatList
            }
            //println("进入聊天页面结束执行")
        }
    }

    // 退出聊天页面
    suspend fun exitChatPage(chatId: Long, owner: MutableState<List<TdApi.Message>>, draftMessage: TdApi.DraftMessage? = null) {
        chatMutex.withLock {
            if (!ownsHistory(chatId, owner)) return@withLock
            //println("退出聊天页面开始执行")
            val closeChatId = saveChatId
            client.send(TdApi.SetChatDraftMessage(closeChatId, 0, draftMessage)) { result ->
                if (result.constructor == TdApi.Ok.CONSTRUCTOR) {
                    println("Set draft message successfully, ChatId: $closeChatId")
                } else {
                    println("Failed to set draft message: $result")
                }
            }
            client.send(TdApi.CloseChat(closeChatId)) { result ->
                if (result.constructor == TdApi.Ok.CONSTRUCTOR) {
                    println("Closed chat page successfully, ChatId: $closeChatId")
                } else {
                    println("Failed to close chat page: $result")
                }
            }
            if (closeChatId != 0L && closeChatId != -1L){
                saveChatMessagesList.remove(closeChatId)
            }
            isExitChatPage = true
            saveChatId = 0L
            historyGeneration++
            //println("退出聊天页面结束执行")
        }
    }

    internal fun ownsHistory(chatId: Long, owner: MutableState<List<TdApi.Message>>) =
        !isExitChatPage && saveChatId == chatId && saveChatList === owner

    internal suspend fun loadHistory(
        chatId: Long,
        owner: MutableState<List<TdApi.Message>>,
        anchorId: Long = 0,
        latestId: Long? = null,
        onlyLocal: Boolean = false,
        applyIf: () -> Boolean = { true }
    ): Boolean = historyMutex.withLock {
        if (!ownsHistory(chatId, owner)) return@withLock false
        val generation = historyGeneration
        if (anchorId != 0L && owner.value.any { it.id == anchorId }) return@withLock true

        var around = if (anchorId == 0L) null else {
            val cached = historyPage(chatId, anchorId, -8, 28, onlyLocal = true)
            if (cached?.any { it.id == anchorId } == true) cached
            else if (onlyLocal) null else historyPage(chatId, anchorId, -8, 28) ?: cached
        }?.takeIf { it.isNotEmpty() }
        // TDLib can return a short cache page. Fill the viewport above the anchor before
        // laying out the list so Compose doesn't clamp the saved offset to a shorter page.
        if (around != null && around.count { it.id < anchorId } < 8) {
            val lastId = around.last().id
            val older = historyPage(chatId, lastId, 0, 21, onlyLocal = true).orEmpty()
            around = around + older.filter { it.id < lastId }
        }
        val page = around ?: historyPage(chatId, 0, 0, 24, onlyLocal) ?: return@withLock false
        if (generation != historyGeneration || !ownsHistory(chatId, owner) || !applyIf()) return@withLock false

        val hadGap = historyHasNewer.value
        historyHasNewer.value = around != null && (latestId == null || around.first().id < latestId)
        owner.value = if (around != null || hadGap) page else
            (owner.value + page).distinctBy { it.id }.sortedByDescending { it.id }
        anchorId == 0L || around != null
    }

    internal suspend fun loadMoreHistory(
        chatId: Long,
        owner: MutableState<List<TdApi.Message>>,
        boundary: Long,
        newer: Boolean
    ) = historyMutex.withLock {
        if (!ownsHistory(chatId, owner)) return@withLock
        val edge = if (newer) owner.value.firstOrNull() else owner.value.lastOrNull()
        if (edge?.id != boundary || (newer && !historyHasNewer.value)) return@withLock
        val generation = historyGeneration
        val page = historyPage(chatId, boundary, if (newer) -20 else 0, 21) ?: return@withLock
        if (generation != historyGeneration || !ownsHistory(chatId, owner)) return@withLock

        val current = owner.value
        val seen = current.mapTo(HashSet(current.size)) { it.id }
        val added = page.filter { (if (newer) it.id > boundary else it.id < boundary) && seen.add(it.id) }
        if (added.isNotEmpty()) owner.value = if (newer) added + current else current + added
        else if (newer) historyHasNewer.value = false
    }

    private suspend fun historyPage(chatId: Long, anchor: Long, offset: Int, limit: Int, onlyLocal: Boolean = false): List<TdApi.Message>? =
        withTimeoutOrNull(if (onlyLocal) 1500L else 8000L) {
            suspendCancellableCoroutine { continuation ->
                client.send(TdApi.GetChatHistory(chatId, anchor, offset, limit, onlyLocal)) { result ->
                    continuation.resume((result as? TdApi.Messages)?.messages?.filterNotNull()?.distinctBy { it.id })
                }
            }
        }

    internal fun reportVisibleMessages(chatId: Long, ids: LongArray) {
        if (ids.isNotEmpty() && saveChatId == chatId && !isExitChatPage) {
            client.send(TdApi.ViewMessages(chatId, ids, null, false)) {}
        }
    }

    internal fun rememberHomeChats() {
        if (userId.isEmpty()) return
        val ids = chatsList.value.asSequence().filter { it.order > 0 && it.isArchiveChatPin == null }
            .sortedByDescending { it.order }.take(20).joinToString(",") { it.id.toString() }
        if (ids.isNotEmpty()) homeCache.edit().putString(userId, ids).apply()
    }

    internal fun restoreHomeChats() {
        val ids = homeCache.getString(userId, null)?.split(',').orEmpty().mapNotNull(String::toLongOrNull)
        for (id in ids.take(20)) {
            if (chatsList.value.any { it.id == id && it.order > 0 }) continue
            client.send(TdApi.GetChat(id)) { result ->
                if (result is TdApi.Chat && result.positions.any { it.list is TdApi.ChatListMain && it.order > 0 } &&
                    chatsList.value.none { it.id == id && it.order > 0 }) {
                    handleNewChat(TdApi.UpdateNewChat(result))
                }
            }
        }
    }

    private val homeCache get() = context.getSharedPreferences("recent_home_chats", MODE_PRIVATE)

    // 关闭连接
    fun close() {
        thumbnailDownloads.close()
        CloudflareProxy.detach(client)
        println("Closing client")
        client.send(TdApi.Close()) {}
    }
}
