package ch.rosenweg4303.cloud.dav

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract
import android.util.Log
import ch.rosenweg4303.cloud.Konto
import java.util.UUID

/**
 * Kalender der Rosenweg-Nextcloud im Android-Kalender (CalendarContract), in beide Richtungen,
 * ohne DAVx5 (Stefan 26.11.2026: die Apps sprechen CalDAV selbst).
 *
 * - Je Nextcloud-Kalender ein lokaler Kalender unter dem Konto "Rosenweg-Nextcloud" (Typ LOCAL,
 *   _SYNC_ID = Adresse des Kalenders). Rosenweg Kalender (Fossify) zeigt ihn wie jeden
 *   Systemkalender ("CalDAV-Synchronisation").
 * - Zuerst hoch: in der App geaenderte (DIRTY) und geloeschte (DELETED) Termine per PUT/DELETE mit
 *   If-Match. Hat der Server inzwischen etwas anderes (412), gewinnt der Server.
 * - Dann herunter: alle Termine je Kalender, verglichen ueber den ETag; was auf dem Server fehlt,
 *   verschwindet lokal.
 * Termine: _SYNC_ID = Adresse der .ics, SYNC_DATA1 = ETag, SYNC_DATA2 = UID.
 * Grenzen dieser Stufe: abweichende Einzeltermine einer Serie und Ausnahmen (EXDATE), Erinnerungen
 * und Teilnehmer werden nicht abgeglichen.
 *
 * Braucht READ_CALENDAR/WRITE_CALENDAR. Blockiert - im Hintergrund aufrufen.
 */
