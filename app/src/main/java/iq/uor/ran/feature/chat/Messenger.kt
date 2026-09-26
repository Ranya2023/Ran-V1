package iq.uor.ran.feature.chat

import iq.uor.ran.App
import iq.uor.ran.core.crypto.*
import iq.uor.ran.core.data.*
import iq.uor.ran.core.net.*
import iq.uor.ran.feature.call.*
import iq.uor.ran.feature.rooms.*
import iq.uor.ran.feature.safety.*
import iq.uor.ran.feature.setup.*
import iq.uor.ran.ui.*
import iq.uor.ran.R

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class Msg(
    val id: String,
    val peer: String,
    val peerName: String,
    val out: Boolean,
    val kind: String,        // text, image, video, file
    val body: String,
    val path: String,
    val fileName: String,
    val mime: String,
    val size: Long,
    val time: Long,
    val status: Int,         // 0 sending, 1 sent, 2 failed, 3 received
    val read: Boolean
)

data class Conversation(val peer: String, val name: String, val last: Msg, val unread: Int)

private class Db(ctx: Context) : SQLiteOpenHelper(ctx, "messages.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE m (id TEXT PRIMARY KEY, peer TEXT, pname TEXT, out INTEGER, kind TEXT, body TEXT, " +
                    "path TEXT, fname TEXT, mime TEXT, size INTEGER, time INTEGER, status INTEGER, rd INTEGER)"
        )
        db.execSQL("CREATE INDEX mp ON m(peer, time)")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
}

/** Encrypted text / photo / video / file messages over the LAN, with automatic retry. */
object Messenger {
    private lateinit var db: Db
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    const val MAX_FILE = 1024L * 1024 * 1024   // 1 GB
    private const val CHUNK = 60 * 1024
    const val CH_MSG = "messages"

    fun isFile(kind: String) = kind == "image" || kind == "video" || kind == "audio" || kind == "file"

    fun init(ctx: Context) {
        if (::db.isInitialized) return
        app = ctx.applicationContext
        db = Db(app)
        scope.launch {
            delay(5000)
            while (true) { runCatching { retryPending() }; delay(25_000) }
        }
    }

    fun mediaDir(): File = File(app.filesDir, "media").apply { mkdirs() }

    /* ---------------- queries ---------------- */

    private fun row(c: android.database.Cursor) = Msg(
        c.getString(0), c.getString(1), c.getString(2), c.getInt(3) == 1, c.getString(4), c.getString(5),
        c.getString(6), c.getString(7), c.getString(8), c.getLong(9), c.getLong(10), c.getInt(11), c.getInt(12) == 1
    )

    private const val COLS = "id,peer,pname,out,kind,body,path,fname,mime,size,time,status,rd"

    fun messages(peer: String): List<Msg> =
        db.readableDatabase.rawQuery("SELECT $COLS FROM m WHERE peer=? ORDER BY time ASC", arrayOf(peer)).use { c ->
            val l = ArrayList<Msg>(); while (c.moveToNext()) l.add(row(c)); l
        }

