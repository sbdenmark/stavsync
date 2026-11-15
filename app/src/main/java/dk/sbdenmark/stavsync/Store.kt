package dk.sbdenmark.stavsync

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Gemmer effektlisten og de stave, du har tilføjet, på telefonen. */
object Store {
    private lateinit var p: SharedPreferences

    fun init(c: Context) {
        if (!::p.isInitialized) p = c.applicationContext.getSharedPreferences("stavsync", Context.MODE_PRIVATE)
    }

    /** Effekterne, du fandt i browser-versionen. Bruges, indtil du gemmer din egen liste. */
    val DEFAULT_FX = listOf(
        Fx(0x2A, "RGB løber nedad"),
        Fx(0x2D, "Rød/grøn løber opad"),
        Fx(0x3C, "Farver fra midten og ud"),
        Fx(0x3F, "Hver anden rød løber"),
        Fx(0x41, "Hver anden grøn løber"),
        Fx(0x43, "Hver anden blå løber"),
        Fx(0xC1, "Blinker forskellige farver"),
        Fx(0xC3, "Blinker hurtigt mellem farver"),
        Fx(0xC4, "Tænd/sluk blink, farver"),
        Fx(0xC5, "RGB tænd/sluk blink"),
        Fx(0xC7, "Fader langsomt mellem farver"),
    )

    fun loadFx(): List<Fx> {
        val s = p.getString("fx", null) ?: return DEFAULT_FX
        return try {
            val a = JSONArray(s)
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                Fx(o.getInt("id"), o.getString("n"), o.optString("v", "A").firstOrNull() ?: 'A')
            }
        } catch (e: Exception) {
            DEFAULT_FX
        }
    }

    fun saveFx(list: List<Fx>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("n", it.name).put("v", it.v.toString())) }
        p.edit().putString("fx", a.toString()).apply()
    }

    fun loadMusicFx(): List<MusicFx> {
        val s = p.getString("mfx", null) ?: return emptyList()
        return try {
            val a = JSONArray(s)
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                MusicFx(Fx(o.getInt("id"), o.getString("n"), o.optString("v", "A").firstOrNull() ?: 'A'), o.optBoolean("blink", false))
            }
        } catch (e: Exception) { emptyList() }
    }

    fun saveMusicFx(list: List<MusicFx>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.fx.id).put("n", it.fx.name).put("v", it.fx.v.toString()).put("blink", it.blink)) }
        p.edit().putString("mfx", a.toString()).apply()
    }

    /** Gemte stave som "adresse|navn", i den rækkefølge de blev tilføjet. */
    fun loadSticks(): List<Pair<String, String>> =
        (p.getString("sticks", "") ?: "").split("\n").filter { it.contains("|") }.map {
            val i = it.indexOf('|'); it.substring(0, i) to it.substring(i + 1)
        }

    fun saveSticks(list: List<Pair<String, String>>) {
        p.edit().putString("sticks", list.joinToString("\n") { "${it.first}|${it.second}" }).apply()
    }

    fun getInt(k: String, d: Int) = p.getInt(k, d)
    fun putInt(k: String, v: Int) = p.edit().putInt(k, v).apply()
    fun getBool(k: String, d: Boolean) = p.getBoolean(k, d)
    fun putBool(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()
    fun getStr(k: String, d: String) = p.getString(k, d) ?: d
    fun putStr(k: String, v: String) = p.edit().putString(k, v).apply()

    /** Læser listen fra browser-versionens "Kopiér listen": "Navn: 0x2A variant A" pr. linje. */
    fun parseExport(text: String): List<Fx> {
        val re = Regex("""^(.*?):\s*0x([0-9a-fA-F]{1,2})(?:\s*variant\s*([A-Da-d]))?\s*$""")
        return text.lines().mapNotNull { line ->
            val m = re.find(line.trim()) ?: return@mapNotNull null
            Fx(m.groupValues[2].toInt(16), m.groupValues[1].trim(), (m.groupValues[3].ifEmpty { "A" }).uppercase()[0])
        }
    }
}