class KalenderAbgleich(private val c: Context, konto: Konto, appName: String) {
    companion object {
        private const val TAG = "RosenwegKalender"
        const val KONTO_NAME = "Rosenweg-Nextcloud"
        const val KONTO_TYP = CalendarContract.ACCOUNT_TYPE_LOCAL

        fun alsAdapter(u: Uri): Uri = u.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, KONTO_NAME)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, KONTO_TYP).build()

        /** Ids der lokalen Nextcloud-Kalender (fuer die Auswahl in der App). */
        fun kalenderIds(c: Context): List<Long> = c.contentResolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID),
            "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=?", arrayOf(KONTO_NAME, KONTO_TYP), null)
            ?.use { cur -> generateSequence { if (cur.moveToNext()) cur.getLong(0) else null }.toList() } ?: emptyList()

        /** Abmelden: lokale Nextcloud-Kalender samt Terminen weg (die Daten bleiben auf dem Server). */
        fun entfernen(c: Context) {
            c.contentResolver.delete(alsAdapter(Calendars.CONTENT_URI),
                "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=?", arrayOf(KONTO_NAME, KONTO_TYP))
        }
    }

    data class Ergebnis(val kalender: Int, val hoch: Int, val herunter: Int, val geloescht: Int)

    private val dav = NextcloudDav(konto, appName)
    private val cr = c.contentResolver

    fun abgleichen(): Ergebnis {
        val server = dav.sammlungen(NextcloudDav.Art.KALENDER)
        val lokal = lokaleKalender()
        // Kalender, die es auf dem Server nicht mehr gibt
        lokal.filterKeys { url -> server.none { it.url == url } }.values.forEach { id ->
            cr.delete(ContentUris.withAppendedId(alsAdapter(Calendars.CONTENT_URI), id), null, null)
        }
        var hoch = 0; var herunter = 0; var weg = 0
        for (s in server) {
            val id = lokal[s.url] ?: anlegen(s)
            aktualisieren(id, s)
            if (!s.nurLesen) hoch += hochladen(id, s)
            val (n, w) = herunterladen(id, s)
            herunter += n; weg += w
        }
        Log.i(TAG, "Kalender: ${server.size}, hoch $hoch, herunter $herunter, geloescht $weg")
        return Ergebnis(server.size, hoch, herunter, weg)
    }

    private fun lokaleKalender(): Map<String, Long> = cr.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID, Calendars._SYNC_ID),
        "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=?", arrayOf(KONTO_NAME, KONTO_TYP), null)?.use { cur ->
        buildMap { while (cur.moveToNext()) cur.getString(1)?.let { put(it, cur.getLong(0)) } }
    } ?: emptyMap()

    private fun farbe(s: NextcloudDav.Sammlung): Int = try {
        android.graphics.Color.parseColor(s.farbe?.take(7) ?: "#1E3A8A")
    } catch (e: Exception) {
        android.graphics.Color.parseColor("#1E3A8A")
    }

    private fun anlegen(s: NextcloudDav.Sammlung): Long {
        val v = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, KONTO_NAME)
            put(Calendars.ACCOUNT_TYPE, KONTO_TYP)
            put(Calendars._SYNC_ID, s.url)
            put(Calendars.NAME, s.name)
            put(Calendars.OWNER_ACCOUNT, KONTO_NAME)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
        }
        val u = cr.insert(alsAdapter(Calendars.CONTENT_URI), v) ?: throw IllegalStateException("Kalender nicht angelegt")
        return ContentUris.parseId(u)
    }

    private fun aktualisieren(id: Long, s: NextcloudDav.Sammlung) {
        val v = ContentValues().apply {
            put(Calendars.CALENDAR_DISPLAY_NAME, "${s.name} (Nextcloud)")
            put(Calendars.CALENDAR_COLOR, farbe(s))
            put(Calendars.CALENDAR_ACCESS_LEVEL, if (s.nurLesen) Calendars.CAL_ACCESS_READ else Calendars.CAL_ACCESS_OWNER)
        }
        cr.update(ContentUris.withAppendedId(alsAdapter(Calendars.CONTENT_URI), id), v, null, null)
    }

    // ─── hoch ───────────────────────────────────────────────────────────────

    private val spalten = arrayOf(Events._ID, Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.DELETED,
        Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION, Events.DTSTART, Events.DTEND, Events.DURATION,
        Events.ALL_DAY, Events.EVENT_TIMEZONE, Events.RRULE)

    private fun hochladen(kalender: Long, s: NextcloudDav.Sammlung): Int {
        var n = 0
        cr.query(alsAdapter(Events.CONTENT_URI), spalten, "${Events.CALENDAR_ID}=? AND (${Events.DIRTY}=1 OR ${Events.DELETED}=1)",
            arrayOf(kalender.toString()), null)?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getLong(0)
                val href = cur.getString(1)
                val etag = cur.getString(2)
                val zeile = ContentUris.withAppendedId(alsAdapter(Events.CONTENT_URI), id)
                try {
                    if (cur.getInt(4) == 1) {
                        if (href != null) dav.loeschen(href, etag)
                        cr.delete(zeile, null, null)
                    } else {
                        val t = termin(cur)
                        val ziel = href ?: (s.url + t.uid + ".ics")
                        val neu = dav.schreiben(ziel, if (href == null) null else etag.orEmpty(), ICal.schreiben(t), "text/calendar; charset=utf-8")
                        cr.update(zeile, ContentValues().apply {
                            put(Events._SYNC_ID, ziel)
                            put(Events.SYNC_DATA2, t.uid)
                            // 412: der Server gewinnt - ETag leeren, damit das Herunterladen ersetzt
                            put(Events.SYNC_DATA1, neu.orEmpty())
                            put(Events.DIRTY, 0)
                        }, null, null)
                    }
                    n++
                } catch (e: ch.rosenweg4303.cloud.NextcloudFehler) {
                    if (e.anmeldungNoetig) throw e
                    Log.w(TAG, "hoch: ${e.code}")
                }
            }
        }
        return n
    }

    private fun termin(cur: Cursor): VTermin {
        val ganztags = cur.getInt(11) == 1
        val beginn = cur.getLong(8)
        val rrule = cur.getString(13)?.takeIf { it.isNotEmpty() }
        val ende = if (!cur.isNull(9) && cur.getLong(9) > 0) cur.getLong(9)
        else beginn + ICal.dauer(cur.getString(10).orEmpty()).let { if (it > 0) it else if (ganztags) 86_400_000L else 3_600_000L }
        return VTermin(
            uid = cur.getString(3)?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString(),
            titel = cur.getString(5).orEmpty(), beginn = beginn, ende = ende, ganztags = ganztags,
            zeitzone = cur.getString(12) ?: "UTC", ort = cur.getString(7).orEmpty(), beschreibung = cur.getString(6).orEmpty(),
            rrule = rrule,
        )
    }

    // ─── herunter ───────────────────────────────────────────────────────────

    /** -> (neu/geaendert, geloescht) */
    private fun herunterladen(kalender: Long, s: NextcloudDav.Sammlung): Pair<Int, Int> {
        val objekte = dav.termine(s.url)
        val lokal = mutableMapOf<String, Pair<Long, String>>()   // href -> (id, etag)
        cr.query(alsAdapter(Events.CONTENT_URI), arrayOf(Events._ID, Events._SYNC_ID, Events.SYNC_DATA1, Events.DIRTY),
            "${Events.CALENDAR_ID}=?", arrayOf(kalender.toString()), null)?.use { cur ->
            while (cur.moveToNext()) {
                val href = cur.getString(1) ?: continue
                if (cur.getInt(3) == 1) continue   // lokal geaendert und noch nicht oben: nicht anfassen
                lokal[href] = cur.getLong(0) to cur.getString(2).orEmpty()
            }
        }
        var n = 0
        for (o in objekte) {
            val alt = lokal.remove(o.href)
            if (alt != null && alt.second == o.etag && o.etag.isNotEmpty()) continue
            val t = ICal.lesen(o.daten).firstOrNull() ?: continue
            val v = werte(kalender, t, o)
            if (alt != null) cr.update(ContentUris.withAppendedId(alsAdapter(Events.CONTENT_URI), alt.first), v, null, null)
            else cr.insert(alsAdapter(Events.CONTENT_URI), v)
            n++
        }
        // auf dem Server geloescht
        lokal.values.forEach { (id, _) -> cr.delete(ContentUris.withAppendedId(alsAdapter(Events.CONTENT_URI), id), null, null) }
        return n to lokal.size
    }

    private fun werte(kalender: Long, t: VTermin, o: NextcloudDav.Objekt) = ContentValues().apply {
        put(Events.CALENDAR_ID, kalender)
        put(Events.TITLE, t.titel)
        put(Events.DESCRIPTION, t.beschreibung)
        put(Events.EVENT_LOCATION, t.ort)
        put(Events.DTSTART, t.beginn)
        put(Events.ALL_DAY, if (t.ganztags) 1 else 0)
        put(Events.EVENT_TIMEZONE, if (t.ganztags) "UTC" else t.zeitzone)
        if (t.rrule != null) {
            // Serien: Android will DURATION statt DTEND
            putNull(Events.DTEND)
            put(Events.RRULE, t.rrule)
            val ms = maxOf(0, t.ende - t.beginn)
            put(Events.DURATION, if (t.ganztags) "P${maxOf(1, ms / 86_400_000L)}D" else "P${ms / 1000}S")
        } else {
            put(Events.DTEND, t.ende)
            putNull(Events.RRULE)
            putNull(Events.DURATION)
        }
        put(Events._SYNC_ID, o.href)
        put(Events.SYNC_DATA1, o.etag)
        put(Events.SYNC_DATA2, t.uid)
        put(Events.DIRTY, 0)
    }
}
