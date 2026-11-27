package ch.rosenweg4303.cloud.dav

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import java.util.UUID

/**
 * VEVENT <-> Zeilen des Android-Kalenders (CalendarContract), ohne Android-Laufzeit (die
 * Spaltennamen sind Konstanten) - damit in JVM-Tests pruefbar. KalenderAbgleich schreibt und liest
 * damit Events, Reminders, Attendees und ExtendedProperties.
 */
internal object KalenderAbbildung {
    /** ExtendedProperties.NAME fuer die nicht abgebildeten iCalendar-Zeilen eines Termins. */
    const val UNBEKANNT = "ch.rosenweg4303.ical.unbekannt"
    /** Events.SYNC_DATA4: SEQUENCE. */
    const val SEQUENZ = Events.SYNC_DATA4

    data class AndroidTermin(
        val werte: Map<String, Any?>,
        /** (Minuten vor Beginn, Reminders.METHOD) */
        val erinnerungen: List<Pair<Int, Int>>,
        val teilnehmer: List<Map<String, Any?>>,
        val unbekannt: String?,
    )

    private const val TAG_MS = 86_400_000L

    /** Termin -> Android. [haupt] gesetzt = abweichender Einzeltermin dieser Serie. [farbSchluessel]: Colors-Tabelle. */
    fun nachAndroid(t: VTermin, haupt: VTermin? = null, farbSchluessel: Set<String> = emptySet()): AndroidTermin {
        val w = linkedMapOf<String, Any?>()
        w[Events.TITLE] = t.titel
        w[Events.DESCRIPTION] = t.beschreibung
        w[Events.EVENT_LOCATION] = t.ort
        w[Events.DTSTART] = t.beginn
        w[Events.ALL_DAY] = if (t.ganztags) 1 else 0
        w[Events.EVENT_TIMEZONE] = if (t.ganztags) "UTC" else t.zeitzone
        val ms = maxOf(0, t.ende - t.beginn)
        if (haupt == null && t.istSerie) {
            // Serien: Android will DURATION statt DTEND
            w[Events.DTEND] = null
            w[Events.DURATION] = if (t.ganztags) "P${maxOf(1, (ms + TAG_MS - 1) / TAG_MS)}D" else "P${ms / 1000}S"
            w[Events.RRULE] = t.rrule
            w[Events.RDATE] = datenListe(t.rdates, t.ganztags)
            w[Events.EXDATE] = datenListe(t.exdates, t.ganztags)
        } else {
            w[Events.DTEND] = if (t.ganztags) maxOf(t.ende, t.beginn + TAG_MS) else t.ende
            w[Events.DURATION] = null
            w[Events.RRULE] = null
            w[Events.RDATE] = null
            w[Events.EXDATE] = null
        }
        if (haupt != null) {
            w[Events.ORIGINAL_INSTANCE_TIME] = t.ausnahmeVon
            w[Events.ORIGINAL_ALL_DAY] = if (haupt.ganztags) 1 else 0
        }
        w[Events.STATUS] = when (t.status) {
            "TENTATIVE" -> Events.STATUS_TENTATIVE
            "CONFIRMED" -> Events.STATUS_CONFIRMED
            "CANCELLED" -> Events.STATUS_CANCELED
            else -> null
        }
        w[Events.AVAILABILITY] = if (t.frei) Events.AVAILABILITY_FREE else Events.AVAILABILITY_BUSY
        w[Events.ACCESS_LEVEL] = when (t.klasse) {
            "CONFIDENTIAL" -> Events.ACCESS_CONFIDENTIAL
            "PRIVATE" -> Events.ACCESS_PRIVATE
            "PUBLIC" -> Events.ACCESS_PUBLIC
            else -> Events.ACCESS_DEFAULT
        }
        w[Events.ORGANIZER] = t.organisator?.email
        w[Events.HAS_ALARM] = if (t.erinnerungen.isEmpty()) 0 else 1
        w[Events.HAS_ATTENDEE_DATA] = 1
        w[Events.EVENT_COLOR_KEY] = t.farbe?.takeIf { it in farbSchluessel }
        w[Events.UID_2445] = t.uid
        w[SEQUENZ] = t.sequenz.toString()

        val erinnerungen = t.erinnerungen.map { it.minuten to if (it.email) Reminders.METHOD_EMAIL else Reminders.METHOD_ALERT }.distinct()

        val org = t.organisator
        val teilnehmer = t.teilnehmer.map { a ->
            mapOf<String, Any?>(
                Attendees.ATTENDEE_EMAIL to a.email,
                Attendees.ATTENDEE_NAME to a.name,
                Attendees.ATTENDEE_RELATIONSHIP to
                    if (org != null && a.email.equals(org.email, true)) Attendees.RELATIONSHIP_ORGANIZER else Attendees.RELATIONSHIP_ATTENDEE,
                Attendees.ATTENDEE_TYPE to when {
                    a.art == "RESOURCE" || a.art == "ROOM" -> Attendees.TYPE_RESOURCE
                    a.rolle == "OPT-PARTICIPANT" -> Attendees.TYPE_OPTIONAL
                    a.rolle == "NON-PARTICIPANT" -> Attendees.TYPE_NONE
                    else -> Attendees.TYPE_REQUIRED
                },
                Attendees.ATTENDEE_STATUS to when (a.status) {
                    "ACCEPTED" -> Attendees.ATTENDEE_STATUS_ACCEPTED
                    "DECLINED" -> Attendees.ATTENDEE_STATUS_DECLINED
                    "TENTATIVE" -> Attendees.ATTENDEE_STATUS_TENTATIVE
                    "NEEDS-ACTION" -> Attendees.ATTENDEE_STATUS_INVITED
                    else -> Attendees.ATTENDEE_STATUS_NONE
                },
            )
        }.toMutableList()
        // Organisator, der nicht selbst Teilnehmer ist: eigene Zeile (Android zeigt ihn so an)
        if (org != null && t.teilnehmer.none { it.email.equals(org.email, true) }) {
            teilnehmer.add(mapOf(Attendees.ATTENDEE_EMAIL to org.email, Attendees.ATTENDEE_NAME to org.name,
                Attendees.ATTENDEE_RELATIONSHIP to Attendees.RELATIONSHIP_ORGANIZER, Attendees.ATTENDEE_TYPE to Attendees.TYPE_NONE,
                Attendees.ATTENDEE_STATUS to Attendees.ATTENDEE_STATUS_NONE))
        }
        return AndroidTermin(w, erinnerungen, teilnehmer, t.unbekannt.takeIf { it.isNotEmpty() }?.joinToString("\n"))
    }

