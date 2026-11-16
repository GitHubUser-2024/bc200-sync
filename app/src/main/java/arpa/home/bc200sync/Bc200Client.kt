package arpa.home.bc200sync

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * BLE session with a BC200. Everything runs on the main thread, including GATT callbacks.
 * Besides reads, protocol ACKs and transfer control it sends only two kinds of changes: deleting
 * a ride after it was copied, and uploading the settings file the user edited.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class Bc200Client(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onStatus(text: String)
        fun onDeviceInfo(text: String)
        fun onRides(rides: List<Protocol.Ride>)
        fun onError(text: String)
        fun onDisconnected()
    }

    private companion object {
        fun uuid16(s: String): UUID = UUID.fromString("0000$s-0000-1000-8000-00805f9b34fb")
        val SERVICE: UUID = uuid16("fda0")
        val CH_GET: UUID = uuid16("fda1")
        val CH_POST: UUID = uuid16("fda2")
        val CH_PUSH: UUID = uuid16("fda3")
        val CH_FILE: UUID = uuid16("fda4")
        val CCCD: UUID = uuid16("2902")
        val BATTERY_SVC: UUID = uuid16("180f")
        val BATTERY: UUID = uuid16("2a19")
        const val TIMEOUT_MS = 15_000L
        const val MAX_RESEND_ROUNDS = 10
        const val MAX_RESEND_LIST = 50
    }

    private sealed class Op {
        class Write(val ch: BluetoothGattCharacteristic, val data: ByteArray) : Op()
        class Notify(val ch: BluetoothGattCharacteristic) : Op()
        class Read(val ch: BluetoothGattCharacteristic) : Op()
    }

    private class Transfer(
        val name: String,
        val progress: (Int, Int) -> Unit,
        val done: (ByteArray?, String?) -> Unit,
    ) {
        var file: Protocol.FastFile? = null
        var rounds = 0
    }

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private val ops = ArrayDeque<Op>()
    private var opBusy = false
    private var mtu = 23
    private var sid = 0
    private val deframers = HashMap<UUID, Protocol.Deframer>()
    private val waiting = HashMap<Int, (ByteArray) -> Unit>()
    private var transfer: Transfer? = null
    private val acks = HashMap<Int, () -> Unit>()
    private var uploading = false
    private var battery: Int? = null
    private var infoText = ""
    private var ready = false

    private val timeout = Runnable { fail("The BC200 stopped responding.") }

    val isConnected: Boolean get() = gatt != null

    /** Connected and past the start-up reads (device info, functions, storage). */
    val isOnline: Boolean get() = gatt != null && ready
    val isReady: Boolean get() = ready && transfer == null && !uploading

    fun connect(device: BluetoothDevice) {
        if (gatt != null) return
        listener.onStatus("Connecting to your BC200" + (device.name?.let { " ($it)" } ?: "") + "…")
        armTimeout()
        gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK, handler)
    }

    fun disconnect() {
        val g = gatt ?: return
        g.disconnect()
        cleanup(g)
        listener.onDisconnected()
    }

    private fun cleanup(g: BluetoothGatt) {
        handler.removeCallbacks(timeout)
        g.close()
        gatt = null
        ops.clear()
        opBusy = false
        waiting.clear()
        acks.clear()
        deframers.clear()
        ready = false
        val up = uploadDone
        uploadDone = null
        uploading = false
        up?.invoke("Connection lost while sending the settings.")
        val t = transfer
        transfer = null
        t?.done?.invoke(null, "Connection lost during download.")
    }

    private fun armTimeout() {
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, TIMEOUT_MS)
    }

    private fun fail(msg: String) {
        listener.onError(msg)
        disconnect()
    }

    private fun char(u: UUID) = gatt?.getService(SERVICE)?.getCharacteristic(u)

    private fun enqueue(op: Op) {
        ops.addLast(op)
        pump()
    }

    private fun pump() {
        if (opBusy) return
        val g = gatt ?: return
        val op = ops.removeFirstOrNull() ?: return
        opBusy = true
        val started = when (op) {
            is Op.Write -> {
                op.ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                op.ch.value = op.data
                g.writeCharacteristic(op.ch)
            }
            is Op.Notify -> {
                g.setCharacteristicNotification(op.ch, true)
                val d = op.ch.getDescriptor(CCCD)
                if (d == null) {
                    false
                } else {
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(d)
                }
            }
            is Op.Read -> g.readCharacteristic(op.ch)
        }
        if (!started) {
            opBusy = false
            fail("Bluetooth operation could not start.")
        }
    }

    private fun opDone() {
        opBusy = false
        pump()
    }

    private fun writeFrame(ch: BluetoothGattCharacteristic, frame: ByteArray) {
        val size = maxOf(20, mtu - 3)
        var i = 0
        while (i < frame.size) {
            enqueue(Op.Write(ch, frame.copyOfRange(i, minOf(i + size, frame.size))))
            i += size
        }
    }

    private fun send(cmd: Int, oid: Int, msg: ByteArray = ByteArray(0)) {
        sid = (sid + 1) and 15
        val ch = char(if (cmd == Protocol.CMD_GET) CH_GET else CH_POST) ?: return
        writeFrame(ch, Protocol.request(cmd, sid, oid, msg))
    }

    private fun get(oid: Int, msg: ByteArray = ByteArray(0), cb: (ByteArray) -> Unit) {
        waiting[oid] = cb
        armTimeout()
        send(Protocol.CMD_GET, oid, msg)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                listener.onStatus("Connected. Reading device…")
                g.requestMtu(247)
                return
            }
            if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (gatt == null) return
                val hadSession = ready
                cleanup(g)
                if (!hadSession) listener.onError("Could not connect (code $status). Try again.")
                listener.onDisconnected()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = value
            if (!g.discoverServices()) fail("Service discovery could not start.")
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE)
            if (status != BluetoothGatt.GATT_SUCCESS || svc == null) {
                fail("This device does not offer the BC200 sync service.")
                return
            }
            for (u in listOf(CH_GET, CH_POST, CH_PUSH, CH_FILE)) {
                val ch = svc.getCharacteristic(u)
                if (ch == null) {
                    fail("The BC200 sync service is incomplete.")
                    return
                }
                enqueue(Op.Notify(ch))
            }
            g.getService(BATTERY_SVC)?.getCharacteristic(BATTERY)?.let { enqueue(Op.Read(it)) }
            start()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Could not subscribe to the BC200 (code $status).")
                return
            }
            opDone()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Write to the BC200 failed (code $status).")
                return
            }
            opDone()
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                readDone(c.uuid, c.value, status)
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray, status: Int) {
            readDone(c.uuid, v, status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) received(c, c.value)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray) {
            received(c, v)
        }
    }

    private fun readDone(u: UUID, v: ByteArray?, status: Int) {
        if (u == BATTERY && status == BluetoothGatt.GATT_SUCCESS && v != null && v.isNotEmpty()) {
            battery = v[0].toInt() and 0xFF
            publishInfo()
        }
        opDone()
    }

    private fun received(c: BluetoothGattCharacteristic, v: ByteArray) {
        if (c.uuid == CH_FILE) {
            val t = transfer ?: return
            val f = t.file ?: return
            armTimeout()
            if (f.add(v)) t.progress(f.received, f.size)
            return
        }
        val d = deframers.getOrPut(c.uuid) { Protocol.Deframer() }
        for (inner in d.feed(v)) {
            val p = Protocol.parse(inner) ?: continue
            if (p.trans == Protocol.TRANS_ACK) {
                acks.remove(p.sid)?.let { cb ->
                    if (acks.isEmpty() && waiting.isEmpty() && transfer == null) handler.removeCallbacks(timeout)
                    cb()
                }
                continue
            }
            writeFrame(c, Protocol.frame(p.cmd, Protocol.TRANS_ACK, p.sid))
            dispatch(p.oid, p.value)
        }
    }

    private fun dispatch(oid: Int, value: ByteArray) {
        armTimeout()
        when (oid) {
            Protocol.OID_FILE_INFO -> {
                val t = transfer ?: return
                val size = (Protocol.pbParse(value)[2] as? Long)?.let(Protocol::zigzag)?.toInt() ?: 0
                // An empty file (filelist.txt with no rides) also ends with "transfer over"; the
                // BC200 stays busy until it gets "complete" AFTER that, so no shortcut here.
                t.file = Protocol.FastFile(size)
            }
            Protocol.OID_FAST_OVER -> transfer?.let(::transferOver)
            else -> waiting.remove(oid)?.invoke(value)
        }
        if (waiting.isEmpty() && acks.isEmpty() && transfer == null) handler.removeCallbacks(timeout)
    }

    /** POST that waits for the device's protocol ACK (no ACK within the timeout disconnects). */
    private fun postAcked(oid: Int, msg: ByteArray, cb: () -> Unit) {
        sid = (sid + 1) and 15
        val ch = char(CH_POST) ?: return
        acks[sid] = cb
        armTimeout()
        writeFrame(ch, Protocol.request(Protocol.CMD_POST, sid, oid, msg))
    }

    private var uploadDone: ((String?) -> Unit)? = null

    /**
     * Uploads a whole file the way the BC200 app does: FileInfo (oid 43), then the data packets
     * (oid 44), each acknowledged by the device. [done] gets null on success or an error text.
     * Callers must read the file back to confirm the device took it.
     */
    fun upload(name: String, data: ByteArray, progress: (Int, Int) -> Unit, done: (String?) -> Unit) {
        if (!isReady) {
            done("The BC200 is busy. Try again in a moment.")
            return
        }
        uploading = true
        uploadDone = done
        fun finish(err: String?) {
            if (uploadDone !== done) return
            uploadDone = null
            uploading = false
            done(err)
        }
        val packets = Protocol.uploadPackets(data)
        fun sendPacket(i: Int) {
            if (i == packets.size) {
                finish(null)
                return
            }
            progress(i, packets.size)
            postAcked(Protocol.OID_FILE_DATA, packets[i]) { sendPacket(i + 1) }
        }
        get(Protocol.OID_FILE_STATUS) { v ->
            if (Protocol.pbParse(v).containsKey(1)) {
                finish("The BC200 is busy. Try again in a moment.")
                return@get
            }
            postAcked(Protocol.OID_FILE_INFO, Protocol.fileInfoRequest(name, data.size)) { sendPacket(0) }
        }
    }

    private fun start() {
        get(Protocol.OID_DEVICE_INFO) { v ->
            val m = Protocol.pbParse(v)
            fun s(f: Int) = (m[f] as? ByteArray)?.let { String(it) } ?: "?"
            infoText = "Firmware ${s(4)} · serial ${s(5)}"
            publishInfo()
            get(Protocol.OID_FUNCTION) { fv ->
                if (Protocol.pbParse(fv)[6] != 1L) {
                    fail("This firmware does not support the fast transfer mode used by the app.")
                    return@get
                }
                get(Protocol.OID_STORAGE) { sv ->
                    val st = Protocol.pbParse(sv)
                    val free = (st[1] as? Long)?.let(Protocol::zigzag) ?: 0
                    val total = (st[2] as? Long)?.let(Protocol::zigzag) ?: 0
                    infoText += "\nStorage: ${free / 1024} MB free of ${total / 1024} MB"
                    publishInfo()
                    ready = true
                    refreshRides()
                }
            }
        }
    }

    private fun publishInfo() {
        val b = battery?.let { "Battery $it% · " } ?: ""
        listener.onDeviceInfo(b + infoText)
    }

    fun refreshRides() {
        listener.onStatus("Reading ride list…")
        download("filelist.txt", { _, _ -> }) { data, err ->
            if (data == null) {
                listener.onError(err ?: "Could not read the ride list.")
            } else {
                listener.onRides(Protocol.parseFileList(data))
            }
        }
    }

    fun download(name: String, progress: (Int, Int) -> Unit, done: (ByteArray?, String?) -> Unit) {
        if (transfer != null || uploading) {
            done(null, "Another transfer is running.")
            return
        }
        val t = Transfer(name, progress, done)
        transfer = t
        get(Protocol.OID_FILE_STATUS) { v ->
            if (Protocol.pbParse(v).containsKey(1)) {
                endTransfer(t, null, "The BC200 is busy. Try again in a moment.")
                return@get
            }
            get(Protocol.OID_GET_FILE, Protocol.fileGetRequest(name)) { r ->
                if (Protocol.pbParse(r)[1] != 1L) endTransfer(t, null, "$name is not on the BC200.")
            }
        }
    }

    /**
     * Deletes one file on the BC200. The device sends no reply to this request, so [done] only
     * means the request was sent; callers must confirm by reading the ride list again.
     */
    fun deleteFile(name: String, done: (Boolean) -> Unit) {
        if (!isReady) {
            done(false)
            return
        }
        send(Protocol.CMD_POST, Protocol.OID_DELETE_FILE, Protocol.fileDeleteRequest(name))
        handler.postDelayed({ done(gatt != null) }, 1500)
    }

    private fun transferOver(t: Transfer) {
        val f = t.file
        val missing = f?.missing()
        if (f == null || missing == null) {
            abortTransfer(t, "No data arrived for ${t.name}.")
            return
        }
        if (missing.isEmpty()) {
            val data = f.assemble()
            if (data == null) abortTransfer(t, "${t.name} arrived with the wrong size.") else finishTransfer(t, data)
            return
        }
        if (++t.rounds > MAX_RESEND_ROUNDS) {
            abortTransfer(t, "Too many lost packets for ${t.name}.")
            return
        }
        send(Protocol.CMD_POST, Protocol.OID_FAST_RESULT, Protocol.resendRequest(missing.take(MAX_RESEND_LIST)))
    }

    private fun finishTransfer(t: Transfer, data: ByteArray) {
        send(Protocol.CMD_POST, Protocol.OID_FAST_RESULT, Protocol.TRANSFER_COMPLETE)
        if (t.name.endsWith(".fit") && data.size >= 14 && Protocol.fitCrc(data) != 0) {
            endTransfer(t, null, "${t.name} failed its FIT checksum.")
        } else {
            endTransfer(t, data, null)
        }
    }

    private fun abortTransfer(t: Transfer, msg: String) {
        send(Protocol.CMD_POST, Protocol.OID_STOP_FILE)
        endTransfer(t, null, msg)
    }

    private fun endTransfer(t: Transfer, data: ByteArray?, err: String?) {
        if (transfer !== t) return
        transfer = null
        t.done(data, err)
    }
}
