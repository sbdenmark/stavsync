package dk.sbdenmark.stavsync

import android.Manifest
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasBt()) { SyncService.send(this, SyncService.ACTION_KEEP); BleHub.connectAll() }
        else BleHub.status = "Appen skal have adgang til enheder i nærheden for at finde stavene."
    }

    private val micLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startMusic() else Engine.mStatus = "Appen skal have adgang til mikrofonen for at lytte efter musik."
    }

    private val projLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == RESULT_OK && data != null) {
            SyncService.send(this, SyncService.ACTION_PHONE) { putExtra("code", res.resultCode); putExtra("data", data) }
        } else Engine.mStatus = "Adgang til telefonens lyd blev ikke givet."
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun hasBt() = if (Build.VERSION.SDK_INT >= 31)
        granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT)
    else granted(Manifest.permission.ACCESS_FINE_LOCATION)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this); BleHub.init(this); Engine.init(this)

        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) need += listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else need += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.all { granted(it) }) { SyncService.send(this, SyncService.ACTION_KEEP); BleHub.connectAll() }
        else permLauncher.launch(need.toTypedArray())

        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (dark) darkColorScheme(
                    primary = Color(0xFF8F7BFF), background = Color(0xFF0F1018), surface = Color(0xFF1A1C28),
                    surfaceVariant = Color(0xFF22253A)
                ) else lightColorScheme(primary = Color(0xFF5B3DF5), background = Color(0xFFF3F4F8))
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App(
                        onStartMusic = { startMusic() },
                        onKeepAlive = { if (hasBt()) SyncService.send(this, SyncService.ACTION_KEEP) }
                    )
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        Engine.saveSettings()
    }

    private fun startMusic() {
        if (!granted(Manifest.permission.RECORD_AUDIO)) { micLauncher.launch(Manifest.permission.RECORD_AUDIO); return }
        if (Engine.source == "phone") {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projLauncher.launch(mpm.createScreenCaptureIntent())
        } else {
            SyncService.send(this, SyncService.ACTION_MIC)
        }
    }
}

// ---------------------------------------------------------------- brugerflade

@Composable
fun App(onStartMusic: () -> Unit, onKeepAlive: () -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Stav-synk", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Holder Magic Lantern-stavene i takt. Kører videre i baggrunden, også når du skifter app.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp
        )
        RunningBar()
        SticksSection()
        MusicSection(onStartMusic)
        MusicListSection()
        StickMicSection()
        CustomSection(onKeepAlive)
        EffectSection(onKeepAlive)
        SyncSection(onKeepAlive)
        FinderSection()
        ColorSection()
        AdvancedSection()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun RunningBar() {
    val r = Engine.running
    if (r == Run.NONE) return
    val what = when (r) {
        Run.EFFECT -> "Indbygget effekt kører"
        Run.SYNC -> "Auto-genstart kører · ${Engine.countdown}"
        Run.CUSTOM -> "Egen effekt kører"
        Run.MUSIC -> "Musik kører · ${Engine.beats} beats"
        Run.STICKMIC -> "Stavenes egen mikrofon kører"
        else -> ""
    }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(what, Modifier.weight(1f), fontWeight = FontWeight.Medium)
        TextButton(onClick = { Engine.stopAll() }) { Text("Stop") }
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            content()
        }
    }
}

@Composable
fun Hint(text: String) {
    if (text.isNotEmpty()) Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
}

@Composable
fun <T> Picker(label: String, options: List<Pair<T, String>>, value: T, onChange: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(options.firstOrNull { it.first == value }?.second ?: "–", Modifier.weight(1f))
                Text("▾")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (v, t) -> DropdownMenuItem(text = { Text(t) }, onClick = { open = false; onChange(v) }) }
            }
        }
    }
}

@Composable
fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
fun TwoButtons(a: String, onA: () -> Unit, b: String, onB: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onA, modifier = Modifier.weight(1f)) { Text(a) }
        OutlinedButton(onClick = onB, modifier = Modifier.weight(1f)) { Text(b) }
    }
}

