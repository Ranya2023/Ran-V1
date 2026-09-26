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

import org.json.JSONObject
import java.util.concurrent.Executors

/** A signalling path to the other phone over the local Wi-Fi (encrypted TCP). */
interface Link {
    fun send(o: JSONObject)
    fun close()
}

/** Local Wi-Fi: messages go through the end-to-end encrypted SecureChannel, in order. */
class LanLink(val ch: SecureChannel) : Link {
    private val ex = Executors.newSingleThreadExecutor()
    override fun send(o: JSONObject) { runCatching { ex.execute { runCatching { ch.sendJson(o) } } } }
    override fun close() { runCatching { ex.execute { ch.close() }; ex.shutdown() } }
}

