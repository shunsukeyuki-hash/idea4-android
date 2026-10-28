package com.shunsuke.idea4

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

const val OUT_LIMIT = 30000f
const val IN_LIMIT = 29000f

private fun clip16(v: Float): Short {
    val x = if (v > 32767f) 32767f else if (v < -32768f) -32768f else v
    return x.toInt().toShort()
}

/** 1チャンネル分のエフェクト（コンプレッサー・ディレイ・リバーブ）。状態を持つので、チャンネルごとに1つ使う。 */
class Fx {
    private var cComp = 0f
    private var cThr = 1f
    private var cExpo = 0f
    private var cMakeup = 1f
    private var cDMix = 0f
    private var cDFrames = 1
    private var cRMix = 0f

    // コンプレッサーの状態
    private val atk = exp(-1f / (0.005f * SR))
    private val rel = exp(-1f / (0.12f * SR))
    private var env = 0f
    private var gain = 1f
    private var cnt = 0

    // ディレイの状態（最大 1 秒）
    private val dBuf = FloatArray(SR)
    private var dW = 0
    private var dDirty = false

    // リバーブの状態
    private val rScale = SR / 44100f * 2.0f
    private val combLen = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val apLen = intArrayOf(556, 441, 341, 225)
    private val cb = Array(combLen.size) { FloatArray(maxOf(8, (combLen[it] * rScale).toInt())) }
    private val ci = IntArray(combLen.size)
    private val cf = FloatArray(combLen.size)
    private val ab = Array(apLen.size) { FloatArray(maxOf(8, (apLen[it] * SR / 44100f).toInt())) }
    private val ai = IntArray(apLen.size)
    private var rDirty = false

    fun prepare(comp: Float, dMix: Float, dMs: Float, rMix: Float) {
        cComp = comp
        if (cComp > 0.01f) {
            cThr = maxOf(0.05f, 0.5f - 0.4f * cComp)
            val ratio = 1f + 7f * cComp
            cExpo = 1f - 1f / ratio
            cMakeup = cThr.pow(-cExpo * 0.5f)
        }
        cDMix = dMix
        cDFrames = (dMs * SR / 1000f).toInt().coerceIn(1, SR - 1)
        cRMix = rMix
        if (cDMix <= 0.01f && dDirty) {
            java.util.Arrays.fill(dBuf, 0f)
            dDirty = false
        }
        if (cRMix <= 0.01f && rDirty) {
            for (b in cb) java.util.Arrays.fill(b, 0f)
            for (b in ab) java.util.Arrays.fill(b, 0f)
            java.util.Arrays.fill(cf, 0f)
            rDirty = false
        }
    }

    fun process(x0: Float): Float {
        var x = x0
        if (cComp > 0.01f) {
            val a = abs(x)
            env = if (a > env) a + atk * (env - a) else a + rel * (env - a)
            if ((cnt and 31) == 0) {
                gain = if (env > cThr) (cThr / env).pow(cExpo) else 1f
            }
            cnt++
            x *= gain * cMakeup
        }
        if (cDMix > 0.01f) {
            dDirty = true
            var r = dW - cDFrames
            if (r < 0) r += dBuf.size
            val dv = dBuf[r]
            dBuf[dW] = x + 0.4f * dv
            dW++
            if (dW == dBuf.size) dW = 0
            x += cDMix * dv
        }
        if (cRMix > 0.01f) {
            rDirty = true
            val inp = x * 0.015f
            var sum = 0f
            for (c in cb.indices) {
                val buf = cb[c]
                val k = ci[c]
                val y = buf[k]
                cf[c] = y * 0.75f + cf[c] * 0.25f
                buf[k] = inp + cf[c] * 0.90f
                ci[c] = if (k + 1 == buf.size) 0 else k + 1
                sum += y
            }
            for (c in ab.indices) {
                val buf = ab[c]
                val k = ai[c]
                val bo = buf[k]
                val o = -sum + bo
                buf[k] = sum + bo * 0.5f
                ai[c] = if (k + 1 == buf.size) 0 else k + 1
                sum = o
            }
            x += cRMix * sum * 3f
        }
        return x
    }
}

/**
 * 再生・ミックス用の1トラック分。音量・パン・ミュート・エフェクトの値は、
 * 再生中に書き換えるとすぐ音に反映される（エフェクトは1サンプルずつリアルタイムで処理する）。
 * pcmR があればステレオのトラック（パンは左右のバランスとして働く）、なければモノラル。
 * shiftFrames が正なら、録音の頭をその分だけ飛ばす。pan は -1（左）〜1（右）。
 */
