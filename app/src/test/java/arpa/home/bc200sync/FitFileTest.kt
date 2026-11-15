package arpa.home.bc200sync

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FitFileTest {
    /** Builds a FIT file: [body] gets a 14-byte header and the trailing CRC. */
    private fun fit(body: ByteArray): ByteArray {
        val h = ByteArrayOutputStream()
        h.write(14); h.write(0x20); h.write(byteArrayOf(0x4C, 0x08)) // header size, protocol, profile
        h.write(le(body.size.toLong(), 4))
        h.write(".FIT".toByteArray())
        val head = h.toByteArray()
        val c = Protocol.fitCrc(head)
        val all = head + byteArrayOf(c.toByte(), (c ushr 8).toByte()) + body
        val crc = Protocol.fitCrc(all)
        return all + byteArrayOf(crc.toByte(), (crc ushr 8).toByte())
    }

    private fun le(v: Long, n: Int) = ByteArray(n) { (v ushr (8 * it)).toByte() }
    private fun be(v: Long, n: Int) = ByteArray(n) { (v ushr (8 * (n - 1 - it))).toByte() }

    /** Definition message: fields as (number, size, base type). */
    private fun def(local: Int, global: Int, fields: List<Triple<Int, Int, Int>>, big: Boolean = false): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(0x40 or local); o.write(0); o.write(if (big) 1 else 0)
        o.write(if (big) be(global.toLong(), 2) else le(global.toLong(), 2))
        o.write(fields.size)
        fields.forEach { o.write(it.first); o.write(it.second); o.write(it.third) }
        return o.toByteArray()
    }

    private val recordFields = listOf(
        Triple(253, 4, 0x86), Triple(0, 4, 0x85), Triple(1, 4, 0x85), Triple(2, 2, 0x84),
        Triple(3, 1, 0x02), Triple(4, 1, 0x02), Triple(5, 4, 0x86), Triple(6, 2, 0x84), Triple(13, 1, 0x01),
    )

    private fun record(ts: Long, lat: Int, lon: Int, altM: Double, hr: Int, cad: Int, distM: Double, kmh: Double, temp: Int) =
        byteArrayOf(0) + le(ts, 4) + le(lat.toLong(), 4) + le(lon.toLong(), 4) +
            le(Math.round((altM + 500) * 5), 2) + byteArrayOf(hr.toByte(), cad.toByte()) +
            le(Math.round(distM * 100), 4) + le(Math.round(kmh / 3.6 * 1000), 2) + byteArrayOf(temp.toByte())

    private val t0 = 1_100_000_000L // FIT time of the first record

    private fun threeRecords(): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(def(0, 20, recordFields))
        o.write(record(t0, 1_000_000, 2_000_000, 300.0, 120, 80, 0.0, 18.0, 15))
        o.write(record(t0 + 10, 1_001_000, 2_001_000, 305.0, 130, 0, 50.0, 18.0, 16))
        // No heart rate (0xFF = invalid) in the last record.
        o.write(record(t0 + 20, 1_002_000, 2_002_000, 302.0, 0xFF, 90, 100.0, 36.0, 17))
        return o.toByteArray()
    }

    @Test fun recordsOnlyGiveSummaryFromRecords() {
        val ride = FitFile.parse(fit(threeRecords()))
        val s = ride.summary
        val r = ride.samples
        assertEquals(3, r.size)
        assertEquals(20f, r.timeS[2], 0f)
        assertEquals(100f, r.distanceM[2], 0.01f)
        assertEquals(10f, r.speed[2], 0.01f) // 36 km/h
        assertTrue(r.hr[2].isNaN())
        assertEquals(305f, r.altitudeM[1], 0.01f)
        assertEquals(1_001_000 * 180.0 / 2147483648.0, r.lat[1], 1e-9)
        assertEquals(t0 + FitFile.FIT_EPOCH, s.start)
        assertEquals(100.0, s.distanceM!!, 0.01)
        assertEquals(20.0, s.elapsedS!!, 0.0)
        assertEquals(125, s.avgHr) // records with a value only
        assertEquals(130, s.maxHr)
        assertEquals(85, s.avgCadence) // pedalling only: zero cadence left out
        assertEquals(5, s.ascentM)
        assertEquals(3, s.descentM)
        assertEquals(36.0, s.maxSpeed!! * 3.6, 0.01)
        assertEquals(16, s.avgTempC)
        assertNull(s.calories)
    }

    @Test fun sessionValuesWin() {
        val o = ByteArrayOutputStream()
        o.write(threeRecords())
        o.write(
            def(
                1, 18,
                listOf(
                    Triple(2, 4, 0x86), Triple(7, 4, 0x86), Triple(9, 4, 0x86), Triple(59, 4, 0x86),
                    Triple(14, 2, 0x84), Triple(11, 2, 0x84), Triple(22, 2, 0x84), Triple(16, 1, 0x02),
                    Triple(17, 1, 0x02),
                ),
            ),
        )
        o.write(
            byteArrayOf(1) + le(t0 - 5, 4) + le(3_600_000, 4) + le(2_000_000, 4) + le(3_000_000, 4) +
                le(6_667, 2) + le(450, 2) + le(210, 2) + byteArrayOf(140.toByte(), 170.toByte()),
        )
        val s = FitFile.parse(fit(o.toByteArray())).summary
        assertEquals(t0 - 5 + FitFile.FIT_EPOCH, s.start)
        assertEquals(3600.0, s.elapsedS!!, 0.0)
        assertEquals(3000.0, s.movingS!!, 0.0)
        assertEquals(20_000.0, s.distanceM!!, 0.0)
        assertEquals(6.667, s.avgSpeed!!, 1e-6)
        assertEquals(450, s.calories)
        assertEquals(210, s.ascentM)
        assertEquals(140, s.avgHr)
        assertEquals(170, s.maxHr)
    }

    @Test fun bigEndianDefinitionAndCompressedTimestamp() {
        val o = ByteArrayOutputStream()
        o.write(def(0, 20, listOf(Triple(253, 4, 0x86), Triple(3, 1, 0x02)), big = true))
        o.write(byteArrayOf(0) + be(t0, 4) + byteArrayOf(100))
        o.write(def(1, 20, listOf(Triple(3, 1, 0x02)))) // records without a timestamp field
        // Compressed header: bit 7, local type 1, 5-bit time offset.
        val off = ((t0 + 3) and 0x1F).toInt()
        o.write(byteArrayOf((0x80 or (1 shl 5) or off).toByte(), 110))
        val r = FitFile.parse(fit(o.toByteArray())).samples
        assertEquals(2, r.size)
        assertEquals(3f, r.timeS[1], 0f)
        assertEquals(110f, r.hr[1], 0f)
        assertTrue(!r.hasPosition)
    }

    @Test fun badFilesAreRefused() {
        val good = fit(threeRecords())
        for (bad in listOf(good.copyOf(good.size - 1), good.copyOf().also { it[30] = (it[30] + 1).toByte() }, ByteArray(20))) {
            try {
                FitFile.parse(bad)
                fail("parsed a bad file")
            } catch (_: FitFile.FitException) {
            }
        }
    }

    /** Optional check against real rides: FIT_SAMPLE_DIR=<folder of .fit files>. Prints no data. */
    @Test fun realFilesIfGiven() {
        val dir = System.getenv("FIT_SAMPLE_DIR") ?: return
        val files = File(dir).listFiles { f -> f.name.endsWith(".fit") }.orEmpty()
        assertTrue(files.isNotEmpty())
        for (f in files) {
            val ride = FitFile.parse(f.readBytes())
            assertTrue(f.name, ride.samples.size > 100 && ride.summary.distanceM != null && ride.summary.start != null)
        }
        println("parsed ${files.size} real files")
    }
}
