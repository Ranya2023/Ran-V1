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

import android.graphics.Bitmap
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONArray
import org.json.JSONObject

data class SetupProfile(
    val org: String,
    val coordinator: String,
    val lang: String,
    val visible: Boolean,
    val who: String,
    val contacts: List<Contact>
)

/** QR codes: "contact card" pairing and one-scan organisation "setup profile". */
object Qr {
    private const val C = "RAN:C:"
    private const val S = "RAN:S:"
    private const val R = "RAN:R:"

    fun roomQr(r: Room): String = R + enc(JSONObject().put("id", r.id).put("n", r.name).put("k", r.key))

    fun bitmap(text: String, size: Int = 720, dark: Int = 0xFF0B1B33.toInt()): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to if (text.length > 900) ErrorCorrectionLevel.L else ErrorCorrectionLevel.M
        )
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m[x, y]) dark else -1
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun enc(o: JSONObject) = Base64.encodeToString(o.toString().toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
    private fun dec(s: String) = JSONObject(String(Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))

    /** My contact card: code, name, phone, identity key, current IP. */
    fun myCard(): String {
        val s = Store.settings.value
        return C + enc(
            JSONObject().put("c", s.code).put("n", s.name).put("p", s.phone)
                .put("k", Identity.publicB64).put("ip", Hub.myIp.value)
        )
    }

    fun setupProfile(org: String, lang: String, visible: Boolean, who: String, contacts: List<Contact>): String {
        val me = Store.settings.value
        val list = JSONArray()
        // the coordinator is always included, with a verified key
        list.put(JSONObject().put("c", me.code).put("n", me.name).put("p", me.phone).put("k", Identity.publicB64))
        contacts.filter { it.code != me.code }.take(7).forEach {
            list.put(JSONObject().put("c", it.code).put("n", it.name).put("p", it.phone).put("k", it.key))
        }
        return S + enc(
            JSONObject().put("org", org).put("co", me.code).put("lang", lang).put("vis", visible)
                .put("who", who).put("list", list)
        )
    }

    /** Returns Contact, SetupProfile, Room, or null. */
    fun parse(text: String?): Any? = try {
        when {
            text == null -> null
            text.startsWith(C) -> dec(text.removePrefix(C)).let {
                val key = it.optString("k")
                Contact(it.getString("c"), it.optString("n"), it.optString("p"), it.optString("ip"), key, verified = key.isNotEmpty())
            }
            text.startsWith(R) -> dec(text.removePrefix(R)).let { Room(it.getString("id"), it.optString("n"), it.getString("k")) }
            text.startsWith(S) -> dec(text.removePrefix(S)).let { o ->
                val a = o.optJSONArray("list") ?: JSONArray()
                SetupProfile(
                    o.optString("org"), o.optString("co"), o.optString("lang", "en"), o.optBoolean("vis"),
                    o.optString("who", "everyone"),
                    (0 until a.length()).map { i ->
                        val c = a.getJSONObject(i); val k = c.optString("k")
                        Contact(c.getString("c"), c.optString("n"), c.optString("p"), "", k, verified = k.isNotEmpty(), team = true)
                    }
                )
            }
            else -> null
        }
    } catch (e: Exception) { null }

    fun applySetup(p: SetupProfile) {
        val me = Store.settings.value
        Store.save(me.copy(org = p.org, lang = p.lang, visible = p.visible, whoCanCall = p.who))
        p.contacts.filter { it.code != me.code }.forEach { Store.upsertContact(it) }
        Store.prefs().edit().putString("coordinator", p.coordinator).apply()
    }
}