    fun conversations(): List<Conversation> {
        val out = ArrayList<Conversation>()
        db.readableDatabase.rawQuery(
            "SELECT $COLS FROM m WHERE time IN (SELECT MAX(time) FROM m GROUP BY peer) ORDER BY time DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                val m = row(c)
                if (out.any { it.peer == m.peer }) continue
                val unread = db.readableDatabase.rawQuery(
                    "SELECT COUNT(*) FROM m WHERE peer=? AND out=0 AND rd=0", arrayOf(m.peer)
                ).use { u -> if (u.moveToFirst()) u.getInt(0) else 0 }
                val name = Store.contact(m.peer)?.name ?: m.peerName
                out.add(Conversation(m.peer, name, m, unread))
            }
        }
        return out
    }

    fun totalUnread(): Int = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM m WHERE out=0 AND rd=0", null)
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun markRead(peer: String) {
        db.writableDatabase.execSQL("UPDATE m SET rd=1 WHERE peer=?", arrayOf(peer))
        runCatching { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(peer.hashCode()) }
        Hub.bumpChats()
    }

    fun deleteChat(peer: String) {
        messages(peer).forEach { if (it.path.isNotEmpty()) runCatching { File(it.path).delete() } }
        db.writableDatabase.delete("m", "peer=?", arrayOf(peer))
        Hub.bumpChats()
    }

    private fun insert(m: Msg) {
        val v = ContentValues().apply {
            put("id", m.id); put("peer", m.peer); put("pname", m.peerName); put("out", if (m.out) 1 else 0)
            put("kind", m.kind); put("body", m.body); put("path", m.path); put("fname", m.fileName)
            put("mime", m.mime); put("size", m.size); put("time", m.time); put("status", m.status)
            put("rd", if (m.read) 1 else 0)
        }
        db.writableDatabase.insertWithOnConflict("m", null, v, SQLiteDatabase.CONFLICT_IGNORE)
        Hub.bumpChats()
    }

    private fun setStatus(id: String, st: Int) {
        db.writableDatabase.execSQL("UPDATE m SET status=? WHERE id=?", arrayOf<Any>(st, id))
        Hub.bumpChats()
    }

    private fun pending(): List<Msg> =
        db.readableDatabase.rawQuery("SELECT $COLS FROM m WHERE out=1 AND status IN (0,2) ORDER BY time ASC", null)
            .use { c -> val l = ArrayList<Msg>(); while (c.moveToNext()) l.add(row(c)); l }

    /* ---------------- sending ---------------- */

    fun sendText(peer: String, peerName: String, text: String) {
        if (text.isBlank()) return
        sendControl(peer, peerName, "text", text.trim())
    }

    /** Text-like messages: text, rollcall, checkin, room invites. */
    fun sendControl(peer: String, peerName: String, kind: String, body: String) {
        val m = Msg(UUID.randomUUID().toString(), peer, peerName, true, kind, body, "", "", "",
            0, System.currentTimeMillis(), 0, true)
        insert(m)
        scope.launch { deliver(m) }
    }

    fun findById(id: String): Msg? =
        db.readableDatabase.rawQuery("SELECT $COLS FROM m WHERE id=?", arrayOf(id)).use { c -> if (c.moveToFirst()) row(c) else null }

    /** All messages of one kind (used by the safety dashboard). */
    fun byKind(kind: String): List<Msg> =
        db.readableDatabase.rawQuery("SELECT $COLS FROM m WHERE kind=? ORDER BY time ASC", arrayOf(kind)).use { c ->
            val l = ArrayList<Msg>(); while (c.moveToNext()) l.add(row(c)); l
        }

    /** Copies the picked file into app storage, then sends it. Returns an error key or null. */
    fun sendFile(peer: String, peerName: String, uri: Uri, caption: String = ""): String? {
        val cr = app.contentResolver
        var name = "file"
        var size = -1L
        runCatching {
            cr.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni) ?: name
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        if (size > MAX_FILE) return "file_too_big"
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val id = UUID.randomUUID().toString()
        scope.launch {
            try {
                val dest = File(mediaDir(), id + "_" + safe(name))
                cr.openInputStream(uri)?.use { i -> FileOutputStream(dest).use { o -> i.copyTo(o, 64 * 1024) } }
                val m = Msg(id, peer, peerName, true, kindOf(mime), caption, dest.absolutePath, name, mime,
                    dest.length(), System.currentTimeMillis(), 0, true)
                insert(m)
                deliver(m)
            } catch (_: Exception) { }
        }
        return null
    }

    private fun safe(n: String) = n.replace(Regex("[^A-Za-z0-9._\\-\\u0600-\\u06FF]"), "_").takeLast(80)

    fun kindOf(mime: String) = when {
        mime.startsWith("image/") -> "image"
        mime.startsWith("video/") -> "video"
        mime.startsWith("audio/") -> "audio"
        else -> "file"
    }

    fun retryNow(id: String) {
        scope.launch { pending().firstOrNull { it.id == id }?.let { deliver(it) } }
    }

    private suspend fun retryPending() {
        val list = pending()
        if (list.isEmpty()) return
        list.groupBy { it.peer }.forEach { (_, msgs) -> for (m in msgs) if (!deliver(m)) break }
    }

    private suspend fun findIp(code: String): String? =
        Hub.online.value[code]?.ip ?: Discovery.resolve(code, 1500)?.ip ?: Store.contact(code)?.ip?.ifEmpty { null }

    private suspend fun deliver(m: Msg): Boolean {
        if (!inFlight.add(m.id)) return false
        try {
            if (deliverLan(m)) { setStatus(m.id, 1); return true }
            setStatus(m.id, 2); return false
        } finally {
            inFlight.remove(m.id)
        }
    }

    private suspend fun deliverLan(m: Msg): Boolean {
        try {
            val ip = findIp(m.peer) ?: return false
            val s = Socket()
            s.connect(InetSocketAddress(ip, Net.SIGNAL_PORT), 3000)
            s.soTimeout = 30_000
            val ch = SecureChannel(s)
            try {
                val err = ch.connectHandshake("msg")
                if (err != null) return false
                ch.remote?.let { r -> learn(r, ip) }
                ch.sendJson(
                    JSONObject().put("id", m.id).put("kind", m.kind).put("body", m.body)
                        .put("fname", m.fileName).put("mime", m.mime).put("size", m.size).put("time", m.time)
                )
                if (isFile(m.kind)) {
                    File(m.path).inputStream().use { i ->
                        val buf = ByteArray(CHUNK)
                        while (true) {
                            val n = i.read(buf); if (n <= 0) break
                            ch.send(if (n == buf.size) buf else buf.copyOf(n))
                        }
                    }
                }
                val ack = ch.recvJson()
                return ack?.optString("ok") == m.id
            } finally { ch.close() }
        } catch (e: Exception) {
            return false
        }
    }

    /** Save what we learned about a peer (TOFU: first key is remembered). */
    fun learn(r: Remote, ip: String) {
        val c = Store.contact(r.code) ?: return
        Store.upsertContact(c.copy(ip = ip, key = if (c.key.isBlank()) r.key else c.key,
            phone = c.phone.ifBlank { r.phone }))
        if (r.avatarHash.isNotEmpty() && r.avatarHash != c.avatarHash) scope.launch { fetchAvatar(r.code, ip, r.avatarHash) }
        else if (r.avatarHash.isEmpty() && c.avatar.isNotEmpty()) Store.upsertContact(Store.contact(r.code)!!.copy(avatar = "", avatarHash = ""))
    }

    /** Downloads a contact's profile picture over the same encrypted Wi-Fi channel. */
    private suspend fun fetchAvatar(code: String, ip: String, hash: String) {
        val s = connect(ip) ?: return
        val ch = SecureChannel(s)
        try {
            if (ch.connectHandshake("profile") != null) return
            val data = ch.recv() ?: return
            if (data.isEmpty() || data.size > 2 * 1024 * 1024) return
            val path = Profile.saveContactAvatar(app, code, data) ?: return
            Store.contact(code)?.let { Store.upsertContact(it.copy(avatar = path, avatarHash = hash)) }
        } catch (e: Exception) {
        } finally { ch.close() }
    }

    private suspend fun connect(ip: String): java.net.Socket? = try {
        java.net.Socket().apply { connect(java.net.InetSocketAddress(ip, Net.SIGNAL_PORT), 2500); soTimeout = 8000 }
    } catch (e: Exception) { null }

    /* ---------------- receiving (called by CallService) ---------------- */

    fun receive(ch: SecureChannel, r: Remote) {
        val ip = ch.socket.inetAddress?.hostAddress ?: ""
        learn(r, ip)
        while (true) {
            val meta = ch.recvJson() ?: break
            val id = meta.getString("id")
            val kind = meta.optString("kind", "text")
            val size = meta.optLong("size")
            var path = ""
            if (isFile(kind)) {
                if (size < 0 || size > MAX_FILE) throw IllegalStateException("size")
                val f = File(mediaDir(), id + "_" + safe(meta.optString("fname", "file")))
                FileOutputStream(f).use { o ->
                    var got = 0L
                    while (got < size) {
                        val chunk = ch.recv() ?: throw IllegalStateException("eof")
                        o.write(chunk); got += chunk.size
                    }
                }
                path = f.absolutePath
            }
            val m = Msg(id, r.code, r.name, false, kind, meta.optString("body"), path,
                meta.optString("fname"), meta.optString("mime"), size, System.currentTimeMillis(), 3,
                Hub.openChat == r.code)
            val fresh = findById(id) == null
            insert(m)
            if (fresh) MediaSaver.autoSave(app, m)
            ch.sendJson(JSONObject().put("ok", id))
            if (fresh) onIncoming(m)
        }
    }

    private fun onIncoming(m: Msg) {
        when (m.kind) {
            "rollcall" -> Safety.onRollCall(app, m)
            "checkin" -> Safety.onCheckin(app, m)
            else -> if (Hub.openChat != m.peer) notifyMessage(m)
        }
    }

    fun preview(m: Msg): String = when (m.kind) {
        "text" -> m.body
        "image" -> "📷 " + L.t("photo")
        "video" -> "🎬 " + L.t("video")
        "rollcall" -> "📋 " + L.t("rollcall")
        "checkin" -> if (Safety.parse(m.body).optString("status") == "help") "🆘 " + L.t("need_help") else "✅ " + L.t("im_safe")
        "room" -> "🎙 " + L.t("room_invite")
        else -> "📎 " + m.fileName
    }

    private fun notifyMessage(m: Msg) {
        val name = Store.contact(m.peer)?.name ?: m.peerName
        val text = preview(m)
        val open = PendingIntent.getActivity(
            app, m.peer.hashCode(),
            Intent(app, ChatActivity::class.java).putExtra("code", m.peer).putExtra("name", name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(app, CH_MSG)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(name)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(m.peer.hashCode(), n) }
    }
}
