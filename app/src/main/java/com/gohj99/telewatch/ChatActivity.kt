/*
 * Copyright (c) 2024-2025 gohj99. Lorem ipsum dolor sit amet, consectetur adipiscing elit.
 * Morbi non lorem porttitor neque feugiat blandit. Ut vitae ipsum eget quam lacinia accumsan.
 * Etiam sed turpis ac ipsum condimentum fringilla. Maecenas magna.
 * Proin dapibus sapien vel ante. Aliquam erat volutpat. Pellentesque sagittis ligula eget metus.
 * Vestibulum commodo. Ut rhoncus gravida arcu.
 */

package com.gohj99.telewatch

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.gohj99.telewatch.model.Chat
import com.gohj99.telewatch.model.ChatReadingPosition
import com.gohj99.telewatch.ui.chat.SplashChatScreen
import com.gohj99.telewatch.ui.main.ErrorScreen
import com.gohj99.telewatch.ui.main.SplashLoadingScreen
import com.gohj99.telewatch.ui.theme.TelewatchTheme
import com.gohj99.telewatch.utils.telegram.TgApi
import com.gohj99.telewatch.utils.telegram.createPrivateChat
import com.gohj99.telewatch.utils.telegram.deleteMessageById
import com.gohj99.telewatch.utils.telegram.getChat
import com.gohj99.telewatch.utils.telegram.getCurrentUser
import com.gohj99.telewatch.utils.telegram.getForumTopics
import com.gohj99.telewatch.utils.telegram.getLastReadInboxMessageId
import com.gohj99.telewatch.utils.telegram.getLastReadOutboxMessageId
import com.gohj99.telewatch.utils.telegram.getMessageLink
import com.gohj99.telewatch.utils.telegram.getMessageTypeById
import com.gohj99.telewatch.utils.telegram.getSupergroup
import com.gohj99.telewatch.utils.telegram.reloadMessageById
import com.gohj99.telewatch.utils.urlHandle
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

class ChatActivity : ComponentActivity() {
    private var tgApi: TgApi? = null
    private var chat: Chat? = null
    private var chatList = mutableStateOf(emptyList<TdApi.Message>())
    private var lastReadOutboxMessageId = mutableStateOf(0L)
    private var lastReadInboxMessageId = mutableStateOf(0L)
    private var goToChat = mutableStateOf(false)
    private var listState = LazyListState()
    private var inputText = mutableStateOf("")
    private val chatTopics = mutableStateOf<Map<Long, String>>(emptyMap())
    private var pendingReadingPosition = false

    @SuppressLint("AutoboxingStateCreation")
    private var currentUserId = mutableStateOf(-1L) // 使用 MutableState 来持有当前用户 ID

    override fun onDestroy() {
        super.onDestroy()
        val api = tgApi ?: return
        val chatId = chat?.id ?: return
        if (inputText.value != "") {
            runBlocking {
                api.exitChatPage(
                    chatId,
                    chatList,
                    TdApi.DraftMessage(
                        null,
                        (System.currentTimeMillis() / 1000).toInt(),
                        TdApi.InputMessageText(
                            TdApi.FormattedText(inputText.value, null),
                            TdApi.LinkPreviewOptions(),
                            false
                        ),
                        0L
                    )
                )
            }
        } else {
            runBlocking {
                api.exitChatPage(chatId, chatList)
            }
        }
    }

