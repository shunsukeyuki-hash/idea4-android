package com.shunsuke.idea4

import android.app.Application
import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import java.time.LocalDate
import kotlin.concurrent.thread

val BASE_TAGS = listOf("ギター", "鼻歌", "リズム", "歌詞", "ひらめき")

class TrackState {
    var pcm by mutableStateOf<ShortArray?>(null)
    var pcmR by mutableStateOf<ShortArray?>(null)
    var vol by mutableFloatStateOf(0.9f)
    var shiftMs by mutableFloatStateOf(0f)
    var adjMs by mutableFloatStateOf(0f)
    var mute by mutableStateOf(false)
    var solo by mutableStateOf(false)
    var pan by mutableFloatStateOf(0f)
    var comp by mutableFloatStateOf(0f)
    var dMix by mutableFloatStateOf(0f)
    var dMs by mutableFloatStateOf(250f)
    var rMix by mutableFloatStateOf(0f)
    var name by mutableStateOf("")
}

/** 再生の準備に使う、トラック設定の写し。エフェクトの計算は別スレッドで行う。 */
private class Snap(
    val pcm: ShortArray?,
    val vol: Float,
    val shiftMs: Float,
    val mute: Boolean,
    val pan: Float,
    val comp: Float,
    val dMix: Float,
    val dMs: Float,
    val rMix: Float,
    val pcmR: ShortArray?,
) {
    fun build(): PlayTrack = makePlay(pcm, vol, shiftMs, mute, pan, comp, dMix, dMs, rMix, pcmR)
}

class AppVm(app: Application) : AndroidViewModel(app) {
    private val appCtx = app.applicationContext
    val store = Store(appCtx)
    private val engine = Engine(appCtx)
    private val main = Handler(Looper.getMainLooper())

    val tracks = List(N_TRACKS) { TrackState() }
    var ideas by mutableStateOf<List<Idea>>(emptyList())
    var current by mutableStateOf<Idea?>(null)
    var memo by mutableStateOf("")
    val selTags = mutableStateListOf<String>()

    var tab by mutableIntStateOf(0)
    var message by mutableStateOf("")
    var recIdx by mutableIntStateOf(-1)
    var level by mutableFloatStateOf(0f)
    var levelR by mutableFloatStateOf(0f)
    var stereoIn by mutableStateOf(store.stereoIn)
    var recStereo by mutableStateOf(false)
    var armIdx by mutableIntStateOf(-1)
    var startSec by mutableDoubleStateOf(0.0)
    var shown by mutableIntStateOf(1)
    var masterVol by mutableFloatStateOf(store.masterVol)
    var playing by mutableStateOf(false)
    var previewId by mutableStateOf<String?>(null)
    var timeSec by mutableDoubleStateOf(0.0)

    var extraMs by mutableFloatStateOf(store.extraMs)
    var latencyMs by mutableFloatStateOf(store.latencyMs)
    var inDevId by mutableIntStateOf(store.inDevId)
    var outDevId by mutableIntStateOf(store.outDevId)
    var inputs by mutableStateOf<List<AudioDeviceInfo>>(emptyList())
    var outputs by mutableStateOf<List<AudioDeviceInfo>>(emptyList())

    var syncStatus by mutableStateOf("")
    var syncing by mutableStateOf(false)

    private var session: Session? = null

    // 再生や録音を始める操作のたびに増やし、古い準備が遅れて届いたときに捨てるための番号
    private var token = 0

    init {
        engine.master = masterVol
        refresh()
        refreshDevices()
    }

    fun say(t: String) {
        message = t
    }

    fun refresh() {
        ideas = store.list()
    }

    fun refreshDevices() {
        inputs = engine.inputs()
        outputs = engine.outputs()
    }

    private fun inDev(): AudioDeviceInfo? {
        val chosen = if (inDevId >= 0) engine.findInput(inDevId) else null
        return chosen ?: engine.builtinMic()
    }

    private fun outDev(): AudioDeviceInfo? = if (outDevId >= 0) engine.findOutput(outDevId) else null

