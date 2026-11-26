package ch.rosenweg4303.cloud.dav

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Eine Zeile einer vCard/iCalendar-Datei: NAME;PARAM=WERT:inhalt */
internal data class Zeile(val name: String, val params: Map<String, String>, val wert: String)

internal object Zeilen {
    /** Entfaltet (RFC 5545/6350: Folgezeilen beginnen mit Leerzeichen/Tab) und zerlegt. */
    fun lesen(text: String): List<Zeile> {
        val roh = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val zeilen = mutableListOf<String>()
        for (z in roh) {
            if ((z.startsWith(" ") || z.startsWith("\t")) && zeilen.isNotEmpty()) zeilen[zeilen.size - 1] += z.substring(1)
            else if (z.isNotEmpty()) zeilen.add(z)
        }
        return zeilen.mapNotNull { z ->
            val dp = doppelpunkt(z)
            if (dp < 0) return@mapNotNull null
            val kopf = z.substring(0, dp).split(';')
            val name = kopf[0].substringAfterLast('.').uppercase(Locale.ROOT)   // "item1.TEL" -> TEL
            val params = kopf.drop(1).associate {
                val k = it.substringBefore('=').uppercase(Locale.ROOT)
                k to it.substringAfter('=', "").trim('"')
            }
            Zeile(name, params, z.substring(dp + 1))
        }
    }

    /** Erster Doppelpunkt ausserhalb von Anfuehrungszeichen (Parameterwerte duerfen ":" enthalten). */
    private fun doppelpunkt(z: String): Int {
        var inQ = false
        for (i in z.indices) {
            if (z[i] == '"') inQ = !inQ else if (z[i] == ':' && !inQ) return i
        }
        return -1
    }

    fun text(wert: String): String = wert.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",")
        .replace("\\;", ";").replace("\\\\", "\\")

    fun maskieren(t: String): String = t.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")

    /** Faltet auf hoechstens 75 Zeichen (UTF-8 grob: Zeichen) und schliesst mit CRLF. */
    fun falten(zeilen: List<String>): String = buildString {
        for (z in zeilen) {
            var rest = z
            var erste = true
            while (rest.length > 74) {
                append(if (erste) "" else " ").append(rest.substring(0, 74)).append("\r\n")
                rest = rest.substring(74)
                erste = false
            }
            append(if (erste) "" else " ").append(rest).append("\r\n")
        }
    }
}

// ─── vCard (Adressen) ───────────────────────────────────────────────────────

/** Was die Rosenweg-Apps aus einer vCard brauchen. Telefon/E-Mail: Typ (z.B. "cell", "work") -> Wert. */
data class VKontakt(
    val uid: String,
    val anzeigename: String,
    val vorname: String = "",
    val nachname: String = "",
    val firma: String = "",
    val telefone: List<Pair<String, String>> = emptyList(),
    val emails: List<Pair<String, String>> = emptyList(),
    val notiz: String = "",
)

object VCard {
    fun lesen(text: String): VKontakt? {
        val z = Zeilen.lesen(text)
        var uid = ""; var fn = ""; var vor = ""; var nach = ""; var org = ""; var notiz = ""
        val tel = mutableListOf<Pair<String, String>>()
        val mail = mutableListOf<Pair<String, String>>()
        for (l in z) when (l.name) {
            "UID" -> uid = l.wert
            "FN" -> fn = Zeilen.text(l.wert)
            "N" -> {
                val t = l.wert.split(';')
                nach = Zeilen.text(t.getOrElse(0) { "" }); vor = Zeilen.text(t.getOrElse(1) { "" })
            }
            "ORG" -> org = Zeilen.text(l.wert.split(';').first())
            "NOTE" -> notiz = Zeilen.text(l.wert)
            "TEL" -> tel.add(typ(l) to l.wert.removePrefix("tel:").trim())
            "EMAIL" -> mail.add(typ(l) to l.wert.trim())
        }
        if (fn.isEmpty()) fn = listOf(vor, nach).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { org }
        if (fn.isEmpty() && tel.isEmpty() && mail.isEmpty()) return null
        return VKontakt(uid, fn, vor, nach, org, tel, mail, notiz)
    }

