package ch.rosenweg4303.cloud.dav

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Colors
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.ExtendedProperties
import android.provider.CalendarContract.Reminders
import android.util.Log
import ch.rosenweg4303.cloud.CloudStatus
import ch.rosenweg4303.cloud.Konto
import ch.rosenweg4303.cloud.NextcloudFehler

/**
 * Kalender der Rosenweg-Nextcloud im Android-Kalender (CalendarContract), in beide Richtungen,
 * ohne DAVx5 (Stefan 26.11.2026: die Apps sprechen CalDAV selbst).
 *
 * - Je Nextcloud-Kalender ein lokaler Kalender unter dem Konto "Rosenweg-Nextcloud" (Typ LOCAL,
 *   _SYNC_ID = Adresse). Rosenweg Kalender (Fossify) zeigt ihn wie jeden Systemkalender.
 *   CAL_SYNC1 = Sync-Token, CAL_SYNC2 = CTag, CAL_SYNC3/4 = zuletzt abgeglichene Farbe/Name
 *   (aendert die App sie, gehen sie per PROPPATCH in die Nextcloud).
 * - Zuerst hoch: geaenderte (DIRTY) und geloeschte (DELETED) Termine samt ihren Einzelterminen per
 *   PUT/DELETE mit If-Match. 412 (inzwischen auf dem Server geaendert): der Server gewinnt, die
 *   lokale Fassung kommt ins [AbgleichProtokoll] (wiederherstellbar).
 * - Dann herunter, inkrementell: WebDAV-Sync (sync-token), sonst ETag-Vergleich; Inhalte per multiget.
 * Termine: _SYNC_ID = Adresse der .ics, SYNC_DATA1 = ETag, SYNC_DATA2 = UID, SYNC_DATA4 = SEQUENCE.
 * Abweichende Einzeltermine (RECURRENCE-ID) sind eigene Zeilen mit ORIGINAL_ID/ORIGINAL_SYNC_ID/
 * ORIGINAL_INSTANCE_TIME; EXDATE/RDATE gehen 1:1 nach Android. Erinnerungen (VALARM <-> Reminders),
 * Teilnehmer (ATTENDEE/ORGANIZER <-> Attendees), Status, Frei/Belegt, Vertraulichkeit, Farbe (COLOR),
 * und alles andere unveraendert (ExtendedProperties). Einladungen verschickt die Nextcloud (iMIP).
 * Nur-Lesen-Kalender (Freigaben ohne Schreibrecht): lokale Aenderungen werden zurueckgesetzt.
 *
 * Braucht READ_CALENDAR/WRITE_CALENDAR. Blockiert - im Hintergrund aufrufen.
 */
