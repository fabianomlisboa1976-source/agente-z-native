package dev.mindmax.v4.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.core.content.getSystemService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.CoroutineScope

/**
 * Cold → hot conversion of ConnectivityManager callbacks into a Flow<NetworkState>.
 * Use [observe] to subscribe; cancel/attach via [attach]/[detach] from a long-lived owner.
 *
 * The watcher is intentionally small: we only care about "online/offline" right
 * now, but emit the underlying Network so callers can distinguish wifi vs cellular.
 */
class NetworkObserver(context: Context) {

    private val appContext = context.applicationContext
    private val manager: ConnectivityManager = appContext.getSystemService()!!

    private val _observe: Flow<NetworkState> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(NetworkState.Available(network))
            }

            override fun onLost(network: Network) {
                trySend(NetworkState.Lost(network))
            }

            override fun onUnavailable() {
                trySend(NetworkState.Unavailable)
            }
        }
        manager.registerDefaultCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }

    /** Shared hot flow — late subscribers immediately get the latest state. */
    fun observe(scope: CoroutineScope) = _observe.shareIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        replay = 1,
    )

    fun attach() {
        // No-op: callbackFlow handles registration. We keep the API symmetric
        // with [detach] so callers have a clear "begin/end" pair.
    }

    fun detach() {
        // The flow cancels itself when the owner goes away; intentionally a no-op.
    }

    sealed class NetworkState {
        data class Available(val network: Network) : NetworkState()
        data class Lost(val network: Network) : NetworkState()
        data object Unavailable : NetworkState()
    }
}
