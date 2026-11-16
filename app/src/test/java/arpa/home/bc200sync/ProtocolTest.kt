package arpa.home.bc200sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun toHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun packet(index: Int, data: ByteArray): ByteArray {
        val p = byteArrayOf(0, 0, (index ushr 8).toByte(), index.toByte()) + data
        return p + byteArrayOf(p.sumOf { it.toInt() and 0xFF }.toByte())
    }

    @Test fun emptyFileIsCompleteWithoutPackets() {
        val f = Protocol.FastFile(0)
        assertEquals(emptyList<Int>(), f.missing())
        assertArrayEquals(ByteArray(0), f.assemble())
    }

    @Test fun crcCheckValue() = assertEquals(0x29B1, Protocol.crc16("123456789".toByteArray()))

    // Same bytes as the request that the real BC200 answered.
    @Test fun deviceInfoRequestMatchesTestedFrame() =
        assertEquals("7e03000700016a6f7f", toHex(Protocol.request(Protocol.CMD_GET, 3, Protocol.OID_DEVICE_INFO)))

    @Test fun escapeRoundTrip() {
        val all = ByteArray(256) { it.toByte() }
        assertArrayEquals(all, Protocol.unescape(Protocol.escape(all)))
    }

    @Test fun frameSurvivesSplitNotifications() {
        val f = Protocol.request(Protocol.CMD_GET, 5, Protocol.OID_GET_FILE, Protocol.fileGetRequest("filelist.txt"))
        val d = Protocol.Deframer()
        assertEquals(0, d.feed(f.copyOfRange(0, 4)).size)
        val out = d.feed(f.copyOfRange(4, f.size))
        assertEquals(1, out.size)
        val p = assertNotNullFrame(out[0])
        assertEquals(Protocol.OID_GET_FILE, p.oid)
        assertEquals(5, p.sid)
    }

    private fun assertNotNullFrame(inner: ByteArray): Protocol.Frame {
        val p = Protocol.parse(inner)
        assertNotNull(p)
        return p!!
    }

    @Test fun corruptFrameRejected() {
        val f = Protocol.request(Protocol.CMD_GET, 1, Protocol.OID_STORAGE)
        val inner = f.copyOfRange(1, f.size - 1)
        inner[3] = (inner[3].toInt() xor 1).toByte()
        assertNull(Protocol.parse(inner))
    }

    // Storage response captured from the device: 14332 KB free of 15816 KB.
    @Test fun storageResponseDecodes() {
        val m = Protocol.pbParse(hex("08f8df011090f701"))
        assertEquals(14332L, Protocol.zigzag(m[1] as Long))
        assertEquals(15816L, Protocol.zigzag(m[2] as Long))
    }

    @Test fun emptyMessageMeansDefaults() = assertEquals(0, Protocol.pbParse(ByteArray(0)).size)

    @Test fun fileListParses() {
        val l = Protocol.parseFileList("20260101120000.fit 78434\r\n20260102120000.fit 5\r\n".toByteArray())
        assertEquals(listOf(Protocol.Ride("20260101120000.fit", 78434), Protocol.Ride("20260102120000.fit", 5)), l)
    }

    @Test fun fastFileReportsThenFillsGap() {
        val f = Protocol.FastFile(10)
        assert(f.add(packet(0, ByteArray(4) { 1 })))
        assert(f.add(packet(2, ByteArray(2) { 3 })))
        assertEquals(listOf(1), f.missing())
        assertNull(f.assemble())
        assert(f.add(packet(1, ByteArray(4) { 2 })))
        assertEquals(emptyList<Int>(), f.missing())
        assertEquals("01010101020202020303", toHex(f.assemble()!!))
    }

    @Test fun fastFileRejectsBadChecksum() {
        val p = packet(0, ByteArray(4) { 1 })
        p[p.size - 1] = (p[p.size - 1] + 1).toByte()
        assertEquals(false, Protocol.FastFile(4).add(p))
    }

    @Test fun fastFileWithNoPacketsIsUnknown() = assertNull(Protocol.FastFile(10).missing())

    // Same body the laptop test sent when it deleted a ride from the real BC200.
    @Test fun deleteRequestMatchesTestedBody() = assertEquals(
        "0a1232303236303631393137353830372e666974",
        toHex(Protocol.fileDeleteRequest("20260619175807.fit")),
    )

    @Test fun resendRequestIsPacked() = assertEquals("1203018101", toHex(Protocol.resendRequest(listOf(1, 129))))

    // Header of a real BC200 FIT file; bytes 12-13 hold the CRC of the first 12 bytes.
    @Test fun fitCrcMatchesRealHeader() =
        assertEquals(0x66B6, Protocol.fitCrc(hex("0e207708 9c960200 2e464954".replace(" ", ""))))
}
