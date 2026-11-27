package ch.rosenweg4303.cloud.dav

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts
import android.util.Log
import ch.rosenweg4303.cloud.CloudStatus
import ch.rosenweg4303.cloud.Konto
import ch.rosenweg4303.cloud.NextcloudFehler
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Adressbuecher der Rosenweg-Nextcloud im Android-Adressbuch (ContactsContract), in beide
 * Richtungen, ohne DAVx5 (Stefan: die Apps sprechen CardDAV selbst). Muster wie das Telefonbuch
 * von Rosenweg Geraet (Kontakte.java): eigenes Konto, Zeilen als Sync-Adapter. Andere Konten
 * (z.B. das Rosenweg-Verzeichnis der geteilten Telefone) fasst der Abgleich nie an.
 *
 * - Konto "Rosenweg-Nextcloud" vom Typ [kontoTyp]. Die App muss dafuer einen
 *   AccountAuthenticator mit genau diesem Typ deklarieren (sonst verweigert Android das Konto).
 * - Zuerst hoch: geaenderte (DIRTY) und geloeschte (DELETED) Kontakte per PUT/DELETE mit If-Match.
 *   412: der Server gewinnt, die lokale Fassung kommt ins [AbgleichProtokoll]. Neue Kontakte landen
 *   im ersten beschreibbaren Buch. Nur-Lesen-Buecher (z.B. das geteilte Rosenweg-Verzeichnis):
 *   Kontakte sind in Android schreibgeschuetzt; trotzdem Geaendertes wird zurueckgesetzt.
 * - Dann herunter, inkrementell: WebDAV-Sync (sync-token) je Buch, sonst ETag-Vergleich.
 * RawContacts: SOURCE_ID = Adresse der .vcf, SYNC1 = ETag, SYNC2 = UID, SYNC3 = Adresse des Buchs.
 * Abgebildet (vCard 3.0/4.0): Name (alle Teile), Spitzname, Firma/Abteilung/Position, alle
 * Telefonnummern/E-Mail-Adressen/Adressen/Webseiten mit Typ und eigener Bezeichnung, Geburtstag,
 * Jahrestag, Notiz, Foto und Gruppen (Nextcloud: CATEGORIES <-> Android-Gruppen). Alles andere geht
 * unveraendert zurueck. Gruppen-vCards (KIND:group) werden nicht als Kontakt gezeigt.
 *
 * Braucht READ_CONTACTS/WRITE_CONTACTS. Blockiert - im Hintergrund aufrufen.
 */
class KontakteAbgleich(private val c: Context, konto: Konto, appName: String, private val kontoTyp: String, heim: String? = null) {
    companion object {
        private const val TAG = "RosenwegKontakte"
        const val KONTO_NAME = "Rosenweg-Nextcloud"
        private const val VCF = "text/vcard; charset=utf-8"
        private const val KATEGORIE = "kategorie:"
        private const val FOTO_MAX = 400_000

        /** Abmelden: Konto samt Kontakten weg (die Daten bleiben auf dem Server). */
        fun entfernen(c: Context, kontoTyp: String) {
            val am = AccountManager.get(c)
            am.getAccountsByType(kontoTyp).forEach { am.removeAccountExplicitly(it) }
            val p = c.getSharedPreferences("rosenweg_dav", Context.MODE_PRIVATE)
            p.edit().apply { p.all.keys.filter { it.startsWith("buch:") }.forEach { remove(it) } }.apply()
        }

        /** Gibt es lokal etwas hochzuladen? Ohne Netz, billig (Ausloeser "lokale Aenderung"). */
        fun lokalGeaendert(c: Context, kontoTyp: String): Boolean {
            val wo = "${RawContacts.ACCOUNT_TYPE}=? AND ${RawContacts.ACCOUNT_NAME}=? AND (${RawContacts.DIRTY}=1 OR ${RawContacts.DELETED}=1)"
            val n = c.contentResolver.query(RawContacts.CONTENT_URI.buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build(),
                arrayOf(RawContacts._ID), wo, arrayOf(kontoTyp, KONTO_NAME), null)?.use { it.count } ?: 0
            if (n > 0) return true
            return (c.contentResolver.query(Groups.CONTENT_URI.buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build(),
                arrayOf(Groups._ID), "${Groups.ACCOUNT_TYPE}=? AND ${Groups.ACCOUNT_NAME}=? AND (${Groups.DIRTY}=1 OR ${Groups.DELETED}=1)",
                arrayOf(kontoTyp, KONTO_NAME), null)?.use { it.count } ?: 0) > 0
        }
    }

