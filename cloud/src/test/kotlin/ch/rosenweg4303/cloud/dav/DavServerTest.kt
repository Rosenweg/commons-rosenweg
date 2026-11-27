package ch.rosenweg4303.cloud.dav

import ch.rosenweg4303.cloud.Konto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Gegen einen echten CalDAV/CardDAV-Server, nur wenn DAV_TEST_URL gesetzt ist (sonst uebersprungen,
 * auch in der CI). Gedacht fuer einen lokalen Radicale:
 *   radicale --storage-filesystem-folder=/tmp/r --auth-type=none
 *   DAV_TEST_URL=http://127.0.0.1:5232 DAV_TEST_BENUTZER=test ./gradlew --no-daemon :cloud:testDebugUnitTest
 * Gegen die Nextcloud: DAV_TEST_URL=https://nextcloud.rosenweg4303.ch, DAV_TEST_BENUTZER, DAV_TEST_PASSWORT
 * (App-Passwort) und DAV_TEST_NEXTCLOUD=1 - legt einen eigenen Test-Kalender/-Adressbuch an und loescht sie.
 */
class DavServerTest {
    private val url = System.getenv("DAV_TEST_URL")
    private val benutzer = System.getenv("DAV_TEST_BENUTZER") ?: "test"
    private val nextcloud = System.getenv("DAV_TEST_NEXTCLOUD") == "1"

    private fun dav(): NextcloudDav {
        assumeTrue("DAV_TEST_URL nicht gesetzt", !url.isNullOrEmpty())
        val konto = Konto(url!!.trimEnd('/'), benutzer, System.getenv("DAV_TEST_PASSWORT") ?: "egal")
        return NextcloudDav(konto, "Rosenweg Test", if (nextcloud) null else url.trimEnd('/') + "/$benutzer/")
    }

    @Test
    fun kalenderVollerZyklus() {
        val d = dav()
        val k = d.kalenderAnlegen("TEST Rosenweg-Abgleich", "#336699")
        try {
            val s = d.sammlungen(NextcloudDav.Art.KALENDER).first { it.url == k }
            assertEquals("TEST Rosenweg-Abgleich", s.name)
            assertEquals("#336699", s.farbe?.take(7)?.uppercase())
            assertFalse(s.nurLesen)
            assertTrue("Server kann WebDAV-Sync", s.syncToken.isNotEmpty())

            val erst = d.aenderungen(k, "")
            assertTrue(erst.geaendert.isEmpty())

            val termin = VKalenderObjekt(
                VTermin("rw-test-1", "Serie", 1795716000000L, 1795723200000L, false, "Europe/Zurich",
                    rrule = "FREQ=WEEKLY;COUNT=5", exdates = listOf(1796925600000L),
                    erinnerungen = listOf(VErinnerung(15)), unbekannt = listOf("X-RW-TEST:1")),
                listOf(VTermin("rw-test-1", "Serie (verschoben)", 1796324400000L, 1796331600000L, false, "Europe/Zurich",
                    ausnahmeVon = 1796320800000L)))
            val href = d.absolut(k + NextcloudDav.dateiname("rw-test-1", ".ics"))
            val e1 = d.schreiben(href, null, ICal.schreiben(termin), "text/calendar; charset=utf-8")
            assertNotNull(e1)
            // gleiche Datei nochmal neu anlegen: existiert schon -> 412
            assertNull(d.schreiben(href, null, ICal.schreiben(termin), "text/calendar; charset=utf-8"))

            val a1 = d.aenderungen(k, erst.token)
            assertEquals(setOf(href), a1.geaendert.keys)
            val etag = a1.geaendert.getValue(href)
            val geholt = d.holen(k, NextcloudDav.Art.KALENDER, listOf(href)).single()
            val o = ICal.objekt(geholt.daten)!!
            assertEquals("FREQ=WEEKLY;COUNT=5", o.haupt.rrule)
            assertEquals(listOf(1796925600000L), o.haupt.exdates)
            assertEquals(1796320800000L, o.ausnahmen.single().ausnahmeVon)
            assertEquals(listOf(VErinnerung(15)), o.haupt.erinnerungen)
            assertTrue("X-RW-TEST:1" in o.haupt.unbekannt)

            // aendern mit If-Match; danach mit altem ETag -> 412 (der Server gewinnt)
            val e2 = d.schreiben(href, etag, ICal.schreiben(o.copy(haupt = o.haupt.copy(titel = "Serie 2"))), "text/calendar; charset=utf-8")
            assertNotNull(e2)
            assertNull(d.schreiben(href, etag, ICal.schreiben(o), "text/calendar; charset=utf-8"))
            val a2 = d.aenderungen(k, a1.token)
            assertEquals(setOf(href), a2.geaendert.keys)
            assertEquals(setOf(href), d.etags(k).keys)

            // umbenennen, umfaerben
            d.sammlungAendern(k, "TEST umbenannt", "#112233")
            val s2 = d.sammlungen(NextcloudDav.Art.KALENDER).first { it.url == k }
            assertEquals("TEST umbenannt", s2.name)
            assertEquals("#112233", s2.farbe?.take(7)?.uppercase())

            // loeschen: im Sync-Bericht als geloescht
            assertFalse(d.loeschen(href, etag))   // alter ETag -> 412
            assertTrue(d.loeschen(href, d.etags(k)[href]))
            val a3 = d.aenderungen(k, a2.token)
            assertEquals(setOf(href), a3.geloescht)
            assertTrue(a3.geaendert.isEmpty())

            // ungueltiges Token -> Rueckfall
            try {
                d.aenderungen(k, "http://radicale.org/ns/sync/ungueltig")
                throw AssertionError("TokenUngueltig erwartet")
            } catch (e: NextcloudDav.TokenUngueltig) { /* gut */ }
            d.eigeneAdresse()   // darf ohne Unterstuetzung nicht werfen
        } finally {
            d.sammlungLoeschen(k)
        }
        assertTrue(d.sammlungen(NextcloudDav.Art.KALENDER).none { it.url == k })
    }

