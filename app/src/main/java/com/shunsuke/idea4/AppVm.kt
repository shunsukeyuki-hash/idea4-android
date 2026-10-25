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
    var vol by mutableFloatStateOf(0.9f)
    var shiftMs by mutableFloatStateOf(0f)
    var adjMs by mutableFloatStateOf(0f)
    var mute by mutableStateOf(false)
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

    init {
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
            } catch (e: Exception) {
                main.post { say("エラー：" + (e.message ?: "原因不明")) }
            }
        }
    }

    // ---------- 再生 ----------
    private fun framesOf(shift: Float, adj: Float): Int = ((shift + adj) * SR / 1000f).toInt()

    private fun playTracks(skip: Int): List<PlayTrack> =
        tracks.mapIndexed { k, t ->
            PlayTrack(if (k == skip) null else t.pcm, t.vol, framesOf(t.shiftMs, t.adjMs), t.mute)
        }

    private fun startPlayback(list: List<PlayTrack>) {
        val end = Mixer.endFrame(list)
        if (end <= 0) {
            previewId = null
            say("再生できるトラックがありません。")
            return
        }
        playing = true
        timeSec = 0.0
        var me: Session? = null
        me = engine.start(
            list, false, end, null, outDev(),
            { t -> if (session === me) timeSec = t },
            { _, err ->
                if (session === me) {
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
        startPlayback(playTracks(-1))
    }

    fun stopAll() {
        val s = session
        if (s != null) s.stop = true
        session = null
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
        stopAll()
        val list = playTracks(i)
        val shift = latencyMs + extraMs
        recIdx = i
        playing = true
        timeSec = 0.0
        say("録音中（最大3分）。他のトラックを聴きながら録れます。")
        var me: Session? = null
        me = engine.start(
            list, true, MAX_SEC * SR, inDev(), outDev(),
            { t -> if (session === me) timeSec = t },
            { pcm, err ->
                if (session === me) {
                    session = null
                    val idx = recIdx
                    recIdx = -1
                    playing = false
                    if (err != null) {
                        say(err)
                    } else if (pcm != null && pcm.size > SR / 5 && idx >= 0) {
                        val t = tracks[idx]
                        t.pcm = pcm
                        t.shiftMs = shift
                        t.adjMs = 0f
                        say("トラック" + (idx + 1) + "を録音しました。保存するのを忘れずに。")
                    } else {
                        say("録音が短すぎたので、保存しませんでした。")
                    }
                }
            },
        )
        session = me
    }

    fun stopRec() {
        val s = session
        if (recIdx >= 0 && s != null) s.stop = true
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
                TrackMeta(true, SR, t.shiftMs.toDouble(), t.adjMs.toDouble(), t.vol.toDouble(), t.mute)
            } else {
                TrackMeta(false)
            }
        }
        val idea = Idea(
            id, base?.ts ?: now, base?.day ?: dayOf(now), now,
            memo.trim(), selTags.toList(), metas, base?.drive,
        )
        val pcms = tracks.map { it.pcm }
        say("保存中…")
        io {
            for (k in 0 until N_TRACKS) store.savePcm(id, k, pcms[k])
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
            t.pcm = null
            t.vol = 0.9f
            t.shiftMs = 0f
            t.adjMs = 0f
            t.mute = false
        }
        current = null
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
            main.post {
                for (k in 0 until N_TRACKS) {
                    val tm = m.tracks.getOrNull(k)
                    val t = tracks[k]
                    t.pcm = pcms[k]
                    t.vol = (tm?.vol ?: 0.9).toFloat()
                    t.shiftMs = (tm?.shift ?: 0.0).toFloat()
                    t.adjMs = (tm?.adj ?: 0.0).toFloat()
                    t.mute = tm?.mute ?: false
                }
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
        io {
            val list = (0 until N_TRACKS).map { k ->
                val tm = m.tracks.getOrNull(k)
                val has = tm != null && tm.has
                PlayTrack(
                    if (has) store.loadPcm(id, k) else null,
                    (tm?.vol ?: 0.9).toFloat(),
                    framesOf((tm?.shift ?: 0.0).toFloat(), (tm?.adj ?: 0.0).toFloat()),
                    tm?.mute ?: false,
                )
            }
            main.post {
                if (previewId == id) startPlayback(list)
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