    private fun typ(l: Zeile) = (l.params["TYPE"] ?: "").lowercase(Locale.ROOT).split(',').firstOrNull { it != "pref" && it != "voice" }.orEmpty()

    fun schreiben(k: VKontakt): String {
        val m = Zeilen::maskieren
        val z = mutableListOf("BEGIN:VCARD", "VERSION:3.0", "UID:${k.uid.ifEmpty { UUID.randomUUID().toString() }}",
            "FN:${m(k.anzeigename)}", "N:${m(k.nachname)};${m(k.vorname)};;;")
        if (k.firma.isNotEmpty()) z.add("ORG:${m(k.firma)}")
        k.telefone.forEach { (t, n) -> z.add(if (t.isEmpty()) "TEL:$n" else "TEL;TYPE=${t.uppercase(Locale.ROOT)}:$n") }
        k.emails.forEach { (t, e) -> z.add(if (t.isEmpty()) "EMAIL:$e" else "EMAIL;TYPE=${t.uppercase(Locale.ROOT)}:$e") }
        if (k.notiz.isNotEmpty()) z.add("NOTE:${m(k.notiz)}")
        z.add("END:VCARD")
        return Zeilen.falten(z)
    }
}

// ─── iCalendar (Termine) ────────────────────────────────────────────────────

/**
 * Ein Termin. [beginn]/[ende] in ms (UTC); ganztags: Mitternacht UTC, Ende exklusiv. [zeitzone] fuer
 * Serien (RRULE wird so, wie sie ist, an Android weitergegeben). Abweichende Einzeltermine einer
 * Serie (RECURRENCE-ID) und EXDATE werden in dieser Stufe nicht abgebildet.
 */
data class VTermin(
    val uid: String,
    val titel: String,
    val beginn: Long,
    val ende: Long,
    val ganztags: Boolean,
    val zeitzone: String,
    val ort: String = "",
    val beschreibung: String = "",
    val rrule: String? = null,
)

object ICal {
    /** Alle Haupt-Termine (ohne RECURRENCE-ID) einer iCalendar-Datei. */
    fun lesen(text: String): List<VTermin> {
        val liste = mutableListOf<VTermin>()
        var im = false
        var block = mutableListOf<Zeile>()
        var tiefe = 0
        for (l in Zeilen.lesen(text)) {
            if (l.name == "BEGIN" && l.wert == "VEVENT") { im = true; block = mutableListOf(); tiefe = 0; continue }
            if (!im) continue
            if (l.name == "BEGIN") tiefe++
            if (l.name == "END" && l.wert == "VEVENT" && tiefe == 0) {
                im = false
                termin(block)?.let { liste.add(it) }
                continue
            }
            if (l.name == "END") tiefe--
            if (tiefe == 0) block.add(l)   // VALARM usw. auslassen
        }
        return liste
    }

    private fun termin(z: List<Zeile>): VTermin? {
        if (z.any { it.name == "RECURRENCE-ID" }) return null
        val start = z.firstOrNull { it.name == "DTSTART" } ?: return null
        val (beginn, ganztags, tz) = zeit(start) ?: return null
        val endeZ = z.firstOrNull { it.name == "DTEND" }
        val ende = endeZ?.let { zeit(it)?.first }
            ?: z.firstOrNull { it.name == "DURATION" }?.let { beginn + dauer(it.wert) }
            ?: (beginn + if (ganztags) TAG else 0)
        fun t(n: String) = z.firstOrNull { it.name == n }?.let { Zeilen.text(it.wert) }.orEmpty()
        return VTermin(
            uid = t("UID"), titel = t("SUMMARY"), beginn = beginn, ende = maxOf(ende, beginn), ganztags = ganztags,
            zeitzone = tz, ort = t("LOCATION"), beschreibung = t("DESCRIPTION"),
            rrule = z.firstOrNull { it.name == "RRULE" }?.wert,
        )
    }

