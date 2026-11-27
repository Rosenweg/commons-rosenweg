package ch.rosenweg4303.cloud.dav

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Erinnerung: [minuten] vor Beginn (negativ = danach). [email]: ACTION:EMAIL statt Anzeige. */
data class VErinnerung(val minuten: Int, val email: Boolean = false)

/**
 * Teilnehmer oder Organisator. [rolle] ROLE (CHAIR, REQ-PARTICIPANT, OPT-PARTICIPANT,
 * NON-PARTICIPANT), [status] PARTSTAT (NEEDS-ACTION, ACCEPTED, DECLINED, TENTATIVE, ...),
 * [art] CUTYPE (INDIVIDUAL, GROUP, RESOURCE, ROOM).
 */
data class VTeilnehmer(
    val email: String,
    val name: String = "",
    val rolle: String = "REQ-PARTICIPANT",
    val status: String = "NEEDS-ACTION",
    val rsvp: Boolean = false,
    val art: String = "INDIVIDUAL",
)

/**
 * Ein Termin (VEVENT). [beginn]/[ende] in ms (UTC); ganztags: Mitternacht UTC, Ende exklusiv.
 * [zeitzone]: Java-Zeitzone von DTSTART ("UTC" bei UTC-Zeiten und ganztags).
 * Serien: [rrule], [rdates], [exdates] (ms wie [beginn]). Abweichender Einzeltermin einer Serie:
 * [ausnahmeVon] = RECURRENCE-ID (ms). [unbekannt]: nicht abgebildete Zeilen, unveraendert.
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
    val rdates: List<Long> = emptyList(),
    val exdates: List<Long> = emptyList(),
    val ausnahmeVon: Long? = null,
    val status: String? = null,
    val frei: Boolean = false,
    val klasse: String? = null,
    val erinnerungen: List<VErinnerung> = emptyList(),
    val organisator: VTeilnehmer? = null,
    val teilnehmer: List<VTeilnehmer> = emptyList(),
    val sequenz: Int = 0,
    val farbe: String? = null,
    val unbekannt: List<String> = emptyList(),
) {
    val istSerie get() = !rrule.isNullOrEmpty() || rdates.isNotEmpty()
}

/** Eine .ics-Datei: der Haupttermin und seine abweichenden Einzeltermine (gleiche UID). */
data class VKalenderObjekt(val haupt: VTermin, val ausnahmen: List<VTermin> = emptyList())

object ICal {
    private const val TAG = 24 * 3600 * 1000L

    private val BEKANNT = setOf("BEGIN", "END", "UID", "DTSTAMP", "LAST-MODIFIED", "SEQUENCE", "SUMMARY", "DESCRIPTION",
        "LOCATION", "DTSTART", "DTEND", "DURATION", "RRULE", "RDATE", "EXDATE", "RECURRENCE-ID", "STATUS", "TRANSP",
        "CLASS", "ORGANIZER", "ATTENDEE", "COLOR")

    /** Alle Haupt-Termine (ohne abweichende Einzeltermine) einer iCalendar-Datei. */
    fun lesen(text: String): List<VTermin> = objekte(text).map { it.haupt }

    /** Die Termine einer Datei, nach UID gruppiert (meist genau einer). */
    fun objekte(text: String): List<VKalenderObjekt> {
        val zeilen = Zeilen.lesen(text)
        val tz = Zeitzonen.angaben(zeilen)
        val ereignisse = mutableListOf<Pair<List<Zeile>, List<List<Zeile>>>>()
        var block: MutableList<Zeile>? = null
        var alarme = mutableListOf<List<Zeile>>()
        var alarm: MutableList<Zeile>? = null
        var tiefe = 0
        for (l in zeilen) {
            if (block == null) {
                if (l.name == "BEGIN" && l.wert.trim() == "VEVENT") { block = mutableListOf(); alarme = mutableListOf(); tiefe = 0 }
                continue
            }
            if (l.name == "BEGIN") {
                tiefe++
                if (tiefe == 1 && l.wert.trim() == "VALARM") alarm = mutableListOf()
                continue
            }
            if (l.name == "END") {
                if (tiefe == 0 && l.wert.trim() == "VEVENT") { ereignisse.add(block to alarme); block = null; continue }
                if (tiefe == 1 && alarm != null) { alarme.add(alarm); alarm = null }
                tiefe--
                continue
            }
            if (tiefe == 0) block.add(l) else if (tiefe == 1) alarm?.add(l)
        }
        val termine = ereignisse.mapNotNull { (z, a) -> termin(z, a, tz) }
        val nachUid = LinkedHashMap<String, MutableList<VTermin>>()
        termine.forEach { nachUid.getOrPut(it.uid) { mutableListOf() }.add(it) }
        return nachUid.values.map { liste ->
            val haupt = liste.firstOrNull { it.ausnahmeVon == null }
            if (haupt != null) VKalenderObjekt(haupt, liste.filter { it.ausnahmeVon != null })
            else {
                // Nur ein Einzeltermin einer fremden Serie (Einladung zu einem Termin): als Termin
                // zeigen, die RECURRENCE-ID bleibt beim Hochladen erhalten.
                val e = liste.first()
                val rid = ereignisse.first { (z, _) -> z.any { it.name == "UID" && it.wert.trim() == e.uid } }.first
                    .first { it.name == "RECURRENCE-ID" }.roh
                VKalenderObjekt(e.copy(ausnahmeVon = null, unbekannt = e.unbekannt + rid))
            }
        }
    }