    override fun onPause() {
        val api = tgApi
        val chatId = chat?.id
        if (!pendingReadingPosition && api != null && api.userId.isNotEmpty() && chatId != null && api.ownsHistory(chatId, chatList)) {
            val index = listState.firstVisibleItemIndex
            val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index && it.key is Long }
                ?: listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key is Long }
            val id = item?.key as? Long
            if (id != null && id > 0) {
                val offset = if (item.index == index || index == 0) listState.firstVisibleItemScrollOffset else 0
                ChatReadingPosition(id, offset, index == 0 && !api.historyHasNewer.value)
                    .save(this, api.userId, chatId)
            }
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        tgApi?.let {
            if (it.saveChatId == -1L) {
                tgApi?.saveChatId = 0L
                finish()
            } else {
                if (goToChat.value) {
                    // 标记打开聊天
                    // 启动协程，不阻塞主线程
                    lifecycleScope.launch {
                        waitForSaveChatIdReady()
                        // 最后打开新聊天
                        TgApiManager.tgApi!!.openChatPage(chat!!.id, chatList)
                    }
                    goToChat.value = false
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 接收传递的 Chat 对象
        chat = intent.getParcelableExtra("chat")

        // 获取 TgApi 实例
        tgApi = TgApiManager.tgApi

        // 如果 chat 为 null，直接退出页面
        if (chat == null) {
            finish()
            return
        }

        if (tgApi == null) {
            // 如果 tgApi 为 null，打开主页面
            val openChatIntent = Intent(this, MainActivity::class.java).apply {
                putExtra("chatId", chat!!.id)
            }
            startActivity(openChatIntent)
            finish()
            return
        }

        // 显示加载页面
        setContent {
            TelewatchTheme {
                SplashLoadingScreen(modifier = Modifier.fillMaxSize())
            }
        }

        loadChat()
    }

    private fun loadChat() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                init()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setContent {
                        TelewatchTheme {
                            ErrorScreen(onRetry = ::loadChat, cause = e.message.orEmpty())
                        }
                    }
                }
            }
        }
    }

    private suspend fun init(parameter: String? = null) {
        // 标记打开聊天
        tgApi!!.openChatPage(chat!!.id, chatList)

        // 已读未读消息id传参
        lastReadOutboxMessageId = tgApi!!.getLastReadOutboxMessageId()
        lastReadInboxMessageId = tgApi!!.getLastReadInboxMessageId()

        // 清空旧的聊天消息
        //chatList.value = emptyList()

        // Current-user details are independent of the first message request.
        lifecycleScope.launch {
            tgApi?.getCurrentUser()?.firstOrNull()?.toLongOrNull()?.let {
                currentUserId.value = it
            }
        }

        // 异步获取当前用户聊天对象
        chat?.let { safeChat ->
            val chatObject = tgApi!!.getChat(safeChat.id)
            chatObject?.let { itChatObject ->
                lastReadOutboxMessageId.value = itChatObject.lastReadOutboxMessageId
                lastReadInboxMessageId.value = itChatObject.lastReadInboxMessageId

                val api = tgApi!!
                val savedPosition = ChatReadingPosition.read(this, api.userId, itChatObject.id)
                val restoredLocally = api.loadHistory(itChatObject.id, chatList,
                    savedPosition?.messageId ?: 0L, itChatObject.lastMessage?.id, onlyLocal = true)

                // 获取聊天草稿
                val draftMessage = itChatObject.draftMessage?.inputMessageText
                if (draftMessage is TdApi.InputMessageText) {
                    draftMessage.text?.let {
                        inputText.value = it.text
                    }
                }
                withContext(Dispatchers.Main) {
                    if (savedPosition != null && restoredLocally) {
                        val (index, offset) = readingLocation(savedPosition, api)
                        listState = LazyListState(index, offset)
                    }
                    pendingReadingPosition = savedPosition != null && !restoredLocally
                    setContent {
                        TelewatchTheme {
                            SplashChatScreen(
                                chatTitle = itChatObject.title,
                                chatList = chatList,
                                chatId = chat!!.id,
                                goToChat = { chat ->
                                    goToChat.value = true
                                    startActivity(
                                        Intent(this@ChatActivity, ChatActivity::class.java).apply {
                                            putExtra("chat", chat)
                                        }
                                    )
                                },
                                press = { message ->
                                    println("点击触发")
                                    println(message.id)
                                    when (message.content) {
                                        is TdApi.MessageText -> {
                                            println("文本消息")
                                        }

                                        is TdApi.MessagePhoto -> {
                                            println("图片消息")
                                            val intent = Intent(this@ChatActivity, ViewActivity::class.java)
                                            intent.putExtra("messageId", message.id)
                                            startActivity(intent)
                                        }

                                        // 贴纸消息
                                        is TdApi.MessageSticker -> {
                                            println("贴纸消息")
                                            val intent = Intent(this@ChatActivity, ViewActivity::class.java)
                                            intent.putExtra("messageId", message.id)
                                            startActivity(intent)
                                        }

                                        // GIF消息
                                        is TdApi.MessageAnimation -> {
                                            println("GIF消息")
                                            val intent = Intent(this@ChatActivity, ViewActivity::class.java)
                                            intent.putExtra("messageId", message.id)
                                            startActivity(intent)
                                        }

                                        is TdApi.MessageVideo -> {
                                            println("视频消息")
                                            lifecycleScope.launch {
                                                tgApi!!.getMessageTypeById(message.id)?.let {
                                                    val videoFile =
                                                        (it.content as TdApi.MessageVideo).video.video
                                                    getUriFromFilePath(
                                                        this@ChatActivity,
                                                        videoFile.local.path
                                                    )?.let { uri ->
                                                        playVideo(uri)
                                                    }
                                                }
                                            }
                                        }

                                        is TdApi.MessageVoiceNote -> {
                                            println("语音消息")
                                        }
                                    }
                                },
                                longPress = { select, message ->
                                    //println("长按触发")
                                    //println(message)
                                    when (select) {
                                        "Edit" -> {
                                            Toast.makeText(this@ChatActivity, getString(R.string.Unable_edit), Toast.LENGTH_SHORT).show()
                                            return@SplashChatScreen "OK"
                                        }
                                        "Forward" -> {
                                            tgApi!!.forwardMessage = mutableStateOf(message)
                                            return@SplashChatScreen "OK"
                                        }

                                        "DeleteMessage" -> {
                                            tgApi!!.deleteMessageById(message.id)
                                            Toast.makeText(this@ChatActivity, "OK", Toast.LENGTH_SHORT).show()
                                            return@SplashChatScreen "OK"
                                        }

                                        "CopyLink" -> {
                                            tgApi!!.getMessageLink(
                                                messageId = message.id,
                                                chatId = message.chatId,
                                                callback = { link ->
                                                    if (link != null) {
                                                        runOnUiThread {
                                                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                                                            val clip = ClipData.newPlainText("message_link", link.link)
                                                            clipboard.setPrimaryClip(clip)

                                                            if (link.isPublic) {
                                                                Toast.makeText(this@ChatActivity, getString(R.string.copy_success_public), Toast.LENGTH_SHORT).show()
                                                            } else {
                                                                Toast.makeText(this@ChatActivity, getString(R.string.copy_success_privacy), Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    } else {
                                                        runOnUiThread {
                                                            Toast.makeText(this@ChatActivity, getString(R.string.Failed_request), Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                }
                                            )
                                            return@SplashChatScreen "OK"
                                        }

                                        "ReloadMessage" -> {
                                            tgApi!!.reloadMessageById(message.id)
                                            Toast.makeText(this@ChatActivity, "OK", Toast.LENGTH_SHORT).show()
                                            return@SplashChatScreen "OK"
                                        }

                                        "GetMessage" -> {
                                            return@SplashChatScreen runBlocking { // 同步阻塞当前线程
                                                try {
                                                    tgApi!!.getMessageTypeById(message.id)?.let { messageType ->
                                                        println("GetMessage result: $messageType")
                                                        val gson = Gson()
                                                        formatJson(gson.toJson(messageType))
                                                    } ?: "error"
                                                } catch (e: Exception) {
                                                    "error: ${e.message}"
                                                }
                                            }
                                            /*val gson = Gson()
                                            val messageJson = gson.toJson(message)
                                            return@SplashChatScreen formatJson(messageJson)*/
                                        }

                                        "Save" -> {
                                            when (message.content) {
                                                is TdApi.MessagePhoto -> {
                                                    return@SplashChatScreen runBlocking {
                                                        tgApi!!.getMessageTypeById(message.id)?.let {
                                                            val content = it.content as TdApi.MessagePhoto
                                                            val photo = content.photo
                                                            val photoSizes = photo.sizes
                                                            val highestResPhoto =
                                                                photoSizes.maxByOrNull { it.width * it.height }
                                                            highestResPhoto?.let { itPhoto ->
                                                                val file = itPhoto.photo
                                                                if (file.local.isDownloadingCompleted) {
                                                                    Toast.makeText(
                                                                        this@ChatActivity,
                                                                        saveImageToExternalStorage(
                                                                            this@ChatActivity,
                                                                            file.local.path
                                                                        ),
                                                                        Toast.LENGTH_SHORT
                                                                    ).show()
                                                                    "OK"
                                                                } else {
                                                                    Toast.makeText(
                                                                        this@ChatActivity,
                                                                        getString(R.string.Download_first),
                                                                        Toast.LENGTH_SHORT
                                                                    ).show()
                                                                    "OK"
                                                                }
                                                            }
                                                        }
                                                        "OK"
                                                    }
                                                }

                                                is TdApi.MessageVideo -> {
                                                    return@SplashChatScreen runBlocking {
                                                        tgApi!!.getMessageTypeById(message.id)?.let {
                                                            val content = it.content as TdApi.MessageVideo
                                                            val video = content.video
                                                            video.video.let { videoIt ->
                                                                val videoFile: TdApi.File = videoIt
                                                                if (videoFile.local.isDownloadingCompleted) {
                                                                    Toast.makeText(
                                                                        this@ChatActivity,
                                                                        saveVideoToExternalStorage(
                                                                            this@ChatActivity,
                                                                            videoFile.local.path
                                                                        ),
                                                                        Toast.LENGTH_SHORT
                                                                    ).show()
                                                                    "OK"
                                                                } else {
                                                                    Toast.makeText(
                                                                        this@ChatActivity,
                                                                        getString(R.string.Download_first),
                                                                        Toast.LENGTH_SHORT
                                                                    ).show()
                                                                    "OK"
                                                                }
                                                            }
                                                        }
                                                        "OK"
                                                    }
                                                }
                                            }
                                            Toast.makeText(
                                                this@ChatActivity,
                                                getString(R.string.No_need_to_save),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            return@SplashChatScreen "OK"
                                        }

                                        else -> return@SplashChatScreen "NotFind"
                                    }
                                },
                                chatObject = itChatObject,
                                lastReadOutboxMessageId = lastReadOutboxMessageId,
                                lastReadInboxMessageId = lastReadInboxMessageId,
                                inputText = inputText,
                                listState = listState,
                                onLinkClick = { url ->
                                    urlHandle(url, this@ChatActivity) {
                                        if (it) goToChat.value = true
                                    }
                                },
                                chatTitleClick = {
                                    startActivity(
                                        Intent(this@ChatActivity, ChatInfoActivity::class.java).apply {
                                            putExtra("chat", Chat(
                                                id = chat!!.id,
                                                title = chat!!.title
                                            )
                                            )
                                        }
                                    )
                                    goToChat.value = true
                                },
                                currentUserId = currentUserId,
                                chatTopics = chatTopics.value
                            )
                        }
                    }
                    if (savedPosition == null || !restoredLocally) {
                        lifecycleScope.launch {
                            val applied = api.loadHistory(itChatObject.id, chatList,
                                savedPosition?.messageId ?: 0L, itChatObject.lastMessage?.id) {
                                savedPosition == null || (!listState.isScrollInProgress &&
                                    listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0)
                            }
                            if (applied && savedPosition != null) {
                                val (index, offset) = readingLocation(savedPosition, api)
                                listState.requestScrollToItem(index, offset)
                            }
                            if (applied || listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
                                pendingReadingPosition = false
                            }
                        }
                    }

                    val supergroup = itChatObject.type as? TdApi.ChatTypeSupergroup
                    if (supergroup != null && !supergroup.isChannel) lifecycleScope.launch {
                        if (api.getSupergroup(supergroup.supergroupId)?.isForum != false) {
                            chatTopics.value = api.getForumTopics(itChatObject.id)?.topics.orEmpty().associate { topic ->
                                val thread = topic.info.messageThreadId
                                (if (topic.lastMessage?.messageThreadId?.let { it != thread } == true) 0L else thread) to
                                    topic.info.name
                            }
                        }
                    }
                }
            }
            if (chatObject == null) {
                if (parameter == "lastRun") {
                    throw IllegalStateException("Unable to create private chat")
                } else {
                    tgApi!!.createPrivateChat(chat!!.id)
                    init("lastRun")
                }
            }
        }
    }

    private fun readingLocation(saved: ChatReadingPosition, api: TgApi): Pair<Int, Int> {
        val messages = chatList.value
        val exact = messages.indexOfFirst { it.id == saved.messageId }
        val nearby = if (exact < 0) messages.indexOfFirst { it.id < saved.messageId } else exact
        val index = if (nearby < 0) messages.lastIndex else nearby
        val atBottom = saved.atBottom && exact == 0 && !api.historyHasNewer.value
        val offset = if (exact >= 0 && (!saved.atBottom || atBottom)) saved.offset else 0
        return (if (atBottom || index < 0) 0 else index + 1) to offset
    }

    fun saveVideoToExternalStorage(context: Context, videoPath: String): String {
        // 获取视频文件名
        val videoName = File(videoPath).nameWithoutExtension

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10及以上使用MediaStore
            val selection = "${MediaStore.Video.Media.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf("$videoName.mp4")
            val queryUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val cursor =
                context.contentResolver.query(queryUri, null, selection, selectionArgs, null)

            if (cursor != null && cursor.count > 0) {
                cursor.close()
                return context.getString(R.string.Same_name_video_exists)
            }
            cursor?.close()

            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "$videoName.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/Telewatch"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1) // 设置IS_PENDING状态
            }

            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
                ?: return context.getString(R.string.failling)

            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    File(videoPath).inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
                return context.getString(R.string.failling)
            }

            // 更新IS_PENDING状态
            contentValues.clear()
            contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, contentValues, null, null)

            return context.getString(R.string.Save_success)
        } else {
            // Android 8和9使用传统方式保存到外部存储
            val videosDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "Telewatch"
            )
            if (!videosDir.exists()) {
                videosDir.mkdirs()
            }

            val file = File(videosDir, "$videoName.mp4")

            if (file.exists()) {
                return context.getString(R.string.Same_name_video_exists)
            }

            FileInputStream(File(videoPath)).use { inputStream ->
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            // 通知系统图库更新
            context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)))

            return context.getString(R.string.Save_success)
        }
    }

    fun saveImageToExternalStorage(context: Context, photoPath: String): String {
        // 获取图片文件名
        val imageName = File(photoPath).nameWithoutExtension

        // 从内部存储读取图片
        val bitmap =
            BitmapFactory.decodeFile(photoPath) ?: return context.getString(R.string.Read_failed)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10及以上使用MediaStore
            val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf("$imageName.jpg")
            val queryUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val cursor =
                context.contentResolver.query(queryUri, null, selection, selectionArgs, null)

            if (cursor != null && cursor.count > 0) {
                cursor.close()
                return context.getString(R.string.Same_name_image_exists)
            }
            cursor?.close()

            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$imageName.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Telewatch"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1) // 设置IS_PENDING状态
            }

            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
                ?: return context.getString(R.string.failling)

            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                outputStream.flush()
            }

            // 更新IS_PENDING状态
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, contentValues, null, null)

            return context.getString(R.string.Save_success)
        } else {
            // Android 8和9使用传统方式保存到外部存储
            val imagesDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "Telewatch"
            )
            if (!imagesDir.exists()) {
                imagesDir.mkdirs()
            }

            val file = File(imagesDir, "$imageName.jpg")

            if (file.exists()) {
                return context.getString(R.string.Same_name_image_exists)
            }

            FileOutputStream(file).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            }

            // 通知系统图库更新
            context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)))

            return context.getString(R.string.Save_success)
        }
    }

    @SuppressLint("QueryPermissionsNeeded")
    fun playVideo(videoUri: Uri) {
        // 创建一个 Intent 来播放视频
        val intent = Intent(Intent.ACTION_VIEW).apply {
            // 设置数据和 MIME 类型
            setDataAndType(videoUri, "video/*")
            // 确保系统能够通过选择器显示可用的播放器应用
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // 检查是否有应用能够处理这个 Intent
        if (intent.resolveActivity(packageManager) != null) {
            // 启动系统的选择器来选择一个播放器
            startActivity(Intent.createChooser(intent, getString(R.string.Select_player)))
        } else {
            // 处理没有应用能够播放视频的情况
            Toast.makeText(this, getString(R.string.No_player), Toast.LENGTH_SHORT).show()
        }
    }

    private fun getUriFromFilePath(context: Context, filePath: String): Uri? {
        // 创建File对象
        val file = File(filePath)

        // 确保文件存在
        if (!file.exists()) {
            return null
        }

        // 返回Uri
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** 每 50ms 检查一次 saveChatId，直到它回到 0L 或 -1L */
    private suspend fun waitForSaveChatIdReady() {
        withContext(Dispatchers.Default) {
            while (true) {
                val id = TgApiManager.tgApi?.saveChatId
                if (id == 0L || id == -1L) break
                delay(50)
            }
        }
    }
}

fun formatJson(jsonString: String): String {
    val gson = GsonBuilder().setPrettyPrinting().create()
    val jsonElement = gson.fromJson(jsonString, Any::class.java)
    return gson.toJson(jsonElement)
}
