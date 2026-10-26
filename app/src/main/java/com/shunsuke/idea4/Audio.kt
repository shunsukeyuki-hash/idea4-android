package com.shunsuke.idea4

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * 再生・ミックス用の1トラック分のデータ（エフェクト処理済みのモノラル）。
 * shiftFrames が正なら、録音の頭をその分だけ飛ばす。pan は -1（左）〜1（右）。
 */
class PlayTrack(
    val pcm: ShortArray?,
    vol: Float,
    val shiftFrames: Int,
    val mute: Boolean,
    pan: Float = 0f,
) {
    val gl: Float
    val gr: Float

    init {
        val p = if (pan < -1f) -1f else if (pan > 1f) 1f else pan
        val a = (p + 1f) / 2f * (Math.PI.toFloat() / 2f)
        gl = vol * 0.85f * cos(a)
        gr = vol * 0.85f * sin(a)
    }
}

private fun clip16(v: Float): Short {
    val x = if (v > 32767f) 32767f else if (v < -32768f) -32768f else v
    return x.toInt().toShort()
}

/** コンプレッサー。amount は 0〜1（大きいほど強くかかる）。 */
fun applyComp(src: ShortArray, amount: Float): ShortArray {
    val thr = maxOf(0.05f, 0.5f - 0.4f * amount)
    val ratio = 1f + 7f * amount
    val expo = 1f - 1f / ratio
    val atk = exp(-1f / (0.005f * SR))
    val rel = exp(-1f / (0.12f * SR))
    val makeup = thr.pow(-expo * 0.5f)
    val out = ShortArray(src.size)
    var env = 0f
    var gain = 1f
    for (i in src.indices) {
        val x = src[i] / 32768f
        val a = abs(x)
        env = if (a > env) a + atk * (env - a) else a + rel * (env - a)
        if ((i and 31) == 0) {
            gain = if (env > thr) (thr / env).pow(expo) else 1f
        }
        out[i] = clip16(x * gain * makeup * 32768f)
    }
    return out
}

/** ディレイ。mix は 0〜1、ms は繰り返しの間隔。繰り返しの余韻の分だけ長くなる。 */
fun applyDelay(src: ShortArray, mix: Float, ms: Float): ShortArray {
    val d = maxOf(1, (ms * SR / 1000f).toInt())
    val fb = 0.4f
    val out = ShortArray(src.size + d * 5)
    val buf = FloatArray(d)
    var idx = 0
    for (i in out.indices) {
        val x = if (i < src.size) src[i].toFloat() else 0f
        val dv = buf[idx]
        out[i] = clip16(x + mix * dv)
        buf[idx] = x + fb * dv
        idx++
        if (idx == d) idx = 0
    }
    return out
}

/** ホールリバーブ：長めの残響を持つ、8本のコム＋4本のオールパスの構成。 */
fun applyReverb(src: ShortArray, mix: Float): ShortArray {
    val tail = SR * 3
    val out = ShortArray(src.size + tail)
    val scale = SR / 44100f * 2.0f
    val combLen = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    val apLen = intArrayOf(556, 441, 341, 225)
    val nc = combLen.size
    val na = apLen.size
    val cb = Array(nc) { FloatArray(maxOf(8, (combLen[it] * scale).toInt())) }
    val ci = IntArray(nc)
    val cf = FloatArray(nc)
    val ab = Array(na) { FloatArray(maxOf(8, (apLen[it] * SR / 44100f).toInt())) }
    val ai = IntArray(na)
    val fb = 0.90f
    val damp = 0.25f
    for (i in out.indices) {
        val x = if (i < src.size) src[i] / 32768f else 0f
        val inp = x * 0.015f
        var sum = 0f
        for (c in 0 until nc) {
            val buf = cb[c]
            val y = buf[ci[c]]
            cf[c] = y * (1f - damp) + cf[c] * damp
            buf[ci[c]] = inp + cf[c] * fb
            ci[c]++
            if (ci[c] == buf.size) ci[c] = 0
            sum += y
        }
        for (a in 0 until na) {
            val buf = ab[a]
            val bo = buf[ai[a]]
            val o = -sum + bo
            buf[ai[a]] = sum + bo * 0.5f
            ai[a]++
            if (ai[a] == buf.size) ai[a] = 0
            sum = o
        }
        out[i] = clip16((x + mix * sum * 3f) * 32768f)
    }
    return out
}

