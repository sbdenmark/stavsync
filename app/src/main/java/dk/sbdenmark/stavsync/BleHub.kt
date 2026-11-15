package dk.sbdenmark.stavsync

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** En lysstav. Felter med "by mutableStateOf" vises direkte i brugerfladen. */
class Stick(val address: String, name: String) {
    var name by mutableStateOf(name)
    var connected by mutableStateOf(false)
    var state by mutableStateOf("Ikke forbundet")

    @Volatile var gatt: BluetoothGatt? = null
    @Volatile var ch: BluetoothGattCharacteristic? = null
    @Volatile var manual = false
    @Volatile var reconnecting = false
    @Volatile var ready = false

    val lock = Any()
    val queue = ArrayDeque<ByteArray>()
    @Volatile var ack: CompletableDeferred<Boolean>? = null
    @Volatile var lastFrame: ByteArray? = null
    @Volatile var lastWrite = 0L
    @Volatile var gap = 25L
    var writer: Job? = null
    var connectWait: CompletableDeferred<Boolean>? = null
}

data class Found(val address: String, val name: String, val rssi: Int)

@SuppressLint("MissingPermission")
object BleHub {
    private val SVC_A = uuid16(0xfff0)
    private val CH_A = uuid16(0xfff3)
    private val SVC_B = uuid16(0xffe0)
    private val CH_B = uuid16(0xffe1)
    private fun uuid16(v: Int) = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v))

    private lateinit var ctx: Context
    private var adapter: BluetoothAdapter? = null
    private val main = Handler(Looper.getMainLooper())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val sticks = mutableStateListOf<Stick>()
    val found = mutableStateListOf<Found>()
    var scanning by mutableStateOf(false)
    var status by mutableStateOf("")

    /** Vent på kvittering fra staven for hver kommando (mere stabilt). */
    @Volatile var useAck = true

    private fun ui(block: () -> Unit) = main.post(block)

    fun init(c: Context) {
        if (::ctx.isInitialized) return
        ctx = c.applicationContext
        adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        Store.loadSticks().forEach { (a, n) -> sticks.add(Stick(a, n)) }
    }

    val connectedSticks: List<Stick> get() = sticks.filter { it.ready }

    // ---------- søgning ----------
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val name = r.device.name ?: r.scanRecord?.deviceName ?: return
            val addr = r.device.address
            ui {
                if (sticks.any { it.address == addr }) return@ui
                val i = found.indexOfFirst { it.address == addr }
                val f = Found(addr, name, r.rssi)
                if (i >= 0) found[i] = f else found.add(f)
                // stave med MELK/ELK i navnet øverst, ellers stærkest signal først
                val sorted = found.sortedWith(compareByDescending<Found> {
                    it.name.uppercase().contains("ELK")
                }.thenByDescending { it.rssi })
                found.clear(); found.addAll(sorted)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            ui { scanning = false; status = "Søgningen fejlede (kode $errorCode). Slå Bluetooth fra og til." }
        }
    }

    fun scan() {
        val sc = adapter?.bluetoothLeScanner
        if (adapter?.isEnabled != true || sc == null) { status = "Bluetooth er slået fra."; return }
        if (scanning) return
        found.clear(); scanning = true; status = "Søger efter stave …"
        try {
            sc.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        } catch (e: Exception) {
            scanning = false; status = "Kunne ikke søge: ${e.message}"; return
        }
        main.postDelayed({ stopScan() }, 10_000)
    }

    fun stopScan() {
        if (!scanning) return
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
        scanning = false
        if (status.startsWith("Søger")) status = if (found.isEmpty()) "Ingen stave fundet. Er Magic Lantern lukket helt?" else ""
    }

    // ---------- tilføj / fjern ----------
    fun add(f: Found) {
        if (sticks.any { it.address == f.address }) return
        val s = Stick(f.address, f.name)
        sticks.add(s); found.removeAll { it.address == f.address }
        saveSticks()
        connect(s)
    }

    fun remove(s: Stick) {
        disconnect(s); sticks.remove(s); saveSticks()
    }

    fun move(s: Stick, delta: Int) {
        val i = sticks.indexOf(s); val j = i + delta
        if (i < 0 || j < 0 || j >= sticks.size) return
        sticks.removeAt(i); sticks.add(j, s); saveSticks()
    }

    private fun saveSticks() = Store.saveSticks(sticks.map { it.address to it.name })

    fun connectAll() = sticks.filter { !it.ready }.forEach { it.manual = false; connect(it) }

    fun disconnectAll() = sticks.forEach { disconnect(it) }

    // ---------- forbindelse ----------
    fun connect(s: Stick) {
        val a = adapter ?: return
        if (s.ready || s.gatt != null) return
        s.manual = false
        ui { s.state = "Forbinder …" }
        val dev: BluetoothDevice = try { a.getRemoteDevice(s.address) } catch (e: Exception) { return }
        s.gatt = dev.connectGatt(ctx, false, callbackFor(s), BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect(s: Stick) {
        s.manual = true
        val g = s.gatt
        if (g != null) {
            try { g.disconnect() } catch (_: Exception) {}
            // nogle telefoner kalder ikke tilbage efter disconnect, så ryd selv op lidt efter
            main.postDelayed({ if (s.gatt === g) closeGatt(s) }, 600)
        }
        ui { s.state = "Afbrudt" }
    }

    private fun closeGatt(s: Stick) {
        val g = s.gatt
        s.gatt = null; s.ch = null; s.ready = false
        s.writer?.cancel(); s.writer = null
        s.ack?.complete(false)
        synchronized(s.lock) { s.queue.clear() }
        try { g?.close() } catch (_: Exception) {}
        ui { s.connected = false }
    }

    private fun callbackFor(s: Stick) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                ui { s.state = "Finder tjenester …" }
                try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) } catch (_: Exception) {}
                // billige stave skal lige have et øjeblik, før de svarer
                main.postDelayed({ try { g.discoverServices() } catch (_: Exception) {} }, 350)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val wasReady = s.ready
                if (s.gatt === g || s.gatt == null) closeGatt(s) else try { g.close() } catch (_: Exception) {}
                s.connectWait?.complete(false)
                ui { s.state = if (s.manual) "Afbrudt" else if (wasReady) "Mistede forbindelsen" else "Kunne ikke forbinde" }
                if (!s.manual) reconnect(s)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(SVC_A)?.getCharacteristic(CH_A) ?: g.getService(SVC_B)?.getCharacteristic(CH_B)
            if (ch == null) {
                ui { s.state = "Ukendt type stav" }
                s.manual = true
                try { g.disconnect() } catch (_: Exception) {}
                return
            }
            s.ch = ch; s.ready = true; s.gap = 25
            s.connectWait?.complete(true)
            startWriter(s)
            ui { s.connected = true; s.state = "Forbundet"; Engine.onStickConnected(s) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            s.ack?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    private fun reconnect(s: Stick) {
        if (s.reconnecting) return
        s.reconnecting = true
        scope.launch {
            for (wait in longArrayOf(300, 1000, 2500, 5000, 10000)) {
                delay(wait)
                if (s.manual || s.ready) break
                ui { s.state = "Forbinder igen …" }
                val w = CompletableDeferred<Boolean>(); s.connectWait = w
                main.post { if (s.gatt == null) connect(s) }
                if (withTimeoutOrNull(8000) { w.await() } == true) break
                main.post { if (!s.ready) closeGatt(s) }
                delay(200)
            }
            s.reconnecting = false
            if (!s.ready && !s.manual) ui { s.state = "Kunne ikke forbinde igen. Tryk Forbind." }
        }
    }

    // ---------- afsendelse ----------
    /** Én kommando af hver slags i køen: en ny farve erstatter den farve, der venter. */
    fun send(s: Stick, frame: ByteArray) {
        if (!s.ready) return
        val k = Proto.kind(frame)
        synchronized(s.lock) {
            s.queue.removeAll { Proto.kind(it) == k }
            s.queue.addLast(frame)
        }
    }

    fun all(frame: ByteArray) = connectedSticks.forEach { send(it, frame) }

    /** Giv hver stav sin egen farve: fn(nummer, antal). */
    fun each(fn: (Int, Int) -> Rgb) {
        val list = connectedSticks
        list.forEachIndexed { i, s -> send(s, Proto.color(fn(i, list.size))) }
    }

    fun clearColors() = sticks.forEach { s -> synchronized(s.lock) { s.queue.removeAll { Proto.kind(it) == 5 } } }

    private fun startWriter(s: Stick) {
        s.writer?.cancel()
        s.writer = scope.launch {
            while (isActive && s.ready) {
                val f = synchronized(s.lock) { s.queue.removeFirstOrNull() }
                if (f == null) {
                    // keep-alive: gentag sidste kommando efter 3 sekunders stilhed
                    val last = s.lastFrame
                    if (last != null && SystemClock.elapsedRealtime() - s.lastWrite > 3000) send(s, last)
                    delay(5); continue
                }
                val t0 = SystemClock.elapsedRealtime()
                val ok = write(s, f)
                val took = SystemClock.elapsedRealtime() - t0
                if (ok) { s.lastFrame = f; s.lastWrite = SystemClock.elapsedRealtime() }
                s.gap = if (took > 350) minOf(200L, s.gap * 2) else maxOf(25L, (s.gap * 0.9).toLong())
                delay(s.gap)
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun write(s: Stick, f: ByteArray): Boolean {
        val g = s.gatt ?: return false
        val ch = s.ch ?: return false
        val canAck = (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
        val withAck = useAck && canAck
        val type = if (withAck || (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val d = CompletableDeferred<Boolean>()
        s.ack = d
        val started = try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(ch, f, type) == BluetoothStatusCodes.SUCCESS
            } else {
                ch.writeType = type
                ch.value = f
                g.writeCharacteristic(ch)
            }
        } catch (e: Exception) { false }
        if (!started) { s.ack = null; return false }
        val res = withTimeoutOrNull(if (type == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) 600L else 150L) { d.await() }
        s.ack = null
        // uden kvittering betragtes kommandoen som sendt, selv hvis telefonen ikke melder tilbage
        return res ?: (type == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
    }
}
