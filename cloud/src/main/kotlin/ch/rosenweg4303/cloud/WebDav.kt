package ch.rosenweg4303.cloud

import android.util.Xml
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

internal object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followRedirects(false) // Zugangsdaten nie an eine Umleitung weitergeben
            .build()
    }

    /**
     * HTTP-Kopfzeilen duerfen nur ASCII enthalten (OkHttp wirft sonst):
     * "Diktiergerät (Android)" -> "Diktiergeraet (Android)".
     */
    fun ascii(text: String): String {
        val ersetzt = text.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
            .replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue").replace("ß", "ss")
        val ohneAkzent = java.text.Normalizer.normalize(ersetzt, java.text.Normalizer.Form.NFD)
        return ohneAkzent.filter { it in ' '..'~' }.ifBlank { "Rosenweg (Android)" }
    }
}

class NextcloudFehler(val code: Int, meldung: String) : IOException(meldung) {
    /** App-Passwort widerrufen oder Konto gesperrt -> neu anmelden. */
    val anmeldungNoetig get() = code == 401

    /** Server voruebergehend ueberlastet — spaeter nochmal, nichts kaputt. */
    val voruebergehend get() = code in 502..504
}

/** Ein Eintrag in einem Nextcloud-Ordner. [pfad] relativ zur Wurzel der Person, ohne fuehrenden "/". */
data class Eintrag(
    val name: String,
    val pfad: String,
    val istOrdner: Boolean,
    val groesse: Long,
    val geaendert: Long,
    val mime: String,
    val etag: String,
    /** oc:permissions der Nextcloud, z.B. "RGDNVW"; leer = unbekannt (dann alles erlaubt). */
    val rechte: String = "",
    /** oc:fileid — fuer Adressen in die Nextcloud-Weboberflaeche (z.B. Euro-Office). 0 = unbekannt. */
    val fileId: Long = 0,
) {
    private fun hat(z: Char) = rechte.isEmpty() || z in rechte
    val darfSchreiben get() = hat('W')
    val darfAnlegen get() = rechte.isEmpty() || 'C' in rechte || 'K' in rechte
    val darfLoeschen get() = hat('D')
    val darfUmbenennen get() = hat('N') && hat('V')
}

/**
 * WebDAV auf die Dateien einer Person. Alle Aufrufe blockieren — im
 * Hintergrund aufrufen. Pfade sind relativ zur Wurzel der Person ("" = Wurzel,
 * "Fotos/2026").
 */
class WebDav(private val konto: Konto, private val appName: String) {
    private val basis: HttpUrl = "${konto.server}/remote.php/dav/files/".toHttpUrl()
        .newBuilder().addPathSegment(konto.benutzer).build()
    private val auth = Credentials.basic(konto.benutzer, konto.appPasswort)
    private val ua = Http.ascii(appName)

    /**
     * Adresse, unter der die Nextcloud-Weboberflaeche die Datei direkt oeffnet
     * (Office-Dateien im Euro-Office-Editor). Anmeldung im Browser ueber Authentik.
     */
    fun webAdresse(e: Eintrag): String = "${konto.server}/index.php/f/${e.fileId}" // oeffnet standardmaessig die Datei

    /** Vorschaubild der Nextcloud (JPEG, hoechstens [px] gross). Kopfzeilen: [abspielKopfzeilen]. */
    fun vorschauAdresse(e: Eintrag, px: Int = 256): String =
        "${konto.server}/index.php/core/preview?fileId=${e.fileId}&x=$px&y=$px&a=1&mode=cover"

    /** Office-Datei direkt im Euro-Office-Editor der Nextcloud (eigener Tab, kein Umweg ueber die Dateiliste). */
    fun officeAdresse(e: Eintrag): String = "${konto.server}/index.php/apps/eurooffice/${e.fileId}"

    fun url(pfad: String): HttpUrl {
        val b = basis.newBuilder()
        pfad.split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
        return b.build()
    }

    private fun anfrage(pfad: String) = Request.Builder().url(url(pfad))
        .header("Authorization", auth).header("User-Agent", ua)