    private fun io(block: () -> Unit) {
        thread {
            try {
                block()
            } catch (e: Throwable) {
                main.post {
                    say("エラー：" + (e.message ?: "原因不明"))
                    if (session == null) {
                        recIdx = -1
                        playing = false
                        previewId = null
                    }
                }
            }
        }
    }

    // ---------- 再生 ----------
    private fun snaps(skip: Int): List<Snap> {
        val anySolo = tracks.any { it.solo }
        return tracks.mapIndexed { k, t ->
            Snap(
                if (k == skip) null else t.pcm,
                t.vol, t.shiftMs + t.adjMs, t.mute || (anySolo && !t.solo), t.pan, t.comp, t.dMix, t.dMs, t.rMix,
                if (k == skip) null else t.pcmR,
            )
        }
    }

    // ---------- シーク ----------
    /** 全体の長さ（秒）。いちばん長いトラックに合わせる。 */
    fun lengthSec(): Double {
        var m = 0
        for (t in tracks) {
            val p = t.pcm ?: continue
            if (p.size > m) m = p.size
        }
        return m.toDouble() / SR
    }

    fun seekTo(sec: Double) {
        if (recIdx >= 0) return
        val x = sec.coerceAtLeast(0.0)
        startSec = x
        if (playing && previewId == null && session != null) play()
    }

    // ---------- 再生中のリアルタイム操作 ----------
    private var liveList: List<PlayTrack>? = null

    /** 画面の操作に合わせて、再生中のトラックの設定を書き換える。 */
    fun syncLive() {
        val l = liveList ?: return
        val anySolo = tracks.any { it.solo }
        for (k in l.indices) {
            val t = tracks.getOrNull(k) ?: continue
            val p = l[k]
            p.vol = t.vol
            p.pan = t.pan
            p.mute = t.mute || (anySolo && !t.solo)
            p.comp = t.comp
            p.dMix = t.dMix
            p.dMs = t.dMs
            p.rMix = t.rMix
        }
    }

    /** どれかの値が変わったことを知るための一覧。 */
    fun liveSignature(): List<Float> =
        tracks.flatMap {
            listOf(
                it.vol, it.pan, if (it.mute) 1f else 0f, if (it.solo) 1f else 0f,
                it.comp, it.dMix, it.dMs, it.rMix,
            )
        }

    /** 外部の音声ファイル（wav / aac / mp3 など）を、トラックに読み込む。ステレオはそのまま扱う。 */
    fun importFile(uri: android.net.Uri, i: Int) {
        if (recIdx >= 0) {
            say("録音を止めてから読み込んでください。")
            return
        }
        stopAll()
        say("読み込み中…")
        val from = (startSec * SR).toInt().coerceIn(0, MAX_SEC * SR - SR)
        io {
            val r = Importer.read(appCtx, uri, MAX_SEC)
            if (r.data.isEmpty()) throw IllegalStateException("音が読み込めませんでした")
            val name = Importer.displayName(appCtx, uri)
            val maxLen = MAX_SEC * SR - from
            var left: ShortArray
            var right: ShortArray? = null
            if (r.ch == 2) {
                val sp = splitStereo(r.data)
                left = resample(sp.first, r.sr, SR)
                right = resample(sp.second, r.sr, SR)
            } else {
                left = resample(r.data, r.sr, SR)
            }
            if (left.size > maxLen) left = left.copyOf(maxLen)
            val len = from + left.size
            val outL = ShortArray(len)
            System.arraycopy(left, 0, outL, from, left.size)
            val outR: ShortArray? = if (right != null) {
                val rr = ShortArray(len)
                System.arraycopy(right, 0, rr, from, minOf(right.size, left.size))
                rr
            } else {
                null
            }
            main.post {
                val t = tracks[i]
                t.pcm = outL
                t.pcmR = outR
                t.shiftMs = 0f
                t.adjMs = 0f
                if (t.name.isBlank() && name.isNotBlank()) t.name = name.take(16)
                if (i + 1 > shown) shown = i + 1
                say("読み込みました（" + (if (outR != null) "ステレオ" else "モノラル") + "）。保存するのを忘れずに。")
            }
        }
    }

