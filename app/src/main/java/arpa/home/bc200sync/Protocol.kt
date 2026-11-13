package arpa.home.bc200sync

import java.io.ByteArrayOutputStream

/**
 * Wire format of the BC200 "fda0" BLE service. No Android dependencies, so it is unit-testable.
 *
 * Frame: 7E, escaped(header + body + CRC), 7F. Bytes 7D/7E/7F are escaped as 7D 01/02/03.
 * Header: byte 0 = cmd<<6 | trans<<4 | sid; bytes 1-2 = big-endian length of body + 5.
 * Body: 2-byte big-endian object id, then a Protocol Buffers message.
 */
object Protocol {
    const val CMD_GET = 0
    const val CMD_POST = 1
    const val TRANS_REQUEST = 0
    const val TRANS_ACK = 2

    const val OID_DEVICE_INFO = 1
    const val OID_FUNCTION = 4
    const val OID_GET_FILE = 41
    const val OID_DELETE_FILE = 42
    const val OID_FILE_INFO = 43
    const val OID_FILE_DATA = 44
    const val OID_STOP_FILE = 45
    const val OID_FAST_OVER = 46
    const val OID_FAST_RESULT = 47
    const val OID_FILE_STATUS = 50
    const val OID_STORAGE = 51

    fun crc16(d: ByteArray, len: Int = d.size): Int {
        var c = 0xFFFF
        for (i in 0 until len) {
            c = ((c shl 8) or (c ushr 8)) and 0xFFFF
            c = c xor (d[i].toInt() and 0xFF)
            c = c xor ((c and 0xFF) ushr 4)
            c = (c xor (c shl 12)) and 0xFFFF
            c = c xor (((c and 0xFF) shl 5) and 0xFFFF)
        }
        return c
    }

    fun escape(d: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(d.size + 8)
        for (b in d) {
            val v = b.toInt() and 0xFF
            if (v in 125..127) {
                o.write(125)
                o.write(v - 124)
            } else {
                o.write(v)
            }
        }
        return o.toByteArray()
    }

    fun unescape(d: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(d.size)
        var i = 0
        while (i < d.size) {
            val v = d[i].toInt() and 0xFF
            val next = if (i + 1 < d.size) d[i + 1].toInt() and 0xFF else 0
            if (v == 125 && next in 1..3) {
                o.write(124 + next)
                i += 2
            } else {
                o.write(v)
                i++
            }
        }
        return o.toByteArray()
    }

    fun frame(cmd: Int, trans: Int, sid: Int, body: ByteArray = ByteArray(0)): ByteArray {
        val n = body.size + 5
        val h = ByteArray(3 + body.size)
        h[0] = ((cmd shl 6) or (trans shl 4) or sid).toByte()
        h[1] = (n ushr 8).toByte()
        h[2] = n.toByte()
        body.copyInto(h, 3)
        val c = crc16(h)
        val esc = escape(h + byteArrayOf((c ushr 8).toByte(), c.toByte()))
        return byteArrayOf(0x7E) + esc + byteArrayOf(0x7F)
    }

    fun request(cmd: Int, sid: Int, oid: Int, msg: ByteArray = ByteArray(0)): ByteArray =
        frame(cmd, TRANS_REQUEST, sid, byteArrayOf((oid ushr 8).toByte(), oid.toByte()) + msg)

    class Frame(val cmd: Int, val trans: Int, val sid: Int, val oid: Int, val value: ByteArray)

    /** Parses the bytes between 7E and 7F. Returns null on a bad length or CRC. */
    fun parse(inner: ByteArray): Frame? {
        val f = unescape(inner)
        if (f.size < 5) return null
        val got = ((f[f.size - 2].toInt() and 0xFF) shl 8) or (f[f.size - 1].toInt() and 0xFF)
        if (crc16(f, f.size - 2) != got) return null
        val h = f[0].toInt() and 0xFF
        val oid = if (f.size >= 7) ((f[3].toInt() and 0xFF) shl 8) or (f[4].toInt() and 0xFF) else 0
        val value = if (f.size > 7) f.copyOfRange(5, f.size - 2) else ByteArray(0)
        return Frame(h ushr 6, (h ushr 4) and 3, h and 15, oid, value)
    }

    /** Reassembles frames from BLE notifications, which can split a frame anywhere. */
    class Deframer {
        private var buf = ByteArray(0)

        fun feed(chunk: ByteArray): List<ByteArray> {
            buf += chunk
            val out = ArrayList<ByteArray>()
            while (true) {
                val a = buf.indexOf(0x7E.toByte())
                if (a < 0) {
                    buf = ByteArray(0)
                    return out
                }
                var e = a + 1
                while (e < buf.size && buf[e] != 0x7F.toByte()) e++
                if (e >= buf.size) {
                    buf = buf.copyOfRange(a, buf.size)
                    return out
                }
                out.add(buf.copyOfRange(a + 1, e))
                buf = buf.copyOfRange(e + 1, buf.size)
            }
        }
    }

    private fun varint(o: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            o.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        o.write(v.toInt())
    }

