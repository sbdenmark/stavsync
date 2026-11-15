package dk.sbdenmark.stavsync

import kotlin.math.abs
import kotlin.math.roundToInt

/** En farve med værdier 0–255. */
data class Rgb(val r: Int, val g: Int, val b: Int) {
    fun scale(k: Double): Rgb {
        val f = k.coerceIn(0.0, 1.0)
        return Rgb((r * f).roundToInt(), (g * f).roundToInt(), (b * f).roundToInt())
    }

    fun mix(o: Rgb, k: Double): Rgb {
        val f = k.coerceIn(0.0, 1.0)
        return Rgb(
            (r + (o.r - r) * f).roundToInt(),
            (g + (o.g - g) * f).roundToInt(),
            (b + (o.b - b) * f).roundToInt()
        )
    }

    /** Blander mod hvid. */
    fun whiten(k: Double): Rgb = mix(WHITE, k)

    companion object {
        val BLACK = Rgb(0, 0, 0)
        val WHITE = Rgb(255, 255, 255)

        /** Mættet farve ud fra farvetone i grader. */
        fun hue(h: Double): Rgb {
            val hh = ((h % 360) + 360) % 360
            fun f(n: Int): Int {
                val k = (n + hh / 60) % 6
                return (255 * (1 - maxOf(minOf(k, 4 - k, 1.0), 0.0))).roundToInt()
            }
            return Rgb(f(5), f(3), f(1))
        }
    }
}

/** En indbygget effekt i staven: nummer, navn og kommando-variant (A–D). */
data class Fx(val id: Int, val name: String, val v: Char = 'A') {
    val hex: String get() = "0x" + id.toString(16).uppercase().padStart(2, '0')
}

/** En effekt i musiklisten. blink = lysstyrken blinker på hvert beat, ellers lyser den jævnt. */
data class MusicFx(val fx: Fx, val blink: Boolean = false)

/**
 * Kommandoer til Magic Lantern-/ELK-stave. Alle er 9 byte: 7E … EF.
 * Byte nr. 2 er kommandoens slags (1 lysstyrke, 2 hastighed, 3 effekt, 4 tænd/sluk, 5 farve).
 */
object Proto {
    @Volatile var melk = true

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    fun color(c: Rgb): ByteArray = if (melk)
        b(0x7e, 0x07, 0x05, 0x03, c.r, c.g, c.b, 0x10, 0xef)
    else b(0x7e, 0x00, 0x05, 0x03, c.r, c.g, c.b, 0x00, 0xef)

    fun bright(v: Int): ByteArray = if (melk)
        b(0x7e, 0x04, 0x01, v, 0x01, 0xff, 0xff, 0x00, 0xef)
    else b(0x7e, 0x00, 0x01, v, 0, 0, 0, 0, 0xef)

    fun speed(v: Int): ByteArray = if (melk)
        b(0x7e, 0x04, 0x02, v, 0xff, 0xff, 0xff, 0x00, 0xef)
    else b(0x7e, 0x00, 0x02, v, 0, 0, 0, 0, 0xef)

    fun effect(id: Int, variant: Char = 'A'): ByteArray = when (variant) {
        'B' -> b(0x7e, 0x00, 0x03, id, 0x03, 0x00, 0x00, 0x00, 0xef)
        'C' -> b(0x7e, 0x05, 0x03, id, 0x03, 0xff, 0xff, 0x00, 0xef)
        'D' -> b(0x7e, 0x04, 0x03, id, 0x03, 0xff, 0xff, 0x00, 0xef)
        else -> b(0x7e, 0x07, 0x03, id, 0x03, 0xff, 0xff, 0x00, 0xef)
    }

    fun on(): ByteArray = if (melk)
        b(0x7e, 0x07, 0x04, 0xff, 0x00, 0x01, 0x02, 0x01, 0xef)
    else b(0x7e, 0x00, 0x04, 0xf0, 0x00, 0x01, 0xff, 0x00, 0xef)

    fun off(): ByteArray = if (melk)
        b(0x7e, 0x07, 0x04, 0x00, 0x00, 0x00, 0x02, 0x01, 0xef)
    else b(0x7e, 0x00, 0x04, 0x00, 0x00, 0x00, 0xff, 0x00, 0xef)

    fun kind(frame: ByteArray): Int = frame[2].toInt() and 0xff

    @Suppress("unused")
    fun differs(a: Rgb, b: Rgb) = abs(a.r - b.r) + abs(a.g - b.g) + abs(a.b - b.b) > 0
}
