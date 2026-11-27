package ch.rosenweg4303.cloud.dav

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TimeZone

/**
 * Zeitzonen fuer iCalendar: TZID lesen (auch Outlook-/Mozilla-Namen, notfalls ueber die
 * VTIMEZONE-Angaben) und VTIMEZONE-Bloecke fuer die geschriebenen TZIDs erzeugen (RFC 5545 3.6.5).
 */
internal object Zeitzonen {
    /** Was eine VTIMEZONE ueber sich sagt: Normalzeit-Versatz (ms) und ob es Sommerzeit gibt. */
    data class Angabe(val normal: Int?, val sommer: Boolean)

    private val ids: Set<String> by lazy { TimeZone.getAvailableIDs().toSet() }

    fun gueltig(id: String?) = id != null && id != "UTC" && id in ids

    /** Die haeufigsten Windows-Namen (Outlook, Exchange). */
    private val WINDOWS = mapOf(
        "W. Europe Standard Time" to "Europe/Zurich",
        "Central Europe Standard Time" to "Europe/Budapest",
        "Central European Standard Time" to "Europe/Warsaw",
        "Romance Standard Time" to "Europe/Paris",
        "GMT Standard Time" to "Europe/London",
        "Greenwich Standard Time" to "Atlantic/Reykjavik",
        "E. Europe Standard Time" to "Europe/Chisinau",
        "FLE Standard Time" to "Europe/Kiev",
        "GTB Standard Time" to "Europe/Bucharest",
        "Russian Standard Time" to "Europe/Moscow",
        "Eastern Standard Time" to "America/New_York",
        "Central Standard Time" to "America/Chicago",
        "Mountain Standard Time" to "America/Denver",
        "Pacific Standard Time" to "America/Los_Angeles",
        "China Standard Time" to "Asia/Shanghai",
        "Tokyo Standard Time" to "Asia/Tokyo",
        "India Standard Time" to "Asia/Kolkata",
        "AUS Eastern Standard Time" to "Australia/Sydney",
        "UTC" to "UTC",
        "Coordinated Universal Time" to "UTC",
    )

    /**
     * TZID -> Java-Zeitzone. Reihenfolge: bekannte ID, Windows-Name, angehaengte ID
     * ("/mozilla.org/20050126_1/Europe/Berlin"), passende Zone zur VTIMEZONE, Geraete-Zeitzone.
     */
    fun aufloesen(tzid: String?, angaben: Map<String, Angabe>): String {
        if (tzid.isNullOrBlank()) return TimeZone.getDefault().id
        val t = tzid.trim()
        if (t in ids) return t
        WINDOWS[t]?.let { return it }
        ids.filter { it.contains('/') && t.endsWith(it) }.maxByOrNull { it.length }?.let { return it }
        val a = angaben[t]
        if (a?.normal != null) {
            val passend = ids.filter { id ->
                val z = TimeZone.getTimeZone(id)
                z.rawOffset == a.normal && z.useDaylightTime() == a.sommer
            }
            val standard = TimeZone.getDefault().id
            return when {
                standard in passend -> standard
                "Europe/Zurich" in passend -> "Europe/Zurich"
                else -> passend.firstOrNull { it.startsWith("Europe/") || it.startsWith("America/") || it.startsWith("Asia/") }
                    ?: passend.firstOrNull() ?: standard
            }
        }
        return TimeZone.getDefault().id
    }

    /** Liest die VTIMEZONE-Bloecke einer Datei. */
    fun angaben(zeilen: List<Zeile>): Map<String, Angabe> {
        val m = mutableMapOf<String, Angabe>()
        var tzid: String? = null
        var normal: Int? = null
        var sommer = false
        var teil: String? = null
        for (l in zeilen) {
            when {
                l.name == "BEGIN" && l.wert == "VTIMEZONE" -> { tzid = null; normal = null; sommer = false }
                l.name == "BEGIN" && (l.wert == "STANDARD" || l.wert == "DAYLIGHT") -> { teil = l.wert; if (l.wert == "DAYLIGHT") sommer = true }
                l.name == "END" && (l.wert == "STANDARD" || l.wert == "DAYLIGHT") -> teil = null
                l.name == "TZID" && teil == null -> tzid = l.wert.trim()
                l.name == "TZOFFSETTO" && teil == "STANDARD" -> normal = versatz(l.wert)
                l.name == "END" && l.wert == "VTIMEZONE" -> tzid?.let { m[it] = Angabe(normal, sommer) }
            }
        }
        return m
    }

