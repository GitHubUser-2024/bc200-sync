package arpa.home.bc200sync

/**
 * Reads a ride (.fit activity file) for the ride page: the summary (FIT "session" message) and
 * every recorded point ("record" messages). Plain Kotlin, no Android classes, so it is unit-tested.
 *
 * Supported: normal and compressed-timestamp headers, little- and big-endian definitions,
 * developer fields (skipped) and FIT "invalid" values (shown as missing). Values missing from the
 * session are worked out from the records.
 */
object FitFile {
    /** Seconds between 1970-01-01 (Unix) and 1989-12-31 (FIT epoch), both UTC. */
    const val FIT_EPOCH = 631_065_600L

    class FitException(msg: String) : Exception(msg)

    class Summary(
        /** Start of the ride, Unix seconds (UTC). */
        val start: Long?,
        /** Whole ride including pauses, seconds. */
        val elapsedS: Double?,
        /** Without pauses, seconds. */
        val movingS: Double?,
        val distanceM: Double?,
        val avgSpeed: Double?, // m/s
        val maxSpeed: Double?,
        val avgHr: Int?,
        val maxHr: Int?,
        val avgCadence: Int?,
        val maxCadence: Int?,
        val ascentM: Int?,
        val descentM: Int?,
        val minAltM: Double?,
        val maxAltM: Double?,
        val avgTempC: Int?,
        val maxTempC: Int?,
        val calories: Int?,
        val laps: Int,
    )

    /** One entry per record message; NaN where the record has no value. */
    class Samples(
        val timeS: FloatArray, // seconds since the first record
        val distanceM: FloatArray,
        val speed: FloatArray, // m/s
        val hr: FloatArray,
        val cadence: FloatArray,
        val altitudeM: FloatArray,
        val tempC: FloatArray,
        val lat: DoubleArray, // degrees
        val lon: DoubleArray,
    ) {
        val size get() = timeS.size
        val hasPosition get() = lat.any { !it.isNaN() }
    }

    class Ride(val summary: Summary, val samples: Samples, val device: String?)

    private const val MSG_FILE_ID = 0
    private const val MSG_SESSION = 18
    private const val MSG_LAP = 19
    private const val MSG_RECORD = 20
    private const val F_TIMESTAMP = 253
    private const val SEMICIRCLE = 180.0 / 2147483648.0

    private class FieldDef(val num: Int, val size: Int, val type: Int)
    private class MsgDef(val global: Int, val big: Boolean, val fields: List<FieldDef>, val devSize: Int)

    fun parse(d: ByteArray): Ride {
        if (d.size < 14) throw FitException("file too short")
        val headerSize = d[0].toInt() and 0xFF
        if (headerSize < 12 || d.size < headerSize) throw FitException("bad header")
        if (String(d, 8, 4, Charsets.US_ASCII) != ".FIT") throw FitException("not a FIT file")
        val dataSize = le32(d, 4)
        val end = headerSize.toLong() + dataSize
        if (end + 2 > d.size) throw FitException("file is cut short")
        if (Protocol.fitCrc(d, (end + 2).toInt()) != 0) throw FitException("checksum does not match")

        val defs = arrayOfNulls<MsgDef>(16)
        val rec = RecordLists()
        val sessions = ArrayList<Map<Int, Double>>()
        var laps = 0
        var fileCreated: Long? = null
        var device: String? = null
        var lastTs = 0L
        var pos = headerSize
        while (pos < end) {
            val h = d[pos].toInt() and 0xFF
            pos++
            if (h and 0x80 != 0) {
                // Compressed timestamp header: 5-bit offset from the last full timestamp.
                val def = defs[(h ushr 5) and 0x03] ?: throw FitException("data before its definition")
                val off = (h and 0x1F).toLong()
                lastTs = if (off >= (lastTs and 0x1F)) (lastTs and 0x1F.inv().toLong()) + off
                else (lastTs and 0x1F.inv().toLong()) + off + 0x20
                if (pos + def.fields.sumOf { it.size } + def.devSize > end) throw FitException("record runs past the end")
                val v = readFields(d, pos, def)
                pos += def.fields.sumOf { it.size } + def.devSize
                if (def.global == MSG_RECORD) rec.add(v, lastTs)
                continue
            }
            val local = h and 0x0F
            if (h and 0x40 != 0) {
                // Definition message.
                if (pos + 5 > end) throw FitException("definition runs past the end")
                val big = d[pos + 1].toInt() != 0
                val global = if (big) be16(d, pos + 2) else le16(d, pos + 2)
                val n = d[pos + 4].toInt() and 0xFF
                pos += 5
                val fields = ArrayList<FieldDef>(n)
                repeat(n) {
                    fields.add(FieldDef(d[pos].toInt() and 0xFF, d[pos + 1].toInt() and 0xFF, d[pos + 2].toInt() and 0xFF))
                    pos += 3
                }
                var devSize = 0
                if (h and 0x20 != 0) {
                    val nd = d[pos].toInt() and 0xFF
                    pos++
                    repeat(nd) {
                        devSize += d[pos + 1].toInt() and 0xFF
                        pos += 3
                    }
                }
                defs[local] = MsgDef(global, big, fields, devSize)
                continue
            }
            val def = defs[local] ?: throw FitException("data before its definition")
            if (pos + def.fields.sumOf { it.size } + def.devSize > end) throw FitException("record runs past the end")
            val v = readFields(d, pos, def)
            pos += def.fields.sumOf { it.size } + def.devSize
            v[F_TIMESTAMP]?.let { lastTs = it.toLong() }
            when (def.global) {
                MSG_RECORD -> rec.add(v, v[F_TIMESTAMP]?.toLong() ?: lastTs)
                MSG_SESSION -> sessions.add(v)
                MSG_LAP -> laps++
                MSG_FILE_ID -> {
                    fileCreated = v[4]?.toLong()
                    device = deviceName(d, pos - def.fields.sumOf { it.size } - def.devSize, def)
                }
            }
        }
        val samples = rec.toSamples()
        return Ride(summary(sessions.firstOrNull(), samples, rec.firstTs ?: fileCreated, laps), samples, device)
    }

