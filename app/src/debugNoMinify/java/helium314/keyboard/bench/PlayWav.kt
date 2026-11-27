// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.bench

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Device test helper, compiled only into the debugNoMinify variant: plays a 16 kHz mono 16-bit wav on the phone's own speaker from a shell, so
 * that voice typing in a text field hears real speech without another app taking the focus. Run from a shell:
 *   CLASSPATH=<base.apk> app_process / helium314.keyboard.bench.PlayWav <wav>
 */
object PlayWav {
    @JvmStatic
    fun main(args: Array<String>) {
        val bytes = File(args[0]).readBytes()
        var pos = 12
        var pcm = ShortArray(0)
        while (pos + 8 <= bytes.size) {
            val size = ByteBuffer.wrap(bytes, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (String(bytes, pos, 4, Charsets.US_ASCII) == "data") {
                val shorts = ByteBuffer.wrap(bytes, pos + 8, size).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                pcm = ShortArray(size / 2) { shorts.get(it) }
                break
            }
            pos += 8 + size
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(16000 * 2)
            .build()
        track.play()
        val start = System.nanoTime()
        track.write(pcm, 0, pcm.size) // blocks until the last part is queued
        val left = pcm.size / 16L - (System.nanoTime() - start) / 1_000_000 + 1500
        if (left > 0) Thread.sleep(left)
        track.stop()
        track.release()
        println("PLAYED ms=${pcm.size / 16}")
    }
}
