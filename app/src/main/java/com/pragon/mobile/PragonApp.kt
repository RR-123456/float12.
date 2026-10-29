package com.pragon.mobile

import android.app.Application
import android.content.ComponentCallbacks2

/** Runs before anything else in every Pragon process: gives the network shield access to the settings. */
class PragonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Guard.app = applicationContext
        Net.init(applicationContext)
        // If the firewall was on before a restart or an app update, put it back.
        try { Firewall.restore(this) } catch (e: Exception) { }
    }

    /** v10: the on-device models (hundreds of MB) are let go when Android is really short of memory; they reload on next use. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            try { OnDeviceLlm.release() } catch (e: Exception) { }
            try { SherpaStt.release() } catch (e: Exception) { }
        }
    }
}