    /**
     * [wiederholen] nur fuer Anfragen ohne Nebenwirkung (PROPFIND, GET): Bei
     * Ueberlastung (502/503/504, z.B. waehrend der naechtlichen Sicherung)
     * einmal nach 3 s erneut versuchen.
     */
    private fun ausfuehren(r: Request, wiederholen: Boolean = false): Response {
        var a = Http.client.newCall(r).execute()
        if (wiederholen && a.code in UEBERLASTET) {
            a.close()
            Thread.sleep(3000)
            a = Http.client.newCall(r).execute()
        }
        if (!a.isSuccessful) {
            a.close()
            throw NextcloudFehler(a.code, when (a.code) {
                401 -> "Anmeldung abgelaufen"
                403 -> "Keine Berechtigung"
                404 -> "Nicht gefunden"
                405, 409 -> "Gibt es schon oder Ordner fehlt"
                412 -> "Gibt es schon"
                507 -> "Speicher voll"
                in UEBERLASTET -> "gerade überlastet, bitte gleich nochmal versuchen"
                in 500..599 -> "Serverfehler ${a.code}"
                else -> "Fehler ${a.code}"
            })
        }
        return a
    }

    fun liste(pfad: String): List<Eintrag> {
        val r = anfrage(pfad).header("Depth", "1")
            .method("PROPFIND", PROPFIND.toRequestBody("application/xml; charset=utf-8".toMediaTypeOrNull())).build()
        ausfuehren(r, wiederholen = true).use { a ->
            val alle = parse(a.body!!.byteStream())
            val selbst = pfad.trim('/')
            return alle.filter { it.pfad != selbst }
                .sortedWith(compareBy<Eintrag> { !it.istOrdner }.thenBy { it.name.lowercase(Locale.ROOT) })
        }
    }

    /**
     * Alle Dateien unter [ordner], auch in Unterordnern (bis [tiefe] Ebenen).
     * Nextcloud erlaubt kein "Depth: infinity", deshalb Ordner fuer Ordner.
     * Gibt es den Ordner nicht, ist die Liste leer.
     */
    fun alleDateien(ordner: String, tiefe: Int = 3): List<Eintrag> {
        val inhalt = try {
            liste(ordner)
        } catch (e: NextcloudFehler) {
            if (e.code == 404) return emptyList() else throw e
        }
        return inhalt.flatMap { e ->
            when {
                !e.istOrdner -> listOf(e)
                tiefe > 0 && !e.name.startsWith(".") -> alleDateien(e.pfad, tiefe - 1)
                else -> emptyList()
            }
        }
    }

    /** Kopfzeilen, um eine Datei direkt zu streamen (z.B. MediaPlayer.setDataSource(ctx, uri, kopfzeilen)). */
    fun abspielKopfzeilen(): Map<String, String> = mapOf("Authorization" to auth, "User-Agent" to ua)

    fun eigenschaften(pfad: String): Eintrag? {
        val r = anfrage(pfad).header("Depth", "0")
            .method("PROPFIND", PROPFIND.toRequestBody("application/xml; charset=utf-8".toMediaTypeOrNull())).build()
        return try {
            ausfuehren(r, wiederholen = true).use { parse(it.body!!.byteStream()).firstOrNull() }
        } catch (e: NextcloudFehler) {
            if (e.code == 404) null else throw e
        }
    }

    /** Laedt nach [ziel]; erst nach vollstaendigem Empfang wird umbenannt. */
    fun herunterladen(pfad: String, ziel: File, fortschritt: ((Long, Long) -> Unit)? = null) {
        ausfuehren(anfrage(pfad).get().build(), wiederholen = true).use { a ->
            val gesamt = a.body!!.contentLength()
            val tmp = File(ziel.parentFile, ".${ziel.name}.teil")
            ziel.parentFile?.mkdirs()
            a.body!!.byteStream().use { ein ->
                tmp.outputStream().use { aus ->
                    val puffer = ByteArray(64 * 1024)
                    var n: Int
                    var bisher = 0L
                    while (ein.read(puffer).also { n = it } >= 0) {
                        aus.write(puffer, 0, n)
                        bisher += n
                        fortschritt?.invoke(bisher, gesamt)
                    }
                }
            }
            if (!tmp.renameTo(ziel)) {
                tmp.delete()
                throw IOException("Konnte ${ziel.name} nicht speichern")
            }
        }
    }

    /**
     * Laedt hoch. Gilt erst als erledigt, wenn die Nextcloud 201/204 meldet —
     * erst danach duerfen Apps die lokale Kopie loeschen (Grundregel).
     *
     * [nurNeu]: nur anlegen, nie ueberschreiben (412, wenn es den Namen schon gibt).
     * [wennEtag]: nur ueberschreiben, wenn die Datei auf dem Server noch diese
     * Fassung hat, sonst [NextcloudFehler] 412 (jemand anderes war schneller).
     *
     * @return ETag der neuen Fassung, falls die Nextcloud ihn mitschickt
     */
    fun hochladen(
        pfad: String, quelle: () -> InputStream, laenge: Long, mime: String?,
        geaendertMs: Long? = null, wennEtag: String? = null, nurNeu: Boolean = false,
    ): String? {
        val body = object : RequestBody() {
            override fun contentType() = (mime ?: "application/octet-stream").toMediaTypeOrNull()
            override fun contentLength() = laenge
            override fun writeTo(sink: BufferedSink) {
                quelle().source().use { sink.writeAll(it) }
            }
        }
        val b = anfrage(pfad).put(body)
        geaendertMs?.let { b.header("X-OC-Mtime", (it / 1000).toString()) }
        wennEtag?.let { b.header("If-Match", "\"$it\"") }
        if (nurNeu) b.header("If-None-Match", "*") // gibt es die Datei schon -> 412, nichts ueberschreiben
        ausfuehren(b.build()).use { a ->
            return (a.header("OC-ETag") ?: a.header("ETag"))?.trim('"')
        }
    }

