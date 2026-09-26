package iq.uor.ran.core.crypto

import iq.uor.ran.App
import iq.uor.ran.core.data.*
import iq.uor.ran.core.net.*
import iq.uor.ran.feature.call.*
import iq.uor.ran.feature.chat.*
import iq.uor.ran.feature.rooms.*
import iq.uor.ran.feature.safety.*
import iq.uor.ran.feature.setup.*
import iq.uor.ran.ui.*
import iq.uor.ran.R

import android.util.Base64
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

/**
 * Long-term identity of this phone (ECDSA P-256).
 * The public key travels in QR codes so contacts can be verified face to face.
 */
object Identity {
    private lateinit var kp: KeyPair

    fun init() {
        if (::kp.isInitialized) return
        val sp = Store.prefs()
        val pub = sp.getString("id_pub", null)
        val prv = sp.getString("id_prv", null)
        kp = if (pub != null && prv != null) {
            val kf = KeyFactory.getInstance("EC")
            KeyPair(kf.generatePublic(X509EncodedKeySpec(unb64(pub))), kf.generatePrivate(PKCS8EncodedKeySpec(unb64(prv))))
        } else {
            val g = KeyPairGenerator.getInstance("EC")
            g.initialize(ECGenParameterSpec("secp256r1"))
            g.generateKeyPair().also {
                sp.edit().putString("id_pub", b64(it.public.encoded)).putString("id_prv", b64(it.private.encoded)).apply()
            }
        }
    }

    val publicBytes: ByteArray get() = kp.public.encoded
    val publicB64: String get() = b64(kp.public.encoded)

    /** Static ECDH with another phone's identity key (used for internet messages). */
    fun agree(theirPub: ByteArray): ByteArray = Crypto.ecdh(kp.private, theirPub)

    fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(kp.private); update(data); sign() }

    fun verify(pub: ByteArray, data: ByteArray, sig: ByteArray): Boolean = try {
        Signature.getInstance("SHA256withECDSA").run { initVerify(Crypto.pub(pub)); update(data); verify(sig) }
    } catch (e: Exception) { false }

    /** Safety number shown to users, e.g. "4F2A 91C0 7B3E D455" */
    fun fingerprint(pub: ByteArray = publicBytes): String {
        val h = MessageDigest.getInstance("SHA-256").digest(pub)
        return h.take(8).joinToString("") { "%02X".format(it) }.chunked(4).joinToString(" ")
    }
}

object Crypto {
    private val rnd = SecureRandom()

    fun pub(bytes: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))

    fun ephemeral(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1")); generateKeyPair()
    }

    fun ecdh(my: PrivateKey, their: ByteArray): ByteArray =
        KeyAgreement.getInstance("ECDH").run { init(my); doPhase(pub(their), true); generateSecret() }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(len)
        var t = ByteArray(0); var pos = 0; var i = 1
        while (pos < len) {
            mac.update(t); mac.update(info); mac.update(i.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, len - pos)
            System.arraycopy(t, 0, out, pos, n); pos += n; i++
        }
        return out
    }

    fun randomBytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }

    /** One-shot AES-256-GCM: returns nonce(12) || ciphertext. */
    fun seal(key: ByteArray, plain: ByteArray): ByteArray {
        val nonce = randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return nonce + c.doFinal(plain)
    }

    fun open(key: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, 0, 12))
        return c.doFinal(data, 12, data.size - 12)
    }
}

/** AES-256-GCM in one direction, with a counter nonce (never repeats, rejects replays). */
class GcmStream(key: ByteArray, private val dir: Byte) {
    private val k = SecretKeySpec(key, "AES")
    private var sendCtr = 0L
    private var lastRecv = -1L

    @Synchronized
    fun seal(plain: ByteArray): ByteArray {
        val nonce = nonce(++sendCtr)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, nonce))
        val ct = c.doFinal(plain)
        return ByteBuffer.allocate(8 + ct.size).putLong(sendCtr).put(ct).array()
    }

    /** strict = true for TCP (in order); false for UDP audio (allows reordering, drops old). */
    @Synchronized
    fun open(msg: ByteArray, strict: Boolean = true): ByteArray? {
        if (msg.size < 8 + 16) return null
        val ctr = ByteBuffer.wrap(msg, 0, 8).long
        if (strict && ctr != lastRecv + 1 && lastRecv != -1L) return null
        if (!strict && ctr <= lastRecv) return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, nonce(ctr)))
            c.doFinal(msg, 8, msg.size - 8).also { lastRecv = ctr }
        } catch (e: Exception) { null }
    }

    private fun nonce(ctr: Long) = ByteBuffer.allocate(12).put(dir).put(0).put(0).put(0).putLong(ctr).array()
}

