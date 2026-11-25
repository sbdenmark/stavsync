package dk.sbdenmark.stavsync

import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject

/**
 * Navne på effektnumrene til MELK-OA21.
 * Navnene kommer fra Magic Lantern-appen (se Catalog.kt).
 * Retter du et navn i Effekt-finderen, gemmes din version og går forud for appens navn.
 */
object Names {
    private val mine = mutableStateMapOf<String, String>()
    private fun key(id: Int, v: Char) = "$id:$v"

    fun init() {
        val s = Store.getStr("names", "")
        if (s.isEmpty()) return
        try {
            val o = JSONObject(s)
            o.keys().forEach { k -> mine[k] = o.getString(k) }
        } catch (_: Exception) {}
    }

    private fun save() {
        val o = JSONObject(); mine.forEach { (k, v) -> o.put(k, v) }
        Store.putStr("names", o.toString())
    }

    /** Navn + hvor navnet kommer fra. */
    fun nameOf(id: Int, v: Char): Pair<String, String> {
        mine[key(id, v)]?.let { return it to "dit navn" }
        Engine.effects.firstOrNull { it.id == id && it.v == v }?.let { return it.name to "fra din liste" }
        // numrene i Magic Lantern-appen gælder både for variant A og E
        if (v == 'A' || v == 'E') Catalog.get(id)?.let { return it.da to "Magic Lantern: ${it.en}" }
        return "Ikke navngivet" to ""
    }

    fun rename(id: Int, v: Char, name: String) {
        val n = name.trim()
        if (n.isEmpty()) mine.remove(key(id, v)) else mine[key(id, v)] = n
        save()
        // opdater også din effektliste, hvis effekten er der
        val i = Engine.effects.indexOfFirst { it.id == id && it.v == v }
        if (i >= 0 && n.isNotEmpty()) { Engine.effects[i] = Engine.effects[i].copy(name = n); Store.saveFx(Engine.effects) }
    }

    fun isNamed(id: Int, v: Char) = nameOf(id, v).second.isNotEmpty()

    /** Hele listen som tekst, så du kan sende den til Claude. */
    fun export(v: Char): String = (0..255).mapNotNull { id ->
        val (n, src) = nameOf(id, v)
        if (src == "dit navn" || src == "fra din liste") "${Fx(id, "").hex} $v: $n" else null
    }.joinToString("\n")
}
