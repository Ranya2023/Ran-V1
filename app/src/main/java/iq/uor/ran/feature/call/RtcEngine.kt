package iq.uor.ran.feature.call

import iq.uor.ran.App
import iq.uor.ran.core.crypto.*
import iq.uor.ran.core.data.*
import iq.uor.ran.core.net.*
import iq.uor.ran.feature.chat.*
import iq.uor.ran.feature.rooms.*
import iq.uor.ran.feature.safety.*
import iq.uor.ran.feature.setup.*
import iq.uor.ran.ui.*
import iq.uor.ran.R

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.os.Build
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

/** Forwards frames to whichever renderer is currently on screen (renderers come and go with the UI). */
class ProxySink(private val onFirst: (() -> Unit)? = null) : VideoSink {
    @Volatile var target: VideoSink? = null
    @Volatile private var seen = false
    @Volatile var frameW = 0
    @Volatile var frameH = 0
    override fun onFrame(frame: VideoFrame) {
        if (!seen) { seen = true; onFirst?.invoke() }
        frameW = frame.rotatedWidth; frameH = frame.rotatedHeight
        target?.onFrame(frame)
    }
    fun reset() { seen = false }
}

/**
 * One WebRTC call: Opus voice with echo cancellation, H.264/VP8 video, screen sharing.
 * Media is encrypted with DTLS-SRTP. Signalling (offer/answer/ICE) travels over our own
 * authenticated channel (LAN SecureChannel or signed internet messages).
 */
