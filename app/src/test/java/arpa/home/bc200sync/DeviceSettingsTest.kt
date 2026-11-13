package arpa.home.bc200sync

import arpa.home.bc200sync.DeviceSettings.BACKLIGHT
import arpa.home.bc200sync.DeviceSettings.BRIGHTNESS
import arpa.home.bc200sync.DeviceSettings.KEY_TONE
import arpa.home.bc200sync.DeviceSettings.NIGHT_MODE
import arpa.home.bc200sync.DeviceSettings.SMART_PAUSE
import arpa.home.bc200sync.DeviceSettings.TIME_ZONE
import arpa.home.bc200sync.DeviceSettings.HEIGHT
import arpa.home.bc200sync.DeviceSettings.LTHR
import arpa.home.bc200sync.DeviceSettings.MAX_HR
import arpa.home.bc200sync.DeviceSettings.WEIGHT
import arpa.home.bc200sync.DeviceSettings.ODOMETERS
import arpa.home.bc200sync.DeviceSettings.WHEELS
import arpa.home.bc200sync.DeviceSettings.BIKE_WEIGHTS
import arpa.home.bc200sync.DeviceSettings.CRANKS
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSettingsTest {
    // Made-up file in the device's layout (tabs, one key per line); no real user data.
    private val sample = """
        {
        	"version":	"V2",
        	"user_profile":	{
        		"weight":	1000,
        		"weight_e":	2200,
        		"stature":	1850,
        		"stature_e":	606,
        		"age":	40,
        		"gender":	0,
        		"MHR":	180,
        		"LTHR":	165,
        		"FTP":	210,
        		"unit":	0,
        		"auto_pause":	true,
        		"night_mode":	1,
        		"file_cover":	true
        	},
        	"bike_profile":	[{
        			"name":	"A B\t\"x\" 12",
        			"wheelsize":	2096,
        			"weight":	90,
        			"weight_e":	198,
        			"crank_length":	1700,
        			"odo":	12345678.900000
        		}, {
        			"name":	"",
        			"wheelsize":	2070,
        			"weight":	80,
        			"weight_e":	176,
        			"crank_length":	1725,
        			"odo":	0
        		}, {
        			"name":	"",
        			"wheelsize":	2070,
        			"weight":	80,
        			"weight_e":	176,
        			"crank_length":	1725,
        			"odo":	0.000010
        		}],
        	"tone_profile":	{
        		"key_tone":	false,
        		"warning_tone":	true
        	},
        	"backlight_profile":	{
        		"backlight":	0,
        		"brightness":	3
        	},
        	"time_profile":	{
        		"time_format":	1,
        		"time_zone":	5.750000
        	}
        }
    """.trimIndent().toByteArray()

    @Test fun minifyKeepsStringsAndNumbers() = assertEquals(
        "{\"a\":\"x y\\\" z\",\"b\":[1.50,2]}",
        DeviceSettings.minify("{ \"a\" :\t\"x y\\\" z\",\n \"b\": [ 1.50, 2 ] }"),
    )

    @Test fun readsAllOptions() {
        val v = DeviceSettings.read(sample)
        assertEquals("0", v[BACKLIGHT])
        assertEquals("3", v[BRIGHTNESS])
        assertEquals("1", v[NIGHT_MODE])
        assertEquals("true", v[SMART_PAUSE])
        assertEquals("false", v[KEY_TONE])
        assertEquals("5.750000", v[TIME_ZONE])
        assertEquals(DeviceSettings.ALL.size, v.values.count { it != null })
    }

    @Test fun labels() {
        assertEquals("Off", DeviceSettings.label(NIGHT_MODE, "1"))
        assertEquals("Auto", DeviceSettings.label(NIGHT_MODE, "2"))
        assertEquals("GMT+5:45", DeviceSettings.label(TIME_ZONE, "5.750000"))
        assertEquals("GMT+2:00", DeviceSettings.label(TIME_ZONE, "2"))
        assertEquals("GMT−3:30", DeviceSettings.label(TIME_ZONE, "-3.5"))
        assertEquals("On", DeviceSettings.label(SMART_PAUSE, "true"))
    }

    @Test fun timeZoneFromPhoneOffset() {
        assertEquals("1", DeviceSettings.timeZoneToken(3_600_000))
        assertEquals("2", DeviceSettings.timeZoneToken(7_200_000))
        assertEquals("5.75", DeviceSettings.timeZoneToken(20_700_000))
        assertEquals("-3.5", DeviceSettings.timeZoneToken(-12_600_000))
        assertEquals("0", DeviceSettings.timeZoneToken(0))
        assertNull(DeviceSettings.timeZoneToken(60_000))
        assertNull(DeviceSettings.timeZoneToken(15 * 3_600_000))
    }

    @Test fun timeZoneRange() {
        val tz = (TIME_ZONE.kind as DeviceSettings.Kind.Choice).options
        assertEquals(105, tz.size)
        assertEquals("GMT−12:00" to "-12", tz.first())
        assertEquals("GMT+0:00" to "0", tz[48])
        assertEquals("GMT+14:00" to "14", tz.last())
    }

    @Test fun changesOnlyTheGivenValues() {
        val out = DeviceSettings.withValues(
            sample,
            mapOf(BACKLIGHT to "5", BRIGHTNESS to "6", SMART_PAUSE to "false", TIME_ZONE to "2"),
        )
        val expected = DeviceSettings.minify(String(sample))
            .replace("\"backlight\":0", "\"backlight\":5")
            .replace("\"brightness\":3", "\"brightness\":6")
            .replace("\"auto_pause\":true", "\"auto_pause\":false")
            .replace("\"time_zone\":5.750000", "\"time_zone\":2")
        assertEquals(expected, String(out))
        assertTrue(String(out).contains("\"odo\":12345678.900000"))
        val back = DeviceSettings.read(out)
        assertEquals("5", back[BACKLIGHT])
        assertEquals("false", back[SMART_PAUSE])
        assertFalse(DeviceSettings.sameContent(out, sample))
    }

    @Test fun readBackIgnoresNumberFormat() {
        val sent = DeviceSettings.withValues(sample, mapOf(TIME_ZONE to "-3.5"))
        val device = String(sample).replace("5.750000", "-3.500000").toByteArray()
        assertTrue(DeviceSettings.sameContent(sent, device))
        // A string that merely looks like a number must not be normalized.
        assertFalse(DeviceSettings.sameContent(sent, String(device).replace("x\\\" 12", "x\\\" 12.0").toByteArray()))
    }

    @Test fun riderNumbers() {
        val v = DeviceSettings.read(sample)
        assertEquals("100 kg", DeviceSettings.label(WEIGHT, v[WEIGHT]))
        assertEquals("185 cm", DeviceSettings.label(HEIGHT, v[HEIGHT]))
        assertEquals("180 bpm", DeviceSettings.label(MAX_HR, v[MAX_HR]))
        assertEquals("100.5 kg", DeviceSettings.label(WEIGHT, "1005"))
        assertEquals("1040", DeviceSettings.numberToToken(WEIGHT, "104"))
        assertNull(DeviceSettings.numberToToken(WEIGHT, "251"))
        assertNull(DeviceSettings.numberToToken(LTHR, "79"))
    }

    @Test fun imperialCopiesLikeTheDevice() {
        // Pairs read from the BC200 after changing weight/height in its own menu.
        for ((kg, lb) in listOf(100 to "2200", 101 to "2220", 104 to "2290")) {
            assertEquals(mapOf("weight_e" to lb), DeviceSettings.derived(WEIGHT, "${kg * 10}"))
        }
        for ((cm, ft) in listOf(185 to "606", 186 to "610")) {
            assertEquals(mapOf("stature_e" to ft), DeviceSettings.derived(HEIGHT, "${cm * 10}"))
        }
        val out = String(DeviceSettings.withValues(sample, mapOf(WEIGHT to "1040", HEIGHT to "1860")))
        assertTrue(out.contains("\"weight\":1040,\"weight_e\":2290,\"stature\":1860,\"stature_e\":610,"))
    }

    @Test fun odometers() {
        val v = DeviceSettings.read(sample)
        assertEquals(listOf("12345678.900000", "0", "0.000010"), ODOMETERS.map { v[it] })
        assertEquals("0 km", DeviceSettings.label(ODOMETERS[2], v[ODOMETERS[2]]))
        assertEquals("12345678.9 km", DeviceSettings.label(ODOMETERS[0], v[ODOMETERS[0]]))
        assertEquals("314.1 km", DeviceSettings.label(ODOMETERS[0], "314.159200"))
        assertTrue(DeviceSettings.needsConfirm(ODOMETERS[0]))
        assertFalse(DeviceSettings.needsConfirm(WEIGHT))
        // Only bike 2's odo changes; bike 1 keeps its exact text, and the rider weight is untouched.
        val out = String(DeviceSettings.withValues(sample, mapOf(ODOMETERS[1] to "10")))
        assertEquals(DeviceSettings.minify(String(sample)).replace("\"odo\":0}", "\"odo\":10}"), out)
        val back = DeviceSettings.read(out.toByteArray())
        assertEquals(listOf("12345678.900000", "10", "0.000010"), ODOMETERS.map { back[it] })
        assertEquals("1000", back[WEIGHT])
    }

    @Test fun bikeSettings() {
        val v = DeviceSettings.read(sample)
        assertEquals("2096 mm", DeviceSettings.label(WHEELS[0], v[WHEELS[0]]))
        assertEquals("9 kg", DeviceSettings.label(BIKE_WEIGHTS[0], v[BIKE_WEIGHTS[0]]))
        assertEquals("172.5 mm", DeviceSettings.label(CRANKS[1], v[CRANKS[1]]))
        assertEquals("Bike 2 crank length", CRANKS[1].longTitle)
        // Typed values to device tokens, with the BC200 menu's limits and steps.
        assertEquals("1725", DeviceSettings.numberToToken(CRANKS[0], "172.5"))
        assertEquals("1725", DeviceSettings.numberToToken(CRANKS[0], "172,5"))
        assertNull(DeviceSettings.numberToToken(CRANKS[0], "172.3"))
        assertNull(DeviceSettings.numberToToken(CRANKS[0], "220.5"))
        assertEquals("1100", DeviceSettings.numberToToken(CRANKS[0], "110"))
        assertEquals("107", DeviceSettings.numberToToken(BIKE_WEIGHTS[0], "10.7"))
        assertNull(DeviceSettings.numberToToken(BIKE_WEIGHTS[0], "10.75"))
        assertNull(DeviceSettings.numberToToken(WHEELS[0], "2096.5"))
        assertNull(DeviceSettings.numberToToken(WHEELS[0], "9"))
        assertEquals("9999", DeviceSettings.numberToToken(WHEELS[0], "9999"))
        // Bike weight in pounds keeps tenths (checked on the BC200: 10 kg → 220, 11 kg → 242).
        assertEquals(mapOf("weight_e" to "220"), DeviceSettings.derived(BIKE_WEIGHTS[1], "100"))
        assertEquals(mapOf("weight_e" to "242"), DeviceSettings.derived(BIKE_WEIGHTS[1], "110"))
        // Bike 3 only: its own weight and weight_e change, never the rider's or another bike's.
        val out = String(DeviceSettings.withValues(sample, mapOf(BIKE_WEIGHTS[2] to "110", CRANKS[2] to "2200", WHEELS[2] to "2100")))
        val bikes = out.substringAfter("\"bike_profile\":[").split("},{")
        assertTrue(bikes[2].contains("\"wheelsize\":2100,\"weight\":110,\"weight_e\":242,\"crank_length\":2200,"))
        assertTrue(bikes[1].contains("\"wheelsize\":2070,\"weight\":80,\"weight_e\":176,\"crank_length\":1725,"))
        assertTrue(out.contains("\"weight\":1000,\"weight_e\":2200,"))
    }

    @Test fun menuFollowsTheDevice() {
        val top = DeviceSettings.MENU.items.map { (it as DeviceSettings.Item.Page).title }
        assertEquals(listOf("Bike", "User", "Device"), top)
        val bike = DeviceSettings.MENU.items[0] as DeviceSettings.Item.Page
        assertEquals(listOf("Bike 1", "Bike 2", "Bike 3"), bike.items.map { (it as DeviceSettings.Item.Page).title })
        assertEquals(DeviceSettings.ODOMETERS[1], (bike.items[1] as DeviceSettings.Item.Page).options[0])
        val all = DeviceSettings.ALL
        assertEquals(all.size, all.toSet().size)
        assertEquals(29, all.size)
        assertEquals("Time zone", TIME_ZONE.longTitle)
        assertEquals("Brightness", BRIGHTNESS.longTitle)
    }

    @Test fun odometerMissingBikeIsNull() {
        val one = String(sample).replace(Regex("\\}, \\{[\\s\\S]*\\}\\]"), "}]").toByteArray()
        val v = DeviceSettings.read(one)
        assertEquals(listOf("12345678.900000", null, null), ODOMETERS.map { v[it] })
        try {
            DeviceSettings.withValues(one, mapOf(ODOMETERS[2] to "5"))
            throw AssertionError("should refuse")
        } catch (_: DeviceSettings.SettingsException) {
        }
    }

    @Test(expected = DeviceSettings.SettingsException::class)
    fun refusesOdometerOutOfRange() {
        DeviceSettings.withValues(sample, mapOf(ODOMETERS[0] to "100000"))
    }

    @Test(expected = DeviceSettings.SettingsException::class)
    fun refusesWeightNotWholeKg() {
        DeviceSettings.withValues(sample, mapOf(WEIGHT to "1005"))
    }

    @Test(expected = DeviceSettings.SettingsException::class)
    fun refusesOutOfRange() {
        DeviceSettings.withValues(sample, mapOf(BACKLIGHT to "6"))
    }

    @Test(expected = DeviceSettings.SettingsException::class)
    fun refusesOddTimeZone() {
        DeviceSettings.withValues(sample, mapOf(TIME_ZONE to "5.7"))
    }

    @Test(expected = DeviceSettings.SettingsException::class)
    fun refusesNonBoolForSwitch() {
        DeviceSettings.withValues(sample, mapOf(KEY_TONE to "1"))
    }

    @Test(expected = DeviceSettings.SettingsException::class)
    fun refusesOtherVersions() {
        DeviceSettings.withValues(String(sample).replace("V2", "V3").toByteArray(), mapOf(BACKLIGHT to "1"))
    }

    @Test fun missingKeyIsNullAndNotWritten() {
        // Newer firmware calls it backlight_time; the app must not guess.
        val newer = String(sample).replace("\"backlight\"", "\"backlight_time\"").toByteArray()
        assertNull(DeviceSettings.read(newer)[BACKLIGHT])
        try {
            DeviceSettings.withValues(newer, mapOf(BACKLIGHT to "1"))
            throw AssertionError("should refuse")
        } catch (_: DeviceSettings.SettingsException) {
        }
    }

    @Test fun fileInfoMatchesTestedWrite() =
        // Name "Setting.json", size 2651: the exact FileInfo the BC200 accepted from the laptop test.
        assertArrayEquals(
            byteArrayOf(0x0A, 12) + "Setting.json".toByteArray() + byteArrayOf(0x10, 0xB6.toByte(), 0x29),
            Protocol.fileInfoRequest("Setting.json", 2651),
        )

    @Test fun uploadPacketMarks() {
        val one = Protocol.uploadPackets(ByteArray(10))
        assertEquals(1, one.size)
        assertEquals(3, one[0][1].toInt())
        val p = Protocol.uploadPackets(ByteArray(300) { it.toByte() })
        assertEquals(listOf(0, 1, 2), p.map { it[0].toInt() })
        assertEquals(listOf(0, 1, 2), p.map { it[1].toInt() })
        assertEquals(listOf(130, 130, 46), p.map { it.size })
        assertArrayEquals(ByteArray(300) { it.toByte() }, p.flatMap { it.drop(2) }.toByteArray())
    }
}
