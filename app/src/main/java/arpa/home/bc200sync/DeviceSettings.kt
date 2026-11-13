package arpa.home.bc200sync

import java.math.BigDecimal

/**
 * The BC200's own settings file, Setting.json ("version":"V2" on the BC200X).
 *
 * V2 firmware only accepts the WHOLE file; a partial map is acknowledged but ignored. To change as
 * little as possible, the app never re-encodes the JSON: it strips the whitespace from the device's
 * own text and replaces just the value tokens of the options that changed, so every other value
 * (odometer, profile, pages …) goes back byte for byte.
 *
 * Every option below was checked on a BC200X (fw V2) against the device's own menu. The device
 * writes its file when you leave its settings menu.
 */
object DeviceSettings {
    const val FILE = "Setting.json"

    sealed class Kind {
        object Bool : Kind()

        /** Menu labels with the token the device stores for each, in the device's menu order. */
        class Choice(val options: List<Pair<String, String>>) : Kind()

        /**
         * Number min..max shown in [unit]; the device stores it multiplied by [scale], in multiples
         * of [step] (crank length 172.5 mm = 1725, step 5 = 0.5 mm). A step below [scale] allows decimals.
         */
        class Number(val min: Int, val max: Int, val unit: String, val scale: Int = 1, val step: Int = scale) : Kind() {
            val decimals: Boolean get() = step < scale
        }
    }

    /** A value in [section], or in its [index]-th object when the section is a list (bike_profile). */
    class Option(
        val section: String,
        val key: String,
        val title: String,
        val kind: Kind,
        val index: Int = -1,
        private val fullTitle: String? = null,
    ) {
        override fun toString() = if (index < 0) "$section.$key" else "$section[$index].$key"

        /** Title that stands on its own outside its group ("Bike 2 crank length", "Time zone"). */
        val longTitle: String get() = fullTitle ?: if (index < 0) title else "Bike ${index + 1} ${title.replaceFirstChar { it.lowercase() }}"
    }

    private fun choice(vararg o: Pair<String, String>) = Kind.Choice(o.toList())

    val BACKLIGHT = Option(
        "backlight_profile", "backlight", "Turn off after",
        choice("Never" to "0", "Auto" to "1", "15 s" to "2", "30 s" to "3", "1 min" to "4", "2 min" to "5"),
        fullTitle = "Backlight off after",
    )
    val BRIGHTNESS = Option(
        "backlight_profile", "brightness", "Brightness",
        Kind.Choice((1..6).map { "$it" to "$it" }),
    )
    val NIGHT_MODE = Option("user_profile", "night_mode", "Night Mode", choice("Off" to "1", "On" to "0", "Auto" to "2"))
    val UNITS = Option("user_profile", "unit", "Unit", choice("kg / km / °C" to "0", "lb / mi / °F" to "1"))
    val TIME_FORMAT = Option("time_profile", "time_format", "Format", choice("24 h" to "1", "12 h" to "0"), fullTitle = "Time format")
    val TIME_ZONE = Option("time_profile", "time_zone", "Zone", Kind.Choice(timeZones()), fullTitle = "Time zone")
    val SMART_PAUSE = Option("user_profile", "auto_pause", "Smart Pause", Kind.Bool)
    val FILE_COVER = Option("user_profile", "file_cover", "Overwrite oldest rides when full", Kind.Bool)
    val KEY_TONE = Option("tone_profile", "key_tone", "Key tone", Kind.Bool)
    val WARNING_TONE = Option("tone_profile", "warning_tone", "Warning tone", Kind.Bool)

    // Rider profile. The BC200 menu shows gender, age, weight and height; max HR, LTHR and FTP
    // are only settable from a phone. Weight and height are stored in tenths (1000 = 100.0 kg).
    val GENDER = Option("user_profile", "gender", "Gender", choice("Male" to "0", "Female" to "1"))
    val AGE = Option("user_profile", "age", "Age", Kind.Number(5, 99, "years"))
    val WEIGHT = Option("user_profile", "weight", "Weight", Kind.Number(20, 250, "kg", 10))
    val HEIGHT = Option("user_profile", "stature", "Height", Kind.Number(100, 250, "cm", 10))
    val MAX_HR = Option("user_profile", "MHR", "Max heart rate", Kind.Number(100, 240, "bpm"))
    val LTHR = Option("user_profile", "LTHR", "Threshold heart rate (LTHR)", Kind.Number(80, 230, "bpm"))
    val FTP = Option("user_profile", "FTP", "FTP", Kind.Number(50, 999, "W"))