    // ---------- field decoding ----------

    /** Field number -> value (scaled later); invalid values and arrays are left out. */
    private fun readFields(d: ByteArray, start: Int, def: MsgDef): HashMap<Int, Double> {
        val out = HashMap<Int, Double>()
        var p = start
        for (f in def.fields) {
            decode(d, p, f.size, f.type, def.big)?.let { out[f.num] = it }
            p += f.size
        }
        return out
    }

    private fun decode(d: ByteArray, p: Int, size: Int, type: Int, big: Boolean): Double? {
        fun u(n: Int): Long {
            var v = 0L
            for (i in 0 until n) {
                val b = (d[p + i].toLong() and 0xFF)
                v = if (big) (v shl 8) or b else v or (b shl (8 * i))
            }
            return v
        }
        return when (type) {
            0x00, 0x02, 0x0D -> if (size != 1) null else u(1).takeIf { it != 0xFFL }?.toDouble()
            0x0A -> if (size != 1) null else u(1).takeIf { it != 0L }?.toDouble()
            0x01 -> if (size != 1) null else u(1).toByte().toLong().takeIf { it != 0x7FL }?.toDouble()
            0x84 -> if (size != 2) null else u(2).takeIf { it != 0xFFFFL }?.toDouble()
            0x8B -> if (size != 2) null else u(2).takeIf { it != 0L }?.toDouble()
            0x83 -> if (size != 2) null else u(2).toShort().toLong().takeIf { it != 0x7FFFL }?.toDouble()
            0x86 -> if (size != 4) null else u(4).takeIf { it != 0xFFFFFFFFL }?.toDouble()
            0x8C -> if (size != 4) null else u(4).takeIf { it != 0L }?.toDouble()
            0x85 -> if (size != 4) null else u(4).toInt().toLong().takeIf { it != 0x7FFFFFFFL }?.toDouble()
            0x88 -> if (size != 4) null else java.lang.Float.intBitsToFloat(u(4).toInt()).takeIf { !it.isNaN() }?.toDouble()
            0x89 -> if (size != 8) null else java.lang.Double.longBitsToDouble(u(8)).takeIf { !it.isNaN() }
            else -> null // 64-bit integers and strings are not needed here
        }
    }

    /** file_id field 8 (product name, a string) if present. */
    private fun deviceName(d: ByteArray, start: Int, def: MsgDef): String? {
        var p = start
        for (f in def.fields) {
            if (f.num == 8 && f.type == 0x07) {
                val s = String(d, p, f.size, Charsets.UTF_8).substringBefore('\u0000').trim()
                return s.ifEmpty { null }
            }
            p += f.size
        }
        return null
    }

    // ---------- records ----------

    private class RecordLists {
        var firstTs: Long? = null
        val ts = ArrayList<Long>()
        val dist = ArrayList<Float>()
        val speed = ArrayList<Float>()
        val hr = ArrayList<Float>()
        val cad = ArrayList<Float>()
        val alt = ArrayList<Float>()
        val temp = ArrayList<Float>()
        val lat = ArrayList<Double>()
        val lon = ArrayList<Double>()

