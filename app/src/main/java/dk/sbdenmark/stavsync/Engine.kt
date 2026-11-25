package dk.sbdenmark.stavsync

import android.content.Context
import android.media.projection.MediaProjection
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

enum class Run { NONE, EFFECT, SYNC, CUSTOM, MUSIC, STICKMIC }

/**
 * Al styring af stavene. Kører uafhængigt af skærmen, så det fortsætter,
 * når appen er i baggrunden (baggrundstjenesten holder processen i live).
 */
object Engine {
    private lateinit var ctx: Context
    private val scope get() = BleHub.scope

    var running by mutableStateOf(Run.NONE)

    // indbyggede effekter
    val effects = mutableStateListOf<Fx>()
    /** Effekterne, der skiftes imellem under musik. */
    val musicFx = mutableStateListOf<MusicFx>()
    var blinkDim by mutableIntStateOf(5)
    // stavenes egen mikrofon
    var smMode by mutableIntStateOf(0)
    var smSens by mutableIntStateOf(60)
    var selected by mutableIntStateOf(0)
    var speed by mutableIntStateOf(50)
    var bright by mutableIntStateOf(100)

    // effekt-finder
    var fnum by mutableIntStateOf(0x80)
    var fvar by mutableStateOf('A')

    // hold i takt
    var interval by mutableIntStateOf(30)
    var countdown by mutableStateOf("–")
    var nudge by mutableStateOf(true)

    // egne effekter
    var cKind by mutableStateOf("blink")
    var cTempo by mutableStateOf(57f)
    var cOn by mutableIntStateOf(50)
    var palette by mutableStateOf("random")
    var fixedHue by mutableStateOf(260f)
    var fixedWhite by mutableStateOf(false)

    // musik
    var source by mutableStateOf("mic") // mic eller phone
    var sens by mutableIntStateOf(55)
    var mMode by mutableStateOf("cycle")
    var div by mutableIntStateOf(1)
    var band by mutableStateOf("bass")
    var beats by mutableIntStateOf(0)
    var level by mutableStateOf(0f)
    var mStatus by mutableStateOf("")
    var diag by mutableStateOf("")

    private var job: Job? = null
    private var audio: AudioIn? = null
    private var projection: MediaProjection? = null

    fun init(c: Context) {
        if (::ctx.isInitialized) return
        ctx = c.applicationContext
        effects.clear(); effects.addAll(Store.loadFx())
        musicFx.clear(); musicFx.addAll(Store.loadMusicFx())
        Names.init()
        fvar = Store.getStr("fvar", "A").firstOrNull() ?: 'A'
        blinkDim = Store.getInt("blinkdim", 5)
        smMode = Store.getInt("smmode", 0); smSens = Store.getInt("smsens", 60)
        speed = Store.getInt("speed", 50); bright = Store.getInt("bright", 100)
        interval = Store.getInt("interval", 30); sens = Store.getInt("sens", 55)
        mMode = Store.getStr("mmode", "cycle"); div = Store.getInt("div", 1)
        band = Store.getStr("band", "bass"); source = Store.getStr("source", "mic")
        cKind = Store.getStr("ckind", "blink"); palette = Store.getStr("palette", "random")
        cOn = Store.getInt("con", 50); cTempo = Store.getInt("ctempo", 57).toFloat()
        nudge = Store.getBool("nudge", true)
        BleHub.useAck = Store.getBool("ack", true)
        Proto.melk = Store.getBool("melk", true)
        fnum = Store.getInt("fnum", 0x80)
    }

    fun saveSettings() {
        Store.putInt("speed", speed); Store.putInt("bright", bright); Store.putInt("interval", interval)
        Store.putInt("sens", sens); Store.putStr("mmode", mMode); Store.putInt("div", div)
        Store.putStr("band", band); Store.putStr("source", source); Store.putStr("ckind", cKind)
        Store.putStr("palette", palette); Store.putInt("con", cOn); Store.putInt("ctempo", cTempo.roundToInt())
        Store.putBool("nudge", nudge); Store.putBool("ack", BleHub.useAck); Store.putBool("melk", Proto.melk)
        Store.putInt("fnum", fnum); Store.putInt("blinkdim", blinkDim); Store.putStr("fvar", fvar.toString()); Store.putInt("smmode", smMode); Store.putInt("smsens", smSens)
    }

