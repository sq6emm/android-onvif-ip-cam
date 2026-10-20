package pl.arhea.onvifipcam.util

import java.net.Inet4Address
import java.net.NetworkInterface

object Net {
    /** Non-loopback IPv4 addresses of interfaces that are up (Wi-Fi, Ethernet, hotspot). */
    fun localIpv4(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("eth")) 1 else 2 }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .map { it.hostAddress!! }
    }.getOrDefault(emptyList())
}