    fun addTrack() {
        if (shown < N_TRACKS) shown++
    }

    fun toggleArm(i: Int) {
        if (recIdx >= 0) return
        armIdx = if (armIdx == i) -1 else i
    }

    /** 下の録音ボタン：準備したトラックで録音を始める（録音中なら止める） */
    fun recordButton(requestMic: (() -> Unit) -> Unit) {
        if (recIdx >= 0) {
            stopRec()
            return
        }
        val i = armIdx
        if (i < 0) {
            say("録音するトラックの ● を押して、録音の準備をしてください。")
            return
        }
        requestMic { startRec(i) }
    }

    private fun startPlayback(list: List<PlayTrack>, live: Boolean = true) {
        val end = Mixer.endFrame(list)
        if (end <= 0) {
            previewId = null
            say("再生できるトラックがありません。")
            return
        }
        var from = (startSec * SR).toInt()
        if (from >= end) {
            from = 0
            startSec = 0.0
        }
        engine.startAt = if (live) from else 0
        playing = true
        timeSec = if (live) from.toDouble() / SR else 0.0
        liveList = if (live) list else null
        syncLive()
        var me: Session? = null
        me = engine.start(
            list, false, end, null, outDev(),
            { t -> if (session === me) timeSec = t },
            { _, err ->
                if (session === me) {
                    liveList = null
                    session = null
                    playing = false
                    previewId = null
                    timeSec = 0.0
                    if (err != null) say(err)
                }
            },
        )
        session = me
    }

    fun play() {
        if (recIdx >= 0) return
        stopAll()
        val my = token
        val snap = snaps(-1)
        if (snap.none { it.pcm != null && !it.mute }) {
            say("再生するトラックがありません。")
            return
        }
        say("")
        io {
            val list = snap.map { it.build() }
            main.post {
                if (token == my) startPlayback(list)
            }
        }
    }

    fun stopAll() {
        token++
        val s = session
        if (s != null) s.stop = true
        session = null
        liveList = null
        playing = false
        previewId = null
        timeSec = 0.0
    }

    // ---------- 録音 ----------
    fun startRec(i: Int) {
        if (recIdx >= 0) {
            stopRec()
            return
        }
        // 再生中に録音を押したら、今の位置から録音を始める
        if (playing && previewId == null) startSec = timeSec
        stopAll()
        val my = token
        val snap = snaps(i)
        val shift = latencyMs + extraMs
        val inD = inDev()
        val outD = outDev()
        val from = (startSec * SR).toInt().coerceIn(0, MAX_SEC * SR - SR)
        recIdx = i
        playing = true
        timeSec = from.toDouble() / SR
        level = 0f
        levelR = 0f
        recStereo = stereoIn
        engine.stereoIn = stereoIn
        engine.onLevel = { l, r ->
            if (recIdx >= 0) {
                level = maxOf(l, level * 0.85f)
                levelR = maxOf(r, levelR * 0.85f)
            }
        }
        say("録音中（最大" + (MAX_SEC / 60) + "分）。他のトラックを聴きながら録れます。")
        io {
            val list = snap.map { it.build() }
            main.post {
                if (token != my) return@post
                var me: Session? = null
                liveList = list
                syncLive()
                engine.startAt = from
                me = engine.start(
                    list, true, MAX_SEC * SR, inD, outD,
                    { t -> if (session === me) timeSec = t },
                    { pcm, err ->
                        if (session === me) {
                            liveList = null
                            session = null
                            val idx = recIdx
                            recIdx = -1
                            playing = false
                            level = 0f
                            levelR = 0f
                            val newR = engine.capturedR
                            if (err != null) {
                                say(err)
                            } else if (pcm != null && pcm.size > SR / 5 && idx >= 0) {
                                val t = tracks[idx]
                                val old = t.pcm
                                if (old == null) {
                                    val merged = ShortArray(from + pcm.size)
                                    System.arraycopy(pcm, 0, merged, from, pcm.size)
                                    t.pcm = merged
                                    if (newR != null) {
                                        val mr = ShortArray(from + newR.size)
                                        System.arraycopy(newR, 0, mr, from, newR.size)
                                        t.pcmR = mr
                                    } else {
                                        t.pcmR = null
                                    }
                                    t.shiftMs = shift
                                    t.adjMs = 0f
                                } else {
                                    // 途中からの録音：その位置から先を、新しい録音で置き換える
                                    val merged = ShortArray(maxOf(old.size, from + pcm.size))
                                    System.arraycopy(old, 0, merged, 0, old.size)
                                    System.arraycopy(pcm, 0, merged, from, pcm.size)
                                    val oldR = t.pcmR
                                    if (oldR != null || newR != null) {
                                        // 片方がステレオなら、トラックはステレオにする（モノラル側は左右同じ音として扱う）
                                        val mergedR = ShortArray(merged.size)
                                        val src = oldR ?: old
                                        System.arraycopy(src, 0, mergedR, 0, minOf(src.size, mergedR.size))
                                        System.arraycopy(newR ?: pcm, 0, mergedR, from, pcm.size)
                                        t.pcmR = mergedR
                                    }
                                    t.pcm = merged
                                }
                                say("トラック" + (idx + 1) + "を録音しました。保存するのを忘れずに。")
                            } else {
                                say("録音が短すぎたので、保存しませんでした。")
                            }
                        }
                    },
                )
                session = me
            }
        }
    }