    private fun lng(v: Any?): Long? = when (v) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }

    private fun int(v: Any?): Int? = lng(v)?.toInt()
    private fun str(v: Any?): String? = v?.toString()?.takeIf { it.isNotEmpty() }

    /** Android -> Termin. [w]: Spalten einer Events-Zeile; Erinnerungen (Minuten, Methode); Teilnehmer-Zeilen. */
    fun vonAndroid(
        w: Map<String, Any?>,
        erinnerungen: List<Pair<Int, Int>>,
        teilnehmer: List<Map<String, Any?>>,
        unbekannt: String?,
    ): VTermin {
        val ganztags = int(w[Events.ALL_DAY]) == 1
        val beginn = lng(w[Events.DTSTART]) ?: 0L
        val dtend = lng(w[Events.DTEND])?.takeIf { it > 0 }
        val dauer = if (dtend != null) dtend - beginn
        else ICal.dauer(str(w[Events.DURATION]).orEmpty()).takeIf { it > 0 } ?: if (ganztags) TAG_MS else 3_600_000L
        val zone = if (ganztags) "UTC" else str(w[Events.EVENT_TIMEZONE]) ?: "UTC"
        val orgMail = str(w[Events.ORGANIZER])
        val orgZeile = teilnehmer.firstOrNull { int(it[Attendees.ATTENDEE_RELATIONSHIP]) == Attendees.RELATIONSHIP_ORGANIZER }
        val organisator = (orgMail ?: str(orgZeile?.get(Attendees.ATTENDEE_EMAIL)))?.let { mail ->
            VTeilnehmer(mail, str(teilnehmer.firstOrNull { str(it[Attendees.ATTENDEE_EMAIL]).equals(mail, true) }?.get(Attendees.ATTENDEE_NAME)).orEmpty())
        }
        val tn = teilnehmer.filter { r ->
            // die nur fuer die Anzeige angelegte Organisator-Zeile ist kein Teilnehmer
            !(int(r[Attendees.ATTENDEE_RELATIONSHIP]) == Attendees.RELATIONSHIP_ORGANIZER &&
                (int(r[Attendees.ATTENDEE_STATUS]) ?: 0) == Attendees.ATTENDEE_STATUS_NONE &&
                (int(r[Attendees.ATTENDEE_TYPE]) ?: 0) == Attendees.TYPE_NONE)
        }.mapNotNull { r ->
            val mail = str(r[Attendees.ATTENDEE_EMAIL]) ?: return@mapNotNull null
            val typ = int(r[Attendees.ATTENDEE_TYPE]) ?: Attendees.TYPE_REQUIRED
            val status = when (int(r[Attendees.ATTENDEE_STATUS])) {
                Attendees.ATTENDEE_STATUS_ACCEPTED -> "ACCEPTED"
                Attendees.ATTENDEE_STATUS_DECLINED -> "DECLINED"
                Attendees.ATTENDEE_STATUS_TENTATIVE -> "TENTATIVE"
                else -> "NEEDS-ACTION"
            }
            VTeilnehmer(
                email = mail, name = str(r[Attendees.ATTENDEE_NAME]).orEmpty(),
                rolle = when {
                    int(r[Attendees.ATTENDEE_RELATIONSHIP]) == Attendees.RELATIONSHIP_ORGANIZER -> "CHAIR"
                    typ == Attendees.TYPE_OPTIONAL -> "OPT-PARTICIPANT"
                    typ == Attendees.TYPE_NONE -> "NON-PARTICIPANT"
                    else -> "REQ-PARTICIPANT"
                },
                status = status, rsvp = status == "NEEDS-ACTION",
                art = if (typ == Attendees.TYPE_RESOURCE) "RESOURCE" else "INDIVIDUAL",
            )
        }
        val farbe = str(w[Events.EVENT_COLOR_KEY])?.takeIf { it in Farben.css }
            ?: lng(w[Events.EVENT_COLOR])?.takeIf { it != 0L }?.let { Farben.naechste(it.toInt() and 0xffffff) }
        val original = lng(w[Events.ORIGINAL_INSTANCE_TIME])?.takeIf {
            w[Events.ORIGINAL_ID] != null || w[Events.ORIGINAL_SYNC_ID] != null
        }
        return VTermin(
            uid = str(w[Events.UID_2445]) ?: str(w[Events.SYNC_DATA2]) ?: UUID.randomUUID().toString(),
            titel = str(w[Events.TITLE]).orEmpty(), beginn = beginn, ende = beginn + maxOf(0, dauer), ganztags = ganztags,
            zeitzone = zone, ort = str(w[Events.EVENT_LOCATION]).orEmpty(), beschreibung = str(w[Events.DESCRIPTION]).orEmpty(),
            rrule = if (original == null) str(w[Events.RRULE]) else null,
            rdates = if (original == null) datenLesen(str(w[Events.RDATE])) else emptyList(),
            exdates = if (original == null) datenLesen(str(w[Events.EXDATE])) else emptyList(),
            ausnahmeVon = original,
            status = when (int(w[Events.STATUS])) {
                Events.STATUS_TENTATIVE -> "TENTATIVE"
                Events.STATUS_CONFIRMED -> "CONFIRMED"
                Events.STATUS_CANCELED -> "CANCELLED"
                else -> null
            },
            frei = int(w[Events.AVAILABILITY]) == Events.AVAILABILITY_FREE,
            klasse = when (int(w[Events.ACCESS_LEVEL])) {
                Events.ACCESS_CONFIDENTIAL -> "CONFIDENTIAL"
                Events.ACCESS_PRIVATE -> "PRIVATE"
                Events.ACCESS_PUBLIC -> "PUBLIC"
                else -> null
            },
            erinnerungen = erinnerungen.mapNotNull { (min, methode) ->
                when (methode) {
                    Reminders.METHOD_EMAIL -> VErinnerung(if (min == -1) 15 else min, true)
                    Reminders.METHOD_SMS -> null
                    else -> VErinnerung(if (min == -1) 15 else min, false)
                }
            }.distinct(),
            // Android setzt ORGANIZER bei jedem neuen Termin; ohne Teilnehmer lassen wir ihn weg
            organisator = organisator?.takeIf { tn.isNotEmpty() && it.email.contains('@') },
            teilnehmer = tn,
            sequenz = int(w[SEQUENZ]) ?: 0,
            farbe = farbe,
            unbekannt = unbekannt?.split("\n")?.filter { it.isNotBlank() }.orEmpty(),
        )
    }

    /** Android-Schreibweise fuer EXDATE/RDATE: UTC-Zeiten, ganztags nur das Datum. */
    fun datenListe(ms: List<Long>, ganztags: Boolean): String? {
        if (ms.isEmpty()) return null
        val f = if (ganztags) ICal.format("yyyyMMdd", "UTC") else ICal.format("yyyyMMdd'T'HHmmss'Z'", "UTC")
        return ms.distinct().sorted().joinToString(",") { f.format(it) }
    }

    /** Android-EXDATE/RDATE lesen: Zeilen "TZID;wert,wert" oder "wert,wert" (UTC, Datum oder Z). */
    fun datenLesen(s: String?): List<Long> {
        if (s.isNullOrBlank()) return emptyList()
        return s.split('\n').flatMap { zeile ->
            val zone = if (zeile.contains(';')) zeile.substringBefore(';') else "UTC"
            zeile.substringAfter(';').split(',').mapNotNull { v ->
                val w = v.trim()
                try {
                    when {
                        w.length == 8 -> ICal.format("yyyyMMdd", "UTC").parse(w)!!.time
                        w.endsWith("Z") -> ICal.format("yyyyMMdd'T'HHmmss'Z'", "UTC").parse(w)!!.time
                        w.isNotEmpty() -> ICal.format("yyyyMMdd'T'HHmmss", zone).parse(w)!!.time
                        else -> null
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }.distinct()
    }
}
