package com.gohj99.telewatch.model

import android.content.Context

internal data class ChatReadingPosition(val messageId: Long, val offset: Int, val atBottom: Boolean) {
    companion object {
        fun read(context: Context, account: String, chatId: Long): ChatReadingPosition? {
            val parts = prefs(context).getString("$account:$chatId", null)?.split(':') ?: return null
            if (parts.size != 3) return null
            val id = parts[0].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val offset = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            return ChatReadingPosition(id, offset, parts[2] == "1")
        }

        private fun prefs(context: Context) = context.getSharedPreferences("chat_reading_position", Context.MODE_PRIVATE)
    }

    fun save(context: Context, account: String, chatId: Long) {
        prefs(context).edit().putString("$account:$chatId", "$messageId:$offset:${if (atBottom) 1 else 0}").apply()
    }
}
