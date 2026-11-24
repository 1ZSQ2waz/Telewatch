/*
 * Based on Niagram's WebSocketHelper (Nullgram).
 * Copyright (C) 2019-2024 qwq233 <qwq233@qwq2333.top>
 * https://github.com/qwq233/Nullgram
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.gohj99.telewatch.utils.proxy

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.tcp2ws.tcp2wsServer
import org.tcp2ws.WorkerRelay
import org.tcp2ws.WorkerRoutes
import java.io.IOException
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

internal object CloudflareProxy {
    private const val DEFAULT_PORT = 6356
    private const val TAG = "CloudflareWorkerProxy"
    private const val KEY_ALIAS = "telewatch_worker_secret"
    private const val REGIONAL_PROXY_DOMAIN = "5es.im"

    data class Settings(val enabled: Boolean, val endpoint: String = "", val secret: String = "")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = Mutex()
    private val clients = mutableMapOf<Client, Boolean>()
    private var relay: tcp2wsServer? = null
    private var relaySettings: Settings? = null
    private var connectivity: ConnectivityManager? = null
    private var network: Network? = null
    private var networkType: Int? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = networkChanged()
        override fun onLost(network: Network) = networkChanged()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = networkChanged()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("niagramx_cloudflare", Context.MODE_PRIVATE)

    fun settings(context: Context): Settings = prefs(context).let {
        val endpoint = it.getString("worker_endpoint", "").orEmpty()
        val secret = it.getString("worker_secret", null)?.let { value ->
            runCatching { decryptSecret(value) }.getOrDefault("")
        }.orEmpty()
        Settings(it.getBoolean("enabled", false) && endpoint.isNotEmpty() && secret.isNotEmpty(), endpoint, secret)
    }

    fun attach(client: Client) {
        synchronized(clients) { clients[client] = false }
    }

    fun ready(context: Context, client: Client) {
        synchronized(clients) {
            if (client !in clients) return
            clients[client] = true
        }
        val app = context.applicationContext
        scope.launch {
            operations.withLock {
                if (!isReady(client)) return@withLock
                try {
                    monitorNetwork(app)
                    val configuration = settings(app)
                    val port = if (configuration.enabled) startRelay(prefs(app), configuration, false) else null
                    applyProxy(prefs(app), client, port)
                    client.send(TdApi.SetNetworkType(currentNetworkType())) {}
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Log.w(TAG, "Unable to restore built-in proxy", error)
                }
            }
        }
    }

    fun detach(client: Client) {
        synchronized(clients) { clients.remove(client) }
        scope.launch {
            operations.withLock {
                if (readyClients().isEmpty()) {
                    stopRelay()
                    connectivity?.unregisterNetworkCallback(networkCallback)
                    connectivity = null
                    network = null
                    networkType = null
                }
            }
        }
    }

    // The transition runs in the process scope so leaving the settings page cannot
    // cancel an AddProxy after TDLib has accepted it but before settings are saved.
    suspend fun configure(context: Context, configuration: Settings) {
        val result = CompletableDeferred<Result<Unit>>()
        val app = context.applicationContext
        scope.launch {
            result.complete(runCatching {
                operations.withLock { configureLocked(app, configuration) }
            })
        }
        result.await().getOrThrow()
    }

    private suspend fun configureLocked(context: Context, configuration: Settings) {
        val storage = prefs(context)
        val before = settings(context)
        val next = configuration.copy(endpoint = configuration.endpoint.trim(), secret = configuration.secret.trim())
        val encrypted = if (next.secret == before.secret) storage.getString("worker_secret", null)
            else next.secret.takeIf { it.isNotEmpty() }?.let(::encryptSecret)
        if (!next.enabled && (next.endpoint.isNotEmpty() || next.secret.isNotEmpty())) {
            WorkerRelay(next.endpoint, next.secret).close()
        }
        try {
            val port = if (next.enabled) startRelay(storage, next, true) else null
            for (client in readyClients()) applyProxy(storage, client, port)
            storage.edit().putBoolean("enabled", next.enabled)
                .putString("worker_endpoint", next.endpoint).putString("worker_secret", encrypted).apply()
            if (!next.enabled) stopRelay()
        } catch (error: Exception) {
            runCatching {
                val port = if (before.enabled) startRelay(storage, before, false) else null
                for (client in readyClients()) {
                    runCatching { applyProxy(storage, client, port) }
                        .onFailure { Log.w(TAG, "Unable to restore proxy selection", it) }
                }
            }
            if (!before.enabled) stopRelay()
            throw error
        }
    }

    suspend fun useManual(context: Context) {
        val current = settings(context)
        if (current.enabled) configure(context, current.copy(enabled = false))
    }

    fun isBuiltIn(context: Context, proxy: TdApi.Proxy): Boolean = owns(prefs(context), proxy)

    private fun owns(storage: SharedPreferences, proxy: TdApi.Proxy): Boolean {
        val socks = proxy.type as? TdApi.ProxyTypeSocks5 ?: return false
        return proxy.server == tcp2wsServer.LOOPBACK && socks.username.isEmpty() &&
            socks.password.isEmpty() && proxy.port.toString() in storage.getStringSet("ports", emptySet()).orEmpty()
    }

    private fun startRelay(storage: SharedPreferences, configuration: Settings, probe: Boolean): Int {
        relay?.takeIf { it.isOpen && relaySettings == configuration }?.let { return it.port }
        val primary = WorkerRelay(configuration.endpoint, configuration.secret)
        val endpoint = URI.create(configuration.endpoint.trim())
        val regional = if (
            endpoint.host.equals("telewatch.$REGIONAL_PROXY_DOMAIN", ignoreCase = true) &&
            (endpoint.port == -1 || endpoint.port == 443)
        ) {
            listOf("sg", "nl", "us").associateWith { region ->
                WorkerRelay("https://telewatch-$region.$REGIONAL_PROXY_DOMAIN", configuration.secret)
            }
        } else emptyMap()
        val worker = WorkerRoutes(primary, regional)
        val started = try {
            if (probe) worker.prepare()
            stopRelay()
            tcp2wsServer(storage.getInt("last_port", DEFAULT_PORT), worker)
        } catch (error: Exception) {
            worker.close()
            throw error
        }
        relay = started
        relaySettings = configuration
        storage.edit().putInt("last_port", started.port)
            .putStringSet("ports", storage.getStringSet("ports", emptySet()).orEmpty() + started.port.toString())
            .apply()
        return started.port
    }

    private fun stopRelay() {
        relay?.close()
        relay = null
        relaySettings = null
    }

    private fun secretKey(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Worker secret key is unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    private fun encryptSecret(secret: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(true))
        return Base64.encodeToString(cipher.iv + cipher.doFinal(secret.toByteArray()), Base64.NO_WRAP)
    }

    private fun decryptSecret(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(false), GCMParameterSpec(128, bytes, 0, 12))
        return cipher.doFinal(bytes, 12, bytes.size - 12).decodeToString()
    }

    private suspend fun applyProxy(storage: SharedPreferences, client: Client, port: Int?) {
        if (!isReady(client)) return
        val proxies = (request(client, TdApi.GetProxies()) as TdApi.Proxies).proxies.orEmpty()
        val managed = proxies.filter { owns(storage, it) }
        val keep = if (port == null) null else {
            val active = managed.firstOrNull { it.isEnabled && it.port == port }
            active ?: request(client, TdApi.AddProxy(tcp2wsServer.LOOPBACK, port, true,
                TdApi.ProxyTypeSocks5("", ""))) as TdApi.Proxy
        }
        for (proxy in managed) {
            if (proxy.id != keep?.id) request(client, TdApi.RemoveProxy(proxy.id))
        }
    }

    private suspend fun request(client: Client, function: TdApi.Function<*>): TdApi.Object =
        withTimeoutOrNull(10_000L) {
            val response = suspendCancellableCoroutine { continuation ->
                client.send(function) { continuation.resume(it) }
            }
            if (response is TdApi.Error) throw IllegalStateException(response.message)
            response
        } ?: throw IOException("Timed out waiting for TDLib proxy settings")

    private fun isReady(client: Client): Boolean = synchronized(clients) { clients[client] == true }
    private fun readyClients(): List<Client> = synchronized(clients) { clients.filterValues { it }.keys.toList() }

    private fun monitorNetwork(context: Context) {
        if (connectivity != null) return
        val manager = context.getSystemService(ConnectivityManager::class.java)
        manager.registerDefaultNetworkCallback(networkCallback)
        connectivity = manager
        network = manager.activeNetwork
    }

    private fun networkChanged() {
        scope.launch {
            operations.withLock {
                val manager = connectivity ?: return@withLock
                val current = manager.activeNetwork
                val type = currentNetworkType()
                if (network != current) relay?.resetConnections()
                if (network != current || networkType != type.constructor) {
                    for (client in readyClients()) client.send(TdApi.SetNetworkType(type)) {}
                }
                network = current
                networkType = type.constructor
            }
        }
    }

    fun onForeground() {
        scope.launch {
            operations.withLock {
                val manager = connectivity ?: return@withLock
                val current = manager.activeNetwork
                val type = currentNetworkType()
                // Screen-off can invalidate a TCP lane without changing the default network.
                relay?.resetConnections()
                for (client in readyClients()) client.send(TdApi.SetNetworkType(type)) {}
                network = current
                networkType = type.constructor
                Log.i(TAG, "Refreshed TDLib transport after returning to foreground")
            }
        }
    }

    private fun currentNetworkType(): TdApi.NetworkType {
        val manager = connectivity ?: return TdApi.NetworkTypeOther()
        val current = manager.activeNetwork ?: return TdApi.NetworkTypeNone()
        val caps = manager.getNetworkCapabilities(current) ?: return TdApi.NetworkTypeOther()
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> TdApi.NetworkTypeMobile()
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TdApi.NetworkTypeWiFi()
            else -> TdApi.NetworkTypeOther()
        }
    }
}