// ---------- stave ----------
@Composable
fun SticksSection() {
    Section("Stave") {
        Hint("Luk Magic Lantern helt (Tving stop), før du søger. Rækkefølgen bruges af Jagt og Skiftevis.")
        TwoButtons(
            if (BleHub.scanning) "Søger …" else "Søg efter stave", { BleHub.scan() },
            "Forbind alle", { BleHub.connectAll() }
        )
        BleHub.found.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.name, fontWeight = FontWeight.Medium)
                    Text("Signal ${f.rssi} dBm", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = { BleHub.add(f) }) { Text("Tilføj") }
            }
        }
        if (BleHub.sticks.isEmpty()) Hint("Ingen stave tilføjet endnu.")
        BleHub.sticks.forEachIndexed { i, s ->
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background, RoundedCornerShape(10.dp)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(10.dp).background(if (s.connected) Color(0xFF3FCF8E) else Color(0xFFFF6B7A), CircleShape))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("${i + 1}. ${s.name}", fontWeight = FontWeight.Medium)
                    Text(s.state, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { BleHub.move(s, -1) }) { Text("▲") }
                TextButton(onClick = { BleHub.move(s, 1) }) { Text("▼") }
                TextButton(onClick = { if (s.connected) BleHub.disconnect(s) else BleHub.connect(s) }) {
                    Text(if (s.connected) "Afbryd" else "Forbind")
                }
                TextButton(onClick = { BleHub.remove(s) }) { Text("✕") }
            }
        }
        Hint(BleHub.status)
    }
}

// ---------- musik ----------
val MUSIC_MODES = listOf(
    "cycle" to "Skift farve på hvert beat",
    "flash" to "Blink på beat (ellers mørk)",
    "builtin" to "Valgt indbygget effekt starter forfra på beatet",
    "randfx" to "Skift mellem effekterne i musiklisten",
    "drop" to "Farveskift + hvid strobe ved drop",
    "pulse" to "Pulsér med lydstyrken",
    "vu" to "VU-meter (grøn → gul → rød)",
    "split" to "Bas = lysstyrke, diskant = farve",
    "energy" to "Farve efter energi (blå → rød)",
    "automix" to "Auto-mix: skifter effekt hvert 16. beat",
)

val MUSIC_HINTS = mapOf(
    "cycle" to "Ny farve på beatet. Farverne følger valget under Egne effekter.",
    "flash" to "Kort blink på beatet, mørkt imellem.",
    "builtin" to "Effekten valgt under Indbyggede effekter (fx 0x3C) startes forfra på alle stave på beatet.",
    "randfx" to "Skifter til en tilfældig effekt fra musiklisten nedenfor (aldrig den samme to gange i træk). Effekter med flueben blinker på hvert beat.",
    "drop" to "Farveskift på beatet og 2 sekunders hvid strobe, når musikken pludselig bliver meget kraftigere.",
    "pulse" to "Lysstyrken følger bassen hele tiden. Ny farve på beatet.",
    "vu" to "Svag lyd giver svagt grønt, kraftig lyd giver klart rødt.",
    "split" to "Bassen styrer lysstyrken, diskanten farven.",
    "energy" to "Farven følger, hvor intens musikken har været de sidste sekunder.",
    "automix" to "Skifter mellem farveskift, blink, energi og den indbyggede effekt.",
)

