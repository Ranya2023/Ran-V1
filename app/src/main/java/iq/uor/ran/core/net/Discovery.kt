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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Presence on the LAN (UDP 45454), privacy-aware:
 *  - "ann" : only phones that chose "Show me as available" announce themselves every 3 s.
 *  - "who" : to reach a hidden phone you must know its exact 8-digit code or phone number.
 *  - "iam" : the matching phone answers only the asker (and only if its privacy allows).
 */
object Discovery {
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val visible = ConcurrentHashMap<String, Peer>()   // announced
    private val reach = ConcurrentHashMap<String, Peer>()     // answered a query (maybe hidden)
    @Volatile private var recvSocket: DatagramSocket? = null
    @Volatile private var sendSocket: DatagramSocket? = null
    private var conflictUntil = 0L
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch { announceLoop() }
        scope.launch { recvLoop() }
        scope.launch { pruneLoop() }
        scope.launch { contactsPresenceLoop() }
    }

    fun stop() {
        running = false
        scope.cancel()
        runCatching { recvSocket?.close() }
        runCatching { sendSocket?.close() }
    }

    fun announceNow() { scope.launch { runCatching { announce() } } }

    private fun status(): Int {
        val s = Store.settings.value
        return when { Hub.isBusy() -> 1; s.dnd -> 2; else -> 0 }
    }

    private fun send(o: JSONObject, to: List<InetAddress>) {
        val sock = sendSocket ?: DatagramSocket().also { it.broadcast = true; sendSocket = it }
        val data = o.toString().toByteArray(Charsets.UTF_8)
        for (a in to) runCatching { sock.send(DatagramPacket(data, data.size, a, Net.DISCOVERY_PORT)) }
    }

    private fun announce() {
        Hub.myIp.value = Net.localIpv4()
        val s = Store.settings.value
        if (!s.registered || !s.visible) return
        send(
            JSONObject().put("t", "ann").put("c", s.code).put("n", s.name)
                .put("p", if (s.sharePhone) s.phone else "").put("s", status()),
            Net.broadcastAddresses()
        )
    }

    private suspend fun announceLoop() {
        while (scope.isActive) {
            try { announce() } catch (_: Exception) { runCatching { sendSocket?.close() }; sendSocket = null }
            delay(3000)
        }
    }

    /** Find a phone by exact code or phone number. Returns null if nobody answers. */
    suspend fun resolve(query: String, timeoutMs: Long = 2500): Peer? = withContext(Dispatchers.IO) {
        val q = query.trim()
        fun known(): Peer? {
            val all = visible.values + reach.values
            return all.firstOrNull { it.code == q } ?: all.firstOrNull { Store.phoneKey(it.code) == Store.phoneKey(q) }
        }
        known()?.let { if (System.currentTimeMillis() - it.lastSeen < 8000) return@withContext it }
        val me = Store.settings.value
        val msg = JSONObject().put("t", "who").put("q", q).put("c", me.code)
        val until = System.currentTimeMillis() + timeoutMs
        var n = 0
        while (System.currentTimeMillis() < until) {
            if (n % 4 == 0) runCatching { send(msg, Net.broadcastAddresses()) }
            n++
            delay(150)
            known()?.let { if (System.currentTimeMillis() - it.lastSeen < 3000) return@withContext it }
        }
        // last resort: a saved IP (works when broadcast is blocked but unicast is not)
        null
    }

    private suspend fun recvLoop() {
        while (scope.isActive) {
            try {
                val s = DatagramSocket(null)
                s.reuseAddress = true
                s.broadcast = true
                s.bind(InetSocketAddress(Net.DISCOVERY_PORT))
                recvSocket = s
                val buf = ByteArray(1024)
                while (scope.isActive) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val ip = p.address.hostAddress ?: continue
                    runCatching { handle(JSONObject(String(p.data, 0, p.length, Charsets.UTF_8)), p.address, ip) }
                }
            } catch (_: Exception) {
                runCatching { recvSocket?.close() }
                delay(1500)
            }
        }
    }

    private fun handle(o: JSONObject, addr: InetAddress, ip: String) {
        val me = Store.settings.value
        val t = o.optString("t")
        val code = o.optString("c")
        if (code.isEmpty() || !me.registered) return
        if (code == me.code) {
            if (ip != Hub.myIp.value && Hub.myIp.value.isNotEmpty()) {
                conflictUntil = System.currentTimeMillis() + 10_000
                Hub.codeConflict.value = true
            }
            return
        }
        when (t) {
            "ann" -> {
                visible.entries.removeIf { it.value.ip == ip && it.key != code }
                Hub.everSeenPeer.value = System.currentTimeMillis()
                visible[code] = Peer(code, o.optString("n", code), ip, o.optString("p"),
                    System.currentTimeMillis(), o.optInt("s"), true)
                publish()
            }
            "iam" -> {
                Hub.everSeenPeer.value = System.currentTimeMillis()
                reach[code] = Peer(code, o.optString("n", code), ip, o.optString("p"),
                    System.currentTimeMillis(), o.optInt("s"), o.optBoolean("v"))
                Store.contact(code)?.let { if (it.ip != ip) Store.upsertContact(it.copy(ip = ip)) }
                publish()
            }
            "who" -> {
                val q = o.optString("q")
                val match = q == me.code || (Store.phoneKey(q).length >= 7 && Store.phoneKey(q) == Store.phoneKey(me.code))
                if (!match) return
                if (Store.isBlocked(code)) return                                   // stay invisible to blocked people
                if (me.whoCanCall == "contacts" && Store.contact(code) == null) return
                send(
                    JSONObject().put("t", "iam").put("c", me.code).put("n", me.name)
                        .put("p", if (me.sharePhone) me.phone else "").put("s", status()).put("v", me.visible),
                    listOf(addr)
                )
            }
        }
    }

    /** Quietly checks whether saved contacts are reachable so the list can show "online". */
    private suspend fun contactsPresenceLoop() {
        delay(4000)
        while (scope.isActive) {
            val me = Store.settings.value
            if (me.registered) {
                val targets = Store.contacts.value.filter { !it.blocked && !visible.containsKey(it.code) }.take(40)
                for (c in targets) {
                    runCatching {
                        send(JSONObject().put("t", "who").put("q", c.code).put("c", me.code), Net.broadcastAddresses())
                    }
                    delay(120)
                }
            }
            delay(20_000)
        }
    }

    private suspend fun pruneLoop() {
        while (scope.isActive) {
            delay(3000)
            val now = System.currentTimeMillis()
            val a = visible.entries.removeIf { now - it.value.lastSeen > 10_000 }
            val b = reach.entries.removeIf { now - it.value.lastSeen > 50_000 }
            if (a || b) publish()
            if (now > conflictUntil) Hub.codeConflict.value = false
        }
    }

    private fun publish() {
        Hub.peers.value = visible.values.filterNot { Store.isBlocked(it.code) }.sortedBy { it.name.lowercase() }
        val m = HashMap<String, Peer>()
        reach.values.forEach { m[it.code] = it }
        visible.values.forEach { m[it.code] = it }
        Hub.online.value = m
    }
}