    private fun versatz(w: String): Int? {
        val r = Regex("""([+-])(\d{2})(\d{2})(\d{2})?""").matchEntire(w.trim()) ?: return null
        val (vz, h, mi) = r.destructured
        val ms = (h.toInt() * 60 + mi.toInt()) * 60_000 + (r.groupValues[4].toIntOrNull() ?: 0) * 1000
        return if (vz == "-") -ms else ms
    }

    private fun offset(o: ZoneOffset): String {
        val s = o.totalSeconds
        val a = kotlin.math.abs(s)
        return (if (s < 0) "-" else "+") + "%02d%02d".format(a / 3600, (a % 3600) / 60) + if (a % 60 != 0) "%02d".format(a % 60) else ""
    }

    private val LOKAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /** VTIMEZONE fuer eine Java-Zeitzone, aus deren aktuellen Regeln. */
    fun vtimezone(id: String): List<String> {
        val regeln = ZoneId.of(id).rules
        val z = mutableListOf("BEGIN:VTIMEZONE", "TZID:$id")
        val tr = regeln.transitionRules
        if (tr.size >= 2) {
            for (r in tr) {
                val t = r.createTransition(1970)
                val teil = if (t.offsetAfter.totalSeconds > t.offsetBefore.totalSeconds) "DAYLIGHT" else "STANDARD"
                val tag = r.dayOfMonthIndicator
                val wt = r.dayOfWeek?.name?.take(2)
                val regel = "FREQ=YEARLY;BYMONTH=${r.month.value}" + when {
                    wt == null -> ";BYMONTHDAY=$tag"
                    tag < 0 -> ";BYDAY=-1$wt"
                    else -> ";BYDAY=$wt;BYMONTHDAY=" + (tag..minOf(tag + 6, 31)).joinToString(",")
                }
                z.addAll(listOf("BEGIN:$teil", "DTSTART:" + t.dateTimeBefore.format(LOKAL), "RRULE:$regel",
                    "TZOFFSETFROM:" + offset(t.offsetBefore), "TZOFFSETTO:" + offset(t.offsetAfter), "END:$teil"))
            }
        } else {
            val o = regeln.getOffset(Instant.now())
            z.addAll(listOf("BEGIN:STANDARD", "DTSTART:19700101T000000", "TZOFFSETFROM:" + offset(o), "TZOFFSETTO:" + offset(o), "END:STANDARD"))
        }
        z.add("END:VTIMEZONE")
        return z
    }
}

