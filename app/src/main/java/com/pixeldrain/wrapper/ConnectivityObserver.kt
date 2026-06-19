package com.pixeldrain.wrapper

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Lightweight wrapper around [ConnectivityManager] that reports whether the
 * device currently has a validated internet connection and pushes live
 * availability changes to a callback. Used to drive the offline screen and the
 * automatic reconnect behaviour.
 */
class ConnectivityObserver(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Returns true if there is an active network with internet capability right now. */
    fun isOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Starts observing connectivity. [onChange] is invoked on a binder thread
     * with the latest availability; callers should marshal to the main thread.
     */
    fun start(onChange: (isOnline: Boolean) -> Unit) {
        stop()
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange(true)
            override fun onLost(network: Network) = onChange(isOnline())
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val validated = networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED
                )
                if (validated) onChange(true)
            }
        }
        callback = cb
        connectivityManager.registerNetworkCallback(request, cb)
    }

    /** Stops observing. Safe to call multiple times. */
    fun stop() {
        callback?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }
        callback = null
    }
}