@Composable
fun MusicSection(onStartMusic: () -> Unit) {
    Section("Musik") {
        Picker(
            "Lyt til", listOf("mic" to "Mikrofonen", "phone" to "Telefonens egen lyd (fx YouTube eller Spotify)"),
            Engine.source
        ) { Engine.source = it; if (Engine.running == Run.MUSIC) { Engine.stopAll(); onStartMusic() } }
        if (Engine.source == "phone") Hint("Android spørger om lov til at optage skærmen. Det er kun lyden, der bruges. Nogle apps tillader ikke optagelse, så prøv mikrofonen, hvis der ikke kommer lyd.")
        Picker("Musikeffekt", MUSIC_MODES, Engine.mMode) { Engine.mMode = it }
        Hint(MUSIC_HINTS[Engine.mMode] ?: "")
        Picker("Skift på hvert", listOf(1 to "beat", 2 to "2. beat (halvtakt)", 4 to "4. beat (hel takt)"), Engine.div) { Engine.div = it }
        Picker("Lytter efter", listOf("bass" to "Bas / stortromme", "full" to "Hele lyden"), Engine.band) { Engine.band = it }
        LabeledSlider("Følsomhed: ${Engine.sens}", Engine.sens.toFloat(), 1f..100f) { Engine.sens = it.roundToInt() }
        LinearProgressIndicator(progress = { Engine.level }, modifier = Modifier.fillMaxWidth().height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Beats fundet", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${Engine.beats}", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        }
        Hint(Engine.mStatus)
        if (Engine.diag.isNotEmpty()) Text(Engine.diag, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val on = Engine.running == Run.MUSIC
        TwoButtons(
            if (on) "Stop musik" else "Start musik", { if (on) Engine.stopAll() else onStartMusic() },
            "Test blink", { BleHub.all(Proto.color(Rgb.hue((0..359).random().toDouble()))) }
        )
    }
}

// ---------- musiklisten ----------
@Composable
fun MusicListSection() {
    var pick by remember { mutableStateOf(0) }
    Section("Musikliste") {
        Hint("Effekterne, der skiftes imellem under \"Skift mellem effekterne i musiklisten\". Sæt flueben, hvis effekten skal blinke på beatet i stedet for at lyse jævnt. Er listen tom, bruges alle dine effekter.")
        if (Engine.musicFx.isEmpty()) Hint("Musiklisten er tom.")
        Engine.musicFx.forEachIndexed { i, mf ->
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background, RoundedCornerShape(10.dp)).padding(start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = mf.blink, onCheckedChange = { Engine.setBlink(i, it) })
                Column(Modifier.weight(1f)) {
                    Text(mf.fx.name, fontWeight = FontWeight.Medium)
                    Text((if (mf.blink) "Blinker på beat" else "Lyser") + " · ${mf.fx.hex}", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { Engine.moveMusic(i, -1) }) { Text("▲") }
                TextButton(onClick = { Engine.removeFromMusic(i) }) { Text("✕") }
            }
        }
        if (Engine.effects.isNotEmpty()) {
            val opts = Engine.effects.mapIndexed { i, fx -> i to "${fx.name} (${fx.hex})" }
            Picker("Tilføj effekt fra din liste", opts, pick.coerceIn(0, Engine.effects.size - 1)) { pick = it }
            TwoButtons(
                "Tilføj", { Engine.effects.getOrNull(pick)?.let { Engine.addToMusic(it) } },
                "Tilføj alle", { Engine.effects.forEach { Engine.addToMusic(it) } }
            )
        }
        LabeledSlider("Lysstyrke mellem beats ved blink: ${Engine.blinkDim} %", Engine.blinkDim.toFloat(), 0f..60f) {
            Engine.blinkDim = it.roundToInt()
        }
    }
}

// ---------- stavenes egen mikrofon ----------
val STICK_MIC_MODES = listOf(
    0 to "Energisk", 1 to "Rytme", 2 to "Spektrum", 3 to "Rullende",
    4 to "Energisk 2", 5 to "Rytme 2", 6 to "Spektrum 2", 7 to "Rullende 2",
)

@Composable
fun StickMicSection() {
    Section("Stavenes egen mikrofon (forsøg)") {
        Hint("Magic Lantern-appens musiktilstande, hvor hver stav lytter med sin egen mikrofon. De kan vise flere farver på én stav, fx som et spektrum, men stavene styrer selv tempoet, så de er ikke helt i takt med hinanden.")
        Picker("Tilstand", STICK_MIC_MODES, Engine.smMode) { Engine.smMode = it; Engine.updateStickMic() }
        LabeledSlider("Følsomhed: ${Engine.smSens}", Engine.smSens.toFloat(), 0f..100f) {
            Engine.smSens = it.roundToInt()
        }
        val on = Engine.running == Run.STICKMIC
        TwoButtons(
            if (on) "Stop" else "Start", { if (on) Engine.stopAll() else Engine.startStickMic() },
            "Opdatér", { Engine.updateStickMic() }
        )
    }
}

// ---------- egne effekter ----------
val CUSTOM_KINDS = listOf(
    "blink" to "Blink i ny farve",
    "fade" to "Blød fade mellem farver",
    "strobe" to "Strobe",
    "chase" to "Jagt (én stav ad gangen)",
    "pingpong" to "Jagt frem og tilbage",
    "alt" to "Skiftevis (lige / ulige stave)",
)

val PALETTES = listOf(
    "random" to "Tilfældig, men tydeligt anderledes",
    "rainbow" to "Regnbue i rækkefølge",
    "basic" to "Grundfarver",
    "white" to "Kun hvid",
    "fixed" to "Den faste farve",
)