        fun add(v: Map<Int, Double>, t: Long) {
            if (firstTs == null) firstTs = t
            ts.add(t)
            dist.add(v[5]?.let { it / 100 }.f())
            speed.add((v[73] ?: v[6])?.let { it / 1000 }.f())
            hr.add(v[3].f())
            cad.add(v[4].f())
            alt.add((v[78] ?: v[2])?.let { it / 5 - 500 }.f())
            temp.add(v[13].f())
            val la = v[0]
            val lo = v[1]
            if (la != null && lo != null) {
                lat.add(la * SEMICIRCLE); lon.add(lo * SEMICIRCLE)
            } else {
                lat.add(Double.NaN); lon.add(Double.NaN)
            }
        }

        private fun Double?.f() = this?.toFloat() ?: Float.NaN

        fun toSamples(): Samples {
            val t0 = firstTs ?: 0L
            return Samples(
                FloatArray(ts.size) { (ts[it] - t0).toFloat() },
                dist.toFloatArray(), speed.toFloatArray(), hr.toFloatArray(), cad.toFloatArray(),
                alt.toFloatArray(), temp.toFloatArray(), lat.toDoubleArray(), lon.toDoubleArray(),
            )
        }
    }

    // ---------- summary ----------

    private fun summary(s: Map<Int, Double>?, r: Samples, firstTs: Long?, laps: Int): Summary {
        fun FloatArray.valid() = filter { !it.isNaN() }
        val speeds = r.speed.valid()
        val hrs = r.hr.valid()
        val cads = r.cadence.valid().filter { it > 0 } // average cadence counts pedalling only
        val alts = r.altitudeM.valid()
        val temps = r.tempC.valid()
        val dists = r.distanceM.valid()
        val lastT = r.timeS.lastOrNull()?.toDouble()
        val start = (s?.get(2)?.toLong() ?: firstTs)?.plus(FIT_EPOCH)
        val elapsed = s?.get(7)?.div(1000) ?: lastT
        val moving = s?.get(59)?.div(1000) ?: s?.get(8)?.div(1000)
        val distance = s?.get(9)?.div(100) ?: dists.maxOrNull()?.toDouble()
        // Ascent/descent from records only when the session has none (sum of rises > 1 m steps).
        var up = 0.0
        var down = 0.0
        var ref = alts.firstOrNull()?.toDouble()
        for (a in alts) {
            val diff = a - (ref ?: a.toDouble())
            if (diff >= 1) { up += diff; ref = a.toDouble() } else if (diff <= -1) { down -= diff; ref = a.toDouble() }
        }
        return Summary(
            start = start,
            elapsedS = elapsed,
            movingS = moving,
            distanceM = distance,
            avgSpeed = (s?.get(124) ?: s?.get(14))?.div(1000)
                ?: if (distance != null && moving != null && moving > 0) distance / moving else speeds.averageOrNull(),
            maxSpeed = (s?.get(125) ?: s?.get(15))?.div(1000) ?: speeds.maxOrNull()?.toDouble(),
            avgHr = s?.get(16)?.toInt() ?: hrs.averageOrNull()?.let { Math.round(it).toInt() },
            maxHr = s?.get(17)?.toInt() ?: hrs.maxOrNull()?.toInt(),
            avgCadence = s?.get(18)?.toInt() ?: cads.averageOrNull()?.let { Math.round(it).toInt() },
            maxCadence = s?.get(19)?.toInt() ?: cads.maxOrNull()?.toInt(),
            ascentM = s?.get(22)?.toInt() ?: if (alts.isEmpty()) null else Math.round(up).toInt(),
            descentM = s?.get(23)?.toInt() ?: if (alts.isEmpty()) null else Math.round(down).toInt(),
            minAltM = (s?.get(127) ?: s?.get(71))?.let { it / 5 - 500 } ?: alts.minOrNull()?.toDouble(),
            maxAltM = (s?.get(128) ?: s?.get(50))?.let { it / 5 - 500 } ?: alts.maxOrNull()?.toDouble(),
            avgTempC = s?.get(57)?.toInt() ?: temps.averageOrNull()?.let { Math.round(it).toInt() },
            maxTempC = s?.get(58)?.toInt() ?: temps.maxOrNull()?.toInt(),
            calories = s?.get(11)?.toInt(),
            laps = laps,
        )
    }

    private fun List<Float>.averageOrNull() = if (isEmpty()) null else sumOf { it.toDouble() } / size

    private fun le16(d: ByteArray, p: Int) = (d[p].toInt() and 0xFF) or ((d[p + 1].toInt() and 0xFF) shl 8)
    private fun be16(d: ByteArray, p: Int) = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)
    private fun le32(d: ByteArray, p: Int) =
        (le16(d, p).toLong()) or (le16(d, p + 2).toLong() shl 16)
}
