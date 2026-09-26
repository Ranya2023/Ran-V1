package iq.uor.ran.feature.rooms

import iq.uor.ran.App
import iq.uor.ran.core.crypto.*
import iq.uor.ran.core.data.*
import iq.uor.ran.core.net.*
import iq.uor.ran.feature.call.*
import iq.uor.ran.feature.chat.*
import iq.uor.ran.feature.safety.*
import iq.uor.ran.feature.setup.*
import iq.uor.ran.ui.*
import iq.uor.ran.R

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

data class Room(val id: String, val name: String, val key: String)

data class RoomMember(val session: Int, val code: String, val name: String, val ip: String,
                      val lastSeen: Long, val lastAudio: Long)

/**
 * Voice rooms (walkie-talkie / open mic) on local Wi-Fi.
 * Anyone with the room QR (id + 256-bit key) can join. Voice is AES-256-GCM encrypted with the room key.
 * Members find each other with encrypted "hello" broadcasts; voice is sent directly to each member.
 * 16 kHz µ-law ≈ 128 kbit/s per speaker. Works for ~10–15 people on one Wi-Fi.
 */
object Rooms {
    private const val PORT = 45457
    private const val RATE = 16000
    private const val FRAME = 320                 // samples per 20 ms
    val saved = MutableStateFlow<List<Room>>(emptyList())
    val active = MutableStateFlow<Room?>(null)
    val members = MutableStateFlow<List<RoomMember>>(emptyList())
    val talking = MutableStateFlow(false)
    val openMic = MutableStateFlow(false)
    val speaker = MutableStateFlow(true)

    private val running = AtomicBoolean(false)
    private val map = ConcurrentHashMap<Int, RoomMember>()
    private val lastSeq = ConcurrentHashMap<Int, Long>()
    private val players = ConcurrentHashMap<Int, AudioTrack>()
    @Volatile private var socket: DatagramSocket? = null
    private var session = 0
    private var seq = 0L
    private lateinit var key: SecretKeySpec
    private lateinit var tag: ByteArray
    private var app: Context? = null
    private var oldMode = AudioManager.MODE_NORMAL

    /* ---------------- saved rooms ---------------- */

    fun load() {
        saved.value = runCatching {
            val a = JSONArray(Store.prefs().getString("rooms", "[]"))
            (0 until a.length()).map { val o = a.getJSONObject(it); Room(o.getString("id"), o.optString("n"), o.getString("k")) }
        }.getOrDefault(emptyList())
    }

    private fun persist(l: List<Room>) {
        saved.value = l
        val a = JSONArray(); l.forEach { a.put(json(it)) }
        Store.prefs().edit().putString("rooms", a.toString()).apply()
    }

    fun json(r: Room): JSONObject = JSONObject().put("id", r.id).put("n", r.name).put("k", r.key)
    fun fromJson(s: String): Room? = runCatching { JSONObject(s).let { Room(it.getString("id"), it.optString("n"), it.getString("k")) } }.getOrNull()

    fun create(name: String): Room {
        val r = Room(Crypto.randomBytes(6).joinToString("") { "%02x".format(it) }, name.trim().take(40), b64(Crypto.randomBytes(32)))
        add(r); return r
    }

    fun add(r: Room) = persist(saved.value.filterNot { it.id == r.id } + r)
    fun remove(id: String) { if (active.value?.id == id) leave(); persist(saved.value.filterNot { it.id == id }) }

    /* ---------------- joining ---------------- */

    fun join(ctx: Context, r: Room) {
        if (active.value?.id == r.id) return
        leave()
        app = ctx.applicationContext
        key = SecretKeySpec(unb64(r.key), "AES")
        tag = MessageDigest.getInstance("SHA-256").digest(r.id.toByteArray()).copyOf(4)
        session = java.security.SecureRandom().nextInt()
        seq = 0
        map.clear(); lastSeq.clear()
        active.value = r
        talking.value = false
        running.set(true)
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        oldMode = am.mode
        runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
        route(speaker.value)
        CallService.instance?.roomMode(true)
        thread(name = "room-rx") { rxLoop() }
        thread(name = "room-tx") { txLoop() }
        thread(name = "room-hello") { helloLoop() }
    }

    fun leave() {
        if (!running.compareAndSet(true, false)) return
        active.value = null
        talking.value = false
        runCatching { socket?.close() }
        players.values.forEach { runCatching { it.stop(); it.release() } }
        players.clear(); map.clear()
        members.value = emptyList()
        app?.let { ctx ->
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            route(false)
            runCatching { am.mode = oldMode }
        }
        CallService.instance?.roomMode(false)
    }

    fun setSpeaker(on: Boolean) { speaker.value = on; if (running.get()) route(on) }

