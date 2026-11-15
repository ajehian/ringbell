package app.ringbell.host

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Loops the system alarm sound (and vibration) until [stop] is called. */
class AlarmPlayer(private val ctx: Context) {
    private var player: MediaPlayer? = null
    private var playing = false

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    fun start() {
        if (playing) return
        playing = true

        // A silenced alarm stream would make "ring until dismissed" pointless.
        try {
            val am = ctx.getSystemService(AudioManager::class.java)
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) == 0) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, (am.getStreamMaxVolume(AudioManager.STREAM_ALARM) * 0.7).toInt().coerceAtLeast(1), 0)
            }
        } catch (_: Exception) {
        }

        val uri = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(ctx, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {
            player?.release()
            player = null
        }

        try {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0))
        } catch (_: Exception) {
        }
    }

    fun stop() {
        if (!playing) return
        playing = false
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        player?.release()
        player = null
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
    }
}