    // Each bike's total distance ("Total Distance" in the BC200 bike menu), always stored in km:
    // 123.456700 shows as 123 km, and a value typed on the device is stored as a whole number (10).
    // Changing it replaces the ridden total, so the app asks before saving it.
    val ODOMETERS: List<Option> = (0..2).map { i ->
        Option("bike_profile", "odo", "Total distance", Kind.Number(0, 99999, "km"), i)
    }

    // Bike menu ranges as the BC200 offers them: wheel circumference 10–9999 mm, crank length
    // 110.0–220.0 mm in 0.5 mm steps, bike weight with one decimal (stored in tenths).
    val WHEELS: List<Option> = (0..2).map { i ->
        Option("bike_profile", "wheelsize", "Wheel circumference", Kind.Number(10, 9999, "mm"), i)
    }
    val BIKE_WEIGHTS: List<Option> = (0..2).map { i ->
        Option("bike_profile", "weight", "Bike weight", Kind.Number(1, 60, "kg", 10, 1), i)
    }
    val CRANKS: List<Option> = (0..2).map { i ->
        Option("bike_profile", "crank_length", "Crank length", Kind.Number(110, 220, "mm", 10, 5), i)
    }

    /** The app's settings menu, built like the BC200's own: pages, sub-headings and settings. */
    sealed class Item {
        /** A menu page; [summary] is the fixed subtitle of its row, else the row shows its values. */
        class Page(val title: String, val items: List<Item>, val summary: String? = null) : Item() {
            /** Every option on this page and its sub-pages. */
            val options: List<Option>
                get() = items.flatMap {
                    when (it) {
                        is Setting -> listOf(it.option)
                        is Page -> it.options
                        is Heading -> emptyList()
                    }
                }
        }

        class Heading(val text: String) : Item()
        class Setting(val option: Option) : Item()
    }

    private fun settings(vararg o: Option) = o.map { Item.Setting(it) }

    val MENU = Item.Page(
        "BC200 settings",
        listOf(
            Item.Page(
                "Bike",
                (0..2).map { Item.Page("Bike ${it + 1}", bike(it).map { o -> Item.Setting(o) }) },
                "Total distance, wheel, weight, crank",
            ),
            Item.Page(
                "User",
                settings(GENDER, AGE, WEIGHT, HEIGHT) + Item.Heading("Phone only") + settings(MAX_HR, LTHR, FTP),
                "Gender, age, weight, height, heart rate, FTP",
            ),
            // The BC200 "Setting" menu, in its order (Zone, Alarm, Altitude, Smart Lap and
            // Language are not in the app yet).
            Item.Page(
                "Device",
                settings(SMART_PAUSE) +
                    Item.Page("Tone", settings(KEY_TONE, WARNING_TONE)) +
                    Item.Page("Backlight", settings(BACKLIGHT, BRIGHTNESS)) +
                    settings(NIGHT_MODE, UNITS) +
                    Item.Page("Time", settings(TIME_FORMAT, TIME_ZONE)) +
                    Item.Page("Storage", settings(FILE_COVER)),
                "Smart Pause, tone, backlight, night mode, unit, time, storage",
            ),
        ),
    )

    private fun bike(i: Int) = listOf(ODOMETERS[i], WHEELS[i], BIKE_WEIGHTS[i], CRANKS[i])

    /** Options whose change loses data the rider cannot get back, so saving asks first. */
    fun needsConfirm(o: Option) = o in ODOMETERS

    val ALL: List<Option> get() = MENU.options