class RtcEngine(
    private val ctx: Context,
    private val caller: Boolean,
    private val signal: (JSONObject) -> Unit,
    private val onConnected: () -> Unit,
    private val onFailed: () -> Unit
) {
    companion object {
        private var factory: PeerConnectionFactory? = null
        lateinit var egl: EglBase
            private set
        val localSink = ProxySink()
        val remoteSink = ProxySink { Hub.remoteVideo.value = true }

        @Synchronized
        fun init(app: Context) {
            if (factory != null) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions()
            )
            egl = EglBase.create()
            val adm = JavaAudioDeviceModule.builder(app)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()
            factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()
        }

        /** Local Wi-Fi only: no STUN, no TURN, no internet server of any kind. */
        fun iceServers(): List<PeerConnection.IceServer> = emptyList()
    }

    private val f = factory!!
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val oldMode = am.mode
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSender: RtpSender? = null

    private var camCapturer: CameraVideoCapturer? = null
    private var camSource: VideoSource? = null
    private var camHelper: SurfaceTextureHelper? = null
    private var camTrack: VideoTrack? = null

    private var screenCapturer: VideoCapturer? = null
    private var screenSource: VideoSource? = null
    private var screenHelper: SurfaceTextureHelper? = null
    private var screenTrack: VideoTrack? = null

    private var frontCamera = true
    private var connectedOnce = false
    private var closed = false
    private var remoteSet = false
    private val pendingIce = mutableListOf<IceCandidate>()

    val cameraOn get() = camTrack != null
    val sharing get() = screenTrack != null

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(c: IceCandidate) {
            signal(JSONObject().put("c", "rtc").put("ice", c.sdp).put("mid", c.sdpMid).put("idx", c.sdpMLineIndex))
        }
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onDataChannel(d: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onTrack(t: RtpTransceiver) {
            val track = t.receiver.track()
            if (track is VideoTrack) track.addSink(remoteSink)
        }
        override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
            when (s) {
                PeerConnection.PeerConnectionState.CONNECTED -> { connectedOnce = true; onConnected() }
                PeerConnection.PeerConnectionState.FAILED -> if (!closed) onFailed()
                else -> {}
            }
        }
    }

    private open inner class Sdp : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) { if (!closed) onFailed() }
        override fun onSetFailure(e: String?) { if (!closed) onFailed() }
    }

    fun start() {
        Hub.remoteVideo.value = false
        remoteSink.reset()
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        val cfg = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
        pc = f.createPeerConnection(cfg, observer) ?: run { onFailed(); return }
        audioSource = f.createAudioSource(MediaConstraints())
        audioTrack = f.createAudioTrack("a0", audioSource)
        if (caller) {
            pc!!.addTrack(audioTrack, listOf("s0"))
            // Always negotiate a video slot: the camera or screen can be switched on at any time without renegotiating
            val vt = pc!!.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf("s0"))
            )
            videoSender = vt.sender
            camTrack?.let { videoSender?.setTrack(it, false) }
            pc!!.createOffer(object : Sdp() {
                override fun onCreateSuccess(d: SessionDescription) {
                    pc?.setLocalDescription(Sdp(), d)
                    signal(JSONObject().put("c", "rtc").put("sdp", d.description).put("type", "offer"))
                }
            }, MediaConstraints())
        }
    }

    fun onSignal(o: JSONObject) {
        val p = pc ?: return
        if (o.has("sdp")) {
            val type = if (o.optString("type") == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
            p.setRemoteDescription(object : Sdp() {
                override fun onSetSuccess() {
                    synchronized(pendingIce) { remoteSet = true; pendingIce.forEach { p.addIceCandidate(it) }; pendingIce.clear() }
                    if (type == SessionDescription.Type.OFFER) answer(p)
                }
            }, SessionDescription(type, o.getString("sdp")))
        } else if (o.has("ice")) {
            val c = IceCandidate(o.optString("mid"), o.optInt("idx"), o.getString("ice"))
            synchronized(pendingIce) { if (remoteSet) p.addIceCandidate(c) else pendingIce.add(c) }
        }
    }

    private fun answer(p: PeerConnection) {
        p.addTrack(audioTrack, listOf("s0"))
        p.transceivers.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }?.let {
            it.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
            videoSender = it.sender
            (screenTrack ?: camTrack)?.let { t -> it.sender.setTrack(t, false) }
        }
        p.createAnswer(object : Sdp() {
            override fun onCreateSuccess(d: SessionDescription) {
                p.setLocalDescription(Sdp(), d)
                signal(JSONObject().put("c", "rtc").put("sdp", d.description).put("type", "answer"))
            }
        }, MediaConstraints())
    }

    /* ---------------- controls ---------------- */

    fun setMuted(m: Boolean) { audioTrack?.setEnabled(!m) }

    fun setSpeaker(on: Boolean) {
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

    /** Turns the camera on/off. Returns true if the camera is now on. */
    fun setCamera(on: Boolean): Boolean {
        if (on == cameraOn) return cameraOn
        if (!on) {
            runCatching { camCapturer?.stopCapture() }
            camTrack?.removeSink(localSink)
            if (screenTrack == null) videoSender?.setTrack(null, false)
            runCatching { camCapturer?.dispose() }; camTrack?.dispose(); camSource?.dispose(); camHelper?.dispose()
            camCapturer = null; camTrack = null; camSource = null; camHelper = null
            return false
        }
        return try {
            val en = Camera2Enumerator(ctx)
            val name = en.deviceNames.firstOrNull { if (frontCamera) en.isFrontFacing(it) else en.isBackFacing(it) }
                ?: en.deviceNames.firstOrNull() ?: return false
            val cap = en.createCapturer(name, null)
            val helper = SurfaceTextureHelper.create("cam", egl.eglBaseContext)
            val src = f.createVideoSource(false)
            cap.initialize(helper, ctx, src.capturerObserver)
            cap.startCapture(1280, 720, 30)
            val track = f.createVideoTrack("v0", src)
            track.addSink(localSink)
            camCapturer = cap; camHelper = helper; camSource = src; camTrack = track
            if (screenTrack == null) videoSender?.setTrack(track, false)
            true
        } catch (e: Exception) { false }
    }

    fun switchCamera() {
        frontCamera = !frontCamera
        runCatching { camCapturer?.switchCamera(null) }
    }

    val isFront get() = frontCamera

    /** Screen sharing: the service must already be foreground with type mediaProjection (Android 14). */
    fun startScreen(data: Intent, w: Int, h: Int, onStopped: () -> Unit): Boolean = try {
        val cap = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() { onStopped() }
        })
        val helper = SurfaceTextureHelper.create("screen", egl.eglBaseContext)
        val src = f.createVideoSource(true)
        cap.initialize(helper, ctx, src.capturerObserver)
        cap.startCapture(w, h, 15)
        val track = f.createVideoTrack("sc0", src)
        screenCapturer = cap; screenHelper = helper; screenSource = src; screenTrack = track
        camTrack?.removeSink(localSink)
        track.addSink(localSink)
        videoSender?.setTrack(track, false)
        true
    } catch (e: Exception) { false }

    fun stopScreen() {
        val t = screenTrack ?: return
        runCatching { screenCapturer?.stopCapture() }
        t.removeSink(localSink)
        videoSender?.setTrack(camTrack, false)
        camTrack?.addSink(localSink)
        runCatching { screenCapturer?.dispose() }; t.dispose(); screenSource?.dispose(); screenHelper?.dispose()
        screenCapturer = null; screenTrack = null; screenSource = null; screenHelper = null
    }

    fun close() {
        if (closed) return
        closed = true
        stopScreen()
        setCamera(false)
        runCatching { pc?.close() }
        runCatching { pc?.dispose() }
        pc = null
        runCatching { audioTrack?.dispose() }
        runCatching { audioSource?.dispose() }
        setSpeaker(false)
        runCatching { am.mode = oldMode }
        Hub.remoteVideo.value = false
        remoteSink.target = null
    }
}
