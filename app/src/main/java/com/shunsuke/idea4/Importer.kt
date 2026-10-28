package com.shunsuke.idea4

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteOrder

/** 外部の音声ファイル（wav / aac / mp3 など）を読み込む。 */
object Importer {
    /** data は交互に並んだ 16bit（ch が 2 なら L, R, L, R…）。 */
    class Result(val sr: Int, val ch: Int, val data: ShortArray)

    private class Buf(private val cap: Int) {
        var a = ShortArray(1 shl 20)
        var n = 0
        val full: Boolean get() = n >= cap
        fun add(v: Short) {
            if (n >= cap) return
            if (n == a.size) a = a.copyOf(minOf(a.size * 2, cap))
            a[n++] = v
        }
        fun done(): ShortArray = a.copyOf(n)
    }

    fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0) ?: ""
                    val dot = n.lastIndexOf('.')
                    return if (dot > 0) n.substring(0, dot) else n
                }
            }
        } catch (e: Exception) {
        }
        return ""
    }

    fun read(ctx: Context, uri: Uri, maxSec: Int): Result {
        val wav = try {
            ctx.contentResolver.openInputStream(uri)?.use { readWav(it, maxSec) }
        } catch (e: Exception) {
            null
        }
        if (wav != null) return wav
        return readCodec(ctx, uri, maxSec)
    }

    private fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, o: Int): Int = le16(b, o) or (le16(b, o + 2) shl 16)

    /** 読めない形式のときは null を返す（そのあと、他の方法で読む）。 */
    private fun readWav(raw: InputStream, maxSec: Int): Result? {
        val ins = BufferedInputStream(raw, 1 shl 16)
        val head = ByteArray(12)
        if (readFull(ins, head, 12) < 12) return null
        if (String(head, 0, 4) != "RIFF" || String(head, 8, 4) != "WAVE") return null
        var fmtTag = 0
        var ch = 0
        var sr = 0
        var bits = 0
        var haveFmt = false
        val ch8 = ByteArray(8)
        while (true) {
            if (readFull(ins, ch8, 8) < 8) return null
            val id = String(ch8, 0, 4)
            var size = le32(ch8, 4).toLong() and 0xFFFFFFFFL
            if (id == "fmt ") {
                val f = ByteArray(minOf(size, 64L).toInt())
                if (readFull(ins, f, f.size) < f.size) return null
                fmtTag = le16(f, 0)
                ch = le16(f, 2)
                sr = le32(f, 4)
                bits = le16(f, 14)
                if (fmtTag == 0xFFFE && f.size >= 26) fmtTag = le16(f, 24)
                haveFmt = true
                var rest = size - f.size
                if (size % 2L == 1L) rest++
                skipFully(ins, rest)
            } else if (id == "data") {
                if (!haveFmt || ch < 1 || sr <= 0) return null
                if (!((fmtTag == 1 && (bits == 8 || bits == 16 || bits == 24 || bits == 32)) || (fmtTag == 3 && bits == 32))) return null
                val oc = minOf(ch, 2)
                val buf = Buf(maxSec * sr * oc)
                val bytesPer = bits / 8
                val frame = ByteArray(bytesPer * ch)
                var left = if (size == 0L || size == 0xFFFFFFFFL) Long.MAX_VALUE else size
                val chunk = ByteArray(bytesPer * ch * 4096)
                while (left > 0 && !buf.full) {
                    val want = minOf(chunk.size.toLong(), left).toInt()
                    val got = ins.read(chunk, 0, want)
                    if (got <= 0) break
                    left -= got
                    val frames = got / frame.size
                    for (fr in 0 until frames) {
                        for (c in 0 until oc) {
                            val o = fr * frame.size + c * bytesPer
                            val v: Int = when {
                                fmtTag == 3 -> {
                                    val fl = java.lang.Float.intBitsToFloat(le32(chunk, o))
                                    (fl.coerceIn(-1f, 1f) * 32767f).toInt()
                                }
                                bits == 8 -> ((chunk[o].toInt() and 0xFF) - 128) shl 8
                                bits == 16 -> le16(chunk, o).toShort().toInt()
                                bits == 24 -> ((chunk[o + 1].toInt() and 0xFF) or (chunk[o + 2].toInt() shl 8))
                                else -> le32(chunk, o) shr 16
                            }
                            buf.add(v.coerceIn(-32768, 32767).toShort())
                        }
                    }
                }
                return Result(sr, oc, buf.done())
            } else {
                if (size % 2L == 1L) size++
                skipFully(ins, size)
            }
        }
    }

    private fun readFull(ins: InputStream, b: ByteArray, n: Int): Int {
        var g = 0
        while (g < n) {
            val r = ins.read(b, g, n - g)
            if (r <= 0) break
            g += r
        }
        return g
    }

    private fun skipFully(ins: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = ins.skip(left)
            if (s <= 0) {
                if (ins.read() < 0) return
                left--
            } else {
                left -= s
            }
        }
    }

    private fun readCodec(ctx: Context, uri: Uri, maxSec: Int): Result {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var tIdx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    tIdx = i
                    fmt = f
                    break
                }
            }
            if (tIdx < 0 || fmt == null) throw IllegalStateException("音声が見つかりませんでした")
            ex.selectTrack(tIdx)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var enc = 2
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            // 出力の形式が分かってから大きさを決める（最初は仮の上限）
            var buf = Buf(maxSec * sr * minOf(ch, 2))
            while (!outDone && !buf.full) {
                if (!inDone) {
                    val ii = c.dequeueInputBuffer(10000)
                    if (ii >= 0) {
                        val ib = c.getInputBuffer(ii)!!
                        val sz = ex.readSampleData(ib, 0)
                        if (sz < 0) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            c.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 10000)
                if (oi >= 0) {
                    val ob = c.getOutputBuffer(oi)!!
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    ob.order(ByteOrder.LITTLE_ENDIAN)
                    val oc = minOf(ch, 2)
                    if (enc == 4) {
                        val fb = ob.asFloatBuffer()
                        val frames = fb.remaining() / ch
                        for (fr in 0 until frames) {
                            for (k in 0 until ch) {
                                val v = fb.get()
                                if (k < oc) buf.add((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                            }
                        }
                    } else {
                        val sb = ob.asShortBuffer()
                        val frames = sb.remaining() / ch
                        for (fr in 0 until frames) {
                            for (k in 0 until ch) {
                                val v = sb.get()
                                if (k < oc) buf.add(v)
                            }
                        }
                    }
                    c.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat
                    val nsr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val nch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    enc = if (f.containsKey("pcm-encoding")) f.getInteger("pcm-encoding") else 2
                    if (nsr != sr || nch != ch) {
                        sr = nsr
                        ch = nch
                        buf = Buf(maxSec * sr * minOf(ch, 2))
                    }
                }
            }
            return Result(sr, minOf(ch, 2), buf.done())
        } finally {
            try {
                codec?.stop()
            } catch (e: Exception) {
            }
            try {
                codec?.release()
            } catch (e: Exception) {
            }
            ex.release()
        }
    }
}
