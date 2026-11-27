package ch.rosenweg4303.cloud.dav

import ch.rosenweg4303.cloud.Konto
import ch.rosenweg4303.cloud.NextcloudFehler
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * CardDAV und CalDAV der Rosenweg-Nextcloud, ohne DAVx5 (Stefan: die Apps sprechen das selbst).
 * Gleicher Zugang wie WebDAV: [Konto] aus KontoSpeicher - auf geteilten Telefonen von Rosenweg
 * Geraet, auf dem eigenen Handy ueber den Anmeldeschirm und NextcloudZugang.
 *
 * Nextcloud-Adressen (fest, wir sprechen nur mit dieser Nextcloud):
 *   Adressbuecher: /remote.php/dav/addressbooks/users/<benutzer>/<buch>/
 *   Kalender:      /remote.php/dav/calendars/<benutzer>/<kalender>/
 * [heim] ersetzt beide (nur fuer Tests gegen einen anderen DAV-Server, z.B. Radicale).
 *
 * Abgleich inkrementell per WebDAV-Sync (RFC 6578, [aenderungen]); ohne gueltiges Sync-Token
 * Rueckfall auf den ETag-Vergleich ([etags]). Inhalte holen per multiget ([holen]).
 * XML wird mit DOM gelesen (laeuft auf Android und in JVM-Tests gleich).
 * Alle Aufrufe blockieren - im Hintergrund aufrufen.
 */
class NextcloudDav(private val konto: Konto, appName: String, private val heimFest: String? = null) {
    enum class Art { ADRESSEN, KALENDER }

    /**
     * Adressbuch oder Kalender. [url] absolut, endet mit "/". [syncToken] leer, wenn der Server
     * kein WebDAV-Sync kann. [komponenten]: bei Kalendern z.B. VEVENT, VTODO.
     */
    data class Sammlung(
        val url: String,
        val name: String,
        val ctag: String,
        val farbe: String?,
        val nurLesen: Boolean,
        val syncToken: String = "",
        val komponenten: Set<String> = emptySet(),
    )

    /** vCard oder iCalendar mit Adresse und ETag. [href] absolut. */
    data class Objekt(val href: String, val etag: String, val daten: String)

    /** Ergebnis von [aenderungen]: geaenderte/neue Objekte (href -> ETag), geloeschte, neues Token. */
    data class Aenderungen(val geaendert: Map<String, String>, val geloescht: Set<String>, val token: String)

    /** Das gespeicherte Sync-Token gilt nicht mehr (oder der Server kann kein Sync) - voll abgleichen. */
    class TokenUngueltig(code: Int) : Exception("sync-token ungueltig ($code)")

    private val auth = Credentials.basic(konto.benutzer, konto.appPasswort)
    private val ua = appName.filter { it in ' '..'~' }.ifBlank { "Rosenweg (Android)" }
    private val xml = "application/xml; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(false).build()

    fun heim(art: Art): String = heimFest ?: (konto.server.trimEnd('/') + when (art) {
        Art.ADRESSEN -> "/remote.php/dav/addressbooks/users/${seg(konto.benutzer)}/"
        Art.KALENDER -> "/remote.php/dav/calendars/${seg(konto.benutzer)}/"
    })