    fun ordnerAnlegen(pfad: String) {
        ausfuehren(anfrage(pfad).method("MKCOL", null).build()).close()
    }

    /** Legt alle fehlenden Ordner bis [pfad] an. */
    fun ordnerSicherstellen(pfad: String) {
        var bisher = ""
        for (teil in pfad.split('/').filter { it.isNotEmpty() }) {
            bisher = if (bisher.isEmpty()) teil else "$bisher/$teil"
            if (eigenschaften(bisher) == null) ordnerAnlegen(bisher)
        }
    }

    /** Verschiebt in den Papierkorb der Nextcloud (wie im Browser). */
    fun loeschen(pfad: String) {
        ausfuehren(anfrage(pfad).delete().build()).close()
    }

    fun verschieben(von: String, nach: String, ueberschreiben: Boolean = false) {
        ausfuehren(
            anfrage(von).method("MOVE", null)
                .header("Destination", url(nach).toString())
                .header("Overwrite", if (ueberschreiben) "T" else "F").build()
        ).close()
    }

    /** Widerruft das App-Passwort auf dem Server (Abmelden). Fehler sind egal. */
    fun appPasswortWiderrufen() {
        try {
            Http.client.newCall(
                Request.Builder().url("${konto.server}/ocs/v2.php/core/apppassword")
                    .header("Authorization", auth).header("OCS-APIRequest", "true")
                    .header("User-Agent", ua).delete().build()
            ).execute().close()
        } catch (e: IOException) {
        }
    }

    private fun parse(ein: InputStream): List<Eintrag> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(ein, "UTF-8")
        val praefix = basis.encodedPath.trimEnd('/') + "/"
        val liste = ArrayList<Eintrag>()
        var href = ""; var ordner = false; var groesse = 0L; var geaendert = 0L; var mime = ""; var etag = ""; var rechte = ""; var fileId = 0L
        var text = StringBuilder()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    if (p.name == "response") {
                        href = ""; ordner = false; groesse = 0L; geaendert = 0L; mime = ""; etag = ""; rechte = ""; fileId = 0L
                    }
                    if (p.name == "collection") ordner = true
                }
                XmlPullParser.TEXT -> text.append(p.text)
                XmlPullParser.END_TAG -> {
                    val t = text.toString().trim()
                    when (p.name) {
                        "href" -> href = t
                        "getcontentlength" -> if (!ordner) groesse = t.toLongOrNull() ?: 0L
                        "size" -> if (ordner) groesse = t.toLongOrNull() ?: 0L
                        "getlastmodified" -> geaendert = datum(t)
                        "getcontenttype" -> mime = t
                        "getetag" -> etag = t.trim('"')
                        "permissions" -> rechte = t
                        "fileid" -> fileId = t.toLongOrNull() ?: 0L
                        "response" -> {
                            val roh = if (href.startsWith(praefix)) href.substring(praefix.length) else href
                            val rel = roh.split('/').filter { it.isNotEmpty() }
                                .joinToString("/") { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
                            liste.add(Eintrag(rel.substringAfterLast('/'), rel, ordner, groesse, geaendert,
                                if (ordner) "inode/directory" else mime.ifEmpty { "application/octet-stream" }, etag, rechte, fileId))
                        }
                    }
                    text = StringBuilder()
                }
            }
        }
        return liste
    }

    private fun datum(t: String): Long = try {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(t)?.time ?: 0L
    } catch (e: Exception) {
        0L
    }

    private companion object {
        val UEBERLASTET = setOf(502, 503, 504)
        const val PROPFIND = """<?xml version="1.0" encoding="UTF-8"?>
<d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
  <d:prop>
    <d:getlastmodified/><d:getcontentlength/><d:getcontenttype/><d:resourcetype/><d:getetag/><oc:size/><oc:permissions/><oc:fileid/>
  </d:prop>
</d:propfind>"""
    }
}
