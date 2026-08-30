package com.example.util

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sin

object SyncBeepGenerator {

    suspend fun playSyncBeep(frequencyHz: Double = 1000.0, durationMs: Int = 150) {
        withContext(Dispatchers.IO) {
            try {
                val sampleRate = 44100
                val numSamples = (sampleRate * (durationMs / 1000.0)).toInt()
                val generatedSnd = ShortArray(numSamples)

                for (i in 0 until numSamples) {
                    val angle = 2.0 * Math.PI * i / (sampleRate / frequencyHz)
                    // Apply fade-in and fade-out envelope to avoid audio clicks
                    val envelope = when {
                        i < 100 -> i / 100.0
                        i > numSamples - 100 -> (numSamples - i) / 100.0
                        else -> 1.0
                    }
                    val sample = (sin(angle) * Short.MAX_VALUE * 0.8 * envelope).toInt()
                    generatedSnd[i] = sample.toShort()
                }

                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(numSamples * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                audioTrack.write(generatedSnd, 0, numSamples)
                audioTrack.play()

                // Wait for playback then release
                Thread.sleep(durationMs.toLong() + 50)
                audioTrack.stop()
                audioTrack.release()
            } catch (_: Exception) {
            }
        }
    }
}
