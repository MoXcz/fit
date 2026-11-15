// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.android.notification

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.getSystemService
import kotlin.math.exp
import kotlin.math.ln

// The old app's alarm: two short 880 Hz pips and a [180, 90, 180] ms buzz. The pips are generated, so
// there's no sound file, and they play on the alarm stream, like a clock app's timer.
class EndCue(
    private val context: Context,
) {
    fun play() {
        beep()
        vibrate()
    }

    private fun beep() {
        val track =
            try {
                AudioTrack
                    .Builder()
                    .setAudioAttributes(alarmAttributes)
                    .setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    ).setBufferSizeInBytes(pips.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
            } catch (e: UnsupportedOperationException) {
                return
            } catch (e: IllegalArgumentException) {
                return
            }
        try {
            track.write(pips, 0, pips.size)
            track.play()
            Handler(Looper.getMainLooper()).postDelayed({ track.release() }, RELEASE_AFTER_MS)
        } catch (e: IllegalStateException) {
            track.release()
        }
    }

    private fun vibrate() {
        val vibrator =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService<VibratorManager>()?.defaultVibrator
            } else {
                context.getSystemService<Vibrator>()
            } ?: return
        if (!vibrator.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 180, 90, 180), -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, alarmAttributes)
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val RELEASE_AFTER_MS = 1_000L
        const val PIP_SECONDS = 0.18
        const val PIP_EVERY_SECONDS = 0.28
        const val PIPS = 2
        const val PEAK = 0.25
        const val ATTACK_SECONDS = 0.01

        val alarmAttributes: AudioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

        // Square waves with a fast attack and an exponential fade to silence, as the old Web Audio one.
        val pips: ShortArray by lazy {
            val decay = ln(PEAK / 0.0001) / (PIP_SECONDS - ATTACK_SECONDS)
            val length = (SAMPLE_RATE * ((PIPS - 1) * PIP_EVERY_SECONDS + PIP_SECONDS)).toInt()
            ShortArray(length) { i ->
                val t = i.toDouble() / SAMPLE_RATE
                val inPip = t % PIP_EVERY_SECONDS
                if (inPip > PIP_SECONDS) return@ShortArray 0
                val gain = if (inPip < ATTACK_SECONDS) PEAK * inPip / ATTACK_SECONDS else PEAK * exp(-(inPip - ATTACK_SECONDS) * decay)
                val square = if ((t * 880 * 2).toInt() % 2 == 0) 1 else -1
                (square * gain * Short.MAX_VALUE).toInt().toShort()
            }
        }
    }
}
