package ch.rosenweg4303.cloud.dav

import android.util.Xml
import ch.rosenweg4303.cloud.Konto
import ch.rosenweg4303.cloud.NextcloudFehler
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * CardDAV und CalDAV der Rosenweg-Nextcloud, ohne DAVx5 (Stefan: die Apps sprechen das selbst).
 * Gleicher Zugang wie WebDAV: [Konto] aus KontoSpeicher - auf geteilten Telefonen von Rosenweg
 * Geraet, auf dem eigenen Handy ueber den Anmeldeschirm und NextcloudZugang.
 *
 * Nextcloud-Adressen (fest, wir sprechen nur mit dieser Nextcloud):
 *   Adressbuecher: /remote.php/dav/addressbooks/users/<benutzer>/<buch>/
 *   Kalender:      /remote.php/dav/calendars/<benutzer>/<kalender>/
 * Alle Aufrufe blockieren - im Hintergrund aufrufen.
 */
class NextcloudDav(private val konto: Konto, appName: String) {
    enum class Art { ADRESSEN, KALENDER }

    /** Adressbuch oder Kalender. [url] absolut, endet mit "/". */
    data class Sammlung(val url: String, val name: String, val ctag: String, val farbe: String?, val nurLesen: Boolean)

    /** vCard oder iCalendar mit Adresse und ETag. [href] absolut. */
    data class Objekt(val href: String, val etag: String, val daten: String)

    private val auth = Credentials.basic(konto.benutzer, konto.appPasswort)
    private val ua = appName.filter { it in ' '..'~' }.ifBlank { "Rosenweg (Android)" }
    private val xml = "application/xml; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(false).build()

    fun heim(art: Art): String = konto.server.trimEnd('/') + when (art) {
        Art.ADRESSEN -> "/remote.php/dav/addressbooks/users/${seg(konto.benutzer)}/"
        Art.KALENDER -> "/remote.php/dav/calendars/${seg(konto.benutzer)}/"
    }

