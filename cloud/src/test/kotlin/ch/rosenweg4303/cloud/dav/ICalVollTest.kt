package ch.rosenweg4303.cloud.dav

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Serien, Ausnahmen, Erinnerungen, Teilnehmer, Zeitzonen und die Abbildung auf CalendarContract. */
class ICalVollTest {
    private val serie = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Nextcloud//Calendar//EN
BEGIN:VTIMEZONE
TZID:Europe/Zurich
BEGIN:DAYLIGHT
TZOFFSETFROM:+0100
TZOFFSETTO:+0200
DTSTART:19700329T020000
RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU
END:DAYLIGHT
BEGIN:STANDARD
TZOFFSETFROM:+0200
TZOFFSETTO:+0100
DTSTART:19701025T030000
RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU
END:STANDARD
END:VTIMEZONE
BEGIN:VEVENT
UID:serie-1
DTSTAMP:20261120T100000Z
CREATED:20261101T100000Z
SEQUENCE:3
SUMMARY:Ausschuss
DTSTART;TZID=Europe/Zurich:20261126T190000
DTEND;TZID=Europe/Zurich:20261126T210000
RRULE:FREQ=WEEKLY;BYDAY=TH;COUNT=10
EXDATE;TZID=Europe/Zurich:20261210T190000,20261217T190000
RDATE;TZID=Europe/Zurich:20261130T190000
STATUS:CONFIRMED
TRANSP:TRANSPARENT
CLASS:PRIVATE
COLOR:teal
CATEGORIES:Sitzung
X-NC-EIGEN:bleibt
ORGANIZER;CN=Stefan Müller:mailto:stefan@example.org
ATTENDEE;CN=Stefan Müller;ROLE=CHAIR;PARTSTAT=ACCEPTED:mailto:stefan@example.org
ATTENDEE;CN="Muster, Erika";ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:erika@example.org
ATTENDEE;ROLE=OPT-PARTICIPANT;PARTSTAT=TENTATIVE:mailto:max@example.org
ATTENDEE;CUTYPE=ROOM;PARTSTAT=ACCEPTED:mailto:raum@example.org
BEGIN:VALARM
ACTION:DISPLAY
DESCRIPTION:Ausschuss
TRIGGER:-PT15M
END:VALARM
BEGIN:VALARM
ACTION:EMAIL
SUMMARY:x
DESCRIPTION:x
TRIGGER;RELATED=END:-PT30M
END:VALARM
BEGIN:VALARM
ACTION:DISPLAY
TRIGGER;VALUE=DATE-TIME:20261126T170000Z
END:VALARM
END:VEVENT
BEGIN:VEVENT
UID:serie-1
DTSTAMP:20261120T100000Z
RECURRENCE-ID;TZID=Europe/Zurich:20261203T190000
SUMMARY:Ausschuss (verschoben)
DTSTART;TZID=Europe/Zurich:20261203T200000
DTEND;TZID=Europe/Zurich:20261203T220000
LOCATION:Gemeinschaftsraum
END:VEVENT
END:VCALENDAR"""

    private val zurich = 3_600_000L   // MEZ (Winter)

    @Test
    fun serieMitAusnahmenLesen() {
        val o = ICal.objekt(serie)!!
        val h = o.haupt
        assertEquals("serie-1", h.uid)
        assertEquals("Europe/Zurich", h.zeitzone)
        assertEquals("FREQ=WEEKLY;BYDAY=TH;COUNT=10", h.rrule)
        assertEquals(listOf(1796925600000L, 1797530400000L), h.exdates)   // 10.12./17.12. 19:00 MEZ
        assertEquals(1, h.rdates.size)
        assertEquals(3, h.sequenz)
        assertEquals("CONFIRMED", h.status)
        assertTrue(h.frei)
        assertEquals("PRIVATE", h.klasse)
        assertEquals("teal", h.farbe)
        assertEquals(listOf("CREATED:20261101T100000Z", "CATEGORIES:Sitzung", "X-NC-EIGEN:bleibt"), h.unbekannt)
        assertEquals("stefan@example.org", h.organisator!!.email)
        assertEquals(4, h.teilnehmer.size)
        assertEquals("Muster, Erika", h.teilnehmer[1].name)
        assertTrue(h.teilnehmer[1].rsvp)
        assertEquals("ROOM", h.teilnehmer[3].art)
        // 15 Min vor Beginn; 30 Min vor Ende einer 2h-Sitzung = 90 Min nach Beginn (-90); absolut 17:00Z = 18:00 MEZ = 60 Min vorher
        assertEquals(listOf(VErinnerung(15), VErinnerung(-90, true), VErinnerung(60)), h.erinnerungen)
        val a = o.ausnahmen.single()
        assertEquals(1796320800000L, a.ausnahmeVon)   // 3.12. 19:00 MEZ
        assertEquals(a.ausnahmeVon!! + 3_600_000L, a.beginn)
        assertEquals("Gemeinschaftsraum", a.ort)
        assertNull(a.rrule)
    }

    @Test
    fun serieHinUndZurueck() {
        val o = ICal.objekt(serie)!!
        val text = ICal.schreiben(o)
        assertTrue(text.contains("BEGIN:VTIMEZONE\r\nTZID:Europe/Zurich"))
        assertTrue(text.contains("RECURRENCE-ID;TZID=Europe/Zurich:20261203T190000"))
        assertTrue(text.contains("EXDATE;TZID=Europe/Zurich:20261210T190000,20261217T190000"))
        assertTrue(text.contains("X-NC-EIGEN:bleibt"))
        assertTrue(text.replace("\r\n ", "").contains("ATTENDEE;CN=\"Muster, Erika\";ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:erika@example.org"))
        assertTrue(text.split("\r\n").all { it.toByteArray().size <= 75 })
        val wieder = ICal.objekt(text)!!
        assertEquals(o.haupt.copy(unbekannt = emptyList()), wieder.haupt.copy(unbekannt = emptyList()))
        assertEquals(o.haupt.unbekannt, wieder.haupt.unbekannt)
        assertEquals(o.ausnahmen, wieder.ausnahmen)
    }

    @Test
    fun vtimezoneAusJavaRegeln() {
        val z = Zeitzonen.vtimezone("Europe/Zurich")
        assertTrue(z.contains("BEGIN:DAYLIGHT"))
        assertTrue(z.contains("DTSTART:19700329T020000"))
        assertTrue(z.contains("TZOFFSETTO:+0200"))
        assertTrue(z.any { it.startsWith("RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=SU;BYMONTHDAY=25,26,27,28,29,30,31") })
        // ohne Sommerzeit
        val tokio = Zeitzonen.vtimezone("Asia/Tokyo")
        assertTrue(tokio.contains("TZOFFSETTO:+0900"))
        assertFalse(tokio.contains("BEGIN:DAYLIGHT"))
    }

    @Test
    fun fremdeZeitzonenNamen() {
        val outlook = """BEGIN:VCALENDAR
BEGIN:VTIMEZONE
TZID:W. Europe Standard Time
BEGIN:STANDARD
TZOFFSETTO:+0100
END:STANDARD
END:VTIMEZONE
BEGIN:VTIMEZONE
TZID:Eigene Zone
BEGIN:STANDARD
TZOFFSETTO:+0100
END:STANDARD
BEGIN:DAYLIGHT
TZOFFSETTO:+0200
END:DAYLIGHT
END:VTIMEZONE
BEGIN:VEVENT
UID:a
DTSTART;TZID=W. Europe Standard Time:20261126T190000
END:VEVENT
BEGIN:VEVENT
UID:b
DTSTART;TZID=/mozilla.org/20050126_1/Europe/Berlin:20261126T190000
END:VEVENT
BEGIN:VEVENT
UID:c
DTSTART;TZID=Eigene Zone:20261126T190000
END:VEVENT
END:VCALENDAR"""
        val t = ICal.lesen(outlook)
        assertEquals("Europe/Zurich", t[0].zeitzone)
        assertEquals("Europe/Berlin", t[1].zeitzone)
        assertEquals(1795716000000L, t[0].beginn)
        assertEquals(1795716000000L, t[1].beginn)
        assertEquals(1795716000000L, t[2].beginn)   // irgendeine MEZ-Zone mit Sommerzeit
    }

    @Test
    fun ganztagsSerieUndEinzelterminOhneSerie() {
        val t = ICal.objekt("""BEGIN:VCALENDAR
BEGIN:VEVENT
UID:g
DTSTART;VALUE=DATE:20261224
RRULE:FREQ=YEARLY
EXDATE;VALUE=DATE:20271224
SUMMARY:Heiligabend
END:VEVENT
END:VCALENDAR""")!!.haupt
        assertTrue(t.ganztags)
        assertEquals(86_400_000L, t.ende - t.beginn)
        assertEquals(listOf(t.beginn + 365 * 86_400_000L), t.exdates)
        val text = ICal.schreiben(t)
        assertTrue(text.contains("EXDATE;VALUE=DATE:20271224"))
        assertTrue(text.contains("DTEND;VALUE=DATE:20261225"))
        // nur ein Einzeltermin einer fremden Serie: wird Termin, RECURRENCE-ID bleibt erhalten
        val nur = ICal.objekt("""BEGIN:VCALENDAR
BEGIN:VEVENT
UID:fremd
RECURRENCE-ID:20261203T180000Z
DTSTART:20261203T190000Z
DTEND:20261203T200000Z
SUMMARY:Eingeladen
END:VEVENT
END:VCALENDAR""")!!
        assertNull(nur.haupt.ausnahmeVon)
        assertTrue(ICal.schreiben(nur).contains("RECURRENCE-ID:20261203T180000Z"))
    }

    @Test
    fun androidDauerUndDaten() {
        assertEquals(3_600_000L, ICal.dauer("P3600S"))
        assertEquals(90 * 60_000L, ICal.dauer("PT1H30M"))
        assertEquals(-15 * 60_000L, ICal.dauer("-PT15M"))
        assertEquals(2 * 86_400_000L, ICal.dauer("P2D"))
        val ms = listOf(1796925600000L, 1797530400000L)
        assertEquals("20261210T180000Z,20261217T180000Z", KalenderAbbildung.datenListe(ms, false))
        assertEquals(ms, KalenderAbbildung.datenLesen("20261210T180000Z,20261217T180000Z"))
        assertEquals(ms, KalenderAbbildung.datenLesen("Europe/Zurich;20261210T190000,20261217T190000"))
        assertEquals(listOf(1797638400000L), KalenderAbbildung.datenLesen("20261219"))
    }

    @Test
    fun abbildungAufCalendarContract() {
        val o = ICal.objekt(serie)!!
        val at = KalenderAbbildung.nachAndroid(o.haupt, null, setOf("teal"))
        val w = at.werte
        assertNull(w[Events.DTEND])
        assertEquals("P7200S", w[Events.DURATION])
        assertEquals("FREQ=WEEKLY;BYDAY=TH;COUNT=10", w[Events.RRULE])
        assertEquals("20261210T180000Z,20261217T180000Z", w[Events.EXDATE])
        assertEquals(Events.STATUS_CONFIRMED, w[Events.STATUS])
        assertEquals(Events.AVAILABILITY_FREE, w[Events.AVAILABILITY])
        assertEquals(Events.ACCESS_PRIVATE, w[Events.ACCESS_LEVEL])
        assertEquals("teal", w[Events.EVENT_COLOR_KEY])
        assertEquals("stefan@example.org", w[Events.ORGANIZER])
        assertEquals(listOf(15 to Reminders.METHOD_ALERT, -90 to Reminders.METHOD_EMAIL, 60 to Reminders.METHOD_ALERT), at.erinnerungen)
        val org = at.teilnehmer.first { it[Attendees.ATTENDEE_EMAIL] == "stefan@example.org" }
        assertEquals(Attendees.RELATIONSHIP_ORGANIZER, org[Attendees.ATTENDEE_RELATIONSHIP])
        assertEquals(Attendees.ATTENDEE_STATUS_ACCEPTED, org[Attendees.ATTENDEE_STATUS])
        assertEquals(Attendees.TYPE_OPTIONAL, at.teilnehmer[2][Attendees.ATTENDEE_TYPE])
        assertEquals(Attendees.TYPE_RESOURCE, at.teilnehmer[3][Attendees.ATTENDEE_TYPE])
        assertEquals(Attendees.ATTENDEE_STATUS_INVITED, at.teilnehmer[1][Attendees.ATTENDEE_STATUS])

        // zurueck, wie es aus dem Provider kaeme (Zahlen als Long, wie aus dem Cursor)
        val zeile = w.mapValues { (_, v) -> if (v is Int) v.toLong() else v } + mapOf(Events._ID to 7L)
        val t = KalenderAbbildung.vonAndroid(zeile, at.erinnerungen, at.teilnehmer, at.unbekannt)
        assertEquals(o.haupt.beginn, t.beginn)
        assertEquals(o.haupt.ende, t.ende)
        assertEquals(o.haupt.exdates, t.exdates)
        assertEquals(o.haupt.rdates, t.rdates)
        assertEquals(o.haupt.erinnerungen, t.erinnerungen)
        assertEquals(o.haupt.organisator!!.email, t.organisator!!.email)
        assertEquals(4, t.teilnehmer.size)
        assertEquals("CHAIR", t.teilnehmer[0].rolle)
        assertEquals("OPT-PARTICIPANT", t.teilnehmer[2].rolle)
        assertEquals("TENTATIVE", t.teilnehmer[2].status)
        assertEquals(o.haupt.unbekannt, t.unbekannt)
        assertEquals("teal", t.farbe)
        assertEquals(3, t.sequenz)

        // Ausnahme
        val a = KalenderAbbildung.nachAndroid(o.ausnahmen.single(), o.haupt)
        assertEquals(1796320800000L, a.werte[Events.ORIGINAL_INSTANCE_TIME])
        assertEquals(0, a.werte[Events.ORIGINAL_ALL_DAY])
        assertNotNull(a.werte[Events.DTEND])
        assertNull(a.werte[Events.RRULE])
        val zurueck = KalenderAbbildung.vonAndroid(a.werte + mapOf(Events.ORIGINAL_ID to 7L), emptyList(), emptyList(), null)
        assertEquals(1796320800000L, zurueck.ausnahmeVon)
    }

    @Test
    fun organisatorNurMitTeilnehmernUndAndereFarbe() {
        // Android setzt ORGANIZER bei jedem neuen Termin; ohne Teilnehmer bleibt er weg
        val ohne = KalenderAbbildung.vonAndroid(mapOf(Events.DTSTART to 0L, Events.DTEND to 3_600_000L, Events.ORGANIZER to "ich@example.org",
            Events.EVENT_COLOR to 0xFF008081L), emptyList(), emptyList(), null)
        assertNull(ohne.organisator)
        assertEquals("teal", ohne.farbe)   // naechster CSS-Name
        val text = ICal.schreiben(VKalenderObjekt(ohne.copy(teilnehmer = listOf(VTeilnehmer("gast@example.org")))), "ich@example.org")
        assertTrue(text.contains("ORGANIZER:mailto:ich@example.org"))
        assertEquals(148, Farben.css.size)
    }
}
