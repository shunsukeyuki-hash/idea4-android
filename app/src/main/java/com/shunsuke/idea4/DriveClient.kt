package com.shunsuke.idea4

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Googleドライブの「アイデア録音」フォルダと同期する。
 * ファイル名とメモの形式はWeb版と同じ（idea_<id>.json、idea_<id>_t1.wav など）。
 */
class DriveClient(private val token: String, private val progress: (String) -> Unit) {

    class HttpErr(val code: Int) : Exception("ドライブとの通信に失敗しました（$code）。")

    private var folder = ""

    private fun req(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
    ): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 120000
        // HttpURLConnection は PATCH を直接扱えないため、POST + 上書きヘッダーで送る
        c.requestMethod = if (method == "PATCH") "POST" else method
        if (method == "PATCH") c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        c.setRequestProperty("Authorization", "Bearer $token")
        for ((k, v) in headers) c.setRequestProperty(k, v)
        if (body != null) {
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
        c.disconnect()
        if (code !in 200..299) throw HttpErr(code)
        return bytes
    }

    private fun text(b: ByteArray): String = String(b, Charsets.UTF_8)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun ensureFolder(): String {
        if (folder.isNotEmpty()) return folder
        val q = enc("name='アイデア録音' and mimeType='application/vnd.google-apps.folder' and trashed=false")
        val r = JSONObject(
            text(req("GET", "https://www.googleapis.com/drive/v3/files?q=" + q + "&fields=files(id)&spaces=drive")),
        )
        val a = r.getJSONArray("files")
        if (a.length() > 0) {
            folder = a.getJSONObject(0).getString("id")
            return folder
        }
        val b = JSONObject()
            .put("name", "アイデア録音")
            .put("mimeType", "application/vnd.google-apps.folder")
            .toString().toByteArray()
        val c = JSONObject(
            text(req("POST", "https://www.googleapis.com/drive/v3/files", mapOf("Content-Type" to "application/json"), b)),
        )
        folder = c.getString("id")
        return folder
    }

    private fun upsert(name: String, data: ByteArray, mime: String, existing: String?): String {
        val f = ensureFolder()
        if (existing != null && existing.isNotEmpty()) {
            try {
                val r = req(
                    "PATCH",
                    "https://www.googleapis.com/upload/drive/v3/files/" + existing + "?uploadType=media&fields=id",
                    mapOf("Content-Type" to mime),
                    data,
                )
                return JSONObject(text(r)).getString("id")
            } catch (e: HttpErr) {
                if (e.code != 404) throw e
            }
        }
        val b = "b" + System.currentTimeMillis()
        val metaJson = JSONObject().put("name", name).put("parents", JSONArray().put(f)).toString()
        val head = "--" + b + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + metaJson +
            "\r\n--" + b + "\r\nContent-Type: " + mime + "\r\n\r\n"
        val tail = "\r\n--" + b + "--"
        val body = head.toByteArray() + data + tail.toByteArray()
        val r = req(
            "POST",
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
            mapOf("Content-Type" to "multipart/related; boundary=" + b),
            body,
        )
        return JSONObject(text(r)).getString("id")
    }