/** Keys for one secured connection. Initiator and responder mirror each other. */
class Session(okm: ByteArray, initiator: Boolean) {
    private val a = okm.copyOfRange(0, 32)
    private val b = okm.copyOfRange(32, 64)
    private val c = okm.copyOfRange(64, 96)
    private val d = okm.copyOfRange(96, 128)
    val tx = GcmStream(if (initiator) a else b, if (initiator) 1 else 2)
    val rx = GcmStream(if (initiator) b else a, if (initiator) 2 else 1)
    fun audioTx() = GcmStream(if (initiator) c else d, if (initiator) 3 else 4)
    fun audioRx() = GcmStream(if (initiator) d else c, if (initiator) 4 else 3)
}

/** What we learned about the other phone during the handshake. */
data class Remote(
    val code: String,
    val name: String,
    val phone: String,
    val key: String,
    val security: Int,      // 0 new/unverified, 1 verified, 2 KEY CHANGED
    val avatarHash: String  // tells the other side when my picture changed
)

/**
 * Length-prefixed frames over TCP. After [handshake] every frame is AES-GCM encrypted.
 *
 * Handshake: each side sends {code,name,phone,id,eph,sig}; sig = ECDSA(identity, eph || type).
 * Session keys = HKDF(ECDH(eph_a, eph_b)). Forward secrecy: ephemeral keys are thrown away.
 */
class SecureChannel(val socket: Socket) {
    private val inp = DataInputStream(BufferedInputStream(socket.getInputStream(), 65536))
    private val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 65536))
    private var session: Session? = null
    var remote: Remote? = null
        private set

    val audioSession: Session? get() = session

    @Synchronized
    fun send(data: ByteArray) {
        val frame = session?.tx?.seal(data) ?: data
        out.writeInt(frame.size); out.write(frame); out.flush()
    }

    fun recv(): ByteArray? {
        val n = try { inp.readInt() } catch (e: EOFException) { return null }
        if (n < 0 || n > 2 * 1024 * 1024) throw IllegalStateException("bad frame")
        val b = ByteArray(n); inp.readFully(b)
        val s = session ?: return b
        return s.rx.open(b) ?: throw SecurityException("decrypt failed")
    }

    fun sendJson(o: JSONObject) = send(o.toString().toByteArray(Charsets.UTF_8))
    fun recvJson(): JSONObject? = recv()?.let { JSONObject(String(it, Charsets.UTF_8)) }

    fun close() { runCatching { socket.close() } }

    private fun hello(type: String, eph: KeyPair): JSONObject {
        val me = Store.settings.value
        val ephPub = eph.public.encoded
        return JSONObject()
            .put("v", 2).put("t", type)
            .put("code", me.code).put("name", me.name)
            .put("phone", if (me.sharePhone) me.phone else "")
            .put("id", Identity.publicB64).put("eph", b64(ephPub)).put("av", me.avatarHash)
            .put("sig", b64(Identity.sign(ephPub + type.toByteArray())))
    }

    private fun parse(o: JSONObject, type: String): Pair<Remote, ByteArray> {
        val id = unb64(o.getString("id"))
        val eph = unb64(o.getString("eph"))
        val sig = unb64(o.getString("sig"))
        if (!Identity.verify(id, eph + type.toByteArray(), sig)) throw SecurityException("bad signature")
        val code = o.getString("code")
        val key = b64(id)
        val c = Store.contact(code)
        val sec = when {
            c == null || c.key.isBlank() -> 0
            c.key != key -> 2
            c.verified -> 1
            else -> 0
        }
        return Remote(code, o.optString("name", code), o.optString("phone"), key, sec, o.optString("av")) to eph
    }

    private fun derive(eph: KeyPair, theirEph: ByteArray, initiator: Boolean) {
        val secret = Crypto.ecdh(eph.private, theirEph)
        val mine = eph.public.encoded
        val info = if (initiator) mine + theirEph else theirEph + mine
        session = Session(Crypto.hkdf(secret, "Ran-v1".toByteArray(), info, 128), initiator)
    }

    /** Client side. Returns error string from the other side (e.g. "busy") or null on success. */
    fun connectHandshake(type: String): String? {
        val eph = Crypto.ephemeral()
        sendJson(hello(type, eph))
        val o = recvJson() ?: return "closed"
        if (o.has("err")) return o.getString("err")
        val (r, theirEph) = parse(o, type)
        remote = r
        derive(eph, theirEph, initiator = true)
        return null
    }

    /**
     * Server side, step 1: read the hello. Caller then decides (busy/blocked...) and calls
     * [accept] or [refuse].
     */
    fun readHello(): Triple<String, Remote, ByteArray>? {
        val o = recvJson() ?: return null
        if (o.optInt("v") != 2) return null
        val type = o.optString("t")
        val (r, theirEph) = parse(o, type)
        remote = r
        return Triple(type, r, theirEph)
    }

    fun accept(type: String, theirEph: ByteArray) {
        val eph = Crypto.ephemeral()
        sendJson(hello(type, eph))
        derive(eph, theirEph, initiator = false)
    }

    fun refuse(err: String) { runCatching { sendJson(JSONObject().put("err", err)) }; close() }
}
