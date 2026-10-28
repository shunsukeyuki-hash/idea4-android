package com.shunsuke.idea4

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

const val SR = 48000
const val N_TRACKS = 8
const val MAX_SEC = 600

data class TrackMeta(
    val has: Boolean,
    val sr: Int = SR,
    val shift: Double = 0.0,
    val adj: Double = 0.0,
    val vol: Double = 0.9,
    val mute: Boolean = false,
    val pan: Double = 0.0,
    val comp: Double = 0.0,
    val dMix: Double = 0.0,
    val dMs: Double = 250.0,
    val rMix: Double = 0.0,
    val name: String = "",
    val stereo: Boolean = false,
)

data class DriveInfo(val ids: Map<String, String>, val at: Long)

data class Idea(
    val id: String,
    val ts: Long,
    val day: String,
    val updatedAt: Long,
    val memo: String,
    val tags: List<String>,
    val tracks: List<TrackMeta>,
    val drive: DriveInfo? = null,
)

fun dayOf(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate().toString()

fun whenStr(ts: Long): String =
    SimpleDateFormat("M/d HH:mm", Locale.JAPAN).format(Date(ts))

fun TrackMeta.toJson(): JSONObject =
    JSONObject().put("has", has).put("sr", sr).put("shift", shift)
        .put("adj", adj).put("vol", vol).put("mute", mute)
        .put("pan", pan).put("comp", comp).put("dMix", dMix).put("dMs", dMs).put("rMix", rMix).put("name", name).put("stereo", stereo)

fun trackFromJson(o: JSONObject?): TrackMeta {
    if (o == null || !o.optBoolean("has", false)) return TrackMeta(false, name = o?.optString("name", "") ?: "")
    return TrackMeta(
        true,
        o.optInt("sr", SR),
        o.optDouble("shift", 0.0),
        o.optDouble("adj", 0.0),
        o.optDouble("vol", 0.9),
        o.optBoolean("mute", false),
        o.optDouble("pan", 0.0),
        o.optDouble("comp", 0.0),
        o.optDouble("dMix", 0.0),
        o.optDouble("dMs", 250.0),
        o.optDouble("rMix", 0.0),
        o.optString("name", ""),
        o.optBoolean("stereo", false),
    )
}

fun Idea.toJson(withDrive: Boolean): JSONObject {
    val tagArr = JSONArray()
    for (t in tags) tagArr.put(t)
    val trackArr = JSONArray()
    for (t in tracks) trackArr.put(t.toJson())
    val o = JSONObject()
        .put("id", id)
        .put("ts", ts)
        .put("day", day)
        .put("updatedAt", updatedAt)
        .put("memo", memo)
        .put("tags", tagArr)
        .put("tracks", trackArr)
    val d = drive
    if (withDrive && d != null) {
        val idsObj = JSONObject()
        for ((k, v) in d.ids) idsObj.put(k, v)
        o.put("drive", JSONObject().put("at", d.at).put("ids", idsObj))
    }
    return o
}

fun ideaFromJson(o: JSONObject): Idea {
    val tagArr = o.optJSONArray("tags")
    val tags = ArrayList<String>()
    if (tagArr != null) for (i in 0 until tagArr.length()) tags.add(tagArr.optString(i))
    val trackArr = o.optJSONArray("tracks")
    val tracks = (0 until N_TRACKS).map { trackFromJson(trackArr?.optJSONObject(it)) }
    val d = o.optJSONObject("drive")
    var drive: DriveInfo? = null
    if (d != null) {
        val ids = HashMap<String, String>()
        val idsObj = d.optJSONObject("ids")
        if (idsObj != null) {
            val keys = idsObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                ids[k] = idsObj.optString(k)
            }
        }
        drive = DriveInfo(ids, d.optLong("at"))
    }
    return Idea(
        o.getString("id"),
        o.optLong("ts"),
        o.optString("day"),
        o.optLong("updatedAt"),
        o.optString("memo"),
        tags,
        tracks,
        drive,
    )
}
