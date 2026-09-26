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
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator

/** Ringtone + vibration for incoming calls, ring-back / busy tones for outgoing calls. */
class Ringer(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var tone: ToneGenerator? = null
    private var ringing = false
    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private val keepRinging = object : Runnable {
        override fun run() {
            if (!ringing) return
            ringtone?.let { if (!it.isPlaying) runCatching { it.play() } }
            main.postDelayed(this, 1500)
        }
    }

    fun startRinging() = main.post {
        stopNow()
        ringing = true
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            try {
                val uri = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_RINGTONE)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ringtone = RingtoneManager.getRingtone(ctx, uri)?.apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    if (Build.VERSION.SDK_INT >= 28) isLooping = true
                    play()
                }
            } catch (_: Exception) { }
            main.postDelayed(keepRinging, 1500)
        }
        if (am.ringerMode != AudioManager.RINGER_MODE_SILENT) {
            val pattern = longArrayOf(0, 900, 700)
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, 0)
                }
            } catch (_: Exception) { }
        }
    }

    fun startRingback() = main.post {
        stopNow()
        tone = try {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).also { it.startTone(ToneGenerator.TONE_SUP_RINGTONE) }
        } catch (_: Exception) { null }
    }

    fun playBusy() = main.post {
        stopNow()
        val t = try { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70) } catch (_: Exception) { null }
        t?.startTone(ToneGenerator.TONE_SUP_BUSY, 1800)
        tone = t
        main.postDelayed({ if (tone === t) stopNow() }, 2000)
    }

    fun stop() = main.post { stopNow() }

    private fun stopNow() {
        ringing = false
        main.removeCallbacks(keepRinging)
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator?.cancel() }
        runCatching { tone?.stopTone(); tone?.release() }
        tone = null
    }
}