    data class Ergebnis(val buecher: Int, val hoch: Int, val herunter: Int, val geloescht: Int, val konflikte: Int = 0)

    private val dav = NextcloudDav(konto, appName, heim)
    private val cr = c.contentResolver
    private val protokoll = AbgleichProtokoll(c, NextcloudDav.Art.ADRESSEN)
    private val prefs = c.getSharedPreferences("rosenweg_dav", Context.MODE_PRIVATE)

    private fun alsAdapter(u: Uri): Uri = u.buildUpon()
        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(RawContacts.ACCOUNT_NAME, KONTO_NAME)
        .appendQueryParameter(RawContacts.ACCOUNT_TYPE, kontoTyp).build()

    private val unser = "${RawContacts.ACCOUNT_TYPE}=? AND ${RawContacts.ACCOUNT_NAME}=?"
    private val unserArgs get() = arrayOf(kontoTyp, KONTO_NAME)

    private fun kontoAnlegen() {
        val am = AccountManager.get(c)
        if (am.getAccountsByType(kontoTyp).isNotEmpty()) return
        am.addAccountExplicitly(Account(KONTO_NAME, kontoTyp), null, null)
        // Kontakte ohne Gruppe sichtbar (sonst blenden Telefon- und Kontakte-Apps das Konto aus)
        cr.insert(ContactsContract.Settings.CONTENT_URI, ContentValues().apply {
            put(ContactsContract.Settings.ACCOUNT_NAME, KONTO_NAME)
            put(ContactsContract.Settings.ACCOUNT_TYPE, kontoTyp)
            put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1)
        })
    }

    private class Zeile(val id: Long, val etag: String, val dirty: Boolean)

    /** Abgleichen; der Stand (ok, Fehler, Anmeldung abgelaufen) geht nach [CloudStatus]. */
    fun abgleichen(): Ergebnis = try {
        abgleichenIntern().also { e -> CloudStatus.abgleich(c, true, "Adressbücher: ${e.buecher}, hoch ${e.hoch}, herunter ${e.herunter}" + if (e.konflikte > 0) ", Konflikte ${e.konflikte}" else "") }
    } catch (x: NextcloudFehler) {
        if (x.anmeldungNoetig) CloudStatus.nextcloud(c, "Anmeldung abgelaufen – „Nextcloud neu verbinden“")
        else CloudStatus.abgleich(c, false, "Kontakte: Nextcloud ${x.code}")
        throw x
    } catch (x: java.io.IOException) {
        CloudStatus.abgleich(c, false, "Kontakte: keine Verbindung")
        throw x
    }

    private fun abgleichenIntern(): Ergebnis {
        Log.i(TAG, "Abgleich Adressbuecher beginnt")
        kontoAnlegen()
        val buecher = dav.sammlungen(NextcloudDav.Art.ADRESSEN)
        val (hoch, nachholen, konflikte) = hochladen(buecher)
        var n = 0; var weg = 0
        for (b in buecher) {
            try {
                val (a, w) = herunterladen(b, nachholen.filter { it.startsWith(b.url) }.toSet())
                n += a; weg += w
            } catch (e: NextcloudFehler) {
                if (e.anmeldungNoetig) throw e
                Log.w(TAG, "${b.name}: ${e.message}")
            }
        }
        // Kontakte aus Buechern, die es nicht mehr gibt
        val urls = buecher.map { it.url }.toSet()
        cr.query(alsAdapter(RawContacts.CONTENT_URI), arrayOf(RawContacts._ID, RawContacts.SYNC3), "$unser AND ${RawContacts.SYNC3} IS NOT NULL",
            unserArgs, null)?.use { cur ->
            while (cur.moveToNext()) if (dav.absolut(cur.getString(1)) !in urls) {
                cr.delete(ContentUris.withAppendedId(alsAdapter(RawContacts.CONTENT_URI), cur.getLong(0)), null, null); weg++
            }
        }
        gruppenAufraeumen()
        Log.i(TAG, "Adressbuecher: ${buecher.size}, hoch $hoch, herunter $n, geloescht $weg, Konflikte $konflikte")
        return Ergebnis(buecher.size, hoch, n, weg, konflikte)
    }

    /** Verworfene lokale Fassung aus dem Protokoll in die Nextcloud schreiben (ueberschreibt), dann abgleichen. */
    fun wiederherstellen(e: AbgleichProtokoll.Eintrag): Boolean {
        val href = e.href ?: return false
        val daten = e.daten ?: return false
        dav.schreiben(href, "", daten, VCF) ?: return false
        protokoll.entfernen(e.id)
        prefs.edit().apply { prefs.all.keys.filter { it.startsWith("buch:") }.forEach { remove(it) } }.apply()
        abgleichen()
        return true
    }

    // ─── Gruppen (CATEGORIES) ────────────────────────────────────────────────

    /** Titel -> Gruppen-Id (ohne geloeschte). */
    private fun gruppen(): MutableMap<String, Long> = cr.query(alsAdapter(Groups.CONTENT_URI), arrayOf(Groups._ID, Groups.TITLE),
        "${Groups.ACCOUNT_TYPE}=? AND ${Groups.ACCOUNT_NAME}=? AND ${Groups.DELETED}=0", unserArgs, null)?.use { cur ->
        val m = mutableMapOf<String, Long>()
        while (cur.moveToNext()) cur.getString(1)?.let { m.putIfAbsent(it, cur.getLong(0)) }
        m
    } ?: mutableMapOf()

    private fun gruppeHolen(titel: String, bekannt: MutableMap<String, Long>): Long = bekannt[titel] ?: run {
        val u = cr.insert(alsAdapter(Groups.CONTENT_URI), ContentValues().apply {
            put(Groups.ACCOUNT_NAME, KONTO_NAME); put(Groups.ACCOUNT_TYPE, kontoTyp)
            put(Groups.TITLE, titel); put(Groups.SOURCE_ID, KATEGORIE + titel)
            put(Groups.GROUP_VISIBLE, 1); put(Groups.SHOULD_SYNC, 1)
        })!!
        ContentUris.parseId(u).also { bekannt[titel] = it }
    }

    /** In der App umbenannte/geloeschte Gruppen: Mitglieder neu hochladen (CATEGORIES). */
    private fun gruppenAenderungen() {
        cr.query(alsAdapter(Groups.CONTENT_URI), arrayOf(Groups._ID, Groups.DELETED),
            "${Groups.ACCOUNT_TYPE}=? AND ${Groups.ACCOUNT_NAME}=? AND (${Groups.DIRTY}=1 OR ${Groups.DELETED}=1)", unserArgs, null)?.use { cur ->
            while (cur.moveToNext()) {
                val gid = cur.getLong(0)
                val mitglieder = cr.query(Data.CONTENT_URI, arrayOf(Data.RAW_CONTACT_ID),
                    "${Data.MIMETYPE}=? AND ${GroupMembership.GROUP_ROW_ID}=?", arrayOf(GroupMembership.CONTENT_ITEM_TYPE, gid.toString()), null)
                    ?.use { m -> generateSequence { if (m.moveToNext()) m.getLong(0) else null }.toList() } ?: emptyList()
                mitglieder.forEach { rid ->
                    cr.update(ContentUris.withAppendedId(alsAdapter(RawContacts.CONTENT_URI), rid), ContentValues().apply { put(RawContacts.DIRTY, 1) }, null, null)
                }
            }
        }
    }

    private fun gruppenAufraeumen() {
        cr.delete(alsAdapter(Groups.CONTENT_URI), "${Groups.ACCOUNT_TYPE}=? AND ${Groups.ACCOUNT_NAME}=? AND ${Groups.DELETED}=1", unserArgs)
        cr.update(alsAdapter(Groups.CONTENT_URI), ContentValues().apply { put(Groups.DIRTY, 0) },
            "${Groups.ACCOUNT_TYPE}=? AND ${Groups.ACCOUNT_NAME}=? AND ${Groups.DIRTY}=1", unserArgs)
        // von uns angelegte Gruppen, die keine Kategorie mehr ist (leer): weg
        cr.query(Groups.CONTENT_SUMMARY_URI, arrayOf(Groups._ID, Groups.SUMMARY_COUNT),
            "${Groups.ACCOUNT_TYPE}=? AND ${Groups.ACCOUNT_NAME}=? AND ${Groups.SOURCE_ID} LIKE ?", unserArgs + "$KATEGORIE%", null)?.use { cur ->
            while (cur.moveToNext()) if (cur.getInt(1) == 0) {
                cr.delete(ContentUris.withAppendedId(alsAdapter(Groups.CONTENT_URI), cur.getLong(0)), null, null)
            }
        }
    }

    // ─── hoch ───────────────────────────────────────────────────────────────

    /** -> (hochgeladen, neu zu holende Adressen, Konflikte) */
    private fun hochladen(buecher: List<NextcloudDav.Sammlung>): Triple<Int, Set<String>, Int> {
        gruppenAenderungen()
        val standard = buecher.firstOrNull { !it.nurLesen }
        val nachholen = mutableSetOf<String>()
        var n = 0; var konflikte = 0
        cr.query(alsAdapter(RawContacts.CONTENT_URI),
            arrayOf(RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.SYNC1, RawContacts.SYNC2, RawContacts.DELETED, RawContacts.SYNC3),
            "$unser AND (${RawContacts.DIRTY}=1 OR ${RawContacts.DELETED}=1)", unserArgs, null)?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getLong(0)
                val href = cur.getString(1)?.let { dav.absolut(it) }
                val etag = cur.getString(2)
                val zeile = ContentUris.withAppendedId(alsAdapter(RawContacts.CONTENT_URI), id)
                val buchUrl = cur.getString(5)?.let { dav.absolut(it) } ?: standard?.url
                val buch = buecher.firstOrNull { it.url == buchUrl }
                var name = ""
                try {
                    if (cur.getInt(4) == 1) {
                        if (href != null && !dav.loeschen(href, etag)) {
                            protokoll.eintragen(AbgleichProtokoll.Typ.KONFLIKT, "Kontakt", "Hier gelöscht, in der Nextcloud inzwischen geändert – der Kontakt bleibt.", href)
                            nachholen.add(href); konflikte++
                        }
                        cr.delete(zeile, null, null); n++
                        continue
                    }
                    val k = lesen(id, cur.getString(3)?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString())
                    name = k.anzeigename
                    if (buch == null || buch.nurLesen) {
                        protokoll.eintragen(AbgleichProtokoll.Typ.NUR_LESEN, name,
                            if (buch == null) "Kein beschreibbares Adressbuch – nicht gespeichert." else "Das Adressbuch \"${buch.name}\" ist nur lesbar – die Änderung wurde zurückgesetzt.",
                            href, VCard.schreiben(k))
                        if (href != null) {
                            cr.update(zeile, ContentValues().apply { put(RawContacts.SYNC1, ""); put(RawContacts.DIRTY, 0) }, null, null)
                            nachholen.add(href)
                        } else cr.delete(zeile, null, null)
                        continue
                    }
                    val vcf = VCard.schreiben(k)
                    val ziel = href ?: dav.absolut(buch.url + NextcloudDav.dateiname(k.uid, ".vcf"))
                    val neu = dav.schreiben(ziel, if (href == null) null else etag.orEmpty(), vcf, VCF)
                    if (neu == null) {
                        protokoll.eintragen(AbgleichProtokoll.Typ.KONFLIKT, name, "In der Nextcloud inzwischen geändert – die Fassung der Nextcloud gilt.", ziel, vcf)
                        cr.update(zeile, ContentValues().apply { put(RawContacts.SYNC1, ""); put(RawContacts.DIRTY, 0) }, null, null)
                        nachholen.add(ziel); konflikte++
                        continue
                    }
                    cr.update(zeile, ContentValues().apply {
                        put(RawContacts.SOURCE_ID, ziel)
                        put(RawContacts.SYNC1, neu)
                        put(RawContacts.SYNC2, k.uid)
                        put(RawContacts.SYNC3, buch.url)
                        put(RawContacts.DIRTY, 0)
                    }, null, null)
                    if (neu.isEmpty()) nachholen.add(ziel)
                    n++
                } catch (e: NextcloudFehler) {
                    if (e.anmeldungNoetig) throw e
                    Log.w(TAG, "hoch: ${e.code}")
                    if (e.code in 400..499 && e.code != 408 && e.code != 429) {
                        protokoll.eintragen(AbgleichProtokoll.Typ.FEHLER, name.ifEmpty { "Kontakt" }, "Von der Nextcloud abgelehnt (${e.code}).", href)
                        cr.update(zeile, ContentValues().apply { put(RawContacts.SYNC1, ""); put(RawContacts.DIRTY, 0) }, null, null)
                        href?.let { nachholen.add(it) }
                    }
                }
            }
        }
        return Triple(n, nachholen, konflikte)
    }

    private fun werte(cur: Cursor): Map<String, Any?> = (0 until cur.columnCount).associate { i ->
        cur.getColumnName(i) to when (cur.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> cur.getLong(i)
            Cursor.FIELD_TYPE_BLOB -> cur.getBlob(i)
            else -> cur.getString(i)
        }
    }

    /** Kontakt aus den Data-Zeilen eines RawContacts (Foto in voller Groesse, Gruppen als Kategorien). */
    private fun lesen(rawId: Long, uid: String): VKontakt {
        val zeilen = mutableListOf<KontakteAbbildung.Daten>()
        val gruppenIds = mutableListOf<Long>()
        val spalten = arrayOf(Data.MIMETYPE, Data.IS_PRIMARY) + (1..15).map { "data$it" }
        cr.query(Data.CONTENT_URI, spalten, "${Data.RAW_CONTACT_ID}=?", arrayOf(rawId.toString()), null)?.use { cur ->
            while (cur.moveToNext()) {
                val w = werte(cur)
                val mime = w[Data.MIMETYPE] as? String ?: continue
                if (mime == GroupMembership.CONTENT_ITEM_TYPE) (w[GroupMembership.GROUP_ROW_ID] as? Number)?.let { gruppenIds.add(it.toLong()) }
                else zeilen.add(KontakteAbbildung.Daten(mime, w))
            }
        }
        val titel = gruppen().entries.associate { (t, id) -> id to t }
        val foto = try {
            cr.openAssetFileDescriptor(Uri.withAppendedPath(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId),
                RawContacts.DisplayPhoto.CONTENT_DIRECTORY), "r")?.use { it.createInputStream().readBytes() }
        } catch (e: Exception) {
            null
        }
        return KontakteAbbildung.vonAndroid(uid, zeilen, gruppenIds.mapNotNull { titel[it] }, foto)
    }

    // ─── herunter ───────────────────────────────────────────────────────────

    /** -> (neu/geaendert, geloescht) */
    private fun herunterladen(b: NextcloudDav.Sammlung, nachholen: Set<String>): Pair<Int, Int> {
        val tokenSchluessel = "buch:${b.url}:token"
        val ctagSchluessel = "buch:${b.url}:ctag"
        val altToken = prefs.getString(tokenSchluessel, null)
        if (nachholen.isEmpty()) {
            if (b.syncToken.isNotEmpty() && b.syncToken == altToken) return 0 to 0
            if (b.syncToken.isEmpty() && b.ctag.isNotEmpty() && b.ctag == prefs.getString(ctagSchluessel, null)) return 0 to 0
        }
        val lokal = mutableMapOf<String, Zeile>()
        cr.query(alsAdapter(RawContacts.CONTENT_URI),
            arrayOf(RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.SYNC1, RawContacts.DIRTY, RawContacts.DELETED, RawContacts.SYNC3),
            unser, unserArgs, null)?.use { cur ->
            while (cur.moveToNext()) {
                val href = cur.getString(1)?.let { dav.absolut(it) } ?: continue
                val buch = cur.getString(5)?.let { dav.absolut(it) }
                if (buch != b.url && !(buch == null && href.startsWith(b.url))) continue
                lokal[href] = Zeile(cur.getLong(0), cur.getString(2).orEmpty(), cur.getInt(3) == 1 || cur.getInt(4) == 1)
            }
        }
        var token: String? = null
        var geaendert: Map<String, String>
        var geloescht: Set<String>
        try {
            if (b.syncToken.isEmpty()) throw NextcloudDav.TokenUngueltig(0)
            val a = dav.aenderungen(b.url, altToken.orEmpty())
            geaendert = a.geaendert
            geloescht = if (altToken.isNullOrEmpty()) lokal.keys - a.geaendert.keys else a.geloescht
            token = a.token
        } catch (e: NextcloudDav.TokenUngueltig) {
            val alle = dav.etags(b.url)
            geaendert = alle
            geloescht = lokal.keys - alle.keys
            token = b.syncToken.ifEmpty { null }
        }
        val holen = (geaendert.filter { (h, e) -> lokal[h]?.let { it.etag != e || e.isEmpty() } ?: true }.keys + nachholen)
            .filter { lokal[it]?.dirty != true }
        val gruppen = gruppen()
        var n = 0
        val bekommen = mutableSetOf<String>()
        for (o in dav.holen(b.url, NextcloudDav.Art.ADRESSEN, holen)) {
            bekommen.add(o.href)
            val k = VCard.lesen(o.daten)
            val vorhanden = lokal[o.href]?.id
            if (k == null || k.istGruppe) {
                vorhanden?.let { cr.delete(ContentUris.withAppendedId(alsAdapter(RawContacts.CONTENT_URI), it), null, null) }
                continue
            }
            try {
                einspielen(k, o, b, vorhanden, gruppen); n++
            } catch (e: Exception) {
                Log.w(TAG, "Kontakt ${o.href}: ${e.javaClass.simpleName} ${e.message}")
            }
        }
        var w = 0
        for (h in geloescht + nachholen.filter { it !in bekommen }) {
            val z = lokal[h] ?: continue
            if (z.dirty) continue
            cr.delete(ContentUris.withAppendedId(alsAdapter(RawContacts.CONTENT_URI), z.id), null, null); w++
        }
        prefs.edit().apply {
            if (token != null) putString(tokenSchluessel, token) else remove(tokenSchluessel)
            putString(ctagSchluessel, b.ctag)
        }.apply()
        return n to w
    }

    private fun einspielen(k: VKontakt, o: NextcloudDav.Objekt, b: NextcloudDav.Sammlung, vorhanden: Long?, gruppen: MutableMap<String, Long>) {
        val ops = ArrayList<ContentProviderOperation>()
        val rawWerte = ContentValues().apply {
            put(RawContacts.SOURCE_ID, o.href)
            put(RawContacts.SYNC1, o.etag)
            put(RawContacts.SYNC2, k.uid)
            put(RawContacts.SYNC3, b.url)
            put(RawContacts.RAW_CONTACT_IS_READ_ONLY, if (b.nurLesen) 1 else 0)
            put(RawContacts.DIRTY, 0)
        }
        val rueck: Int?
        if (vorhanden != null) {
            // an Ort und Stelle: RawContact (und damit Favorit, Klingelton, Verknuepfungen) bleibt
            ops.add(ContentProviderOperation.newUpdate(ContentUris.withAppendedId(alsAdapter(RawContacts.CONTENT_URI), vorhanden))
                .withValues(rawWerte).build())
            ops.add(ContentProviderOperation.newDelete(alsAdapter(Data.CONTENT_URI))
                .withSelection("${Data.RAW_CONTACT_ID}=?", arrayOf(vorhanden.toString())).build())
            rueck = null
        } else {
            ops.add(ContentProviderOperation.newInsert(alsAdapter(RawContacts.CONTENT_URI))
                .withValue(RawContacts.ACCOUNT_NAME, KONTO_NAME).withValue(RawContacts.ACCOUNT_TYPE, kontoTyp)
                .withValues(rawWerte).build())
            rueck = 0
        }
        fun daten(mime: String): ContentProviderOperation.Builder {
            val bld = ContentProviderOperation.newInsert(alsAdapter(Data.CONTENT_URI)).withValue(Data.MIMETYPE, mime)
            return if (rueck != null) bld.withValueBackReference(Data.RAW_CONTACT_ID, rueck) else bld.withValue(Data.RAW_CONTACT_ID, vorhanden)
        }
        var foto: ByteArray? = null
        for (d in KontakteAbbildung.nachAndroid(k)) {
            if (d.mime == Photo.CONTENT_ITEM_TYPE) { foto = d.werte[Photo.PHOTO] as? ByteArray; continue }
            val bld = daten(d.mime)
            d.werte.forEach { (s, v) -> bld.withValue(s, v) }
            ops.add(bld.build())
        }
        k.kategorien.forEach { t ->
            ops.add(daten(GroupMembership.CONTENT_ITEM_TYPE).withValue(GroupMembership.GROUP_ROW_ID, gruppeHolen(t, gruppen)).build())
        }
        val erg = cr.applyBatch(ContactsContract.AUTHORITY, ops)
        val rawId = vorhanden ?: ContentUris.parseId(erg[0].uri!!)
        // Foto einzeln (Binder-Grenze); der Provider legt daraus Vorschau und grosses Bild an
        foto?.let { bytes ->
            cr.insert(alsAdapter(Data.CONTENT_URI), ContentValues().apply {
                put(Data.RAW_CONTACT_ID, rawId); put(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE); put(Photo.PHOTO, verkleinern(bytes))
            })
        }
    }

    private fun verkleinern(b: ByteArray): ByteArray {
        if (b.size <= FOTO_MAX) return b
        return try {
            val bm = BitmapFactory.decodeByteArray(b, 0, b.size) ?: return b
            val f = 1024f / maxOf(bm.width, bm.height)
            val klein = if (f < 1f) Bitmap.createScaledBitmap(bm, (bm.width * f).toInt(), (bm.height * f).toInt(), true) else bm
            ByteArrayOutputStream().use { out -> klein.compress(Bitmap.CompressFormat.JPEG, 85, out); out.toByteArray() }
        } catch (e: Exception) {
            b
        }
    }
}