    /** En stav, der lige er blevet forbundet (igen), får den aktuelle tilstand. */
    fun onStickConnected(s: Stick) {
        BleHub.send(s, Proto.on())
        BleHub.send(s, Proto.bright(bright))
        if (running == Run.EFFECT || running == Run.SYNC) currentFx()?.let { BleHub.send(s, Proto.effect(it.id, it.v)) }
    }

    fun currentFx(): Fx? = effects.getOrNull(selected) ?: effects.firstOrNull()

    // ---------- fælles ----------
    fun stopAll() {
        job?.cancel(); job = null
        audio?.stop(); audio = null
        projection?.let { try { it.stop() } catch (_: Exception) {} }
        projection = null
        BleHub.clearColors()
        if (running == Run.MUSIC) BleHub.all(Proto.bright(bright))
        if (running == Run.STICKMIC) BleHub.all(Proto.stickMic(false))
        running = Run.NONE
        countdown = "–"; level = 0f
        if (mStatus.startsWith("Lytter")) mStatus = ""
    }

    fun off() { stopAll(); BleHub.all(Proto.off()) }

    fun setColor(c: Rgb) { stopAll(); BleHub.all(Proto.on()); BleHub.all(Proto.color(c)) }

    fun fixedColor(): Rgb = if (fixedWhite) Rgb.WHITE else Rgb.hue(fixedHue.toDouble())

    private suspend fun startFrames(fx: Fx) {
        BleHub.all(Proto.on()); delay(60)
        BleHub.all(Proto.bright(bright)); delay(60)
        BleHub.all(Proto.speed(speed)); delay(60)
        BleHub.all(Proto.effect(fx.id, fx.v))
    }

    fun startEffect() {
        val fx = currentFx() ?: return
        stopAll(); running = Run.EFFECT
        job = scope.launch { startFrames(fx) }
    }

    fun sendSelected() { currentFx()?.let { BleHub.all(Proto.effect(it.id, it.v)) } }

    // ---------- hold i takt ----------
    fun startSync() {
        val fx = currentFx() ?: return
        stopAll(); running = Run.SYNC
        job = scope.launch {
            startFrames(fx)
            var left = interval
            while (true) {
                countdown = "$left s"
                delay(1000); left--
                if (left <= 0) {
                    val f = currentFx() ?: fx
                    if (nudge) { BleHub.all(Proto.color(Rgb.BLACK)); delay(80) }
                    BleHub.all(Proto.effect(f.id, f.v))
                    left = interval
                }
            }
        }
    }

    // ---------- effekt-finder ----------
    fun finderTry() { stopAll(); BleHub.all(Proto.on()); BleHub.all(Proto.effect(fnum, fvar)) }

    fun finderSave(name: String) {
        val n = name.trim().ifEmpty { Names.nameOf(fnum, fvar).first.takeIf { it != "Ikke navngivet" } ?: ("Effekt " + Fx(fnum, "").hex) }
        val i = effects.indexOfFirst { it.id == fnum && it.v == fvar }
        if (i >= 0) effects[i] = Fx(fnum, n, fvar) else effects.add(Fx(fnum, n, fvar))
        selected = if (i >= 0) i else effects.size - 1
        Store.saveFx(effects)
    }

    fun deleteSelected() {
        if (selected in effects.indices) { effects.removeAt(selected); selected = 0; Store.saveFx(effects) }
    }

    fun resetList() { effects.clear(); effects.addAll(Store.DEFAULT_FX); selected = 0; Store.saveFx(effects) }

    // ---------- stavenes egen mikrofon ----------
    fun startStickMic() {
        stopAll()
        running = Run.STICKMIC
        BleHub.all(Proto.on())
        BleHub.all(Proto.stickMic(true))
        BleHub.all(Proto.stickMicSens(smSens))
        BleHub.all(Proto.stickMicMode(smMode))
    }

    fun updateStickMic() {
        if (running != Run.STICKMIC) return
        BleHub.all(Proto.stickMicSens(smSens)); BleHub.all(Proto.stickMicMode(smMode))
    }

    fun addGroupToMusic(group: String) = Catalog.inGroup(group).forEach { addToMusic(Fx(it.id, it.da, 'A')) }

