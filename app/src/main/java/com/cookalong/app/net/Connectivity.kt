package com.cookalong.app.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import java.io.IOException

/** What the system says about the default network. Pure data, so the rules below can be tested without Android. */
enum class NetReading {
    /** No default network at all (Wi-Fi off, cable unplugged, airplane mode). */
    NONE,
    /** Connected to a network that Android has not validated as having internet (router without uplink, captive portal). */
    NOT_VALIDATED,
    VALIDATED,
    /** Capabilities couldn't be read. The app assumes online rather than blocking itself. */
    UNKNOWN,
}

/** A request failed because the network is unreachable (as opposed to a server error). Message is TV-friendly. */
class NoInternetException(message: String, cause: Throwable? = null) : IOException(message, cause)

object Connectivity {
    /** How long "offline" must last before the UI says so; a Wi-Fi roam or a DHCP renewal is not an outage. */
    const val OFFLINE_GRACE_MS = 1_500L

    fun isOnline(reading: NetReading) = reading == NetReading.VALIDATED || reading == NetReading.UNKNOWN

    /**
     * Readings to the "online" flag. Going online is immediate; going offline waits [graceMs] and is dropped if a
     * network comes back in the meantime. The first reading is applied at once so a cold start offline says so.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun onlineFlow(readings: Flow<NetReading>, graceMs: Long = OFFLINE_GRACE_MS): Flow<Boolean> {
        var first = true
        return readings.map(::isOnline).distinctUntilChanged().transformLatest { online ->
            if (!online && !first) delay(graceMs)
            first = false
            emit(online)
        }.distinctUntilChanged()
    }

    /** Hot "online" flag from the system's default-network callback. Starts true until the first reading arrives. */
    fun stateIn(context: Context, scope: CoroutineScope): StateFlow<Boolean> =
        onlineFlow(systemReadings(context)).stateIn(scope, SharingStarted.Eagerly, true)

    /** For tests and previews. */
    fun fixed(online: Boolean): StateFlow<Boolean> = MutableStateFlow(online)

    @SuppressLint("MissingPermission")
    private fun systemReadings(context: Context): Flow<NetReading> = callbackFlow {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) { trySend(NetReading.UNKNOWN); close(); return@callbackFlow }
        fun read(caps: NetworkCapabilities?) = when {
            caps == null -> NetReading.UNKNOWN
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetReading.NOT_VALIDATED
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> NetReading.VALIDATED
            else -> NetReading.NOT_VALIDATED
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { trySend(read(caps)) }
            override fun onLost(network: Network) { trySend(NetReading.NONE) }
        }
        try {
            val active = cm.activeNetwork
            trySend(if (active == null) NetReading.NONE else read(cm.getNetworkCapabilities(active)))
            cm.registerDefaultNetworkCallback(callback)
        } catch (e: SecurityException) {
            trySend(NetReading.UNKNOWN); close(); return@callbackFlow
        } catch (e: RuntimeException) {
            trySend(NetReading.UNKNOWN); close(); return@callbackFlow
        }
        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()
}