/** 重い処理を含むので、メインスレッドでは呼ばないこと。 */
fun makePlay(
    pcm: ShortArray?,
    vol: Float,
    shiftMs: Float,
    mute: Boolean,
    pan: Float,
    comp: Float,
    dMix: Float,
    dMs: Float,
    rMix: Float = 0f,
): PlayTrack {
    var p = pcm
    if (p != null && !mute) {
        if (comp > 0.01f) p = applyComp(p, comp)
        if (dMix > 0.01f) p = applyDelay(p, dMix, dMs)
        if (rMix > 0.01f) p = applyReverb(p, rMix)
    }
    return PlayTrack(p, vol, (shiftMs * SR / 1000f).toInt(), mute, pan)
}

object Mixer {
    fun endFrame(tracks: List<PlayTrack>): Int {
        var e = 0
        for (x in tracks) {
            val p = x.pcm ?: continue
            if (x.mute) continue
            e = maxOf(e, p.size - x.shiftFrames)
        }
        return e
    }

    /** out には左右交互（L, R, L, R…）で n フレーム分を書く。 */
    fun mix(tracks: List<PlayTrack>, start: Int, out: ShortArray, n: Int) {
        for (i in 0 until n) {
            var l = 0f
            var r = 0f
            val pos = start + i
            for (x in tracks) {
                val p = x.pcm ?: continue
                if (x.mute) continue
                val idx = pos + x.shiftFrames
                if (idx >= 0 && idx < p.size) {
                    val v = p[idx].toFloat()
                    l += v * x.gl
                    r += v * x.gr
                }
            }
            out[2 * i] = clip16(l)
            out[2 * i + 1] = clip16(r)
        }
    }

    /** 左右交互のステレオで返す（長さは フレーム数×2）。 */
    fun render(tracks: List<PlayTrack>): ShortArray? {
        val end = endFrame(tracks)
        if (end <= 0) return null
        val out = ShortArray(end * 2)
        val buf = ShortArray(4800 * 2)
        var pos = 0
        while (pos < end) {
            val n = minOf(4800, end - pos)
            mix(tracks, pos, buf, n)
            System.arraycopy(buf, 0, out, pos * 2, n * 2)
            pos += n
        }
        return out
    }
}

object Wav {
    fun encode(p: ShortArray, sr: Int, ch: Int = 1): ByteArray {
        val data = p.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray())
        b.putInt(36 + data)
        b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray())
        b.putInt(16)
        b.putShort(1.toShort())
        b.putShort(ch.toShort())
        b.putInt(sr)
        b.putInt(sr * 2 * ch)
        b.putShort((2 * ch).toShort())
        b.putShort(16.toShort())
        b.put("data".toByteArray())
        b.putInt(data)
        b.asShortBuffer().put(p)
        return b.array()
    }

    /** トラックのWAV（モノラル）を読む。 */
    fun decode(bytes: ByteArray): Pair<Int, ShortArray> {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val sr = b.getInt(24)
        val n = (bytes.size - 44) / 2
        val s = ShortArray(maxOf(0, n))
        b.position(44)
        b.asShortBuffer().get(s)
        return Pair(sr, s)
    }
}

fun pcmToBytes(p: ShortArray): ByteArray {
    val b = ByteBuffer.allocate(p.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    b.asShortBuffer().put(p)
    return b.array()
}

fun pcmFromBytes(bytes: ByteArray): ShortArray {
    val s = ShortArray(bytes.size / 2)
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s)
    return s
}

fun resample(p: ShortArray, from: Int, to: Int): ShortArray {
    if (from == to || p.isEmpty()) return p
    val n = (p.size.toLong() * to / from).toInt()
    val o = ShortArray(n)
    val r = from.toDouble() / to
    for (i in 0 until n) {
        val x = i * r
        val i0 = minOf(x.toInt(), p.size - 1)
        val i1 = minOf(i0 + 1, p.size - 1)
        val f = x - x.toInt()
        o[i] = (p[i0] * (1 - f) + p[i1] * f).toInt().toShort()
    }
    return o
}
