package iq.uor.ran.feature.safety

import iq.uor.ran.App
import iq.uor.ran.core.crypto.*
import iq.uor.ran.core.data.*
import iq.uor.ran.core.net.*
import iq.uor.ran.feature.call.*
import iq.uor.ran.feature.chat.*
import iq.uor.ran.feature.rooms.*
import iq.uor.ran.feature.setup.*
import iq.uor.ran.ui.*
import iq.uor.ran.R

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class RollCall(val id: String, val title: String, val time: Long, val members: List<String>)

/**
 * "I'm safe" check-ins and coordinator roll calls.
 * Everything travels as normal encrypted messages, so it works on local Wi-Fi AND over the internet,
 * and waits in the queue until the person is reachable.
 */
object Safety {
    const val CH_SOS = "sos"
    const val CH_SAFETY = "safety"
    const val ACTION = "iq.uor.ran.CHECKIN"
    val rollCalls = MutableStateFlow<List<RollCall>>(emptyList())

    fun parse(body: String): JSONObject = runCatching { JSONObject(body) }.getOrDefault(JSONObject())

    fun load() {
        rollCalls.value = runCatching {
            val a = JSONArray(Store.prefs().getString("rollcalls", "[]"))
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val m = o.optJSONArray("m") ?: JSONArray()
                RollCall(o.getString("id"), o.optString("t"), o.optLong("time"), (0 until m.length()).map { m.getString(it) })
            }
        }.getOrDefault(emptyList())
    }

    private fun persist(list: List<RollCall>) {
        rollCalls.value = list
        val a = JSONArray()
        list.forEach { r -> a.put(JSONObject().put("id", r.id).put("t", r.title).put("time", r.time).put("m", JSONArray(r.members))) }
        Store.prefs().edit().putString("rollcalls", a.toString()).apply()
    }

    /** Coordinator: ask every team member "Are you safe?". */
    fun startRollCall(title: String): RollCall? {
        val team = Store.team()
        if (team.isEmpty()) return null
        val rc = RollCall(UUID.randomUUID().toString().take(12), title, System.currentTimeMillis(), team.map { it.code })
        val body = JSONObject().put("rid", rc.id).put("title", title).toString()
        team.forEach { Messenger.sendControl(it.code, it.name, "rollcall", body) }
        persist((listOf(rc) + rollCalls.value).take(20))
        return rc
    }

    fun deleteRollCall(id: String) = persist(rollCalls.value.filterNot { it.id == id })

    /** Latest check-in received from each person (optionally for one roll call). */
    fun responses(rid: String? = null): Map<String, Msg> {
        val out = HashMap<String, Msg>()
        Messenger.byKind("checkin").filter { !it.out && (rid == null || parse(it.body).optString("rid") == rid) }
            .forEach { out[it.peer] = it }
        return out
    }

    /** Send my status. To one person (answering a roll call) or to my whole team. */
    fun checkIn(ctx: Context, status: String, note: String = "", rid: String = "", to: List<Contact>? = null): Int {
        val targets = to ?: Store.team()
        val body = JSONObject().put("status", status).put("note", note).put("rid", rid)
        if (Store.settings.value.shareLocation) location(ctx)?.let {
            body.put("lat", it.latitude).put("lon", it.longitude).put("acc", it.accuracy.toInt())
        }
        targets.forEach { Messenger.sendControl(it.code, it.name, "checkin", body.toString()) }
        return targets.size
    }

    @SuppressLint("MissingPermission")
    fun location(ctx: Context): Location? {
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    fun mapLink(o: JSONObject): String? =
        if (o.has("lat")) "geo:${o.getDouble("lat")},${o.getDouble("lon")}?q=${o.getDouble("lat")},${o.getDouble("lon")}" else null

    /* ---------------- notifications ---------------- */

    private fun nm(ctx: Context) = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun action(ctx: Context, req: Int, status: String, m: Msg, rid: String): PendingIntent =
        PendingIntent.getBroadcast(ctx, req,
            Intent(ctx, SafetyReceiver::class.java).setAction(ACTION).putExtra("status", status)
                .putExtra("peer", m.peer).putExtra("name", m.peerName).putExtra("rid", rid).putExtra("nid", m.id.hashCode()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun openChat(ctx: Context, m: Msg): PendingIntent =
        PendingIntent.getActivity(ctx, m.peer.hashCode(),
            Intent(ctx, ChatActivity::class.java).putExtra("code", m.peer).putExtra("name", m.peerName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun onRollCall(ctx: Context, m: Msg) {
        val o = parse(m.body)
        val rid = o.optString("rid")
        val name = Store.contact(m.peer)?.name ?: m.peerName
        val n = NotificationCompat.Builder(ctx, CH_SAFETY)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle("📋 " + L.t("rollcall") + " – " + name)
            .setContentText(o.optString("title").ifBlank { L.t("are_you_safe") })
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openChat(ctx, m))
            .addAction(0, "✅ " + L.t("im_safe"), action(ctx, m.id.hashCode(), "safe", m, rid))
            .addAction(0, "🆘 " + L.t("need_help"), action(ctx, m.id.hashCode() + 1, "help", m, rid))
            .build()
        runCatching { nm(ctx).notify(m.id.hashCode(), n) }
    }

    fun onCheckin(ctx: Context, m: Msg) {
        val o = parse(m.body)
        val help = o.optString("status") == "help"
        val name = Store.contact(m.peer)?.name ?: m.peerName
        val text = (if (help) "🆘 " + L.t("need_help") else "✅ " + L.t("im_safe")) +
                (if (o.optString("note").isNotBlank()) " – " + o.optString("note") else "") +
                (if (o.has("lat")) " 📍" else "")
        val n = NotificationCompat.Builder(ctx, if (help) CH_SOS else CH_SAFETY)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(name)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(if (help) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (help) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openChat(ctx, m))
            .build()
        runCatching { nm(ctx).notify(m.id.hashCode(), n) }
    }
}

/** Handles the "I'm safe" / "Need help" buttons on a roll-call notification. */
class SafetyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        Store.init(ctx); Identity.init(); Messenger.init(ctx)
        val peer = i.getStringExtra("peer") ?: return
        val c = Store.contact(peer) ?: Contact(peer, i.getStringExtra("name") ?: peer)
        Safety.checkIn(ctx, i.getStringExtra("status") ?: "safe", rid = i.getStringExtra("rid") ?: "", to = listOf(c))
        runCatching { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(i.getIntExtra("nid", 0)) }
    }
}
