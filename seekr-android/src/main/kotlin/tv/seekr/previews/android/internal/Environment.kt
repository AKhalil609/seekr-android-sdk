package tv.seekr.previews.android.internal

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import java.io.File
import tv.seekr.previews.android.NetworkPolicy
import tv.seekr.previews.core.contrib.InstallationStore

/** Whether the network may be touched now ([NetworkPolicy.Unmetered]: only on an unmetered active network). */
internal fun interface NetworkGate {
    fun isAllowed(): Boolean
}

internal class AndroidNetworkGate(context: Context, private val policy: NetworkPolicy) : NetworkGate {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    @Suppress("DEPRECATION")
    override fun isAllowed(): Boolean {
        if (policy == NetworkPolicy.Any) return true
        val manager = cm ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val caps = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            } else {
                // API 21-22 have no activeNetwork: isActiveNetworkMetered is the equivalent signal.
                manager.activeNetworkInfo?.isConnected == true && !manager.isActiveNetworkMetered
            }
        } catch (e: SecurityException) {
            false // ACCESS_NETWORK_STATE revoked: cannot prove the network is unmetered, so stay off it
        }
    }
}

/** Device thermal state; true once the platform reports throttling. API < 29 has no such signal (the app's own snapshot flag still applies). */
internal fun interface ThermalMonitor {
    fun isThrottled(): Boolean
}

internal class AndroidThermalMonitor(context: Context) : ThermalMonitor {
    private val power = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    override fun isThrottled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        // MODERATE and up: the 1 GB Fire TV class is the reason for being this early, any throttling means we are competing with playback.
        return (power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE) >= PowerManager.THERMAL_STATUS_MODERATE
    }
}

/** The installation token (API-key auth only) persisted next to the app's private files, so it survives restarts. */
internal class FileInstallationStore(private val file: File) : InstallationStore {
    override suspend fun load(): String? = synchronized(this) {
        try {
            file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: java.io.IOException) {
            null
        }
    }

    override suspend fun save(token: String) {
        synchronized(this) {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(token)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    override suspend fun clear() {
        synchronized(this) { file.delete() }
    }
}