    private fun seg(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Absolute, einheitlich kodierte Adresse (Server und wir kodieren Sonderzeichen sonst verschieden). */
    fun absolut(href: String): String = norm(if (href.startsWith("http")) href else konto.server.toHttpUrl().resolve(href)!!.toString())

    private fun anfrage(url: String) = Request.Builder().url(url).header("Authorization", auth).header("User-Agent", ua)

    private fun ausfuehren(r: Request): Pair<Int, String> {
        http.newCall(r).execute().use { a ->
            val text = a.body?.string().orEmpty()
            if (a.code == 401) throw NextcloudFehler(401, "Nextcloud: Anmeldung abgelaufen")
            return a.code to text
        }
    }

    private fun propfind(url: String, tiefe: String, props: String): Pair<Int, String> = ausfuehren(
        anfrage(url).method("PROPFIND", """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:card="urn:ietf:params:xml:ns:carddav" xmlns:a="http://apple.com/ns/ical/">
  <d:prop>$props</d:prop>
</d:propfind>""".toRequestBody(xml)).header("Depth", tiefe).build()
    )

    /** Adressbuecher bzw. Kalender (nur solche mit Terminen) der Person. */
    fun sammlungen(art: Art): List<Sammlung> {
        val (code, text) = propfind(heim(art), "1", "<d:resourcetype/><d:displayname/><cs:getctag/><d:sync-token/>" +
            "<a:calendar-color/><c:supported-calendar-component-set/><d:current-user-privilege-set/>")
        if (code != 207) throw NextcloudFehler(code, "PROPFIND ${art.name}: $code")
        return Multistatus.lesen(text).antworten.mapNotNull { a ->
            val typ = a.kinder["resourcetype"].orEmpty()
            val komp = a.kinder["supported-calendar-component-set"].orEmpty().filter { it.startsWith("comp:") }
                .map { it.removePrefix("comp:") }.toSet()
            val passt = when (art) {
                Art.ADRESSEN -> "addressbook" in typ
                Art.KALENDER -> "calendar" in typ && (komp.isEmpty() || "VEVENT" in komp)
            }
            if (!passt) return@mapNotNull null
            val rechte = a.kinder["current-user-privilege-set"]
            Sammlung(
                url = absolut(a.href), name = a.text["displayname"].orEmpty().ifEmpty { a.href.trimEnd('/').substringAfterLast('/') },
                ctag = a.text["getctag"].orEmpty(), farbe = a.text["calendar-color"]?.takeIf { it.startsWith("#") },
                // Abos (webcal, "subscribed") sind immer nur lesbar
                nurLesen = "subscribed" in typ || (rechte != null && listOf("write", "write-content", "bind", "all").none { it in rechte }),
                syncToken = a.text["sync-token"].orEmpty(), komponenten = komp,
            )
        }
    }

    /**
     * Eigene Kalender-Adresse (calendar-user-address-set, "mailto:"), z.B. fuer ORGANIZER und damit
     * Android den eigenen Teilnahmestatus erkennt. null, wenn der Server keine nennt.
     */
    fun eigeneAdresse(): String? = try {
        val (c1, t1) = propfind(heim(Art.KALENDER), "0", "<d:current-user-principal/>")
        val principal = if (c1 == 207) Multistatus.lesen(t1).antworten.firstOrNull()?.hrefs?.get("current-user-principal")?.firstOrNull() else null
        if (principal == null) null else {
            val (c2, t2) = propfind(absolut(principal), "0", "<c:calendar-user-address-set/>")
            if (c2 != 207) null else Multistatus.lesen(t2).antworten.firstOrNull()?.hrefs?.get("calendar-user-address-set")
                ?.firstOrNull { it.startsWith("mailto:", ignoreCase = true) }?.substring(7)?.takeIf { it.contains('@') }
        }
    } catch (e: NextcloudFehler) {
        if (e.anmeldungNoetig) throw e
        null
    }

    /**
     * WebDAV-Sync (RFC 6578): was sich seit [token] geaendert hat. Leeres Token = alles (erster
     * Abgleich). [TokenUngueltig], wenn der Server das Token nicht (mehr) kennt oder kein Sync kann.
     */
    fun aenderungen(sammlung: String, token: String): Aenderungen {
        val geaendert = mutableMapOf<String, String>()
        val geloescht = mutableSetOf<String>()
        var t = token
        val selbst = absolut(sammlung)
        repeat(MAX_RUNDEN) {
            val (code, text) = ausfuehren(anfrage(sammlung).method("REPORT", """<?xml version="1.0" encoding="utf-8"?>
<d:sync-collection xmlns:d="DAV:">
  <d:sync-token>${esc(t)}</d:sync-token>
  <d:sync-level>1</d:sync-level>
  <d:prop><d:getetag/></d:prop>
</d:sync-collection>""".toRequestBody(xml)).build())
            if (code != 207) {
                if (code in listOf(400, 403, 404, 409, 412, 415, 501)) throw TokenUngueltig(code)
                throw NextcloudFehler(code, "sync-collection: $code")
            }
            val ms = Multistatus.lesen(text)
            var weiter = false
            for (a in ms.antworten) {
                val href = absolut(a.href)
                if (href == selbst) { if (a.status == 507) weiter = true; continue }
                if (a.status == 404) { geloescht.add(href); geaendert.remove(href) }
                else a.text["getetag"]?.let { geaendert[href] = it; geloescht.remove(href) }
            }
            t = ms.syncToken ?: throw TokenUngueltig(code)
            if (!weiter) return Aenderungen(geaendert, geloescht, t)
        }
        return Aenderungen(geaendert, geloescht, t)
    }

    /** Alle Objekte einer Sammlung mit ETag (Rueckfall ohne WebDAV-Sync). */
    fun etags(sammlung: String): Map<String, String> {
        val (code, text) = propfind(sammlung, "1", "<d:resourcetype/><d:getetag/>")
        if (code != 207) throw NextcloudFehler(code, "PROPFIND: $code")
        val selbst = absolut(sammlung)
        return Multistatus.lesen(text).antworten.mapNotNull { a ->
            val href = absolut(a.href)
            if (href == selbst || "collection" in a.kinder["resourcetype"].orEmpty()) null
            else a.text["getetag"]?.let { href to it }
        }.toMap()
    }

    /** Inhalte per calendar-multiget / addressbook-multiget, in Paketen. */
    fun holen(sammlung: String, art: Art, hrefs: Collection<String>): List<Objekt> = hrefs.chunked(PAKET).flatMap { teil ->
        val (wurzel, ns, daten) = when (art) {
            Art.KALENDER -> Triple("c:calendar-multiget", "xmlns:c=\"urn:ietf:params:xml:ns:caldav\"", "c:calendar-data")
            Art.ADRESSEN -> Triple("c:addressbook-multiget", "xmlns:c=\"urn:ietf:params:xml:ns:carddav\"", "c:address-data")
        }
        val liste = teil.joinToString("") { "<d:href>${esc(it.toHttpUrl().encodedPath)}</d:href>" }
        report(sammlung, """<?xml version="1.0" encoding="utf-8"?>
<$wurzel xmlns:d="DAV:" $ns>
  <d:prop><d:getetag/><$daten/></d:prop>$liste
</$wurzel>""", daten.substringAfter(':'))
    }

    /** Alle vCards eines Adressbuchs. */
    fun adressen(buch: String): List<Objekt> = report(buch, """<?xml version="1.0" encoding="utf-8"?>
<card:addressbook-query xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
  <d:prop><d:getetag/><card:address-data/></d:prop>
</card:addressbook-query>""", "address-data")

    /**
     * Termine eines Kalenders; ohne Zeitraum alle. Mit Zeitraum: Serien, die hineinreichen, eingeschlossen.
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
        return Multistatus.lesen(text).antworten.mapNotNull { a ->
            val daten = a.text[datenProp]?.takeIf { a.status == null || a.status == 200 } ?: return@mapNotNull null
            Objekt(absolut(a.href), a.text["getetag"].orEmpty(), daten)
        }
    }

    /** Ein einzelnes Objekt (z.B. fuer das Protokoll); null = nicht (mehr) da. */
    fun lesen(href: String): Objekt? {
        http.newCall(anfrage(href).get().build()).execute().use { a ->
            if (a.code == 404 || a.code == 410) return null
            if (a.code == 401) throw NextcloudFehler(401, "Nextcloud: Anmeldung abgelaufen")
            if (a.code !in 200..299) throw NextcloudFehler(a.code, "GET: ${a.code}")
            return Objekt(absolut(href), a.header("ETag").orEmpty(), a.body?.string().orEmpty())
        }
    }

    /**
     * Anlegen ([etag] null, If-None-Match) oder aendern (If-Match; leerer [etag] = ohne Bedingung,
     * ueberschreibt). Rueckgabe: neuer ETag (leer, wenn der Server keinen nennt - dann neu holen).
     * 412 = auf dem Server inzwischen geaendert -> null (der Server gewinnt).
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

    // ─── Sammlungen anlegen, aendern, loeschen ─────────────────────────────────

    /** Neuer Kalender (MKCALENDAR, nur Termine). [farbe] "#RRGGBB". Rueckgabe: Adresse. */
    fun kalenderAnlegen(name: String, farbe: String?): String {
        val url = heim(Art.KALENDER) + kurzname(name) + "/"
        val f = farbe?.let { "<a:calendar-color>${esc(it)}</a:calendar-color>" }.orEmpty()
        val (code, _) = ausfuehren(anfrage(url).method("MKCALENDAR", """<?xml version="1.0" encoding="utf-8"?>
<c:mkcalendar xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/">
  <d:set><d:prop><d:displayname>${esc(name)}</d:displayname>$f
    <c:supported-calendar-component-set><c:comp name="VEVENT"/></c:supported-calendar-component-set>
  </d:prop></d:set>
</c:mkcalendar>""".toRequestBody(xml)).build())
        if (code != 201) throw NextcloudFehler(code, "MKCALENDAR: $code")
        return absolut(url)
    }

    /** Neues Adressbuch (erweitertes MKCOL). Rueckgabe: Adresse. */
    fun adressbuchAnlegen(name: String): String {
        val url = heim(Art.ADRESSEN) + kurzname(name) + "/"
        val (code, _) = ausfuehren(anfrage(url).method("MKCOL", """<?xml version="1.0" encoding="utf-8"?>
<d:mkcol xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
  <d:set><d:prop><d:resourcetype><d:collection/><card:addressbook/></d:resourcetype><d:displayname>${esc(name)}</d:displayname></d:prop></d:set>
</d:mkcol>""".toRequestBody(xml)).build())
        if (code != 201) throw NextcloudFehler(code, "MKCOL: $code")
        return absolut(url)
    }

    /** Name und/oder Farbe einer Sammlung aendern (PROPPATCH). */
    fun sammlungAendern(url: String, name: String?, farbe: String?) {
        if (name == null && farbe == null) return
        val p = (name?.let { "<d:displayname>${esc(it)}</d:displayname>" } ?: "") +
            (farbe?.let { "<a:calendar-color>${esc(it)}</a:calendar-color>" } ?: "")
        val (code, text) = ausfuehren(anfrage(url).method("PROPPATCH", """<?xml version="1.0" encoding="utf-8"?>
<d:propertyupdate xmlns:d="DAV:" xmlns:a="http://apple.com/ns/ical/">
  <d:set><d:prop>$p</d:prop></d:set>
</d:propertyupdate>""".toRequestBody(xml)).build())
        if (code != 207 && code !in 200..299) throw NextcloudFehler(code, "PROPPATCH: $code")
        if (code == 207 && Multistatus.lesen(text).fehlschlaege > 0) throw NextcloudFehler(403, "PROPPATCH abgelehnt")
    }

    /** Sammlung loeschen (bei freigegebenen Kalendern: Freigabe fuer mich beenden). */
    fun sammlungLoeschen(url: String) {
        if (!loeschen(url, null)) throw NextcloudFehler(412, "DELETE: 412")
    }

    private fun kurzname(name: String): String {
        val basis = java.text.Normalizer.normalize(name.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9]+"), "-").trim('-').take(30)
        return (if (basis.isEmpty()) "kalender" else basis) + "-" + UUID.randomUUID().toString().take(8)
    }

    companion object {
        private const val PAKET = 50
        private const val MAX_RUNDEN = 20

        fun utc(ms: Long): String = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(ms))

        internal fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        /** Pfadsegmente dekodieren und einheitlich wieder kodieren. */
        internal fun norm(url: String): String {
            val u = url.toHttpUrl()
            val b = u.newBuilder().encodedPath("/")
            val seg = u.pathSegments
            seg.forEachIndexed { i, s -> if (s.isNotEmpty() || i == seg.lastIndex) b.addPathSegment(s) }
            return b.build().toString()
        }

        /**
         * Die Server-Adresse eines Objekts aus einer lokalen Zeile (_SYNC_ID, SOURCE_ID) - nur, wenn es
         * eine absolute http(s)-Adresse IN [sammlung] ist, sonst null (= neu anlegen). Grund (A37,
         * 27.11.2026): der Kalender-Provider setzt bei lokalen Konten selbst _SYNC_ID wie
         * "Rosenweg-Nextcloud1795748294768"; daraus wurde "PUT /Rosenweg-Nextcloud..." (404) und ein
         * multiget mit fremdem Pfad (403).
         */
        fun inSammlung(href: String?, sammlung: String): String? {
            if (href.isNullOrBlank() || !(href.startsWith("https://") || href.startsWith("http://"))) return null
            val h = try { norm(href) } catch (e: IllegalArgumentException) { return null }
            val s = norm(sammlung).let { if (it.endsWith("/")) it else "$it/" }
            return h.takeIf { it.startsWith(s) && it.length > s.length && !it.endsWith("/") }
        }

        /** Dateiname fuer ein neues Objekt: die UID, wenn sie harmlos ist, sonst eine neue. */
        fun dateiname(uid: String, endung: String): String =
            (if (uid.matches(Regex("[A-Za-z0-9._-]{1,120}"))) uid else UUID.randomUUID().toString()) + endung
    }
}

