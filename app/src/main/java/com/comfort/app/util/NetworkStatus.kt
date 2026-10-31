package com.comfort.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.comfort.app.data.GalleryDlPreferences

/** Whether the device currently has a network a download may start on — what DownloadWorker
 * waits for before starting ([downloadNetwork]; the jobs carry no WorkManager network condition
 * any more): a validated, internet-capable network, additionally restricted to unmetered (Wi-Fi/
 * Ethernet) when the Wi-Fi-only setting is on. The same rule as the worker's, so this reflects
 * exactly why a queued download either can or can't actually start right now, not a looser "is
 * airplane mode on" guess — a Wi-Fi network that's
 * connected but not yet validated by the OS (some hotspot/captive-portal setups never do) is
 * exactly the kind of thing that leaves a download stuck at "waiting to start" with no visible
 * reason, which is what this is for surfacing. */
@Composable
fun rememberIsNetworkAvailable(): Boolean {
    val context = LocalContext.current
    var available by remember { mutableStateOf(hasUsableNetwork(context)) }

    DisposableEffect(Unit) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (connectivityManager == null) {
            onDispose {}
        } else {
            // The default network's own callbacks, judged from the capabilities they carry: when
            // Wi-Fi went off, onLost re-read activeNetwork, which still named the network being
            // lost at that moment, so this stayed "available" and a running card never showed
            // "No internet connection" (seen on the phone). Another network taking over arrives
            // as onAvailable/onCapabilitiesChanged for it.
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    available = connectivityManager.getNetworkCapabilities(network)?.let { usableNetwork(context, it) } ?: false
                }
                override fun onLost(network: Network) { available = false }
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    available = usableNetwork(context, capabilities)
                }
            }
            connectivityManager.registerDefaultNetworkCallback(callback)
            onDispose { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }
    return available
}

/** What the network allows a download right now (DownloadWorker; downloads no longer carry
 * WorkManager's network condition, see DownloadDispatcher.enqueueWorkMany). */
enum class DownloadNetwork {
    /** A usable network: [hasUsableNetwork]. */
    OK,
    /** Nothing connected (or not working yet). Nothing can flow, so a running download can wait. */
    NONE,
    /** Only a metered network while Wi-Fi only is on: anything still downloading would use mobile
     * data. */
    METERED_ONLY,
}

fun downloadNetwork(context: Context): DownloadNetwork {
    if (hasUsableNetwork(context)) return DownloadNetwork.OK
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return DownloadNetwork.OK
    val capabilities = connectivityManager.activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
    val connected = capabilities != null &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    return if (connected && GalleryDlPreferences.isWifiOnly(context)) DownloadNetwork.METERED_ONLY else DownloadNetwork.NONE
}

fun hasUsableNetwork(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return usableNetwork(context, capabilities)
}

/** [hasUsableNetwork]'s rule for a network with [capabilities]. */
private fun usableNetwork(context: Context, capabilities: NetworkCapabilities): Boolean {
    if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
    if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
    if (GalleryDlPreferences.isWifiOnly(context) && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
        return false
    }
    return true
}
