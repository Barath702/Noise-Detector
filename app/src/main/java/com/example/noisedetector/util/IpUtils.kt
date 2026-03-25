package com.example.noisedetector.util

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import java.net.Inet4Address

object IpUtils {

    fun getLanIpv4(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return null
        val link = cm.getLinkProperties(network) ?: return null
        for (addr in link.linkAddresses) {
            val host = addr.address
            if (!host.isLoopbackAddress && host is Inet4Address) {
                return host.hostAddress?.trim()
            }
        }
        return null
    }

    fun isLikelyLocalIp(host: String): Boolean {
        val h = host.trim()
        if (h == "localhost" || h == "127.0.0.1") return true
        if (h.startsWith("192.168.")) return true
        if (h.startsWith("10.")) return true
        if (Regex("^172\\.(1[6-9]|2\\d|3[0-1])\\.").containsMatchIn(h)) return true
        return false
    }
}