    fun objekt(text: String): VKalenderObjekt? = objekte(text).firstOrNull()

    private fun termin(z: List<Zeile>, alarme: List<List<Zeile>>, tz: Map<String, Zeitzonen.Angabe>): VTermin? {
        val start = z.firstOrNull { it.name == "DTSTART" } ?: return null
        val (beginn, ganztags, zone) = zeit(start, tz) ?: return null
        val endeZ = z.firstOrNull { it.name == "DTEND" }
        val ende = endeZ?.let { zeit(it, tz)?.first }
            ?: z.firstOrNull { it.name == "DURATION" }?.let { beginn + dauer(it.wert) }
            ?: (beginn + if (ganztags) TAG else 0)
        fun t(n: String) = z.firstOrNull { it.name == n }?.let { Zeilen.text(it.wert) }.orEmpty()
        val unbekannt = mutableListOf<String>()
        val teilnehmer = mutableListOf<VTeilnehmer>()
        for (l in z) {
            if (l.name == "ATTENDEE") person(l)?.let { teilnehmer.add(it) } ?: unbekannt.add(l.roh)
            else if (l.name == "ORGANIZER" && person(l) == null) unbekannt.add(l.roh)
            else if (l.name !in BEKANNT) unbekannt.add(l.roh)
        }
        val endeOk = maxOf(ende, beginn)
        return VTermin(
            uid = t("UID").trim(), titel = t("SUMMARY"), beginn = beginn, ende = endeOk, ganztags = ganztags,
            zeitzone = zone, ort = t("LOCATION"), beschreibung = t("DESCRIPTION"),
            rrule = z.firstOrNull { it.name == "RRULE" }?.wert?.trim()?.takeIf { it.isNotEmpty() },
            rdates = z.filter { it.name == "RDATE" }.flatMap { zeiten(it, tz) },
            exdates = z.filter { it.name == "EXDATE" }.flatMap { zeiten(it, tz) },
            ausnahmeVon = z.firstOrNull { it.name == "RECURRENCE-ID" }?.let { zeit(it, tz)?.first },
            status = t("STATUS").uppercase(Locale.ROOT).trim().ifEmpty { null },
            frei = t("TRANSP").trim().equals("TRANSPARENT", true),
            klasse = t("CLASS").uppercase(Locale.ROOT).trim().ifEmpty { null },
            erinnerungen = alarme.mapNotNull { erinnerung(it, beginn, endeOk, tz) },
            organisator = z.firstOrNull { it.name == "ORGANIZER" }?.let { person(it) },
            teilnehmer = teilnehmer,
            sequenz = t("SEQUENCE").trim().toIntOrNull() ?: 0,
            farbe = t("COLOR").trim().lowercase(Locale.ROOT).ifEmpty { null },
            unbekannt = unbekannt,
        )
    }

    private fun person(l: Zeile): VTeilnehmer? {
        val w = l.wert.trim()
        val mail = l.params["EMAIL"]?.takeIf { it.contains('@') }
            ?: if (w.startsWith("mailto:", true)) w.substring(7) else null
        if (mail.isNullOrBlank()) return null
        return VTeilnehmer(
            email = mail.trim(), name = l.params["CN"].orEmpty(),
            rolle = l.params["ROLE"]?.uppercase(Locale.ROOT) ?: "REQ-PARTICIPANT",
            status = l.params["PARTSTAT"]?.uppercase(Locale.ROOT) ?: "NEEDS-ACTION",
            rsvp = l.params["RSVP"].equals("TRUE", true),
            art = l.params["CUTYPE"]?.uppercase(Locale.ROOT) ?: "INDIVIDUAL",
        )
    }

