package app.libre.helpers

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService

object NetworkHelper {
    /**
     * Detect whether network is available
     */
    fun isNetworkAvailable(context: Context): Boolean {
        // In case we are using a VPN, we return true since we might be using reverse tethering
        val connectivityManager = context.getSystemService<ConnectivityManager>() ?: return false

        val activeNetwork = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /**
     * Detect whether the current network is metered
     * @param context Context of the application
     * @return whether the network is metered or not
     */
    fun isNetworkMetered(context: Context): Boolean {
        val connectivityManager = context.getSystemService<ConnectivityManager>() ?: return false
        val activeNetwork = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
        
        // In case we are using a VPN, it should default to not metered
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
            return false
        }
        
        return connectivityManager.isActiveNetworkMetered
    }
}
