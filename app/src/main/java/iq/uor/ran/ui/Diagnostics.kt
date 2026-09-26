package iq.uor.ran.ui

import iq.uor.ran.core.data.*
import iq.uor.ran.core.net.*
import iq.uor.ran.feature.call.*
import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

/** One thing that is wrong, in plain words, with a button that fixes it. */
data class Issue(val titleKey: String, val bodyKey: String, val fix: String, val severity: Int)   // 2 = blocking, 1 = warning

/**
 * Tells the user WHY calls or messages are not working, instead of failing silently:
 * no Wi-Fi, Wi-Fi without a connection, a network that blocks phone-to-phone traffic,
 * missing permissions, paused service, battery restrictions.
 */
object NetworkDoctor {

    fun wifiConnected(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun onVpn(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    fun check(ctx: Context): List<Issue> {
        val out = mutableListOf<Issue>()
        val s = Store.settings.value
        val ip = Hub.myIp.value

        if (s.paused) out += Issue("diag_paused", "diag_paused_body", "resume", 2)
        if (!wifiConnected(ctx) && ip.isEmpty())
            out += Issue("diag_no_wifi", "diag_no_wifi_body", "wifi", 2)
        else if (ip.isEmpty())
            out += Issue("diag_no_ip", "diag_no_ip_body", "wifi", 2)
        if (onVpn(ctx)) out += Issue("diag_vpn", "diag_vpn_body", "wifi", 1)
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            out += Issue("diag_mic", "diag_mic_body", "app", 2)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.areNotificationsEnabled()) out += Issue("diag_notif", "diag_notif_body", "notif", 2)
        if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent())
            out += Issue("diag_fullscreen", "diag_fullscreen_body", "fullscreen", 1)
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(ctx.packageName))
            out += Issue("diag_battery", "diag_battery_body", "battery", 1)
        if (!Hub.serviceRunning.value && !s.paused) out += Issue("diag_service", "diag_service_body", "resume", 2)
        // connected to Wi-Fi, nobody ever seen → the network probably blocks device-to-device traffic
        if (out.none { it.severity == 2 } && ip.isNotEmpty() && Hub.peers.value.isEmpty() && Hub.everSeenPeer.value == 0L &&
            System.currentTimeMillis() - Hub.startedAt > 60_000)
            out += Issue("diag_isolation", "diag_isolation_body", "help", 1)
        return out
    }

    /** Short line for the home screen: green when everything is fine. */
    fun summary(ctx: Context): Issue? = check(ctx).minByOrNull { -it.severity }
}
