package com.gohj99.telewatch.utils.telegram

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.LinkedHashMap

internal class ThumbnailDownloads(
    private val send: (TdApi.Function<*>, Client.ResultHandler) -> Unit
) {
    constructor(client: Client) : this({ request, handler -> client.send(request, handler) })

    private class Pending(val fileId: Int, var priority: Int) {
        val result = CompletableDeferred<String?>()
        var users = 0
        var started = false
        var foreground = false
    }

    private val lock = Any()
    private val paths = object : LinkedHashMap<Int, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?): Boolean = size > 128
    }
    private val pending = LinkedHashMap<Int, Pending>()
    private var thumbnails = 0
    private var foreground = 0
    private var closed = false

    fun cachedPath(file: TdApi.File): String? = synchronized(lock) {
        paths[file.id] ?: file.local?.takeIf { it.isDownloadingCompleted }?.path?.takeIf(String::isNotEmpty)
    }

    suspend fun path(file: TdApi.File, priority: Int = 8, timeoutMillis: Long = 30_000): String? {
        val request = synchronized(lock) {
            cachedPath(file)?.let { return it }
            if (closed) return null
            val request = pending.getOrPut(file.id) { Pending(file.id, priority.coerceIn(1, 32)) }
            request.users++
            if (priority > request.priority) {
                request.priority = priority.coerceAtMost(32)
                if (request.started) {
                    send(TdApi.DownloadFile(file.id, request.priority, 0, 0, false)) { }
                }
            }
            startQueued()
            request
        }

        return try {
            withTimeoutOrNull(timeoutMillis) { request.result.await() }
        } finally {
            synchronized(lock) {
                request.users--
                if (request.users == 0 && pending[file.id] === request) {
                    pending.remove(file.id)
                    if (request.started) {
                        release(request)
                        // A late response must not remove a subsequent request for the same file.
                        send(TdApi.CancelDownloadFile(file.id, false)) { }
                    }
                    startQueued()
                }
            }
        }
    }

    // Keep an independent slot for an explicitly opened photo or selected sticker.
    private fun startQueued() {
        while (!closed) {
            val request = pending.values.filter {
                !it.started && if (it.priority >= 16) foreground < 1 else thumbnails < 4
            }.maxByOrNull { it.priority } ?: return
            request.started = true
            request.foreground = request.priority >= 16
            if (request.foreground) foreground++ else thumbnails++
            send(TdApi.DownloadFile(request.fileId, request.priority, 0, 0, true)) { response ->
                synchronized(lock) {
                    if (pending[request.fileId] === request) {
                        val path = (response as? TdApi.File)?.local?.takeIf { it.isDownloadingCompleted }
                            ?.path?.takeIf(String::isNotEmpty)
                        if (path != null) paths[request.fileId] = path
                        pending.remove(request.fileId)
                        release(request)
                        request.result.complete(path)
                        startQueued()
                    }
                }
            }
        }
    }

    private fun release(request: Pending) {
        if (request.foreground) foreground-- else thumbnails--
    }

    fun close() {
        synchronized(lock) {
            closed = true
            val requests = pending.values.toList()
            pending.clear()
            thumbnails = 0
            foreground = 0
            requests.forEach { request ->
                if (request.started) send(TdApi.CancelDownloadFile(request.fileId, false)) { }
                request.result.complete(null)
            }
        }
    }
}