    fun stopRec() {
        if (recIdx < 0) return
        val s = session
        if (s != null) {
            s.stop = true
        } else {
            // まだ準備中だった場合は、そのまま取りやめる
            token++
            recIdx = -1
            playing = false
            level = 0f
        }
    }

    // ---------- 遅延の測定 ----------
    fun measureLatency() {
        if (recIdx >= 0 || playing) return
        val total = SR * 3 / 2
        val beep = ShortArray(total)
        val start = (0.3 * SR).toInt()
        val len = (0.08 * SR).toInt()
        val fadeIn = 0.003 * SR
        val fadeOut = 0.01 * SR
        for (i in 0 until len) {
            val env = minOf(1.0, i / fadeIn) * minOf(1.0, (len - i) / fadeOut)
            beep[start + i] = (Math.sin(2.0 * Math.PI * 1000.0 * i / SR) * 20000.0 * env).toInt().toShort()
        }
        stopAll()
        playing = true
        say("測定中…スマホのスピーカーから音が鳴ります。")
        var me: Session? = null
        me = engine.start(
            listOf(PlayTrack(beep, 1f, 0, false)), true, total,
            engine.builtinMic(), engine.builtinSpeaker(),
            { t -> if (session === me) timeSec = t },
            { pcm, err ->
                if (session === me) {
                    session = null
                    playing = false
                    timeSec = 0.0
                    if (err != null) {
                        say(err)
                    } else if (pcm == null) {
                        say("測定に失敗しました。もう一度試してください。")
                    } else {
                        var mx = 0
                        for (v in pcm) mx = maxOf(mx, Math.abs(v.toInt()))
                        if (mx < 600) {
                            say("ビープ音を拾えませんでした。静かな場所で、音量を上げてお試しください。")
                        } else {
                            var idx = 0
                            while (idx < pcm.size && Math.abs(pcm[idx].toInt()) < mx * 0.3) idx++
                            val l = idx * 1000.0 / SR - 300.0
                            if (l < 0 || l > 800) {
                                say("測定値が不自然でした（" + l.toInt() + " ms）。もう一度お試しください。")
                            } else {
                                latencyMs = l.toFloat()
                                store.latencyMs = latencyMs
                                say("遅延は約" + l.toInt() + " msでした。これから録るトラックに自動で補正されます。")
                            }
                        }
                    }
                }
            },
        )
        session = me
    }

