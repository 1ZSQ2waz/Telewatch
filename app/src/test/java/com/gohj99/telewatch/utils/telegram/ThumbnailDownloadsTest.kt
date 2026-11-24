package com.gohj99.telewatch.utils.telegram

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailDownloadsTest {
    private class FakeClient {
        data class Call(val request: TdApi.Function<*>, val handler: Client.ResultHandler)
        val calls = mutableListOf<Call>()
        fun send(request: TdApi.Function<*>, handler: Client.ResultHandler) {
            calls.add(Call(request, handler))
        }
        fun downloads() = calls.filter { (it.request as? TdApi.DownloadFile)?.synchronous == true }
        fun cancelledIds() = calls.mapNotNull { (it.request as? TdApi.CancelDownloadFile)?.fileId }
        fun finish(call: Call, path: String = "/cache/thumbnail.webp") {
            val id = (call.request as TdApi.DownloadFile).fileId
            call.handler.onResult(file(id).apply {
                local = TdApi.LocalFile().apply { isDownloadingCompleted = true; this.path = path }
            })
        }
    }

    @Test
    fun offscreenRequestsReleaseCapacityAndLateCallbacksCannotRemoveTheirReplacements() = runBlocking {
        val client = FakeClient()
        val downloads = ThumbnailDownloads(client::send)
        val jobs = (1..5).map { id -> async(start = CoroutineStart.UNDISPATCHED) { downloads.path(file(id)) } }
        assertEquals(listOf(1, 2, 3, 4), client.downloads().map { (it.request as TdApi.DownloadFile).fileId })
        val stale = client.downloads().first()
        jobs.first().cancelAndJoin()
        assertEquals(listOf(1), client.cancelledIds())
        assertEquals(5, (client.downloads().last().request as TdApi.DownloadFile).fileId)
        val replacement = async(start = CoroutineStart.UNDISPATCHED) { downloads.path(file(1)) }
        client.finish(stale)
        assertEquals(5, client.downloads().size)
        client.finish(client.downloads()[1])
        assertEquals(1, (client.downloads().last().request as TdApi.DownloadFile).fileId)
        client.finish(stale, "/cache/stale.webp")
        client.finish(client.downloads().last(), "/cache/new.webp")
        assertEquals("/cache/new.webp", replacement.await())
        downloads.close()
        jobs.drop(1).forEach { it.await() }
    }

    @Test
    fun sharedDownloadsStayAliveUntilTheirLastConsumerLeavesAndCompletedFilesAreCached() = runBlocking {
        val client = FakeClient()
        val downloads = ThumbnailDownloads(client::send)
        val first = async(start = CoroutineStart.UNDISPATCHED) { downloads.path(file(8)) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { downloads.path(file(8)) }
        assertEquals(1, client.downloads().size)
        first.cancelAndJoin()
        assertTrue(client.cancelledIds().isEmpty())
        client.finish(client.downloads().single())
        assertEquals("/cache/thumbnail.webp", second.await())
        assertEquals("/cache/thumbnail.webp", downloads.cachedPath(file(8)))
        assertEquals("/cache/thumbnail.webp", downloads.path(file(8)))
        assertEquals(1, client.downloads().size)
        downloads.close()
    }

    @Test
    fun openedMediaUsesItsOwnSlotAndCanPromoteAnExistingDownload() = runBlocking {
        val client = FakeClient()
        val downloads = ThumbnailDownloads(client::send)
        val background = (1..5).map { id -> async(start = CoroutineStart.UNDISPATCHED) { downloads.path(file(id)) } }
        val opened = async(start = CoroutineStart.UNDISPATCHED) { downloads.path(file(10), priority = 32) }
        assertEquals(10, (client.downloads().last().request as TdApi.DownloadFile).fileId)
        val next = async(start = CoroutineStart.UNDISPATCHED) { downloads.path(file(11), priority = 24) }
        assertEquals(5, client.downloads().size)
        opened.cancelAndJoin()
        assertEquals(11, (client.downloads().last().request as TdApi.DownloadFile).fileId)
        val promoted = async(start = CoroutineStart.UNDISPATCHED) { downloads.path(file(1), priority = 24) }
        val promotion = client.calls.last().request as TdApi.DownloadFile
        assertEquals(1, promotion.fileId)
        assertEquals(24, promotion.priority)
        assertEquals(false, promotion.synchronous)
        downloads.close()
        background.forEach { it.await() }
        assertNull(next.await())
        assertNull(promoted.await())
    }

    @Test
    fun aTimedOutDownloadCanRetryImmediatelyWithoutANegativeCache() = runBlocking {
        val client = FakeClient()
        val downloads = ThumbnailDownloads(client::send)
        assertNull(downloads.path(file(1), timeoutMillis = 5))
        assertEquals(listOf(1), client.cancelledIds())
        val retried = async(start = CoroutineStart.UNDISPATCHED) { downloads.path(file(1)) }
        assertEquals(2, client.downloads().size)
        client.finish(client.downloads().last())
        assertEquals("/cache/thumbnail.webp", retried.await())
        downloads.close()
    }

    @Test
    fun closingAnAccountCancelsActiveDownloadsAndUnblocksQueuedConsumers() = runBlocking {
        val client = FakeClient()
        val downloads = ThumbnailDownloads(client::send)
        val jobs = (1..6).map { id -> async(start = CoroutineStart.UNDISPATCHED) { downloads.path(file(id)) } }
        downloads.close()
        assertEquals(listOf(1, 2, 3, 4), client.cancelledIds())
        jobs.forEach { assertNull(it.await()) }
        assertNull(downloads.path(file(7)))
        assertEquals(4, client.downloads().size)
    }

    companion object {
        private fun file(id: Int) = TdApi.File().apply { this.id = id }
    }
}
