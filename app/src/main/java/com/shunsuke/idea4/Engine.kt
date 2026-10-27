package com.shunsuke.idea4

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import kotlin.math.abs
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

class Session {
    @Volatile
    var stop = false
}

/**
 * 再生と録音を1本のスレッドで同時に進める。
 * 入力（マイク）と出力（スピーカー・イヤホン）は別々に指定できる。
 */
class Engine(ctx: Context) {
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())

    fun inputs(): List<AudioDeviceInfo> = am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
    fun outputs(): List<AudioDeviceInfo> = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
    fun findInput(id: Int): AudioDeviceInfo? = inputs().firstOrNull { it.id == id }
    fun findOutput(id: Int): AudioDeviceInfo? = outputs().firstOrNull { it.id == id }
    fun builtinMic(): AudioDeviceInfo? =
        inputs().firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

    fun builtinSpeaker(): AudioDeviceInfo? =
        outputs().firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    @SuppressLint("MissingPermission")
    @Volatile
    var master: Float = 1f

    @Volatile
    var onLevel: ((Float) -> Unit)? = null

    fun start(
        tracks: List<PlayTrack>,
        record: Boolean,
        maxFrames: Int,
        inDev: AudioDeviceInfo?,
        outDev: AudioDeviceInfo?,
        onProgress: (Double) -> Unit,
        onFinish: (ShortArray?, String?) -> Unit,
    ): Session {
        val s = Session()
        thread(name = "idea-audio") {
            var rec: AudioRecord? = null
            var out: AudioTrack? = null
            var captured: ShortArray? = null
            var error: String? = null
            try {
                val chunk = 960
                val minOut = AudioTrack.getMinBufferSize(
                    SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                )
                val tr = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SR)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(minOut, chunk * 4 * 3))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
                out = tr
                if (outDev != null) tr.setPreferredDevice(outDev)

                if (record) {
                    val minIn = AudioRecord.getMinBufferSize(
                        SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    )
                    val r = AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SR)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build(),
                        )
                        .setBufferSizeInBytes(maxOf(minIn, chunk * 2 * 4))
                        .build()
                    rec = r
                    if (r.state != AudioRecord.STATE_INITIALIZED) {
                        throw IllegalStateException("マイクを開けませんでした")
                    }
                    if (inDev != null) r.setPreferredDevice(inDev)
                }

                val total = if (record) maxFrames else 0
                val buf = ShortArray(chunk * 2)
                val inb = ShortArray(chunk)
                val store = if (record) ShortArray(total) else null
                var pos = 0

                rec?.startRecording()
                tr.play()
                while (!s.stop) {
                    val limit = if (record) total else Mixer.totalFrames(tracks)
                    if (pos >= limit) break
                    tr.setVolume(master)
                    val n = minOf(chunk, limit - pos)
                    Mixer.mix(tracks, pos, buf, n)
                    tr.write(buf, 0, n * 2)
                    val r2 = rec
                    if (r2 != null && store != null) {
                        var g = 0
                        while (g < n) {
                            val got = r2.read(inb, g, n - g)
                            if (got <= 0) break
                            g += got
                        }
                        System.arraycopy(inb, 0, store, pos, g)
                        var pk = 0
                        for (q in 0 until g) {
                            val a = abs(inb[q].toInt())
                            if (a > pk) pk = a
                        }
                        val lv = pk / 32768f
                        val cb = onLevel
                        if (cb != null) main.post { cb(lv) }
                    }
                    pos += n
                    if ((pos / chunk) % 5 == 0) {
                        val t = pos.toDouble() / SR
                        main.post { onProgress(t) }
                    }
                }
                if (store != null) captured = store.copyOf(minOf(pos, store.size))
            } catch (e: Exception) {
                error = "音声を開始できませんでした（" + (e.message ?: "原因不明") + "）。"
            } finally {
                try {
                    rec?.stop()
                } catch (e: Exception) {
                }
                try {
                    rec?.release()
                } catch (e: Exception) {
                }
                try {
                    if (s.stop || error != null) {
                        out?.pause()
                        out?.flush()
                    } else {
                        out?.stop()
                        Thread.sleep(300)
                    }
                } catch (e: Exception) {
                }
                try {
                    out?.release()
                } catch (e: Exception) {
                }
                val c = captured
                val err = error
                main.post { onFinish(c, err) }
            }
        }
        return s
    }
}