    /** Decodes a message to field number -> Long (varint) or ByteArray (length-delimited). */
    fun pbParse(b: ByteArray): Map<Int, Any> {
        val out = HashMap<Int, Any>()
        var p = 0
        fun rv(): Long {
            var v = 0L
            var s = 0
            while (true) {
                val c = b[p++].toInt() and 0xFF
                v = v or ((c and 0x7F).toLong() shl s)
                s += 7
                if (c < 0x80) return v
            }
        }
        try {
            while (p < b.size) {
                val k = rv().toInt()
                val field = k ushr 3
                when (k and 7) {
                    0 -> out[field] = rv()
                    1 -> p += 8
                    2 -> {
                        val n = rv().toInt()
                        out[field] = b.copyOfRange(p, p + n)
                        p += n
                    }
                    5 -> p += 4
                    else -> return out
                }
            }
        } catch (_: IndexOutOfBoundsException) {
            return out
        } catch (_: IllegalArgumentException) {
            return out
        }
        return out
    }

    fun zigzag(v: Long): Long = (v ushr 1) xor -(v and 1)

    /** FileGetReq and FileDeleteReq share the same layout: field 1 = file name. */
    fun fileGetRequest(name: String): ByteArray = fileNameMessage(name)

    fun fileDeleteRequest(name: String): ByteArray = fileNameMessage(name)

    private fun fileNameMessage(name: String): ByteArray {
        val n = name.toByteArray()
        val o = ByteArrayOutputStream()
        o.write(0x0A)
        varint(o, n.size.toLong())
        o.write(n)
        return o.toByteArray()
    }

    /** FileInfo for an upload: field 1 = name, field 2 = size (sint32, zigzag). */
    fun fileInfoRequest(name: String, size: Int): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(fileNameMessage(name))
        o.write(0x10)
        varint(o, ((size shl 1) xor (size shr 31)).toLong() and 0xFFFFFFFFL)
        return o.toByteArray()
    }

    const val UPLOAD_CHUNK = 128

    /**
     * File data for an upload, one POST per packet: [index, mark] + up to 128 bytes, where mark is
     * 0 first, 1 middle, 2 last, 3 the only packet.
     */
    fun uploadPackets(data: ByteArray): List<ByteArray> {
        val n = maxOf(1, (data.size + UPLOAD_CHUNK - 1) / UPLOAD_CHUNK)
        return (0 until n).map { i ->
            val mark = when {
                n == 1 -> 3
                i == 0 -> 0
                i == n - 1 -> 2
                else -> 1
            }
            byteArrayOf(i.toByte(), mark.toByte()) +
                data.copyOfRange(i * UPLOAD_CHUNK, minOf(data.size, (i + 1) * UPLOAD_CHUNK))
        }
    }

    val TRANSFER_COMPLETE = byteArrayOf(0x08, 0x01)

    /** FileDirectTransResult asking the device to resend packets (packed repeated field 2). */
    fun resendRequest(missing: List<Int>): ByteArray {
        val list = ByteArrayOutputStream()
        missing.forEach { varint(list, it.toLong()) }
        val o = ByteArrayOutputStream()
        o.write(0x12)
        varint(o, list.size().toLong())
        o.write(list.toByteArray())
        return o.toByteArray()
    }

    data class Ride(val name: String, val size: Int)

    fun parseFileList(data: ByteArray): List<Ride> =
        String(data).split("\r\n").mapNotNull { line ->
            val p = line.split(" ")
            val size = if (p.size == 2) p[1].toIntOrNull() else null
            if (size != null) Ride(p[0], size) else null
        }

    /** Fast-transfer payloads, which arrive as: 4-byte index, data, 1-byte sum. */
    class FastFile(val size: Int) {
        private val packets = HashMap<Int, ByteArray>()
        private var payload = 0

        val received: Int get() = packets.values.sumOf { it.size }

        fun add(pkt: ByteArray): Boolean {
            if (pkt.size < 5) return false
            var sum = 0
            for (i in 0 until pkt.size - 1) sum += pkt[i].toInt() and 0xFF
            if (sum and 0xFF != pkt[pkt.size - 1].toInt() and 0xFF) return false
            var idx = 0
            for (i in 0 until 4) idx = (idx shl 8) or (pkt[i].toInt() and 0xFF)
            val data = pkt.copyOfRange(4, pkt.size - 1)
            packets[idx] = data
            if (data.size > payload) payload = data.size
            return true
        }

        /** Indices still needed, or null when no packet arrived to derive the size from. */
        fun missing(): List<Int>? {
            if (size == 0) return emptyList()
            if (payload == 0) return null
            val total = (size + payload - 1) / payload
            return (0 until total).filter { it !in packets }
        }

        fun assemble(): ByteArray? {
            if (missing()?.isEmpty() != true) return null
            val out = ByteArrayOutputStream(size)
            packets.keys.sorted().forEach { out.write(packets.getValue(it)) }
            return out.toByteArray().takeIf { it.size == size }
        }
    }

    private val FIT_TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
    )

    /** FIT checksum. Over a whole file, including its trailing CRC, the result is 0. */
    fun fitCrc(d: ByteArray, len: Int = d.size): Int {
        var c = 0
        for (i in 0 until len) {
            val b = d[i].toInt() and 0xFF
            var t = FIT_TABLE[c and 0xF]
            c = (c ushr 4) and 0x0FFF
            c = c xor t xor FIT_TABLE[b and 0xF]
            t = FIT_TABLE[c and 0xF]
            c = (c ushr 4) and 0x0FFF
            c = c xor t xor FIT_TABLE[(b ushr 4) and 0xF]
        }
        return c
    }
}