class PlayTrack(
    val pcm: ShortArray?,
    vol: Float,
    val shiftFrames: Int,
    mute: Boolean,
    pan: Float = 0f,
    val pcmR: ShortArray? = null,
) {
    @Volatile var vol: Float = vol
    @Volatile var mute: Boolean = mute
    @Volatile var pan: Float = pan
    @Volatile var comp: Float = 0f
    @Volatile var dMix: Float = 0f
    @Volatile var dMs: Float = 250f
    @Volatile var rMix: Float = 0f

    // ---- ミキサーが使う内部の値 ----
    var pl = 0f
    var pr = 0f
    var tl = 0f
    var tr = 0f
    var outL = 0f
    var outR = 0f
    private var inited = false

    private val fxL = Fx()
    private val fxR: Fx? = if (pcmR != null) Fx() else null

    /** 1チャンクの処理を始める前に、現在の設定を読み込む。 */
    fun prepare() {
        val m = mute
        val v = vol
        val p0 = pan
        val p = if (p0 < -1f) -1f else if (p0 > 1f) 1f else p0
        if (pcmR != null) {
            // ステレオ：パンは左右のバランス
            val half = (Math.PI.toFloat() / 2f)
            val gl = if (p > 0f) cos(p * half) else 1f
            val gr = if (p < 0f) cos(-p * half) else 1f
            tl = if (m) 0f else v * 0.75f * gl
            tr = if (m) 0f else v * 0.75f * gr
        } else {
            val a = (p + 1f) / 2f * (Math.PI.toFloat() / 2f)
            tl = if (m) 0f else v * 0.85f * cos(a)
            tr = if (m) 0f else v * 0.85f * sin(a)
        }
        if (!inited) {
            pl = tl
            pr = tr
            inited = true
        }
        val c = comp
        val d = dMix
        val ms = dMs
        val r = rMix
        fxL.prepare(c, d, ms, r)
        fxR?.prepare(c, d, ms, r)
    }

    /** 位置 pos のサンプルを、エフェクトを通した値（-1〜1）で outL / outR に入れる。 */
    fun process(pos: Int) {
        val idx = pos + shiftFrames
        val p = pcm
        val l = if (p != null && idx >= 0 && idx < p.size) p[idx] / 32768f else 0f
        val fr = fxR
        if (fr != null) {
            val q = pcmR
            val r = if (q != null && idx >= 0 && idx < q.size) q[idx] / 32768f else 0f
            outL = fxL.process(l)
            outR = fr.process(r)
        } else {
            val y = fxL.process(l)
            outL = y
            outR = y
        }
    }

    /** 音の終わりのあとに、余韻として鳴らし続ける長さ（フレーム）。 */
    fun tailFrames(): Int {
        if (pcm == null) return 0
        if (rMix > 0.01f) return SR * 3
        if (dMix > 0.01f) return (dMs * SR / 1000f).toInt() * 5
        return 0
    }
}

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
    pcmR: ShortArray? = null,
): PlayTrack {
    val t = PlayTrack(pcm, vol, (shiftMs * SR / 1000f).toInt(), mute, pan, pcmR)
    t.comp = comp
    t.dMix = dMix
    t.dMs = dMs
    t.rMix = rMix
    return t
}

/**
 * リミッター。ピークが threshold を超えそうなとき、瞬時に音量を下げて、ゆっくり戻す。
 * threshold は 16bit の値（最大 32767）。
 */
class Limiter(private val threshold: Float) {
    private var g = 1f
    private val relCoef = 1f - exp(-1f / (0.15f * SR))

    fun gainFor(peak: Float): Float {
        val need = if (peak > threshold) threshold / peak else 1f
        val up = g + (1f - g) * relCoef
        g = if (need < up) need else up
        return g
    }

    fun processMono(buf: ShortArray, n: Int) {
        for (i in 0 until n) {
            val v = buf[i].toFloat()
            buf[i] = clip16(v * gainFor(abs(v)))
        }
    }

    /** 左右で同じ量だけ下げる（ステレオの定位が崩れないように）。 */
    fun processStereo(l: ShortArray, r: ShortArray, n: Int) {
        for (i in 0 until n) {
            val a = l[i].toFloat()
            val b = r[i].toFloat()
            val g = gainFor(maxOf(abs(a), abs(b)))
            l[i] = clip16(a * g)
            r[i] = clip16(b * g)
        }
    }
}