    /** GMT−12:00 … GMT+14:00 in 15-minute steps; the device stores decimal hours (+5:45 = 5.75). */
    private fun timeZones(): List<Pair<String, String>> = (-48..56).map { q ->
        val m = Math.abs(q) * 15
        val label = "GMT" + (if (q < 0) "−" else "+") + "%d:%02d".format(m / 60, m % 60)
        label to numberToken(BigDecimal(q).divide(BigDecimal(4)))
    }

    /**
     * Device token for a UTC offset (the phone's, which includes summer time), or null when the
     * BC200 menu has no such zone. 3 600 000 ms → "1", 20 700 000 ms (+5:45) → "5.75".
     */
    fun timeZoneToken(offsetMillis: Int): String? {
        if (offsetMillis % 900_000 != 0) return null
        val token = numberToken(BigDecimal(offsetMillis / 900_000).divide(BigDecimal(4)))
        return token.takeIf { valid(TIME_ZONE, it) }
    }

    fun numberToken(v: BigDecimal): String = v.stripTrailingZeros().toPlainString().let {
        if (it == "-0") "0" else it
    }

    class SettingsException(msg: String) : Exception(msg)

    /**
     * Values the device keeps alongside an option, in the same section, computed the way the BC200
     * itself does (checked by changing weight/height in its menu): the imperial copies of weight
     * (whole pounds × 10, rounded down; 104 kg → 2290) and height (feet × 100, rounded down;
     * 185 cm → 606). A bike's weight keeps tenths of a pound instead (11.0 kg → 242).
     */
    fun derived(o: Option, token: String): Map<String, String> {
        val tenths = token.toLongOrNull() ?: return emptyMap()
        return when (o) {
            WEIGHT -> mapOf("weight_e" to ((tenths * 22046226L / 100_000_000L) * 10).toString())
            HEIGHT -> mapOf("stature_e" to (tenths * 1000L / 3048L).toString())
            in BIKE_WEIGHTS -> mapOf("weight_e" to (tenths * 22046226L / 10_000_000L).toString())
            else -> emptyMap()
        }
    }

    /** Device token for a number typed in the option's unit, or null when not allowed. */
    fun numberToToken(o: Option, text: String): String? {
        val k = o.kind as? Kind.Number ?: return null
        val v = text.trim().replace(',', '.').toBigDecimalOrNull() ?: return null
        val token = try {
            v.multiply(BigDecimal(k.scale)).toBigIntegerExact().toLong()
        } catch (_: ArithmeticException) {
            return null
        }
        return token.toString().takeIf { valid(o, it) }
    }

    /** The option's value in its unit ("100" for weight 1000), for editing. */
    fun numberText(o: Option, token: String?): String {
        val k = o.kind as? Kind.Number ?: return ""
        val v = token?.toBigDecimalOrNull() ?: return ""
        return numberToken(v.divide(BigDecimal(k.scale)))
    }