    // ---------- musiklisten ----------
    fun addToMusic(fx: Fx) {
        if (musicFx.none { it.fx.id == fx.id && it.fx.v == fx.v }) { musicFx.add(MusicFx(fx)); Store.saveMusicFx(musicFx) }
    }
    fun removeFromMusic(i: Int) { if (i in musicFx.indices) { musicFx.removeAt(i); Store.saveMusicFx(musicFx) } }
    fun setBlink(i: Int, on: Boolean) { if (i in musicFx.indices) { musicFx[i] = musicFx[i].copy(blink = on); Store.saveMusicFx(musicFx) } }
    fun moveMusic(i: Int, d: Int) {
        val j = i + d
        if (i in musicFx.indices && j in musicFx.indices) { val x = musicFx.removeAt(i); musicFx.add(j, x); Store.saveMusicFx(musicFx) }
    }

    fun importList(text: String): Int {
        val list = Store.parseExport(text)
        if (list.isEmpty()) return 0
        list.forEach { fx -> if (effects.none { it.id == fx.id && it.v == fx.v }) effects.add(fx) }
        Store.saveFx(effects)
        return list.size
    }

    // ---------- farver ----------
    private val BASIC = listOf(
        Rgb(255, 0, 0), Rgb(0, 255, 0), Rgb(0, 0, 255), Rgb(255, 200, 0),
        Rgb(0, 255, 255), Rgb(255, 0, 255), Rgb.WHITE
    )

    private class Pal { var hue = Random.nextDouble(360.0); var i = -1 }

    private fun nextColor(p: Pal): Rgb = when (palette) {
        "white" -> Rgb.WHITE
        "fixed" -> fixedColor()
        "basic" -> { p.i = (p.i + 1) % BASIC.size; BASIC[p.i] }
        "rainbow" -> { p.hue = (p.hue + 45) % 360; Rgb.hue(p.hue) }
        else -> { p.hue = (p.hue + 90 + Random.nextDouble(180.0)) % 360; Rgb.hue(p.hue) } // altid mindst 90° fra forrige
    }

    // ---------- egne effekter ----------
    fun tempoMs(): Long = ((50 * 200.0.pow(cTempo / 100.0) / 10).roundToInt() * 10).toLong()

    fun startCustom() {
        stopAll()
        if (BleHub.connectedSticks.isEmpty()) { mStatus = "Tilføj og forbind mindst én stav først."; return }
        running = Run.CUSTOM
        BleHub.all(Proto.on()); BleHub.all(Proto.bright(bright))
        job = scope.launch {
            val p = Pal()
            val kind = cKind
            var next = SystemClock.elapsedRealtime() + 150
            var offAt = 0L
            var step = 0; var dir = 1
            var a: Rgb? = null; var b: Rgb? = null
            var from = Rgb.BLACK; var to: Rgb? = null; var startT = 0L; var lastSend = 0L
            while (true) {
                val t = SystemClock.elapsedRealtime()
                val period = tempoMs()
                val onFrac = cOn / 100.0
                val n = BleHub.connectedSticks.size.coerceAtLeast(1)
                if (kind == "fade") {
                    if (to == null || t - startT >= period) { from = to ?: Rgb.BLACK; to = nextColor(p); startT = t }
                    if (t - lastSend >= 50) {
                        lastSend = t
                        BleHub.all(Proto.color(from.mix(to!!, (t - startT).toDouble() / period)))
                    }
                } else {
                    if (t >= next) {
                        next += period; if (next < t) next = t + period
                        when (kind) {
                            "blink", "strobe" -> {
                                BleHub.all(Proto.color(nextColor(p)))
                                val on = if (kind == "strobe") maxOf(30.0, period * onFrac) else period * onFrac
                                offAt = if (onFrac < 1) t + on.toLong() else 0
                            }
                            "chase", "pingpong" -> {
                                if (kind == "chase") {
                                    step = (step + 1) % n
                                    if (step == 0 || a == null) a = nextColor(p)
                                } else {
                                    if (n > 1) {
                                        step += dir
                                        if (step >= n - 1 || step <= 0) { dir = -dir; step = step.coerceIn(0, n - 1) }
                                    }
                                    if (step == 0 || a == null) a = nextColor(p)
                                }
                                val cur = step; val col = a!!
                                BleHub.each { i, _ -> if (i == cur) col else Rgb.BLACK }
                                offAt = if (onFrac < 1) t + (period * onFrac).toLong() else 0
                            }
                            "alt" -> {
                                step = step xor 1
                                if (step == 1 || a == null) { a = nextColor(p); b = nextColor(p) }
                                val s = step; val ca = a!!; val cb = b!!
                                BleHub.each { i, _ -> if (i % 2 == s) ca else cb }
                            }
                        }
                    }
                    if (offAt != 0L && t >= offAt) { offAt = 0; BleHub.all(Proto.color(Rgb.BLACK)) }
                }
                delay(10)
            }
        }
    }

