package com.shunsuke.idea4

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** アイデアの保存。1アイデアにつき1フォルダ（meta.json と t0.pcm〜t3.pcm）。 */
class Store(ctx: Context) {
    private val root = File(ctx.filesDir, "ideas").apply { mkdirs() }
    private val prefs = ctx.getSharedPreferences("idea4", Context.MODE_PRIVATE)

    fun list(): List<Idea> {
        val dirs = root.listFiles() ?: return emptyList()
        val out = ArrayList<Idea>()
        for (d in dirs) {
            val f = File(d, "meta.json")
            if (!f.exists()) continue
            try {
                out.add(ideaFromJson(JSONObject(f.readText())))
            } catch (e: Exception) {
            }
        }
        out.sortByDescending { it.ts }
        return out
    }

    fun saveMeta(i: Idea) {
        val d = File(root, i.id)
        d.mkdirs()
        File(d, "meta.json").writeText(i.toJson(true).toString())
    }

    fun savePcm(id: String, k: Int, pcm: ShortArray?) {
        val d = File(root, id)
        d.mkdirs()
        val f = File(d, "t$k.pcm")
        if (pcm == null) f.delete() else f.writeBytes(pcmToBytes(pcm))
    }

    fun loadPcm(id: String, k: Int): ShortArray? {
        val f = File(File(root, id), "t$k.pcm")
        return if (f.exists()) pcmFromBytes(f.readBytes()) else null
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    // ----- 設定 -----
    var extraMs: Float
        get() = prefs.getFloat("extra", 0f)
        set(v) {
            prefs.edit().putFloat("extra", v).apply()
        }

    var masterVol: Float
        get() = prefs.getFloat("master", 1f)
        set(v) {
            prefs.edit().putFloat("master", v).apply()
        }

    var latencyMs: Float
        get() = prefs.getFloat("latency", 0f)
        set(v) {
            prefs.edit().putFloat("latency", v).apply()
        }

    var inDevId: Int
        get() = prefs.getInt("inDev", -1)
        set(v) {
            prefs.edit().putInt("inDev", v).apply()
        }

    var outDevId: Int
        get() = prefs.getInt("outDev", -1)
        set(v) {
            prefs.edit().putInt("outDev", v).apply()
        }

    fun customTags(): List<String> {
        val s = prefs.getString("tags", "[]") ?: "[]"
        val out = ArrayList<String>()
        try {
            val a = JSONArray(s)
            for (i in 0 until a.length()) out.add(a.optString(i))
        } catch (e: Exception) {
        }
        return out
    }

    fun addCustomTag(t: String) {
        val l = customTags().toMutableList()
        if (!l.contains(t)) l.add(t)
        val a = JSONArray()
        for (x in l) a.put(x)
        prefs.edit().putString("tags", a.toString()).apply()
    }

    // ----- ドライブ側から消すための削除記録 -----
    fun tombstones(): Set<String> = HashSet(prefs.getStringSet("del", emptySet()) ?: emptySet())

    fun addTombstone(id: String) {
        val s = HashSet(tombstones())
        s.add(id)
        prefs.edit().putStringSet("del", s).apply()
    }

    fun clearTombstones() {
        prefs.edit().remove("del").apply()
    }
}