@Composable
fun CustomSection(onKeepAlive: () -> Unit) {
    Section("Egne effekter") {
        Hint("Appen sender selv hver farve til alle stave på samme tid, så de altid er i takt.")
        Picker("Effekt", CUSTOM_KINDS, Engine.cKind) { Engine.cKind = it; if (Engine.running == Run.CUSTOM) Engine.startCustom() }
        val ms = Engine.tempoMs()
        val unit = when (Engine.cKind) { "chase", "pingpong" -> "pr. stav"; "fade" -> "pr. farveskift"; "alt" -> "pr. skift"; else -> "pr. blink" }
        LabeledSlider(
            "Tempo: ${"%.2f".format(ms / 1000.0).replace('.', ',')} s $unit · ${(60000.0 / ms).roundToInt()} BPM",
            Engine.cTempo, 0f..100f
        ) { Engine.cTempo = it }
        if (Engine.cKind in listOf("blink", "strobe", "chase", "pingpong"))
            LabeledSlider("Tændt-tid: ${Engine.cOn} %", Engine.cOn.toFloat(), 5f..100f) { Engine.cOn = (it / 5).roundToInt() * 5 }
        Picker("Farver", PALETTES, Engine.palette) { Engine.palette = it }
        val on = Engine.running == Run.CUSTOM
        Button(onClick = { if (on) Engine.stopAll() else { onKeepAlive(); Engine.startCustom() } }, modifier = Modifier.fillMaxWidth()) {
            Text(if (on) "Stop egen effekt" else "Start egen effekt")
        }
    }
}

// ---------- indbyggede effekter ----------
@Composable
fun EffectSection(onKeepAlive: () -> Unit) {
    Section("Indbyggede effekter") {
        val opts = Engine.effects.mapIndexed { i, fx -> i to "${fx.name} (${fx.hex}, ${fx.v})" }
        Picker("Effekt", opts, Engine.selected) { Engine.selected = it; if (Engine.running != Run.MUSIC) Engine.sendSelected() }
        LabeledSlider("Hastighed: ${Engine.speed}", Engine.speed.toFloat(), 0f..100f) {
            Engine.speed = it.roundToInt(); BleHub.all(Proto.speed(Engine.speed))
        }
        LabeledSlider("Lysstyrke: ${Engine.bright}", Engine.bright.toFloat(), 0f..100f) {
            Engine.bright = it.roundToInt(); BleHub.all(Proto.bright(Engine.bright))
        }
        TwoButtons("Start på alle", { onKeepAlive(); Engine.startEffect() }, "Sluk alle", { Engine.off() })
    }
}

