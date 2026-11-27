package ch.rosenweg4303.cloud.dav

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** vCard 3.0/4.0 vollstaendig, Gruppen, Fotos, Multistatus. */
class VCardVollTest {
    private val jpeg = Base64.getEncoder().encodeToString(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4, 5))

    private val nextcloud = "BEGIN:VCARD\r\nVERSION:3.0\r\nPRODID:-//Sabre//Sabre VObject 4.5.6//EN\r\nUID:k-1\r\n" +
        "FN:Dr. Erika M. Muster\r\nN:Muster;Erika;Maria;Dr.;\r\nNICKNAME:Riki\r\nORG:Rosenweg;Technik\r\nTITLE:Leiterin\r\n" +
        "TEL;TYPE=\"cell\":+41 79 000 00 00\r\nTEL;TYPE=WORK,FAX:+41 61 000 00 01\r\nTEL;TYPE=HOME;TYPE=VOICE:+41 61 000 00 02\r\n" +
        "item1.TEL:3001\r\nitem1.X-ABLabel:Haustelefon\r\n" +
        "EMAIL;TYPE=HOME,PREF:erika@example.org\r\nEMAIL;TYPE=WORK:e.muster@example.com\r\n" +
        "ADR;TYPE=HOME:;;Rosenweg 9;Kaiseraugst;AG;4303;Schweiz\r\nADR;TYPE=WORK:Postfach 1;;Hauptstr. 1\\, 2. Stock;Basel;;4000;\r\n" +
        "URL:https://rosenweg4303.ch\r\nBDAY:1980-05-17\r\nX-ANNIVERSARY:20050611\r\n" +
        "NOTE:Erste Zeile\\nZweite\\; mit Semikolon\r\nCATEGORIES:Ausschuss,STWEG 3\r\n" +
        "PHOTO;ENCODING=b;TYPE=JPEG:$jpeg\r\nX-SOCIALPROFILE;TYPE=mastodon:https://x.y/@e\r\nIMPP:xmpp:erika@example.org\r\n" +
        "REV:20261120T100000Z\r\nEND:VCARD\r\n"

    @Test
    fun vollstaendigLesen() {
        val k = VCard.lesen(nextcloud)!!
        assertEquals("Dr. Erika M. Muster", k.anzeigename)
        assertEquals(listOf("Erika", "Muster", "Maria", "Dr."), listOf(k.vorname, k.nachname, k.zweitname, k.praefix))
        assertEquals("Riki", k.spitzname)
        assertEquals(Triple("Rosenweg", "Technik", "Leiterin"), Triple(k.firma, k.abteilung, k.position))
        assertEquals(4, k.telefone.size)
        assertEquals(listOf("work", "fax"), k.telefone[1].typen)
        assertEquals(listOf("home", "voice"), k.telefone[2].typen)
        assertEquals("Haustelefon", k.telefone[3].bezeichnung)
        assertTrue(k.emails[0].bevorzugt)
        assertEquals("Rosenweg 9", k.adressen[0].strasse)
        assertEquals("4303", k.adressen[0].plz)
        assertEquals("Hauptstr. 1, 2. Stock", k.adressen[1].strasse)
        assertEquals("Postfach 1", k.adressen[1].postfach)
        assertEquals("1980-05-17", k.geburtstag)
        assertEquals("2005-06-11", k.jahrestag)
        assertEquals("Erste Zeile\nZweite; mit Semikolon", k.notiz)
        assertEquals(listOf("Ausschuss", "STWEG 3"), k.kategorien)
        assertEquals(jpeg, k.foto)
        assertEquals(listOf("X-SOCIALPROFILE;TYPE=mastodon:https://x.y/@e", "IMPP:xmpp:erika@example.org"), k.unbekannt)
        assertFalse(k.istGruppe)
    }

    @Test
    fun hinUndZurueck() {
        val k = VCard.lesen(nextcloud)!!
        val text = VCard.schreiben(k)
        assertTrue(text.contains("rw1.TEL:3001\r\nrw1.X-ABLabel:Haustelefon"))
        assertTrue(text.contains("CATEGORIES:Ausschuss,STWEG 3"))
        assertTrue(text.contains("X-SOCIALPROFILE;TYPE=mastodon:https://x.y/@e"))
        assertEquals(k, VCard.lesen(text))
    }

    @Test
    fun vcard4FotoDatumOhneJahrGruppe() {
        val v4 = VCard.lesen("BEGIN:VCARD\nVERSION:4.0\nUID:urn:uuid:1\nFN:Max\nTEL;VALUE=uri;TYPE=\"voice,cell\":tel:+41790000000\n" +
            "PHOTO:data:image/jpeg;base64,$jpeg\nBDAY:--0517\nEND:VCARD")!!
        assertEquals("+41790000000", v4.telefone.single().wert)
        assertEquals(listOf("voice", "cell"), v4.telefone.single().typen)
        assertEquals(jpeg, v4.foto)
        assertEquals("--05-17", v4.geburtstag)
        assertTrue(VCard.schreiben(v4).contains("BDAY;X-APPLE-OMIT-YEAR=1604:1604-05-17"))
        assertEquals("--05-17", VCard.lesen(VCard.schreiben(v4))!!.geburtstag)
        // Foto als Link bleibt unveraendert
        val link = VCard.lesen("BEGIN:VCARD\nVERSION:3.0\nFN:L\nPHOTO;VALUE=uri:https://x/y.jpg\nEND:VCARD")!!
        assertNull(link.foto)
        assertEquals(listOf("PHOTO;VALUE=uri:https://x/y.jpg"), link.unbekannt)
        // Gruppen-vCard (Apple) wird erkannt
        val g = VCard.lesen("BEGIN:VCARD\nVERSION:3.0\nFN:Freunde\nX-ADDRESSBOOKSERVER-KIND:group\nX-ADDRESSBOOKSERVER-MEMBER:urn:uuid:1\nEND:VCARD")!!
        assertTrue(g.istGruppe)
    }

    @Test
    fun abbildungAufContactsContract() {
        val k = VCard.lesen(nextcloud)!!
        val d = KontakteAbbildung.nachAndroid(k)
        val tel = d.filter { it.mime == Phone.CONTENT_ITEM_TYPE }
        assertEquals(listOf(Phone.TYPE_MOBILE, Phone.TYPE_FAX_WORK, Phone.TYPE_HOME, Phone.TYPE_CUSTOM), tel.map { it.werte[Phone.TYPE] })
        assertEquals("Haustelefon", tel[3].werte[Phone.LABEL])
        val mail = d.filter { it.mime == Email.CONTENT_ITEM_TYPE }
        assertEquals(Email.TYPE_HOME, mail[0].werte[Email.TYPE])
        assertEquals(1, mail[0].werte[android.provider.ContactsContract.Data.IS_PRIMARY])
        val adr = d.filter { it.mime == StructuredPostal.CONTENT_ITEM_TYPE }
        assertEquals("Rosenweg 9\n4303 Kaiseraugst\nAG\nSchweiz", adr[0].werte[StructuredPostal.FORMATTED_ADDRESS])
        val ev = d.filter { it.mime == Event.CONTENT_ITEM_TYPE }.associate { it.werte[Event.TYPE] to it.werte[Event.START_DATE] }
        assertEquals(mapOf(Event.TYPE_BIRTHDAY to "1980-05-17", Event.TYPE_ANNIVERSARY to "2005-06-11"), ev)
        val foto = d.single { it.mime == Photo.CONTENT_ITEM_TYPE }.werte[Photo.PHOTO] as ByteArray
        assertArrayEquals(Base64.getDecoder().decode(jpeg), foto)

        // zurueck (wie aus dem Provider: Zahlen als Long)
        val zeilen = d.map { z -> KontakteAbbildung.Daten(z.mime, z.werte.mapValues { (_, v) -> if (v is Int) v.toLong() else v }) }
        val zurueck = KontakteAbbildung.vonAndroid("k-1", zeilen, k.kategorien)
        assertEquals(k.copy(telefone = k.telefone.map { it.copy(typen = KontakteAbbildung.telefonTypen(KontakteAbbildung.telefonTyp(it))) },
            emails = k.emails.map { it.copy(typen = listOf("internet") + (it.typen - "pref")) }), zurueck)
    }

    @Test
    fun multistatusSyncBericht() {
        val ms = Multistatus.lesen("""<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav" xmlns:oc="http://owncloud.org/ns">
 <d:response><d:href>/remote.php/dav/calendars/stefan/privat/neu.ics</d:href>
  <d:propstat><d:prop><d:getetag>"e1"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/calendars/stefan/privat/weg%20da.ics</d:href><d:status>HTTP/1.1 404 Not Found</d:status></d:response>
 <d:response><d:href>/remote.php/dav/calendars/stefan/geteilt/</d:href>
  <d:propstat><d:prop><d:resourcetype><d:collection/><cal:calendar/></d:resourcetype>
   <d:current-user-privilege-set><d:privilege><d:read/></d:privilege><d:privilege><d:write-properties/></d:privilege></d:current-user-privilege-set>
   <cal:supported-calendar-component-set><cal:comp name="VEVENT"/><cal:comp name="VTODO"/></cal:supported-calendar-component-set>
  </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
  <d:propstat><d:prop><oc:fehlt/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>
 <d:sync-token>http://sabre.io/ns/sync/42</d:sync-token>
</d:multistatus>""")
        assertEquals("http://sabre.io/ns/sync/42", ms.syncToken)
        assertEquals("\"e1\"", ms.antworten[0].text["getetag"])
        assertEquals(404, ms.antworten[1].status)
        val g = ms.antworten[2]
        assertTrue("calendar" in g.kinder["resourcetype"]!!)
        assertTrue("comp:VTODO" in g.kinder["supported-calendar-component-set"]!!)
        val rechte = g.kinder["current-user-privilege-set"]!!
        assertFalse(listOf("write", "write-content", "bind", "all").any { it in rechte })
        assertEquals(1, ms.fehlschlaege)
        assertEquals("https://nextcloud.rosenweg4303.ch/remote.php/dav/calendars/stefan/privat/weg%20da.ics",
            NextcloudDav.norm("https://nextcloud.rosenweg4303.ch/remote.php/dav/calendars/stefan/privat/weg da.ics"))
        assertEquals(NextcloudDav.norm("https://h/a/b%40c.ics"), NextcloudDav.norm("https://h/a/b@c.ics"))
        assertEquals("https://h/a/", NextcloudDav.norm("https://h/a/"))
        assertEquals("x-1.ics", NextcloudDav.dateiname("x-1", ".ics"))
        // Adresse nur, wenn sie in der Sammlung liegt (A37: Provider setzt _SYNC_ID "Rosenweg-Nextcloud<Zeit>")
        val k = "https://nextcloud.rosenweg4303.ch/remote.php/dav/calendars/stefan.mueller/personal/"
        assertNull(NextcloudDav.inSammlung("Rosenweg-Nextcloud1795748294768", k))
        assertNull(NextcloudDav.inSammlung(null, k))
        assertNull(NextcloudDav.inSammlung("", k))
        assertNull(NextcloudDav.inSammlung("https://nextcloud.rosenweg4303.ch/Rosenweg-Nextcloud1795748294768", k))
        assertNull(NextcloudDav.inSammlung("https://nextcloud.rosenweg4303.ch/remote.php/dav/calendars/stefan.mueller/andere/a.ics", k))
        assertNull(NextcloudDav.inSammlung(k, k))
        assertEquals(NextcloudDav.norm(k + "a%40b.ics"), NextcloudDav.inSammlung(k + "a@b.ics", k))
        assertEquals(k + "x.ics", NextcloudDav.inSammlung(k + "x.ics", k.trimEnd('/')))
        assertTrue(NextcloudDav.dateiname("a b@c", ".ics").matches(Regex("[0-9a-f-]{36}\\.ics")))
    }
}
