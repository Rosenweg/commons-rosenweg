package ch.rosenweg4303.cloud.dav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormateTest {
    @Test
    fun vcardLesenUndSchreiben() {
        val text = "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:abc-1\r\nFN:Erika Muster\r\nN:Muster;Erika;;;\r\n" +
            "ORG:Rosenweg\\, STWEG 3;Technik\r\nTEL;TYPE=CELL,VOICE:+41 79 000 00 00\r\nitem1.EMAIL;TYPE=INTERNET,WORK:erika@example.org\r\n" +
            "NOTE:Zeile 1\\nZeile 2 mit langem Text, der gefaltet\r\n  weitergeht\r\nEND:VCARD\r\n"
        val k = VCard.lesen(text)!!
        assertEquals("abc-1", k.uid)
        assertEquals("Erika Muster", k.anzeigename)
        assertEquals("Erika", k.vorname)
        assertEquals("Muster", k.nachname)
        assertEquals("Rosenweg, STWEG 3", k.firma)
        assertEquals(listOf("cell" to "+41 79 000 00 00"), k.telefone)
        assertEquals("erika@example.org", k.emails.single().second)
        assertEquals("Zeile 1\nZeile 2 mit langem Text, der gefaltet weitergeht", k.notiz)
        val zurueck = VCard.lesen(VCard.schreiben(k))!!
        assertEquals(k, zurueck)
    }

    @Test
    fun icalOrtszeitGanztagsDauerUndSerie() {
        val text = """BEGIN:VCALENDAR
BEGIN:VEVENT
UID:t1
SUMMARY:Sitzung
DTSTART;TZID=Europe/Zurich:20261126T190000
DTEND;TZID=Europe/Zurich:20261126T210000
RRULE:FREQ=WEEKLY;BYDAY=TH
BEGIN:VALARM
TRIGGER:-PT15M
SUMMARY:nicht der Titel
END:VALARM
END:VEVENT
BEGIN:VEVENT
UID:t1
RECURRENCE-ID;TZID=Europe/Zurich:20261203T190000
SUMMARY:verschoben
DTSTART;TZID=Europe/Zurich:20261203T200000
END:VEVENT
BEGIN:VEVENT
UID:t2
SUMMARY:Ferien
DTSTART;VALUE=DATE:20261224
DTEND;VALUE=DATE:20261227
END:VEVENT
BEGIN:VEVENT
UID:t3
SUMMARY:Kurz
DTSTART:20261126T080000Z
DURATION:PT1H30M
END:VEVENT
END:VCALENDAR"""
        val t = ICal.lesen(text)
        assertEquals(3, t.size)   // die Ausnahme (RECURRENCE-ID) zaehlt nicht
        val serie = t[0]
        assertEquals("Sitzung", serie.titel)
        assertEquals("Europe/Zurich", serie.zeitzone)
        assertEquals(1795716000000L, serie.beginn)   // 26.11.2026 19:00 MEZ = 18:00 UTC
        assertEquals(2 * 3600_000L, serie.ende - serie.beginn)
        assertEquals("FREQ=WEEKLY;BYDAY=TH", serie.rrule)
        assertTrue(t[1].ganztags)
        assertEquals(3 * 86_400_000L, t[1].ende - t[1].beginn)
        assertFalse(t[2].ganztags)
        assertEquals(90 * 60_000L, t[2].ende - t[2].beginn)
        assertNull(t[2].rrule)
        // Hin und zurueck: Serie in Ortszeit, Ganztag als DATE
        val wieder = ICal.lesen(ICal.schreiben(serie)).single()
        assertEquals(serie.beginn, wieder.beginn)
        assertEquals("Europe/Zurich", wieder.zeitzone)
        assertEquals(t[1].beginn, ICal.lesen(ICal.schreiben(t[1])).single().beginn)
        assertTrue(ICal.schreiben(serie).contains("DTSTART;TZID=Europe/Zurich:20261126T190000"))
    }

    @Test
    fun faltenHaeltZeilenKurz() {
        val lang = "x".repeat(200)
        val t = Zeilen.falten(listOf("NOTE:$lang"))
        assertTrue(t.split("\r\n").all { it.length <= 75 })
        assertEquals("NOTE:$lang", Zeilen.lesen(t).let { "NOTE:" + it.single().wert })
    }
}