object Mixer {
    /** 録音の音そのものが終わる位置。ミュート中のトラックも含める（途中で戻せるように）。 */
    fun endFrame(tracks: List<PlayTrack>): Int {
        var e = 0
        for (x in tracks) {
            val p = x.pcm ?: continue
            e = maxOf(e, p.size - x.shiftFrames)
        }
        return e
    }

    /** エフェクトの余韻まで含めた長さ。 */
    fun totalFrames(tracks: List<PlayTrack>): Int {
        val e = endFrame(tracks)
        if (e <= 0) return 0
        var t = 0
        for (x in tracks) t = maxOf(t, x.tailFrames())
        return e + t
    }

    /** out には左右交互（L, R, L, R…）で n フレーム分を書く。 */
    fun mix(tracks: List<PlayTrack>, start: Int, out: ShortArray, n: Int, lim: Limiter? = null) {
        val accL = FloatArray(n)
        val accR = FloatArray(n)
        for (x in tracks) {
            if (x.pcm == null) continue
            x.prepare()
            for (i in 0 until n) {
                val f = (i + 1) / n.toFloat()
                x.process(start + i)
                accL[i] += x.outL * 32768f * (x.pl + (x.tl - x.pl) * f)
                accR[i] += x.outR * 32768f * (x.pr + (x.tr - x.pr) * f)
            }
            x.pl = x.tl
            x.pr = x.tr
        }
        for (i in 0 until n) {
            var l = accL[i]
            var r = accR[i]
            if (lim != null) {
                val g = lim.gainFor(maxOf(abs(l), abs(r)))
                l *= g
                r *= g
            }
            out[2 * i] = clip16(l)
            out[2 * i + 1] = clip16(r)
        }
    }

    /** ミックスをそのままWAV（ステレオ）のバイト列で返す。長いときにメモリを節約するため、直接書き込む。 */
    fun renderWav(tracks: List<PlayTrack>): ByteArray? {
        val end = totalFrames(tracks)
        if (end <= 0) return null
        val data = end * 4
        val out = ByteArray(44 + data)
        val h = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray())
        h.putInt(36 + data)
        h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray())
        h.putInt(16)
        h.putShort(1.toShort())
        h.putShort(2.toShort())
        h.putInt(SR)
        h.putInt(SR * 4)
        h.putShort(4.toShort())
        h.putShort(16.toShort())
        h.put("data".toByteArray())
        h.putInt(data)
        val buf = ShortArray(4800 * 2)
        val lim = Limiter(OUT_LIMIT)
        var pos = 0
        var o = 44
        while (pos < end) {
            val n = minOf(4800, end - pos)
            mix(tracks, pos, buf, n, lim)
            for (i in 0 until n * 2) {
                val v = buf[i].toInt()
                out[o++] = (v and 0xFF).toByte()
                out[o++] = ((v shr 8) and 0xFF).toByte()
            }
            pos += n
        }
        return out
    }

    /** 左右交互のステレオで返す（長さは フレーム数×2）。 */
    fun render(tracks: List<PlayTrack>): ShortArray? {
        val end = totalFrames(tracks)
        if (end <= 0) return null
        val out = ShortArray(end * 2)
        val buf = ShortArray(4800 * 2)
        val lim = Limiter(OUT_LIMIT)
        var pos = 0
        while (pos < end) {
            val n = minOf(4800, end - pos)
            mix(tracks, pos, buf, n, lim)
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

    /** WAV のチャンネル数（1 か 2）を返す。 */
    fun channels(bytes: ByteArray): Int =
        if (bytes.size > 24) ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(22).toInt() else 1

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

/** 左右のデータを交互に並べる（L, R, L, R…）。 */
fun interleave(l: ShortArray, r: ShortArray): ShortArray {
    val n = minOf(l.size, r.size)
    val o = ShortArray(n * 2)
    for (i in 0 until n) {
        o[2 * i] = l[i]
        o[2 * i + 1] = r[i]
    }
    return o
}

/** 交互に並んだステレオを、左右に分ける。 */
fun splitStereo(x: ShortArray): Pair<ShortArray, ShortArray> {
    val n = x.size / 2
    val l = ShortArray(n)
    val r = ShortArray(n)
    for (i in 0 until n) {
        l[i] = x[2 * i]
        r[i] = x[2 * i + 1]
    }
    return Pair(l, r)
}
