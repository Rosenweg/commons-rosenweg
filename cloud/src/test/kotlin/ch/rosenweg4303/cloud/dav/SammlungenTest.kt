package ch.rosenweg4303.cloud.dav

import ch.rosenweg4303.cloud.Konto
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/**
 * Viele Kalender (Stefan: "Und was ist, wenn es mehr als 3 Kalender gibt?"): eine PROPFIND-Antwort
 * wie von der Nextcloud mit 12 Kalendern - eigene, freigegebene (lesen/schreiben), Abo (webcal),
 * Geburtstage, reine Aufgabenliste - plus Postein-/ausgang und Papierkorb, die keine Kalender sind.
 * Kleiner HTTP-Server aus dem JDK, kein Netz noetig.
 */
class SammlungenTest {
    private fun kalender(name: String, anzeige: String, farbe: String, schreiben: Boolean = true, komp: String = "VEVENT",
                         typ: String = "<cal:calendar/>") = """
 <d:response><d:href>/remote.php/dav/calendars/stefan/$name/</d:href><d:propstat><d:prop>
  <d:resourcetype><d:collection/>$typ</d:resourcetype><d:displayname>$anzeige</d:displayname>
  <cs:getctag>http://sabre.io/ns/sync/$name</cs:getctag><d:sync-token>http://sabre.io/ns/sync/7</d:sync-token>
  <x1:calendar-color xmlns:x1="http://apple.com/ns/ical/">$farbe</x1:calendar-color>
  <cal:supported-calendar-component-set>${komp.split(",").joinToString("") { "<cal:comp name=\"$it\"/>" }}</cal:supported-calendar-component-set>
  <d:current-user-privilege-set><d:privilege><d:read/></d:privilege>${if (schreiben) "<d:privilege><d:write/></d:privilege>" else ""}</d:current-user-privilege-set>
 </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""

    @Test
    fun zwoelfKalenderMitFarbenFreigabenUndAbo() {
        val eigene = (1..8).map { i -> kalender("eigen-$i", "Eigener $i", "#%06XFF".format(0x101010 * i)) }
        val antwort = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/" xmlns:nc="http://nextcloud.com/ns">
 <d:response><d:href>/remote.php/dav/calendars/stefan/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 ${kalender("personal", "Persönlich", "#0082C9")}
 ${eigene.joinToString("")}
 ${kalender("stweg_shared_by_verwaltung", "STWEG 3 (Verwaltung)", "#E9322D", schreiben = false)}
 ${kalender("technik_shared_by_hans", "Technik", "#2D8C3C")}
 ${kalender("contact_birthdays", "Geburtstage", "#FFCC00", schreiben = false)}
 ${kalender("feiertage", "Feiertage AG", "#795AAB", schreiben = false, typ = "<cal:calendar/><cs:subscribed/>")}
 ${kalender("aufgaben", "Aufgaben", "#000000", komp = "VTODO")}
 <d:response><d:href>/remote.php/dav/calendars/stefan/inbox/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:schedule-inbox/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/calendars/stefan/outbox/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:schedule-outbox/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/calendars/stefan/trashbin/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><nc:trash-bin/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
</d:multistatus>"""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { x ->
            val b = antwort.toByteArray()
            x.sendResponseHeaders(207, b.size.toLong()); x.responseBody.use { it.write(b) }
        }
        server.start()
        try {
            val basis = "http://127.0.0.1:${server.address.port}"
            val s = NextcloudDav(Konto(basis, "stefan", "x"), "Test").sammlungen(NextcloudDav.Art.KALENDER)
            // 1 + 8 eigene + 2 Freigaben + Geburtstage + Abo = 13; Aufgabenliste, Postfaecher, Papierkorb nicht
            assertEquals(13, s.size)
            assertEquals(13, s.map { it.farbe }.distinct().size)
            assertTrue(s.all { it.url.startsWith("$basis/remote.php/dav/calendars/stefan/") && it.url.endsWith("/") })
            val n = s.associateBy { it.name }
            assertEquals("#0082C9", n.getValue("Persönlich").farbe)
            assertFalse(n.getValue("Persönlich").nurLesen)
            assertTrue(n.getValue("STWEG 3 (Verwaltung)").nurLesen)
            assertFalse(n.getValue("Technik").nurLesen)
            assertTrue(n.getValue("Geburtstage").nurLesen)
            assertTrue(n.getValue("Feiertage AG").nurLesen)
            assertTrue(s.all { it.syncToken == "http://sabre.io/ns/sync/7" })
        } finally {
            server.stop(0)
        }
    }
}
