package com.pragon.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * v11: "is there working internet right now?". A cached flag kept fresh by Android's network callback, so
 * Offline.active() can ask on every request without slowing anything down.
 */
object Net {
    @Volatile private var cached = true
    @Volatile private var started = false
    /** Called on an Android background thread when online <-> offline changes. */
    @Volatile var onChange: ((Boolean) -> Unit)? = null

    private fun cm(c: Context) = c.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun probe(c: Context): Boolean = try {
        val m = cm(c)
        val n = m.activeNetwork
        val caps = if (n == null) null else m.getNetworkCapabilities(n)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (e: Exception) { false }

    fun init(app: Context) {
        if (started) return
        started = true
        cached = probe(app)
        try {
            cm(app).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                private fun refresh() {
                    val now = probe(app)
                    if (now != cached) { cached = now; try { onChange?.invoke(now) } catch (e: Exception) { } }
                }
                override fun onAvailable(network: Network) { refresh() }
                override fun onLost(network: Network) { refresh() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { refresh() }
            })
        } catch (e: Exception) { }
    }

    /** True when the phone has a validated internet connection. */
    fun online(c: Context): Boolean {
        if (!started) init(c.applicationContext)
        return cached
    }
}