    /** Removes whitespace outside strings; keeps every other character (and number) as it is. */
    fun minify(text: String): String {
        val o = StringBuilder(text.length)
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                o.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else if (c == '"') {
                inString = true
                o.append(c)
            } else if (!c.isWhitespace()) {
                o.append(c)
            }
        }
        return o.toString()
    }

    // Every brace escaped: Android's ICU regex rejects a bare '}' that the desktop JVM accepts.
    private fun sectionRegex(section: String) = Regex("\"" + section + "\":\\{[^\\{\\}\\[\\]]*\\}")
    private fun fieldRegex(key: String) = Regex("\"" + key + "\":(true|false|-?\\d+(?:\\.\\d+)?)")
    private val VERSION = Regex("\"version\":\"V2\"")

    private fun listRegex(section: String) =
        Regex("\"" + section + "\":\\[(?:\\{[^\\{\\}\\[\\]]*\\},?)*\\]")
    private val OBJECT = Regex("\\{[^\\{\\}\\[\\]]*\\}")

    /** The flat object holding [o] and where it is in the minified text, or null when not single. */
    private class Block(val range: IntRange, val value: String)

    private fun block(min: String, o: Option): Block? {
        if (o.index < 0) return sectionRegex(o.section).findAll(min).toList().singleOrNull()?.let { Block(it.range, it.value) }
        val list = listRegex(o.section).findAll(min).toList().singleOrNull() ?: return null
        val obj = OBJECT.findAll(list.value).toList().getOrNull(o.index) ?: return null
        val start = list.range.first + obj.range.first
        return Block(start..start + obj.value.length - 1, obj.value)
    }

    private fun field(block: String, key: String): MatchResult? =
        fieldRegex(key).findAll(block).toList().singleOrNull()

    /** True when the token is a valid value for the option. */
    fun valid(o: Option, token: String): Boolean = when (val k = o.kind) {
        Kind.Bool -> token == "true" || token == "false"
        is Kind.Choice -> k.options.any { same(it.second, token) }
        is Kind.Number -> token.toLongOrNull()?.let { v ->
            v % k.step == 0L && v in k.min.toLong() * k.scale..k.max.toLong() * k.scale
        } == true
    }

    /** Equal values; numbers are compared by value, so "2" equals "2.0". */
    fun same(a: String?, b: String?): Boolean {
        if (a == null || b == null) return a == b
        val x = a.toBigDecimalOrNull()
        val y = b.toBigDecimalOrNull()
        return if (x != null && y != null) x.compareTo(y) == 0 else a == b
    }

    /** Menu text for a stored value. */
    fun label(o: Option, token: String?): String = when {
        token == null -> "not available"
        o.kind is Kind.Bool -> if (token == "true") "On" else "Off"
        o in ODOMETERS -> token.toBigDecimalOrNull()?.let {
            numberToken(it.setScale(1, java.math.RoundingMode.DOWN)) + " km"
        } ?: "value $token"
        o.kind is Kind.Number -> numberText(o, token).ifEmpty { "value $token" } + " " + (o.kind as Kind.Number).unit
        else -> (o.kind as Kind.Choice).options.firstOrNull { same(it.second, token) }?.first ?: "value $token"
    }

    /** Current value token of every known option; null when the file has no single such value. */
    fun read(file: ByteArray): Map<Option, String?> {
        val min = minify(String(file, Charsets.UTF_8))
        return ALL.associateWith { o -> block(min, o)?.let { field(it.value, o.key)?.groupValues?.get(1) } }
    }

    /** The whole settings file with only the given values changed, ready to send. */
    fun withValues(file: ByteArray, changes: Map<Option, String>): ByteArray {
        var min = minify(String(file, Charsets.UTF_8))
        if (VERSION.findAll(min).count() != 1) {
            throw SettingsException("Unknown settings version; the app only writes \"V2\" settings.")
        }
        for ((o, token) in changes) {
            if (!valid(o, token)) throw SettingsException("Value $token is not allowed for ${o.longTitle}.")
            for ((key, t) in mapOf(o.key to token) + derived(o, token)) {
                val s = block(min, o) ?: throw SettingsException("The BC200 settings have no single $o.")
                val f = field(s.value, key) ?: throw SettingsException("The BC200 settings have no single $key value.")
                min = min.replaceRange(s.range, s.value.replaceRange(f.groups[1]!!.range, t))
            }
        }
        return min.toByteArray(Charsets.UTF_8)
    }

    /**
     * True when the file read back from the device holds exactly what was sent, ignoring layout and
     * number formatting (the device prints decimals with six places: 5.75 comes back as 5.750000).
     */
    fun sameContent(sent: ByteArray, readBack: ByteArray): Boolean =
        normalized(String(sent, Charsets.UTF_8)) == normalized(String(readBack, Charsets.UTF_8))

    /** Minified text with every number outside strings rewritten in one canonical form. */
    fun normalized(text: String): String {
        val min = minify(text)
        val o = StringBuilder(min.length)
        var inString = false
        var escaped = false
        var i = 0
        while (i < min.length) {
            val c = min[i]
            if (inString) {
                o.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                i++
            } else if (c == '-' || c.isDigit()) {
                var e = i + 1
                while (e < min.length && (min[e].isDigit() || min[e] in ".eE+-")) e++
                val n = min.substring(i, e)
                o.append(n.toBigDecimalOrNull()?.let(::numberToken) ?: n)
                i = e
            } else {
                if (c == '"') inString = true
                o.append(c)
                i++
            }
        }
        return o.toString()
    }
}