@Composable
fun SyncSection(onKeepAlive: () -> Unit) {
    Section("Hold indbyggede effekter i takt") {
        Hint("Starter den valgte indbyggede effekt forfra på alle stave med fast interval.")
        Picker("Genstart hver", listOf(10, 15, 20, 30, 45, 60).map { it to "$it sek" }, Engine.interval) { Engine.interval = it }
        val on = Engine.running == Run.SYNC
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { if (on) Engine.stopAll() else { onKeepAlive(); Engine.startSync() } }, modifier = Modifier.weight(1f)) {
                Text(if (on) "Stop auto-genstart" else "Start auto-genstart")
            }
            Spacer(Modifier.width(16.dp))
            Text(Engine.countdown, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---------- effekt-finder ----------
@Composable
fun FinderSection() {
    val (curName, src) = Names.nameOf(Engine.fnum, Engine.fvar)
    var name by remember(Engine.fnum, Engine.fvar) { mutableStateOf(if (curName == "Ikke navngivet") "" else curName.trimEnd('?')) }
    var importText by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    val clip = LocalClipboardManager.current
    fun go(n: Int) { Engine.fnum = (n + 256) % 256; Engine.finderTry(); msg = "" }
    Section("Effekt-finder") {
        Hint("Alle 212 effekter har navne fra Magic Lantern-appen. Ret dem, der ser anderledes ud på dine stave.")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { go(Engine.fnum - 1) }, modifier = Modifier.weight(1f)) { Text("◀ Forrige") }
            Text(Fx(Engine.fnum, "").hex, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = { go(Engine.fnum + 1) }, modifier = Modifier.weight(1f)) { Text("Næste ▶") }
        }
        Column {
            Text(curName, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            if (src.isNotEmpty()) Hint(src.replaceFirstChar { it.uppercase() })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { Engine.finderTry() }, modifier = Modifier.weight(1f)) { Text("Vis igen") }
            OutlinedButton(onClick = {
                var n = Engine.fnum
                for (k in 1..256) { n = (n + 1) % 256; if (!Names.isNamed(n, Engine.fvar)) break }
                go(n)
            }, modifier = Modifier.weight(1f)) { Text("Næste unavngivne ▶▶") }
        }
        var grp by remember { mutableStateOf(Catalog.get(Engine.fnum)?.group ?: "Basic") }
        Picker("Hop til gruppe", Catalog.GROUPS, grp) { g ->
            grp = g; Catalog.inGroup(g).firstOrNull()?.let { go(it.id) }
        }
        OutlinedButton(onClick = {
            Engine.addGroupToMusic(grp)
            msg = "Hele gruppen \"${Catalog.GROUPS.toMap()[grp]}\" er lagt i musiklisten."
        }, modifier = Modifier.fillMaxWidth()) { Text("Læg hele gruppen i musiklisten") }
        Picker("Kommando-variant", listOf(
            'A' to "A (virker på dine stave)", 'E' to "E (Magic Lantern-appens form)", 'B' to "B", 'C' to "C", 'D' to "D"
        ), Engine.fvar) { Engine.fvar = it; Engine.finderTry() }
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Navn") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        TwoButtons(
            "Gem navn", { Names.rename(Engine.fnum, Engine.fvar, name); msg = if (name.isBlank()) "Navnet er fjernet." else "Navnet er gemt." },
            "Ingen effekt her", { Names.rename(Engine.fnum, Engine.fvar, "Ingen effekt"); msg = "Markeret som tom." }
        )
        TwoButtons(
            "Tilføj til min liste", { Names.rename(Engine.fnum, Engine.fvar, name); Engine.finderSave(name); msg = "Tilføjet til din liste." },
            "Tilføj til musiklisten", {
                Names.rename(Engine.fnum, Engine.fvar, name)
                Engine.addToMusic(Fx(Engine.fnum, name.trim().ifEmpty { "Effekt " + Fx(Engine.fnum, "").hex }, Engine.fvar))
                msg = "Tilføjet til musiklisten."
            }
        )
        OutlinedButton(onClick = {
            val t = Names.export(Engine.fvar)
            clip.setText(AnnotatedString(t.ifEmpty { "(ingen navne endnu)" }))
            msg = "Dine navne for variant ${Engine.fvar} er kopieret. Sæt dem ind i chatten."
        }, modifier = Modifier.fillMaxWidth()) { Text("Kopiér mine navne (til Claude)") }
        Hint(msg)
        Text("Din liste", fontWeight = FontWeight.Medium)
        TwoButtons("Slet valgt effekt", { Engine.deleteSelected(); msg = "Slettet." },
            "Nulstil listen", { Engine.resetList(); msg = "Listen er nulstillet." })
        OutlinedTextField(value = importText, onValueChange = { importText = it },
            label = { Text("Indsæt listen fra browser-versionen") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = {
            val n = Engine.importList(importText)
            msg = if (n > 0) "$n effekter importeret." else "Fandt ingen effekter i teksten."
            if (n > 0) importText = ""
        }, modifier = Modifier.fillMaxWidth()) { Text("Importér") }
    }
}

// ---------- fast farve ----------
@Composable
fun ColorSection() {
    Section("Fast farve") {
        val c = Engine.fixedColor()
        Box(Modifier.fillMaxWidth().height(36.dp).background(Color(c.r, c.g, c.b), RoundedCornerShape(10.dp)))
        LabeledSlider("Farvetone", Engine.fixedHue, 0f..359f) {
            Engine.fixedHue = it; Engine.fixedWhite = false; Engine.setColor(Engine.fixedColor())
        }
        TwoButtons("Hvid", { Engine.fixedWhite = true; Engine.setColor(Rgb.WHITE) }, "Sluk", { Engine.setColor(Rgb.BLACK) })
    }
}

// ---------- avanceret ----------
@Composable
fun AdvancedSection() {
    var open by remember { mutableStateOf(false) }
    var melk by remember { mutableStateOf(Proto.melk) }
    var ack by remember { mutableStateOf(BleHub.useAck) }
    Section("Avanceret") {
        TextButton(onClick = { open = !open }) { Text(if (open) "Skjul" else "Vis indstillinger") }
        if (open) {
            Toggle("Magic Lantern-protokol (slå fra for ELK-BLEDOM)", melk) { melk = it; Proto.melk = it }
            Toggle("Sikker afsendelse: vent på kvittering fra staven", ack) { ack = it; BleHub.useAck = it }
            Toggle("Ved genstart: sluk kort lyset først", Engine.nudge) { Engine.nudge = it }
        }
    }
}