    // ---------- musik ----------
    private class M {
        val hist = ArrayDeque<Double>()
        var maxE = 1e-9; var maxB = 1e-9; var maxH = 1e-9; var maxSlow = 1e-9
        var lastBeat = 0L; var lastSend = 0L; var flashOff = 0L; var quiet = 0; var tick = 0
        var slowE = 0.0; var fastE = 0.0; var longE = 0.0
        var strobeUntil = 0L; var strobeOn = false; var dropCool = 0L
        var vu = 0.0; var glow = 0.0; var cur: Rgb? = null
        var lastFx = -1; var fxName = ""; var curBlink = false; var dimAt = 0L
        val pal = Pal()
    }

    private val MIX = listOf("cycle", "flash", "energy", "builtin")

    fun startMusic(proj: MediaProjection?) {
        stopAll()
        projection = proj
        val a = AudioIn(ctx, proj)
        val m = M()
        beats = 0; level = 0f
        BleHub.all(Proto.on()); BleHub.all(Proto.bright(bright))
        val err = a.start(scope) { b, f, h -> musicFrame(m, b, f, h) }
        if (err != null) { mStatus = err; projection?.stop(); projection = null; return }
        audio = a; running = Run.MUSIC
        mStatus = "Lytter efter musik …"
    }

    private fun musicFrame(m: M, eB: Double, eF: Double, eH: Double) {
        if (running != Run.MUSIC) return
        val t = SystemClock.elapsedRealtime(); m.tick++
        val e = if (band == "bass") eB else eF
        m.hist.addLast(e); if (m.hist.size > 50) m.hist.removeFirst()
        val avg = m.hist.average()
        m.maxE = maxOf(m.maxE * 0.998, e); m.maxB = maxOf(m.maxB * 0.998, eB); m.maxH = maxOf(m.maxH * 0.998, eH)
        val lvl = sqrt(e / m.maxE)
        m.fastE = m.fastE * 0.85 + eF * 0.15
        m.slowE = m.slowE * 0.995 + eF * 0.005
        m.longE = m.longE * 0.998 + eF * 0.002
        m.maxSlow = maxOf(m.maxSlow * 0.9995, m.slowE)
        if (m.tick % 3 == 0) level = lvl.toFloat().coerceIn(0f, 1f)

        // beat: energien springer tydeligt over gennemsnittet for det sidste sekund
        val c = 1.2 + (100 - sens) * 0.018
        val raw = m.hist.size > 10 && e > avg * c && e > m.maxE * 0.04 && t - m.lastBeat > 220
        var beat = false
        if (raw) { m.lastBeat = t; beats++; beat = beats % div == 0 }

        var mode = mMode
        if (mode == "automix") mode = MIX[(beats / 16) % MIX.size]

        // drop: kort, kraftig energi efter en roligere periode
        if (m.tick > 500 && t > m.dropCool && m.fastE > m.longE * 3.5 && m.slowE < m.maxSlow * 0.8 && m.maxSlow > 1e-7) {
            m.strobeUntil = t + 2000; m.dropCool = t + 8000
        }

        val rate = t - m.lastSend >= 60
        when (mode) {
            "cycle" -> if (beat) { m.cur = nextColor(m.pal); BleHub.all(Proto.color(m.cur!!)) }
            "flash" -> {
                if (beat) { BleHub.all(Proto.color(nextColor(m.pal))); m.flashOff = t + 120 }
                else if (m.flashOff != 0L && t > m.flashOff) { m.flashOff = 0; BleHub.all(Proto.color(Rgb.BLACK)) }
            }
            "builtin" -> if (beat) currentFx()?.let { fireFx(it) }
            "randfx" -> {
                // musiklisten bruges; er den tom, bruges den almindelige liste uden blink
                val list = if (musicFx.isNotEmpty()) musicFx.toList() else effects.map { MusicFx(it) }
                if (beat && list.isNotEmpty()) {
                    var i = Random.nextInt(list.size)
                    if (list.size > 1 && i == m.lastFx) i = (i + 1 + Random.nextInt(list.size - 1)) % list.size
                    m.lastFx = i; val pick = list[i]; m.fxName = pick.fx.name
                    if (m.curBlink && !pick.blink) BleHub.all(Proto.bright(bright)) // tilbage til fuld styrke
                    m.curBlink = pick.blink
                    BleHub.all(Proto.effect(pick.fx.id, pick.fx.v))
                }
                // blink: fuld styrke på hvert beat, dæmpet imellem, mens effekten kører videre
                if (m.curBlink) {
                    if (raw) { BleHub.all(Proto.bright(bright)); m.dimAt = t + 120 }
                    else if (m.dimAt != 0L && t > m.dimAt) { m.dimAt = 0; BleHub.all(Proto.bright(blinkDim)) }
                }
            }
            "drop" -> when {
                t < m.strobeUntil -> if (t - m.lastSend >= 50) {
                    m.lastSend = t; m.strobeOn = !m.strobeOn
                    BleHub.all(Proto.color(if (m.strobeOn) Rgb.WHITE else Rgb.BLACK))
                }
                m.strobeOn -> { m.strobeOn = false; m.cur = nextColor(m.pal); BleHub.all(Proto.color(m.cur!!)) }
                beat -> { m.cur = nextColor(m.pal); BleHub.all(Proto.color(m.cur!!)) }
            }
            "pulse" -> {
                if (beat || m.cur == null) m.cur = nextColor(m.pal)
                if (rate) { m.lastSend = t; BleHub.all(Proto.color(m.cur!!.scale(maxOf(0.04, lvl)))) }
            }
            "vu" -> {
                m.vu = maxOf(lvl, m.vu * 0.9)
                if (rate) { m.lastSend = t; BleHub.all(Proto.color(Rgb.hue(120 - 120 * minOf(1.0, m.vu * 1.1)).scale(maxOf(0.05, m.vu)))) }
            }
            "split" -> if (rate) {
                m.lastSend = t
                val bl = sqrt(eB / m.maxB); val hl = minOf(1.0, sqrt(eH / m.maxH))
                var col = Rgb.hue(260 - 200 * hl)
                if (hl > 0.85) col = col.whiten((hl - 0.85) / 0.15)
                BleHub.all(Proto.color(col.scale(maxOf(0.05, bl))))
            }
            "energy" -> {
                val en = minOf(1.0, sqrt(m.slowE / m.maxSlow))
                if (beat) m.glow = 1.0
                m.glow *= 0.9
                if (rate) {
                    m.lastSend = t
                    var col = Rgb.hue(240 - 240 * en)
                    if (en > 0.9) col = col.whiten((en - 0.9) * 5)
                    BleHub.all(Proto.color(col.scale(0.35 + 0.65 * m.glow)))
                }
            }
        }

        m.quiet = if (eF < 1e-7) m.quiet + 1 else 0
        if (m.tick % 25 == 0) {
            fun db(x: Double) = if (x > 0) "${(10 * log10(x)).roundToInt()} dB" else "-∞"
            diag = "Kilde: ${audio?.label ?: ""}\nHele lyden: ${db(eF)} · Bas: ${db(eB)} · Diskant: ${db(eH)}"
            mStatus = when {
                m.quiet > 100 -> if (projection != null)
                    "Der kommer ingen lyd fra telefonen. Afspiller appen musik, og tillader den optagelse? Prøv ellers mikrofonen."
                else "Mikrofonen hører næsten intet."
                else -> "Lytter … niveau ${(lvl * 100).roundToInt()} %" +
                    (if (mMode == "automix") " · nu: $mode" else "") +
                    (if (mode == "randfx" && m.fxName.isNotEmpty()) " · effekt: ${m.fxName}" else "") +
                    (if (t < m.strobeUntil) " · DROP!" else "")
            }
        }
    }

    private fun fireFx(fx: Fx) {
        if (nudge) {
            BleHub.all(Proto.color(Rgb.BLACK))
            scope.launch { delay(40); BleHub.all(Proto.effect(fx.id, fx.v)) }
        } else BleHub.all(Proto.effect(fx.id, fx.v))
    }

    @Suppress("unused")
    private fun unusedFloor(x: Double) = floor(x)
}
