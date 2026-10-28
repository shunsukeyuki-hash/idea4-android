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

    /** 次の start() で、この位置（フレーム）から始める。使うと 0 に戻る。 */
    @Volatile
    var startAt: Int = 0

    /** 次の start() をステレオ録音にする。使うと false に戻る。 */
    @Volatile
    var stereoIn: Boolean = false

    /** 左右それぞれのピーク（0〜1）。モノラルのときは左右とも同じ値。 */
    @Volatile
    var onLevel: ((Float, Float) -> Unit)? = null

    /** ステレオ録音の右チャンネル。onFinish が呼ばれる時点で入っている（モノラルのときは null）。 */
    @Volatile
    var capturedR: ShortArray? = null

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
        val from = startAt
        startAt = 0
        val stereo = record && stereoIn
        stereoIn = false
        capturedR = null
        thread(name = "idea-audio") {
            var rec: AudioRecord? = null
            var out: AudioTrack? = null
            var captured: ShortArray? = null
            var capturedRt: ShortArray? = null
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
                    val inMask = if (stereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
                    val minIn = AudioRecord.getMinBufferSize(
                        SR, inMask, AudioFormat.ENCODING_PCM_16BIT,
                    )
                    val r = AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SR)
                                .setChannelMask(inMask)
                                .build(),
                        )
                        .setBufferSizeInBytes(maxOf(minIn, chunk * 2 * 4 * (if (stereo) 2 else 1)))
                        .build()
                    rec = r
                    if (r.state != AudioRecord.STATE_INITIALIZED) {
                        throw IllegalStateException(if (stereo) "ステレオで入力を開けませんでした。設定でモノラルにするか、別の入力を選んでください" else "マイクを開けませんでした")
                    }
                    if (inDev != null) r.setPreferredDevice(inDev)
                }

                val total = if (record) maxFrames else 0
                val buf = ShortArray(chunk * 2)
                val ch = if (stereo) 2 else 1
                val inb = ShortArray(chunk * ch)
                val inL = ShortArray(chunk)
                val inR = ShortArray(chunk)
                val firstSize = minOf(maxOf(0, total - from), SR * 60)
                var store: ShortArray? = if (record) ShortArray(firstSize) else null
                var storeR: ShortArray? = if (record && stereo) ShortArray(firstSize) else null
                var pos = from
                val outLim = Limiter(OUT_LIMIT)
                val inLim = Limiter(IN_LIMIT)

                rec?.startRecording()
                tr.play()
                while (!s.stop) {
                    val limit = if (record) total else Mixer.totalFrames(tracks)
                    if (pos >= limit) break
                    tr.setVolume(master)
                    val n = minOf(chunk, limit - pos)
                    Mixer.mix(tracks, pos, buf, n, outLim)
                    tr.write(buf, 0, n * 2)
                    val r2 = rec
                    val st0 = store
                    if (r2 != null && st0 != null) {
                        val want = n * ch
                        var g = 0
                        while (g < want) {
                            val got = r2.read(inb, g, want - g)
                            if (got <= 0) break
                            g += got
                        }
                        val frames = g / ch
                        if (stereo) {
                            for (q in 0 until frames) {
                                inL[q] = inb[2 * q]
                                inR[q] = inb[2 * q + 1]
                            }
                        } else {
                            System.arraycopy(inb, 0, inL, 0, frames)
                        }
                        // クリップの目安：リミッターをかける前の最大値を測る
                        var pkL = 0
                        var pkR = 0
                        for (q in 0 until frames) {
                            val a = abs(inL[q].toInt())
                            if (a > pkL) pkL = a
                            if (stereo) {
                                val b = abs(inR[q].toInt())
                                if (b > pkR) pkR = b
                            }
                        }
                        if (stereo) inLim.processStereo(inL, inR, frames) else inLim.processMono(inL, frames)
                        var st: ShortArray = st0
                        var stR: ShortArray? = storeR
                        val need = pos - from + frames
                        if (need > st.size) {
                            // 録音が伸びたら、必要な分だけ少しずつ広げる（最初から10分ぶんは確保しない）
                            val ns = minOf(maxOf(need, st.size * 2), maxOf(0, total - from))
                            st = st.copyOf(ns)
                            store = st
                            if (stR != null) {
                                stR = stR.copyOf(ns)
                                storeR = stR
                            }
                        }
                        System.arraycopy(inL, 0, st, pos - from, frames)
                        if (stR != null) System.arraycopy(inR, 0, stR, pos - from, frames)
                        val lvL = pkL / 32768f
                        val lvR = if (stereo) pkR / 32768f else lvL
                        val cb = onLevel
                        if (cb != null) main.post { cb(lvL, lvR) }
                    }
                    pos += n
                    if ((pos / chunk) % 5 == 0) {
                        val t = pos.toDouble() / SR
                        main.post { onProgress(t) }
                    }
                }
                val stEnd = store
                if (stEnd != null) captured = stEnd.copyOf(maxOf(0, minOf(pos - from, stEnd.size)))
                val stEndR = storeR
                if (stEndR != null) capturedRt = stEndR.copyOf(maxOf(0, minOf(pos - from, stEndR.size)))
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
                capturedR = capturedRt
                val err = error
                main.post { onFinish(c, err) }
            }
        }
        return s
    }
}