/** WebDAV-Multistatus (DOM, ohne Android). */
internal class Multistatus(val antworten: List<Antwort>, val syncToken: String?, val fehlschlaege: Int) {
    /**
     * Ein <response>: [status] auf Antwort-Ebene (z.B. 404 im Sync-Bericht), [text] der Eigenschaften
     * mit Status 200, [kinder] die Namen der Kind-Elemente (Container wie resourcetype, bei comp
     * "comp:VEVENT"), [hrefs] enthaltene href-Texte.
     */
    class Antwort(
        val href: String,
        val status: Int?,
        val text: Map<String, String>,
        val kinder: Map<String, Set<String>>,
        val hrefs: Map<String, List<String>>,
    )

    companion object {
        private fun fabrik() = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            for (f in listOf("http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false)) {
                try { setFeature(f.first, f.second) } catch (e: Exception) { /* nicht jeder Parser kennt das */ }
            }
        }

        private fun Element.kinder(): List<Element> = (0 until childNodes.length).map { childNodes.item(it) }
            .filter { it.nodeType == Node.ELEMENT_NODE }.map { it as Element }

        private fun Element.name() = localName ?: nodeName.substringAfter(':')

        private fun status(s: String?): Int? = s?.trim()?.split(' ')?.getOrNull(1)?.toIntOrNull()

        fun lesen(text: String): Multistatus {
            val doc = fabrik().newDocumentBuilder().parse(InputSource(StringReader(text)))
            val wurzel = doc.documentElement
            val antworten = mutableListOf<Antwort>()
            var fehl = 0
            var token: String? = null
            for (r in wurzel.kinder()) {
                when (r.name()) {
                    "sync-token" -> token = r.textContent.trim()
                    "response" -> {
                        var href = ""
                        var st: Int? = null
                        val t = mutableMapOf<String, String>()
                        val k = mutableMapOf<String, Set<String>>()
                        val h = mutableMapOf<String, List<String>>()
                        for (e in r.kinder()) when (e.name()) {
                            "href" -> if (href.isEmpty()) href = e.textContent.trim()
                            "status" -> st = status(e.textContent)
                            "propstat" -> {
                                val ps = e.kinder()
                                val ok = status(ps.firstOrNull { it.name() == "status" }?.textContent) ?: 200
                                if (ok !in 200..299) { fehl++; continue }
                                for (prop in ps.filter { it.name() == "prop" }) for (p in prop.kinder()) {
                                    val n = p.name()
                                    t[n] = p.textContent.trim()
                                    val alle = mutableSetOf<String>()
                                    val hr = mutableListOf<String>()
                                    fun lauf(x: Element) {
                                        for (c in x.kinder()) {
                                            val cn = c.name()
                                            alle.add(cn)
                                            if (cn == "comp") alle.add("comp:" + c.getAttribute("name"))
                                            if (cn == "href") hr.add(c.textContent.trim())
                                            lauf(c)
                                        }
                                    }
                                    lauf(p)
                                    k[n] = alle
                                    h[n] = hr
                                }
                            }
                        }
                        antworten.add(Antwort(href, st, t, k, h))
                    }
                }
            }
            return Multistatus(antworten, token, fehl)
        }
    }
}