    private fun route(on: Boolean) {
        val ctx = app ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (on) am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { am.setCommunicationDevice(it) }
                else am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = on
            }
        } catch (_: Exception) { }
    }

    /* ---------------- packets ---------------- */
    // [0x55][type][tag 4][session 4][seq 8][AES-GCM ciphertext]   type 0 = hello, 1 = voice

    private fun packet(type: Int, plain: ByteArray): ByteArray {
        val s = ++seq
        val nonce = ByteBuffer.allocate(12).putInt(session).putLong(s).array()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        c.updateAAD(byteArrayOf(type.toByte()))
        val ct = c.doFinal(plain)
        return ByteBuffer.allocate(18 + ct.size).put(0x55.toByte()).put(type.toByte()).put(tag).putInt(session).putLong(s).put(ct).array()
    }

    private fun send(data: ByteArray, to: List<InetAddress>) {
        val s = socket ?: return
        for (a in to) runCatching { s.send(DatagramPacket(data, data.size, a, PORT)) }
    }

    private fun helloLoop() {
        while (running.get()) {
            runCatching {
                val me = Store.settings.value
                val hello = JSONObject().put("c", me.code).put("n", me.name).toString().toByteArray()
                val p = packet(0, hello)
                send(p, Net.broadcastAddresses())
                send(p, map.values.mapNotNull { runCatching { InetAddress.getByName(it.ip) }.getOrNull() })
                val now = System.currentTimeMillis()
                map.entries.removeIf { now - it.value.lastSeen > 8000 }
                publish()
            }
            Thread.sleep(2000)
        }
    }

    private fun publish() { members.value = map.values.sortedBy { it.name.lowercase() } }

    @SuppressLint("MissingPermission")
    private fun txLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val ctx = app ?: return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 8))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
        val fx = mutableListOf<AudioEffect>()
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.let { it.setEnabled(true); fx.add(it) }
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.let { it.setEnabled(true); fx.add(it) }
        val pcm = ShortArray(FRAME)
        val ulaw = ByteArray(FRAME)
        try {
            rec.startRecording()
            while (running.get()) {
                var off = 0
                while (off < FRAME && running.get()) {
                    val n = rec.read(pcm, off, FRAME - off)
                    if (n <= 0) { Thread.sleep(5); break }
                    off += n
                }
                if (!(talking.value || openMic.value) || map.isEmpty()) continue
                for (i in 0 until FRAME) ulaw[i] = encode(pcm[i])
                val p = packet(1, ulaw)
                send(p, map.values.mapNotNull { runCatching { InetAddress.getByName(it.ip) }.getOrNull() })
            }
        } catch (_: Exception) {
        } finally {
            fx.forEach { runCatching { it.release() } }
            runCatching { rec.stop() }; rec.release()
        }
    }

    private fun rxLoop() {
        try {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.broadcast = true
            s.bind(InetSocketAddress(PORT))
            s.soTimeout = 1000
            socket = s
            val buf = ByteArray(2048)
            val pcm = ShortArray(FRAME)
            while (running.get()) {
                val dp = DatagramPacket(buf, buf.size)
                try { s.receive(dp) } catch (e: java.net.SocketTimeoutException) { continue }
                val n = dp.length
                if (n < 18 + 16 || buf[0] != 0x55.toByte()) continue
                if (buf[2] != tag[0] || buf[3] != tag[1] || buf[4] != tag[2] || buf[5] != tag[3]) continue
                val bb = ByteBuffer.wrap(buf, 6, 12)
                val sess = bb.int; val sq = bb.long
                if (sess == session) continue
                val last = lastSeq[sess] ?: -1L
                if (sq <= last) continue
                val type = buf[1].toInt()
                val plain = try {
                    val c = Cipher.getInstance("AES/GCM/NoPadding")
                    c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ByteBuffer.allocate(12).putInt(sess).putLong(sq).array()))
                    c.updateAAD(byteArrayOf(type.toByte()))
                    c.doFinal(buf, 18, n - 18)
                } catch (e: Exception) { continue }
                lastSeq[sess] = sq
                val ip = dp.address.hostAddress ?: continue
                val now = System.currentTimeMillis()
                if (type == 0) {
                    val o = JSONObject(String(plain))
                    val code = o.optString("c")
                    if (!Store.allowed(code)) continue
                    val old = map[sess]
                    map[sess] = RoomMember(sess, code, Store.contact(code)?.name ?: o.optString("n", code), ip, now, old?.lastAudio ?: 0)
                    if (old == null) publish()
                } else if (type == 1) {
                    val m = map[sess] ?: continue
                    map[sess] = m.copy(lastAudio = now, lastSeen = now)
                    for (i in 0 until minOf(FRAME, plain.size)) pcm[i] = decode(plain[i])
                    player(sess).write(pcm, 0, minOf(FRAME, plain.size))
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun player(sess: Int): AudioTrack = players.getOrPut(sess) {
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, RATE / 2))   // ~250 ms jitter buffer
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { it.play() }
    }

    /* ---------------- G.711 µ-law ---------------- */

    private fun encode(sample: Short): Byte {
        var pcm = sample.toInt()
        val sign = if (pcm < 0) { pcm = -pcm; 0x80 } else 0
        if (pcm > 32635) pcm = 32635
        pcm += 0x84
        var exp = 7
        var mask = 0x4000
        while (exp > 0 && (pcm and mask) == 0) { exp--; mask = mask shr 1 }
        val mant = (pcm shr (exp + 3)) and 0x0F
        return ((sign or (exp shl 4) or mant).inv() and 0xFF).toByte()
    }

    private fun decode(b: Byte): Short {
        val u = b.toInt().inv() and 0xFF
        val sign = u and 0x80
        val exp = (u shr 4) and 0x07
        val mant = u and 0x0F
        val mag = (((mant shl 3) + 0x84) shl exp) - 0x84
        return (if (sign != 0) -mag else mag).toShort()
    }

    fun isTalking(session: Int) = map[session]?.let { System.currentTimeMillis() - it.lastAudio < 400 } == true
}
