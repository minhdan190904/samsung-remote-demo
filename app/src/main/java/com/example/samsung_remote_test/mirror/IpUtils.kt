package com.example.samsung_remote_test.mirror

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

object IpUtils {
    fun getLocalIpv4(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        val lp = cm.getLinkProperties(network) ?: return null
        val ok = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!ok) return null
        return lp.linkAddresses
            .map { it.address }
            .firstOrNull { it is Inet4Address }
            ?.hostAddress
    }
}