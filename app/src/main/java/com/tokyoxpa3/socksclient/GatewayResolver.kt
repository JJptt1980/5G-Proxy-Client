package com.tokyoxpa3.socksclient

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.net.Inet4Address

object GatewayResolver {
    private const val TAG = "GatewayResolver"
    private val KEYWORDS = setOf("gateway", "auto")

    fun isAuto(host: String): Boolean = host.trim().lowercase() in KEYWORDS

    fun wifiGatewayV4(ctx: Context): String? {
        val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return null
        return try {
            @Suppress("DEPRECATION")
            val networks = cm.allNetworks.toList()
            for (net in networks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == false) continue
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == false) continue
                val lp = cm.getLinkProperties(net) ?: continue
                for (route in lp.routes) {
                    val gw = route.gateway
                    if (route.isDefaultRoute && gw is Inet4Address && gw.isAnyLocalAddress == false) {
                        Log.i(TAG, "Wi-Fi gateway: ${gw.hostAddress}")
                        return gw.hostAddress
                    }
                }
            }
            Log.w(TAG, "no Wi-Fi IPv4 default gateway found")
            null
        } catch (e: Exception) {
            Log.w(TAG, "read Wi-Fi gateway failed: ${e.message}")
            null
        }
    }
}