    private fun erinnerung(a: List<Zeile>, beginn: Long, ende: Long, tz: Map<String, Zeitzonen.Angabe>): VErinnerung? {
        val aktion = a.firstOrNull { it.name == "ACTION" }?.wert?.trim()?.uppercase(Locale.ROOT) ?: "DISPLAY"
        if (aktion !in setOf("DISPLAY", "AUDIO", "EMAIL")) return null
        val tr = a.firstOrNull { it.name == "TRIGGER" } ?: return null
        val w = tr.wert.trim()
        val minuten = if (tr.params["VALUE"].equals("DATE-TIME", true) || (w.firstOrNull()?.isDigit() == true)) {
            val t = zeit(tr, tz)?.first ?: return null
            ((beginn - t) / 60_000).toInt()
        } else {
            val d = dauer(w)
            if (tr.params["RELATED"].equals("END", true)) (-(ende - beginn + d) / 60_000).toInt() else (-d / 60_000).toInt()
        }
        return VErinnerung(minuten, aktion == "EMAIL")
    }

    /** -> (ms UTC, ganztags, Zeitzone) */
    internal fun zeit(l: Zeile, tz: Map<String, Zeitzonen.Angabe> = emptyMap()): Triple<Long, Boolean, String>? =
        einzel(l.wert.trim().substringBefore(',').substringBefore('/'), l, tz)

    private fun einzel(w: String, l: Zeile, tz: Map<String, Zeitzonen.Angabe>): Triple<Long, Boolean, String>? = try {
        when {
            l.params["VALUE"].equals("DATE", true) || w.length == 8 ->
                Triple(format("yyyyMMdd", "UTC").parse(w)!!.time, true, "UTC")
            w.endsWith("Z") -> Triple(format("yyyyMMdd'T'HHmmss'Z'", "UTC").parse(w)!!.time, false, "UTC")
            else -> {
                val zone = Zeitzonen.aufloesen(l.params["TZID"], tz)
                Triple(format("yyyyMMdd'T'HHmmss", zone).parse(w)!!.time, false, zone)
            }
        }
    } catch (e: Exception) {
        null
    }

    /** Mehrere Werte (EXDATE/RDATE: durch Komma getrennt; PERIOD: nur der Beginn). */
    private fun zeiten(l: Zeile, tz: Map<String, Zeitzonen.Angabe>): List<Long> =
        l.wert.split(',').mapNotNull { einzel(it.trim().substringBefore('/'), l, tz)?.first }