    private fun seg(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun absolut(href: String): String =
        if (href.startsWith("http")) href else konto.server.toHttpUrl().resolve(href)!!.toString()

    private fun anfrage(url: String) = Request.Builder().url(url).header("Authorization", auth).header("User-Agent", ua)

    private fun ausfuehren(r: Request): Pair<Int, String> {
        http.newCall(r).execute().use { a ->
            val text = a.body?.string().orEmpty()
            if (a.code == 401) throw NextcloudFehler(401, "Nextcloud: Anmeldung abgelaufen")
            return a.code to text
        }
    }

    /** Adressbuecher bzw. Kalender (nur Termine) der Person. */
    fun sammlungen(art: Art): List<Sammlung> {
        val body = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/">
  <d:prop><d:resourcetype/><d:displayname/><cs:getctag/><a:calendar-color/><c:supported-calendar-component-set/><d:current-user-privilege-set/></d:prop>
</d:propfind>"""
        val (code, text) = ausfuehren(anfrage(heim(art)).method("PROPFIND", body.toRequestBody(xml)).header("Depth", "1").build())
        if (code != 207) throw NextcloudFehler(code, "PROPFIND ${art.name}: $code")
        return antworten(text).mapNotNull { a ->
            val typ = a.props["resourcetype"].orEmpty()
            val passt = when (art) {
                Art.ADRESSEN -> "addressbook" in typ
                Art.KALENDER -> "calendar" in typ && (a.props["supported-calendar-component-set"]?.contains("VEVENT") != false)
            }
            if (!passt) return@mapNotNull null
            Sammlung(
                url = absolut(a.href), name = a.props["displayname"].orEmpty().ifEmpty { a.href.trimEnd('/').substringAfterLast('/') },
                ctag = a.props["getctag"].orEmpty(), farbe = a.props["calendar-color"]?.takeIf { it.startsWith("#") },
                nurLesen = a.props["current-user-privilege-set"]?.let { "write" !in it } ?: false,
            )
        }
    }

    /** Alle vCards eines Adressbuchs. */
    fun adressen(buch: String): List<Objekt> = report(buch, """<?xml version="1.0" encoding="utf-8"?>
<card:addressbook-query xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
  <d:prop><d:getetag/><card:address-data/></d:prop>
</card:addressbook-query>""", "address-data")

    /**
     * Termine eines Kalenders; ohne Zeitraum alle (dann darf der Abgleich lokal loeschen, was auf dem
     * Server fehlt). Mit Zeitraum: Serien, die hineinreichen, eingeschlossen.
     */
    fun termine(kalender: String, von: Long? = null, bis: Long? = null): List<Objekt> {
        val bereich = if (von != null && bis != null) """<c:time-range start="${utc(von)}" end="${utc(bis)}"/>""" else ""
        return report(kalender, """<?xml version="1.0" encoding="utf-8"?>
<c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
  <d:prop><d:getetag/><c:calendar-data/></d:prop>
  <c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VEVENT">$bereich</c:comp-filter></c:comp-filter></c:filter>
</c:calendar-query>""", "calendar-data")
    }

    private fun report(url: String, body: String, datenProp: String): List<Objekt> {
        val (code, text) = ausfuehren(anfrage(url).method("REPORT", body.toRequestBody(xml)).header("Depth", "1").build())
        if (code != 207) throw NextcloudFehler(code, "REPORT: $code")
        return antworten(text).mapNotNull { a ->
            val daten = a.props[datenProp] ?: return@mapNotNull null
            Objekt(absolut(a.href), a.props["getetag"].orEmpty(), daten)
        }
    }

    /**
     * Anlegen ([etag] null, If-None-Match) oder aendern (If-Match). Rueckgabe: neuer ETag (leer, wenn
     * der Server keinen nennt). 412 = auf dem Server inzwischen geaendert -> null (der Server gewinnt).
     */
    fun schreiben(href: String, etag: String?, daten: String, typ: String): String? {
        val r = anfrage(href).put(daten.toRequestBody(typ.toMediaType()))
        if (etag == null) r.header("If-None-Match", "*") else if (etag.isNotEmpty()) r.header("If-Match", etag)
        http.newCall(r.build()).execute().use { a ->
            if (a.code == 412) return null
            if (a.code == 401) throw NextcloudFehler(401, "Nextcloud: Anmeldung abgelaufen")
            if (a.code !in 200..299) throw NextcloudFehler(a.code, "PUT: ${a.code}")
            return a.header("ETag").orEmpty()
        }
    }

    /** Loeschen; 404 gilt als erledigt. false = 412 (inzwischen geaendert, der Server gewinnt). */
    fun loeschen(href: String, etag: String?): Boolean {
        val r = anfrage(href).delete()
        if (!etag.isNullOrEmpty()) r.header("If-Match", etag)
        http.newCall(r.build()).execute().use { a ->
            if (a.code == 412) return false
            if (a.code == 401) throw NextcloudFehler(401, "Nextcloud: Anmeldung abgelaufen")
            if (a.code != 404 && a.code !in 200..299) throw NextcloudFehler(a.code, "DELETE: ${a.code}")
            return true
        }
    }

    // ─── Multistatus lesen ──────────────────────────────────────────────────

    private class Antwort(val href: String, val props: Map<String, String>)

    /** Je <response>: href und die Eigenschaften mit Status 200 (lokaler Name -> Text; bei Containern die Kind-Namen). */
    private fun antworten(text: String): List<Antwort> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(StringReader(text))
        val liste = mutableListOf<Antwort>()
        var href = ""
        var props = mutableMapOf<String, String>()
        val stat = mutableMapOf<String, String>()
        var statOk = true
        var inProp = false
        var propTiefe = 0
        var aktuell: String? = null
        val inhalt = StringBuilder()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    val n = p.name
                    when {
                        n == "response" -> { href = ""; props = mutableMapOf() }
                        n == "propstat" -> { stat.clear(); statOk = true }
                        n == "prop" && !inProp -> { inProp = true; propTiefe = p.depth }
                        inProp && p.depth == propTiefe + 1 -> { aktuell = n; inhalt.setLength(0) }
                        inProp && aktuell != null -> {
                            // Container (resourcetype, privilege-set, component-set): Kind-Namen merken
                            inhalt.append(' ').append(n).append(' ')
                            if (n == "comp") inhalt.append(p.getAttributeValue(null, "name").orEmpty()).append(' ')
                        }
                        else -> inhalt.setLength(0)   // href, status
                    }
                }
                XmlPullParser.TEXT -> inhalt.append(p.text)
                XmlPullParser.END_TAG -> {
                    val n = p.name
                    when {
                        inProp && p.depth == propTiefe + 1 && n == aktuell -> { stat[n] = inhalt.toString().trim(); aktuell = null }
                        n == "prop" && inProp && p.depth == propTiefe -> inProp = false
                        n == "status" && !inProp -> statOk = inhalt.contains(" 200")
                        n == "propstat" -> { if (statOk) props.putAll(stat); stat.clear() }
                        n == "href" && !inProp && href.isEmpty() -> href = inhalt.toString().trim()
                        n == "response" -> liste.add(Antwort(href, props))
                    }
                }
            }
        }
        return liste
    }

    companion object {
        fun utc(ms: Long): String = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(ms))
    }
}
