package com.comfort.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.comfort.app.data.GalleryDlPreferences

/** Whether the device currently has a network that WorkManager's own CONNECTIVITY constraint (see
 * DownloadDispatcher.enqueueWork's Constraints.setRequiredNetworkType) would actually accept right
 * now — a validated, internet-capable network, additionally restricted to unmetered (Wi-Fi/
 * Ethernet) when the Wi-Fi-only setting is on. Mirrors that constraint's own logic (NetworkType
 * .UNMETERED vs .CONNECTED) so this reflects exactly why a queued download either can or can't
 * actually start right now, not a looser "is airplane mode on" guess — a Wi-Fi network that's
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
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { available = hasUsableNetwork(context) }
                override fun onLost(network: Network) { available = hasUsableNetwork(context) }
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    available = hasUsableNetwork(context)
                }
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, callback)
            onDispose { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }
    return available
}

private fun hasUsableNetwork(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
    if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
    if (GalleryDlPreferences.isWifiOnly(context) && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
        return false
    }
    return true
}
