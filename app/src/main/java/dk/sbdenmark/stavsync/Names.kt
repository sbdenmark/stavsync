package dk.sbdenmark.stavsync

import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject

/**
 * Navne på effektnumrene til MELK-OA21.
 * Ingen har offentliggjort en navneliste, så navnene er:
 *  - dine egne iagttagelser (sikre),
 *  - gæt ud fra mønstre i de numre, du har fundet (markeret med "?"),
 *  - eller "ikke navngivet".
 * Retter du et navn i Effekt-finderen, gemmes din version og går forud for gættet.
 */
object Names {
    private val mine = mutableStateMapOf<String, String>()
    private fun key(id: Int, v: Char) = "$id:$v"

    /** Det du selv har set på stavene (variant A). */
    private val seen = mapOf(
        0x2A to "RGB løber nedad",
        0x2D to "Rød/grøn løber opad",
        0x3C to "Farver fra midten og ud",
        0x3F to "Hver anden rød løber",
        0x41 to "Hver anden grøn løber",
        0x43 to "Hver anden blå løber",
        0xC1 to "Blinker forskellige farver",
        0xC3 to "Blinker hurtigt mellem farver",
        0xC4 to "Tænd/sluk blink, farver",
        0xC5 to "RGB tænd/sluk blink",
        0xC7 to "Fader langsomt mellem farver",
    )

    /** Gæt ud fra naboerne. */
    private fun guess(id: Int): String? = when (id) {
        0x2B, 0x2C -> "RGB løber (anden retning eller farve?)"
        0x2E -> "Rød/grøn løber nedad?"
        0x2F, 0x30 -> "To farver løber?"
        in 0x31..0x3B -> "Løbeeffekt?"
        0x3D -> "Farver fra enderne og ind?"
        0x3E -> "Farver fra midten og ud (anden variant?)"
        0x40 -> "Hver anden rød løber modsat?"
        0x42 -> "Hver anden grøn løber modsat?"
        0x44 -> "Hver anden blå løber modsat?"
        in 0x45..0x4F -> "Hver anden farve løber?"
        0xC0 -> "Skift mellem farver?"
        0xC2 -> "Blink mellem farver?"
        0xC6 -> "Strobe?"
        0xC8, 0xC9 -> "Fade mellem farver?"
        else -> null
    }

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

    /** Navn + om det er sikkert. */
    fun nameOf(id: Int, v: Char): Pair<String, String> {
        mine[key(id, v)]?.let { return it to "dit navn" }
        Engine.effects.firstOrNull { it.id == id && it.v == v }?.let { return it.name to "fra din liste" }
        if (v == 'A') {
            seen[id]?.let { return it to "set på dine stave" }
            guess(id)?.let { return it to "gæt" }
        } else {
            // variant E bruger formentlig samme numre som A, men det er ikke testet
            (seen[id] ?: guess(id))?.let { return it.trimEnd('?') + "?" to "gæt ud fra variant A" }
        }
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

    fun isNamed(id: Int, v: Char) = nameOf(id, v).second.let { it == "dit navn" || it == "fra din liste" || it == "set på dine stave" }

    /** Hele listen som tekst, så du kan sende den til Claude. */
    fun export(v: Char): String = (0..255).mapNotNull { id ->
        val (n, src) = nameOf(id, v)
        if (src == "dit navn" || src == "fra din liste") "${Fx(id, "").hex} $v: $n" else null
    }.joinToString("\n")
}