    private fun listRemote(): List<Pair<String, String>> {
        val f = ensureFolder()
        val out = ArrayList<Pair<String, String>>()
        var page = ""
        do {
            val q = enc("'" + f + "' in parents and trashed=false and name contains '.json'")
            var url = "https://www.googleapis.com/drive/v3/files?q=" + q +
                "&fields=nextPageToken,files(id,name)&pageSize=200"
            if (page.isNotEmpty()) url += "&pageToken=" + page
            val r = JSONObject(text(req("GET", url)))
            val a = r.getJSONArray("files")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Pair(o.getString("name"), o.getString("id")))
            }
            page = r.optString("nextPageToken", "")
        } while (page.isNotEmpty())
        return out
    }

    private fun download(id: String): ByteArray =
        req("GET", "https://www.googleapis.com/drive/v3/files/" + id + "?alt=media")

    private fun importIdea(store: Store, meta: JSONObject, metaId: String) {
        val id = meta.getString("id")
        val tracksJ = meta.optJSONArray("tracks")
        val files = meta.optJSONObject("files")
        val ids = HashMap<String, String>()
        ids["meta"] = metaId
        val tracks = ArrayList<TrackMeta>()
        for (k in 0 until N_TRACKS) {
            val tm = trackFromJson(tracksJ?.optJSONObject(k))
            val fid = files?.optString("t$k", "") ?: ""
            if (!tm.has || fid.isEmpty()) {
                tracks.add(TrackMeta(false))
                store.savePcm(id, k, null)
                store.savePcmR(id, k, null)
                continue
            }
            val bytes = download(fid)
            val chans = Wav.channels(bytes)
            val w = Wav.decode(bytes)
            if (chans == 2 && tm.stereo) {
                val sp = splitStereo(w.second)
                store.savePcm(id, k, resample(sp.first, w.first, SR))
                store.savePcmR(id, k, resample(sp.second, w.first, SR))
            } else {
                store.savePcm(id, k, resample(w.second, w.first, SR))
                store.savePcmR(id, k, null)
            }
            ids["t$k"] = fid
            tracks.add(tm.copy(sr = SR, stereo = chans == 2 && tm.stereo))
        }
        val tagsJ = meta.optJSONArray("tags")
        val tags = ArrayList<String>()
        if (tagsJ != null) for (i in 0 until tagsJ.length()) tags.add(tagsJ.optString(i))
        val updated = meta.optLong("updatedAt")
        store.saveMeta(
            Idea(
                id,
                meta.optLong("ts"),
                meta.optString("day"),
                updated,
                meta.optString("memo"),
                tags,
                tracks,
                DriveInfo(ids, updated),
            ),
        )
    }

    private fun uploadIdea(store: Store, m: Idea) {
        val ids = HashMap<String, String>()
        val old = m.drive
        if (old != null) ids.putAll(old.ids)
        val base = "idea_" + m.id
        val files = JSONObject()
        val playList = ArrayList<PlayTrack>()
        for (k in 0 until N_TRACKS) {
            val tm = m.tracks.getOrNull(k)
            if (tm == null || !tm.has) continue
            val pcm = store.loadPcm(m.id, k) ?: continue
            val pcmR = if (tm.stereo) store.loadPcmR(m.id, k) else null
            val wavBytes = if (pcmR != null) Wav.encode(interleave(pcm, pcmR), SR, 2) else Wav.encode(pcm, SR)
            val fid = upsert(base + "_t" + (k + 1) + ".wav", wavBytes, "audio/wav", ids["t$k"])
            ids["t$k"] = fid
            files.put("t$k", fid)
            playList.add(
                makePlay(
                    pcm, tm.vol.toFloat(), (tm.shift + tm.adj).toFloat(), tm.mute,
                    tm.pan.toFloat(), tm.comp.toFloat(), tm.dMix.toFloat(), tm.dMs.toFloat(), tm.rMix.toFloat(), pcmR,
                ),
            )
        }
        val mixWav = Mixer.renderWav(playList)
        if (mixWav != null) {
            ids["mix"] = upsert(base + "_mix.wav", mixWav, "audio/wav", ids["mix"])
        }
        val meta = m.toJson(false).put("app", "idea4").put("files", files)
        ids["meta"] = upsert(base + ".json", meta.toString().toByteArray(), "application/json", ids["meta"])
        store.saveMeta(m.copy(drive = DriveInfo(ids, m.updatedAt)))
    }

    /** 戻り値は（読み込んだ件数, 送信した件数）。 */
    fun sync(store: Store): Pair<Int, Int> {
        progress("ドライブの状態を確認中…")
        ensureFolder()
        val remote = listRemote()
        val del = store.tombstones()
        val rmap = HashMap<String, Pair<String, JSONObject>>()
        val re = Regex("^idea_(.+)\\.json$")
        for (entry in remote) {
            val name = entry.first
            val fid = entry.second
            val mm = re.find(name) ?: continue
            val id = mm.groupValues[1]
            if (del.contains(id)) {
                req(
                    "PATCH",
                    "https://www.googleapis.com/drive/v3/files/" + fid,
                    mapOf("Content-Type" to "application/json"),
                    "{\"trashed\":true}".toByteArray(),
                )
                continue
            }
            val meta = JSONObject(text(download(fid)))
            if (meta.optString("app") == "idea4" && meta.optString("id") == id) {
                rmap[id] = Pair(fid, meta)
            }
        }
        val local = store.list()
        val lmap = HashMap<String, Idea>()
        for (l in local) lmap[l.id] = l
        var down = 0
        var up = 0
        for ((id, r) in rmap) {
            val l = lmap[id]
            if (l == null || r.second.optLong("updatedAt") > l.updatedAt) {
                progress("読み込み中…（" + (down + 1) + "件目）")
                importIdea(store, r.second, r.first)
                down++
            }
        }
        for (l in local) {
            val r = rmap[l.id]
            if (r == null || l.updatedAt > r.second.optLong("updatedAt")) {
                progress("送信中…（" + (up + 1) + "件目）")
                uploadIdea(store, l)
                up++
            }
        }
        store.clearTombstones()
        return Pair(down, up)
    }
}