/** Ereignisfarben (iCalendar COLOR, RFC 7986: CSS-Farbnamen) wie in der Nextcloud. */
object Farben {
    private const val ALLE = "aliceblue f0f8ff antiquewhite faebd7 aqua 00ffff aquamarine 7fffd4 azure f0ffff beige f5f5dc " +
        "bisque ffe4c4 black 000000 blanchedalmond ffebcd blue 0000ff blueviolet 8a2be2 brown a52a2a burlywood deb887 " +
        "cadetblue 5f9ea0 chartreuse 7fff00 chocolate d2691e coral ff7f50 cornflowerblue 6495ed cornsilk fff8dc crimson dc143c " +
        "cyan 00ffff darkblue 00008b darkcyan 008b8b darkgoldenrod b8860b darkgray a9a9a9 darkgreen 006400 darkgrey a9a9a9 " +
        "darkkhaki bdb76b darkmagenta 8b008b darkolivegreen 556b2f darkorange ff8c00 darkorchid 9932cc darkred 8b0000 " +
        "darksalmon e9967a darkseagreen 8fbc8f darkslateblue 483d8b darkslategray 2f4f4f darkslategrey 2f4f4f " +
        "darkturquoise 00ced1 darkviolet 9400d3 deeppink ff1493 deepskyblue 00bfff dimgray 696969 dimgrey 696969 " +
        "dodgerblue 1e90ff firebrick b22222 floralwhite fffaf0 forestgreen 228b22 fuchsia ff00ff gainsboro dcdcdc " +
        "ghostwhite f8f8ff gold ffd700 goldenrod daa520 gray 808080 green 008000 greenyellow adff2f grey 808080 " +
        "honeydew f0fff0 hotpink ff69b4 indianred cd5c5c indigo 4b0082 ivory fffff0 khaki f0e68c lavender e6e6fa " +
        "lavenderblush fff0f5 lawngreen 7cfc00 lemonchiffon fffacd lightblue add8e6 lightcoral f08080 lightcyan e0ffff " +
        "lightgoldenrodyellow fafad2 lightgray d3d3d3 lightgreen 90ee90 lightgrey d3d3d3 lightpink ffb6c1 " +
        "lightsalmon ffa07a lightseagreen 20b2aa lightskyblue 87cefa lightslategray 778899 lightslategrey 778899 " +
        "lightsteelblue b0c4de lightyellow ffffe0 lime 00ff00 limegreen 32cd32 linen faf0e6 magenta ff00ff maroon 800000 " +
        "mediumaquamarine 66cdaa mediumblue 0000cd mediumorchid ba55d3 mediumpurple 9370db mediumseagreen 3cb371 " +
        "mediumslateblue 7b68ee mediumspringgreen 00fa9a mediumturquoise 48d1cc mediumvioletred c71585 " +
        "midnightblue 191970 mintcream f5fffa mistyrose ffe4e1 moccasin ffe4b5 navajowhite ffdead navy 000080 " +
        "oldlace fdf5e6 olive 808000 olivedrab 6b8e23 orange ffa500 orangered ff4500 orchid da70d6 palegoldenrod eee8aa " +
        "palegreen 98fb98 paleturquoise afeeee palevioletred db7093 papayawhip ffefd5 peachpuff ffdab9 peru cd853f " +
        "pink ffc0cb plum dda0dd powderblue b0e0e6 purple 800080 rebeccapurple 663399 red ff0000 rosybrown bc8f8f " +
        "royalblue 4169e1 saddlebrown 8b4513 salmon fa8072 sandybrown f4a460 seagreen 2e8b57 seashell fff5ee " +
        "sienna a0522d silver c0c0c0 skyblue 87ceeb slateblue 6a5acd slategray 708090 slategrey 708090 snow fffafa " +
        "springgreen 00ff7f steelblue 4682b4 tan d2b48c teal 008080 thistle d8bfd8 tomato ff6347 turquoise 40e0d0 " +
        "violet ee82ee wheat f5deb3 white ffffff whitesmoke f5f5f5 yellow ffff00 yellowgreen 9acd32"

    /** Alle CSS-Namen -> RGB. */
    val css: Map<String, Int> by lazy {
        ALLE.split(' ').chunked(2).associate { (n, h) -> n to h.toInt(16) }
    }

    /** Auswahl, die die App zum Einfaerben einzelner Termine anbietet (Android Colors-Tabelle). */
    val auswahl = listOf("crimson", "red", "orangered", "darkorange", "orange", "gold", "yellowgreen", "forestgreen",
        "seagreen", "teal", "darkturquoise", "deepskyblue", "dodgerblue", "royalblue", "navy", "slateblue",
        "rebeccapurple", "mediumvioletred", "hotpink", "sienna", "slategray", "dimgray")

    /** Naechster CSS-Name zu einer Farbe (0xRRGGBB, Alpha egal). */
    fun naechste(rgb: Int): String = css.minByOrNull { (_, c) ->
        val dr = ((c shr 16) and 0xff) - ((rgb shr 16) and 0xff)
        val dg = ((c shr 8) and 0xff) - ((rgb shr 8) and 0xff)
        val db = (c and 0xff) - (rgb and 0xff)
        dr * dr * 3 + dg * dg * 4 + db * db * 2
    }!!.key

    /** COLOR-Wert (Name oder #RRGGBB) -> RGB, sonst null. */
    fun rgb(wert: String?): Int? {
        val w = wert?.trim()?.lowercase(java.util.Locale.ROOT) ?: return null
        css[w]?.let { return it }
        return if (w.matches(Regex("#[0-9a-f]{6}([0-9a-f]{2})?"))) w.substring(1, 7).toInt(16) else null
    }
}