    // ---------- 保存・開く・削除 ----------
    fun currentLabel(): String {
        val c = current ?: return "新しいアイデア（未保存）"
        return whenStr(c.ts) + " のアイデアを編集中（保存で上書き）"
    }

    fun save() {
        if (recIdx >= 0) {
            say("録音を止めてから保存してください。")
            return
        }
        if (tracks.none { it.pcm != null }) {
            say("保存するトラックがありません。")
            return
        }
        val now = System.currentTimeMillis()
        val base = current
        val id = base?.id ?: ("i" + now)
        val metas = tracks.map { t ->
            if (t.pcm != null) {
                TrackMeta(
                    true, SR, t.shiftMs.toDouble(), t.adjMs.toDouble(), t.vol.toDouble(), t.mute,
                    t.pan.toDouble(), t.comp.toDouble(), t.dMix.toDouble(), t.dMs.toDouble(), t.rMix.toDouble(), t.name, t.pcmR != null,
                )
            } else {
                TrackMeta(false, name = t.name)
            }
        }
        val idea = Idea(
            id, base?.ts ?: now, base?.day ?: dayOf(now), now,
            memo.trim(), selTags.toList(), metas, base?.drive,
        )
        val pcms = tracks.map { it.pcm }
        val pcmsR = tracks.map { it.pcmR }
        say("保存中…")
        io {
            for (k in 0 until N_TRACKS) {
                store.savePcm(id, k, pcms[k])
                store.savePcmR(id, k, pcmsR[k])
            }
            store.saveMeta(idea)
            main.post {
                current = idea
                refresh()
                say("保存しました。")
            }
        }
    }

    fun newIdea() {
        if (recIdx >= 0) return
        stopAll()
        for (t in tracks) {
            t.solo = false
            t.pcm = null
            t.pcmR = null
            t.vol = 0.9f
            t.shiftMs = 0f
            t.adjMs = 0f
            t.mute = false
            t.pan = 0f
            t.comp = 0f
            t.dMix = 0f
            t.dMs = 250f
            t.rMix = 0f
            t.name = ""
        }
        current = null
        startSec = 0.0
        shown = 1
        armIdx = -1
        memo = ""
        selTags.clear()
        say("新しいアイデアを始めます。")
    }

    fun openIdea(id: String) {
        if (recIdx >= 0) {
            say("録音を止めてから開いてください。")
            return
        }
        val m = ideas.firstOrNull { it.id == id } ?: return
        stopAll()
        say("読み込み中…")
        io {
            val pcms = (0 until N_TRACKS).map { k ->
                val tm = m.tracks.getOrNull(k)
                if (tm != null && tm.has) store.loadPcm(id, k) else null
            }
            val pcmsR = (0 until N_TRACKS).map { k ->
                val tm = m.tracks.getOrNull(k)
                if (tm != null && tm.has && tm.stereo) store.loadPcmR(id, k) else null
            }
            main.post {
                for (k in 0 until N_TRACKS) {
                    val tm = m.tracks.getOrNull(k)
                    val t = tracks[k]
                    t.pcm = pcms[k]
                    t.pcmR = pcmsR[k]
                    t.vol = (tm?.vol ?: 0.9).toFloat()
                    t.shiftMs = (tm?.shift ?: 0.0).toFloat()
                    t.adjMs = (tm?.adj ?: 0.0).toFloat()
                    t.mute = tm?.mute ?: false
                    t.pan = (tm?.pan ?: 0.0).toFloat()
                    t.comp = (tm?.comp ?: 0.0).toFloat()
                    t.dMix = (tm?.dMix ?: 0.0).toFloat()
                    t.dMs = (tm?.dMs ?: 250.0).toFloat()
                    t.rMix = (tm?.rMix ?: 0.0).toFloat()
                    t.name = tm?.name ?: ""
                }
                var last = 0
                for (k in 0 until N_TRACKS) {
                    val tm = m.tracks.getOrNull(k)
                    if (tm != null && (tm.has || tm.name.isNotBlank())) last = k
                }
                shown = last + 1
                startSec = 0.0
                armIdx = -1
                current = m
                memo = m.memo
                selTags.clear()
                selTags.addAll(m.tags)
                tab = 0
                say("開きました。重ね録りして、保存すると上書きされます。")
            }
        }
    }