    @Test
    fun zwoelfKalender() {
        val d = dav()
        val farben = (1..12).map { "#%02X%02X%02X".format(20 * it, 255 - 15 * it, 7 * it) }
        val urls = farben.mapIndexed { i, f -> d.kalenderAnlegen("TEST Viele $i", f) }
        try {
            val s = d.sammlungen(NextcloudDav.Art.KALENDER).filter { it.url in urls }
            assertEquals(12, s.size)
            assertEquals(farben.toSet(), s.map { it.farbe!!.take(7).uppercase() }.toSet())
            // umbenennen und loeschen auf dem Server sieht der naechste Abruf
            d.sammlungAendern(urls[0], "TEST Viele umbenannt", null)
            d.sammlungLoeschen(urls[1])
            val s2 = d.sammlungen(NextcloudDav.Art.KALENDER).filter { it.url in urls }
            assertEquals(11, s2.size)
            assertTrue(s2.any { it.name == "TEST Viele umbenannt" })
        } finally {
            urls.forEach { try { d.sammlungLoeschen(it) } catch (e: Exception) { /* schon weg */ } }
        }
    }

    @Test
    fun adressbuchZyklus() {
        val d = dav()
        val b = d.adressbuchAnlegen("TEST Rosenweg-Kontakte")
        try {
            assertTrue(d.sammlungen(NextcloudDav.Art.ADRESSEN).any { it.url == b })
            val k = VKontakt("rw-k-1", "Erika Muster", "Erika", "Muster", telefone = listOf(VEintrag(listOf("cell"), "+41790000000")),
                kategorien = listOf("Ausschuss"), geburtstag = "--05-17", foto = "/9j/4AAQ")
            val href = d.absolut(b + NextcloudDav.dateiname(k.uid, ".vcf"))
            assertNotNull(d.schreiben(href, null, VCard.schreiben(k), "text/vcard; charset=utf-8"))
            val a = d.aenderungen(b, "")
            assertEquals(setOf(href), a.geaendert.keys)
            val zurueck = VCard.lesen(d.holen(b, NextcloudDav.Art.ADRESSEN, a.geaendert.keys).single().daten)!!
            assertEquals(listOf("Ausschuss"), zurueck.kategorien)
            assertEquals("--05-17", zurueck.geburtstag)
            assertEquals("/9j/4AAQ", zurueck.foto)
            assertEquals("+41790000000", zurueck.telefone.single().wert)
        } finally {
            d.sammlungLoeschen(b)
        }
    }
}