    private const val TAG = 24 * 3600 * 1000L

    /** -> (ms UTC, ganztags, Zeitzone) */
    internal fun zeit(l: Zeile): Triple<Long, Boolean, String>? = try {
        val w = l.wert.trim()
        when {
            l.params["VALUE"] == "DATE" || w.length == 8 ->
                Triple(format("yyyyMMdd", "UTC").parse(w)!!.time, true, "UTC")
            w.endsWith("Z") -> Triple(format("yyyyMMdd'T'HHmmss'Z'", "UTC").parse(w)!!.time, false, "UTC")
            else -> {
                val tz = l.params["TZID"]?.takeIf { TimeZone.getAvailableIDs().contains(it) } ?: TimeZone.getDefault().id
                Triple(format("yyyyMMdd'T'HHmmss", tz).parse(w)!!.time, false, tz)
            }
        }
    } catch (e: Exception) {
        null
    }

    /** ISO-8601-Dauer wie PT1H30M, P1D, P1W. */
    internal fun dauer(w: String): Long {
        val m = Regex("""([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?""").matchEntire(w.trim()) ?: return 0
        val g = m.groupValues
        fun n(i: Int) = g[i].toLongOrNull() ?: 0
        val ms = (((n(2) * 7 + n(3)) * 24 + n(4)) * 60 + n(5)) * 60_000 + n(6) * 1000
        return if (g[1] == "-") -ms else ms
    }

    private fun format(muster: String, tz: String) = SimpleDateFormat(muster, Locale.US).apply {
        timeZone = TimeZone.getTimeZone(tz); isLenient = false
    }

    fun schreiben(t: VTermin): String {
        val m = Zeilen::maskieren
        val z = mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Rosenweg 4303//Rosenweg-Apps//DE", "BEGIN:VEVENT",
            "UID:${t.uid.ifEmpty { UUID.randomUUID().toString() }}",
            "DTSTAMP:${format("yyyyMMdd'T'HHmmss'Z'", "UTC").format(System.currentTimeMillis())}")
        if (t.ganztags) {
            z.add("DTSTART;VALUE=DATE:${format("yyyyMMdd", "UTC").format(t.beginn)}")
            z.add("DTEND;VALUE=DATE:${format("yyyyMMdd", "UTC").format(maxOf(t.ende, t.beginn + TAG))}")
        } else if (!t.rrule.isNullOrEmpty() && t.zeitzone != "UTC" && TimeZone.getAvailableIDs().contains(t.zeitzone)) {
            // Serie in Ortszeit, sonst verschiebt die Sommerzeit jeden zweiten Termin um eine Stunde
            z.add("DTSTART;TZID=${t.zeitzone}:${format("yyyyMMdd'T'HHmmss", t.zeitzone).format(t.beginn)}")
            z.add("DTEND;TZID=${t.zeitzone}:${format("yyyyMMdd'T'HHmmss", t.zeitzone).format(t.ende)}")
        } else {
            z.add("DTSTART:${format("yyyyMMdd'T'HHmmss'Z'", "UTC").format(t.beginn)}")
            z.add("DTEND:${format("yyyyMMdd'T'HHmmss'Z'", "UTC").format(t.ende)}")
        }
        z.add("SUMMARY:${m(t.titel)}")
        if (t.ort.isNotEmpty()) z.add("LOCATION:${m(t.ort)}")
        if (t.beschreibung.isNotEmpty()) z.add("DESCRIPTION:${m(t.beschreibung)}")
        t.rrule?.takeIf { it.isNotEmpty() }?.let { z.add("RRULE:$it") }
        z.addAll(listOf("END:VEVENT", "END:VCALENDAR"))
        return Zeilen.falten(z)
    }
}