class KalenderAbgleich(private val c: Context, konto: Konto, appName: String, heim: String? = null) {
    companion object {
        private const val TAG = "RosenwegKalender"
        const val KONTO_NAME = "Rosenweg-Nextcloud"
        const val KONTO_TYP = CalendarContract.ACCOUNT_TYPE_LOCAL
        /** Angehaengt an den Namen in Android, damit man die Nextcloud-Kalender erkennt. */
        const val ENDUNG = " (Nextcloud)"
        private const val ICS = "text/calendar; charset=utf-8"
        /** Hochzuladen: geaendert, geloescht, oder ein Haupttermin ohne Server-Adresse (neu in der App angelegt). */
        private const val HOCH = "${Events.DIRTY}=1 OR ${Events.DELETED}=1 OR " +
            "(${Events.ORIGINAL_ID} IS NULL AND (${Events._SYNC_ID} IS NULL OR ${Events._SYNC_ID} NOT LIKE 'http%'))"

        fun alsAdapter(u: Uri): Uri = u.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, KONTO_NAME)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, KONTO_TYP).build()

        private const val UNSERE = "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=?"
        private val UNSERE_ARGS = arrayOf(KONTO_NAME, KONTO_TYP)

        /** Ids der lokalen Nextcloud-Kalender (fuer die Auswahl in der App). */
        fun kalenderIds(c: Context): List<Long> = c.contentResolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID),
            UNSERE, UNSERE_ARGS, null)
            ?.use { cur -> generateSequence { if (cur.moveToNext()) cur.getLong(0) else null }.toList() } ?: emptyList()

        /** Ein Nextcloud-Kalender fuer die Verwaltung in der App. */
        data class Kalender(val id: Long, val name: String, val farbe: Int, val nurLesen: Boolean, val url: String)

        fun kalender(c: Context): List<Kalender> = c.contentResolver.query(Calendars.CONTENT_URI,
            arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.CALENDAR_COLOR, Calendars.CALENDAR_ACCESS_LEVEL, Calendars._SYNC_ID),
            UNSERE, UNSERE_ARGS, Calendars.CALENDAR_DISPLAY_NAME)?.use { cur ->
            buildList {
                while (cur.moveToNext()) add(Kalender(cur.getLong(0), cur.getString(1).orEmpty().removeSuffix(ENDUNG), cur.getInt(2),
                    cur.getInt(3) < Calendars.CAL_ACCESS_CONTRIBUTOR, cur.getString(4).orEmpty()))
            }
        } ?: emptyList()

        /**
         * Gibt es lokal etwas hochzuladen (geaenderte/geloeschte Termine, umbenannte/umgefaerbte
         * Kalender)? Fuer den Ausloeser "lokale Aenderung" - ohne Netz, billig.
         */
        fun lokalGeaendert(c: Context): Boolean {
            val ids = kalenderIds(c)
            if (ids.isEmpty()) return false
            val n = c.contentResolver.query(alsAdapter(Events.CONTENT_URI), arrayOf(Events._ID),
                "${Events.CALENDAR_ID} IN (${ids.joinToString(",")}) AND ($HOCH)", null, null)
                ?.use { it.count } ?: 0
            if (n > 0) return true
            return c.contentResolver.query(Calendars.CONTENT_URI,
                arrayOf(Calendars.CALENDAR_DISPLAY_NAME, Calendars.CALENDAR_COLOR, Calendars.CAL_SYNC4, Calendars.CAL_SYNC3),
                UNSERE, UNSERE_ARGS, null)?.use { cur ->
                var anders = false
                while (cur.moveToNext()) {
                    if ((cur.getString(2) != null && cur.getString(0) != cur.getString(2)) ||
                        (cur.getString(3) != null && cur.getInt(1).toString() != cur.getString(3))) anders = true
                }
                anders
            } ?: false
        }

        /** Abmelden: lokale Nextcloud-Kalender samt Terminen weg (die Daten bleiben auf dem Server). */
        fun entfernen(c: Context) {
            c.contentResolver.delete(alsAdapter(Calendars.CONTENT_URI), UNSERE, UNSERE_ARGS)
            c.contentResolver.delete(alsAdapter(Colors.CONTENT_URI), UNSERE, UNSERE_ARGS)
        }

        fun hex(farbe: Int) = String.format("#%06X", farbe and 0xFFFFFF)
    }

    data class Ergebnis(val kalender: Int, val hoch: Int, val herunter: Int, val geloescht: Int, val konflikte: Int = 0)

    private val dav = NextcloudDav(konto, appName, heim)
    private val cr = c.contentResolver
    private val protokoll = AbgleichProtokoll(c, NextcloudDav.Art.KALENDER)
    private val prefs = c.getSharedPreferences("rosenweg_dav", Context.MODE_PRIVATE)
    private val adresseSchluessel = "adresse:" + konto.benutzer
    private var farbSchluessel = mutableSetOf<String>()

    private class Lokal(val id: Long, val name: String, val farbe: Int, val token: String?, val ctag: String?, val farbeGesetzt: String?, val nameGesetzt: String?)

    private class Zeile(val id: Long, val etag: String, val dirty: Boolean)

    /** Abgleichen; der Stand (ok, Fehler, Anmeldung abgelaufen) geht nach [CloudStatus]. */
    fun abgleichen(): Ergebnis = try {
        abgleichenIntern().also { e -> CloudStatus.abgleich(c, true, "Kalender: ${e.kalender}, hoch ${e.hoch}, herunter ${e.herunter}" + if (e.konflikte > 0) ", Konflikte ${e.konflikte}" else "") }
    } catch (x: NextcloudFehler) {
        if (x.anmeldungNoetig) CloudStatus.nextcloud(c, "Anmeldung abgelaufen – „Nextcloud neu verbinden“")
        else CloudStatus.abgleich(c, false, "Kalender: Nextcloud ${x.code}")
        throw x
    } catch (x: java.io.IOException) {
        CloudStatus.abgleich(c, false, "Kalender: keine Verbindung")
        throw x
    }

    private fun abgleichenIntern(): Ergebnis {
        Log.i(TAG, "Abgleich Kalender beginnt")
        val server = dav.sammlungen(NextcloudDav.Art.KALENDER)
        val adresse = eigeneAdresse()
        farbenEinrichten()
        val lokal = lokaleKalender()
        // Kalender, die es auf dem Server nicht mehr gibt
        lokal.filterKeys { url -> server.none { it.url == url } }.values.forEach { k ->
            cr.delete(ContentUris.withAppendedId(alsAdapter(Calendars.CONTENT_URI), k.id), null, null)
        }
        var hoch = 0; var herunter = 0; var weg = 0; var konflikte = 0
        for (s in server) {
            val k = lokal[s.url] ?: anlegen(s)
            try {
                sammlungAbgleichen(k, s, adresse)
                val (h, nachholen, kf) = hochladen(k.id, s, adresse)
                hoch += h; konflikte += kf
                val (n, w) = herunterladen(k, s, nachholen)
                herunter += n; weg += w
            } catch (e: NextcloudFehler) {
                if (e.anmeldungNoetig) throw e
                Log.w(TAG, "${s.name}: ${e.message}")
            }
        }
        Log.i(TAG, "Kalender: ${server.size}, hoch $hoch, herunter $herunter, geloescht $weg, Konflikte $konflikte")
        return Ergebnis(server.size, hoch, herunter, weg, konflikte)
    }

    /** Eigene Adresse (mailto) fuer ORGANIZER und den eigenen Teilnahmestatus; einmal am Tag frisch. */
    private fun eigeneAdresse(): String? {
        val alt = prefs.getString(adresseSchluessel, null)
        if (alt != null && System.currentTimeMillis() - prefs.getLong("$adresseSchluessel:zeit", 0) < 86_400_000L) return alt.ifEmpty { null }
        val neu = dav.eigeneAdresse()
        prefs.edit().putString(adresseSchluessel, neu.orEmpty()).putLong("$adresseSchluessel:zeit", System.currentTimeMillis()).apply()
        return neu
    }

    // ─── Kalender ───────────────────────────────────────────────────────────

    private fun lokaleKalender(): Map<String, Lokal> = cr.query(Calendars.CONTENT_URI,
        arrayOf(Calendars._ID, Calendars._SYNC_ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.CALENDAR_COLOR,
            Calendars.CAL_SYNC1, Calendars.CAL_SYNC2, Calendars.CAL_SYNC3, Calendars.CAL_SYNC4),
        UNSERE, UNSERE_ARGS, null)?.use { cur ->
        buildMap {
            while (cur.moveToNext()) cur.getString(1)?.let {
                put(dav.absolut(it), Lokal(cur.getLong(0), cur.getString(2).orEmpty(), cur.getInt(3), cur.getString(4), cur.getString(5),
                    cur.getString(6), cur.getString(7)))
            }
        }
    } ?: emptyMap()

    private fun farbe(s: NextcloudDav.Sammlung): Int = try {
        android.graphics.Color.parseColor(s.farbe?.take(7) ?: "#1E3A8A")
    } catch (e: Exception) {
        android.graphics.Color.parseColor("#1E3A8A")
    }

    private fun anlegen(s: NextcloudDav.Sammlung): Lokal {
        val v = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, KONTO_NAME)
            put(Calendars.ACCOUNT_TYPE, KONTO_TYP)
            put(Calendars._SYNC_ID, s.url)
            put(Calendars.NAME, s.name)
            put(Calendars.CALENDAR_DISPLAY_NAME, s.name + ENDUNG)
            put(Calendars.CALENDAR_COLOR, farbe(s))
            put(Calendars.OWNER_ACCOUNT, KONTO_NAME)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
        }
        val u = cr.insert(alsAdapter(Calendars.CONTENT_URI), v) ?: throw IllegalStateException("Kalender nicht angelegt")
        return Lokal(ContentUris.parseId(u), s.name + ENDUNG, farbe(s), null, null, null, null)
    }

    /** Umbenennen/Umfaerben aus der App hoch (PROPPATCH), dann die Angaben des Servers uebernehmen. */
    private fun sammlungAbgleichen(k: Lokal, s: NextcloudDav.Sammlung, adresse: String?) {
        var name = s.name
        var farbe = farbe(s)
        if (k.nameGesetzt != null && k.name != k.nameGesetzt && k.name.removeSuffix(ENDUNG).isNotBlank()) {
            try {
                dav.sammlungAendern(s.url, k.name.removeSuffix(ENDUNG).trim(), null); name = k.name.removeSuffix(ENDUNG).trim()
            } catch (e: NextcloudFehler) {
                if (e.anmeldungNoetig) throw e
                protokoll.eintragen(AbgleichProtokoll.Typ.FEHLER, s.name, "Umbenennen nicht möglich (${e.code})")
            }
        }
        if (k.farbeGesetzt != null && k.farbe.toString() != k.farbeGesetzt) {
            try {
                dav.sammlungAendern(s.url, null, hex(k.farbe)); farbe = k.farbe
            } catch (e: NextcloudFehler) {
                if (e.anmeldungNoetig) throw e
                protokoll.eintragen(AbgleichProtokoll.Typ.FEHLER, s.name, "Farbe ändern nicht möglich (${e.code})")
            }
        }
        val v = ContentValues().apply {
            put(Calendars.NAME, name)
            put(Calendars.CALENDAR_DISPLAY_NAME, name + ENDUNG)
            put(Calendars.CALENDAR_COLOR, farbe)
            put(Calendars.CAL_SYNC3, farbe.toString())
            put(Calendars.CAL_SYNC4, name + ENDUNG)
            put(Calendars.CALENDAR_ACCESS_LEVEL, if (s.nurLesen) Calendars.CAL_ACCESS_READ else Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, adresse ?: KONTO_NAME)
            put(Calendars.ALLOWED_REMINDERS, "${Reminders.METHOD_DEFAULT},${Reminders.METHOD_ALERT},${Reminders.METHOD_EMAIL}")
            put(Calendars.ALLOWED_AVAILABILITY, "${Events.AVAILABILITY_BUSY},${Events.AVAILABILITY_FREE}")
            put(Calendars.ALLOWED_ATTENDEE_TYPES, "${Attendees.TYPE_NONE},${Attendees.TYPE_OPTIONAL},${Attendees.TYPE_REQUIRED},${Attendees.TYPE_RESOURCE}")
            put(Calendars.MAX_REMINDERS, 8)
            put(Calendars.CAN_ORGANIZER_RESPOND, 1)
            put(Calendars.CAN_MODIFY_TIME_ZONE, 1)
        }
        cr.update(ContentUris.withAppendedId(alsAdapter(Calendars.CONTENT_URI), k.id), v, null, null)
    }

    /** Farbauswahl fuer einzelne Termine (Android Colors-Tabelle, Schluessel = CSS-Name wie COLOR). */
    private fun farbenEinrichten() {
        farbSchluessel = cr.query(Colors.CONTENT_URI, arrayOf(Colors.COLOR_KEY),
            "$UNSERE AND ${Colors.COLOR_TYPE}=${Colors.TYPE_EVENT}", UNSERE_ARGS, null)?.use { cur ->
            generateSequence { if (cur.moveToNext()) cur.getString(0) else null }.toMutableSet()
        } ?: mutableSetOf()
        Farben.auswahl.forEach { farbeEintragen(it) }
    }

    private fun farbeEintragen(name: String) {
        if (name in farbSchluessel) return
        val rgb = Farben.css[name] ?: return
        try {
            cr.insert(alsAdapter(Colors.CONTENT_URI), ContentValues().apply {
                put(Colors.ACCOUNT_NAME, KONTO_NAME)
                put(Colors.ACCOUNT_TYPE, KONTO_TYP)
                put(Colors.COLOR_TYPE, Colors.TYPE_EVENT)
                put(Colors.COLOR_KEY, name)
                put(Colors.COLOR, rgb or 0xFF000000.toInt())
            })
            farbSchluessel.add(name)
        } catch (e: Exception) {
            Log.w(TAG, "Farbe $name: ${e.message}")
        }
    }

    // ─── Kalender anlegen, aendern, loeschen (aus der App) ────────────────────

    /** Neuer Kalender in der Nextcloud; danach abgleichen. Rueckgabe: lokale Id. */
    fun kalenderAnlegen(name: String, farbe: Int): Long? {
        val url = dav.kalenderAnlegen(name.trim(), hex(farbe))
        abgleichen()
        return lokaleKalender()[url]?.id
    }

    fun kalenderAendern(id: Long, name: String, farbe: Int) {
        val k = kalender(c).firstOrNull { it.id == id } ?: return
        dav.sammlungAendern(k.url, name.trim(), hex(farbe))
        cr.update(ContentUris.withAppendedId(alsAdapter(Calendars.CONTENT_URI), id), ContentValues().apply {
            put(Calendars.NAME, name.trim())
            put(Calendars.CALENDAR_DISPLAY_NAME, name.trim() + ENDUNG)
            put(Calendars.CALENDAR_COLOR, farbe)
            put(Calendars.CAL_SYNC3, farbe.toString())
            put(Calendars.CAL_SYNC4, name.trim() + ENDUNG)
        }, null, null)
    }

    /** Kalender in der Nextcloud loeschen (bei Freigaben: Freigabe beenden) und lokal entfernen. */
    fun kalenderLoeschen(id: Long) {
        val k = kalender(c).firstOrNull { it.id == id } ?: return
        dav.sammlungLoeschen(k.url)
        cr.delete(ContentUris.withAppendedId(alsAdapter(Calendars.CONTENT_URI), id), null, null)
    }

    /** Verworfene lokale Fassung aus dem Protokoll in die Nextcloud schreiben (ueberschreibt), dann abgleichen. */
    fun wiederherstellen(e: AbgleichProtokoll.Eintrag): Boolean {
        val href = e.href ?: return false
        val daten = e.daten ?: return false
        dav.schreiben(href, "", daten, ICS) ?: return false
        protokoll.entfernen(e.id)
        // sicher neu holen: Token des Kalenders vergessen
        cr.update(alsAdapter(Calendars.CONTENT_URI), ContentValues().apply { putNull(Calendars.CAL_SYNC1); putNull(Calendars.CAL_SYNC2) },
            UNSERE, UNSERE_ARGS)
        abgleichen()
        return true
    }

    // ─── Zeilen lesen ───────────────────────────────────────────────────────

    private fun werte(cur: Cursor): Map<String, Any?> = (0 until cur.columnCount).associate { i ->
        cur.getColumnName(i) to when (cur.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> cur.getLong(i)
            Cursor.FIELD_TYPE_FLOAT -> cur.getDouble(i)
            Cursor.FIELD_TYPE_BLOB -> null
            else -> cur.getString(i)
        }
    }

    private fun ereignisse(auswahl: String, args: Array<String>): List<Map<String, Any?>> =
        cr.query(alsAdapter(Events.CONTENT_URI), null, auswahl, args, null)?.use { cur ->
            buildList { while (cur.moveToNext()) add(werte(cur)) }
        } ?: emptyList()

    private fun erinnerungen(id: Long): List<Pair<Int, Int>> = cr.query(Reminders.CONTENT_URI, arrayOf(Reminders.MINUTES, Reminders.METHOD),
        "${Reminders.EVENT_ID}=?", arrayOf(id.toString()), null)?.use { cur ->
        buildList { while (cur.moveToNext()) add(cur.getInt(0) to cur.getInt(1)) }
    } ?: emptyList()

    private fun teilnehmer(id: Long): List<Map<String, Any?>> = cr.query(Attendees.CONTENT_URI,
        arrayOf(Attendees.ATTENDEE_EMAIL, Attendees.ATTENDEE_NAME, Attendees.ATTENDEE_RELATIONSHIP, Attendees.ATTENDEE_TYPE, Attendees.ATTENDEE_STATUS),
        "${Attendees.EVENT_ID}=?", arrayOf(id.toString()), null)?.use { cur ->
        buildList { while (cur.moveToNext()) add(werte(cur)) }
    } ?: emptyList()

    private fun unbekannt(id: Long): String? = cr.query(alsAdapter(ExtendedProperties.CONTENT_URI), arrayOf(ExtendedProperties.VALUE),
        "${ExtendedProperties.EVENT_ID}=? AND ${ExtendedProperties.NAME}=?", arrayOf(id.toString(), KalenderAbbildung.UNBEKANNT), null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun termin(w: Map<String, Any?>): VTermin {
        val id = (w[Events._ID] as Number).toLong()
        return KalenderAbbildung.vonAndroid(w, erinnerungen(id), teilnehmer(id), unbekannt(id))
    }

    private fun lng(v: Any?) = (v as? Number)?.toLong()

    // ─── hoch ───────────────────────────────────────────────────────────────

    /** -> (hochgeladen, neu zu holende Adressen, Konflikte) */
    private fun hochladen(kalender: Long, s: NextcloudDav.Sammlung, adresse: String?): Triple<Int, Set<String>, Int> {
        val geaendert = ereignisse("${Events.CALENDAR_ID}=? AND ($HOCH)", arrayOf(kalender.toString()))
        if (geaendert.isEmpty()) return Triple(0, emptySet(), 0)
        val hauptIds = geaendert.map { lng(it[Events.ORIGINAL_ID]) ?: lng(it[Events._ID])!! }.distinct()
        val nachholen = mutableSetOf<String>()
        var n = 0; var konflikte = 0
        for (hid in hauptIds) {
            val m = ereignisse("${Events._ID}=?", arrayOf(hid.toString())).firstOrNull() ?: continue
            // nur eine echte Adresse in diesem Kalender; sonst ist der Termin neu (UID + ".ics", If-None-Match)
            val href = NextcloudDav.inSammlung(m[Events._SYNC_ID] as? String, s.url)
            val etag = m[Events.SYNC_DATA1] as? String
            val titel = (m[Events.TITLE] as? String).orEmpty()
            try {
                if (lng(m[Events.DELETED]) == 1L) {
                    if (href != null && !dav.loeschen(href, etag)) {
                        protokoll.eintragen(AbgleichProtokoll.Typ.KONFLIKT, titel,
                            "Hier gelöscht, in der Nextcloud inzwischen geändert – der Termin bleibt.", href, null)
                        nachholen.add(href); konflikte++
                    }
                    lokalLoeschen(hid); n++
                    continue
                }
                if (s.nurLesen) {
                    protokoll.eintragen(AbgleichProtokoll.Typ.NUR_LESEN, titel,
                        "Der Kalender \"${s.name}\" ist nur lesbar – die Änderung wurde zurückgesetzt.", href, null)
                    lokalLoeschen(hid)
                    href?.let { nachholen.add(it) }
                    continue
                }
                val ausnahmenZeilen = ereignisse("${Events.ORIGINAL_ID}=?", arrayOf(hid.toString()))
                var haupt = termin(m)
                val geaenderteZeit = ausnahmenZeilen.filter { lng(it[Events.DELETED]) != 1L && lng(it[Events.STATUS]) != Events.STATUS_CANCELED.toLong() }
                val ausnahmen = geaenderteZeit.map { termin(it).copy(uid = haupt.uid) }.filter { it.ausnahmeVon != null }
                // abgesagte/geloeschte Einzeltermine -> EXDATE (ausser es gibt dafuer eine geaenderte Fassung)
                val weg = ausnahmenZeilen.filter { lng(it[Events.DELETED]) == 1L || lng(it[Events.STATUS]) == Events.STATUS_CANCELED.toLong() }
                    .mapNotNull { lng(it[Events.ORIGINAL_INSTANCE_TIME]) }.filter { t -> ausnahmen.none { it.ausnahmeVon == t } }
                val ichOrganisiere = haupt.organisator == null || haupt.organisator?.email.equals(adresse, true)
                haupt = haupt.copy(exdates = (haupt.exdates + weg).distinct(),
                    sequenz = if (href != null && ichOrganisiere) haupt.sequenz + 1 else haupt.sequenz)
                val ics = ICal.schreiben(VKalenderObjekt(haupt, ausnahmen), adresse)
                val ziel = href ?: dav.absolut(s.url + NextcloudDav.dateiname(haupt.uid, ".ics"))
                val neu = dav.schreiben(ziel, if (href == null) null else etag.orEmpty(), ics, ICS)
                if (neu == null) {
                    protokoll.eintragen(AbgleichProtokoll.Typ.KONFLIKT, titel,
                        "In der Nextcloud inzwischen geändert – die Fassung der Nextcloud gilt.", ziel, ics)
                    lokalLoeschen(hid)
                    nachholen.add(ziel); konflikte++
                    continue
                }
                cr.update(ContentUris.withAppendedId(alsAdapter(Events.CONTENT_URI), hid), ContentValues().apply {
                    put(Events._SYNC_ID, ziel)
                    put(Events.SYNC_DATA1, neu)
                    put(Events.SYNC_DATA2, haupt.uid)
                    put(Events.UID_2445, haupt.uid)
                    put(KalenderAbbildung.SEQUENZ, haupt.sequenz.toString())
                    put(Events.DIRTY, 0)
                }, null, null)
                for (a in ausnahmenZeilen) {
                    val aid = lng(a[Events._ID])!!
                    val u = ContentUris.withAppendedId(alsAdapter(Events.CONTENT_URI), aid)
                    if (lng(a[Events.DELETED]) == 1L) cr.delete(u, null, null)
                    else cr.update(u, ContentValues().apply { put(Events.ORIGINAL_SYNC_ID, ziel); put(Events.DIRTY, 0) }, null, null)
                }
                if (neu.isEmpty()) nachholen.add(ziel)   // Server hat angepasst: seine Fassung holen
                n++
            } catch (e: NextcloudFehler) {
                if (e.anmeldungNoetig) throw e
                Log.w(TAG, "hoch \"$titel\": ${e.code}")
                if (e.code in 400..499 && e.code != 408 && e.code != 429) {
                    // vom Server abgelehnt (z.B. ungueltig): nicht endlos wiederholen
                    protokoll.eintragen(AbgleichProtokoll.Typ.FEHLER, titel, "Von der Nextcloud abgelehnt (${e.code}).", href, null)
                    cr.update(ContentUris.withAppendedId(alsAdapter(Events.CONTENT_URI), hid),
                        ContentValues().apply { put(Events.DIRTY, 0); put(Events.SYNC_DATA1, "") }, null, null)
                    href?.let { nachholen.add(it) }
                }
            }
        }
        return Triple(n, nachholen, konflikte)
    }

    private fun lokalLoeschen(id: Long) {
        cr.delete(alsAdapter(Events.CONTENT_URI), "${Events.ORIGINAL_ID}=?", arrayOf(id.toString()))
        cr.delete(ContentUris.withAppendedId(alsAdapter(Events.CONTENT_URI), id), null, null)
    }

    // ─── herunter ───────────────────────────────────────────────────────────

    /** -> (neu/geaendert, geloescht) */
    private fun herunterladen(k: Lokal, s: NextcloudDav.Sammlung, nachholen: Set<String>): Pair<Int, Int> {
        if (nachholen.isEmpty()) {
            if (s.syncToken.isNotEmpty() && s.syncToken == k.token) return 0 to 0
            if (s.syncToken.isEmpty() && s.ctag.isNotEmpty() && s.ctag == k.ctag) return 0 to 0
        }
        val lokal = mutableMapOf<String, Zeile>()
        cr.query(alsAdapter(Events.CONTENT_URI), arrayOf(Events._ID, Events._SYNC_ID, Events.SYNC_DATA1, Events.DIRTY, Events.DELETED),
            "${Events.CALENDAR_ID}=? AND ${Events.ORIGINAL_ID} IS NULL", arrayOf(k.id.toString()), null)?.use { cur ->
            while (cur.moveToNext()) {
                val href = NextcloudDav.inSammlung(cur.getString(1), s.url) ?: continue
                lokal[href] = Zeile(cur.getLong(0), cur.getString(2).orEmpty(), cur.getInt(3) == 1 || cur.getInt(4) == 1)
            }
        }
        var token: String? = null
        var geaendert: Map<String, String>
        var geloescht: Set<String>
        try {
            if (s.syncToken.isEmpty()) throw NextcloudDav.TokenUngueltig(0)
            val a = dav.aenderungen(s.url, k.token.orEmpty())
            geaendert = a.geaendert
            // erster Abgleich (leeres Token): vollstaendige Liste
            geloescht = if (k.token.isNullOrEmpty()) lokal.keys - a.geaendert.keys else a.geloescht
            token = a.token
        } catch (e: NextcloudDav.TokenUngueltig) {
            val alle = dav.etags(s.url)
            geaendert = alle
            geloescht = lokal.keys - alle.keys
            token = s.syncToken.ifEmpty { null }
        }
        val holen = (geaendert.filter { (h, e) -> lokal[h]?.let { it.etag != e || e.isEmpty() } ?: true }.keys + nachholen)
            .filter { lokal[it]?.dirty != true }
        var n = 0
        val bekommen = mutableSetOf<String>()
        for (o in dav.holen(s.url, NextcloudDav.Art.KALENDER, holen)) {
            bekommen.add(o.href)
            if (einspielen(k.id, o, lokal[o.href]?.id)) n++
        }
        var w = 0
        for (h in geloescht + nachholen.filter { it !in bekommen }) {
            val z = lokal[h] ?: continue
            if (z.dirty) continue
            lokalLoeschen(z.id); w++
        }
        cr.update(ContentUris.withAppendedId(alsAdapter(Calendars.CONTENT_URI), k.id), ContentValues().apply {
            if (token != null) put(Calendars.CAL_SYNC1, token) else putNull(Calendars.CAL_SYNC1)
            put(Calendars.CAL_SYNC2, s.ctag)
        }, null, null)
        return n to w
    }

    private fun inhalt(werte: Map<String, Any?>) = ContentValues().apply {
        for ((k, v) in werte) when (v) {
            null -> putNull(k)
            is Int -> put(k, v)
            is Long -> put(k, v)
            is String -> put(k, v)
            else -> put(k, v.toString())
        }
    }

    /** Eine .ics in Android ablegen (neu oder an Ort und Stelle aendern). false = kein Termin darin. */
    private fun einspielen(kalender: Long, o: NextcloudDav.Objekt, vorhanden: Long?): Boolean {
        val obj = try { ICal.objekt(o.daten) } catch (e: Exception) { null }
        if (obj == null) {
            // z.B. eine Aufgabe (VTODO) in einem gemischten Kalender: hier nicht zeigen
            if (vorhanden != null) lokalLoeschen(vorhanden)
            return false
        }
        obj.haupt.farbe?.let { farbeEintragen(it) }
        val at = KalenderAbbildung.nachAndroid(obj.haupt, null, farbSchluessel)
        val v = inhalt(at.werte).apply {
            put(Events.CALENDAR_ID, kalender)
            put(Events._SYNC_ID, o.href)
            put(Events.SYNC_DATA1, o.etag)
            put(Events.SYNC_DATA2, obj.haupt.uid)
            put(Events.DIRTY, 0)
        }
        val id = if (vorhanden != null) {
            cr.update(ContentUris.withAppendedId(alsAdapter(Events.CONTENT_URI), vorhanden), v, null, null)
            vorhanden
        } else ContentUris.parseId(cr.insert(alsAdapter(Events.CONTENT_URI), v) ?: return false)
        nebenzeilen(id, at)

        // Einzeltermine: an Ort und Stelle (gleiche ORIGINAL_INSTANCE_TIME), damit die Ids bleiben
        val alt = mutableMapOf<Long, Long>()
        cr.query(alsAdapter(Events.CONTENT_URI), arrayOf(Events._ID, Events.ORIGINAL_INSTANCE_TIME),
            "${Events.ORIGINAL_ID}=?", arrayOf(id.toString()), null)?.use { cur ->
            while (cur.moveToNext()) alt[cur.getLong(1)] = cur.getLong(0)
        }
        for (a in obj.ausnahmen) {
            a.farbe?.let { farbeEintragen(it) }
            val aat = KalenderAbbildung.nachAndroid(a, obj.haupt, farbSchluessel)
            val av = inhalt(aat.werte).apply {
                put(Events.CALENDAR_ID, kalender)
                put(Events.ORIGINAL_ID, id)
                put(Events.ORIGINAL_SYNC_ID, o.href)
                put(Events.SYNC_DATA2, obj.haupt.uid)
                put(Events.DIRTY, 0)
            }
            val aid = alt.remove(a.ausnahmeVon)?.also {
                cr.update(ContentUris.withAppendedId(alsAdapter(Events.CONTENT_URI), it), av, null, null)
            } ?: cr.insert(alsAdapter(Events.CONTENT_URI), av)?.let { ContentUris.parseId(it) } ?: continue
            nebenzeilen(aid, aat)
        }
        alt.values.forEach { cr.delete(ContentUris.withAppendedId(alsAdapter(Events.CONTENT_URI), it), null, null) }
        return true
    }

    /** Erinnerungen, Teilnehmer und nicht abgebildete Zeilen eines Termins ersetzen. */
    private fun nebenzeilen(id: Long, at: KalenderAbbildung.AndroidTermin) {
        val arg = arrayOf(id.toString())
        cr.delete(alsAdapter(Reminders.CONTENT_URI), "${Reminders.EVENT_ID}=?", arg)
        at.erinnerungen.forEach { (min, methode) ->
            cr.insert(alsAdapter(Reminders.CONTENT_URI), ContentValues().apply {
                put(Reminders.EVENT_ID, id); put(Reminders.MINUTES, min); put(Reminders.METHOD, methode)
            })
        }
        cr.delete(alsAdapter(Attendees.CONTENT_URI), "${Attendees.EVENT_ID}=?", arg)
        at.teilnehmer.forEach { t -> cr.insert(alsAdapter(Attendees.CONTENT_URI), inhalt(t).apply { put(Attendees.EVENT_ID, id) }) }
        cr.delete(alsAdapter(ExtendedProperties.CONTENT_URI), "${ExtendedProperties.EVENT_ID}=? AND ${ExtendedProperties.NAME}=?",
            arrayOf(id.toString(), KalenderAbbildung.UNBEKANNT))
        at.unbekannt?.let { u ->
            cr.insert(alsAdapter(ExtendedProperties.CONTENT_URI), ContentValues().apply {
                put(ExtendedProperties.EVENT_ID, id); put(ExtendedProperties.NAME, KalenderAbbildung.UNBEKANNT); put(ExtendedProperties.VALUE, u)
            })
        }
    }
}