    /** ISO-8601-Dauer wie PT1H30M, P1D, P1W, auch Androids P3600S. */
    internal fun dauer(w: String): Long {
        val m = Regex("""([+-])?P(?:(\d+)W)?(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").matchEntire(w.trim()) ?: return 0
        val g = m.groupValues
        fun n(i: Int) = g[i].toLongOrNull() ?: 0
        val ms = (((n(2) * 7 + n(3)) * 24 + n(4)) * 60 + n(5)) * 60_000 + n(6) * 1000
        return if (g[1] == "-") -ms else ms
    }

    internal fun format(muster: String, tz: String) = SimpleDateFormat(muster, Locale.US).apply {
        timeZone = TimeZone.getTimeZone(tz); isLenient = false
    }

    // ─── schreiben ──────────────────────────────────────────────────────────

    fun schreiben(t: VTermin): String = schreiben(VKalenderObjekt(t))

    /**
     * Ganze .ics-Datei: VTIMEZONE fuer jede verwendete Zeitzone, Haupttermin, Einzeltermine.
     * [eigeneAdresse]: wird ORGANIZER, wenn ein Termin Teilnehmer, aber keinen Organisator hat
     * (dann verschickt die Nextcloud die Einladungen).
     */
    fun schreiben(o: VKalenderObjekt, eigeneAdresse: String? = null): String {
        val alle = listOf(o.haupt) + o.ausnahmen
        val zonen = alle.filter { !it.ganztags && Zeitzonen.gueltig(it.zeitzone) }.map { it.zeitzone }.distinct()
        val z = mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Rosenweg 4303//Rosenweg-Apps//DE", "CALSCALE:GREGORIAN")
        zonen.forEach { z.addAll(Zeitzonen.vtimezone(it)) }
        val uid = o.haupt.uid.ifEmpty { UUID.randomUUID().toString() }
        ereignis(z, o.haupt.copy(uid = uid), null, eigeneAdresse)
        o.ausnahmen.forEach { ereignis(z, it.copy(uid = uid, rrule = null, rdates = emptyList(), exdates = emptyList()), o.haupt, eigeneAdresse) }
        z.add("END:VCALENDAR")
        return Zeilen.falten(z)
    }

    /** Zeitwert im Stil des Termins: DATE, TZID-Ortszeit oder UTC. */
    private fun wert(name: String, ms: List<Long>, ganztags: Boolean, zone: String): String = when {
        ganztags -> "$name;VALUE=DATE:" + ms.joinToString(",") { format("yyyyMMdd", "UTC").format(it) }
        Zeitzonen.gueltig(zone) -> "$name;TZID=$zone:" + ms.joinToString(",") { format("yyyyMMdd'T'HHmmss", zone).format(it) }
        else -> "$name:" + ms.joinToString(",") { format("yyyyMMdd'T'HHmmss'Z'", "UTC").format(it) }
    }

    private fun ereignis(z: MutableList<String>, t: VTermin, haupt: VTermin?, eigeneAdresse: String?) {
        val m = Zeilen::maskieren
        val p = Zeilen::param
        z.add("BEGIN:VEVENT")
        z.add("UID:${t.uid}")
        z.add("DTSTAMP:${format("yyyyMMdd'T'HHmmss'Z'", "UTC").format(System.currentTimeMillis())}")
        if (t.sequenz > 0) z.add("SEQUENCE:${t.sequenz}")
        if (haupt != null && t.ausnahmeVon != null) z.add(wert("RECURRENCE-ID", listOf(t.ausnahmeVon), haupt.ganztags, haupt.zeitzone))
        z.add(wert("DTSTART", listOf(t.beginn), t.ganztags, t.zeitzone))
        z.add(wert("DTEND", listOf(if (t.ganztags) maxOf(t.ende, t.beginn + TAG) else t.ende), t.ganztags, t.zeitzone))
        z.add("SUMMARY:${m(t.titel)}")
        if (t.ort.isNotEmpty()) z.add("LOCATION:${m(t.ort)}")
        if (t.beschreibung.isNotEmpty()) z.add("DESCRIPTION:${m(t.beschreibung)}")
        t.rrule?.takeIf { it.isNotEmpty() }?.let { z.add("RRULE:$it") }
        if (t.rdates.isNotEmpty()) z.add(wert("RDATE", t.rdates.distinct().sorted(), t.ganztags, t.zeitzone))
        if (t.exdates.isNotEmpty()) z.add(wert("EXDATE", t.exdates.distinct().sorted(), t.ganztags, t.zeitzone))
        t.status?.let { z.add("STATUS:$it") }
        if (t.frei) z.add("TRANSP:TRANSPARENT")
        t.klasse?.let { z.add("CLASS:$it") }
        t.farbe?.let { z.add("COLOR:$it") }
        val org = t.organisator ?: eigeneAdresse?.takeIf { t.teilnehmer.isNotEmpty() }?.let { VTeilnehmer(it) }
        org?.let { o -> z.add("ORGANIZER" + (if (o.name.isNotEmpty()) ";CN=${p(o.name)}" else "") + ":mailto:${o.email}") }
        for (a in t.teilnehmer) {
            val b = StringBuilder("ATTENDEE")
            if (a.name.isNotEmpty()) b.append(";CN=").append(p(a.name))
            if (a.art != "INDIVIDUAL") b.append(";CUTYPE=").append(a.art)
            b.append(";ROLE=").append(a.rolle).append(";PARTSTAT=").append(a.status)
            if (a.rsvp) b.append(";RSVP=TRUE")
            z.add(b.append(":mailto:").append(a.email).toString())
        }
        z.addAll(t.unbekannt)
        for (e in t.erinnerungen) {
            z.add("BEGIN:VALARM")
            if (e.email) z.addAll(listOf("ACTION:EMAIL", "SUMMARY:${m(t.titel)}", "DESCRIPTION:${m(t.titel)}"))
            else z.addAll(listOf("ACTION:DISPLAY", "DESCRIPTION:${m(t.titel.ifEmpty { "Erinnerung" })}"))
            z.add("TRIGGER:" + if (e.minuten >= 0) "-PT${e.minuten}M" else "PT${-e.minuten}M")
            z.add("END:VALARM")
        }
        z.add("END:VEVENT")
    }
}
