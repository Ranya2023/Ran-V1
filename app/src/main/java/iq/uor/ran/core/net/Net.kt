package iq.uor.ran.core.net

import iq.uor.ran.App
import iq.uor.ran.core.crypto.*
import iq.uor.ran.core.data.*
import iq.uor.ran.feature.call.*
import iq.uor.ran.feature.chat.*
import iq.uor.ran.feature.rooms.*
import iq.uor.ran.feature.safety.*
import iq.uor.ran.feature.setup.*
import iq.uor.ran.ui.*
import iq.uor.ran.R

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

object Net {
    const val DISCOVERY_PORT = 45454   // UDP broadcast "I am here"
    const val SIGNAL_PORT = 45455      // TCP call setup (CALL / RINGING / ACCEPT / REJECT / HANGUP)
    const val AUDIO_PORT = 45456       // UDP voice packets
    const val MAGIC = "RAN"

    /** Remove characters that would break the "|" separated protocol. */
    fun esc(s: String) = s.replace("|", " ").replace("\n", " ").trim().take(40)

    private fun interfaces(): List<NetworkInterface> {
        return try {
            val all = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            Collections.list(all).filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** This phone's Wi-Fi / hotspot IPv4 address. */
    fun localIpv4(): String {
        val candidates = interfaces().flatMap { nif ->
            Collections.list(nif.inetAddresses)
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .map { nif.name to (it.hostAddress ?: "") }
        }
        return (candidates.firstOrNull { it.first.startsWith("wlan") }
            ?: candidates.firstOrNull { it.first.contains("ap") }
            ?: candidates.firstOrNull())?.second ?: ""
    }

    /** Broadcast addresses of every active network (e.g. 10.20.255.255). */
    fun broadcastAddresses(): List<InetAddress> {
        val list = interfaces().flatMap { nif ->
            nif.interfaceAddresses.mapNotNull { it.broadcast }
        }.toMutableList()
        runCatching { list.add(InetAddress.getByName("255.255.255.255")) }
        return list.distinctBy { it.hostAddress }
    }

    fun isValidIp(ip: String): Boolean {
        val p = ip.trim().split(".")
        return p.size == 4 && p.all { s -> s.toIntOrNull()?.let { it in 0..255 } == true }
    }
}