    fun deleteIdea(id: String) {
        val m = ideas.firstOrNull { it.id == id } ?: return
        if (previewId == id) stopAll()
        io {
            store.delete(id)
            if (m.drive != null) store.addTombstone(id)
            main.post {
                if (current?.id == id) current = null
                refresh()
                say("削除しました。")
            }
        }
    }

    fun preview(id: String) {
        if (recIdx >= 0) return
        if (previewId == id) {
            stopAll()
            return
        }
        val m = ideas.firstOrNull { it.id == id } ?: return
        stopAll()
        previewId = id
        val my = token
        io {
            val list = (0 until N_TRACKS).map { k ->
                val tm = m.tracks.getOrNull(k)
                val has = tm != null && tm.has
                makePlay(
                    if (has) store.loadPcm(id, k) else null,
                    (tm?.vol ?: 0.9).toFloat(),
                    ((tm?.shift ?: 0.0) + (tm?.adj ?: 0.0)).toFloat(),
                    tm?.mute ?: false,
                    (tm?.pan ?: 0.0).toFloat(),
                    (tm?.comp ?: 0.0).toFloat(),
                    (tm?.dMix ?: 0.0).toFloat(),
                    (tm?.dMs ?: 250.0).toFloat(),
                    (tm?.rMix ?: 0.0).toFloat(),
                    if (has && tm != null && tm.stereo) store.loadPcmR(id, k) else null,
                )
            }
            main.post {
                if (token == my && previewId == id) startPlayback(list, false)
            }
        }
    }

    // ---------- タグ・カレンダー ----------
    fun allTags(): List<String> {
        val all = ArrayList<String>()
        all.addAll(BASE_TAGS)
        all.addAll(store.customTags())
        for (m in ideas) all.addAll(m.tags)
        all.addAll(selTags)
        return all.distinct()
    }

    fun addTag(t: String) {
        val v = t.trim()
        if (v.isEmpty()) return
        store.addCustomTag(v)
        if (!selTags.contains(v)) selTags.add(v)
    }

    fun streak(): Int {
        val days = ideas.map { it.day }.toSet()
        var d = LocalDate.now()
        if (!days.contains(d.toString())) d = d.minusDays(1)
        var n = 0
        while (days.contains(d.toString())) {
            n++
            d = d.minusDays(1)
        }
        return n
    }

    // ---------- 設定 ----------
    fun chooseInput(id: Int) {
        inDevId = id
        store.inDevId = id
    }

    fun setMaster(v: Float) {
        val x = v.coerceIn(0f, 1f)
        masterVol = x
        engine.master = x
        store.masterVol = x
    }

    fun setStereoIn(on: Boolean) {
        stereoIn = on
        store.stereoIn = on
    }

    fun chooseOutput(id: Int) {
        outDevId = id
        store.outDevId = id
    }

    fun setExtra(v: Float) {
        extraMs = v
        store.extraMs = v
    }

    // ---------- ドライブ同期 ----------
    fun sync(token: String) {
        if (syncing) return
        syncing = true
        syncStatus = "同期中…"
        thread {
            try {
                val client = DriveClient(token) { s -> main.post { syncStatus = s } }
                val r = client.sync(store)
                main.post {
                    refresh()
                    syncStatus = "同期しました。読み込み " + r.first + " 件、送信 " + r.second + " 件。"
                    syncing = false
                }
            } catch (e: Exception) {
                main.post {
                    syncStatus = e.message ?: "同期できませんでした。"
                    syncing = false
                }
            }
        }
    }

    override fun onCleared() {
        val s = session
        if (s != null) s.stop = true
        super.onCleared()
    }
}
