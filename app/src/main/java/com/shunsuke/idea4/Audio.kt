package com.shunsuke.idea4

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 再生・ミックス用の1トラック分のデータ。shiftFrames が正なら、録音の頭をその分だけ飛ばす。 */
class PlayTrack(
    val pcm: ShortArray?,
    val vol: Float,
    val shiftFrames: Int,
    val mute: Boolean,
)

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

    fun mix(tracks: List<PlayTrack>, start: Int, out: ShortArray, n: Int) {
        for (i in 0 until n) {
            var s = 0f
            val pos = start + i
            for (x in tracks) {
                val p = x.pcm ?: continue
                if (x.mute) continue
                val idx = pos + x.shiftFrames
                if (idx >= 0 && idx < p.size) s += p[idx].toFloat() * x.vol
            }
            var v = s * 0.7f
            if (v > 32767f) v = 32767f
            if (v < -32768f) v = -32768f
            out[i] = v.toInt().toShort()
        }
    }

    fun render(tracks: List<PlayTrack>): ShortArray? {
        val end = endFrame(tracks)
        if (end <= 0) return null
        val out = ShortArray(end)
        val buf = ShortArray(4800)
        var pos = 0
        while (pos < end) {
            val n = minOf(buf.size, end - pos)
            mix(tracks, pos, buf, n)
            System.arraycopy(buf, 0, out, pos, n)
            pos += n
        }
        return out
    }
}

object Wav {
    fun encode(p: ShortArray, sr: Int): ByteArray {
        val data = p.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray())
        b.putInt(36 + data)
        b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray())
        b.putInt(16)
        b.putShort(1.toShort())
        b.putShort(1.toShort())
        b.putInt(sr)
        b.putInt(sr * 2)
        b.putShort(2.toShort())
        b.putShort(16.toShort())
        b.put("data".toByteArray())
        b.putInt(data)
        b.asShortBuffer().put(p)
        return b.array()
    }

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
