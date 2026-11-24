package com.gohj99.telewatch.utils.telegram

import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Uses the owning account's TDLib client, including its file identifiers. */
internal suspend fun <T : TdApi.Object> TgApi.stickerRequest(request: TdApi.Function<T>): T =
    suspendCancellableCoroutine { continuation ->
        client.send(request) { result ->
            if (result is TdApi.Error) {
                continuation.resumeWithException(IllegalStateException(result.message))
            } else {
                @Suppress("UNCHECKED_CAST")
                continuation.resume(result as T)
            }
        }
    }

internal suspend fun TgApi.sendSticker(
    chatId: Long,
    sticker: TdApi.Sticker,
    reply: TdApi.Message?,
    threadId: Long
): TdApi.Message {
    val replyTo = when {
        reply == null -> null
        reply.chatId == chatId -> TdApi.InputMessageReplyToMessage(reply.id, null)
        else -> TdApi.InputMessageReplyToExternalMessage(reply.chatId, reply.id, null)
    }
    return stickerRequest(TdApi.SendMessage().apply {
        this.chatId = chatId
        messageThreadId = threadId
        this.replyTo = replyTo
        inputMessageContent = TdApi.InputMessageSticker(
            TdApi.InputFileId(sticker.sticker.id), null, sticker.width, sticker.height, sticker.emoji
        )
    })
}
